package com.jkdc.app

import androidx.compose.runtime.MutableState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualMuteUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var originalManualTurn = false
    private var originalMuted = false
    private var originalState = "idle"
    private var originalVoiceSession = false
    private var originalWorking = false
    private var originalTurnInFlight = false

    @Suppress("UNCHECKED_CAST")
    private fun setState(name: String, value: Any?) {
        val field = AppViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }

    private lateinit var vm: AppViewModel

    @Test fun manualMuteIsVisibleAccessibleAndIndependentOfSessionAndTutorOutput() {
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            originalManualTurn = vm.voiceManualTurn
            originalMuted = vm.isMuted
            originalState = vm.state
            originalWorking = vm.working
            originalTurnInFlight = vm.turnInFlightUi
            originalVoiceSession = AppViewModel::class.java.getDeclaredField("voiceSession").apply { isAccessible = true }.getBoolean(vm)
            setState("state", "active")
            AppViewModel::class.java.getDeclaredField("voiceSession").apply { isAccessible = true }.setBoolean(vm, true)
            vm.updateVoiceManualTurn(true)
            setState("working", true)
            setState("turnInFlightUi", true)
        }

        val control = compose.onNodeWithTag("manual-mute-control")
        control.assertIsDisplayed()
        control.assertIsEnabled()
        val node = control.fetchSemanticsNode()
        assertTrue("manual mute control has an accessible action label", node.config.contains(SemanticsProperties.ContentDescription))
        assertTrue("manual mute control exposes its current state", node.config.contains(SemanticsProperties.StateDescription))
        val listeningDescription = node.config[SemanticsProperties.StateDescription]
        val bounds = node.boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("manual mute target is at least 48 dp wide", bounds.width / density >= 48f)
        assertTrue("manual mute target is at least 48 dp tall", bounds.height / density >= 48f)
        control.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        control.assertIsFocused()

        control.performClick()
        val mutedNode = control.fetchSemanticsNode()
        assertFalse("accessible state changes after muting", listeningDescription == mutedNode.config[SemanticsProperties.StateDescription])
        assertFalse("accessible action changes to unmute", node.config[SemanticsProperties.ContentDescription] ==
            mutedNode.config[SemanticsProperties.ContentDescription])
        compose.runOnIdle {
            assertTrue("mute is observable in the active manual voice session", vm.isMuted)
            assertTrue("mute does not clear unrelated pending work", vm.working)
            assertEquals("active", vm.state)
            assertTrue(vm.isVoiceSession)
            assertTrue("pending tutor turn remains pending after microphone mute", vm.turnInFlightUi)
        }
        control.performClick()
        compose.runOnIdle {
            assertFalse(vm.isMuted)
            assertTrue(vm.working)
            assertEquals("active", vm.state)
            assertTrue(vm.isVoiceSession)
        }
    }

    @After fun restoreConversationState() {
        if (!::vm.isInitialized) return
        compose.runOnIdle {
            if (vm.isMuted != originalMuted) vm.toggleMute()
            vm.updateVoiceManualTurn(originalManualTurn)
            setState("state", originalState)
            setState("working", originalWorking)
            setState("turnInFlightUi", originalTurnInFlight)
            AppViewModel::class.java.getDeclaredField("voiceSession").apply { isAccessible = true }
                .setBoolean(vm, originalVoiceSession)
        }
    }
}
