package com.jkdc.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jkdc.app.AppViewModel
import com.jkdc.app.R
import com.jkdc.app.core.CourseRegistry
import com.jkdc.app.core.CourseUnit
import com.jkdc.app.core.ExerciseEngine
import com.jkdc.app.core.LevelEngine
import com.jkdc.app.core.ListeningRegistry
import com.jkdc.app.core.ListeningText
import com.jkdc.app.core.ReadingRegistry
import com.jkdc.app.core.ReadingText

/** The Listen tab: a small library with two shelves. Listen = authored texts read
    aloud by the local voice, answered in writing with the transcript hidden until
    the end. Read = the reading skill's texts, visible while answering. Both
    finish with one completion mark carrying the score. */
@Composable
fun ListeningScreen(vm: AppViewModel) {
    var shelf by rememberSaveable { mutableStateOf("listen") }
    DisposableEffect(Unit) { onDispose { vm.stopListeningText() } }
    Column(Modifier.fillMaxSize()) {
        LibraryShelfPicker(shelf) { shelf = it }
        if (shelf == "read") ReadingLibrary(vm) else ListeningLibrary(vm)
    }
}

@Composable
private fun LibraryShelfPicker(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryChip(stringResource(R.string.library_listen), selected == "listen", Modifier.weight(1f)) { onSelect("listen") }
        LibraryChip(stringResource(R.string.library_read), selected == "read", Modifier.weight(1f)) { onSelect("read") }
    }
}

@Composable
private fun LibraryChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(role = Role.Tab, onClick = onClick)
        .testTag(if (selected) "library-shelf-selected" else "library-shelf"),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) AppColors.Secondary else AppColors.Surface.copy(alpha = .85f)) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = if (selected) AppColors.Cream else AppColors.Secondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth())
    }
}

