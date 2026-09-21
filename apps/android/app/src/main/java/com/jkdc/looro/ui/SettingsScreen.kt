package com.jkdc.looro.ui

import android.view.WindowManager
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.jkdc.looro.BuildConfig
import com.jkdc.looro.LooroViewModel
import com.jkdc.looro.R
import com.jkdc.looro.core.LanguageRegistry
import com.jkdc.looro.core.MeaningLanguages
import com.jkdc.looro.core.Passage
import com.jkdc.looro.core.SessionRecord
import com.jkdc.looro.core.Speaker
import com.jkdc.looro.core.UsageSummary
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch

@Composable
fun SettingsScreen(vm: LooroViewModel, onExport: () -> Unit, onImport: () -> Unit, onReviewConsent: () -> Unit,
                   onAccount: (() -> Unit)? = null, onDismiss: () -> Unit = {}) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var keyDialog by rememberSaveable { mutableStateOf(false) }
    var deleteKey by rememberSaveable { mutableStateOf(false) }
    var deleteAll by rememberSaveable { mutableStateOf(false) }
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
                    SettingsSwitchRow(stringResource(R.string.settings_slow_speech), "settings-slow-speech",
                        prefs.slowSpeech == true, !vm.isRunning) {
                        vm.updatePreferences(prefs.copy(slowSpeech = if (it) true else null))
                    }
                    SettingsDivider()
                    Column(Modifier.fillMaxWidth()) {
                        SettingsSwitchRow(stringResource(R.string.settings_listening_practice), "settings-listening-practice",
                            prefs.listeningPractice == true, !vm.isRunning) {
                            vm.updatePreferences(prefs.copy(listeningPractice = if (it) true else null))
                        }
                        Text(stringResource(R.string.settings_listening_practice_hint),
                            style = MaterialTheme.typography.bodySmall, color = LooroColors.Secondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                    }
                    SettingsDivider()
                    // On-device voice controls: inert in the WebRTC build, where
                    // turn-taking is server-side — never render them there.
                    if (BuildConfig.CONVERSATION_ENGINE != "openai") {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_voice_manual_turn),
                                style = MaterialTheme.typography.bodyLarge, color = LooroColors.Ink,
                                modifier = Modifier.weight(1f).padding(end = 12.dp))
                            Switch(checked = vm.voiceManualTurn, onCheckedChange = { vm.setVoiceManualTurn(it) })
                        }
                        SettingsDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.settings_silence_label),
                                    style = MaterialTheme.typography.bodyLarge, color = LooroColors.Ink)
                                Text(String.format(java.util.Locale.US, "%.1f s", vm.silenceEndMs / 1000f),
                                    style = MaterialTheme.typography.bodyLarge, color = LooroColors.Secondary)
                            }
                            Slider(value = vm.silenceEndMs.toFloat(),
                                onValueChange = { vm.updateSilenceEndMs(((it / 100).toLong() * 100)) },
                                valueRange = 600f..3000f)
                            Text(stringResource(R.string.settings_silence_hint),
                                style = MaterialTheme.typography.bodySmall, color = LooroColors.Secondary)
                        }
                        SettingsDivider()
                    }
                    SettingsRow(stringResource(R.string.settings_corrections_label), stringResource(R.string.settings_corrections_value))
                    SettingsDivider()
                    BasicTextField(value = prefs.interests, onValueChange = { vm.updatePreferences(prefs.copy(interests = it.take(500))) },
                        enabled = !vm.isRunning, textStyle = MaterialTheme.typography.bodyLarge.copy(color = LooroColors.Ink),
                        cursorBrush = SolidColor(LooroColors.Secondary), minLines = 1, maxLines = 4,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 14.dp)
                            .testTag("settings-interests"), decorationBox = { field ->
                            if (prefs.interests.isEmpty()) Text(stringResource(R.string.settings_interests_label),
                                style = MaterialTheme.typography.bodyLarge, color = LooroColors.Secondary.copy(alpha = .7f))
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
                                color = LooroColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                            SettingsRow(stringResource(if (vm.hasKey) R.string.settings_replace_key else R.string.settings_save_key),
                                enabled = !vm.isRunning, tint = LooroColors.Secondary, chevron = true, onClick = { keyDialog = true })
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_open_api_keys), tint = LooroColors.Secondary,
                                onClick = { open("https://platform.openai.com/api-keys") })
                            if (vm.hasKey) {
                                SettingsDivider()
                                SettingsRow(stringResource(R.string.settings_remove_key), enabled = !vm.isRunning,
                                    tint = LooroColors.Red, onClick = { deleteKey = true })
                            }
                            Text(stringResource(R.string.settings_key_owner_footer), style = MaterialTheme.typography.bodySmall,
                                color = LooroColors.Secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
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
                    SettingsRow(stringResource(R.string.settings_usage_billing_link), tint = LooroColors.Secondary,
                        onClick = { open("https://platform.openai.com/usage") })
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_section_your_data), stringResource(R.string.settings_backup_footer)) {
                    SettingsRow(stringResource(R.string.settings_export_backup), enabled = !vm.isRunning, symbol = SettingsSymbol.EXPORT,
                        tint = LooroColors.Secondary, onClick = onExport)
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_import_backup), enabled = !vm.isRunning, symbol = SettingsSymbol.IMPORT,
                        tint = LooroColors.Secondary, onClick = onImport)
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.progress_section_title), chevron = true,
                        modifier = Modifier.testTag("settings-progress"), onClick = { progress = true })
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.history_section_title), symbol = SettingsSymbol.HISTORY, chevron = true,
                        modifier = Modifier.testTag("settings-history"), onClick = { history = true })
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_delete_all_data), enabled = !vm.isRunning,
                        tint = LooroColors.Red, onClick = { deleteAll = true })
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_section_help_privacy)) {
                    SettingsRow(stringResource(R.string.common_privacy_policy), tint = LooroColors.Secondary,
                        onClick = { open("https://looro.example/privacy/") })
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.common_terms_of_use), tint = LooroColors.Secondary,
                        onClick = { open("https://looro.example/terms/") })
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.common_contact_support), tint = LooroColors.Secondary,
                        onClick = { open("https://looro.example/support/") })
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_section_ai_permission), chevron = true,
                        modifier = Modifier.testTag("settings-ai-permission"), onClick = { permissionDetails = true })
                }
            }
            item {
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_app_version_footer, version), style = MaterialTheme.typography.bodySmall, color = LooroColors.Secondary)
                        Text(stringResource(R.string.settings_models_footer), style = MaterialTheme.typography.bodySmall, color = LooroColors.Secondary)
                    }
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_openai_data_controls), tint = LooroColors.Secondary,
                        onClick = { open("https://developers.openai.com/api/docs/guides/your-data") })
                    Text(stringResource(R.string.settings_data_use_footer), style = MaterialTheme.typography.bodySmall,
                        color = LooroColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_open_source_notices), chevron = true, onClick = { notices = true })
                }
            }
        }
    }

    if (keyDialog) KeyDialog(vm, onDismiss = { keyDialog = false })
    if (notices) NoticesDialog(onDismiss = { notices = false })
    if (history) SettingsHistorySheet(vm, onDismiss = { history = false }, onSelect = { transcript = it }, onDelete = { deleteSession = it })
    if (progress) SettingsProgressSheet(vm, onDismiss = { progress = false })
    if (permissionDetails) AlertDialog(onDismissRequest = { permissionDetails = false },
        title = { Text(stringResource(R.string.settings_section_ai_permission)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(if (prefs.aiConsentVersion == AI_CONSENT_VERSION) R.string.settings_ai_consent_accepted else R.string.settings_ai_consent_not_accepted),
                style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_ai_permission_summary), color = LooroColors.Secondary)
        } },
        confirmButton = {
            if (prefs.aiConsentVersion == AI_CONSENT_VERSION) LooroTextButton(onClick = { permissionDetails = false; revokeConsent = true },
                enabled = !vm.isRunning, modifier = Modifier.testTag("revoke-ai-consent")) {
                Text(stringResource(R.string.settings_revoke_consent_button), color = LooroColors.Red)
            } else LooroTextButton(onClick = { permissionDetails = false; onReviewConsent() },
                enabled = !vm.isRunning, modifier = Modifier.testTag("review-ai-consent")) { Text(stringResource(R.string.settings_review_consent_button)) }
        }, dismissButton = { LooroTextButton(onClick = { permissionDetails = false }) { Text(stringResource(R.string.common_close)) } })
    if (deleteKey) ConfirmDialog(stringResource(R.string.settings_delete_key_confirm_title), stringResource(R.string.settings_delete_key_confirm_message), stringResource(R.string.common_delete), {
        vm.deleteKey(); deleteKey = false
    }, { deleteKey = false })
    if (deleteAll) ConfirmDialog(stringResource(R.string.settings_delete_all_confirm_title), stringResource(R.string.settings_delete_all_confirm_message), stringResource(R.string.settings_delete_all_confirm_button), {
        vm.deleteLearningData(); deleteAll = false
    }, { deleteAll = false })
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
private fun SettingsHistorySheet(vm: LooroViewModel, onDismiss: () -> Unit, onSelect: (SessionRecord) -> Unit, onDelete: (SessionRecord) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LooroColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.9f)) {
            SettingsSheetHeader(stringResource(R.string.history_section_title), onDismiss)
            LazyColumn(Modifier.weight(1f).testTag("settings-history-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (vm.archive.sessions.isEmpty()) item {
                    Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, color = LooroColors.Secondary,
                        modifier = Modifier.padding(16.dp))
                }
                items(vm.archive.sessions.sortedByDescending { it.startedAt }, key = { it.id }) { session ->
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().clickable { onSelect(session) }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium)
                            Text("${LanguageRegistry.get(session.languageID)?.name ?: session.languageID} · ${formatDate(session.startedAt)}",
                                color = LooroColors.Secondary, style = MaterialTheme.typography.bodySmall)
                        }
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.common_delete), enabled = !vm.isRunning, tint = LooroColors.Red, onClick = { onDelete(session) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsProgressSheet(vm: LooroViewModel, onDismiss: () -> Unit) {
    // Computed once per open: a read-only projection of the local archive.
    val stats = remember { vm.stats() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LooroColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.9f)) {
            SettingsSheetHeader(stringResource(R.string.progress_section_title), onDismiss)
            LazyColumn(Modifier.weight(1f).testTag("settings-progress-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (stats.totalSessions == 0) item {
                    Text(stringResource(R.string.progress_empty), style = MaterialTheme.typography.bodyLarge, color = LooroColors.Secondary,
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
                                        .background(if (index == stats.last14DaysMinutes.lastIndex) LooroColors.Secondary
                                            else LooroColors.Secondary.copy(alpha = .35f), RoundedCornerShape(3.dp)))
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
                            style = MaterialTheme.typography.bodySmall, color = LooroColors.Secondary,
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
        Text(value, style = MaterialTheme.typography.bodyLarge, color = LooroColors.Secondary)
    }
}

@Composable
private fun KeyDialog(vm: LooroViewModel, onDismiss: () -> Unit) {
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
        Surface(shape = RoundedCornerShape(28.dp), color = LooroColors.Surface) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text(stringResource(R.string.settings_key_dialog_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.settings_key_dialog_note), color = LooroColors.Secondary)
                LooroTextField(
                    key,
                    { key = it.take(500) },
                    Modifier.fillMaxWidth().testTag("api-key-input").semantics { password() },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    label = { Text(stringResource(R.string.settings_key_dialog_field_label)) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    LooroTextButton(onClick = { key = ""; onDismiss() }) { Text(stringResource(R.string.common_cancel)) }
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
        confirmButton = { LooroTextButton(onClick = onConfirm) { Text(confirm, color = LooroColors.Red) } },
        dismissButton = { LooroTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun TranscriptDialog(vm: LooroViewModel, session: SessionRecord, onDismiss: () -> Unit) {
    var correcting by remember { mutableStateOf<Passage?>(null) }
    val liveSession = vm.archive.sessions.firstOrNull { it.id == session.id }
        ?: vm.session?.takeIf { it.id == session.id }
        ?: session
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = LooroColors.Surface) {
            LazyColumn(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text(liveSession.title, style = MaterialTheme.typography.headlineMedium)
                    Text("${formatDate(liveSession.startedAt)} · ${pluralStringResource(R.plurals.history_passages_count, liveSession.passages.size, liveSession.passages.size)}", color = LooroColors.Secondary)
                }
                items(liveSession.passages, key = { it.id }) { passage ->
                    Column(
                        Modifier.fillMaxWidth().background(
                            if (passage.speaker == Speaker.user) LooroColors.SurfaceBright else LooroColors.Peach,
                            RoundedCornerShape(18.dp),
                        ).padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (passage.speaker == Speaker.user) R.string.history_speaker_you else R.string.history_speaker_looro),
                                style = MaterialTheme.typography.labelSmall, color = LooroColors.Secondary, modifier = Modifier.weight(1f))
                            if (passage.speaker == Speaker.assistant && passage.text.isNotBlank()) {
                                ReportUtteranceAction(onClick = {
                                    vm.reportUtterance(liveSession.id, passage.id)
                                    onDismiss()
                                }, modifier = Modifier.testTag("report-history-${passage.id}"))
                            }
                        }
                        SelectionContainer { Text(passage.text) }
                        if (liveSession.languageID == "zh") PinyinHelp(passage.text)
                        if (passage.speaker == Speaker.user && !vm.isRunning) LooroTextButton(onClick = { correcting = passage }) { Text(stringResource(R.string.history_edit_passage_button)) }
                    }
                }
                liveSession.topics.flatMap { it.sources }.filter { it.safeUrl() != null }.takeIf { it.isNotEmpty() }?.let { sources ->
                    item { Text(stringResource(R.string.history_saved_sources), fontWeight = FontWeight.SemiBold) }
                    items(sources) { source ->
                        val uriHandler = LocalUriHandler.current
                        Text("↗ ${source.title}", color = LooroColors.Orange, modifier = Modifier.clickable { source.safeUrl()?.let(uriHandler::openUri) }.padding(vertical = 6.dp))
                    }
                }
                item { LooroTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_close)) } }
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
        text = { LooroTextField(text, { text = it.take(10_000) }, minLines = 3, maxLines = 9) },
        confirmButton = { Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { LooroTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
