package com.jkdc.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jkdc.app.AppViewModel
import com.jkdc.app.R
import com.jkdc.app.core.ConversationTheme
import com.jkdc.app.core.Course
import com.jkdc.app.core.CourseProgress
import com.jkdc.app.core.CourseRegistry
import com.jkdc.app.core.CourseUnit
import com.jkdc.app.core.LanguageRegistry
import com.jkdc.app.core.Lesson
import com.jkdc.app.core.LevelStats

/** The Learn tab: courses for the current language, their lessons, and the
    study card with optional read-aloud. Practice flows through the same
    consent + microphone path as a normal conversation. */
@Composable
fun LearnScreen(vm: AppViewModel, onPractice: (String) -> Unit, onThemeChosen: () -> Unit) {
    val courses = CourseRegistry.forLanguage(vm.language.id)
    val progress = remember { vm.progressStats() to vm.completedLessonIDs() }
    var openLessonID by rememberSaveable { mutableStateOf<String?>(null) }
    var exercisesOpen by rememberSaveable { mutableStateOf(false) }
    var recallOpen by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { vm.stopLessonReading() } }
    val openLesson = openLessonID?.let { CourseRegistry.lesson(it) }
    if (recallOpen) {
        RecallScreen(vm, onBack = { recallOpen = false })
    } else if (openLesson != null && exercisesOpen) {
        ExerciseScreen(vm, openLesson, onBack = { exercisesOpen = false })
    } else if (openLesson != null) {
        LessonDetail(vm, openLesson,
            onBack = { openLessonID = null; vm.stopLessonReading() },
            onPractice = { id -> vm.stopLessonReading(); onPractice(id) },
            onTheme = { theme -> vm.stopLessonReading(); vm.chooseTheme(theme); onThemeChosen() },
            onExercises = { exercisesOpen = true })
    } else if (courses.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.learn_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.learn_empty_body), color = AppColors.Secondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().testTag("learn-screen").padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PageHeading(stringResource(R.string.learn_eyebrow), stringResource(R.string.learn_title),
                stringResource(R.string.learn_subtitle), Modifier.padding(top = 20.dp, bottom = 6.dp)) }
            item { LanguageSwitcher(vm) }
            if (vm.dueRecallCount() > 0) item {
                Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { recallOpen = true }.testTag("recall-card"),
                    shape = RoundedCornerShape(26.dp), color = AppColors.Sage) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(AppSymbol.Words, Modifier.size(28.dp), color = AppColors.Secondary)
                        Column {
                            Text(stringResource(R.string.learn_recall_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.learn_recall_card, vm.dueRecallCount()),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                        }
                    }
                }
            }
            items(courses.size, key = { courses[it].id }) { index ->
                CourseCard(vm, courses[index], progress.first.courses.firstOrNull { it.courseID == courses[index].id },
                    progress.second, onOpen = { id -> openLessonID = id; vm.stopLessonReading() })
            }
        }
    }
}

@Composable
private fun LanguageSwitcher(vm: AppViewModel) {
    val languages = LanguageRegistry.all
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(languages.size, key = { languages[it].id }) { index ->
            val module = languages[index]
            val selected = module.id == vm.language.id
            Surface(Modifier.clickable(role = Role.Button, enabled = !vm.isRunning) { vm.selectLanguage(module.id) }
                .testTag("language-" + module.id),
                shape = RoundedCornerShape(16.dp), color = if (selected) AppColors.Sage else AppColors.Surface.copy(alpha = .85f)) {
                Text(module.nativeName, Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) AppColors.Ink else AppColors.Secondary)
            }
        }
    }
}

@Composable
private fun CourseCard(vm: AppViewModel, course: Course, courseProgress: CourseProgress?,
                       doneLessonIDs: Set<String>, onOpen: (String) -> Unit) {
    val level = remember(course.id) { vm.levelStats(course) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = AppColors.Surface.copy(alpha = .85f)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(AppSymbol.Sparkles, Modifier.size(28.dp), color = AppColors.Secondary)
                Column {
                    Text(course.title, style = MaterialTheme.typography.titleMedium)
                    Text(course.subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                }
            }
            Text(course.description, style = MaterialTheme.typography.bodyMedium)
            courseProgress?.takeIf { it.lessonsTotal > 0 }?.let {
                Text(stringResource(R.string.learn_course_percent, it.percent),
                    style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
            }
            LevelCard(level)
            course.units.forEach { unit -> UnitSection(vm, unit, level, doneLessonIDs, onOpen) }
        }
    }
}

