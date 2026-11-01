package com.jkdc.app.ui

import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.jkdc.app.AppViewModel
import com.jkdc.app.R
import com.jkdc.app.data.SearchHit
import com.jkdc.app.core.LanguageRegistry
import com.jkdc.app.core.ConversationProvider
import com.jkdc.app.core.MeaningLanguages
import com.jkdc.app.core.Passage
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.Speaker
import com.jkdc.app.core.UsageSummary
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch

@Composable
fun SettingsScreen(vm: AppViewModel, onImport: () -> Unit, onReviewConsent: () -> Unit,
                   onAccount: (() -> Unit)? = null, onResume: (String) -> Unit = {}, onDismiss: () -> Unit = {},
                   onTokensPurchase: (() -> Unit)? = null, tokensBalanceHint: String? = null,
                   onExportDb: () -> Unit = {}, onImportDb: () -> Unit = {}) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var keyDialog by rememberSaveable { mutableStateOf(false) }
    var deleteKey by rememberSaveable { mutableStateOf(false) }
    var deleteAll by rememberSaveable { mutableStateOf(false) }
    var deleteLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var languageDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var deleteLanguageTarget by rememberSaveable { mutableStateOf(vm.language.id) }
    var permissionDetails by rememberSaveable { mutableStateOf(false) }
    var revokeConsent by rememberSaveable { mutableStateOf(false) }
    var notices by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var progress by rememberSaveable { mutableStateOf(false) }
    var transcript by remember { mutableStateOf<SessionRecord?>(null) }
    var deleteSession by remember { mutableStateOf<SessionRecord?>(null) }
    val prefs = vm.archive.preferences
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    fun open(url: String) { runCatching { uriHandler.openUri(url) } }

    Column(Modifier.fillMaxSize()) {
        SettingsSheetHeader(stringResource(R.string.settings_navigation_title), onDismiss)
        LazyColumn(Modifier.weight(1f).testTag("settings-screen"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item {
                SettingsGroup(stringResource(R.string.settings_just_your_pace),
                    stringResource(if (vm.isRunning) R.string.settings_language_running_footer else R.string.settings_language_footer)) {
                    SettingsChoiceRow(stringResource(R.string.settings_learning_language), vm.language.settingsTitle, vm.language.id,
                        LanguageRegistry.all.map { it.id to it.settingsTitle }, "settings-learning-language", !vm.isRunning, vm::selectLanguage)
                    SettingsDivider()
                    SettingsMeaningSwitch(prefs.meaningVisible, vm::toggleMeaning)
                    SettingsDivider()
                    SettingsChoiceRow(stringResource(R.string.settings_meaning_language), prefs.meaningLanguage, prefs.meaningLanguage,
                        MeaningLanguages.all.map { it to it }, "settings-meaning-language", !vm.isRunning) {
                        vm.updatePreferences(prefs.copy(meaningLanguage = it))
                    }
                    SettingsDivider()
                    // Instructions language (placement-test): what language explains
                    // helpers like the level test. Subtitles = today's behaviour.
                    SettingsChoiceRow(stringResource(R.string.settings_instructions), vm.instructionsLanguage, vm.instructionsLanguage,
                        listOf(com.jkdc.app.core.InstructionsLanguageStore.SUBTITLES to stringResource(R.string.placement_instructions_subtitles),
                            "en" to "English", "es" to "Español"), "settings-instructions-language", !vm.isRunning) {
                        vm.chooseInstructionsLanguage(it)
                    }
                    SettingsDivider()
                    val rate = prefs.speechRate ?: 1.0
                    SettingsChoiceRow(stringResource(R.string.settings_speech_rate),
                        stringResource(when (rate) {
                            0.6 -> R.string.speech_rate_very_slow
                            0.8 -> R.string.speech_rate_slow
                            1.2 -> R.string.speech_rate_fast
                            else -> R.string.speech_rate_normal
                        }), com.jkdc.app.core.SpeechRates.idOf(prefs.speechRate),
                        listOf("0.6" to stringResource(R.string.speech_rate_very_slow),
                            "0.8" to stringResource(R.string.speech_rate_slow),
                            "1.0" to stringResource(R.string.speech_rate_normal),
                            "1.2" to stringResource(R.string.speech_rate_fast)),
                        "settings-speech-rate", !vm.isRunning) {
                        vm.updatePreferences(prefs.copy(speechRate = it.toDoubleOrNull()))
                    }
                    SettingsDivider()
                    Column(Modifier.fillMaxWidth()) {
                        SettingsSwitchRow(stringResource(R.string.settings_listening_practice), "settings-listening-practice",
                            prefs.listeningPractice == true, !vm.isRunning) {
                            vm.updatePreferences(prefs.copy(listeningPractice = if (it) true else null))
                        }
                        Text(stringResource(R.string.settings_listening_practice_hint),
                            style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    }
                    SettingsDivider()
                    Column(Modifier.fillMaxWidth()) {
                        SettingsSwitchRow(stringResource(R.string.settings_pronunciation_practice), "settings-pronunciation-practice",
                            prefs.pronunciationEnabled == true, !vm.isRunning) {
                            vm.updatePreferences(prefs.copy(pronunciationEnabled = if (it) true else null))
                        }
                        Text(stringResource(R.string.settings_pronunciation_hint),
                            style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    }
                    SettingsDivider()
                    Column(Modifier.fillMaxWidth()) {
                        SettingsSwitchRow(stringResource(R.string.usage_metrics_title), "settings-usage-metrics",
                            prefs.usageMetricsEnabled != false, true) {
                            vm.updatePreferences(prefs.copy(usageMetricsEnabled = if (it) null else false))
                        }
                        Text(stringResource(R.string.usage_metrics_hint),
                            style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    }
                    SettingsDivider()
                    // On-device voice controls: inert while the engine actually
                    // executed is the OpenAI realtime path (build flag or the
                    // persisted debug pick) — turn-taking is server-side there.
                    if (vm.effectiveEngineId != "openai") {
                        val sttValue = prefs.sttEngine ?: "local"
                        SettingsChoiceRow(stringResource(R.string.settings_stt_title),
                            stringResource(when (sttValue) {
                                "openai" -> R.string.settings_stt_openai
                                "groq" -> R.string.settings_stt_groq
                                else -> R.string.settings_stt_device
                            }),
                            sttValue,
                            listOf("local" to stringResource(R.string.settings_stt_device),
                                "openai" to stringResource(R.string.settings_stt_openai),
                                "groq" to stringResource(R.string.settings_stt_groq)),
                            "settings-stt-engine", !vm.isRunning) { id ->
                            vm.updatePreferences(prefs.copy(sttEngine = if (id == "local") null else id))
                        }
                        Text(stringResource(R.string.settings_stt_hint),
                            style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                        SettingsDivider()
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_voice_manual_turn),
                                style = MaterialTheme.typography.bodyLarge, color = AppColors.Ink,
                                modifier = Modifier.weight(1f).padding(end = 12.dp))
                            Switch(checked = vm.voiceManualTurn, onCheckedChange = { vm.updateVoiceManualTurn(it) })
                        }
                        SettingsDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.settings_silence_label),
                                    style = MaterialTheme.typography.bodyLarge, color = AppColors.Ink)
                                Text(String.format(java.util.Locale.US, "%.1f s", vm.silenceEndMs / 1000f),
                                    style = MaterialTheme.typography.bodyLarge, color = AppColors.Secondary)
                            }
                            Slider(value = vm.silenceEndMs.toFloat(),
                                onValueChange = { vm.updateSilenceEndMs(((it / 100).toLong() * 100)) },
                                valueRange = 600f..3000f)
                            Text(stringResource(R.string.settings_silence_hint),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                        }
                        SettingsDivider()
                    }
                    // Runtime provider selection + per-provider keys. Always visible.
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.settings_conversation_provider),
                                style = MaterialTheme.typography.bodyLarge, color = AppColors.Ink)
                            Text(stringResource(R.string.settings_conversation_provider_hint),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                            // Conversation provider: Personal key vs Hosted minutes.
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { vm.selectConversationProvider(ConversationProvider.PERSONAL_KEY) },
                                    enabled = !vm.isRunning,
                                    modifier = Modifier.weight(1f).testTag("provider-personal-key"),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (vm.conversationProvider == ConversationProvider.PERSONAL_KEY) AppColors.Secondary else AppColors.Cream,
                                        contentColor = if (vm.conversationProvider == ConversationProvider.PERSONAL_KEY) AppColors.Cream else AppColors.Ink
                                    )
                                ) { Text("Your own key", style = MaterialTheme.typography.labelSmall) }
                                OutlinedButton(
                                    onClick = { vm.selectConversationProvider(ConversationProvider.HOSTED_MINUTES) },
                                    enabled = !vm.isRunning,
                                    modifier = Modifier.weight(1f).testTag("provider-hosted-minutes"),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (vm.conversationProvider == ConversationProvider.HOSTED_MINUTES) AppColors.Secondary else AppColors.Cream,
                                        contentColor = if (vm.conversationProvider == ConversationProvider.HOSTED_MINUTES) AppColors.Cream else AppColors.Ink
                                    )
                                ) { Text("Hosted minutes", style = MaterialTheme.typography.labelSmall) }
                            }
                            SettingsDivider()
                            vm.localEngines.forEach { engine ->
                                Row(Modifier.fillMaxWidth().clickable(enabled = !vm.isRunning) { vm.selectLocalEngine(engine.id) }
                                        .padding(vertical = 4.dp).testTag("engine-" + engine.id),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = engine.selected, onClick = { vm.selectLocalEngine(engine.id) },
                                        enabled = !vm.isRunning)
                                    Column {
                                        Text(engine.label, style = MaterialTheme.typography.bodyMedium, color = AppColors.Ink)
                                        Text(stringResource(when {
                                            engine.hasCustomKey -> R.string.engine_key_custom
                                            engine.hasKey -> R.string.engine_key_included
                                            else -> R.string.engine_key_missing
                                        }), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                                    }
                                }
                            }
                            var engineKey by remember { mutableStateOf("") }
                            val selected = vm.localEngines.firstOrNull { it.selected }
                            if (selected != null) {
                                BasicTextField(value = engineKey,
                                    onValueChange = { engineKey = it.take(512) },
                                    enabled = !vm.isRunning,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.Ink),
                                    cursorBrush = SolidColor(AppColors.Secondary), minLines = 1, maxLines = 1,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("engine-key"),
                                    decorationBox = { field ->
                                        if (engineKey.isEmpty()) Text(stringResource(R.string.engine_key_hint),
                                            style = MaterialTheme.typography.bodyLarge, color = AppColors.Secondary.copy(alpha = .7f))
                                        field
                                    })
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(enabled = !vm.isRunning && engineKey.isNotBlank(),
                                        onClick = { if (vm.saveEngineKey(selected.id, engineKey)) engineKey = "" }) {
                                        Text(stringResource(R.string.engine_key_save), color = AppColors.Ink)
                                    }
                                    if (selected.hasCustomKey) {
                                        TextButton(enabled = !vm.isRunning,
                                            onClick = { vm.removeEngineKey(selected.id) }) {
                                            Text(stringResource(R.string.engine_key_remove), color = AppColors.Secondary)
                                        }
                                    }
                                }
                            }
                            // OpenCode model picker: only the opencode engine
                            // exposes a subscription catalog. A changed model
                            // applies from the next conversation on (same rule
                            // as engine switching).
                            if (selected?.id == "opencode") {
                                LaunchedEffect(Unit) { vm.ensureEngineModelsLoaded() }
                                SettingsDivider()
                                Text(stringResource(R.string.settings_engine_model_label),
                                    style = MaterialTheme.typography.bodyLarge, color = AppColors.Ink)
                                Text(stringResource(R.string.settings_engine_model_hint),
                                    style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                                if (vm.engineModelsLoading) {
                                    Text(stringResource(R.string.engine_models_loading),
                                        style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                                }
                                if (vm.engineModelsFailed && !vm.engineModelsLoading) {
                                    Text(stringResource(R.string.engine_models_error),
                                        style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                                }
                                vm.engineModelOptions.forEach { model ->
                                    val modelLabel = if (model.isFree) {
                                        model.name + " " + stringResource(R.string.engine_model_free)
                                    } else model.name
                                    Row(Modifier.fillMaxWidth()
                                            .clickable(enabled = !vm.isRunning) { vm.selectEngineModel(model.id) }
                                            .padding(vertical = 4.dp).testTag("engine-model-" + model.id),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = vm.selectedEngineModel == model.id,
                                            onClick = { vm.selectEngineModel(model.id) }, enabled = !vm.isRunning)
                                        Text(modelLabel, style = MaterialTheme.typography.bodyMedium, color = AppColors.Ink)
                                    }
                                }
                                TextButton(enabled = !vm.isRunning && !vm.engineModelsLoading,
                                    onClick = { vm.ensureEngineModelsLoaded(force = true) },
                                    modifier = Modifier.testTag("engine-models-refresh")) {
                                    Text(stringResource(R.string.engine_models_refresh), color = AppColors.Ink)
                                }
                            }
                            // Commercial surface for the hosted-proxy engine ("Palique (tokens)").
                            // Engine SELECTION is the factory row above; this row is only the
                            // paywall/pack entry, with the server's balance display hint.
                            if (onTokensPurchase != null) {
                                SettingsDivider()
                                Row(Modifier.fillMaxWidth().clickable(enabled = !vm.isRunning) { onTokensPurchase() }
                                        .padding(horizontal = 16.dp, vertical = 12.dp).testTag("settings-tokens-row"),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.tokens_row_title), style = MaterialTheme.typography.bodyLarge, color = AppColors.Ink)
                                        Text(stringResource(R.string.tokens_row_hint), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                                    }
                                    tokensBalanceHint?.let { hint ->
                                        Surface(color = AppColors.Peach.copy(alpha = .7f), shape = RoundedCornerShape(50)) {
                                            Text(hint, Modifier.padding(horizontal = 10.dp, vertical = 5.dp).testTag("settings-tokens-balance"),
                                                style = MaterialTheme.typography.labelSmall, color = AppColors.Ink)
                                        }
                                    }
                                    AppIcon(AppSymbol.ChevronRight, Modifier.size(13.dp), color = AppColors.Secondary.copy(alpha = .6f))
                                }
                            }
                        }
                        SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_corrections_label), stringResource(R.string.settings_corrections_value))
                    SettingsDivider()
                    BasicTextField(value = prefs.interests, onValueChange = { vm.updatePreferences(prefs.copy(interests = it.take(500))) },
                        enabled = !vm.isRunning, textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.Ink),
                        cursorBrush = SolidColor(AppColors.Secondary), minLines = 1, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 14.dp)
                            .testTag("settings-interests"), decorationBox = { field ->
                            if (prefs.interests.isEmpty()) Text(stringResource(R.string.settings_interests_label),
                                style = MaterialTheme.typography.bodyLarge, color = AppColors.Secondary.copy(alpha = .7f))
                            field()
                        })
                }
            }
            if (onAccount != null) item {
                SettingsGroup {
                    SettingsRow(stringResource(R.string.account_title), enabled = !vm.isRunning, symbol = SettingsSymbol.ACCOUNT,
                        chevron = true, modifier = Modifier.testTag("managed-account-settings"), onClick = onAccount)
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_advanced),
                    if (!vm.hasKey) stringResource(R.string.settings_byok_version_footer) else null) {
                    SettingsRow(stringResource(R.string.settings_use_own_key), symbol = SettingsSymbol.KEY,
                        chevron = !advanced, modifier = Modifier.testTag("advanced-api-key"), onClick = { advanced = !advanced })
                    AnimatedVisibility(advanced, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column {
                            SettingsDivider()
                            if (vm.hasKey) Text(stringResource(R.string.settings_key_saved_notice), style = MaterialTheme.typography.bodySmall,
                                color = AppColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                            SettingsRow(stringResource(if (vm.hasKey) R.string.settings_replace_key else R.string.settings_save_key),
                                enabled = !vm.isRunning, tint = AppColors.Secondary, chevron = true, onClick = { keyDialog = true })
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_open_api_keys), tint = AppColors.Secondary,
                                onClick = { open("https://platform.openai.com/api-keys") })
                            if (vm.hasKey) {
                                SettingsDivider()
                                SettingsRow(stringResource(R.string.settings_remove_key), enabled = !vm.isRunning,
                                    tint = AppColors.Red, onClick = { deleteKey = true })
                            }
                            Text(stringResource(R.string.settings_key_owner_footer), style = MaterialTheme.typography.bodySmall,
                                color = AppColors.Secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                        }
                    }
                }
            }
            item {
                val usage = UsageSummary.of(vm.archive.sessions)
                SettingsGroup(stringResource(R.string.settings_keep_comfortable), stringResource(R.string.settings_usage_footer)) {
                    val limits = (listOf(5, 10, 15, 20, 30, 60) + prefs.sessionMinutes).distinct().sorted()
                    SettingsChoiceRow(stringResource(R.string.settings_conversation_limit), stringResource(R.string.settings_limit_minutes, prefs.sessionMinutes),
                        prefs.sessionMinutes.toString(), limits.map { it.toString() to stringResource(R.string.settings_limit_minutes, it) },
                        "settings-conversation-limit", !vm.isRunning) { vm.updatePreferences(prefs.copy(sessionMinutes = it.toInt())) }
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_voice_time_label), usage.voiceTime)
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_voice_estimate_label), usage.voiceEstimate)
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_search_calls_label), usage.searchCalls.toString())
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_usage_billing_link), tint = AppColors.Secondary,
                        onClick = { open("https://platform.openai.com/usage") })
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_section_your_data), stringResource(R.string.settings_backup_footer)) {
                    // The legacy JSON import stays (restore path); the JSON
                    // export row is retired — the .db backup below is the way
                    // to take data out.
                    SettingsRow(stringResource(R.string.settings_import_backup), enabled = !vm.isRunning, symbol = SettingsSymbol.IMPORT,
                        tint = AppColors.Secondary, onClick = onImport)
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.progress_section_title), chevron = true,
                        modifier = Modifier.testTag("settings-progress"), onClick = { progress = true })
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.history_section_title), symbol = SettingsSymbol.HISTORY, chevron = true,
                        modifier = Modifier.testTag("settings-history"), onClick = { history = true })
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_delete_all_data), enabled = !vm.isRunning,
                        tint = AppColors.Red, onClick = { deleteAll = true })
                }
            }
            item {
                // The learning.db backup (whole SQLite file, verified on both
                // ends) and the per-language delete. The JSON import above is
                // the legacy restore path only; export is the .db file.
                SettingsGroup(stringResource(R.string.settings_db_section_title), stringResource(R.string.settings_db_section_footer)) {
                    SettingsRow(stringResource(R.string.settings_export_db), enabled = !vm.isRunning, symbol = SettingsSymbol.EXPORT,
                        tint = AppColors.Secondary, modifier = Modifier.testTag("settings-export-db"), onClick = onExportDb)
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_import_db), enabled = !vm.isRunning, symbol = SettingsSymbol.IMPORT,
                        tint = AppColors.Secondary, modifier = Modifier.testTag("settings-import-db"), onClick = onImportDb)
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_delete_language_data), enabled = !vm.isRunning,
                        tint = AppColors.Red, modifier = Modifier.testTag("settings-delete-language"),
                        onClick = { deleteLanguageDialog = true })
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_section_help_privacy)) {
                    SettingsRow(stringResource(R.string.common_privacy_policy), tint = AppColors.Secondary,
                        onClick = { open("https://app.example/privacy/") })
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.common_terms_of_use), tint = AppColors.Secondary,
                        onClick = { open("https://app.example/terms/") })
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.common_contact_support), tint = AppColors.Secondary,
                        onClick = { open("https://app.example/support/") })
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_section_ai_permission), chevron = true,
                        modifier = Modifier.testTag("settings-ai-permission"), onClick = { permissionDetails = true })
                }
            }
            item {
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_app_version_footer, version), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                        Text(stringResource(R.string.settings_models_footer), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                    }
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_openai_data_controls), tint = AppColors.Secondary,
                        onClick = { open("https://developers.openai.com/api/docs/guides/your-data") })
                    Text(stringResource(R.string.settings_data_use_footer), style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_open_source_notices), chevron = true, onClick = { notices = true })
                }
            }
        }
    }

    if (keyDialog) KeyDialog(vm, onDismiss = { keyDialog = false })
    if (notices) NoticesDialog(onDismiss = { notices = false })
    if (history) SettingsHistorySheet(vm, onDismiss = { history = false }, onSelect = { transcript = it }, onDelete = { deleteSession = it },
                        onResume = { id -> history = false; onResume(id) })
    if (progress) SettingsProgressSheet(vm, onDismiss = { progress = false })
    if (permissionDetails) AlertDialog(onDismissRequest = { permissionDetails = false },
        title = { Text(stringResource(R.string.settings_section_ai_permission)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(if (prefs.aiConsentVersion == AI_CONSENT_VERSION) R.string.settings_ai_consent_accepted else R.string.settings_ai_consent_not_accepted),
                style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_ai_permission_summary), color = AppColors.Secondary)
        } },
        confirmButton = {
            if (prefs.aiConsentVersion == AI_CONSENT_VERSION) AppTextButton(onClick = { permissionDetails = false; revokeConsent = true },
                enabled = !vm.isRunning, modifier = Modifier.testTag("revoke-ai-consent")) {
                Text(stringResource(R.string.settings_revoke_consent_button), color = AppColors.Red)
            } else AppTextButton(onClick = { permissionDetails = false; onReviewConsent() },
                enabled = !vm.isRunning, modifier = Modifier.testTag("review-ai-consent")) { Text(stringResource(R.string.settings_review_consent_button)) }
        }, dismissButton = { AppTextButton(onClick = { permissionDetails = false }) { Text(stringResource(R.string.common_close)) } })
    if (deleteKey) ConfirmDialog(stringResource(R.string.settings_delete_key_confirm_title), stringResource(R.string.settings_delete_key_confirm_message), stringResource(R.string.common_delete), {
        vm.deleteKey(); deleteKey = false
    }, { deleteKey = false })
    if (deleteAll) ConfirmDialog(stringResource(R.string.settings_delete_all_confirm_title), stringResource(R.string.settings_delete_all_confirm_message), stringResource(R.string.settings_delete_all_confirm_button), {
        vm.deleteLearningData(); deleteAll = false
    }, { deleteAll = false })
    if (deleteLanguageDialog) AlertDialog(
        onDismissRequest = { deleteLanguageDialog = false },
        title = { Text(stringResource(R.string.settings_delete_language_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LanguageRegistry.all.forEach { language ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !vm.isRunning) { deleteLanguageTarget = language.id }
                            .testTag("settings-delete-language-${language.id}").padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = deleteLanguageTarget == language.id, onClick = null, enabled = !vm.isRunning)
                        Text(language.settingsTitle, style = MaterialTheme.typography.bodyLarge,
                            color = AppColors.Ink, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { AppTextButton(onClick = { deleteLanguageDialog = false; languageDeleteConfirm = true },
            enabled = !vm.isRunning) { Text(stringResource(R.string.common_continue)) } },
        dismissButton = { AppTextButton(onClick = { deleteLanguageDialog = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
    if (languageDeleteConfirm) {
        val languageName = LanguageRegistry.get(deleteLanguageTarget)?.name ?: deleteLanguageTarget
        ConfirmDialog(stringResource(R.string.settings_delete_language_confirm_title, languageName),
            stringResource(R.string.settings_delete_language_confirm_message, languageName),
            stringResource(R.string.settings_delete_language_confirm_button, languageName), {
                vm.deleteLearningDataForLanguage(deleteLanguageTarget)
                languageDeleteConfirm = false
                // The ViewModel surfaces success and every refusal through its
                // notice; the sheet has no inline notice row, so toast it.
                vm.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }, { languageDeleteConfirm = false })
    }
    if (revokeConsent) ConfirmDialog(stringResource(R.string.settings_revoke_consent_confirm_title),
        stringResource(R.string.settings_revoke_consent_confirm_message), stringResource(R.string.settings_revoke_consent_button), {
            vm.updatePreferences(vm.archive.preferences.copy(aiConsentVersion = null)); revokeConsent = false
        }, { revokeConsent = false })
    deleteSession?.let { session -> ConfirmDialog(stringResource(R.string.history_delete_session_confirm_title), session.title, stringResource(R.string.common_delete), {
        vm.deleteSession(session.id); deleteSession = null
    }, { deleteSession = null }) }
    transcript?.let { TranscriptDialog(vm, it, onDismiss = { transcript = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHistorySheet(vm: AppViewModel, onDismiss: () -> Unit, onSelect: (SessionRecord) -> Unit, onDelete: (SessionRecord) -> Unit, onResume: (String) -> Unit = {}) {
    // Conversation search (phase G): a blank query keeps the plain history
    // list; a non-blank one swaps the list for FTS results after a short
    // debounce (LaunchedEffect cancels and restarts on every keystroke).
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        delay(250)
        results = vm.searchConversations(query)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.9f)) {
            SettingsSheetHeader(stringResource(R.string.history_section_title), onDismiss)
            AppSearchField(query, { query = it.take(200) }, stringResource(R.string.history_search_placeholder),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("history-search-input"))
            LazyColumn(Modifier.weight(1f).testTag("settings-history-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (query.isNotBlank()) {
                    // Results mode. The snippet carries the FTS markers («» around
                    // the match, … for elided text) rendered plainly; tap opens the
                    // conversation's transcript — the same navigation as tapping a
                    // history row. A pure read, so it stays available while a
                    // conversation runs.
                    val hits = results.orEmpty()
                    if (results != null && hits.isEmpty()) item {
                        Text(stringResource(R.string.history_search_no_results), style = MaterialTheme.typography.bodyLarge, color = AppColors.Secondary,
                            modifier = Modifier.padding(16.dp).testTag("history-search-empty"))
                    }
                    items(hits, key = { it.session.id }) { hit ->
                        SettingsGroup {
                            Column(Modifier.fillMaxWidth().clickable { onSelect(hit.session) }.padding(16.dp)
                                    .testTag("history-search-result-${hit.session.id}"),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(hit.session.title, style = MaterialTheme.typography.titleMedium)
                                Text(hit.snippet, style = MaterialTheme.typography.bodyMedium)
                                Text(formatDate(hit.session.startedAt), color = AppColors.Secondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    val history = vm.archive.sessions.filter { it.languageID == vm.language.id }.sortedByDescending { it.startedAt }
                    if (history.isEmpty()) item {
                        Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, color = AppColors.Secondary,
                            modifier = Modifier.padding(16.dp))
                    }
                    items(history, key = { it.id }) { session ->
                        SettingsGroup {
                            Column(Modifier.fillMaxWidth().clickable { onSelect(session) }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(session.title, style = MaterialTheme.typography.titleMedium)
                                Text("${LanguageRegistry.get(session.languageID)?.name ?: session.languageID} · ${formatDate(session.startedAt)}",
                                    color = AppColors.Secondary, style = MaterialTheme.typography.bodySmall)
                            }
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.conversations_continue), enabled = !vm.isRunning, onClick = { onResume(session.id) })
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.common_delete), enabled = !vm.isRunning, tint = AppColors.Red, onClick = { onDelete(session) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsProgressSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    // Computed once per open: a read-only projection of the local archive.
    val stats = remember { vm.stats() }
    val progress = remember { vm.progressStats() }
    val levelStats = remember { vm.currentLevelStats() }
    val medals = remember { vm.listeningMedals() }
    val mastery = remember { vm.masteryStats() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.9f)) {
            SettingsSheetHeader(stringResource(R.string.progress_section_title), onDismiss)
            LazyColumn(Modifier.weight(1f).testTag("settings-progress-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (stats.totalSessions == 0 && progress.lessonsDone == 0 && progress.last30DaysActivities.all { it == 0 }) item {
                    Text(stringResource(R.string.progress_empty), style = MaterialTheme.typography.bodyLarge, color = AppColors.Secondary,
                        modifier = Modifier.padding(16.dp))
                } else {
                    item {
                        SettingsGroup {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProgressRow(stringResource(R.string.progress_conversations), stats.totalSessions.toString())
                                val hours = stats.totalVoiceMinutes / 60
                                val minutes = stats.totalVoiceMinutes % 60
                                ProgressRow(stringResource(R.string.progress_practice_time),
                                    if (hours > 0) stringResource(R.string.progress_time_hm, hours, minutes)
                                    else stringResource(R.string.progress_time_m, minutes))
                                ProgressRow(stringResource(R.string.progress_day_streak), stats.activeStreakDays.toString())
                                ProgressRow(stringResource(R.string.progress_longest_streak), stats.longestStreakDays.toString())
                            }
                        }
                    }
                    if (progress.lessonsTotal > 0 || progress.activeStreakDays > 0) item {
                        SettingsGroup(stringResource(R.string.progress_learning_heading)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (progress.lessonsTotal > 0) {
                                    levelStats?.let {
                                        ProgressRow(stringResource(R.string.progress_level),
                                            stringResource(R.string.learn_level_label, it.level, levelTitle(it.level)))
                                    }
                                    ProgressRow(stringResource(R.string.progress_lessons_done),
                                        "${progress.lessonsDone}/${progress.lessonsTotal}")
                                }
                                if (progress.themesPractised > 0) ProgressRow(stringResource(R.string.progress_themes_practised),
                                    progress.themesPractised.toString())
                                if (progress.exercisesCompleted > 0) ProgressRow(stringResource(R.string.progress_exercises_completed),
                                    progress.exercisesCompleted.toString())
                                if (progress.listeningCompleted > 0) ProgressRow(stringResource(R.string.progress_listening_completed),
                                    progress.listeningCompleted.toString())
                                if (medals > 0) ProgressRow(stringResource(R.string.progress_listening_medals), medals.toString())
                                ProgressRow(stringResource(R.string.progress_activity_streak), progress.activeStreakDays.toString())
                            }
                        }
                    }
                    if (mastery != null && mastery.skills.isNotEmpty()) item {
                        SettingsGroup(stringResource(R.string.progress_skills_heading)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.progress_skills_overall, mastery.overall),
                                    style = MaterialTheme.typography.bodyMedium)
                                mastery.categories.forEach { category ->
                                    SkillMasteryBar(stringResource(skillCategoryLabel(category.category)), category.mastery)
                                }
                                val patterns = vm.errorPatterns()
                                if (patterns.isNotEmpty()) {
                                    Text(stringResource(R.string.progress_patterns, patterns.sumOf { it.count }),
                                        style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                                }
                            }
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.progress_words_by_recall)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val labels = listOf(R.string.progress_bar_new, R.string.progress_bar_fragile,
                                    R.string.progress_bar_growing, R.string.progress_bar_steady)
                                labels.forEachIndexed { bars, label ->
                                    ProgressRow(stringResource(label), stats.wordsByBars[bars].toString())
                                }
                            }
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.progress_last14)) {
                            val peak = (stats.last14DaysMinutes.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
                            Row(Modifier.fillMaxWidth().padding(16.dp).height(64.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                                stats.last14DaysMinutes.forEachIndexed { index, minutes ->
                                    Box(Modifier.weight(1f).height((6 + 52 * minutes / peak).dp)
                                        .background(if (index == stats.last14DaysMinutes.lastIndex) AppColors.Secondary
                                            else AppColors.Secondary.copy(alpha = .35f), RoundedCornerShape(3.dp)))
                                }
                            }
                        }
                    }
                    if (progress.last30DaysActivities.any { it > 0 }) item {
                        SettingsGroup(stringResource(R.string.progress_last30)) {
                            val peak = (progress.last30DaysActivities.maxOrNull() ?: 0).coerceAtLeast(1)
                            Row(Modifier.fillMaxWidth().padding(16.dp).height(64.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                                progress.last30DaysActivities.forEachIndexed { index, count ->
                                    Box(Modifier.weight(1f).height((6 + 52 * count / peak).dp)
                                        .background(if (index == progress.last30DaysActivities.lastIndex) AppColors.Secondary
                                            else AppColors.Secondary.copy(alpha = .35f), RoundedCornerShape(2.dp)))
                                }
                            }
                        }
                    }
                    if (stats.capabilityCount > 0) item {
                        SettingsGroup(stringResource(R.string.progress_capabilities, stats.capabilityCount)) {
                            stats.latestCapability?.let {
                                Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    item {
                        Text(stringResource(R.string.progress_scope_note),
                            style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = AppColors.Secondary)
    }
}

@Composable
private fun SkillMasteryBar(label: String, mastery: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.progress_skills_percent, mastery),
                style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary)
        }
        Box(Modifier.fillMaxWidth().height(5.dp).background(AppColors.Cream, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth((mastery / 100.0).toFloat())
                .height(5.dp).background(AppColors.Secondary, RoundedCornerShape(3.dp)))
        }
    }
}

private fun skillCategoryLabel(category: String): Int = when (category) {
    "grammar" -> R.string.skill_category_grammar
    "vocab" -> R.string.skill_category_vocab
    "function" -> R.string.skill_category_function
    "speaking" -> R.string.skill_category_speaking
    "listening" -> R.string.skill_category_listening
    "reading" -> R.string.skill_category_reading
    "writing" -> R.string.skill_category_writing
    else -> R.string.skill_category_pron
}

@Composable
private fun KeyDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    // Intentionally starts empty even when a key exists; secrets never flow back into Compose state.
    var key by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { key = ""; onDismiss() }) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)?.let { it != 0 } ?: false
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = AppColors.Surface) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text(stringResource(R.string.settings_key_dialog_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.settings_key_dialog_note), color = AppColors.Secondary)
                AppTextField(
                    key,
                    { key = it.take(500) },
                    Modifier.fillMaxWidth().testTag("api-key-input").semantics { password() },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    label = { Text(stringResource(R.string.settings_key_dialog_field_label)) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AppTextButton(onClick = { key = ""; onDismiss() }) { Text(stringResource(R.string.common_cancel)) }
                    Button(onClick = { vm.saveKey(key.trim()); key = ""; onDismiss() }, enabled = key.isNotBlank()) { Text(stringResource(R.string.common_save)) }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { AppTextButton(onClick = onConfirm) { Text(confirm, color = AppColors.Red) } },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun TranscriptDialog(vm: AppViewModel, session: SessionRecord, onDismiss: () -> Unit) {
    var correcting by remember { mutableStateOf<Passage?>(null) }
    val liveSession = vm.archive.sessions.firstOrNull { it.id == session.id }
        ?: vm.session?.takeIf { it.id == session.id }
        ?: session
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = AppColors.Surface) {
            LazyColumn(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text(liveSession.title, style = MaterialTheme.typography.headlineMedium)
                    Text("${formatDate(liveSession.startedAt)} · ${pluralStringResource(R.plurals.history_passages_count, liveSession.passages.size, liveSession.passages.size)}", color = AppColors.Secondary)
                }
                items(liveSession.passages, key = { it.id }) { passage ->
                    Column(
                        Modifier.fillMaxWidth().background(
                            if (passage.speaker == Speaker.user) AppColors.SurfaceBright else AppColors.Peach,
                            RoundedCornerShape(18.dp),
                        ).padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (passage.speaker == Speaker.user) R.string.history_speaker_you else R.string.history_speaker_app),
                                style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary, modifier = Modifier.weight(1f))
                            if (passage.speaker == Speaker.assistant && passage.text.isNotBlank()) {
                                ReportUtteranceAction(onClick = {
                                    vm.reportUtterance(liveSession.id, passage.id)
                                    onDismiss()
                                }, modifier = Modifier.testTag("report-history-${passage.id}"))
                            }
                        }
                        SelectionContainer { Text(passage.text) }
                        if (liveSession.languageID == "zh") PinyinHelp(passage.text)
                        if (passage.speaker == Speaker.user && !vm.isRunning) AppTextButton(onClick = { correcting = passage }) { Text(stringResource(R.string.history_edit_passage_button)) }
                    }
                }
                liveSession.topics.flatMap { it.sources }.filter { it.safeUrl() != null }.takeIf { it.isNotEmpty() }?.let { sources ->
                    item { Text(stringResource(R.string.history_saved_sources), fontWeight = FontWeight.SemiBold) }
                    items(sources) { source ->
                        val uriHandler = LocalUriHandler.current
                        Text("↗ ${source.title}", color = AppColors.Orange, modifier = Modifier.clickable { source.safeUrl()?.let(uriHandler::openUri) }.padding(vertical = 6.dp))
                    }
                }
                item { AppTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_close)) } }
            }
        }
    }
    correcting?.let { passage -> CorrectionDialog(passage, onSave = {
        vm.correctPassage(liveSession.id, passage.id, it); correcting = null
    }, onDismiss = { correcting = null }) }
}

@Composable
private fun CorrectionDialog(passage: Passage, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(passage.id) { mutableStateOf(passage.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_correction_dialog_title)) },
        text = { AppTextField(text, { text = it.take(10_000) }, minLines = 3, maxLines = 9) },
        confirmButton = { Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
