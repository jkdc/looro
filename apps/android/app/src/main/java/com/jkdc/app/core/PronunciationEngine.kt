package com.jkdc.app.core

/** Word-error-rate pronunciation scoring: the target phrase against the
 *  speech transcript, both through the ExerciseEngine normalizer.
 *  Deterministic — the same target and transcript always produce the same
 *  score, and nothing here touches audio, only the recognized words. */
object PronunciationEngine {
    /** A score at or above this counts as passed and records ledger evidence.
        Conservative on purpose: STT mishears accents, so half the words is a
        fair bar rather than perfection. */
    const val PASS_THRESHOLD = 50

    fun score(target: String, transcript: String): Int {
        val wanted = ExerciseEngine.normalize(target).split(' ').filter { it.isNotEmpty() }
        val heard = ExerciseEngine.normalize(transcript).split(' ').filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return if (heard.isEmpty()) 100 else 0
        val distance = editDistance(wanted, heard)
        return (100.0 * (1.0 - distance.toDouble() / wanted.size)).toInt().coerceIn(0, 100)
    }

    fun passed(score: Int): Boolean = score >= PASS_THRESHOLD

    /** Levenshtein distance over word lists — insertions, deletions and
        substitutions each cost one, so an extra word penalizes exactly as
        much as a dropped one. */
    private fun editDistance(a: List<String>, b: List<String>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val current = IntArray(b.size + 1)
            current[0] = i
            for (j in 1..b.size) {
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[b.size]
    }
}
