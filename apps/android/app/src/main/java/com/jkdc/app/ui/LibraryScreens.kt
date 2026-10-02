package com.jkdc.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jkdc.app.AppViewModel
import com.jkdc.app.R
import com.jkdc.app.core.ConversationTheme
import com.jkdc.app.core.Scenario
import com.jkdc.app.core.Scenarios
import com.jkdc.app.core.TopicBrief
import com.jkdc.app.core.WordPractice
import com.jkdc.app.core.WordState
import com.jkdc.app.core.RecallEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TopicsScreen(vm: AppViewModel, onChoose: () -> Unit, onCurrentTopic: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All") }
    var current by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(false) }
    val categories = remember(vm.language.id) { listOf("All") + vm.language.themes.map { it.category }.distinct() }
    val themes = vm.language.themes.filter {
        (category == "All" || it.category == category) &&
            (search.isBlank() || it.title.contains(search, true) || it.subtitle.contains(search, true) || it.category.contains(search, true))
    }
    val scenarios = Scenarios.shared.filter {
        search.isBlank() || it.title.contains(search, true) || it.subtitle.contains(search, true) || it.category.contains(search, true)
    }

    LazyVerticalGrid(columns = GridCells.Adaptive(if (LocalDensity.current.fontScale > 1.3f) 260.dp else 150.dp),
        modifier = Modifier.fillMaxSize().testTag("topics-screen").padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            AppSearchField(search, { search = it.take(80) }, stringResource(R.string.topics_search_placeholder))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHeading(stringResource(R.string.topics_eyebrow), stringResource(R.string.topics_title),
                stringResource(R.string.topics_subtitle), Modifier.padding(top = 20.dp, bottom = 10.dp))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { vm.chooseTheme(null); onChoose() },
                shape = RoundedCornerShape(26.dp), color = AppColors.Surface.copy(alpha = .85f)) {
                Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(AppSymbol.Wave)
                    Text(stringResource(R.string.topics_just_talk), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    AppIcon(AppSymbol.ChevronRight, Modifier.size(19.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { item ->
                    FilterChip(selected = category == item, onClick = { category = item }, label = { Text(categoryLabel(item), style = MaterialTheme.typography.bodySmall) },
                        shape = CircleShape, border = null,
                        colors = FilterChipDefaults.filterChipColors(containerColor = AppColors.Surface.copy(alpha = .70f),
                            selectedContainerColor = AppColors.Peach, selectedLabelColor = AppColors.Ink),
                        modifier = Modifier.height(44.dp))
                }
            }
        }
        itemsIndexed(themes, key = { _, theme -> theme.id }) { index, theme ->
            ThemeCard(theme, index) {
                if (theme.id == "today") current = true
                else { vm.chooseTheme(theme); onChoose() }
            }
        }
        if (themes.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(stringResource(R.string.topics_no_results), color = AppColors.Secondary, modifier = Modifier.padding(vertical = 28.dp))
        }
        if (scenarios.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 16.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.scenarios_section_title), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.scenarios_section_subtitle), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                }
            }
            itemsIndexed(scenarios, key = { _, scenario -> "scenario-${scenario.id}" }) { index, scenario ->
                ScenarioCard(scenario, index) { vm.chooseScenario(scenario); onChoose() }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "scenario-custom") {
                Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { custom = true },
                    shape = RoundedCornerShape(26.dp), color = AppColors.Butter.copy(alpha = .8f)) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(AppSymbol.Sparkles, color = AppColors.Secondary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.scenarios_custom_card_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.scenarios_custom_card_subtitle), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                        }
                        AppIcon(AppSymbol.ChevronRight, Modifier.size(19.dp), color = AppColors.Secondary)
                    }
                }
            }
        }
    }
    if (current) CurrentTopicDialog(vm, onDismiss = { current = false }, onFind = onCurrentTopic, onDiscuss = {
        vm.discuss(it); current = false; onChoose()
    })
    if (custom) CustomScenarioDialog(vm, onDismiss = { custom = false }, onStart = onChoose)
}

