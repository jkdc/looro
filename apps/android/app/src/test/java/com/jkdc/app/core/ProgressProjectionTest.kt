package com.jkdc.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressProjectionTest {
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
