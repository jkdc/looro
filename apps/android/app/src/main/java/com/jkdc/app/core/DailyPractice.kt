package com.jkdc.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class DailyPracticePackage(
    val schemaVersion: Int,
    val packageVersionId: String,
    val contentHash: String,
    val editorialDate: String,
    val language: String,
    val profileVersion: String,
    val topic: DailyTopic,
    val objective: String,
    val challengeReference: String,
    val estimatedMinutes: DailyEstimatedMinutes,
    val expressions: List<DailyExpression>,
    val listening: DailyListening,
    val speaking: DailySpeaking,
    val goals: List<DailyGoal>,
    val practice: DailyPracticeSection,
    val supportLocales: List<String>,
)

@Serializable data class DailyTopic(val id: String, val title: String, val summary: String)
@Serializable data class DailyEstimatedMinutes(val preparation: Int, val listening: Int, val speaking: Int, val practice: Int, val review: Int, val total: Int)
@Serializable data class DailyExpression(val id: String, val text: String, val meanings: DailyLocales, val examples: DailyLocales)
@Serializable data class DailyLocales(val en: String, val es: String)
@Serializable data class DailyListening(val speechLocale: String, val transcript: String, val questions: List<DailyQuestion>)
@Serializable data class DailySpeaking(val role: String, val opening: String, val topicAnchors: List<String>, val closingCue: String)
@Serializable data class DailyGoal(val id: String, val task: String, val hints: DailyLocales, val sentenceStarters: DailyLocales, val exampleResponses: DailyLocales)
@Serializable data class DailyPracticeSection(val questions: List<DailyQuestion>)
@Serializable data class DailyQuestion(val id: String, val kind: String, val prompt: String, val options: List<DailyOption>, val hints: DailyLocales)
@Serializable data class DailyOption(val id: String, val text: String)

object DailyPracticeCodec {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
    fun decodePackage(source: String): DailyPracticePackage = json.decodeFromString<DailyPracticePackage>(source).also {
        require(it.schemaVersion == 1) { "unsupported daily package schema" }
        require(it.language == "en" && it.profileVersion == "en-v1") { "daily language is not enabled" }
        val speechLocale = java.util.Locale.forLanguageTag(it.listening.speechLocale)
        require(speechLocale.language == it.language &&
            speechLocale.toLanguageTag().equals(it.listening.speechLocale, ignoreCase = true)) {
            "invalid daily speech locale"
        }
        require(it.expressions.size == 5 && it.listening.questions.size == 3 && it.goals.size == 3 && it.practice.questions.size == 4) {
            "invalid daily package shape"
        }
    }
    fun encodePackage(value: DailyPracticePackage): String = json.encodeToString(value)
}

@Serializable
enum class DailyAttemptStage { PREPARATION, LISTENING, SPEAKING, PRACTICE, REVIEW }

@Serializable
data class DailySpeechFragment(
    val id: String,
    val role: String,
    val text: String,
    val voiceSegmentId: String,
    val sequence: Int,
    val transcriptionUncertain: Boolean = false,
)

/** Device-local draft, independent of the editorial date so it resumes after midnight. */
@Serializable
data class DailyAttemptLocalState(
    val attemptId: String,
    val createIdempotencyKey: String = attemptId,
    val createStatus: String = "pending",
    val revision: Long = 0,
    val nextEventSequence: Long = 0,
    val stage: DailyAttemptStage = DailyAttemptStage.PREPARATION,
    val answers: Map<String, String?> = emptyMap(),
    val skippedStages: Set<DailyAttemptStage> = emptySet(),
    val speakingOpen: Boolean = false,
    val finished: Boolean = false,
    val helpUsed: Set<String> = emptySet(),
    val markedTargets: Set<String> = emptySet(),
    val pendingSpeakingFragments: List<DailySpeechFragment> = emptyList(),
) {
    companion object { fun start(attemptId: String, createIdempotencyKey: String = attemptId) =
        DailyAttemptLocalState(attemptId, createIdempotencyKey) }
}

/** Pure local reducer; elapsed time never closes or advances a practice attempt. */
object DailyAttemptReducer {
    private val json = Json { explicitNulls = false }
    private const val SAFE_FRAGMENT_CHUNK_BYTES = 22 * 1024
    private const val MAX_FRAGMENTS_PER_EVENT = 20

    fun answer(state: DailyAttemptLocalState, questionId: String, value: String?): DailyAttemptLocalState {
        require(!state.finished && questionId.isNotBlank() && (value == null || value.length <= 120))
        return state.copy(answers = state.answers + (questionId to value))
    }

    /** Records only identifiers the server accepts; a malformed help id never fails the stage. */
    fun markHelpUsed(state: DailyAttemptLocalState, ids: List<String>): DailyAttemptLocalState {
        val valid = ids.filter { it.matches(Regex("^[a-z0-9_-]{1,64}$")) }
        return state.copy(helpUsed = state.helpUsed + valid)
    }

