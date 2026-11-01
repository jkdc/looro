package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test

class DailyPlanEngineTest {
    @Test fun `recall comes first and never exceeds half the budget`() {
        val plan = DailyPlanEngine.plan(15, dueWords = 20, hasNewLesson = true,
            hasUnplayedListening = true, hasUnplayedReading = true)
        assertEquals("recall", plan.first().kind)
        assertTrue(plan.first().minutes <= 8)
        assertEquals(15, plan.sumOf { it.minutes })
    }

    @Test fun `bigger budgets add a listening, smaller ones prefer a reading`() {
        val big = DailyPlanEngine.plan(20, dueWords = 10, hasNewLesson = true,
            hasUnplayedListening = true, hasUnplayedReading = true)
        assertEquals(listOf("recall", "lesson", "listen"), big.map { it.kind })
        val small = DailyPlanEngine.plan(5, dueWords = 0, hasNewLesson = true,
            hasUnplayedListening = true, hasUnplayedReading = true)
        assertTrue(small.map { it.kind }.contains("lesson") || small.map { it.kind }.contains("read"))
    }

    @Test fun `no content means empty plan`() {
        val plan = DailyPlanEngine.plan(10, dueWords = 0, hasNewLesson = false,
            hasUnplayedListening = false, hasUnplayedReading = false)
            assertTrue("plan should be empty when nothing to do", plan.isEmpty())
    }

    @Test fun `zero budget yields an empty plan`() {
        assertTrue(DailyPlanEngine.plan(0, dueWords = 5, hasNewLesson = true,
            hasUnplayedListening = true, hasUnplayedReading = true).isEmpty())
    }
}
