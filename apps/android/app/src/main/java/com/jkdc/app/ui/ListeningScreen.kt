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
import com.jkdc.app.core.ExerciseEngine
import com.jkdc.app.core.LevelEngine
import com.jkdc.app.core.ListeningRegistry
import com.jkdc.app.core.ListeningText

/** The Listening tab: authored texts read aloud by the local voice, answered in
    writing. The transcript stays hidden while answering and is revealed at the
    end; finishing records one completion with the score. */
@Composable
fun ListeningScreen(vm: AppViewModel) {
    val texts = ListeningRegistry.forLanguage(vm.language.id)
    var openTextID by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { vm.stopListeningText() } }
    val open = openTextID?.let { ListeningRegistry.text(it) }
    if (open != null) {
        ListeningDetail(vm, open, onBack = { openTextID = null; vm.stopListeningText() })
    } else {
        LazyColumn(Modifier.fillMaxSize().testTag("listening-screen").padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PageHeading(stringResource(R.string.listening_eyebrow), stringResource(R.string.listening_title),
                stringResource(R.string.listening_subtitle), Modifier.padding(top = 20.dp, bottom = 6.dp)) }
            items(texts.size, key = { texts[it].id }) { index ->
                val text = texts[index]
                val best = vm.listeningScore(text.id)
                val medal = LevelEngine.listeningMedal(best)
                Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { openTextID = text.id }.testTag("listening-" + text.id),
                    shape = RoundedCornerShape(26.dp), color = AppColors.Surface.copy(alpha = .85f)) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(AppSymbol.Headphones, Modifier.size(28.dp), color = AppColors.Secondary)
                        Column(Modifier.weight(1f)) {
                            Text(text.title, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.listening_level, text.level, text.questions.size),
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

@Composable
private fun ListeningDetail(vm: AppViewModel, item: ListeningText, onBack: () -> Unit) {
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
                    Text(stringResource(R.string.listening_level, item.level, questions.size),
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
