package com.jkdc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import com.jkdc.app.AppViewModel
import com.jkdc.app.R
import com.jkdc.app.core.DailyAttemptLocalState
import com.jkdc.app.core.DailyAttemptReducer
import com.jkdc.app.core.DailyAttemptStage
import com.jkdc.app.core.DailyPracticePackage
import com.jkdc.app.core.DailyQuestion
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private fun takeCodePoints(value: String, maximum: Int): String =
    value.substring(0, value.offsetByCodePoints(0, minOf(maximum, value.codePointCount(0, value.length))))

@Composable
fun DailyPracticeScreen(vm: AppViewModel, onBack: () -> Unit, onOpenSpeaking: (String) -> Unit) {
    DisposableEffect(vm) { onDispose { vm.stopDailyListening() } }
    val owner = vm.loadedLearningIdentity
    val language = vm.language.id
    val scope = rememberCoroutineScope()
    var dailyPackage by remember { mutableStateOf<DailyPracticePackage?>(null) }
    var draft by remember { mutableStateOf<DailyAttemptLocalState?>(null) }
    var completedHistory by remember { mutableStateOf<List<Pair<DailyPracticePackage, DailyAttemptLocalState>>>(emptyList()) }
    var selectedHistory by remember { mutableStateOf<Pair<DailyPracticePackage, DailyAttemptLocalState>?>(null) }
    var historyReview by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var review by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var createUnknown by rememberSaveable { mutableStateOf(false) }
    var retryEventAvailable by rememberSaveable { mutableStateOf(false) }
    var outboxBlocked by rememberSaveable { mutableStateOf(false) }
    var showTranscript by rememberSaveable { mutableStateOf(false) }
    var showHints by rememberSaveable { mutableStateOf(false) }
    val explanationLanguage = vm.archive.preferences.meaningLanguage.substringBefore('-')
        .takeIf { it == "en" || it == "es" } ?: "en"

    LaunchedEffect(owner, language) {
        loading = true; error = null; dailyPackage = null; draft = null; review = null
        completedHistory = emptyList(); selectedHistory = null; historyReview = null
        if (owner != null && language == "en") {
            try {
                val cached = vm.cachedDailyAttempts(owner).firstOrNull { !it.second.finished }
                completedHistory = vm.cachedDailyAttempts(owner).filter { it.second.finished }
                if (owner != vm.loadedLearningIdentity) return@LaunchedEffect
                if (cached != null) {
                    dailyPackage = cached.first
                    draft = cached.second
                    createUnknown = cached.second.createStatus == "pending"
                    retryEventAvailable = vm.hasUnknownDailyEvent(owner, cached.second.attemptId)
                    outboxBlocked = vm.hasBlockingDailyEvent(owner, cached.second.attemptId)
                    if (cached.second.stage == DailyAttemptStage.REVIEW && cached.second.createStatus == "acknowledged") {
                        review = runCatching { vm.dailyReview(owner, cached.second.attemptId) }.getOrNull()
                    }
                } else dailyPackage = vm.dailyCurrentPackage()
                if (owner != vm.loadedLearningIdentity) return@LaunchedEffect
            } catch (failure: Exception) {
                if (owner == vm.loadedLearningIdentity) error = failure.message ?: "daily_load_failed"
            }
        }
        if (owner == vm.loadedLearningIdentity) loading = false
    }

    LaunchedEffect(owner, selectedHistory?.second?.attemptId) {
        val selected = selectedHistory ?: return@LaunchedEffect
        historyReview = null
        try {
            val loaded = vm.dailyReview(owner ?: return@LaunchedEffect, selected.second.attemptId)
            if (owner == vm.loadedLearningIdentity && selectedHistory?.second?.attemptId == selected.second.attemptId)
                historyReview = loaded
        } catch (_: Exception) { }
    }

    val pkg = dailyPackage
    val active = draft
    Surface(Modifier.fillMaxSize().testTag("daily-practice-screen"), color = AppColors.Cream) {
        if (loading) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.daily_loading), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        } else if (selectedHistory != null) {
            DailyHistoryContent(selectedHistory!!, historyReview) { selectedHistory = null; historyReview = null }
        } else if (pkg == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.daily_back)) }
                Text(stringResource(R.string.daily_unavailable), style = MaterialTheme.typography.titleMedium)
                error?.let { Text(stringResource(R.string.daily_error_persistent, it), color = AppColors.Red,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                Button(onClick = { loading = true; scope.launch {
                    try { dailyPackage = vm.dailyCurrentPackage(); error = null }
                    catch (failure: Exception) { error = failure.message ?: "daily_load_failed" }
                    finally { loading = false }
                } }) { Text(stringResource(R.string.daily_retry)) }
                if (completedHistory.isNotEmpty()) {
                    Text(stringResource(R.string.daily_history), style = MaterialTheme.typography.titleMedium)
                    completedHistory.forEach { item ->
                        TextButton(onClick = { selectedHistory = item; historyReview = null }) {
                            Text("${item.first.editorialDate} · ${item.first.topic.title}")
                        }
                    }
                }
            }
        } else if (active == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.daily_back)) }
                Text(stringResource(R.string.daily_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.daily_date, pkg.editorialDate), color = AppColors.Secondary)
                Text(pkg.topic.title, style = MaterialTheme.typography.titleLarge)
                Text(pkg.topic.summary)
                Text(stringResource(R.string.daily_duration, pkg.estimatedMinutes.total), color = AppColors.Secondary)
                Text(stringResource(R.string.daily_consent_summary), color = AppColors.Secondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(stringResource(R.string.daily_retention_notice), color = AppColors.Secondary)
                error?.let { Text(stringResource(R.string.daily_error_persistent, it), color = AppColors.Red,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                Button(enabled = !busy && !vm.dailySpeakingSaving && owner != null, onClick = { scope.launch {
                    busy = true; error = null
                    try {
                        val (created, _) = vm.createDailyAttempt(owner ?: return@launch, pkg, explanationLanguage)
                        draft = created; createUnknown = false
                    } catch (failure: Exception) {
                        error = failure.message ?: "daily_create_failed"
                        val cached = owner?.let { runCatching { vm.cachedDailyAttempts(it) }.getOrNull() }
                            ?.firstOrNull { it.first.packageVersionId == pkg.packageVersionId }
                        draft = cached?.second
                        createUnknown = cached?.second?.createStatus == "pending"
                    } finally { busy = false }
                } }) { Text(stringResource(if (busy) R.string.daily_saving else R.string.daily_start)) }
                if (completedHistory.isNotEmpty()) {
                    Text(stringResource(R.string.daily_history), style = MaterialTheme.typography.titleMedium)
                    completedHistory.forEach { item ->
                        TextButton(onClick = { selectedHistory = item; historyReview = null }) {
                            Text("${item.first.editorialDate} · ${item.first.topic.title}")
                        }
                    }
                }
            }
        } else {
            DailyAttemptContent(vm, pkg, active, owner, review, busy || vm.dailySpeakingSaving, error, showTranscript, showHints, outboxBlocked,
                onBack, onOpenSpeaking,
                onDraft = { next -> scope.launch {
                    busy = true
                    try { vm.persistDailyDraft(owner ?: return@launch, pkg, next); draft = next; error = null }
                    catch (failure: Exception) { error = failure.message ?: "daily_save_failed" }
                    finally { busy = false }
                } },
                onPrepareSpeaking = { opened, attemptID -> scope.launch {
                    busy = true
                    try {
                        vm.persistDailyDraft(owner ?: return@launch, pkg, opened)
                        draft = opened; error = null
                        onOpenSpeaking(attemptID)
                    } catch (failure: Exception) { error = failure.message ?: "daily_save_failed" }
                    finally { busy = false }
                } },
                onEvent = { staged, event -> scope.launch {
                    busy = true; error = null
                    try {
                        val (saved, response) = vm.appendDailyEvent(owner ?: return@launch, pkg, staged, event)
                        draft = saved
                        outboxBlocked = vm.hasBlockingDailyEvent(owner, saved.attemptId)
                        if (response["state"]?.toString()?.trim('"') in setOf("review_pending", "completed", "partial")) {
                            review = vm.dailyReview(owner, saved.attemptId)
                        }
                    } catch (failure: Exception) {
                        val restored = owner?.let { runCatching { vm.cachedDailyAttempts(it) }.getOrNull() }
                            ?.firstOrNull { it.second.attemptId == staged.attemptId }
                        draft = restored?.second ?: staged
                        error = failure.message ?: "daily_save_failed"
                        retryEventAvailable = owner?.let { runCatching { vm.hasUnknownDailyEvent(it, staged.attemptId) }.getOrDefault(false) } ?: false
                        outboxBlocked = owner?.let { runCatching { vm.hasBlockingDailyEvent(it, staged.attemptId) }.getOrDefault(true) } ?: true
                    }
                    finally { busy = false }
                } },
                onReview = { scope.launch {
                    busy = true
                    try { review = vm.dailyReview(owner ?: return@launch, active.attemptId); error = null }
                    catch (failure: Exception) { error = failure.message ?: "daily_review_unavailable" }
                    finally { busy = false }
                } },
                onImport = { accepted -> scope.launch {
                    busy = true
                    try {
                        val saved = vm.importDailyWords(owner ?: return@launch, pkg, active.attemptId, accepted)
                        error = "words:${saved.inserted},${saved.existing},${saved.suppressed},${saved.pendingCapacity}"
                    } catch (failure: Exception) { error = failure.message ?: "daily_words_failed" }
                    finally { busy = false }
                } },
                onTranscript = {
                    val showing = !showTranscript
                    showTranscript = showing
                    if (showing) scope.launch {
                        val next = DailyAttemptReducer.markHelpUsed(active, listOf("transcript"))
                        try { vm.persistDailyDraft(owner ?: return@launch, pkg, next); draft = next }
                        catch (failure: Exception) { error = failure.message ?: "daily_save_failed" }
                    }
                },
                onHints = {
                    val showing = !showHints
                    showHints = showing
                    if (showing) scope.launch {
                        val ids = when (active.stage) {
                            DailyAttemptStage.PREPARATION -> pkg.goals.map { "hint_${it.id}" }
                            DailyAttemptStage.LISTENING -> pkg.listening.questions.map { "hint_${it.id}" }
                            DailyAttemptStage.PRACTICE -> pkg.practice.questions.map { "hint_${it.id}" }
                            else -> emptyList()
                        }
                        val next = DailyAttemptReducer.markHelpUsed(active, ids)
                        try { vm.persistDailyDraft(owner ?: return@launch, pkg, next); draft = next }
                        catch (failure: Exception) { error = failure.message ?: "daily_save_failed" }
                    }
                },
                onRetryCreate = { scope.launch {
                    busy = true
                    try {
                        val (accepted, _) = vm.retryDailyAttemptCreate(owner ?: return@launch, pkg, active, explanationLanguage)
                        draft = accepted; createUnknown = false; error = null
                    }
                    catch (failure: Exception) { error = failure.message ?: "daily_create_failed"; createUnknown = failure is com.jkdc.app.network.DailyPracticeFailure.OutcomeUnknown }
                    finally { busy = false }
                } },
                onRetryEvent = { scope.launch {
                    busy = true
                    try { vm.retryUnknownDailyEvent(owner ?: return@launch, active.attemptId); error = null; retryEventAvailable = false }
                    catch (failure: Exception) { error = failure.message ?: "daily_save_failed" }
                    finally {
                        outboxBlocked = owner?.let { runCatching { vm.hasBlockingDailyEvent(it, active.attemptId) }.getOrDefault(true) } ?: true
                        retryEventAvailable = owner?.let { runCatching { vm.hasUnknownDailyEvent(it, active.attemptId) }.getOrDefault(false) } ?: false
                        busy = false
                    }
                } },
                retryEventAvailable = retryEventAvailable,
                createUnknown = createUnknown)
        }
    }
}

