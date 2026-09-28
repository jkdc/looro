package com.jkdc.app.core

/** Spaced-recall scheduling for saved expressions. Reuses the word-bar ladder
    (`1, 1, 4, 14` days) and the archive's Apple-epoch seconds; grading is
    typed-recall v1 — correct promotes one bar, a lapse drops to bar 1.
    Entries are scoped per learning language: the upsert key is
    (languageID, text); legacy null-language entries stay reachable everywhere
    so old backups keep working, and get adopted by the current language the
    next time the same text is seeded or looked up. */
object RecallEngine {
    const val MAXIMUM_EXPRESSIONS = 2_000
    private val LADDER = listOf(1.0, 1.0, 4.0, 14.0)

    /** Case/whitespace-insensitive upsert key for an expression. */
    fun normalizeKey(text: String): String = text.trim().lowercase()

    fun expressionKey(text: String, languageID: String?): String =
        "${languageID.orEmpty()}|${normalizeKey(text)}"

    private fun firstMatching(a: Archive, text: String): ExpressionState? {
        val languageID = a.preferences.learningLanguageID
        val key = normalizeKey(text)
        return a.expressions.firstOrNull { normalizeKey(it.text) == key && (it.languageID == null || it.languageID == languageID) }
    }

    /** Seed a lesson's target expressions into the notebook. Idempotent on the
        (language, text) key; silently skips when the notebook is at capacity. */
    fun seedFromLesson(a: Archive, lesson: Lesson, now: Double) {
        val languageID = a.preferences.learningLanguageID
        for (expression in lesson.expressions) {
            if (normalizeKey(expression.text).isEmpty()) continue
            val index = a.expressions.indexOfFirst {
                normalizeKey(it.text) == normalizeKey(expression.text) && (it.languageID == languageID || it.languageID == null)
            }
            if (index >= 0) {
                if (a.expressions[index].languageID == null) a.expressions[index] = a.expressions[index].copy(languageID = languageID)
                continue
            }
            if (a.expressions.size >= MAXIMUM_EXPRESSIONS) return
            a.expressions += ExpressionState(text = expression.text, meaning = expression.meaning,
                source = "lesson", lessonID = lesson.id, dueAt = now, languageID = languageID)
        }
    }

    /** Save a lookup; upserts on the (language, text) key. Returns false when a
        new entry cannot fit. */
    fun saveLookup(a: Archive, text: String, meaning: String, now: Double): Boolean {
        val languageID = a.preferences.learningLanguageID
        if (normalizeKey(text).isEmpty()) return false
        val index = a.expressions.indexOfFirst {
            normalizeKey(it.text) == normalizeKey(text) && (it.languageID == languageID || it.languageID == null)
        }
        if (index >= 0) {
            val current = a.expressions[index]
            a.expressions[index] = current.copy(meaning = if (current.meaning.isNotBlank()) current.meaning else meaning,
                languageID = languageID)
            return true
        }
        if (a.expressions.size >= MAXIMUM_EXPRESSIONS) return false
        a.expressions += ExpressionState(text = text.trim(), meaning = meaning, source = "lookup", dueAt = now, languageID = languageID)
        return true
    }

    fun remove(a: Archive, text: String) {
        val languageID = a.preferences.learningLanguageID
        val key = normalizeKey(text)
        a.expressions.removeAll { normalizeKey(it.text) == key && (it.languageID == null || it.languageID == languageID) }
    }

    /** Due expressions for the learning language (bars < 3, dueAt reached),
        oldest due first. Legacy null-language entries stay due everywhere. */
    fun due(a: Archive, now: Double, languageID: String = a.preferences.learningLanguageID): List<ExpressionState> =
        a.expressions.filter { (it.languageID == null || it.languageID == languageID) && it.bars < 3 && it.dueAt <= now }
            .sortedBy { it.dueAt }

    fun dueCount(a: Archive, now: Double, languageID: String = a.preferences.learningLanguageID): Int = due(a, now, languageID).size

    /** Grade a recall attempt. Correct promotes one bar and schedules the next
        review on the ladder; a lapse drops to bar 1 and reviews tomorrow. */
    fun grade(a: Archive, text: String, correct: Boolean, now: Double) {
        val state = firstMatching(a, text) ?: return
        state.attempts += 1
        state.lastSeen = now
        state.bars = if (correct) (state.bars + 1).coerceAtMost(3) else 1
        state.dueAt = now + LADDER[state.bars] * 86400
    }
}