@Composable
private fun LevelCard(stats: LevelStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = AppColors.Sage, modifier = Modifier.size(46.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stats.level.toString(), style = MaterialTheme.typography.titleMedium)
                }
            }
            Column {
                Text(stringResource(R.string.learn_level_label, stats.level, levelTitle(stats.level)),
                    style = MaterialTheme.typography.titleMedium)
                Text(if (stats.pointsToNext == null) stringResource(R.string.learn_level_top)
                    else pluralStringResource(R.plurals.learn_level_next, stats.pointsToNext, stats.pointsToNext, stats.level + 1),
                    style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
            }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(AppColors.Cream, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(stats.progressInLevel.coerceIn(0.0, 1.0).toFloat())
                .height(6.dp).background(AppColors.Secondary, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun UnitSection(vm: AppViewModel, unit: CourseUnit, level: LevelStats,
                        doneLessonIDs: Set<String>, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(unit.title, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }.weight(1f))
            if (unit.lessons.isNotEmpty()) Text(stringResource(R.string.learn_course_percent,
                unit.lessons.count { it.id in doneLessonIDs } * 100 / unit.lessons.size),
                style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
        }
        unit.lessons.forEach { lesson ->
            val attempts = vm.lessonAttempts(lesson.id)
            val tier = level.mastery.firstOrNull { it.lessonID == lesson.id }?.tier ?: 0
            Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(lesson.id) }.testTag("lesson-" + lesson.id),
                shape = RoundedCornerShape(18.dp), color = AppColors.Cream) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(lesson.title, style = MaterialTheme.typography.titleSmall)
                        Text(lesson.goal, style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                    }
                    if (tier > 0) LessonStars(tier)
                    Text(if (attempts == 0) stringResource(R.string.lesson_new) else stringResource(R.string.lesson_attempts, attempts),
                        style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                }
            }
        }
    }
}

@Composable
private fun LessonStars(tier: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(end = 8.dp)) {
        repeat(3) { index ->
            AppIcon(AppSymbol.Star, Modifier.size(15.dp), color = if (index < tier) AppColors.Secondary
                else AppColors.Secondary.copy(alpha = .25f), filled = index < tier)
        }
    }
}

@Composable
private fun LessonDetail(vm: AppViewModel, lesson: Lesson, onBack: () -> Unit,
                         onPractice: (String) -> Unit, onTheme: (ConversationTheme) -> Unit,
                         onExercises: () -> Unit) {
    val unit = CourseRegistry.all.flatMap { it.units }.firstOrNull { u -> u.lessons.any { it.id == lesson.id } }
    LaunchedEffect(lesson.id, vm.lessonAudioOn) {
        // A live practice session is listening: reading the card aloud would feed
        // the microphone and loop the conversation.
        if (vm.lessonAudioOn && !vm.isRunning) vm.readLessonCard(lesson.title, *lesson.intro.explanation.toTypedArray(),
            *lesson.intro.examples.map { it.text }.toTypedArray())
    }
    Column(Modifier.fillMaxSize().testTag("lesson-screen").padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftRoundButton(AppSymbol.Back, stringResource(R.string.common_back), onClick = onBack, diameter = 44.dp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.lesson_read_aloud), style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
            Switch(checked = vm.lessonAudioOn, onCheckedChange = { vm.setLessonAudio(it) },
                modifier = Modifier.padding(start = 8.dp).testTag("lesson-audio"))
        }
        // Bottom padding clears the floating navigation so the practice CTA is reachable.
        LazyColumn(contentPadding = PaddingValues(bottom = 180.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(lesson.title, style = MaterialTheme.typography.headlineSmall)
                    Text(lesson.goal, style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary)
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
                            Text(example.meaning, style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
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
                            Text(expression.note, style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                        }
                    }
                }
            }
            item { DetailHeading(stringResource(R.string.lesson_situation_heading)) }
            item { Text(lesson.situation, style = MaterialTheme.typography.bodyLarge) }
            if (lesson.exercises.isNotEmpty()) {
                item { DetailHeading(stringResource(R.string.lesson_exercises_heading)) }
                item {
                    Button(onClick = onExercises, modifier = Modifier.fillMaxWidth().testTag("lesson-exercises"),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                        Text(stringResource(R.string.lesson_exercises_button))
                    }
                }
            }
            unit?.let { u ->
                item { DetailHeading(stringResource(R.string.lesson_themes_heading)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(u.themes.size) { index ->
                            val theme = u.themes[index]
                            Surface(Modifier.clickable(role = Role.Button) { onTheme(theme) },
                                shape = RoundedCornerShape(20.dp),
                                color = AppColors.Panels.getOrElse(theme.colorIndex) { AppColors.Peach }) {
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
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                    Text(stringResource(R.string.lesson_practice_button))
                }
            }
        }
    }
}

@Composable
private fun DetailHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = AppColors.Secondary,
        modifier = Modifier.semantics { heading() })
}
