package com.jkdc.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jkdc.app.R
import com.jkdc.app.ScenarioReviewState

/** Post-session review of a situation practice: verified errors ("you said →
    better"), strengths, savable key phrases and a focus for the next attempt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioReviewSheet(
    state: ScenarioReviewState,
    onDismiss: () -> Unit,
    onPractiseAgain: () -> Unit,
    onSavePhrase: (phrase: String, meaning: String) -> Boolean,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxHeight(.92f).testTag("scenario-review")) {
            item { SettingsSheetHeader(stringResource(R.string.review_title), onDismiss) }
            when (state) {
                is ScenarioReviewState.Preparing -> item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                        Text(stringResource(R.string.review_preparing), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.review_preparing_note), style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary)
                    }
                }
                is ScenarioReviewState.Failed -> item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.review_failed_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.review_failed_body), color = AppColors.Secondary)
                    }
                }
                is ScenarioReviewState.Ready -> {
                    val review = state.review
                    item {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.review_scenario_label, state.scenarioTitle),
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            if (review.retryFocus.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Surface(shape = RoundedCornerShape(20.dp), color = AppColors.Peach) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(R.string.review_retry_title), style = MaterialTheme.typography.labelLarge)
                                        Text(review.retryFocus, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    if (review.strengths.isNotEmpty()) item {
                        ReviewSection(stringResource(R.string.review_strengths_title)) {
                            review.strengths.forEach { strength -> ReviewLine("✓", strength, AppColors.Sage) }
                        }
                    }
                    if (review.errors.isNotEmpty()) item {
                        ReviewSection(stringResource(R.string.review_errors_title)) {
                            review.errors.forEach { error ->
                                Surface(Modifier.padding(vertical = 6.dp), shape = RoundedCornerShape(18.dp), color = AppColors.Surface) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        ReviewLine("✗", error.quote, AppColors.Red, label = stringResource(R.string.review_you_said))
                                        ReviewLine("→", error.correction, AppColors.Sage, label = stringResource(R.string.review_better))
                                        if (error.note.isNotBlank()) Text(error.note, style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                                    }
                                }
                            }
                        }
                    }
                    if (review.keyPhrases.isNotEmpty()) item {
                        var saved by remember { mutableStateOf(setOf<String>()) }
                        ReviewSection(stringResource(R.string.review_key_phrases_title)) {
                            review.keyPhrases.forEach { phrase ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(phrase.phrase, style = MaterialTheme.typography.titleMedium)
                                        Text(phrase.meaning, style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                                    }
                                    val key = phrase.phrase
                                    Surface(modifier = Modifier.clickable(enabled = key !in saved) {
                                        if (onSavePhrase(phrase.phrase, phrase.meaning)) saved = saved + key
                                    }, shape = RoundedCornerShape(14.dp), color = AppColors.Sage) {
                                        Text(
                                            stringResource(if (key in saved) R.string.review_phrase_saved else R.string.review_phrase_save),
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onPractiseAgain, modifier = Modifier.fillMaxWidth().testTag("scenario-review-again"),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                                Text(stringResource(R.string.review_practise_again))
                            }
                            AppTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.review_done))
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun ReviewLine(marker: String, text: String, tint: androidx.compose.ui.graphics.Color, label: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(marker, color = tint, style = MaterialTheme.typography.titleMedium)
        Column {
            if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
