package com.jkdc.app.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.jkdc.app.core.PronunciationEngine
import com.jkdc.app.core.Course
import com.jkdc.app.core.CourseRegistry
import com.jkdc.app.core.CourseUnit
import com.jkdc.app.core.DailyPlanEngine
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
    var openCheckpointID by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { vm.stopLessonReading() } }
    val openLesson = openLessonID?.let { CourseRegistry.lesson(it) }
    val openCheckpoint = openCheckpointID?.let { vm.checkpoint(it) }
    if (recallOpen) {
        RecallScreen(vm, onBack = { recallOpen = false })
    } else if (openCheckpoint != null) {
        CheckpointScreen(vm, openCheckpoint, onBack = { openCheckpointID = null })
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
            LanguageSwitcher(vm)
            Text(stringResource(R.string.learn_empty_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp))
            Text(stringResource(R.string.learn_empty_body), color = AppColors.Secondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    } else {
        // ---- CEFR level context, resolved before the list DSL (its lambda is
        // not a composable scope). Default: the placement suggestion when one
        // exists (suggest, never fix), else the recommended lesson's level,
        // else the first level with anything left to do. ----
        var placementOpen by rememberSaveable { mutableStateOf(false) }
        var levelTestLevel by rememberSaveable { mutableStateOf<String?>(null) }
        // Re-read on return from the test: a fresh result must replace the card text.
        val placementSuggestion = remember(placementOpen) { vm.placementSuggestion() }
        val recommended = vm.recommendedLesson()
        val recommendedUnit = courses.asSequence().flatMap { it.units }.firstOrNull { unit -> unit.lessons.any { it.id == recommended?.id } }
        val levels = remember(courses) { courses.asSequence().flatMap { it.units }.map { it.level }.distinct().sortedBy(::cefrRank).toList() }
        val defaultLevel = remember(levels, recommendedUnit, progress.second, placementSuggestion) {
            placementSuggestion?.band
                ?: recommendedUnit?.level
                ?: levels.firstOrNull { level -> courses.asSequence().flatMap { it.units }.filter { it.level == level }
                    .any { unit -> unit.lessons.any { it.id !in progress.second } } }
                ?: levels.firstOrNull()
        }
        var savedLevel by rememberSaveable { mutableStateOf<String?>(null) }
        val selectedLevel = savedLevel?.takeIf { it in levels } ?: defaultLevel ?: levels.firstOrNull() ?: "A1"
        val completedLevels = remember(levels, progress.second) {
            levels.filter { level -> courses.asSequence().flatMap { it.units }.filter { it.level == level }
                .flatMap { it.lessons }.let { lessons -> lessons.any() && lessons.all { it.id in progress.second } } }
        }
        if (placementOpen) {
            PlacementScreen(vm, onBack = { placementOpen = false },
                onApplyBand = { band -> savedLevel = band; placementOpen = false })
        } else if (levelTestLevel != null) {
            LevelTestScreen(vm, level = levelTestLevel!!, onBack = { levelTestLevel = null },
                onApplyBand = { band -> savedLevel = band; levelTestLevel = null })
        } else LazyColumn(Modifier.fillMaxSize().testTag("learn-screen").padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PageHeading(stringResource(R.string.learn_eyebrow), stringResource(R.string.learn_title),
                stringResource(R.string.learn_subtitle), Modifier.padding(top = 20.dp, bottom = 6.dp)) }
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
            vm.checkpointReady()?.let { checkpoint ->
                // A ready checkpoint is the most time-sensitive item on the page:
                // it rides directly under the heading, never below the catalog.
                item(key = checkpoint.id) {
                    Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { openCheckpointID = checkpoint.id }
                        .testTag("checkpoint-card"), shape = RoundedCornerShape(26.dp), color = AppColors.Butter) {
                        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppIcon(AppSymbol.Sparkles, Modifier.size(28.dp), color = AppColors.Secondary)
                            Column(Modifier.weight(1f)) {
                                Text(checkpoint.title, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.learn_checkpoint_ready),
                                    style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                            }
                        }
                    }
                }
            }
            item(key = "daily-plan") { DailyPlanCard(vm, onRecall = { recallOpen = true }, onLesson = { id -> openLessonID = id; vm.stopLessonReading() }) }
            if (vm.placementBank != null) item(key = "placement-entry") {
                // Free, optional level test: suggests the starting level, never gates.
                Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { placementOpen = true }
                    .testTag("placement-card"), shape = RoundedCornerShape(26.dp), color = AppColors.Surface.copy(alpha = .85f)) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(AppSymbol.Search, Modifier.size(24.dp), color = AppColors.Secondary)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.placement_entry_title), style = MaterialTheme.typography.titleMedium)
                            Text(placementSuggestion?.let { suggestion ->
                                stringResource(R.string.placement_entry_cta_result, suggestion.band,
                                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(suggestion.at)))
                            } ?: stringResource(R.string.placement_entry_cta),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                        }
                        AppIcon(AppSymbol.ChevronRight, Modifier.size(16.dp), color = AppColors.Secondary.copy(alpha = .6f))
                    }
                }
            }
            // ---- CEFR level catalog: one level at a time ----
            // Duolingo-style sections (you never scroll across levels) with a
            // Busuu-style explicit level picker that stays pinned while the
            // learner browses the catalog below it.
            stickyHeader(key = "level-bar") {
                LevelBar(levels, selectedLevel, completedLevels, onSelect = { savedLevel = it })
            }
            if (vm.levelTestPool(selectedLevel) != null) item(key = "leveltest-entry") {
                val history = remember(selectedLevel) { vm.levelTestHistory(selectedLevel) }
                Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { levelTestLevel = selectedLevel }
                    .testTag("leveltest-card"), shape = RoundedCornerShape(26.dp), color = AppColors.Surface.copy(alpha = .85f)) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(AppSymbol.Check, Modifier.size(24.dp), color = AppColors.Secondary)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.leveltest_entry_title, selectedLevel), style = MaterialTheme.typography.titleMedium)
                            Text(history?.let { last ->
                                stringResource(R.string.leveltest_entry_cta_result, last.overall,
                                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(last.at)))
                            } ?: stringResource(R.string.leveltest_entry_cta),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                        }
                        AppIcon(AppSymbol.ChevronRight, Modifier.size(16.dp), color = AppColors.Secondary.copy(alpha = .6f))
                    }
                }
            }
            courses.forEach { course ->
                val unitsAtLevel = course.units.filter { it.level == selectedLevel }
                if (unitsAtLevel.isNotEmpty()) {
                    item(key = "course-${course.id}") {
                        CourseLabel(course)
                    }
                    if (unitsAtLevel.any { it.id == recommendedUnit?.id }) {
                        item(key = "level-stats-${course.id}") { LevelCard(vm.levelStats(course)) }
                    }
                    unitsAtLevel.forEach { unit ->
                        item(key = "${course.id}:${unit.id}") {
                            UnitSection(vm, unit, vm.levelStats(course), progress.second, recommended?.id,
                                onOpen = { id -> openLessonID = id; vm.stopLessonReading() })
                        }
                    }
                }
            }
        }
    }
}

