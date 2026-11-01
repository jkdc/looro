package com.jkdc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jkdc.app.AppViewModel
import com.jkdc.app.R
import com.jkdc.app.core.Checkpoint
import com.jkdc.app.core.CheckpointEngine
import com.jkdc.app.core.ExerciseEngine

/** A level checkpoint: one authored item bank, answered in order with immediate
    feedback; the summary applies the overall threshold and every category floor.
    Passing records one `assessment` mark — a preparation signal, never a
    proficiency claim. */
@Composable
fun CheckpointScreen(vm: AppViewModel, checkpoint: Checkpoint, onBack: () -> Unit) {
    val questions = checkpoint.questions
    var index by rememberSaveable { mutableStateOf(0) }
    var answer by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var verdict by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var answers by rememberSaveable { mutableStateOf(listOf<String>()) }
    var recorded by rememberSaveable { mutableStateOf(false) }
    val finished = index >= questions.size && questions.isNotEmpty()
    val result = if (finished) CheckpointEngine.grade(checkpoint, answers) else null
    androidx.compose.runtime.LaunchedEffect(finished, checkpoint.id) {
        if (finished && !recorded) {
            recorded = true
            vm.checkpointCompleted(checkpoint.id, result!!.overall)
        }
    }

    Column(Modifier.fillMaxSize().testTag("checkpoint-screen").padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftRoundButton(AppSymbol.Back, stringResource(R.string.common_back), onClick = onBack, diameter = 44.dp)
            Spacer(Modifier.weight(1f))
            Text(checkpoint.title, style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(checkpoint.title, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.checkpoint_subtitle),
                        style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                }
            }
            if (finished) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(if (result!!.passed) R.string.checkpoint_passed else R.string.checkpoint_not_passed),
                            style = MaterialTheme.typography.titleLarge,
                            color = if (result.passed) AppColors.Secondary else AppColors.Ink)
                        Text(stringResource(R.string.checkpoint_overall, result.overall),
                            style = MaterialTheme.typography.bodyLarge)
                        result.categories.forEach { (category, score) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(categoryLabelFor(category)), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.progress_skills_percent, score),
                                    style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary)
                            }
                        }
                        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("checkpoint-finish"),
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
                    ExerciseCard(question.exercise, answer, selected, verdict,
                        onAnswer = { answer = it.take(160) }, onSelect = { selected = it },
                        onCheck = {
                            val attempt = when (question.exercise.kind) {
                                ExerciseEngine.CHOICE -> selected?.let { question.exercise.options.getOrNull(it) } ?: ""
                                else -> answer
                            }
                            val correct = ExerciseEngine.isCorrect(question.exercise, attempt)
                            answers = answers + attempt
                            verdict = correct
                        },
                        onNext = { index++; answer = ""; selected = null; verdict = null })
                }
            }
        }
    }
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
