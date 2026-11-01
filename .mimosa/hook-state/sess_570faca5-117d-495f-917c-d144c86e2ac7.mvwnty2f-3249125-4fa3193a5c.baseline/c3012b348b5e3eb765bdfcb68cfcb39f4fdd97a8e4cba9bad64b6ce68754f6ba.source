package com.jkdc.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Parses and verifies the structured scenario review produced by the review
    helper. Validation mirrors LearningEngine.validate: every error quote must
    be an exact contiguous substring of the transcript fragments it cites, so a
    hallucinated correction never reaches the learner. Invalid items are dropped
    individually; the rest of the review survives. */
object ScenarioReviewing {
    const val MAXIMUM_ERRORS = 6
    const val MAXIMUM_STRENGTHS = 3
    const val MAXIMUM_KEY_PHRASES = 6
    const val MAXIMUM_SOURCE_IDS = 8
    val KINDS = setOf("grammar", "vocabulary", "register", "phrasing", "omission")

    @Serializable
    private data class Response(
        val strengths: List<String> = emptyList(), val errors: List<ReviewedError> = emptyList(),
        val keyPhrases: List<ReviewedPhrase> = emptyList(), val retryFocus: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns null when the reply is not the expected JSON at all. */
    fun parse(text: String): ScenarioReview? = try {
        val decoded = json.decodeFromString<Response>(text)
        ScenarioReview(createdAt = nowSeconds(), strengths = decoded.strengths,
            errors = decoded.errors, keyPhrases = decoded.keyPhrases, retryFocus = decoded.retryFocus)
    } catch (_: Exception) { null }

    fun validate(review: ScenarioReview, session: SessionRecord): ScenarioReview {
        val byID = session.fragments.associateBy { it.id }
        val errors = review.errors.take(MAXIMUM_ERRORS).mapNotNull { error ->
            val quote = error.quote.trim().take(200)
            val correction = error.correction.trim().take(200)
            if (quote.isEmpty() || correction.isEmpty()) return@mapNotNull null
            if (error.kind !in KINDS) return@mapNotNull null
            val sourceIDs = error.sourceIDs.take(MAXIMUM_SOURCE_IDS)
            val evidence = sourceIDs.mapNotNull { byID[it]?.text }.joinToString("")
            if (!evidence.contains(quote)) return@mapNotNull null
            error.copy(quote = quote, correction = correction, note = error.note.trim().take(200), sourceIDs = sourceIDs)
        }
        val strengths = review.strengths.asSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .take(MAXIMUM_STRENGTHS).map { it.take(200) }.toList()
        val keyPhrases = review.keyPhrases.take(MAXIMUM_KEY_PHRASES).mapNotNull { phrase ->
            val text = phrase.phrase.trim().take(120)
            if (text.isEmpty()) null else ReviewedPhrase(text, phrase.meaning.trim().take(160))
        }
        return ScenarioReview(createdAt = review.createdAt, strengths = strengths, errors = errors,
            keyPhrases = keyPhrases, retryFocus = review.retryFocus.trim().take(200))
    }

    /** "you said → corrected" pairs for the next attempt's prompt, newest session first. */
    fun previousErrors(review: ScenarioReview): String = review.errors
        .joinToString("; ") { "${it.quote} → ${it.correction}" }.take(400)
}