    /** Help evidence belongs to the stage that offered it; sends at most the server limit. */
    fun helpUsedForStage(helpUsed: Set<String>, allowedIds: Set<String>): List<String> =
        helpUsed.filter(allowedIds::contains).sorted().take(8)

    fun markTarget(state: DailyAttemptLocalState, expressionId: String): DailyAttemptLocalState {
        require(!state.finished && state.stage != DailyAttemptStage.REVIEW && expressionId.matches(Regex("^[a-z0-9_-]{1,64}$")))
        return state.copy(markedTargets = state.markedTargets + expressionId)
    }

    fun speakingFragmentChunks(fragments: List<DailySpeechFragment>): List<List<DailySpeechFragment>> {
        val chunks = mutableListOf<List<DailySpeechFragment>>()
        var current = mutableListOf<DailySpeechFragment>()
        for (fragment in fragments) {
            require(fragment.text.isNotBlank() && fragment.text.codePointCount(0, fragment.text.length) <= 1_000)
            val candidate = current + fragment
            if (current.isNotEmpty() && (current.size >= MAX_FRAGMENTS_PER_EVENT ||
                    encodedFragmentBytes(candidate) > SAFE_FRAGMENT_CHUNK_BYTES)) {
                chunks += current.toList()
                current = mutableListOf(fragment)
            } else current = candidate.toMutableList()
            require(encodedFragmentBytes(current) <= SAFE_FRAGMENT_CHUNK_BYTES) { "daily_fragment_too_large" }
        }
        if (current.isNotEmpty()) chunks += current.toList()
        return chunks
    }

    fun isServerAttemptInProgress(state: String?): Boolean = state == "in_progress"

    /** A successfully replayed event advances the server revision exactly once. */
    fun acceptedRebasedRevision(conflictRevision: Long, acceptedRevision: Long?): Long =
        acceptedRevision?.takeIf { it == conflictRevision + 1 } ?: error("daily_rebase_revision_mismatch")

    /** Reconstructs navigation from canonical server progress, keeping skipped stages distinct locally. */
    fun restoreServerProgress(state: DailyAttemptLocalState, completedStages: Set<String>,
        skippedStages: Set<String>, attemptFinished: Boolean = false): DailyAttemptLocalState {
        val stageNames = listOf("preparation", "listening", "speaking", "practice")
        val resolved = completedStages + skippedStages
        val nextIndex = stageNames.indexOfFirst { it !in resolved }.let { if (it < 0) stageNames.size else it }
        val next = when (nextIndex) {
            0 -> DailyAttemptStage.PREPARATION
            1 -> DailyAttemptStage.LISTENING
            2 -> DailyAttemptStage.SPEAKING
            3 -> DailyAttemptStage.PRACTICE
            else -> DailyAttemptStage.REVIEW
        }
        val localSkipped = skippedStages.mapNotNull { name -> when (name) {
            "preparation" -> DailyAttemptStage.PREPARATION
            "listening" -> DailyAttemptStage.LISTENING
            "speaking" -> DailyAttemptStage.SPEAKING
            "practice" -> DailyAttemptStage.PRACTICE
            else -> null
        } }.toSet()
        return state.copy(stage = if (attemptFinished) DailyAttemptStage.REVIEW else next,
            skippedStages = localSkipped, speakingOpen = false, finished = state.finished || attemptFinished)
    }

    fun encodedFragmentBytes(fragments: List<DailySpeechFragment>): Int =
        json.encodeToString(fragments).toByteArray(Charsets.UTF_8).size

    fun canRebaseConfirmedConflict(eventType: String, stageResolved: Boolean, attemptOpen: Boolean,
        sameAttempt: Boolean, samePackage: Boolean, evidenceCollision: Boolean): Boolean {
        if (!attemptOpen || !sameAttempt || !samePackage || evidenceCollision) return false
        return when (eventType) {
            "word_marked" -> true
            "preparation_completed", "stage_submitted", "stage_skipped", "speaking_segment_saved", "speaking_closed" -> !stageResolved
            else -> false
        }
    }

    fun queueSpeakingFragments(state: DailyAttemptLocalState, fragments: List<DailySpeechFragment>): DailyAttemptLocalState {
        require(!state.finished && state.stage == DailyAttemptStage.SPEAKING && state.speakingOpen)
        speakingFragmentChunks(fragments)
        return state.copy(pendingSpeakingFragments = state.pendingSpeakingFragments + fragments)
    }

    fun dequeueSpeakingFragments(state: DailyAttemptLocalState, count: Int): DailyAttemptLocalState {
        require(count in 1..state.pendingSpeakingFragments.size)
        return state.copy(pendingSpeakingFragments = state.pendingSpeakingFragments.drop(count))
    }

