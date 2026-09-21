package com.jkdc.looro.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.jkdc.looro.LooroViewModel
import com.jkdc.looro.R
import com.jkdc.looro.core.ConversationTheme
import com.jkdc.looro.core.Course
import com.jkdc.looro.core.CourseRegistry
import com.jkdc.looro.core.CourseUnit
import com.jkdc.looro.core.Lesson

/** The Learn tab: courses for the current language, their lessons, and the
    study card with optional read-aloud. Practice flows through the same
    consent + microphone path as a normal conversation. */
@Composable
fun LearnScreen(vm: LooroViewModel, onPractice: (String) -> Unit, onThemeChosen: () -> Unit) {
    val courses = CourseRegistry.forLanguage(vm.language.id)
    var openLessonID by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { vm.stopLessonReading() } }
    val openLesson = openLessonID?.let { CourseRegistry.lesson(it) }
    if (openLesson != null) {
        LessonDetail(vm, openLesson,
            onBack = { openLessonID = null; vm.stopLessonReading() },
            onPractice = { id -> vm.stopLessonReading(); onPractice(id) },
            onTheme = { theme -> vm.stopLessonReading(); vm.chooseTheme(theme); onThemeChosen() })
    } else if (courses.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.learn_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.learn_empty_body), color = LooroColors.Secondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().testTag("learn-screen").padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PageHeading(stringResource(R.string.learn_eyebrow), stringResource(R.string.learn_title),
                stringResource(R.string.learn_subtitle), Modifier.padding(top = 20.dp, bottom = 6.dp)) }
            items(courses.size, key = { courses[it].id }) { index ->
                CourseCard(vm, courses[index], onOpen = { id -> openLessonID = id; vm.stopLessonReading() })
            }
        }
    }
}

@Composable
private fun CourseCard(vm: LooroViewModel, course: Course, onOpen: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = LooroColors.Surface.copy(alpha = .85f)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LooroIcon(LooroSymbol.Sparkles, Modifier.size(28.dp), color = LooroColors.Secondary)
                Column {
                    Text(course.title, style = MaterialTheme.typography.titleMedium)
                    Text(course.subtitle, style = MaterialTheme.typography.bodySmall, color = LooroColors.Secondary)
                }
            }
            Text(course.description, style = MaterialTheme.typography.bodyMedium)
            course.units.forEach { unit -> UnitSection(vm, unit, onOpen) }
        }
    }
}

@Composable
private fun UnitSection(vm: LooroViewModel, unit: CourseUnit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(unit.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        unit.lessons.forEach { lesson ->
            val attempts = vm.lessonAttempts(lesson.id)
            Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(lesson.id) }.testTag("lesson-" + lesson.id),
                shape = RoundedCornerShape(18.dp), color = LooroColors.Cream) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(lesson.title, style = MaterialTheme.typography.titleSmall)
                        Text(lesson.goal, style = MaterialTheme.typography.bodySmall, color = LooroColors.Secondary)
                    }
                    Text(if (attempts == 0) stringResource(R.string.lesson_new) else stringResource(R.string.lesson_attempts, attempts),
                        style = MaterialTheme.typography.labelSmall, color = LooroColors.Secondary)
                }
            }
        }
    }
}

@Composable
private fun LessonDetail(vm: LooroViewModel, lesson: Lesson, onBack: () -> Unit,
                         onPractice: (String) -> Unit, onTheme: (ConversationTheme) -> Unit) {
    val unit = CourseRegistry.all.flatMap { it.units }.firstOrNull { u -> u.lessons.any { it.id == lesson.id } }
    LaunchedEffect(lesson.id, vm.lessonAudioOn) {
        if (vm.lessonAudioOn) vm.readLessonCard(lesson.title, *lesson.intro.explanation.toTypedArray(),
            *lesson.intro.examples.map { it.text }.toTypedArray())
    }
    Column(Modifier.fillMaxSize().testTag("lesson-screen").padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftRoundButton(LooroSymbol.Back, stringResource(R.string.common_back), onClick = onBack, diameter = 44.dp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.lesson_read_aloud), style = MaterialTheme.typography.labelSmall, color = LooroColors.Secondary)
            Switch(checked = vm.lessonAudioOn, onCheckedChange = { vm.setLessonAudio(it) },
                modifier = Modifier.padding(start = 8.dp).testTag("lesson-audio"))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(lesson.title, style = MaterialTheme.typography.headlineSmall)
                    Text(lesson.goal, style = MaterialTheme.typography.bodyMedium, color = LooroColors.Secondary)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    lesson.intro.explanation.forEach { paragraph ->
                        Text(paragraph, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            item { DetailHeading(stringResource(R.string.lesson_examples_heading)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lesson.intro.examples.forEach { example ->
                        Column {
                            Text(example.text, style = MaterialTheme.typography.titleSmall)
                            Text(example.meaning, style = MaterialTheme.typography.bodySmall, color = LooroColors.Secondary)
                        }
                    }
                }
            }
            item { DetailHeading(stringResource(R.string.lesson_expressions_heading)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lesson.expressions.forEach { expression ->
                        Column {
                            Text(expression.text + "  —  " + expression.meaning, style = MaterialTheme.typography.titleSmall)
                            Text(expression.note, style = MaterialTheme.typography.bodySmall, color = LooroColors.Secondary)
                        }
                    }
                }
            }
            item { DetailHeading(stringResource(R.string.lesson_situation_heading)) }
            item { Text(lesson.situation, style = MaterialTheme.typography.bodyLarge) }
            unit?.let { u ->
                item { DetailHeading(stringResource(R.string.lesson_themes_heading)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(u.themes.size) { index ->
                            val theme = u.themes[index]
                            Surface(Modifier.clickable(role = Role.Button) { onTheme(theme) },
                                shape = RoundedCornerShape(20.dp),
                                color = LooroColors.Panels.getOrElse(theme.colorIndex) { LooroColors.Peach }) {
                                Column(Modifier.padding(14.dp).testTag("course-theme-" + theme.id), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(theme.title, style = MaterialTheme.typography.titleSmall)
                                    Text(theme.subtitle, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Button(onClick = { onPractice(lesson.id) }, modifier = Modifier.fillMaxWidth().testTag("lesson-practice"),
                    colors = ButtonDefaults.buttonColors(containerColor = LooroColors.Orange, contentColor = LooroColors.Ink)) {
                    Text(stringResource(R.string.lesson_practice_button))
                }
            }
        }
    }
}

@Composable
private fun DetailHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = LooroColors.Secondary,
        modifier = Modifier.semantics { heading() })
}
