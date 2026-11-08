package com.jkdc.app.core

import java.util.Locale
import java.util.UUID

enum class DailyWordImportAction { INSERT, KEEP, SUPPRESS, PENDING_CAPACITY }

data class DailyWordsImportSummary(val inserted: Int, val existing: Int, val suppressed: Int, val pendingCapacity: Int)

data class DailyWordCandidate(
    val candidateId: String,
    val kind: String,
    val text: String,
    val meaning: String,
    val confidence: String,
    val origin: String? = null,
)

data class DailyWordsReview(val reviewId: String, val languageID: String, val candidates: List<DailyWordCandidate>)

data class DailyWordExisting(
    val text: String,
    val languageID: String,
    val bars: Int,
    val attempts: Int,
    val meaning: String,
)

data class DailyWordImportPlan(
    val owner: String,
    val languageID: String,
    val originKey: String,
    val normKey: String,
    val text: String,
    val meaning: String,
    val kind: String,
    val action: DailyWordImportAction,
    val now: Double,
)

object DailyWordsImport {
    private const val MAXIMUM_EXPRESSIONS = 2_000

    fun planDailyWordsImport(
        result: DailyWordsReview,
        owner: String,
        language: String,
        existing: List<DailyWordExisting>,
        receipts: Set<String>,
        suppressions: Set<String>,
        now: Double,
    ): List<DailyWordImportPlan> {
        require(owner.isNotBlank() && language.matches(Regex("^[a-z]{2}(?:-[A-Z]{2})?$")))
        require(result.languageID == language && result.reviewId.isNotBlank() && now.isFinite())
        val known = existing.filter { it.languageID == language }.mapTo(HashSet()) { normalizeKey(it.text) }
        var expressionCount = existing.size
        val emitted = HashSet<String>()
        val output = ArrayList<DailyWordImportPlan>()
        for (candidate in result.candidates) {
            if (candidate.confidence != "supported" || candidate.kind !in setOf("word", "expression")) continue
            val text = candidate.text.trim()
            val key = normalizeKey(text)
            if (key.isEmpty() || !emitted.add(key)) continue
            val originKey = if (candidate.origin == "learner_mark") "mark:${candidate.candidateId}"
                else "review:${result.reviewId}:${candidate.candidateId}"
            val suppressionKey = "$language:$key"
            val action = when {
                originKey in receipts -> DailyWordImportAction.KEEP
                suppressionKey in suppressions -> DailyWordImportAction.SUPPRESS
                key in known -> DailyWordImportAction.KEEP
                expressionCount >= MAXIMUM_EXPRESSIONS -> DailyWordImportAction.PENDING_CAPACITY
                else -> DailyWordImportAction.INSERT
            }
            val saved = existing.firstOrNull { it.languageID == language && normalizeKey(it.text) == key }
            output += DailyWordImportPlan(owner, language, originKey, key, text,
                saved?.meaning?.takeIf(String::isNotBlank) ?: candidate.meaning.trim(), candidate.kind, action, now)
            if (action == DailyWordImportAction.INSERT) { known += key; expressionCount++ }
        }
        return output
    }

    internal fun normalizeKey(value: String): String = value.trim().lowercase(Locale.ROOT)
}

fun planManualDailyWord(
    markID: String,
    text: String,
    meaning: String,
    kind: String,
    owner: String,
    language: String,
    existing: List<DailyWordExisting>,
    receipts: Set<String>,
    suppressions: Set<String>,
    now: Double,
): List<DailyWordImportPlan> {
    require(runCatching { UUID.fromString(markID) }.isSuccess)
    val cleanedText = text.trim()
    val cleanedMeaning = meaning.trim()
    require(cleanedText.isNotEmpty() && cleanedText.codePointCount(0, cleanedText.length) <= 120)
    require(cleanedMeaning.isNotEmpty() && cleanedMeaning.codePointCount(0, cleanedMeaning.length) <= 300)
    require(kind == "word" || kind == "expression")
    val review = DailyWordsReview("mark", language,
        listOf(DailyWordCandidate(markID, kind, cleanedText, cleanedMeaning, "supported", origin = "learner_mark")))
    // An explicit learner save is the sole path allowed to clear this one suppression;
    // the writer performs the actual delete atomically with the word and receipt.
    val manualKey = "$language:${DailyWordsImport.normalizeKey(cleanedText)}"
    return DailyWordsImport.planDailyWordsImport(review, owner, language, existing, receipts,
        suppressions - manualKey, now)
}

fun planDailyWordsImport(
    result: DailyWordsReview,
    owner: String,
    language: String,
    existing: List<DailyWordExisting>,
    receipts: Set<String>,
    suppressions: Set<String>,
    now: Double,
): List<DailyWordImportPlan> = DailyWordsImport.planDailyWordsImport(result, owner, language, existing, receipts, suppressions, now)
