package com.jkdc.app.core

/** Daily Learning Plan: composes a short session from what the ledger says the
    user needs — recall first, then new content, then one comprehension activity.
    A pure function of (budget, due words, what's next, what's unplayed); the UI
    renders the steps and wires the ones that can navigate. */
data class PlanStep(val kind: String, val minutes: Int) // kind: recall · lesson · listen · read

object DailyPlanEngine {
    val BUDGETS = listOf(5, 10, 15, 20)

    /** Composes a plan within the budget. Rules:
        - recall is first and takes the due words' share (never more than half);
        - one new lesson when there is one;
        - one comprehension step (reading preferred for small budgets, listening
          for bigger ones) when either shelf has untouched content;
        - steps never exceed the budget; a zero budget yields an empty plan. */
    fun plan(budget: Int, dueWords: Int, hasNewLesson: Boolean, hasUnplayedListening: Boolean,
             hasUnplayedReading: Boolean): List<PlanStep> {
        if (budget <= 0) return emptyList()
        val steps = mutableListOf<PlanStep>()
        var left = budget
        if (dueWords > 0) {
            val minutes = minOf(left, maxOf(3, budget / 3))
            steps += PlanStep("recall", minutes); left -= minutes
        }
        if (left >= 5 && hasNewLesson) {
            val minutes = minOf(left, maxOf(5, budget / 3))
            steps += PlanStep("lesson", minutes); left -= minutes
        }
        if (left >= 3) {
            val minutes = left
            when {
                budget >= 10 && hasUnplayedListening -> steps += PlanStep("listen", minutes)
                hasUnplayedReading -> steps += PlanStep("read", minutes)
                hasUnplayedListening -> steps += PlanStep("listen", minutes)
            }
        }
        return steps
    }
}
