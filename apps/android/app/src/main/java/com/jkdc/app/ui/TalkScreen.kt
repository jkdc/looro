package com.jkdc.app.ui

import android.os.Build

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.Dialog
import com.jkdc.app.AppViewModel
import com.jkdc.app.R
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.Speaker

@Composable
fun TalkScreen(
    vm: AppViewModel,
    microphoneMessage: String?,
    onMicrophone: () -> Unit,
    onOpenAppSettings: (() -> Unit)?,
    onSendTyped: (String) -> Unit,
    onLookup: (String, String) -> Unit,
    onHelp: () -> Unit,
) {
    val palette = LocalConversationPalette.current
    var typing by rememberSaveable { mutableStateOf(false) }
    var lookup by rememberSaveable { mutableStateOf(false) }
    var lookupWord by rememberSaveable { mutableStateOf("") }
    var lookupSentence by rememberSaveable { mutableStateOf("") }
    var transcript by remember { mutableStateOf<SessionRecord?>(null) }
    // Leaving the Talk tab (TalkScreen leaves composition; the ViewModel does not)
    // must not keep the tutor's voice playing or the microphone capturing elsewhere.
    DisposableEffect(Unit) {
        onDispose { vm.pauseVoiceSession() }
    }
    val assistantPassage = vm.session?.passages?.lastOrNull { it.speaker == Speaker.assistant }
    val userPassage = vm.session?.passages?.lastOrNull { it.speaker == Speaker.user }
    // While the tutor's reply is pending, the learner's own transcription takes
    // the big caption: confirmation the turn was heard beats a stale reply.
    val replyPending = userPassage != null &&
        (assistantPassage == null || userPassage.startMS > assistantPassage.startMS)
    val passage = if (replyPending) userPassage?.text else assistantPassage?.text
    val caption = passage?.takeIf { it.isNotBlank() } ?: vm.language.greeting
    val busy = vm.state == "connecting" || vm.state == "closing"
    val targetScroll = remember(assistantPassage?.id) { ScrollState(0) }
    val meaningScroll = remember(assistantPassage?.id, vm.archive.preferences.meaningLanguage) { ScrollState(0) }
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.background).testTag("talk-screen")) {
    val scrollPage = LocalDensity.current.fontScale > 1.3f || maxHeight < 480.dp
    val compact = !scrollPage && maxHeight < 620.dp
    val captionWidth = with(LocalDensity.current) { (maxWidth - 56.dp).roundToPx().coerceAtLeast(1) }
    val longPassage = passage != null && !scrollPage && textMeasurer.measure(
        caption, style = MaterialTheme.typography.headlineSmall,
        constraints = Constraints(maxWidth = captionWidth),
    ).lineCount > 2
    val readingSpace by animateFloatAsState(
        if (longPassage || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            vm.language.id == "zh" && com.jkdc.app.core.MandarinPinyin.containsHan(caption))) 1f else 0f, spring(dampingRatio = 1f, stiffness = 260f), label = "passage reading space",
    )
    val conversing = vm.session?.passages?.isNotEmpty() == true
    val orbSize = when {
        scrollPage -> 170.dp
        conversing -> minOf(104.dp, maxHeight * .14f)
        compact -> minOf(150.dp, maxHeight * .24f) - 26.dp * readingSpace
        else -> 220.dp - 64.dp * readingSpace
    }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .then(if (scrollPage) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 28.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = AppColors.Butter.copy(alpha = .58f), shape = CircleShape) {
            Text(
                vm.selectedTheme?.title ?: vm.language.talkTitle,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 8.dp),
                style = MaterialTheme.typography.labelMedium, color = palette.mutedText,
            )
        }
        Spacer(Modifier.height(if (compact) 8.dp else 24.dp - 16.dp * readingSpace))
        AppOrb(
            energy = maxOf(vm.outputLevel.toFloat(), vm.inputLevel.toFloat() * .45f),
            listening = vm.state == "active" && vm.isVoiceSession && !vm.isMuted,
            active = vm.state != "closing",
            modifier = Modifier.size(orbSize),
            palette = palette,
            contentDescription = stringResource(
                if (vm.state == "active" && vm.isVoiceSession && !vm.isMuted) R.string.talk_orb_listening_desc
                else R.string.talk_orb_idle_desc),
        )
        Box(Modifier.fillMaxWidth().padding(top = if (compact) 8.dp else 12.dp), contentAlignment = Alignment.Center) {
            val active = vm.state == "active"
            Text(statusText(vm.state, vm.isMuted, vm.isVoiceSession), style = MaterialTheme.typography.bodySmall,
                color = if (active) palette.sessionText else palette.mutedText,
                modifier = Modifier.testTag("conversation-status")
                    .background(if (active) palette.sessionSurface else palette.background, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp))
            if (assistantPassage != null && !replyPending && passage?.isNotBlank() == true) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    ReportUtteranceAction(onClick = {
                        vm.session?.id?.let { vm.reportUtterance(it, assistantPassage.id) }
                    }, modifier = Modifier.requiredSize(40.dp).testTag("report-current-utterance"))
                }
            }
        }
        Spacer(Modifier.height(if (compact) 12.dp else 20.dp - 8.dp * readingSpace))
        vm.partialUserTranscript?.let { partial ->
            Text(partial, style = MaterialTheme.typography.bodySmall, color = palette.mutedText,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("partial-transcript"))
            Spacer(Modifier.height(4.dp))
        }
        Column(
            // Each language keeps a share of the available space. A single scroller let
            // long target-language replies push their meaning entirely below the viewport.
            modifier = (if (scrollPage) Modifier else Modifier.weight(1f))
                .fillMaxWidth().testTag("conversation-captions"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        Column(
            modifier = (if (scrollPage) Modifier else Modifier.weight(1f, fill = false).passageScroll(targetScroll))
                .fillMaxWidth().testTag("target-passage-scroll"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (replyPending) {
                Text(stringResource(R.string.history_speaker_you), style = MaterialTheme.typography.labelSmall,
                    color = palette.mutedText, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(
                if (passage == null) AnnotatedString(caption)
                else captionLinks(caption, vm.language.id) { word ->
                    vm.clearLookup(); lookupWord = word; lookupSentence = caption
                    lookup = true; onLookup(word, caption)
                },
                style = if (passage == null) MaterialTheme.typography.displaySmall.let {
                    if (palette.serifHeadline) it.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif) else it
                }
                    else MaterialTheme.typography.headlineSmall,
                color = palette.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("target-caption"),
            )
            if (vm.language.id == "zh") PinyinHelp(caption)
            if (assistantPassage != null && !replyPending && passage?.isNotBlank() == true) {
                AppTextButton(onClick = { vm.replayReply(passage) },
                    enabled = !vm.replaying && !vm.working && !vm.lessonReading,
                    modifier = Modifier.padding(top = 2.dp).testTag("replay-reply")) {
                    if (vm.replaying) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.Ink)
                    else AppIcon(AppSymbol.Wave, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.talk_replay_button), style = MaterialTheme.typography.bodySmall, color = AppColors.Ink)
                }
            }
        }
        if (vm.archive.preferences.meaningVisible && !replyPending &&
            vm.archive.preferences.meaningLanguage != vm.language.name) {
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = (if (scrollPage) Modifier else Modifier.weight(.72f, fill = false).passageScroll(meaningScroll))
                    .fillMaxWidth().testTag("meaning-passage-scroll"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            Text(
                when {
                    passage == null -> com.jkdc.app.core.MeaningLanguages.greeting(vm.archive.preferences.meaningLanguage)
                    vm.meaning.isNotBlank() -> vm.meaning
                    vm.translating -> stringResource(R.string.talk_meaning_loading)
                    else -> ""
                },
                color = palette.mutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("meaning-caption"),
            )
            if (vm.meaningFailed) {
                Text(stringResource(R.string.talk_meaning_failed), color = palette.mutedText, style = MaterialTheme.typography.bodySmall)
                AppTextButton(onClick = vm::retryMeaning) { Text(stringResource(R.string.talk_retry_meaning_button)) }
            }
            }
        }
        if (!replyPending) vm.session?.passages?.lastOrNull { it.speaker == Speaker.user }?.let { user ->
            Row(Modifier.padding(top = 3.dp).testTag("user-caption"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_speaker_you), style = MaterialTheme.typography.labelSmall, color = palette.mutedText)
                Text(user.text.takeLast(160), style = MaterialTheme.typography.bodySmall, color = palette.mutedText,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (vm.session?.topics?.lastOrNull()?.sources?.isNotEmpty() == true) {
            AppTextButton(onClick = { transcript = vm.session }) { Text(stringResource(R.string.topics_sources_heading)) }
        }
        }
        if (vm.working) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.talk_checking), style = MaterialTheme.typography.bodySmall, color = palette.mutedText)
            }
        }
        Spacer(Modifier.height(when { scrollPage -> 28.dp; compact -> 10.dp; else -> 24.dp }))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            RoundAction(AppSymbol.Captions, stringResource(R.string.talk_meaning_label),
                selected = vm.archive.preferences.meaningVisible, onClick = vm::toggleMeaning)
            // Beginner mode: the check takes the centre slot the mic vacated.
            // While a turn is processing it holds a clearly distinct pending
            // state (sage background, filled icon, pulsing, replying label)
            // until the whole turn — STT → tutor → speech — completes, then
            // restores for the next turn. A press with nothing spoken yet stays
            // ARMED instead (no pending state): the turn starts when the
            // post-press phrase closes.
            if (vm.voiceManualTurn && vm.isRunning && vm.isVoiceSession) {
                val turnPending = vm.turnInFlightUi || vm.working || replyPending
                val pulse = rememberInfiniteTransition(label = "end-turn-pulse")
                val pendingAlpha by pulse.animateFloat(
                    initialValue = 1f, targetValue = 0.55f,
                    animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "end-turn-alpha",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ManualMuteControl(isMuted = vm.isMuted, enabled = vm.state == "active", onClick = vm::toggleMute)
                    RoundAction(AppSymbol.Check,
                        stringResource(if (turnPending) R.string.talk_end_turn_pending else R.string.talk_end_turn_button),
                        selected = turnPending, pending = turnPending, enabled = !vm.working, onClick = vm::endVoiceTurn,
                        modifier = Modifier.alpha(if (turnPending) pendingAlpha else 1f))
                }
            }
            RoundAction(if (vm.isRunning) AppSymbol.End else AppSymbol.Transcript,
                stringResource(if (vm.isRunning) R.string.talk_end_label else R.string.talk_transcript_label),
                enabled = vm.session != null, onClick = {
                    if (vm.isRunning) {
                        if (!vm.endDailySpeaking()) vm.end()
                    } else transcript = vm.session
                })
        }
        Text(stringResource(if (vm.state == "active" && vm.isVoiceSession && !vm.isMuted) R.string.talk_microphone_on else R.string.talk_microphone_off),
            style = MaterialTheme.typography.bodySmall, color = palette.mutedText, modifier = Modifier.padding(top = 6.dp))
        if (vm.state == "active" || vm.state == "paused" || microphoneMessage != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                AppTextButton(onClick = { typing = true }, enabled = !busy && !vm.working) {
                    AppIcon(AppSymbol.Keyboard, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.talk_type_button), style = MaterialTheme.typography.bodySmall, color = AppColors.Ink)
                }
                if (vm.state == "active") AppTextButton(onClick = onHelp, enabled = !vm.working) {
                    AppIcon(AppSymbol.Sparkles, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.talk_help_button), style = MaterialTheme.typography.bodySmall, color = AppColors.Ink)
                }
            }
        } else if (vm.session == null) {
            Text(stringResource(R.string.talk_reply_any_language), style = MaterialTheme.typography.bodySmall,
                color = palette.mutedText, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp, bottom = 8.dp))
        }

        microphoneMessage?.let {
            Text(it, color = palette.mutedText, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            if (onOpenAppSettings != null) AppTextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.talk_open_phone_settings)) }
        }
        vm.notice?.let { Text(it, color = palette.mutedText, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
        if (vm.state == "paused") {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)) {
                AppTextButton(onClick = vm::resumeVoiceSession, enabled = !busy, modifier = Modifier.testTag("talk-resume")) {
                    Text(stringResource(R.string.talk_resume_button), style = MaterialTheme.typography.bodySmall, color = AppColors.Ink)
                }
                AppTextButton(onClick = vm::restartVoiceSession, enabled = !busy, modifier = Modifier.testTag("talk-restart")) {
                    Text(stringResource(R.string.talk_restart_button), style = MaterialTheme.typography.bodySmall, color = AppColors.Ink)
                }
            }
        } else if (vm.session != null && !vm.isRunning) {
            AppTextButton(onClick = vm::resetConversation) { Text(stringResource(R.string.talk_new_conversation_button)) }
        }
        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
    }
    }
    // Talk control: floats CENTRED above the navigation bar with three readable
    // modes — idle (orange mic), listening (green wave), paused (neutral, struck
    // mic). In beginner voice sessions it stays hidden: the centre check is the
    // hero control there and the orb would cover it.
    val beginnerSession = vm.voiceManualTurn && vm.isRunning && vm.isVoiceSession
    val talkActive = vm.state == "active" && vm.isVoiceSession
    val listening = talkActive && !vm.isMuted
    val micDescription = stringResource(when {
        talkActive && vm.isMuted -> R.string.talk_mic_unmute_desc
        talkActive -> R.string.talk_mic_mute_desc
        else -> R.string.talk_mic_start_desc
    })
    val micEnabled = !busy && (vm.state != "active" || vm.isVoiceSession)
    if (!(vm.state == "active" && !vm.isVoiceSession) && !beginnerSession) {
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 124.dp).size(64.dp)
            .shadow(16.dp, CircleShape, ambientColor = palette.primary.copy(alpha = .12f), spotColor = palette.primary.copy(alpha = .2f))
            .background(when {
                listening -> palette.primary
                talkActive -> palette.background
                else -> palette.primary
            }, CircleShape).clip(CircleShape)
            .testTag("start-conversation").semantics { contentDescription = micDescription }
            .clickable(enabled = micEnabled, role = Role.Button) {
                when {
                    talkActive -> vm.toggleMute()
                    vm.state == "paused" -> vm.resumeVoiceSession()
                    else -> onMicrophone()
                }
            }, contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(25.dp), color = palette.onPrimary, strokeWidth = 2.dp)
            else AppIcon(when {
                listening -> AppSymbol.Wave
                talkActive -> AppSymbol.MicOff
                else -> AppSymbol.Mic
            }, modifier = Modifier.size(28.dp),
                color = if (talkActive && !listening) palette.mutedText else palette.onPrimary)
        }
    }
    }

    if (typing) TypedReplySheet(vm.language.name, vm.working, onSendTyped, onDismiss = { typing = false })
    androidx.compose.runtime.LaunchedEffect(vm.pendingLookupOpen) {
        if (vm.pendingLookupOpen && !lookup) { lookup = true; vm.pendingLookupOpen = false }
    }
    if (lookup) {
        var lookupSaved by remember(lookupWord) { mutableStateOf(false) }
        WordLookupSheet(lookupWord, lookupSentence, vm.language.id, vm.lookupResult, vm.lookupError, vm.lookupLoading,
            onDismiss = { vm.clearLookup(); lookup = false; lookupWord = "" },
            onSave = { if (vm.saveLookupExpression(lookupWord, vm.lookupResult.orEmpty())) lookupSaved = true },
            saved = lookupSaved)
    }
    transcript?.let { TranscriptDialog(vm, it, onDismiss = { transcript = null }) }
}

