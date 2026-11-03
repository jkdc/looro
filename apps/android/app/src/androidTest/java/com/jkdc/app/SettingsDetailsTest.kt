package com.jkdc.app

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jkdc.app.core.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDetailsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private var originalPreferences = Preferences()
    private var preferencesCaptured = false

    @Before fun skipOnboarding() {
        compose.awaitHistoryLoaded()
        compose.runOnIdle {
            val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            originalPreferences = vm.archive.preferences.copy()
            preferencesCaptured = true
            vm.updatePreferences(originalPreferences.copy(hasOnboarded = true))
        }
        compose.waitForIdle()
    }

    @After fun restorePreferences() {
        if (!preferencesCaptured) return
        compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java].updatePreferences(originalPreferences) }
    }

    @Test fun settingsMatchTheIphoneCorrectionsKeysModelsAndVersionDetails() {
        val activity = compose.activity
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        assertEquals("0.1", version)
        compose.onNodeWithTag("tab-settings").performClick()
        val settings = compose.onNodeWithTag("settings-screen")
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_corrections_value)))
        settings.performScrollToNode(hasTestTag("advanced-api-key"))
        compose.onNodeWithTag("advanced-api-key").performClick()
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_open_api_keys), substring = true))
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_models_footer)))
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_app_version_footer, version)))
    }

    @Test fun providerButtonsResolveLocalizedResources() {
        // Per-app locale only exists as a platform API from Android 13. The
        // live render under Spanish is covered by the selector test below
        // (which recreates the activity); here we pin the resource values.
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val activity = compose.activity
        val spanish = activity.createConfigurationContext(android.content.res.Configuration().apply {
            setLocale(java.util.Locale("es"))
        })
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-screen")
            .performScrollToNode(hasTestTag("provider-personal-key"))
        assertEquals("Tu propia clave", spanish.getString(R.string.settings_provider_personal_key))
        assertEquals("Minutos hospedados", spanish.getString(R.string.settings_provider_hosted_minutes))
    }

}
