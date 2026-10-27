package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ProgressEngineTest {
    private val zone = ZoneId.of("UTC")
    private val day = 86_400.0
    private val listeningIDs = setOf("en-listen-coffee")

    private fun course(languageID: String, vararg lessonIDs: String) = Course(id = "$languageID-foundations",
        languageID = languageID, title = "Foundations", subtitle = "s", symbol = "g", description = "d",
        units = listOf(CourseUnit(id = "u1", title = "U1", subtitle = "", level = "A1", themes = emptyList(),
            lessons = lessonIDs.map { Lesson(it, "T", "G", LessonIntro(emptyList(), emptyList()), emptyList(), "s", LessonPractice(emptyList())) })))

    private fun stats(lessons: List<LessonState>, completions: List<CompletionMark>,
                      sessions: List<SessionRecord> = emptyList(), languageID: String = "en",
                      courses: List<Course> = listOf(course("en", "en-l1", "en-l2", "en-l3")),
                      now: Double = 20.0) =
        ProgressEngine.stats(sessions, lessons, completions, courses, listeningIDs, languageID, zone = zone, now = now)

    private val lesson = { id: String, attempts: Int -> LessonState(lessonID = id, attempts = attempts) }

    @Test fun completionKeysPassSlugsThroughAndHashEverythingElse() {
        assertEquals("en-foundations-u1-to-be", ProgressEngine.completionKey("en-foundations-u1-to-be"))
        assertEquals("restaurant", ProgressEngine.completionKey("restaurant"))
        val hashed = ProgressEngine.completionKey("en|niño|greeting")
        assertEquals(16, hashed.length)
        assertTrue(hashed.all { it in "0123456789abcdef" })
        assertEquals(hashed, ProgressEngine.completionKey("en|niño|greeting"))
        assertFalse(hashed == ProgressEngine.completionKey("es|niño|greeting"))
    }

    @Test fun lessonsDoneAndCoursePercentComeFromTheAttemptCounters() {
        val lessons = listOf(lesson("en-l1", 1), lesson("en-l3", 2))
        val result = stats(lessons, emptyList())
        assertEquals(2, result.lessonsDone)
        assertEquals(3, result.lessonsTotal)
        assertEquals(66, result.courses.single().percent)
    }

    @Test fun activityDaysUniteScopedCompletionsAndScopedVoiceSessions() {
        val marks = listOf(100.0, 101.0, 102.0, 104.0).map {
            CompletionMark(ProgressEngine.LESSON, "en-l1", it * day + 400.0, languageID = "en")
        }
        val foreign = CompletionMark(ProgressEngine.LESSON, "fr-l1", 103 * day + 400.0, languageID = "fr")
        val session = SessionRecord(startedAt = 103 * day + 400.0, languageID = "en").apply { voiceSeconds = 60.0 }
        val foreignSession = SessionRecord(startedAt = 105 * day + 400.0, languageID = "fr").apply { voiceSeconds = 60.0 }
        val result = stats(emptyList(), marks + foreign, listOf(session, foreignSession), now = 105 * day + 400.0)
        assertEquals(5, result.activeStreakDays)
        assertEquals(5, result.longestStreakDays)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 1, 0),
            result.last30DaysActivities)
    }

    @Test fun ambiguousCountsRequireTheLanguageStampContentIdsDoNot() {
        val completions = listOf(
            CompletionMark(ProgressEngine.CONVERSATION_THEME, "restaurant", 10.0, languageID = "en"),
            CompletionMark(ProgressEngine.CONVERSATION_THEME, "restaurant", 20.0, languageID = "fr"),
            CompletionMark(ProgressEngine.CONVERSATION_THEME, "cafe", 30.0, languageID = "fr"),
            CompletionMark(ProgressEngine.EXERCISE_SET, "en-l1", 40.0, 80),
            CompletionMark(ProgressEngine.LISTENING, "en-listen-coffee", 50.0, 60),
            CompletionMark(ProgressEngine.RECALL_ROUND, "recall", 60.0, languageID = "en"),
            CompletionMark(ProgressEngine.RECALL_ROUND, "recall", 70.0, languageID = "fr"),
            CompletionMark(ProgressEngine.WORDS_PRACTICE, "hash1", 80.0, languageID = "fr"),
            CompletionMark(ProgressEngine.ASSESSMENT, "en-foundations", 90.0, 100),
            CompletionMark(ProgressEngine.ASSESSMENT, "free", 95.0, 100, languageID = "en"),
            CompletionMark(ProgressEngine.ASSESSMENT, "free", 96.0, 100, languageID = "fr"))
        val result = stats(emptyList(), completions)
        assertEquals(1, result.themesPractised)
        assertEquals(1, result.exercisesCompleted)
        assertEquals(1, result.listeningCompleted)
        assertEquals(1, result.practiceRounds)
        assertEquals(0, result.wordsPracticeItems)
        assertEquals(2, result.assessmentsPassed)
    }

    @Test fun legacyNullMarksCountForStreaksButNotForAmbiguousCounts() {
        val completions = listOf(
            CompletionMark(ProgressEngine.CONVERSATION_THEME, "restaurant", 10.0),
            CompletionMark(ProgressEngine.LESSON, "en-l1", day + 400.0))
        val result = stats(listOf(lesson("en-l1", 1)), completions, now = 2 * day)
        assertEquals(0, result.themesPractised)
        assertEquals(0, result.exercisesCompleted)
        assertEquals(1, result.lessonsDone)
        assertEquals(2, result.activeStreakDays)
    }

    @Test fun syncEntriesRollUpOnlyTheRequestedLanguageAndCarryIt() {
        val nowMilliseconds = 1_750_000_000_000L
        fun at(unixMilliseconds: Long) = ProgressTime.fromUnixMilliseconds(
            unixMilliseconds.toDouble(), nowMilliseconds.toDouble())!!
        val marks = listOf(
            CompletionMark(ProgressEngine.LESSON, "en-l1", at(nowMilliseconds - 15_000)),
            CompletionMark(ProgressEngine.LESSON, "fr-l1", at(nowMilliseconds - 10_000), languageID = "fr"),
            CompletionMark(ProgressEngine.LISTENING, "en-listen-coffee", at(nowMilliseconds - 5_000), 60))
        val entries = ProgressSync.entries(listOf(LessonState("en-l1", attempts = 7, lastSeen = at(nowMilliseconds - 1_000))), marks,
            listOf(course("en", "en-l1", "fr-l1")), listeningIDs, "en", nowUnixMilliseconds = nowMilliseconds)
        assertEquals(listOf("en", "en"), entries.map { it.language })
        assertEquals(setOf("en-l1", "en-listen-coffee"), entries.map { it.itemID }.toSet())
        val l1 = entries.single { it.itemID == "en-l1" }
        assertEquals(7, l1.attempts)
        assertEquals(nowMilliseconds - 15_000, l1.firstAtMilliseconds)
        assertEquals(nowMilliseconds - 1_000, l1.atMilliseconds)
    }

    @Test fun progressEntryCollectionDoesNotDropRowsBeforeUploadBatching() {
        val nowMilliseconds = 1_750_000_000_000L
        val marks = (0 until 201).map { index ->
            val unixMilliseconds = nowMilliseconds - (201 - index) * 1_000L
            CompletionMark(ProgressEngine.CONVERSATION_THEME, "theme-%03d".format(index),
                ProgressTime.fromUnixMilliseconds(unixMilliseconds.toDouble(), nowMilliseconds.toDouble())!!, languageID = "en")
        }

        val entries = ProgressSync.entries(emptyList(), marks, emptyList(), emptySet(), "en", nowUnixMilliseconds = nowMilliseconds)

        assertEquals(201, entries.size)
        assertEquals("theme-000", entries.first().itemID)
        assertEquals("theme-200", entries.last().itemID)
    }

    @Test fun remoteMergeIgnoresOtherLanguagesAndStampsRestoredMarks() {
        val archive = ArchiveCodec.decode(ArchiveCodec.encode(Archive(lessons = mutableListOf(
            LessonState(lessonID = "en-l1", attempts = 2, lastSeen = 10.0)),
            completions = mutableListOf(CompletionMark(ProgressEngine.LISTENING, "en-listen-known", 5.0, 40)))))
        val merged = ProgressSync.mergeRemote(archive, listOf(
            RemoteProgress(ProgressEngine.LESSON, "en-l1", 1.0, 20.0, 5, 0, "en"),
            RemoteProgress(ProgressEngine.LESSON, "fr-l1", 2.0, 30.0, 9, 0, "fr"),
            RemoteProgress(ProgressEngine.EXERCISE_SET, "en-l1", 3.0, 40.0, 2, 80, "en"),
            RemoteProgress(ProgressEngine.LISTENING, "en-listen-known", 4.0, 50.0, 3, 90, "en")), "en")
        assertEquals(5, merged.lessons.single { it.lessonID == "en-l1" }.attempts)
        assertNull(merged.lessons.firstOrNull { it.lessonID == "fr-l1" })
        val restored = merged.completions!!.single { it.kind == ProgressEngine.EXERCISE_SET }
        assertEquals("en", restored.languageID)
        assertEquals(1, merged.completions!!.count { it.kind == ProgressEngine.LISTENING })
    }

    @Test fun archiveMergeUnionsCompletionMarksWithExactTupleDedup() {
        val current = Archive(completions = mutableListOf(CompletionMark(ProgressEngine.LESSON, "en-l1", 10.0)))
        val incoming = ArchiveCodec.decode(ArchiveCodec.encode(Archive(completions = mutableListOf(
            CompletionMark(ProgressEngine.LESSON, "en-l1", 10.0),
            CompletionMark(ProgressEngine.LESSON, "en-l1", 20.0)))))
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
        // Same instant in two languages is two legitimate marks, not a duplicate.
        val bilingual = ArchiveCodec.decode(ArchiveCodec.encode(Archive(completions = mutableListOf(
            CompletionMark(ProgressEngine.CONVERSATION_THEME, "restaurant", 10.0, languageID = "en"),
            CompletionMark(ProgressEngine.CONVERSATION_THEME, "restaurant", 10.0, languageID = "fr")))))
        assertEquals(2, bilingual.completions!!.size)
    }

    private fun assertThrows(type: Class<out Exception>, block: () -> Unit) {
        try { block(); fail("expected ${type.simpleName}") } catch (error: Exception) {
            assertTrue("expected ${type.simpleName} but got $error", type.isInstance(error))
        }
    }
}
