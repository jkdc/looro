package com.jkdc.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.jkdc.app.AppViewModel
import com.jkdc.app.R
import com.jkdc.app.core.ExerciseEngine
import com.jkdc.app.core.PlacementEngine
import com.jkdc.app.core.PlacementItem
import com.jkdc.app.core.PlacementResult
import java.util.Locale

/** The free level test (placement-test): an adaptive core ladder, then fixed
    listening and writing blocks at the resulting band, then the CEFR can-do —
    always offered, complete or abandoned. The result is a suggestion, never a
    certificate and never a gate: the learner picks the level. Instructions
    follow the "instructions language" setting (subtitles by default). */
@Composable
fun PlacementScreen(vm: AppViewModel, onBack: () -> Unit, onApplyBand: (String) -> Unit) {
    val bank = vm.placementBank ?: run { onBack(); return }

    // Session state: the asked ids plus attempts fully determine the ladder
    // (the engine is deterministic), so the session survives rotation.
    var askedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var attempts by rememberSaveable { mutableStateOf(listOf<String>()) }
    var startBand by rememberSaveable { mutableStateOf<String?>(null) }
    var band by rememberSaveable { mutableStateOf<String?>(null) }
    var phase by rememberSaveable { mutableStateOf(PHASE_CORE) }
    var abandoned by rememberSaveable { mutableStateOf(false) }
    var blockIndex by rememberSaveable { mutableStateOf(0) }
    var listenVerdicts by rememberSaveable { mutableStateOf(listOf<Boolean>()) }
    var writeVerdicts by rememberSaveable { mutableStateOf(listOf<Boolean>()) }
    var canDoBand by rememberSaveable { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<PlacementResult?>(null) }
    var saved by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf<Boolean?>(null) }

    DisposableEffect(Unit) { onDispose { vm.stopLessonReading() } }

    val context = LocalContext.current
    val currentConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
    val localized = remember(vm.instructionsLanguage, currentConfiguration) {
        val locale = when (vm.instructionsLanguage) { "en" -> Locale("en"); "es" -> Locale("es"); else -> null }
        locale ?: return@remember null
        val configuration = android.content.res.Configuration(currentConfiguration)
        configuration.setLocale(locale)
        context.createConfigurationContext(configuration)
    }
    fun t(resId: Int): String = localized?.getString(resId) ?: context.getString(resId)
    fun t(resId: Int, argument: Any): String = localized?.getString(resId, argument) ?: context.getString(resId, argument)
    fun t(resId: Int, first: Any, second: Any): String =
        localized?.getString(resId, first, second) ?: context.getString(resId, first, second)

    LaunchedEffect(Unit) {
        if (startBand == null) { startBand = vm.placementStartBand(); band = startBand }
    }

    // Replay the ladder from the ORIGINAL start band: the asked sequence fully
    // determines the bands the session went through.
    val coreState = remember(askedIds, attempts, startBand) {
        var state = PlacementEngine.start(bank, startBand ?: "A1")
        askedIds.forEachIndexed { index, id ->
            val item = bank.items.firstOrNull { it.id == id } ?: return@forEachIndexed
            state = PlacementEngine.applyCore(state, item, attempts.getOrElse(index) { "" })
        }
        state
    }

    fun buildResult(): PlacementResult {
        val finalBand = band ?: "A1"
        val listeningBand = PlacementEngine.blockBand(finalBand, listenVerdicts)
        val writingBand = PlacementEngine.blockBand(finalBand, writeVerdicts)
        return PlacementResult(languageID = bank.languageID, version = bank.version, band = finalBand,
            categories = PlacementEngine.categories(coreState.asked, coreState.verdicts),
            listeningBand = listeningBand.takeIf { it != finalBand },
            writingBand = writingBand.takeIf { it != finalBand },
            confidence = PlacementEngine.confidence(coreState.answered),
            corePercent = PlacementEngine.corePercent(coreState.verdicts),
            source = if (abandoned) PlacementResult.SOURCE_CANDO else PlacementResult.SOURCE_TEST,
            at = System.currentTimeMillis())
    }

    val currentItem = if (phase == PHASE_CORE) PlacementEngine.nextCoreItem(coreState) else null
    LaunchedEffect(phase, coreState.finished, currentItem?.id) {
        if (phase == PHASE_CORE && (coreState.finished || currentItem == null)) {
            band = coreState.band
            phase = PHASE_LISTENING; blockIndex = 0; listenVerdicts = emptyList()
        }
    }
    val listenItems = remember(bank, band) { PlacementEngine.blockItems(bank, band ?: "A1", PlacementItem.MODE_LISTENING) }
    val writeItems = remember(bank, band) { PlacementEngine.blockItems(bank, band ?: "A1", PlacementItem.MODE_WRITING) }
    val listenItem = if (phase == PHASE_LISTENING) listenItems.getOrNull(blockIndex) else null
    val writeItem = if (phase == PHASE_WRITING) writeItems.getOrNull(blockIndex) else null
    LaunchedEffect(listenItem?.id) { listenItem?.speech?.let { vm.speakPlacement(it) } }
    LaunchedEffect(phase, listenItem?.id) {
        if (phase == PHASE_LISTENING && listenItem == null) { phase = PHASE_WRITING; blockIndex = 0; writeVerdicts = emptyList() }
    }
    LaunchedEffect(phase, writeItem?.id) {
        if (phase == PHASE_WRITING && writeItem == null) { result = buildResult(); phase = PHASE_RESULT }
    }
    LaunchedEffect(result) {
        val final = result
        if (final != null && !saved) { saved = true; vm.savePlacementResult(final) }
    }

    fun handleBack() {
        if (phase == PHASE_CORE || phase == PHASE_LISTENING || phase == PHASE_WRITING) {
            if (phase == PHASE_CORE && askedIds.isEmpty()) { onBack(); return }
            abandoned = true; result = null; phase = PHASE_CANDO
        } else onBack()
    }

    Column(Modifier.fillMaxWidth().testTag("placement-screen").padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftRoundButton(AppSymbol.Back, t(R.string.common_back), onClick = ::handleBack, diameter = 44.dp)
            Spacer(Modifier.weight(1f))
            Text(t(R.string.placement_title), style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t(R.string.placement_title), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    Text(t(R.string.placement_subtitle), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                }
            }
            item {
                // Instructions language: generic setting, placement is its v1 consumer.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(R.string.placement_instructions), style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                    listOf(com.jkdc.app.core.InstructionsLanguageStore.SUBTITLES to t(R.string.placement_instructions_subtitles),
                        "en" to "English", "es" to "Español").forEach { (value, label) ->
                        val chosen = vm.instructionsLanguage == value
                        Surface(Modifier.clickable(role = Role.Button) { vm.chooseInstructionsLanguage(value) }
                                .testTag("placement-instructions-$value"),
                            shape = RoundedCornerShape(14.dp),
                            color = if (chosen) AppColors.Secondary else AppColors.Cream) {
                            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (chosen) AppColors.Cream else AppColors.Secondary)
                        }
                    }
                }
            }
            when (phase) {
                PHASE_CORE -> if (currentItem != null) {
                    val item = currentItem
                    item(key = item.id) {
                        Text(t(R.string.placement_phase_core, coreState.answered + 1),
                            style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                    }
                    item(key = item.id + "-card") {
                        QuestionCard(item = item, attempt = attempt, checked = checked, localized = localized,
                            onAttempt = { attempt = it },
                            onCheck = {
                                askedIds = askedIds + item.id
                                attempts = attempts + attempt
                                checked = ExerciseEngine.isCorrect(item.exercise, attempt)
                            },
                            onContinue = { attempt = ""; checked = null })
                    }
                }
                PHASE_LISTENING -> if (listenItem != null) {
                    val item = listenItem
                    item(key = item.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(t(R.string.placement_phase_listening, blockIndex + 1, listenItems.size),
                                style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                            OutlinedButton(onClick = { vm.speakPlacement(item.speech ?: "") },
                                modifier = Modifier.testTag("placement-replay")) {
                                Text(t(R.string.placement_listen_replay))
                            }
                        }
                    }
                    item(key = item.id + "-card") {
                        QuestionCard(item = item, attempt = attempt, checked = checked, localized = localized,
                            onAttempt = { attempt = it },
                            onCheck = {
                                checked = ExerciseEngine.isCorrect(item.exercise, attempt)
                                listenVerdicts = listenVerdicts + checked!!
                            },
                            onContinue = {
                                attempt = ""; checked = null
                                if (listenVerdicts.size >= PlacementEngine.BLOCK_SIZE) {
                                    phase = PHASE_WRITING; blockIndex = 0; writeVerdicts = emptyList()
                                } else blockIndex += 1
                            })
                    }
                }
                PHASE_WRITING -> if (writeItem != null) {
                    val item = writeItem
                    item(key = item.id) {
                        Text(t(R.string.placement_phase_writing, blockIndex + 1, writeItems.size),
                            style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                    }
                    item(key = item.id + "-card") {
                        QuestionCard(item = item, attempt = attempt, checked = checked, localized = localized,
                            onAttempt = { attempt = it },
                            onCheck = {
                                checked = ExerciseEngine.isCorrect(item.exercise, attempt)
                                writeVerdicts = writeVerdicts + checked!!
                            },
                            onContinue = {
                                attempt = ""; checked = null
                                if (writeVerdicts.size >= PlacementEngine.BLOCK_SIZE) {
                                    result = buildResult(); phase = PHASE_RESULT
                                } else blockIndex += 1
                            })
                    }
                }
                PHASE_CANDO -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(t(R.string.placement_cando_title), style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() })
                            Text(t(R.string.placement_cando_hint), style = MaterialTheme.typography.bodySmall,
                                color = AppColors.Secondary)
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlacementEngine.BANDS.forEachIndexed { index, level ->
                                val chosen = canDoBand == level
                                Surface(Modifier.fillMaxWidth().testTag("placement-cando-$level")
                                        .clickable(role = Role.Button) { canDoBand = level },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (chosen) AppColors.Sage else AppColors.Surface.copy(alpha = .85f)) {
                                    Text(t(candoStatement(index)), Modifier.padding(14.dp),
                                        style = MaterialTheme.typography.bodyMedium, color = AppColors.Ink)
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                    if (abandoned && canDoBand != null) {
                                        result = PlacementEngine.canDoResult(bank, canDoBand!!)
                                            .copy(at = System.currentTimeMillis())
                                        phase = PHASE_RESULT
                                    } else onBack()
                                }, enabled = canDoBand != null,
                                modifier = Modifier.testTag("placement-cando-continue"),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                                Text(t(R.string.placement_continue))
                            }
                            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("placement-cando-skip")) {
                                Text(t(R.string.placement_cando_skip))
                            }
                        }
                    }
                }
                PHASE_RESULT -> result?.let { final ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("placement-result")) {
                            Text(t(R.string.placement_result_title, final.band), style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() })
                            if (final.source == PlacementResult.SOURCE_CANDO)
                                Text(t(R.string.placement_result_cando_only), style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.Secondary)
                            if (canDoBand != null && canDoBand != final.band && final.source == PlacementResult.SOURCE_TEST)
                                Text(t(R.string.placement_result_cando, canDoBand!!), style = MaterialTheme.typography.bodyMedium)
                            final.categories.forEach { (category, score) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(categoryLabelFor(category)), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(R.string.progress_skills_percent, score),
                                        style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary)
                                }
                            }
                            final.listeningBand?.let {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(t(R.string.placement_result_listening), style = MaterialTheme.typography.bodyMedium)
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary)
                                }
                            }
                            final.writingBand?.let {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(t(R.string.placement_result_writing), style = MaterialTheme.typography.bodyMedium)
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary)
                                }
                            }
                            Text(t(when (final.confidence) {
                                PlacementResult.CONFIDENCE_HIGH -> R.string.placement_confidence_high
                                PlacementResult.CONFIDENCE_MEDIUM -> R.string.placement_confidence_medium
                                else -> R.string.placement_confidence_low
                            }), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                            Text(t(R.string.placement_result_estimate), style = MaterialTheme.typography.bodySmall,
                                color = AppColors.Secondary)
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onApplyBand(final.band) },
                                modifier = Modifier.fillMaxWidth().testTag("placement-apply"),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                                Text(t(R.string.placement_result_start, final.band))
                            }
                            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("placement-other")) {
                                Text(t(R.string.placement_result_other))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One question with immediate feedback: choice renders as tappable options,
    written/gap as a text field. Grading is always `ExerciseEngine`. */
@Composable
private fun QuestionCard(item: PlacementItem, attempt: String, checked: Boolean?,
                         localized: android.content.Context?,
                         onAttempt: (String) -> Unit, onCheck: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    fun text(resId: Int): String = localized?.getString(resId) ?: context.getString(resId)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().testTag("placement-question")) {
        Text(item.exercise.prompt, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        if (item.exercise.kind == ExerciseEngine.CHOICE) {
            item.exercise.options.forEachIndexed { index, option ->
                val isSelected = option == attempt
                Surface(Modifier.fillMaxWidth().clickable(role = Role.Button, enabled = checked == null) { onAttempt(option) }
                        .testTag("placement-option-$index"),
                    shape = RoundedCornerShape(14.dp),
                    color = when {
                        checked != null && option in item.exercise.answers -> AppColors.Sage
                        isSelected -> AppColors.Secondary
                        else -> AppColors.Cream
                    }) {
                    Text(option, Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected && checked == null) AppColors.Cream else AppColors.Ink)
                }
            }
        } else {
            BasicTextField(value = attempt,
                onValueChange = { if (checked == null) onAttempt(it) },
                enabled = checked == null, minLines = 1, maxLines = 2,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.Ink),
                cursorBrush = SolidColor(AppColors.Secondary),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("placement-attempt"))
        }
        when (checked) {
            true -> Text(text(R.string.placement_correct), style = MaterialTheme.typography.titleSmall,
                color = AppColors.Secondary)
            false -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text(R.string.placement_incorrect), style = MaterialTheme.typography.titleSmall)
                Text(item.exercise.explain, style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
            }
            null -> {}
        }
        if (checked == null) {
            Button(onClick = onCheck, enabled = attempt.isNotBlank(), modifier = Modifier.testTag("placement-check"),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                Text(text(R.string.placement_check))
            }
        } else {
            Button(onClick = onContinue, modifier = Modifier.testTag("placement-continue"),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                Text(text(R.string.placement_continue))
            }
        }
    }
}

private fun candoStatement(index: Int): Int = when (index) {
    0 -> R.string.placement_cando_a1
    1 -> R.string.placement_cando_a2
    2 -> R.string.placement_cando_b1
    3 -> R.string.placement_cando_b2
    else -> R.string.placement_cando_c1
}

private fun categoryLabelFor(category: String): Int = when (category) {
    "grammar" -> R.string.skill_category_grammar
    "vocab" -> R.string.skill_category_vocab
    "function" -> R.string.skill_category_function
    "speaking" -> R.string.skill_category_speaking
    "listening" -> R.string.skill_category_listening
    "reading" -> R.string.skill_category_reading
    "writing" -> R.string.skill_category_writing
    else -> R.string.skill_category_pron
}

private const val PHASE_CORE = "core"
private const val PHASE_LISTENING = "listening"
private const val PHASE_WRITING = "writing"
private const val PHASE_CANDO = "cando"
private const val PHASE_RESULT = "result"