@Composable
private fun DailyHistoryContent(
    historic: Pair<DailyPracticePackage, DailyAttemptLocalState>,
    result: kotlinx.serialization.json.JsonObject?,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.daily_history_back)) }
        Text(historic.first.topic.title, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.daily_date, historic.first.editorialDate), color = AppColors.Secondary)
        Text(stringResource(R.string.daily_history_review), style = MaterialTheme.typography.titleMedium)
        if (result == null) Text(stringResource(R.string.daily_review_waiting))
        else {
            result["strengths"]?.jsonArray?.forEach { Text("✓ ${it.jsonPrimitive.content}") }
            result["priorities"]?.jsonArray?.forEach { Text("• ${it.jsonPrimitive.content}") }
            result["errors"]?.jsonArray?.forEach { errorItem ->
                val value = errorItem.jsonObject
                Text("${value["quote"]?.jsonPrimitive?.content.orEmpty()} → ${value["correction"]?.jsonPrimitive?.content.orEmpty()}")
                Text(value["explanation"]?.jsonPrimitive?.content.orEmpty(), color = AppColors.Secondary)
            }
        }
    }
}

@Composable
private fun DailyAttemptContent(vm: AppViewModel, pkg: DailyPracticePackage, draft: DailyAttemptLocalState,
    owner: String?, review: kotlinx.serialization.json.JsonObject?, busy: Boolean, error: String?,
    showTranscript: Boolean, showHints: Boolean, outboxBlocked: Boolean, onBack: () -> Unit, onOpenSpeaking: (String) -> Unit,
    onDraft: (DailyAttemptLocalState) -> Unit, onPrepareSpeaking: (DailyAttemptLocalState, String) -> Unit,
    onEvent: (DailyAttemptLocalState, kotlinx.serialization.json.JsonObject) -> Unit,
    onReview: () -> Unit, onImport: (kotlinx.serialization.json.JsonObject) -> Unit,
    onTranscript: () -> Unit, onHints: () -> Unit, onRetryCreate: () -> Unit, onRetryEvent: () -> Unit,
    retryEventAvailable: Boolean, createUnknown: Boolean) {
    val language = vm.archive.preferences.meaningLanguage.substringBefore('-').takeIf { it == "es" } ?: "en"
    var manualText by rememberSaveable(draft.attemptId) { mutableStateOf("") }
    var manualMeaning by rememberSaveable(draft.attemptId) { mutableStateOf("") }
    var manualKind by rememberSaveable(draft.attemptId) { mutableStateOf("expression") }
    var manualMessage by rememberSaveable(draft.attemptId) { mutableStateOf<String?>(null) }
    var savingManualWord by rememberSaveable(draft.attemptId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text(stringResource(R.string.daily_back)) } }
        item { Text(pkg.topic.title, style = MaterialTheme.typography.headlineSmall) }
        item { Text(stringResource(R.string.daily_saved_on_device), color = AppColors.Secondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        item { Text(stringResource(R.string.daily_retention_notice), color = AppColors.Secondary) }
        item { Text(stringResource(R.string.daily_stage, stringResource(when (draft.stage) {
            DailyAttemptStage.PREPARATION -> R.string.daily_preparation
            DailyAttemptStage.LISTENING -> R.string.daily_listening
            DailyAttemptStage.SPEAKING -> R.string.daily_speaking
            DailyAttemptStage.PRACTICE -> R.string.daily_practice
            DailyAttemptStage.REVIEW -> R.string.daily_review
        }))) }
        error?.let { message -> item {
            val savedCount = message.removePrefix("saved:").toIntOrNull()
            val outcomes = message.removePrefix("words:").split(',').mapNotNull(String::toIntOrNull)
            Text(when {
                outcomes.size == 4 -> stringResource(R.string.daily_words_outcomes, outcomes[0], outcomes[1], outcomes[2], outcomes[3])
                savedCount == null -> stringResource(R.string.daily_error_persistent, message)
                savedCount == 0 -> stringResource(R.string.daily_words_none)
                else -> stringResource(R.string.daily_words_saved, savedCount)
            },
                color = if (savedCount == null && outcomes.size != 4) AppColors.Red else AppColors.Secondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        } }
        if (outboxBlocked) item { Text(stringResource(R.string.daily_pending_save_block), color = AppColors.Red,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
        if (createUnknown) item { Button(enabled = !busy, onClick = onRetryCreate) { Text(stringResource(R.string.daily_retry_same_attempt)) } }
        if (retryEventAvailable) item { Button(enabled = !busy, onClick = onRetryEvent) { Text(stringResource(R.string.daily_retry)) } }
        when (draft.stage) {
            DailyAttemptStage.PREPARATION -> {
                item { Text(pkg.objective) }
                items(pkg.expressions) { expression ->
                    Surface(color = AppColors.Surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(expression.text, style = MaterialTheme.typography.titleMedium)
                            Text(if (language == "es") expression.meanings.es else expression.meanings.en)
                            if (expression.id !in draft.markedTargets) TextButton(enabled = !busy && !outboxBlocked,
                                onClick = {
                                    val meaning = if (language == "es") expression.meanings.es else expression.meanings.en
                                    val markId = java.util.UUID.randomUUID().toString()
                                    scope.launch {
                                        savingManualWord = true
                                        try {
                                            val outcome = vm.saveDailyManualWord(owner ?: return@launch, pkg,
                                                draft.attemptId, markId, expression.text, meaning, "expression")
                                            if (outcome.pendingCapacity > 0) manualMessage = "capacity" else
                                                manualMessage = if (outcome.inserted > 0 || outcome.existing > 0) "saved" else "error"
                                            val marked = DailyAttemptReducer.markTarget(draft, expression.id)
                                            onEvent(marked, buildJsonObject {
                                                put("type", "word_marked"); put("markId", markId)
                                                put("text", expression.text); put("meaning", meaning)
                                                put("kind", "expression"); put("sourceQuestionId", kotlinx.serialization.json.JsonNull)
                                            })
                                        } catch (_: Exception) { manualMessage = "error" }
                                        finally { savingManualWord = false }
                                    }
                                }) { Text(stringResource(R.string.daily_mark_for_review)) }
                            else Text(stringResource(R.string.daily_marked_for_review), color = AppColors.Secondary)
                        }
                    }
                }
                items(pkg.goals) { goal ->
                    Text(if (language == "es") goal.task else goal.task, style = MaterialTheme.typography.bodyMedium)
                    if (showHints) Text(if (language == "es") goal.hints.es else goal.hints.en, color = AppColors.Secondary)
                }
                item { TextButton(onClick = onHints) { Text(stringResource(if (showHints) R.string.daily_hide_hint else R.string.daily_show_hint)) } }
                item { Button(enabled = !busy && !outboxBlocked, onClick = { onEvent(DailyAttemptReducer.completePreparation(draft),
                    kotlinx.serialization.json.buildJsonObject { put("type", "preparation_completed"); put("activeMs", 0) }) }) {
                    Text(stringResource(R.string.daily_continue))
                } }
                item { TextButton(enabled = !busy && !outboxBlocked, onClick = { onEvent(DailyAttemptReducer.skip(draft, draft.stage),
                    DailyAttemptReducer.stageSkippedEvent(draft.stage, "learner_choice")) }) {
                    Text(stringResource(R.string.daily_skip))
                } }
            }
            DailyAttemptStage.LISTENING, DailyAttemptStage.PRACTICE -> {
                val questions = if (draft.stage == DailyAttemptStage.LISTENING) pkg.listening.questions else pkg.practice.questions
                if (draft.stage == DailyAttemptStage.LISTENING) {
                    item { Text(pkg.objective) }
                    item { DailyListeningPlayer(vm, pkg) {
                        onEvent(
                            DailyAttemptReducer.skip(draft, DailyAttemptStage.LISTENING),
                            DailyAttemptReducer.stageSkippedEvent(DailyAttemptStage.LISTENING, "local_tts_unavailable"),
                        )
                    } }
                    item { TextButton(onClick = onTranscript) { Text(stringResource(if (showTranscript) R.string.daily_hide_transcript else R.string.daily_show_transcript)) } }
                    if (showTranscript) item { Text(pkg.listening.transcript, color = AppColors.Secondary) }
                }
                items(questions, key = { it.id }) { question ->
                    DailyQuestionCard(question, draft.answers[question.id], showHints, language,
                        onAnswer = { value -> onDraft(DailyAttemptReducer.answer(draft, question.id, value)) })
                }
                item { TextButton(onClick = onHints) { Text(stringResource(if (showHints) R.string.daily_hide_hint else R.string.daily_show_hint)) } }
                item { Button(enabled = !busy && !outboxBlocked, onClick = {
                    val next = DailyAttemptReducer.submitStage(draft, draft.stage)
                    val event = kotlinx.serialization.json.buildJsonObject {
                        put("type", "stage_submitted"); put("stage", if (draft.stage == DailyAttemptStage.LISTENING) "listening" else "practice"); put("activeMs", 0)
                        put("answers", kotlinx.serialization.json.buildJsonArray {
                            questions.forEach { q -> add(kotlinx.serialization.json.buildJsonObject {
                                put("questionId", q.id); draft.answers[q.id]?.let { put("value", it) } ?: put("value", kotlinx.serialization.json.JsonNull)
                            }) }
                        })
                        put("helpUsed", kotlinx.serialization.json.buildJsonArray {
                            draft.helpUsed.sorted().take(8).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        })
                    }
                    onEvent(next, event)
                }) { Text(stringResource(R.string.daily_submit)) } }
                item { TextButton(enabled = !busy && !outboxBlocked, onClick = { onEvent(DailyAttemptReducer.skip(draft, draft.stage),
                    DailyAttemptReducer.stageSkippedEvent(draft.stage, "learner_choice")) }) {
                    Text(stringResource(R.string.daily_skip))
                } }
            }
            DailyAttemptStage.SPEAKING -> {
                item { Text(pkg.speaking.opening, style = MaterialTheme.typography.bodyLarge) }
                items(pkg.speaking.topicAnchors) { Text("• $it") }
                item { Text(pkg.speaking.closingCue, color = AppColors.Secondary) }
                item { Button(enabled = !busy && !outboxBlocked, onClick = {
                    val opened = DailyAttemptReducer.openSpeaking(draft)
                    onPrepareSpeaking(opened, draft.attemptId)
                }) { Text(stringResource(if (draft.speakingOpen) R.string.daily_resume_speaking else R.string.daily_open_speaking)) } }
                if (draft.speakingOpen && vm.state in setOf("active", "connecting", "closing")) item { Button(enabled = !busy, onClick = { vm.endDailySpeaking() }) {
                    Text(stringResource(R.string.daily_close_speaking))
                } }
                item { TextButton(enabled = !busy && !outboxBlocked, onClick = { onEvent(DailyAttemptReducer.skip(draft, draft.stage),
                    DailyAttemptReducer.stageSkippedEvent(draft.stage, "learner_choice")) }) {
                    Text(stringResource(R.string.daily_skip))
                } }
            }
            DailyAttemptStage.REVIEW -> {
                item { Button(enabled = !busy && !outboxBlocked, onClick = {
                    onEvent(DailyAttemptReducer.finish(draft), kotlinx.serialization.json.buildJsonObject {
                        put("type", "finished"); put("stopReason", "learner_choice")
                    })
                }) { Text(stringResource(R.string.daily_finish)) } }
                item { Button(enabled = !busy && !outboxBlocked, onClick = onReview) { Text(stringResource(R.string.daily_review_refresh)) } }
                item {
                    Text(stringResource(R.string.daily_manual_word_heading), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.daily_manual_word_notice), color = AppColors.Secondary)
                    TextField(value = manualText, onValueChange = { manualText = takeCodePoints(it, 120) },
                        label = { Text(stringResource(R.string.daily_manual_word_label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    TextField(value = manualMeaning, onValueChange = { manualMeaning = takeCodePoints(it, 300) },
                        label = { Text(stringResource(R.string.daily_manual_meaning_label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { manualKind = "word" }, enabled = manualKind != "word") {
                            Text(stringResource(R.string.daily_manual_kind_word))
                        }
                        TextButton(onClick = { manualKind = "expression" }, enabled = manualKind != "expression") {
                            Text(stringResource(R.string.daily_manual_kind_expression))
                        }
                    }
                    Button(enabled = !busy && !savingManualWord && !outboxBlocked && manualText.isNotBlank() && manualMeaning.isNotBlank(), onClick = {
                        val text = manualText.trim()
                        val meaning = manualMeaning.trim()
                        val kind = manualKind
                        val markId = java.util.UUID.randomUUID().toString()
                        scope.launch {
                            savingManualWord = true
                            try {
                                val outcome = vm.saveDailyManualWord(owner ?: return@launch, pkg, draft.attemptId,
                                    markId, text, meaning, kind)
                                manualMessage = when {
                                    outcome.pendingCapacity > 0 -> "capacity"
                                    outcome.inserted > 0 -> "saved"
                                    outcome.existing > 0 -> "existing"
                                    outcome.suppressed > 0 -> "suppressed"
                                    else -> "error"
                                }
                                manualText = ""; manualMeaning = ""
                                onEvent(draft, buildJsonObject {
                                    put("type", "word_marked"); put("markId", markId); put("text", text)
                                    put("meaning", meaning); put("kind", kind); put("sourceQuestionId", kotlinx.serialization.json.JsonNull)
                                })
                            } catch (failure: Exception) {
                                manualMessage = "error"
                            } finally { savingManualWord = false }
                        }
                    }) { Text(stringResource(R.string.daily_manual_add_button)) }
                    when (manualMessage) {
                        "saved" -> Text(stringResource(R.string.daily_manual_saved), color = AppColors.Secondary)
                        "capacity" -> Text(stringResource(R.string.daily_manual_capacity), color = AppColors.Red)
                        "existing" -> Text(stringResource(R.string.daily_manual_existing), color = AppColors.Secondary)
                        "suppressed" -> Text(stringResource(R.string.daily_manual_suppressed), color = AppColors.Red)
                        "error" -> Text(stringResource(R.string.daily_manual_error), color = AppColors.Red)
                    }
                }
                review?.let { accepted ->
                    if (accepted["state"] != null) item { Text(stringResource(R.string.daily_review_waiting)) }
                    else {
                        accepted["strengths"]?.jsonArray?.forEach { item { Text("✓ ${it.jsonPrimitive.content}") } }
                        accepted["priorities"]?.jsonArray?.forEach { item { Text("• ${it.jsonPrimitive.content}") } }
                        accepted["errors"]?.jsonArray?.forEach { errorItem -> item {
                            val value = errorItem.jsonObject
                            Text("${value["quote"]?.jsonPrimitive?.content.orEmpty()} → ${value["correction"]?.jsonPrimitive?.content.orEmpty()}")
                            Text(value["explanation"]?.jsonPrimitive?.content.orEmpty(), color = AppColors.Secondary)
                        } }
                        item { Button(enabled = !busy && !outboxBlocked, onClick = { onImport(accepted) }) { Text(stringResource(R.string.daily_save_words)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyListeningPlayer(vm: AppViewModel, pkg: DailyPracticePackage, onUnavailableSkip: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = {
            if (vm.dailyListeningPlaying) vm.stopDailyListening() else vm.playDailyListening(pkg)
        }) {
            Text(stringResource(when {
                vm.dailyListeningBusy && !vm.dailyListeningPlaying -> R.string.daily_audio_loading
                vm.dailyListeningPlaying -> R.string.daily_audio_pause
                else -> R.string.daily_audio_play
            }))
        }
        if (vm.dailyListeningVoiceUnavailable) {
            Text(stringResource(R.string.daily_audio_error), color = AppColors.Red,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            TextButton(onClick = onUnavailableSkip) { Text(stringResource(R.string.daily_audio_skip_unavailable)) }
        }
    }
}

@Composable
private fun DailyQuestionCard(question: DailyQuestion, answer: String?, showHint: Boolean, language: String,
    onAnswer: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(question.prompt, style = MaterialTheme.typography.titleMedium)
        if (question.options.isNotEmpty()) question.options.forEach { option ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                RadioButton(selected = answer == option.id, onClick = { onAnswer(option.id) })
                Text(option.text, Modifier.padding(top = 12.dp))
            }
        } else {
            var text by remember(question.id, answer) { mutableStateOf(answer.orEmpty()) }
            TextField(value = text, onValueChange = { text = it; onAnswer(it.takeIf(String::isNotBlank)) },
                label = { Text(stringResource(R.string.daily_answer_label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        if (showHint) Text(if (language == "es") question.hints.es else question.hints.en, color = AppColors.Secondary)
    }
}