@Composable
private fun ScenarioCard(scenario: Scenario, index: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).testTag("scenario-${scenario.id}"),
        shape = RoundedCornerShape(26.dp),
        color = AppColors.Panels.getOrElse(scenario.colorIndex) { AppColors.Panels[index % AppColors.Panels.size] },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(19.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            AppIcon(scenarioSymbol(scenario.id), Modifier.size(31.dp), color = AppColors.Secondary)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(scenario.title, style = MaterialTheme.typography.titleMedium)
                Text(scenario.subtitle, color = AppColors.Secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CustomScenarioDialog(vm: AppViewModel, onDismiss: () -> Unit, onStart: () -> Unit) {
    var goal by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = AppColors.Surface) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PageHeading(stringResource(R.string.scenarios_custom_eyebrow), stringResource(R.string.scenarios_custom_title),
                    stringResource(R.string.scenarios_custom_subtitle))
                AppTextField(goal, { goal = it.take(160) }, modifier = Modifier.fillMaxWidth().testTag("scenario-custom-input"),
                    label = { Text(stringResource(R.string.scenarios_custom_placeholder)) }, minLines = 2, maxLines = 4)
                Button(onClick = { vm.chooseScenario(Scenarios.custom(goal)); onStart() },
                    enabled = goal.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("scenario-custom-start"),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                    Text(stringResource(R.string.scenarios_custom_start))
                }
                Text(stringResource(R.string.scenarios_custom_note), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                AppTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_close)) }
            }
        }
    }
}

private fun scenarioSymbol(id: String) = when (id) {
    "job_interview" -> AppSymbol.Star
    "presentation" -> AppSymbol.Mic
    "sports" -> AppSymbol.Sun
    "restaurant" -> AppSymbol.Food
    "doctor" -> AppSymbol.Leaf
    "phone_call" -> AppSymbol.Wave
    "small_talk" -> AppSymbol.Coffee
    "shopping" -> AppSymbol.Themes
    else -> AppSymbol.Sparkles
}

@Composable
private fun ThemeCard(theme: ConversationTheme, index: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        color = AppColors.Panels.getOrElse(theme.colorIndex) { AppColors.Panels[index % AppColors.Panels.size] },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(19.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            AppIcon(themeSymbol(theme.id), Modifier.size(31.dp), color = AppColors.Secondary)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(theme.title, style = MaterialTheme.typography.titleMedium)
                Text(theme.subtitle, color = AppColors.Secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CurrentTopicDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onFind: (String) -> Unit,
    onDiscuss: (TopicBrief) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = AppColors.Surface) {
            LazyColumn(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { PageHeading(stringResource(R.string.topics_current_dialog_eyebrow), stringResource(R.string.topics_current_dialog_title), stringResource(R.string.topics_current_dialog_subtitle)) }
                item {
                    AppTextField(
                        query,
                        { query = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(vm.language.topicPlaceholder) },
                        minLines = 2,
                        maxLines = 4,
                    )
                }
                item {
                    Button(onClick = { onFind(query.trim()) }, enabled = query.isNotBlank() && !vm.working, modifier = Modifier.fillMaxWidth()) {
                        if (vm.working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(if (vm.working) "  " + stringResource(R.string.topics_finding_button) else stringResource(R.string.topics_find_button))
                    }
                }
                vm.topicResult?.let { brief ->
                    item { Text(inlineMarkdown(brief.text), style = MaterialTheme.typography.bodyLarge) }
                    if (brief.sources.isNotEmpty()) {
                        item { Text(stringResource(R.string.topics_sources_heading), fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }) }
                        items(brief.sources.filter { it.safeUrl() != null }) { source ->
                            Text(
                                "↗ ${source.title}",
                                color = AppColors.Orange,
                                modifier = Modifier.fillMaxWidth().clickable { source.safeUrl()?.let(uriHandler::openUri) }.padding(vertical = 7.dp),
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = { onDiscuss(brief) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink),
                        ) { Text(stringResource(R.string.topics_talk_about_button)) }
                    }
                }
                item { Text(stringResource(R.string.topics_sources_note), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary) }
                item { AppTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_close)) } }
            }
        }
    }
}

