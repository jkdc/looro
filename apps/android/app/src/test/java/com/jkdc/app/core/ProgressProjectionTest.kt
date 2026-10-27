package com.jkdc.app.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ProgressProjectionTest {
    @Test fun remote90ImprovesLocal40InEitherOrder() {
        val local = ProgressAggregate("account-a", "es", ProgressEngine.EXERCISE_SET, "unit-1", 10, 20, 2, 40)
        val remote = ProgressAggregate("account-a", "es", ProgressEngine.EXERCISE_SET, "unit-1", 5, 30, 4, 90)
        assertEquals(90, ProgressProjection.merge("account-a", "es", listOf(local), listOf(remote)).single().bestScore)
        assertEquals(90, ProgressProjection.merge("account-a", "es", listOf(remote), listOf(local)).single().bestScore)
    }

    @Test fun projectionReplayDoesNotInventActivity() {
        val remote = ProgressAggregate("account-a", "es", ProgressEngine.LISTENING, "l1", 1_700_000_000_000, 1_700_000_000_000, 3, 90)
        val localEvents = emptyList<CompletionMark>()
        val projected = ProgressProjection.effectiveCompletions("account-a", "es", localEvents, listOf(remote))
        assertEquals(1, projected.size)
        assertEquals(0, localEvents.size)
    }

    @Test fun otherOwnerOrLanguageDoesNotContribute() {
        val candidates = listOf(
            ProgressAggregate("account-b", "es", "exercise_set", "x", 1, 9, 9, 99),
            ProgressAggregate("account-a", "fr", "exercise_set", "x", 1, 9, 9, 99),
            ProgressAggregate("account-a", null, "exercise_set", "x", 1, 9, 9, 99),
        )
        assertEquals(emptyList<ProgressAggregate>(), ProgressProjection.merge("account-a", "es", emptyList(), candidates))
    }

    @Test fun nextUploadRetainsRemoteBestAndFirstAtForAllKinds() {
        val kinds = ProgressEngine.kinds
        val remote = kinds.map { kind -> ProgressAggregate("account-a", "en", kind, "item-$kind", 1_700_000_000_000, 1_700_000_000_500, 4, 90) }
        val localMark = CompletionMark(ProgressEngine.EXERCISE_SET, "item-${ProgressEngine.EXERCISE_SET}",
            1_699_999_999.5, 40, "en")
        val entries = ProgressSync.entries(emptyList(), listOf(localMark), emptyList(), emptySet(), "en", ownerAccountID = "account-a", remote = remote)
        assertEquals(kinds.size, entries.size)
        assertEquals(kinds.toSet(), entries.map { it.kind }.toSet())
        assertEquals(90, entries.first { it.kind == ProgressEngine.EXERCISE_SET }.bestScore)
        assertEquals(4, entries.first { it.kind == ProgressEngine.EXERCISE_SET }.attempts)
        assertEquals(1_700_000_000_000, entries.first { it.kind == ProgressEngine.EXERCISE_SET }.firstAtMilliseconds)
    }

    @Test fun remoteReadModelsImproveCompletionWithoutAddingActivity() {
        val lesson = Lesson("en-l1", "Lesson", "Grammar", LessonIntro(emptyList(), emptyList()), emptyList(), "s", LessonPractice(emptyList()))
        val course = Course("en-foundations", "en", "Foundations", "s", "g", "d", listOf(
            CourseUnit("u1", "Unit", "", "A1", emptyList(), listOf(lesson))))
        val remote = listOf(
            ProgressAggregate("account-a", "en", ProgressEngine.LESSON, "en-l1", 1_700_000_000_000, 1_700_000_000_000, 2, 0),
            ProgressAggregate("account-a", "en", ProgressEngine.EXERCISE_SET, "en-l1", 1_700_000_000_000, 1_700_000_000_000, 3, 90),
        )
        val result = ProgressEngine.stats(emptyList(), emptyList(), emptyList(), listOf(course), emptySet(), "en",
            zone = ZoneId.of("UTC"), now = 1_700_000_000.0, ownerAccountID = "account-a", remote = remote)
        assertEquals(1, result.lessonsDone)
        assertEquals(1, result.exercisesCompleted)
        assertEquals(0, result.activeStreakDays)
        assertEquals(0, result.last30DaysActivities.sum())
    }

    @Test fun remoteExerciseScoreImprovesLevelMasteryWithoutCreatingLocalMarks() {
        val remote = listOf(
            ProgressAggregate("account-a", "en", ProgressEngine.LESSON, "en-l1", 1_700_000_000_000, 1_700_000_000_500, 4, 0),
            ProgressAggregate("account-a", "en", ProgressEngine.EXERCISE_SET, "en-l1", 1_700_000_000_000, 1_700_000_000_500, 4, 90),
        )
        assertEquals(3, LevelEngine.lessonTier("en-l1", emptyList(), emptyList(),
            ZoneId.of("UTC"), "account-a", "en", remote))
    }

    @Test fun remoteBestScoreWinsAndRollupIsIdempotentInEitherOrder() {
        val local = ProgressAggregate("account-a", "es", "exercise", "unit-1", 10, 20, 2, 40)
        val remote = ProgressAggregate("account-a", "es", "exercise", "unit-1", 5, 30, 4, 90)

        val localThenRemote = ProgressProjection.merge("account-a", "es", listOf(local), listOf(remote)).single()
        val remoteThenLocal = ProgressProjection.merge("account-a", "es", listOf(remote), listOf(local)).single()
        val replayed = ProgressProjection.merge("account-a", "es", listOf(localThenRemote), listOf(remote)).single()

        assertEquals(ProgressAggregate("account-a", "es", "exercise", "unit-1", 5, 30, 4, 90), localThenRemote)
        assertEquals(localThenRemote, remoteThenLocal)
        assertEquals(localThenRemote, replayed)
    }

    @Test fun otherOwnerAndUnresolvedLanguageDoNotContribute() {
        val local = ProgressAggregate("account-a", "es", "lesson", "unit-1", 10, 20, 1, 0)
        val remote = listOf(
            ProgressAggregate("account-b", "es", "lesson", "unit-1", 1, 90, 9, 90),
            ProgressAggregate("account-a", "fr", "lesson", "unit-1", 2, 80, 8, 80),
            ProgressAggregate("account-a", null, "lesson", "unit-1", 3, 70, 7, 70),
        )

        assertEquals(listOf(local), ProgressProjection.merge("account-a", "es", listOf(local), remote))
    }

    @Test fun projectionDoesNotAppendLocalEvents() {
        val input = listOf(ProgressAggregate("account-a", "es", "reading", "r1", 1, 2, 1, 0))
        val before = input.toList()

        assertEquals(input, ProgressProjection.merge("account-a", "es", input, emptyList()))
        assertEquals(before, input)
    }
}