/** CEFR sort key: A1 < A2 < B1 < B2 < C1 < C2, unknown shapes sort last. */
private fun cefrRank(level: String): Int {
    val letter = level.firstOrNull()?.uppercaseChar()?.let { it - 'A' + 1 } ?: return 99
    val digit = level.drop(1).toIntOrNull() ?: return 99
    return letter * 10 + digit
}

/** Pinned CEFR picker. Selected level filled; a fully completed level carries
    a check so the ladder reads as progress, not just navigation. */
@Composable
private fun LevelBar(levels: List<String>, selected: String?, completedLevels: List<String>, onSelect: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("level-bar"), color = AppColors.Cream) {
        Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            levels.forEach { level ->
                val isSelected = level == selected
                val isDone = level in completedLevels
                Surface(Modifier.clickable(role = Role.Button, enabled = level != selected) { onSelect(level) }
                        .testTag("level-$level"),
                    shape = RoundedCornerShape(50),
                    color = if (isSelected) AppColors.Orange else AppColors.CreamRaised) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (isDone && !isSelected) AppIcon(AppSymbol.Check, Modifier.size(12.dp), color = AppColors.Secondary)
                        Text(level, style = MaterialTheme.typography.titleSmall,
                            color = if (isSelected) AppColors.Ink else AppColors.Secondary)
                    }
                }
            }
        }
    }
}

/** One course's name over its units inside the level view. */
@Composable
private fun CourseLabel(course: Course) {
    Text(course.title, style = MaterialTheme.typography.titleSmall, color = AppColors.Secondary,
        modifier = Modifier.semantics { heading() }.testTag("course-label-" + course.id))
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
                Text(stringResource(R.string.learn_level_breakdown, stats.lessonPoints, stats.listeningPoints),
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
private fun DailyPlanCard(vm: AppViewModel, onRecall: () -> Unit, onLesson: (String) -> Unit) {
    var budget by rememberSaveable { mutableStateOf(10) }
    val steps = vm.dailyPlan(budget)
    val recommended = vm.recommendedLesson()
    Surface(Modifier.fillMaxWidth().testTag("daily-plan"),
        shape = RoundedCornerShape(26.dp), color = AppColors.Surface.copy(alpha = .85f)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // Weighted but not filling: the budget chips keep their intrinsic
                // width (measured first) and the title wraps instead of squeezing
                // the chips into vertical text on narrow screens.
                Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DailyPlanEngine.BUDGETS.forEach { option ->
                        Surface(Modifier.clickable(role = Role.Button) { budget = option }.testTag("plan-budget-" + option),
                            shape = RoundedCornerShape(14.dp),
                            color = if (option == budget) AppColors.Secondary else AppColors.Cream) {
                            Text(stringResource(R.string.plan_minutes, option),
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
                                color = if (option == budget) AppColors.Cream else AppColors.Secondary)
                        }
                    }
                }
            }
            if (steps.isEmpty()) {
                Text(stringResource(R.string.plan_empty), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
            } else steps.forEach { step ->
                val label = when (step.kind) {
                    "lesson" -> stringResource(R.string.plan_step_lesson, recommended?.title ?: stringResource(R.string.learn_title))
                    else -> stringResource(when (step.kind) {
                        "recall" -> R.string.plan_step_recall
                        "listen" -> R.string.plan_step_listen
                        else -> R.string.plan_step_read
                    })
                }
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    when (step.kind) {
                        "recall" -> onRecall()
                        "lesson" -> recommended?.let { onLesson(it.id) }
                    }
                }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppIcon(when (step.kind) {
                        "recall" -> AppSymbol.Words
                        "listen" -> AppSymbol.Headphones
                        else -> AppSymbol.Sparkles
                    }, Modifier.size(18.dp), color = AppColors.Secondary)
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.plan_minutes, step.minutes),
                        style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                }
            }
        }
    }
}