@Composable
fun WordsScreen(vm: AppViewModel, onOpenLessons: () -> Unit = {}, onOpenTalk: () -> Unit = {}, onLookupTerm: (String) -> Unit = {}) {
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<WordState?>(null) }
    var practising by rememberSaveable { mutableStateOf(false) }
    val words = vm.learner.words.filter { search.isBlank() || it.lemma.contains(search, true) || it.meaning.contains(search, true) }
    val dueCount = vm.learner.words.count { it.dueAt < com.jkdc.app.core.nowSeconds() }
    LazyColumn(
        Modifier.fillMaxSize().testTag("words-screen").padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item { PageHeading(stringResource(R.string.words_eyebrow, vm.language.name), stringResource(R.string.words_title), stringResource(R.string.words_subtitle), Modifier.padding(top = 20.dp)) }
        item {
            Button(onClick = { practising = true }, enabled = vm.learner.words.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("words-practice"),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Sage, contentColor = AppColors.Ink)) {
                Text(stringResource(if (dueCount > 0) R.string.practice_button else R.string.practice_button_extra, dueCount))
            }
        }
        item {
            AppSearchField(search, { search = it.take(80) }, stringResource(R.string.words_search_placeholder))
        }
        item {
            Text(stringResource(R.string.words_expressions_heading), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
        }
        val expressions = vm.notebookExpressions()
        if (expressions.isEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.words_expressions_empty), style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onOpenLessons, modifier = Modifier.weight(1f).testTag("words-cta-lessons"),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Sage, contentColor = AppColors.Ink)) {
                        Text(stringResource(R.string.words_empty_cta_lessons))
                    }
                    Button(onClick = onOpenTalk, modifier = Modifier.weight(1f).testTag("words-cta-talk"),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Sage, contentColor = AppColors.Ink)) {
                        Text(stringResource(R.string.words_empty_cta_talk))
                    }
                }
            }
        } else items(expressions, key = { RecallEngine.expressionKey(it.text, it.languageID) }) { expression ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = AppColors.Butter) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(expression.text, style = MaterialTheme.typography.titleLarge)
                        Text(expression.meaning, color = AppColors.Secondary)
                        Text(expressionSourceLabel(expression.source), style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        RecallBars(expression.bars)
                        AppTextButton(onClick = { vm.removeExpression(expression.text) },
                            modifier = Modifier.testTag("expression-remove-" + expression.text)) {
                            Text(stringResource(R.string.words_expression_remove))
                        }
                    }
                }
            }
        }
        if (words.isEmpty()) item {
            Surface(shape = RoundedCornerShape(28.dp), color = AppColors.Sage) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(AppSymbol.Leaf, Modifier.size(34.dp), color = AppColors.Secondary)
                    Text(stringResource(if (search.isBlank()) R.string.words_empty_title_blank else R.string.words_empty_title_search), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(if (search.isBlank()) R.string.words_empty_subtitle_blank else R.string.words_empty_subtitle_search),
                        color = AppColors.Secondary,
                    )
                }
            }
        } else items(words, key = { it.id }) { word ->
            Surface(Modifier.fillMaxWidth().clickable { selected = word }, shape = RoundedCornerShape(20.dp), color = AppColors.Surface) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(word.lemma, style = MaterialTheme.typography.titleLarge)
                        Text(word.meaning, color = AppColors.Secondary)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        RecallBars(word.bars)
                        Text(wordLabel(word.label), style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.words_recall_note), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
        }
        if (vm.learner.capabilities.isNotEmpty()) item {
            Surface(shape = RoundedCornerShape(24.dp), color = AppColors.Butter) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(stringResource(R.string.words_capabilities_title), style = MaterialTheme.typography.titleLarge)
                    vm.learner.capabilities.forEach { Text(it) }
                    Text(stringResource(R.string.words_capabilities_note), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    selected?.let { word -> WordDialog(word, onRemove = { vm.hideWord(word.id); selected = null }, onDismiss = { selected = null }) }
    if (practising) WritingPracticeSheet(vm, onDismiss = { practising = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WritingPracticeSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    // Due words first (earliest first, capped per drill); with nothing due the
    // "extra practice" mode drills the whole collection instead. Attempts are
    // not stored in this change.
    val due = remember {
        val now = com.jkdc.app.core.nowSeconds()
        val pending = vm.learner.words.filter { it.dueAt < now }.sortedBy { it.dueAt }
        (if (pending.isNotEmpty()) pending else vm.learner.words.sortedBy { it.dueAt }).take(10)
    }
    var index by rememberSaveable { mutableStateOf(0) }
    var answer by rememberSaveable { mutableStateOf("") }
    var missed by rememberSaveable { mutableStateOf(false) }
    var correctCount by rememberSaveable { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.9f).testTag("writing-practice")) {
            SettingsSheetHeader(stringResource(R.string.practice_title), onDismiss)
            when {
                due.isEmpty() -> Text(stringResource(R.string.practice_no_due_words),
                    Modifier.padding(20.dp), color = AppColors.Secondary)
                index >= due.size -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.practice_summary, due.size, correctCount), style = MaterialTheme.typography.titleLarge)
                    AppTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_close)) }
                }
                else -> {
                    val word = due[index]
                    val masked = remember(word) { WordPractice.maskedExample(word.example, word.form) }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.practice_progress, index + 1, due.size),
                            style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                        Text(stringResource(R.string.practice_prompt), style = MaterialTheme.typography.titleMedium)
                        Text(word.meaning, style = MaterialTheme.typography.headlineMedium)
                        if (masked.isNotBlank()) Text(masked, color = AppColors.Secondary)
                        if (!missed) {
                            AppTextField(answer, { answer = it.take(80) }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("practice-answer"),
                                label = { Text(stringResource(R.string.practice_your_answer)) })
                            Button(onClick = {
                                if (WordPractice.isCorrect(answer, word.form, word.lemma)) {
                                    correctCount++; index++; answer = ""
                                    vm.wordPracticed(word)
                                    if (index >= due.size) vm.wordsPracticeRoundCompleted(correctCount, due.size)
                                } else missed = true
                            }, enabled = answer.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("practice-check"),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                                Text(stringResource(R.string.practice_check))
                            }
                        } else {
                            Text(stringResource(R.string.practice_miss, word.form), style = MaterialTheme.typography.titleMedium, color = AppColors.Red)
                            Button(onClick = { index++; answer = ""; missed = false },
                                modifier = Modifier.fillMaxWidth().testTag("practice-next"),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                                Text(stringResource(R.string.practice_next))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WordDialog(word: WordState, onRemove: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = AppColors.Surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text(word.lemma, style = MaterialTheme.typography.headlineLarge)
                if (word.id.startsWith("zh|")) PinyinHelp(word.lemma)
                Text(word.meaning, style = MaterialTheme.typography.titleLarge, color = AppColors.Secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RecallBars(word.bars); Text(wordLabel(word.label))
                }
                Text(stringResource(wordExplanationRes(word)))
                Surface(shape = RoundedCornerShape(18.dp), color = AppColors.Peach) { Text(stringResource(R.string.words_example_quote, word.example), Modifier.padding(17.dp)) }
                Text(
                    "${pluralStringResource(R.plurals.words_independent_uses, word.independentCount, word.independentCount)} · ${stringResource(R.string.words_last_seen, formatDate(word.lastSeen))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Secondary,
                )
                AppTextButton(onClick = onRemove) { Text(stringResource(R.string.words_remove_button), color = AppColors.Red) }
                AppTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.words_done_button)) }
            }
        }
    }
}