@Composable
private fun ListeningLibrary(vm: AppViewModel) {
    val texts = ListeningRegistry.forLanguage(vm.language.id)
    val unitOrder = CourseRegistry.all.firstOrNull { it.languageID == vm.language.id }?.units ?: emptyList()
    var openTextID by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openTextID?.let { ListeningRegistry.text(it) }
    if (open != null) {
        ListeningDetail(vm, open, unitOrder, onBack = { openTextID = null; vm.stopListeningText() })
    } else {
        LazyColumn(Modifier.fillMaxSize().testTag("listening-screen").padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PageHeading(stringResource(R.string.listening_eyebrow), stringResource(R.string.listening_title),
                stringResource(R.string.listening_subtitle), Modifier.padding(top = 8.dp, bottom = 6.dp)) }
            texts.groupBy { it.level }.forEach { (level, items) ->
                item(key = "section-$level") {
                    Text(level, style = MaterialTheme.typography.titleSmall, color = AppColors.Secondary,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() }
                            .testTag("listening-section-" + level))
                }
                items(items.size, key = { items[it].id }) { index ->
                    val text = items[index]
                    val best = vm.listeningScore(text.id)
                    val medal = LevelEngine.listeningMedal(best)
                    Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { openTextID = text.id }.testTag("listening-" + text.id),
                        shape = RoundedCornerShape(26.dp), color = AppColors.Surface.copy(alpha = .85f)) {
                        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppIcon(AppSymbol.Headphones, Modifier.size(28.dp), color = AppColors.Secondary)
                            Column(Modifier.weight(1f)) {
                                Text(text.title, style = MaterialTheme.typography.titleMedium)
                                Text(listeningSubtitle(text, unitOrder),
                                    style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                            }
                            if (medal > 0) AppIcon(AppSymbol.Star, Modifier.size(16.dp), color = AppColors.Secondary, filled = true)
                            Text(if (best == null) stringResource(R.string.lesson_new)
                                else stringResource(R.string.listening_score, best),
                                style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingLibrary(vm: AppViewModel) {
    val texts = ReadingRegistry.forLanguage(vm.language.id)
    val unitOrder = CourseRegistry.all.firstOrNull { it.languageID == vm.language.id }?.units ?: emptyList()
    var openTextID by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openTextID?.let { ReadingRegistry.text(it) }
    if (open != null) {
        ReadingDetail(vm, open, unitOrder, onBack = { openTextID = null })
    } else {
        LazyColumn(Modifier.fillMaxSize().testTag("reading-screen").padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PageHeading(stringResource(R.string.reading_eyebrow), stringResource(R.string.reading_title),
                stringResource(R.string.reading_subtitle), Modifier.padding(top = 8.dp, bottom = 6.dp)) }
            texts.groupBy { it.level }.forEach { (level, items) ->
                item(key = "rsection-$level") {
                    Text(level, style = MaterialTheme.typography.titleSmall, color = AppColors.Secondary,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() }
                            .testTag("reading-section-" + level))
                }
                items(items.size, key = { items[it].id }) { index ->
                    val text = items[index]
                    val best = vm.readingScore(text.id)
                    Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { openTextID = text.id }.testTag("reading-" + text.id),
                        shape = RoundedCornerShape(26.dp), color = AppColors.Surface.copy(alpha = .85f)) {
                        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppIcon(AppSymbol.Words, Modifier.size(28.dp), color = AppColors.Secondary)
                            Column(Modifier.weight(1f)) {
                                Text(text.title, style = MaterialTheme.typography.titleMedium)
                                Text(readingSubtitle(text, unitOrder),
                                    style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                            }
                            Text(if (best == null) stringResource(R.string.lesson_new)
                                else stringResource(R.string.listening_score, best),
                                style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                        }
                    }
                }
            }
        }
    }
}

/** "Theme 3 · <unit title> · N questions" when the text belongs to a course
    unit; "A2 · <topic> · N questions" for topic-only extras. Shared by both
    shelves via their text/id accessors. */
@Composable
private fun shelfSubtitle(unitRank: Int?, unitTitle: String?, level: String, topic: String, count: Int): String =
    if (unitRank != null && unitTitle != null) stringResource(R.string.listening_theme, unitRank + 1, unitTitle, count)
    else stringResource(R.string.listening_topic, level, topic, count)

@Composable
fun listeningSubtitle(text: ListeningText, unitOrder: List<CourseUnit>): String =
    shelfSubtitle(text.unitID?.let { id -> unitOrder.indexOfFirst { it.id == id }.takeIf { rank -> rank >= 0 } },
        text.unitID?.let { id -> unitOrder.firstOrNull { it.id == id }?.title }, text.level, text.topic, text.questions.size)

@Composable
fun readingSubtitle(text: ReadingText, unitOrder: List<CourseUnit>): String =
    shelfSubtitle(text.unitID?.let { id -> unitOrder.indexOfFirst { it.id == id }.takeIf { rank -> rank >= 0 } },
        text.unitID?.let { id -> unitOrder.firstOrNull { it.id == id }?.title }, text.level, text.topic, text.questions.size)

@Composable
private fun ListeningDetail(vm: AppViewModel, item: ListeningText,
                            unitOrder: List<CourseUnit>, onBack: () -> Unit) {
    var phase by rememberSaveable { mutableStateOf(0) }
    var index by rememberSaveable { mutableStateOf(0) }
    var answer by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var verdict by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var correctCount by rememberSaveable { mutableStateOf(0) }
    var showTranscript by rememberSaveable { mutableStateOf(false) }
    val questions = item.questions
    val finished = phase == 1 && index >= questions.size && questions.isNotEmpty()
    LaunchedEffectOnce(finished, item.id, correctCount, questions.size) {
        vm.listeningCompleted(item.id, correctCount, questions.size)
    }

    Column(Modifier.fillMaxSize().testTag("listening-detail").padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftRoundButton(AppSymbol.Back, stringResource(R.string.common_back), onClick = onBack, diameter = 44.dp)
            Spacer(Modifier.weight(1f))
            Text(item.title, style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    Text(listeningSubtitle(item, unitOrder),
                        style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                }
            }
            if (phase == 0) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.listening_instruction), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { vm.playListeningText(item.text) }, enabled = !vm.listeningPlaying,
                            modifier = Modifier.fillMaxWidth().testTag("listening-play"),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                            Text(stringResource(if (vm.listeningPlaying) R.string.listening_playing else R.string.listening_play))
                        }
                        OutlinedButton(onClick = { vm.stopListeningText() }, enabled = vm.listeningPlaying,
                            modifier = Modifier.fillMaxWidth().testTag("listening-stop")) {
                            Text(stringResource(R.string.listening_stop))
                        }
                        Button(onClick = { phase = 1 }, modifier = Modifier.fillMaxWidth().testTag("listening-questions"),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Secondary, contentColor = AppColors.Cream)) {
                            Text(stringResource(R.string.listening_ready))
                        }
                    }
                }
            } else if (phase == 1 && questions.isNotEmpty()) {
                if (index >= questions.size) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(stringResource(R.string.exercise_summary, correctCount, questions.size),
                                style = MaterialTheme.typography.titleLarge)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.listening_show_transcript),
                                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Switch(checked = showTranscript, onCheckedChange = { showTranscript = it },
                                    modifier = Modifier.testTag("listening-transcript"))
                            }
                            if (showTranscript) Text(item.text, style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("listening-finish"),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                                Text(stringResource(R.string.exercise_done))
                            }
                        }
                    }
                } else {
                    // Same stale-provider guard as ExerciseScreen: resolve inside the item.
                    item {
                        Text(stringResource(R.string.exercise_counter, index + 1, questions.size),
                            style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                    }
                    item {
                        val question = questions.getOrNull(index) ?: return@item
                        ExerciseCard(question, answer, selected, verdict,
                            onAnswer = { answer = it.take(160) }, onSelect = { selected = it },
                            onCheck = {
                                val attempt = when (question.kind) {
                                    ExerciseEngine.CHOICE -> selected?.let { question.options.getOrNull(it) } ?: ""
                                    else -> answer
                                }
                                val correct = ExerciseEngine.isCorrect(question, attempt)
                                verdict = correct
                                if (correct) correctCount++
                            },
                            onNext = { index++; answer = ""; selected = null; verdict = null })
                    }
                }
            }
        }
    }
}