@Composable
private fun UnitSection(vm: AppViewModel, unit: CourseUnit, level: LevelStats,
                        doneLessonIDs: Set<String>, recommendedID: String?, onOpen: (String) -> Unit) {
    // The pinned level bar above is the CEFR context; unit rows never repeat
    // it. Only the unit holding the recommended lesson lists its lessons.
    val hasRecommendation = unit.lessons.any { it.id == recommendedID }
    var expanded by rememberSaveable(unit.id) { mutableStateOf(hasRecommendation) }
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, label = "unit-chevron")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp).testTag("unit-" + unit.id),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(unit.title, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }.weight(1f))
            Text(stringResource(R.string.learn_unit_progress,
                unit.lessons.count { it.id in doneLessonIDs }, unit.lessons.size),
                style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
            AppIcon(AppSymbol.ChevronRight, Modifier.size(14.dp).graphicsLayer { rotationZ = chevron },
                color = AppColors.Secondary.copy(alpha = .6f))
        }
        if (expanded) {
            val recommendedIDInUnit = recommendedID
            unit.lessons.forEach { lesson ->
                val attempts = vm.lessonAttempts(lesson.id)
                val tier = level.mastery.firstOrNull { it.lessonID == lesson.id }?.tier ?: 0
                Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(lesson.id) }.testTag("lesson-" + lesson.id),
                    shape = RoundedCornerShape(18.dp), color = AppColors.Cream) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(lesson.title, style = MaterialTheme.typography.titleSmall)
                                if (recommendedIDInUnit == lesson.id && attempts == 0) Text(stringResource(R.string.learn_recommended),
                                    style = MaterialTheme.typography.labelSmall, color = AppColors.Secondary)
                            }
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
            val pronTargets = if (vm.pronunciationEnabled) lesson.skills.filter { it.startsWith("pron:") } else emptyList()
            if (pronTargets.isNotEmpty()) {
                item { DetailHeading(stringResource(R.string.lesson_pronounce_heading)) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.lesson_pronounce_hint), style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Secondary)
                        lesson.expressions.take(3).forEach { expression ->
                            val listening = vm.pronunciationListening == expression.text
                            Surface(Modifier.fillMaxWidth().clickable(role = Role.Button,
                                    onClickLabel = stringResource(R.string.lesson_pronounce_play)) {
                                    if (!vm.isRunning) vm.readLessonCard(expression.text)
                                }.testTag("pronounce-" + expression.text.hashCode()),
                                shape = RoundedCornerShape(18.dp), color = AppColors.Cream) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    AppIcon(AppSymbol.Wave, Modifier.size(20.dp), color = AppColors.Secondary)
                                    Column(Modifier.weight(1f)) {
                                        Text(expression.text, style = MaterialTheme.typography.titleSmall)
                                        Text(expression.note, style = MaterialTheme.typography.bodySmall,
                                            color = AppColors.Secondary)
                                    }
                                    when {
                                        listening -> Text(stringResource(R.string.lesson_pronounce_listening),
                                            style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                                        else -> Button(onClick = { vm.checkPronunciation(expression.text, lesson.id) },
                                            enabled = !vm.isRunning,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = AppColors.Orange, contentColor = AppColors.Ink)) {
                                            Text(stringResource(R.string.lesson_pronounce_check))
                                        }
                                    }
                                }
                            }
                            vm.pronunciationResult?.takeIf { it.first == expression.text }?.let { (_, score) ->
                                val passed = PronunciationEngine.passed(score)
                                Text(
                                    stringResource(R.string.lesson_pronounce_score, score) + " · " +
                                        stringResource(if (passed) R.string.lesson_pronounce_pass else R.string.lesson_pronounce_retry),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (passed) AppColors.Secondary else AppColors.Ink,
                                    modifier = Modifier.testTag("pronounce-score-" + expression.text.hashCode()),
                                )
                            }
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