internal fun formatDate(secondsSince2001: Double): String = runCatching {
    val unix = (secondsSince2001 + com.jkdc.app.core.APPLE_EPOCH_UNIX_SECONDS).toLong()
    DateTimeFormatter.ofPattern("d MMM yyyy").format(Instant.ofEpochSecond(unix).atZone(ZoneId.systemDefault()))
}.getOrDefault("—")

@Composable
private fun categoryLabel(value: String) = when (value) {
    "All" -> stringResource(R.string.topics_category_all)
    "Everyday" -> stringResource(R.string.topics_category_everyday)
    "Connection" -> stringResource(R.string.topics_category_connection)
    "Local life" -> stringResource(R.string.topics_category_local_life)
    "Interests" -> stringResource(R.string.topics_category_interests)
    else -> value
}

@StringRes
internal fun wordExplanationRes(word: WordState): Int = when {
    word.independentCount == 0 -> R.string.words_explanation_supported
    word.bars == 1 -> R.string.words_explanation_recalled_once
    word.bars == 2 -> R.string.words_explanation_recalled_days
    else -> R.string.words_explanation_steady
}

@Composable
private fun wordLabel(value: String) = when (value) {
    "New" -> stringResource(R.string.words_recall_new)
    "Fragile" -> stringResource(R.string.words_recall_fragile)
    "Growing" -> stringResource(R.string.words_recall_growing)
    "Steady" -> stringResource(R.string.words_recall_steady)
    else -> value
}

@Composable
private fun expressionSourceLabel(value: String) = when (value) {
    "lesson" -> stringResource(R.string.words_source_lesson)
    "lookup" -> stringResource(R.string.words_source_lookup)
    else -> value
}

private fun themeSymbol(id: String) = when (id) {
    "coffee" -> AppSymbol.Coffee
    "weekend" -> AppSymbol.Sun
    "walk", "cabin" -> AppSymbol.Leaf
    "dinner", "restaurant" -> AppSymbol.Food
    "music" -> AppSymbol.Music
    "books" -> AppSymbol.Words
    "today", "travel", "travelstories" -> AppSymbol.Globe
    else -> AppSymbol.Sparkles
}