private fun Modifier.passageScroll(state: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (state.maxValue > 0 && size.height > 0) {
            val fade = minOf(10.dp.toPx() / size.height, .15f)
            drawRect(
                Brush.verticalGradient(
                    0f to if (state.value > 0) Color.Transparent else Color.Black,
                    fade to Color.Black,
                    (1f - fade) to Color.Black,
                    1f to if (state.value < state.maxValue) Color.Transparent else Color.Black,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
    .verticalScroll(state)

@Composable
private fun RoundAction(symbol: AppSymbol, label: String, selected: Boolean = false, enabled: Boolean = true,
                        pending: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SoftRoundButton(symbol, label, onClick, tint = when {
                pending -> AppColors.Sage
                selected -> AppColors.Butter.copy(alpha = .70f)
                else -> Color.White.copy(alpha = .72f)
            },
            enabled = enabled, filledIcon = selected)
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.Ink.copy(alpha = if (enabled) 1f else .45f))
    }
}

@Composable
private fun ManualMuteControl(isMuted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val actionLabel = stringResource(if (isMuted) R.string.talk_mic_unmute_desc else R.string.talk_mic_mute_desc)
    val stateLabel = stringResource(if (isMuted) R.string.talk_status_muted else R.string.talk_status_listening)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier.size(52.dp)
                .testTag("manual-mute-control")
                .semantics {
                    contentDescription = actionLabel
                    stateDescription = stateLabel
                }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .background(if (isMuted) AppColors.Cream else Color.White.copy(alpha = .78f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(if (isMuted) AppSymbol.MicOff else AppSymbol.Mic, Modifier.size(23.dp), color = AppColors.Ink)
        }
        Text(stringResource(if (isMuted) R.string.talk_manual_mic_unmute else R.string.talk_manual_mic_mute),
            style = MaterialTheme.typography.labelSmall, color = AppColors.Ink)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun TypedReplySheet(languageName: String, working: Boolean, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var requestedFocus by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.Cream,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 26.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.talk_typed_reply_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                SoftRoundButton(AppSymbol.Close, stringResource(R.string.common_close), onDismiss, diameter = 40.dp)
            }
            Text(stringResource(R.string.talk_typed_reply_subtitle, languageName), color = AppColors.Secondary,
                style = MaterialTheme.typography.bodyMedium)
            AppTextField(text, { text = it.take(2_000) }, modifier = Modifier.fillMaxWidth().testTag("typed-reply-input").focusRequester(focus).onGloballyPositioned {
                    if (!requestedFocus) { requestedFocus = true; focus.requestFocus() }
                },
                minLines = 3, maxLines = 6, label = { Text(stringResource(R.string.talk_typed_reply_field_label)) })
            Button(onClick = { onSend(text.trim()); onDismiss() }, enabled = text.isNotBlank() && !working,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("typed-reply-send"), shape = CircleShape) {
                Text(stringResource(R.string.talk_typed_reply_send_button)); Spacer(Modifier.width(8.dp))
                AppIcon(AppSymbol.ArrowUp, Modifier.size(18.dp))
            }
        }
    }
}


@Composable
private fun statusText(state: String, muted: Boolean, voice: Boolean) = when (state) {
    "connecting" -> stringResource(R.string.talk_status_connecting)
    "active" -> if (!voice) stringResource(R.string.talk_status_written) else if (muted) stringResource(R.string.talk_status_muted) else stringResource(R.string.talk_status_listening)
    "closing" -> stringResource(R.string.talk_status_closing)
    "paused" -> stringResource(R.string.talk_status_paused)
    "ended" -> stringResource(R.string.talk_status_ended)
    "failed" -> stringResource(R.string.talk_status_failed)
    else -> stringResource(R.string.talk_status_idle)
}
