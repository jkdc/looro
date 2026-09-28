package com.jkdc.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.jkdc.app.core.Exercise
import com.jkdc.app.core.ExerciseEngine
import com.jkdc.app.core.Lesson
import kotlin.random.Random

/** Authored offline exercises for a lesson: choice, written gap, word ordering and
    free written answer — all graded locally by ExerciseEngine, no AI involved.
    Finishing the set records one completion with the score. */
@Composable
fun ExerciseScreen(vm: AppViewModel, lesson: Lesson, onBack: () -> Unit) {
    val exercises = lesson.exercises
    var index by rememberSaveable { mutableStateOf(0) }
    var answer by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var verdict by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var correctCount by rememberSaveable { mutableStateOf(0) }
    val finished = index >= exercises.size

    Column(Modifier.fillMaxSize().testTag("exercise-screen").padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftRoundButton(AppSymbol.Back, stringResource(R.string.common_back), onClick = onBack, diameter = 44.dp)
            Spacer(Modifier.weight(1f))
            Text(lesson.title, style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
        }
        if (exercises.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(vertical = 60.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.lesson_exercises_heading), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LaunchedEffectOnce(finished, lesson.id, correctCount, exercises.size) {
                vm.exerciseSetCompleted(lesson.id, correctCount, exercises.size)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.lesson_exercises_heading), style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() })
                        if (!finished) Text(stringResource(R.string.exercise_counter, index + 1, exercises.size),
                            style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                    }
                }
                if (finished) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.exercise_summary, correctCount, exercises.size),
                                style = MaterialTheme.typography.titleLarge)
                            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("exercise-finish"),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                                Text(stringResource(R.string.exercise_done))
                            }
                        }
                    }
                } else {
                    val exercise = exercises[index]
                    item {
                        ExerciseCard(exercise, answer, selected, verdict,
                            onAnswer = { answer = it.take(120) }, onSelect = { selected = it },
                            onCheck = {
                                val attempt = when (exercise.kind) {
                                    ExerciseEngine.CHOICE -> selected?.let { exercise.options.getOrNull(it) } ?: ""
                                    else -> answer
                                }
                                val correct = ExerciseEngine.isCorrect(exercise, attempt)
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

@Composable
private fun ExerciseCard(exercise: Exercise, answer: String, selected: Int?, verdict: Boolean?,
                         onAnswer: (String) -> Unit, onSelect: (Int) -> Unit,
                         onCheck: () -> Unit, onNext: () -> Unit) {
    val tokens = remember(exercise.id) {
        if (exercise.kind == ExerciseEngine.ORDER) {
            exercise.answers.firstOrNull()?.split(" ")?.shuffled(Random(exercise.id.hashCode())) ?: emptyList()
        } else emptyList()
    }
    val chosen = remember(exercise.id) { mutableStateListOf<Int>() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(exercise.prompt, style = MaterialTheme.typography.titleMedium)
        if (verdict == null) {
            when (exercise.kind) {
                ExerciseEngine.CHOICE -> exercise.options.forEachIndexed { optionIndex, option ->
                    Surface(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { onSelect(optionIndex) }
                        .testTag("exercise-option"),
                        shape = RoundedCornerShape(18.dp),
                        color = if (selected == optionIndex) AppColors.Sage else AppColors.Cream) {
                        Text(option, Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                    }
                }
                ExerciseEngine.ORDER -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp), color = AppColors.Surface) {
                        Text(if (chosen.isEmpty()) " " else chosen.joinToString(" ") { tokens.getOrNull(it).orEmpty() },
                            Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tokens.forEachIndexed { tokenIndex, token ->
                            Surface(Modifier.clickable(enabled = tokenIndex !in chosen, role = androidx.compose.ui.semantics.Role.Button) {
                                    if (tokenIndex !in chosen) chosen.add(tokenIndex)
                                }.testTag("exercise-token"),
                                shape = RoundedCornerShape(14.dp), color = AppColors.Peach) {
                                Text(token, Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                    if (chosen.isNotEmpty()) AppTextButton(onClick = { chosen.clear() }) {
                        Text(stringResource(R.string.exercise_clear))
                    }
                }
                else -> AppTextField(answer, onAnswer, singleLine = exercise.kind == ExerciseEngine.GAP,
                    modifier = Modifier.fillMaxWidth().testTag("exercise-answer"),
                    label = { Text(stringResource(R.string.practice_your_answer)) })
            }
            Button(onClick = {
                if (exercise.kind == ExerciseEngine.ORDER) onAnswer(chosen.joinToString(" ") { tokens.getOrNull(it).orEmpty() })
                onCheck()
            }, enabled = when (exercise.kind) {
                ExerciseEngine.CHOICE -> selected != null
                ExerciseEngine.ORDER -> chosen.size == tokens.size && tokens.isNotEmpty()
                else -> answer.isNotBlank()
            }, modifier = Modifier.fillMaxWidth().testTag("exercise-check"),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                Text(stringResource(R.string.practice_check))
            }
        } else {
            Text(if (verdict == true) stringResource(R.string.recall_correct) else stringResource(R.string.exercise_wrong),
                style = MaterialTheme.typography.titleMedium, color = if (verdict == true) AppColors.Secondary else AppColors.Red)
            Text(exercise.explain, style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary)
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().testTag("exercise-next"),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                Text(stringResource(R.string.exercise_next))
            }
        }
    }
}

@Composable
private fun LaunchedEffectOnce(finished: Boolean, lessonID: String, correct: Int, total: Int, record: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(finished, lessonID) { if (finished && total > 0) record() }
}
