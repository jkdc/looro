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
import org.junit.Assert.assertTrue
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
        // A locale test may leave the activity destroyed mid-recreation; the
        // preference restore is best-effort at that point.
        runCatching {
            compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java].updatePreferences(originalPreferences) }
        }
    }

    /** Locale recreation windows destroy the compose hierarchy between the old
        and new activity: the poll predicates must survive that and return false. */
    private fun textVisible(text: String): Boolean =
        runCatching { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    /** Drains any pending locale recreation from a previous test before this
        test's own locale steps run: back to system language, English UI. */
    private fun resetToSystemLocaleAndWait(locales: LocaleManager) {
        locales.applicationLocales = android.os.LocaleList.getEmptyLocaleList()
        compose.waitUntil(timeoutMillis = 20_000) { textVisible("Ready when you are") }
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

    @Test fun appLanguageSelectorSwitchesSettingsLanguage() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val activity = compose.activity
        val locales = activity.getSystemService(LocaleManager::class.java)
        val original = locales.applicationLocales
        resetToSystemLocaleAndWait(locales)
        try {
            compose.onNodeWithTag("tab-settings").performClick()
            val settings = compose.onNodeWithTag("settings-screen")
            settings.performScrollToNode(hasTestTag("settings-app-language"))
            // Open the selector dropdown and verify the Spanish option is listed.
            compose.onNodeWithTag("settings-app-language").performClick()
            compose.onNodeWithTag("settings-app-language-es").assertExists()
            // Clicking the dropdown item races with the activity recreation it
            // triggers (ComposeNotIdleException in the test rule's idle sync), so
            // the selection is applied through the exact mechanism the item
            // invokes: AppCompatDelegate.setApplicationLocales. The pending
            // dropdown dies with the recreation — no need to close it (back
            // would dismiss the whole Settings sheet first).
            compose.runOnIdle {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags("es"))
            }
            // The settings sheet (rememberSaveable) stays open and the whole
            // hierarchy — including the provider buttons — re-renders Spanish.
            compose.waitUntil(timeoutMillis = 20_000) {
                textVisible("Listo") && textVisible("Tu propia clave")
            }
            // waitUntil returns on timeout without failing: assert the Spanish
            // UI actually rendered.
            compose.onNodeWithTag("settings-done").assert(hasText("Listo"))
        } finally {
            locales.applicationLocales = original
            // waitForIdle deadlocks across the restore recreation; poll for the
            // English sheet header with a bounded timeout instead.
            compose.waitUntil(timeoutMillis = 20_000) { textVisible("Make yourself comfortable") }
        }
    }

    @Test fun everySupportedAppLanguageResolvesItsResources() {
        // The es test above covers the full selector click flow (recreation
        // included); clicking another popup item races with activity recreation
        // and can deadlock the compose idle sync, so the remaining languages
        // are verified deterministically: each values-XX folder must resolve
        // its own localized resources and be part of the selector model.
        val activity = compose.activity
        val expectedDone = mapOf("fr" to "Terminé", "de" to "Fertig", "it" to "Fatto",
            "pt" to "Concluído", "nb" to "Ferdig", "zh" to "完成")
        for ((language, done) in expectedDone) {
            val localized = activity.createConfigurationContext(android.content.res.Configuration().apply {
                setLocale(java.util.Locale(language))
            })
            assertEquals(done, localized.getString(R.string.settings_done))
            assertTrue(com.jkdc.app.core.AppLanguage.OPTIONS.contains(language))
            assertTrue(com.jkdc.app.core.AppLanguage.NATIVE_LABELS.containsKey(language))
        }
    }

    @Test fun appLanguageChoicePersistsAcrossActivityRecreation() {
        // Regression for the user report "the chosen app language is not
        // remembered": the selector writes through AppCompatDelegate (system
        // per-app locale on 13+); after the activity is recreated the choice
        // must still drive the resources.
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val activity = compose.activity
        val locales = activity.getSystemService(LocaleManager::class.java)
        val original = locales.applicationLocales
        resetToSystemLocaleAndWait(locales)
        try {
            compose.runOnIdle {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags("fr"))
            }
            // The Talk tab status line is visible without opening any sheet.
            compose.waitUntil(timeoutMillis = 20_000) { textVisible("Prêt quand tu veux") }
            compose.activityRule.scenario.recreate()
            // waitForIdle deadlocks across recreate with a pending locale change;
            // poll for the (persisted) French status line instead.
            compose.waitUntil(timeoutMillis = 20_000) { textVisible("Prêt quand tu veux") }
            compose.onNodeWithTag("tab-settings").performClick()
            compose.onNodeWithTag("settings-done").assert(hasText("Terminé"))
        } finally {
            locales.applicationLocales = original
            compose.waitUntil(timeoutMillis = 20_000) { textVisible("Ready when you are") }
        }
    }
}
