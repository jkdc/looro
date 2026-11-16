package com.jkdc.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jkdc.app.core.Preferences
import com.jkdc.app.core.DailyAttemptStage
import com.jkdc.app.ui.rememberDailyHintsVisible
import com.jkdc.app.core.DailyAttemptLocalState
import com.jkdc.app.core.DailyEstimatedMinutes
import com.jkdc.app.core.DailyExpression
import com.jkdc.app.core.DailyGoal
import com.jkdc.app.core.DailyListening
import com.jkdc.app.core.DailyLocales
import com.jkdc.app.core.DailyPracticePackage
import com.jkdc.app.core.DailyPracticeSection
import com.jkdc.app.core.DailyQuestion
import com.jkdc.app.core.DailySpeaking
import com.jkdc.app.core.DailyTopic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyPracticeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var original = Preferences()
    private var preferencesCaptured = false

    @Before fun prepareEnglishLearner() {
        compose.awaitHistoryLoaded()
        compose.runOnIdle {
            val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            original = vm.archive.preferences.copy()
            preferencesCaptured = true
            vm.updatePreferences(original.copy(learningLanguageID = "en", hasOnboarded = true, aiConsentVersion = 1))
        }
        compose.waitForIdle()
    }

    @After fun restorePreferences() {
        if (!preferencesCaptured) return
        compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java].updatePreferences(original) }
    }

    @Test fun englishLearnEntryOpensDailyPracticeScreen() {
        compose.onNodeWithTag("tab-learn").performClick()
        compose.onNodeWithTag("daily-practice-entry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("daily-practice-screen").assertIsDisplayed()
    }

    @Test fun unfinishedAttemptResumesFromDeviceCacheWithoutFetchingTodaysPackage() {
        // Dispose any screen left by an earlier instrumentation test before seeding the cache.
        compose.onNodeWithTag("tab-talk").performClick()
        lateinit var vm: AppViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        val owner = requireNotNull(vm.loadedLearningIdentity)
        val attemptId = "88888888-8888-4888-8888-888888888889"
        val locales = DailyLocales("Fixture text", "Texto de prueba")
        fun question(id: String) = DailyQuestion(id, "comprehension", "Fixture question", emptyList(), locales)
        val packageFixture = DailyPracticePackage(
            schemaVersion = 1,
            packageVersionId = "99999999-9999-4999-8999-999999999998",
            contentHash = "fixture-hash",
            editorialDate = "2026-01-02",
            language = "en",
            profileVersion = "en-v1",
            topic = DailyTopic("cached-topic", "Resumed fixture topic", "Saved with the unfinished attempt."),
            objective = "",
            challengeReference = "B1-B2",
            estimatedMinutes = DailyEstimatedMinutes(2, 4, 6, 3, 2, 17),
            expressions = List(5) { index ->
                DailyExpression("fixture-expression-$index", "fixture expression $index", locales, locales)
            },
            listening = DailyListening("en-US", "Fixture transcript", List(3) { question("listening-$it") }),
            speaking = DailySpeaking("learner", "", emptyList(), ""),
            goals = List(3) { index ->
                DailyGoal("fixture-goal-$index", "Fixture goal", locales, locales, locales)
            },
            practice = DailyPracticeSection(List(4) { question("practice-$it") }),
            supportLocales = listOf("en", "es"),
        )
        val draft = DailyAttemptLocalState.start(attemptId).copy(
            createStatus = "acknowledged", stage = DailyAttemptStage.LISTENING, revision = 3)
        runBlocking { vm.persistDailyDraft(owner, packageFixture, draft) }

        compose.onNodeWithTag("tab-learn").performClick()
        compose.onNodeWithTag("daily-practice-entry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("daily-practice-screen").assertIsDisplayed()
        compose.onNodeWithText("Resumed fixture topic").assertIsDisplayed()
        compose.onNodeWithText("Your current step is saved on this device.").assertIsDisplayed()
        compose.onNodeWithText("Stage: Listening").assertIsDisplayed()
    }

}

@RunWith(AndroidJUnit4::class)
class DailyHintVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun visibleHintsResetAcrossPreparationListeningAndPracticeStages() {
        var stage by mutableStateOf(DailyAttemptStage.PREPARATION)
        compose.setContent {
            var hintsVisible by rememberDailyHintsVisible("hint-test-attempt", stage)
            Column {
                Text(if (hintsVisible) "Hints visible in $stage" else "Hints hidden in $stage")
                Button(onClick = { hintsVisible = true }) { Text("Show hints") }
                Button(onClick = { stage = DailyAttemptStage.LISTENING }) { Text("Continue to listening") }
                Button(onClick = { stage = DailyAttemptStage.PRACTICE }) { Text("Continue to practice") }
            }
        }

        compose.onNodeWithText("Show hints").performClick()
        compose.onNodeWithText("Hints visible in PREPARATION").assertIsDisplayed()
        compose.onNodeWithText("Continue to listening").performClick()
        compose.onNodeWithText("Hints hidden in LISTENING").assertIsDisplayed()
        compose.onNodeWithText("Show hints").performClick()
        compose.onNodeWithText("Hints visible in LISTENING").assertIsDisplayed()
        compose.onNodeWithText("Continue to practice").performClick()
        compose.onNodeWithText("Hints hidden in PRACTICE").assertIsDisplayed()
    }
}
