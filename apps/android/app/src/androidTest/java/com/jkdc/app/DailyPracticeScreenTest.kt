package com.jkdc.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jkdc.app.core.Preferences
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
}