    fun submitStage(state: DailyAttemptLocalState, stage: DailyAttemptStage): DailyAttemptLocalState {
        require(!state.finished && stage == state.stage && stage in setOf(DailyAttemptStage.LISTENING, DailyAttemptStage.PRACTICE))
        return state.copy(stage = if (stage == DailyAttemptStage.LISTENING) DailyAttemptStage.SPEAKING else DailyAttemptStage.REVIEW)
    }

    fun skip(state: DailyAttemptLocalState, stage: DailyAttemptStage): DailyAttemptLocalState {
        require(!state.finished && stage == state.stage && stage != DailyAttemptStage.REVIEW)
        val next = when (stage) {
            DailyAttemptStage.PREPARATION -> DailyAttemptStage.LISTENING
            DailyAttemptStage.LISTENING -> DailyAttemptStage.SPEAKING
            DailyAttemptStage.SPEAKING -> DailyAttemptStage.PRACTICE
            DailyAttemptStage.PRACTICE -> DailyAttemptStage.REVIEW
            DailyAttemptStage.REVIEW -> error("review cannot be skipped")
        }
        return state.copy(stage = next, skippedStages = state.skippedStages + stage,
            speakingOpen = if (stage == DailyAttemptStage.SPEAKING) false else state.speakingOpen)
    }

    fun stageSkippedEvent(stage: DailyAttemptStage, reason: String, activeMs: Long = 0): kotlinx.serialization.json.JsonObject {
        require(stage != DailyAttemptStage.REVIEW)
        require(reason in setOf("learner_choice", "microphone_denied", "network_error", "funding_unavailable", "local_tts_unavailable"))
        val stageName = when (stage) {
            DailyAttemptStage.PREPARATION -> "preparation"
            DailyAttemptStage.LISTENING -> "listening"
            DailyAttemptStage.SPEAKING -> "speaking"
            DailyAttemptStage.PRACTICE -> "practice"
            DailyAttemptStage.REVIEW -> error("review cannot be skipped")
        }
        return kotlinx.serialization.json.buildJsonObject {
            put("type", JsonPrimitive("stage_skipped"))
            put("stage", JsonPrimitive(stageName))
            put("activeMs", JsonPrimitive(activeMs.coerceAtLeast(0L)))
            put("reason", JsonPrimitive(reason))
        }
    }

    /** The closed stage submission exactly as the screen sends it; answers and
     *  per-stage help evidence travel with the attempt's own wording. */
    fun stageSubmittedEvent(state: DailyAttemptLocalState, questionIds: List<String>, stage: DailyAttemptStage,
        allowedHelpIds: Set<String>, activeMs: Long): kotlinx.serialization.json.JsonObject {
        require(stage == DailyAttemptStage.LISTENING || stage == DailyAttemptStage.PRACTICE)
        return kotlinx.serialization.json.buildJsonObject {
            put("type", JsonPrimitive("stage_submitted"))
            put("stage", JsonPrimitive(if (stage == DailyAttemptStage.LISTENING) "listening" else "practice"))
            put("activeMs", JsonPrimitive(activeMs.coerceAtLeast(0L)))
            put("answers", kotlinx.serialization.json.buildJsonArray {
                questionIds.forEach { questionId ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("questionId", JsonPrimitive(questionId))
                        val answer = state.answers[questionId]
                        if (answer != null) put("value", JsonPrimitive(answer)) else put("value", kotlinx.serialization.json.JsonNull)
                    })
                }
            })
            put("helpUsed", kotlinx.serialization.json.buildJsonArray {
                helpUsedForStage(state.helpUsed, allowedHelpIds).forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    fun completePreparation(state: DailyAttemptLocalState): DailyAttemptLocalState {
        require(!state.finished && state.stage == DailyAttemptStage.PREPARATION)
        return state.copy(stage = DailyAttemptStage.LISTENING)
    }

    fun openSpeaking(state: DailyAttemptLocalState): DailyAttemptLocalState {
        require(!state.finished && state.stage == DailyAttemptStage.SPEAKING)
        return state.copy(speakingOpen = true)
    }

    fun closeSpeaking(state: DailyAttemptLocalState): DailyAttemptLocalState {
        require(!state.finished && state.stage == DailyAttemptStage.SPEAKING && state.speakingOpen)
        return state.copy(stage = DailyAttemptStage.PRACTICE, speakingOpen = false)
    }

    fun finish(state: DailyAttemptLocalState): DailyAttemptLocalState {
        require(!state.finished && state.stage == DailyAttemptStage.REVIEW && !state.speakingOpen)
        return state.copy(finished = true)
    }

    fun afterQueuedEvent(state: DailyAttemptLocalState): DailyAttemptLocalState =
        state.copy(revision = state.revision + 1, nextEventSequence = state.nextEventSequence + 1)

    fun isFinished(state: DailyAttemptLocalState, elapsedMinutes: Int = 0): Boolean = state.finished
    fun encode(state: DailyAttemptLocalState): String = json.encodeToString(state)
    fun decode(value: String): DailyAttemptLocalState = json.decodeFromString(value)
}
