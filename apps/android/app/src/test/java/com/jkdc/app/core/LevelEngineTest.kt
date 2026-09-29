package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class LevelEngineTest {
    private val zone = ZoneId.of("UTC")
    private val day = 86_400.0

    private fun course(vararg lessonIDs: String) = Course(id = "en-foundations", languageID = "en", title = "F",
        subtitle = "s", symbol = "g", description = "d", units = listOf(
        CourseUnit(id = "u1", title = "U1", subtitle = "", level = "A1", themes = emptyList(),
            lessons = lessonIDs.map { Lesson(it, "T", "G", LessonIntro(emptyList(), emptyList()), emptyList(), "s", LessonPractice(emptyList())) })))

    private fun listening(vararg ids: String) = ids.map { id ->
        ListeningText(id = id, languageID = "en", title = "T", level = "A1", topic = "t", text = "x", questions = emptyList())
    }

    @Test fun `tiers grow with practice and exercise scores`() {
        val marks = listOf(CompletionMark(ProgressEngine.EXERCISE_SET, "l1", 10.0, 70))
        assertEquals(0, LevelEngine.lessonTier("l1", emptyList(), marks, zone))
        assertEquals(2, LevelEngine.lessonTier("l1", listOf(LessonState("l1", attempts = 1)), marks, zone))
        assertEquals(1, LevelEngine.lessonTier("l1", listOf(LessonState("l1", attempts = 3)), emptyList(), zone))
        assertEquals(2, LevelEngine.lessonTier("l1", listOf(LessonState("l1", attempts = 1)), marks, zone))
        assertEquals(3, LevelEngine.lessonTier("l1", listOf(LessonState("l1", attempts = 1)),
            listOf(CompletionMark(ProgressEngine.EXERCISE_SET, "l1", 10.0, 90)), zone))
    }

    @Test fun `tier three also comes from spaced oral practice at sixty percent`() {
        val marks = listOf(
            CompletionMark(ProgressEngine.LESSON, "l1", day + 400.0),
            CompletionMark(ProgressEngine.LESSON, "l1", 4 * day + 400.0),
            CompletionMark(ProgressEngine.EXERCISE_SET, "l1", 10.0, 60))
        assertEquals(3, LevelEngine.lessonTier("l1", listOf(LessonState("l1", attempts = 2)), marks, zone))
        val sameDay = marks.take(1) + marks[2]
        assertEquals(2, LevelEngine.lessonTier("l1", listOf(LessonState("l1", attempts = 2)), sameDay, zone))
    }

    @Test fun `level climbs the ladder and reports points to next`() {
        val lessons = listOf(LessonState("l1", attempts = 1), LessonState("l2", attempts = 1), LessonState("l3", attempts = 1),
            LessonState("l4", attempts = 1))
        val marks = listOf(
            CompletionMark(ProgressEngine.EXERCISE_SET, "l1", 10.0, 100),
            CompletionMark(ProgressEngine.EXERCISE_SET, "l2", 10.0, 75),
            CompletionMark(ProgressEngine.EXERCISE_SET, "l3", 10.0, 50))
        val stats = LevelEngine.stats(course("l1", "l2", "l3", "l4", "l5"), lessons, marks, zone = zone)
        // l1 = 3, l2 = 2, l3 = 1, l4 = 1, l5 = 0 → 7 lesson points → level 2 (threshold 6), 5 points to level 3 (12).
        assertEquals(7, stats.points)
        assertEquals(7, stats.lessonPoints)
        assertEquals(0, stats.listeningPoints)
        assertEquals(2, stats.level)
        assertEquals(5, stats.pointsToNext)
        assertEquals(1.0 / 6.0, stats.progressInLevel, 0.0001)
        assertEquals(3, stats.mastery.first { it.lessonID == "l1" }.tier)
        assertEquals(0, stats.mastery.first { it.lessonID == "l5" }.tier)
    }

    @Test fun `listening points double the medal grade from the best score`() {
        fun best(vararg scores: Int) = scores.map { CompletionMark(ProgressEngine.LISTENING, "t1", 10.0, it) }
        val texts = listening("t1", "t2", "t3", "t4")
        fun statsOf(vararg marks: CompletionMark) =
            LevelEngine.stats(course("l1"), emptyList(), marks.toList(), texts, zone)
        // Nothing below 80 counts; best-of-marks wins; unknown items never count.
        assertEquals(0, statsOf().listeningPoints)
        assertEquals(1, statsOf(CompletionMark(ProgressEngine.LISTENING, "t1", 10.0, 80)).listeningPoints)
        assertEquals(1, statsOf(*best(79, 85).toTypedArray()).listeningPoints)
        assertEquals(4, statsOf(CompletionMark(ProgressEngine.LISTENING, "t1", 10.0, 100),
            CompletionMark(ProgressEngine.LISTENING, "t2", 10.0, 100),
            CompletionMark(ProgressEngine.LISTENING, "t3", 10.0, 70),
            CompletionMark(ProgressEngine.LISTENING, "unknown", 10.0, 100)).listeningPoints)
        assertEquals(LevelEngine.listeningMedal(80), LevelEngine.listeningTier(80))
    }

    @Test fun `lesson-only mastery no longer reaches the top of the ladder`() {
        val lessons = (1..18).map { LessonState("l$it", attempts = 1) }
        val marks = (1..18).map { CompletionMark(ProgressEngine.EXERCISE_SET, "l$it", 10.0, 95) }
        val stats = LevelEngine.stats(course(*lessons.map { it.lessonID }.toTypedArray()), lessons, marks, zone = zone)
        // 54 lesson points: level 7 now — the last rungs need listening too.
        assertEquals(54, stats.points)
        assertEquals(7, stats.level)
        assertEquals(7, stats.pointsToNext)
    }

    @Test fun `top of the ladder reports complete and progress is bounded`() {
        val lessons = (1..18).map { LessonState("l$it", attempts = 1) }
        val marks = (1..18).map { CompletionMark(ProgressEngine.EXERCISE_SET, "l$it", 10.0, 95) } +
            listening(*((1..12).map { "t$it" }).toTypedArray()).map {
                CompletionMark(ProgressEngine.LISTENING, it.id, 10.0, 100)
            }
        val stats = LevelEngine.stats(course(*lessons.map { it.lessonID }.toTypedArray()), lessons, marks,
            listening(*((1..12).map { "t$it" }).toTypedArray()), zone)
        // 54 lesson + 24 listening = 78 = full Foundations.
        assertEquals(78, stats.points)
        assertEquals(54, stats.lessonPoints)
        assertEquals(24, stats.listeningPoints)
        assertEquals(10, stats.level)
        assertNull(stats.pointsToNext)
        assertEquals(1.0, stats.progressInLevel, 0.0001)
    }

    @Test fun `listening medals come from the best score`() {
        assertEquals(0, LevelEngine.listeningMedal(null))
        assertEquals(0, LevelEngine.listeningMedal(79))
        assertEquals(1, LevelEngine.listeningMedal(80))
        assertEquals(1, LevelEngine.listeningMedal(99))
        assertEquals(2, LevelEngine.listeningMedal(100))
    }
}
