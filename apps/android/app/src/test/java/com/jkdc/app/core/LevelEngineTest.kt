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
        val stats = LevelEngine.stats(course("l1", "l2", "l3", "l4", "l5"), lessons, marks, zone)
        // l1 = 3, l2 = 2, l3 = 1, l4 = 1, l5 = 0 → 7 points → level 2 (threshold 4), 1 point to level 3 (8).
        assertEquals(7, stats.points)
        assertEquals(2, stats.level)
        assertEquals(1, stats.pointsToNext)
        assertEquals(0.75, stats.progressInLevel, 0.0001)
        assertEquals(3, stats.mastery.first { it.lessonID == "l1" }.tier)
        assertEquals(0, stats.mastery.first { it.lessonID == "l5" }.tier)
    }

    @Test fun `top of the ladder reports complete and progress is bounded`() {
        val lessons = (1..18).map { LessonState("l$it", attempts = 1) }
        val marks = (1..18).flatMap { listOf(CompletionMark(ProgressEngine.EXERCISE_SET, "l$it", 10.0, 95)) }
        val stats = LevelEngine.stats(course(*lessons.map { it.lessonID }.toTypedArray()), lessons, marks, zone)
        assertEquals(54, stats.points)
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
