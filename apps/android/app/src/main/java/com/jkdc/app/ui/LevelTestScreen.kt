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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
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
import com.jkdc.app.AppViewModel
import com.jkdc.app.R
import com.jkdc.app.core.ExerciseEngine
import com.jkdc.app.core.LevelTestEngine
import com.jkdc.app.core.LevelTestItem
import com.jkdc.app.core.LevelTestResult
import com.jkdc.app.core.LevelTestSection
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** The weekly level-verification test (level-tests): exam rules — no feedback
    while answering, one scrollable page, submission grades everything with
    the level's own thresholds, then a full review and a directional result
    (pass → ready for the next band, with a jump that never fixes; fail →
    weak sections and the units to reinforce). The attempt is always recorded
    and always visible on the level's card. */
@Composable
@OptIn(kotlinx.coroutines.FlowPreview::class)
fun LevelTestScreen(
    vm: AppViewModel,
    level: String,
    resumeAttemptID: String? = null,
    onBack: () -> Unit,
    onApplyBand: (String) -> Unit,
) {
    val currentSelection = remember(vm, level) { vm.levelTestSelection(level) }
    var recoveredAttempt by remember(resumeAttemptID) {
        mutableStateOf<com.jkdc.app.data.LevelTestAttemptStore.Restored?>(null)
    }
    val selection = recoveredAttempt?.selection ?: currentSelection ?: run { onBack(); return }
    val testLevel = selection.level
    val exam = selection.exam
    val attemptKey = selection.examID ?: selection.contentID ?: "${selection.level.languageID}-${selection.level.level}-${exam.weekIndex}"
    val screenKey = resumeAttemptID ?: attemptKey
    var attemptID by rememberSaveable(screenKey) {
        mutableStateOf(resumeAttemptID ?: UUID.randomUUID().toString())
    }
    var attempts by rememberSaveable(screenKey) { mutableStateOf(listOf<String>()) }
    var submitted by rememberSaveable(screenKey) { mutableStateOf(false) }
    var result by remember(screenKey) { mutableStateOf<LevelTestResult?>(null) }
    var pendingResult by remember(screenKey) { mutableStateOf<LevelTestResult?>(null) }
    var persistedAttempt by remember(screenKey) { mutableStateOf<com.jkdc.app.data.LevelTestAttemptStore.Restored?>(null) }
    var startFailed by remember(screenKey) { mutableStateOf(false) }
    var retryStart by remember { mutableStateOf(0) }
    var savingResult by remember { mutableStateOf(false) }
    var submissionFailed by remember { mutableStateOf(false) }
    val savingQuestions = remember { mutableStateMapOf<String, Int>() }
    val failedQuestions = remember { mutableStateMapOf<String, Boolean>() }
    val saveVersions = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val questionListPositions = remember(exam) {
        var listPosition = 1 // The exam heading is the first LazyColumn item.
        var previousSection: LevelTestSection? = null
        exam.items.mapIndexed { index, item ->
            if (item.section != previousSection) { listPosition++; previousSection = item.section }
            val previous = exam.items.getOrNull(index - 1)
            if (item.passageId != null && previous?.passageId != item.passageId) listPosition++
            listPosition++
            listPosition - 1
        }
    }

    val context = LocalContext.current
    val localized = remember(vm.instructionsLanguage) {
        val locale = when (vm.instructionsLanguage) { "en" -> java.util.Locale("en"); "es" -> java.util.Locale("es"); else -> null }
        locale ?: return@remember null
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        context.createConfigurationContext(configuration)
    }
    val strings = Strings(fallback = context, localized = localized)

    fun persistQuestion(questionIndex: Int, response: String, position: Int = questionListPositions[questionIndex]) {
        val item = exam.items[questionIndex]
        val version = (saveVersions[item.id] ?: 0) + 1
        saveVersions[item.id] = version
        savingQuestions[item.id] = version
        failedQuestions.remove(item.id)
        scope.launch {
            try {
                vm.saveLevelTestAnswer(attemptID, item.id, response, position)
                if (saveVersions[item.id] == version) { savingQuestions.remove(item.id); failedQuestions.remove(item.id) }
            } catch (_: Exception) {
                if (saveVersions[item.id] == version) { savingQuestions.remove(item.id); failedQuestions[item.id] = true }
            }
        }
    }

    fun retryFailedAnswers() {
        failedQuestions.keys.toList().forEach { questionID ->
            val index = exam.items.indexOfFirst { it.id == questionID }
            if (index >= 0) persistQuestion(index, attempts.getOrElse(index) { "" })
        }
    }

    DisposableEffect(Unit) { onDispose { vm.stopLessonReading() } }
    LaunchedEffect(attemptKey, retryStart, resumeAttemptID) {
        startFailed = false
        try {
            val restored = if (resumeAttemptID != null) {
                requireNotNull(vm.loadLevelTestAttempt(resumeAttemptID)) { "Exam attempt was not found" }
            } else vm.startLevelTestAttempt(selection, attemptID)
            recoveredAttempt = restored.takeIf { resumeAttemptID != null }
            attemptID = restored.attemptID
            persistedAttempt = restored
            attempts = restored.selection.exam.items.map { restored.responses[it.id].orEmpty() }
            result = restored.result
            submitted = restored.result != null
        } catch (_: Exception) {
            startFailed = true
        }
    }
    LaunchedEffect(exam.items.firstOrNull()?.id) {
        // The first listening stimulus auto-plays when its section is reached;
        // handled per passage below via ids.
    }

    LaunchedEffect(persistedAttempt?.attemptID, questionListPositions) {
        val restored = persistedAttempt ?: return@LaunchedEffect
        if (restored.result != null) return@LaunchedEffect
        val initialPosition = restored.position.coerceIn(0, (questionListPositions.lastOrNull() ?: 0))
        listState.scrollToItem(initialPosition)
        snapshotFlow { listState.firstVisibleItemIndex }
            .drop(1).distinctUntilChanged().debounce(300).collect { visiblePosition ->
                val questionIndex = questionListPositions.indexOfLast { it <= visiblePosition }.coerceAtLeast(0)
                persistQuestion(questionIndex, attempts.getOrElse(questionIndex) { "" }, visiblePosition)
            }
    }

    fun submit() {
        if (submitted || savingResult) return
        savingResult = true
        submissionFailed = false
        val final = pendingResult ?: LevelTestEngine.gradeByAttempts(testLevel, exam, attempts)
            .copy(at = System.currentTimeMillis()).also { pendingResult = it }
        val finalResponses = exam.items.mapIndexed { index, item -> item.id to attempts.getOrElse(index) { "" } }.toMap()
        val finalPosition = listState.firstVisibleItemIndex
        scope.launch {
            try {
                vm.completeLevelTestAttempt(attemptID, selection, finalResponses, finalPosition, final)
                persistedAttempt = vm.loadLevelTestAttempt(attemptID)
                result = final
                submitted = true
            } catch (_: Exception) {
                submissionFailed = true
            } finally {
                savingResult = false
            }
        }
    }

    if (persistedAttempt == null) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.t(if (startFailed) R.string.leveltest_attempt_start_failed else R.string.leveltest_attempt_starting),
                style = MaterialTheme.typography.bodyMedium)
            if (startFailed) Button(onClick = { retryStart++ }) { Text(strings.t(R.string.leveltest_retry)) }
        }
        return
    }

    Column(Modifier.fillMaxWidth().testTag("leveltest-screen").padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftRoundButton(AppSymbol.Back, strings.t(R.string.common_back), onClick = onBack, diameter = 44.dp)
            Spacer(Modifier.weight(1f))
            Text(strings.t(R.string.leveltest_title), style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(strings.t(R.string.leveltest_title), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    Text(strings.t(R.string.leveltest_subtitle, level), style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Secondary)
                }
            }
            if (!submitted) {
                var lastSection: LevelTestSection? = null
                val ordered = exam.items.sortedBy { exam.items.indexOf(it) }
                exam.items.forEachIndexed { index, item ->
                    if (item.section != lastSection) {
                        lastSection = item.section
                        item(key = "header-" + item.section.name) {
                            Text(sectionLabel(item.section, strings), style = MaterialTheme.typography.titleSmall,
                                color = AppColors.Secondary, modifier = Modifier
                                    .padding(top = 8.dp)
                                    .semantics { heading() })
                        }
                    }
                    // Reading passages show their text; listening passages play it.
                    val previous = exam.items.getOrNull(index - 1)
                    if (item.passageId != null && previous?.passageId != item.passageId) {
                        val passage = exam.passageById[item.passageId]
                        if (item.section == LevelTestSection.READING && passage != null) {
                            item(key = "passage-" + passage.id) {
                                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = AppColors.Cream) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (passage.title.isNotBlank()) Text(passage.title, style = MaterialTheme.typography.titleSmall)
                                        Text(passage.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        if (item.section == LevelTestSection.LISTENING && passage != null) {
                            item(key = "listen-" + passage.id) {
                                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = AppColors.Lilac) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(strings.t(R.string.leveltest_listen_prompt), style = MaterialTheme.typography.titleSmall)
                                            Text(strings.t(R.string.leveltest_listen_hint), style = MaterialTheme.typography.bodySmall,
                                                color = AppColors.Secondary)
                                        }
                                        OutlinedButton(onClick = { vm.speakPlacement(passage.text) },
                                            modifier = Modifier.testTag("leveltest-replay-" + passage.id)) {
                                            Text(strings.t(R.string.leveltest_listen_play))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = item.id) {
                        LevelTestQuestion(item = item, attempt = attempts.getOrElse(index) { "" },
                            localized = localized, enabled = !savingResult && pendingResult == null, onAttempt = { value ->
                                attempts = attempts.toMutableList().also { list ->
                                    while (list.size <= index) list.add("")
                                    list[index] = value
                                }
                                persistQuestion(index, value)
                            })
                        if (attempts.getOrElse(index) { "" }.isNotBlank()) {
                            val saveLabel = when {
                                savingQuestions.containsKey(item.id) -> R.string.leveltest_attempt_saving_answer
                                failedQuestions.containsKey(item.id) -> R.string.leveltest_attempt_save_failed
                                else -> R.string.leveltest_attempt_answer_saved
                            }
                            Text(strings.t(saveLabel), style = MaterialTheme.typography.labelSmall,
                                color = if (failedQuestions.containsKey(item.id)) MaterialTheme.colorScheme.error else AppColors.Secondary,
                                modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                item {
                    Button(onClick = ::submit, enabled = !savingResult &&
                        (pendingResult != null || attempts.count { it.isNotBlank() } == exam.items.size),
                        modifier = Modifier.fillMaxWidth().testTag("leveltest-submit"),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                        Text(strings.t(when {
                            savingResult -> R.string.leveltest_saving_result
                            submissionFailed -> R.string.leveltest_retry
                            else -> R.string.leveltest_submit
                        }))
                    }
                    if (submissionFailed) Text(strings.t(R.string.leveltest_attempt_save_failed),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (failedQuestions.isNotEmpty()) {
                        Text(strings.t(R.string.leveltest_attempt_save_failed),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = ::retryFailedAnswers) { Text(strings.t(R.string.leveltest_retry)) }
                    }
                    Text(strings.t(R.string.leveltest_submit_hint), style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Secondary, modifier = Modifier.padding(top = 6.dp))
                }
            } else {
                val final = result
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("leveltest-result")) {
                        Text(strings.t(if (final?.passed == true) R.string.leveltest_passed else R.string.leveltest_not_passed,
                            level), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(strings.t(R.string.leveltest_overall, final?.overall ?: 0), style = MaterialTheme.typography.bodyLarge)
                        final?.sections?.forEach { (section, score) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(sectionLabel(section.replaceFirstChar { it.uppercase() }, strings),
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.progress_skills_percent, score),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (score >= (testLevel.thresholds[section] ?: 60)) AppColors.Secondary else AppColors.Ink)
                            }
                        }
                        if (final?.weakUnitIDs?.isNotEmpty() == true) {
                            Text(strings.t(R.string.leveltest_reinforce), style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 6.dp))
                            final.weakUnitIDs.forEach { unitID ->
                                val unitTitle = com.jkdc.app.core.CourseRegistry.all
                                    .firstOrNull { course -> course.units.any { it.id == unitID } }
                                    ?.units?.firstOrNull { it.id == unitID }?.title ?: unitID
                                Text("· $unitTitle", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(strings.t(R.string.leveltest_estimate), style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Secondary)
                    }
                }
                item(key = "leveltest-review-title") {
                    Text(strings.t(R.string.leveltest_review_title), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                }
                val reviewsByQuestion = persistedAttempt?.questionReviews.orEmpty().associateBy { it.questionID }
                exam.items.forEachIndexed { index, question ->
                    item(key = "leveltest-review-${question.id}") {
                        val review = reviewsByQuestion[question.id]
                        val passage = question.passageId?.let(exam.passageById::get)
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.fillMaxWidth().testTag("leveltest-review-${question.id}")) {
                            Text("${index + 1}. ${question.exercise.prompt}", style = MaterialTheme.typography.bodyLarge)
                            passage?.let { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                            if (review == null) {
                                Text(strings.t(R.string.leveltest_review_summary_only), style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.Secondary)
                            } else {
                                Text(strings.t(R.string.leveltest_review_your_answer, review.response.ifBlank { strings.t(R.string.leveltest_review_blank) }),
                                    style = MaterialTheme.typography.bodySmall)
                                Text(strings.t(R.string.leveltest_review_solution, review.acceptedAnswers.joinToString(" / ")),
                                    style = MaterialTheme.typography.bodySmall)
                                if (review.explanation.isNotBlank()) Text(review.explanation, style = MaterialTheme.typography.bodySmall)
                                Text(strings.t(if (review.correct) R.string.leveltest_review_correct else R.string.leveltest_review_incorrect),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (review.correct) AppColors.Secondary else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (final?.passed == true && final.nextBand != null) {
                            Button(onClick = { onApplyBand(final.nextBand!!); onBack() },
                                modifier = Modifier.fillMaxWidth().testTag("leveltest-next"),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                                Text(strings.t(R.string.leveltest_next, final.nextBand!!))
                            }
                        }
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("leveltest-done")) {
                            Text(strings.t(R.string.leveltest_done))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelTestQuestion(item: LevelTestItem, attempt: String, localized: android.content.Context?, enabled: Boolean,
                              onAttempt: (String) -> Unit) {
    val context = LocalContext.current
    fun text(resId: Int): String = localized?.getString(resId) ?: context.getString(resId)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("leveltest-q-" + item.id)) {
        Text(item.exercise.prompt, style = MaterialTheme.typography.bodyLarge)
        if (item.exercise.kind == ExerciseEngine.CHOICE) {
            item.exercise.options.forEach { option ->
                val chosen = option == attempt
                Surface(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { onAttempt(option) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (chosen) AppColors.Secondary else AppColors.Cream) {
                    Text(option, Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (chosen) AppColors.Cream else AppColors.Ink)
                }
            }
        } else {
            BasicTextField(value = attempt, onValueChange = onAttempt, enabled = enabled, minLines = 1, maxLines = 2,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.Ink),
                cursorBrush = SolidColor(AppColors.Secondary),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("leveltest-attempt"))
        }
    }
}

private class Strings(private val fallback: android.content.Context, private val localized: android.content.Context?) {
    fun t(resId: Int): String = localized?.getString(resId) ?: fallback.getString(resId)
    fun t(resId: Int, argument: Any?): String = localized?.getString(resId, argument) ?: fallback.getString(resId, argument)
}

private fun sectionLabel(section: LevelTestSection, strings: Strings): String = when (section) {
    LevelTestSection.RECOGNITION -> strings.t(R.string.leveltest_section_recognition)
    LevelTestSection.READING -> strings.t(R.string.leveltest_section_reading)
    LevelTestSection.LISTENING -> strings.t(R.string.leveltest_section_listening)
    LevelTestSection.WRITING -> strings.t(R.string.leveltest_section_writing)
}

private fun sectionLabel(section: String, strings: Strings): String = when (section.lowercase()) {
    "recognition" -> strings.t(R.string.leveltest_section_recognition)
    "reading" -> strings.t(R.string.leveltest_section_reading)
    "listening" -> strings.t(R.string.leveltest_section_listening)
    "writing" -> strings.t(R.string.leveltest_section_writing)
    else -> section
}
