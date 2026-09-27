package com.jkdc.app.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.jkdc.app.core.ExpressionState
import com.jkdc.app.core.RecallEngine

/** The recall session: typed typed-recall v1 over the due expression queue.
    Grading is self-evident — the answer is compared to the saved expression
    text (case/whitespace-insensitive) — and schedules the ladder. */
@Composable
fun RecallScreen(vm: AppViewModel, onBack: () -> Unit) {
    var queue by remember { mutableStateOf(vm.recallQueue()) }
    var index by rememberSaveable { mutableStateOf(0) }
    var answer by rememberSaveable { mutableStateOf("") }
    var correct by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var correctCount by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().testTag("recall-screen").padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftRoundButton(AppSymbol.Back, stringResource(R.string.common_back), onClick = onBack, diameter = 44.dp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.recall_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
        }
        if (index >= queue.size) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.recall_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.recall_empty_body), color = AppColors.Secondary,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            val item = queue[index]
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 140.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    Text(stringResource(R.string.recall_counter, index + 1, queue.size),
                        style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.recall_prompt_label), style = MaterialTheme.typography.titleSmall,
                            color = AppColors.Secondary)
                        Text(item.meaning, style = MaterialTheme.typography.headlineSmall)
                    }
                }
                item {
                    OutlinedTextField(value = answer, onValueChange = { answer = it }, singleLine = true,
                        label = { Text(stringResource(R.string.recall_answer_label)) },
                        modifier = Modifier.fillMaxWidth().testTag("recall-answer"))
                }
                item {
                    when (correct) {
                        null -> Button(onClick = {
                                val ok = RecallEngine.normalizeKey(answer) == RecallEngine.normalizeKey(item.text)
                                vm.gradeRecall(item.text, ok)
                                correct = ok
                                if (ok) correctCount += 1
                            }, enabled = answer.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("recall-check"),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                            Text(stringResource(R.string.recall_check))
                        }
                        true -> RecallVerdict(stringResource(R.string.recall_correct), item,
                            onContinue = { index += 1; finishRound(correct, index, queue.size, correctCount, vm) })
                        false -> RecallVerdict(stringResource(R.string.recall_answer_was, item.text), item,
                            onContinue = { index += 1; finishRound(correct, index, queue.size, correctCount, vm) })
                    }
                }
            }
        }
    }
}

/** Reports the finished round once; the empty state itself must stay event-free. */
private fun finishRound(correct: Boolean?, index: Int, total: Int, correctCount: Int, vm: AppViewModel) {
    if (correct == null || index < total) return
    vm.practiceRoundCompleted(correctCount, total)
}

@Composable
private fun RecallVerdict(message: String, item: ExpressionState, onContinue: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("recall-verdict")) {
        Surface(shape = RoundedCornerShape(18.dp), color = AppColors.Sage) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(message, style = MaterialTheme.typography.titleSmall)
                Text(item.meaning, style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
            }
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag("recall-continue"),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
            Text(stringResource(R.string.recall_continue))
        }
    }
}
