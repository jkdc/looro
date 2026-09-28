package com.jkdc.app.core

/** Deterministic grading for authored temario exercises. No AI, no network:
    attempts are normalized (NFC, case, punctuation, spacing) and compared
    against the accepted answers; a `<…>` span in an expected answer accepts
    any non-empty text. Shared verbatim with the Listening questions. */
object ExerciseEngine {
    const val CHOICE = "choice"
    const val GAP = "gap"
    const val ORDER = "order"
    const val WRITTEN = "written"

    private val PUNCTUATION = Regex("[.,!?;:¿¡\"“”«»…()]")
    private val SPACES = Regex("\\s+")
    private val PLACEHOLDER = Regex("<[^<>]+>")

    fun normalize(text: String): String = text.canonical().lowercase()
        .replace(PUNCTUATION, " ").replace(SPACES, " ").trim()

    /** True when the attempt matches an accepted answer. `choice` compares the
        picked option exactly; `order` receives the chosen tokens joined with
        spaces; `gap`/`written` compare with placeholders tolerated. */
    fun isCorrect(exercise: Exercise, attempt: String): Boolean = when (exercise.kind) {
        CHOICE -> exercise.answers.any { it == attempt.trim() }
        ORDER -> exercise.answers.any { normalize(it) == normalize(attempt) }
        else -> exercise.answers.any { matches(it, attempt) }
    }

    private fun matches(expected: String, attempt: String): Boolean {
        val wanted = normalize(expected)
        return if (!wanted.contains('<')) normalize(attempt) == wanted
        else Regex(PLACEHOLDER.split(wanted).joinToString(".+") { Regex.escape(it) }).matches(normalize(attempt))
    }
}
