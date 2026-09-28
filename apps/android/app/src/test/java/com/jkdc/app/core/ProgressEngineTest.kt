package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ProgressEngineTest {
    private val zone = ZoneId.of("UTC")
    private val course = Course(id = "en-foundations", languageID = "en", title = "Foundations", subtitle = "s",
        symbol = "graduationcap", description = "d", units = listOf(
        CourseUnit(id = "u1", title = "U1", subtitle = "", level = "A1", themes = emptyList(), lessons = listOf(
            lesson("l1"), lesson("l2"))),
        CourseUnit(id = "u2", title = "U2", subtitle = "", level = "A1", themes = emptyList(), lessons = listOf(
            lesson("l3"))))
    )
    private fun lesson(id: String) = Lesson(id, "T", "G", LessonIntro(emptyList(), emptyList()), emptyList(), "s",
        LessonPractice(emptyList()))

    private fun stats(lessons: List<LessonState>, completions: List<CompletionMark>,
                      sessions: List<SessionRecord> = emptyList(), now: Double = 20.0) =
        ProgressEngine.stats(sessions, lessons, completions, listOf(course), zone, now)

    @Test fun completionKeysPassSlugsThroughAndHashEverythingElse() {
        assertEquals("en-foundations-u1-to-be", ProgressEngine.completionKey("en-foundations-u1-to-be"))
        assertEquals("restaurant", ProgressEngine.completionKey("restaurant"))
        val hashed = ProgressEngine.completionKey("en|niño|greeting")
        assertEquals(16, hashed.length)
        assertTrue(hashed.all { it in "0123456789abcdef" })
        assertEquals(hashed, ProgressEngine.completionKey("en|niño|greeting"))
    }

    @Test fun lessonsDoneAndCoursePercentComeFromTheAttemptCounters() {
        val lessons = listOf(LessonState(lessonID = "l1", attempts = 1), LessonState(lessonID = "l3", attempts = 2))
        val result = stats(lessons, emptyList())
        assertEquals(2, result.lessonsDone)
        assertEquals(3, result.lessonsTotal)
        assertEquals(66, result.courses.single().percent)
    }

    @Test fun activityDaysUniteCompletionsAndVoiceSessionsForStreaks() {
        val day = 86400.0
        // Days 100, 101, 102 complete; day 104 completes again. A voice session on day 103 keeps the run going.
        val marks = listOf(100.0, 101.0, 102.0, 104.0).map { CompletionMark(ProgressEngine.LESSON, "l1", it * day + 400.0) }
        val session = SessionRecord(startedAt = 103 * day + 400.0).apply { voiceSeconds = 60.0 }
        val result = stats(emptyList(), marks, listOf(session), now = 105 * day + 400.0)
        assertEquals(5, result.activeStreakDays)
        assertEquals(5, result.longestStreakDays)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 1, 0),
            result.last30DaysActivities)
    }

    @Test fun kindCountersCountDistinctItemsPerFamily() {
        val marks = listOf(
            CompletionMark(ProgressEngine.CONVERSATION_THEME, "restaurant", 10.0),
            CompletionMark(ProgressEngine.CONVERSATION_THEME, "restaurant", 20.0),
            CompletionMark(ProgressEngine.CONVERSATION_THEME, "cafe", 30.0),
            CompletionMark(ProgressEngine.EXERCISE_SET, "l1", 40.0, 80),
            CompletionMark(ProgressEngine.LISTENING, "cafe-morning", 50.0, 60),
            CompletionMark(ProgressEngine.RECALL_ROUND, "recall", 60.0),
            CompletionMark(ProgressEngine.RECALL_ROUND, "recall", 70.0),
            CompletionMark(ProgressEngine.WORDS_PRACTICE, "abc123", 80.0),
            CompletionMark(ProgressEngine.ASSESSMENT, "en-foundations", 90.0, 100),
            CompletionMark(ProgressEngine.ASSESSMENT, "free", 95.0, 0))
        val result = stats(emptyList(), marks)
        assertEquals(2, result.themesPractised)
        assertEquals(1, result.exercisesCompleted)
        assertEquals(1, result.listeningCompleted)
        assertEquals(2, result.practiceRounds)
        assertEquals(1, result.wordsPracticeItems)
        assertEquals(1, result.assessmentsPassed)
    }

    @Test fun syncEntriesRollUpMarksAndLessonCountersOutrankTheCappedLedger() {
        val marks = listOf(
            CompletionMark(ProgressEngine.LESSON, "l1", 10.0),
            CompletionMark(ProgressEngine.LESSON, "l1", 20.0),
            CompletionMark(ProgressEngine.LISTENING, "cafe", 30.0, 60))
        val entries = ProgressSync.entries(listOf(LessonState(lessonID = "l1", attempts = 7, lastSeen = 25.0)), marks)
        val l1 = entries.single { it.itemID == "l1" }
        assertEquals(ProgressEngine.LESSON, l1.kind)
        assertEquals(7, l1.attempts)
        assertEquals(25.0, (l1.atMilliseconds - 978_307_200_000L) / 1000.0, 0.001)
        val cafe = entries.single { it.itemID == "cafe" }
        assertEquals(1, cafe.attempts)
        assertEquals(60, cafe.bestScore)
        assertTrue(entries.first().atMilliseconds >= entries.last().atMilliseconds)
    }

    @Test fun remoteMergeRestoresLessonsCounterWiseAndSynthesizesUnseenCompletionMarks() {
        val archive = ArchiveCodec.decode(ArchiveCodec.encode(Archive(lessons = mutableListOf(
            LessonState(lessonID = "l1", attempts = 2, lastSeen = 10.0)),
            completions = mutableListOf(CompletionMark(ProgressEngine.LISTENING, "known", 5.0, 40)))))
        val merged = ProgressSync.mergeRemote(archive, listOf(
            RemoteProgress(ProgressEngine.LESSON, "l1", 1.0, 20.0, 5, 0),
            RemoteProgress(ProgressEngine.LESSON, "l2", 2.0, 30.0, 1, 0),
            RemoteProgress(ProgressEngine.EXERCISE_SET, "l1", 3.0, 40.0, 2, 80),
            RemoteProgress(ProgressEngine.LISTENING, "known", 4.0, 50.0, 3, 90)))
        assertEquals(5, merged.lessons.single { it.lessonID == "l1" }.attempts)
        assertEquals(20.0, merged.lessons.single { it.lessonID == "l1" }.lastSeen, 0.0001)
        assertEquals(1, merged.lessons.single { it.lessonID == "l2" }.attempts)
        assertTrue(merged.completions!!.any { it.kind == ProgressEngine.EXERCISE_SET && it.itemID == "l1" && it.bestScore == 80 })
        assertEquals(1, merged.completions!!.count { it.kind == ProgressEngine.LISTENING })
    }

    @Test fun archiveMergeUnionsCompletionMarksWithExactTupleDedup() {
        val current = Archive(completions = mutableListOf(CompletionMark(ProgressEngine.LESSON, "l1", 10.0, 0)))
        val incoming = ArchiveCodec.decode(ArchiveCodec.encode(Archive(completions = mutableListOf(
            CompletionMark(ProgressEngine.LESSON, "l1", 10.0, 0),
            CompletionMark(ProgressEngine.LESSON, "l1", 20.0, 0)))))
        val merged = ArchiveCodec.merge(current, incoming)
        assertEquals(2, merged.completions!!.size)
        assertEquals(1, merged.completions!!.count { it.at == 10.0 })
        assertEquals(1, merged.completions!!.count { it.at == 20.0 })
    }

    @Test fun archiveValidationRejectsUnknownKindsScoresAndDuplicateMarks() {
        fun encoded(vararg marks: CompletionMark) =
            ArchiveCodec.encode(Archive(completions = mutableListOf(*marks)))
        assertThrows(ArchiveError::class.java) { ArchiveCodec.decode(encoded(CompletionMark("streak", "x", 10.0))) }
        assertThrows(ArchiveError::class.java) { ArchiveCodec.decode(encoded(CompletionMark(ProgressEngine.LESSON, "x", 10.0, 101))) }
        assertThrows(ArchiveError::class.java) {
            ArchiveCodec.decode(encoded(CompletionMark(ProgressEngine.LESSON, "x", 10.0),
                CompletionMark(ProgressEngine.LESSON, "x", 10.0)))
        }
    }

    private fun assertThrows(type: Class<out Exception>, block: () -> Unit) {
        try { block(); fail("expected ${type.simpleName}") } catch (error: Exception) {
            assertTrue("expected ${type.simpleName} but got $error", type.isInstance(error))
        }
    }
}
