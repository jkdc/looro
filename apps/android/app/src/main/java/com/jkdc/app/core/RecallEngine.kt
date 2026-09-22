package com.jkdc.app.core

/** Spaced-recall scheduling for saved expressions. Reuses the word-bar ladder
    (`1, 1, 4, 14` days) and the archive's Apple-epoch seconds; grading is
    typed-recall v1 — correct promotes one bar, a lapse drops to bar 1. */
object RecallEngine {
    const val MAXIMUM_EXPRESSIONS = 2_000
    private val LADDER = listOf(1.0, 1.0, 4.0, 14.0)

    /** Case/whitespace-insensitive upsert key for an expression. */
    fun normalizeKey(text: String): String = text.trim().lowercase()

    /** Seed a lesson's target expressions into the notebook. Idempotent on the
        normalized text; silently skips when the notebook is at capacity. */
    fun seedFromLesson(a: Archive, lesson: Lesson, now: Double) {
        val known = a.expressions.mapTo(mutableSetOf()) { normalizeKey(it.text) }
        for (expression in lesson.expressions) {
            val key = normalizeKey(expression.text)
            if (key.isEmpty()) continue
            if (key in known) continue
            if (a.expressions.size >= MAXIMUM_EXPRESSIONS) return
            val state = ExpressionState(text = expression.text, meaning = expression.meaning,
                source = "lesson", lessonID = lesson.id, dueAt = now)
            a.expressions += state
            known += key
        }
    }

    /** Save a lookup; upserts on the normalized text. Returns false when a new
        entry cannot fit. */
    fun saveLookup(a: Archive, text: String, meaning: String, now: Double): Boolean {
        val key = normalizeKey(text)
        if (key.isEmpty()) return false
        val index = a.expressions.indexOfFirst { normalizeKey(it.text) == key }
        if (index >= 0) {
            val current = a.expressions[index]
            if (current.meaning.isBlank()) a.expressions[index] = current.copy(meaning = meaning)
            return true
        }
        if (a.expressions.size >= MAXIMUM_EXPRESSIONS) return false
        a.expressions += ExpressionState(text = text.trim(), meaning = meaning, source = "lookup", dueAt = now)
        return true
    }

    fun remove(a: Archive, text: String) {
        val key = normalizeKey(text)
        a.expressions.removeAll { normalizeKey(it.text) == key }
    }

    /** Due expressions (bars < 3, dueAt reached), oldest due first. */
    fun due(a: Archive, now: Double): List<ExpressionState> =
        a.expressions.filter { it.bars < 3 && it.dueAt <= now }.sortedBy { it.dueAt }

    fun dueCount(a: Archive, now: Double): Int = due(a, now).size

    /** Grade a recall attempt. Correct promotes one bar and schedules the next
        review on the ladder; a lapse drops to bar 1 and reviews tomorrow. */
    fun grade(a: Archive, text: String, correct: Boolean, now: Double) {
        val key = normalizeKey(text)
        val state = a.expressions.firstOrNull { normalizeKey(it.text) == key } ?: return
        state.attempts += 1
        state.lastSeen = now
        state.bars = if (correct) (state.bars + 1).coerceAtMost(3) else 1
        state.dueAt = now + LADDER[state.bars] * 86400
    }
}