/** Reading keeps the text visible the whole time — comprehension, not memory. */
@Composable
private fun ReadingDetail(vm: AppViewModel, item: ReadingText,
                          unitOrder: List<CourseUnit>, onBack: () -> Unit) {
    var index by rememberSaveable { mutableStateOf(0) }
    var answer by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var verdict by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var correctCount by rememberSaveable { mutableStateOf(0) }
    val questions = item.questions
    val finished = index >= questions.size && questions.isNotEmpty()
    LaunchedEffectOnce(finished, item.id, correctCount, questions.size) {
        vm.readingCompleted(item.id, correctCount, questions.size)
    }

    Column(Modifier.fillMaxSize().testTag("reading-detail").padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftRoundButton(AppSymbol.Back, stringResource(R.string.common_back), onClick = onBack, diameter = 44.dp)
            Spacer(Modifier.weight(1f))
            Text(item.title, style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    Text(readingSubtitle(item, unitOrder),
                        style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                }
            }
            item {
                Text(item.text, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("reading-text"))
            }
            if (questions.isNotEmpty()) {
                if (index >= questions.size) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(stringResource(R.string.exercise_summary, correctCount, questions.size),
                                style = MaterialTheme.typography.titleLarge)
                            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("reading-finish"),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                                Text(stringResource(R.string.exercise_done))
                            }
                        }
                    }
                } else {
                    item {
                        Text(stringResource(R.string.exercise_counter, index + 1, questions.size),
                            style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                    }
                    item {
                        val question = questions.getOrNull(index) ?: return@item
                        ExerciseCard(question, answer, selected, verdict,
                            onAnswer = { answer = it.take(160) }, onSelect = { selected = it },
                            onCheck = {
                                val attempt = when (question.kind) {
                                    ExerciseEngine.CHOICE -> selected?.let { question.options.getOrNull(it) } ?: ""
                                    else -> answer
                                }
                                val correct = ExerciseEngine.isCorrect(question, attempt)
                                verdict = correct
                                if (correct) correctCount++
                            },
                            onNext = { index++; answer = ""; selected = null; verdict = null })
                    }
                }
            }
        }
    }
}
