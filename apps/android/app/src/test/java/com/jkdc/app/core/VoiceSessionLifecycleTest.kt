package com.jkdc.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionLifecycleTest {
    @Test fun onlyRunningVoiceSessionsPauseOnLeave() {
        assertTrue(VoiceSessionLifecycle.shouldPauseOnLeave(state = "active", isVoiceSession = true))
        assertTrue(VoiceSessionLifecycle.shouldPauseOnLeave(state = "connecting", isVoiceSession = true))
        // Typed sessions have nothing playing; idle/ended have nothing to pause.
        assertFalse(VoiceSessionLifecycle.shouldPauseOnLeave(state = "active", isVoiceSession = false))
        assertFalse(VoiceSessionLifecycle.shouldPauseOnLeave(state = "idle", isVoiceSession = true))
        assertFalse(VoiceSessionLifecycle.shouldPauseOnLeave(state = "ended", isVoiceSession = true))
        assertFalse(VoiceSessionLifecycle.shouldPauseOnLeave(state = "paused", isVoiceSession = true))
    }

    @Test fun finishWritesPausedInsteadOfEndedWhenTheUserPaused() {
        assertEquals("paused", VoiceSessionLifecycle.stateAfterClose(pausedByUser = true))
        assertEquals("ended", VoiceSessionLifecycle.stateAfterClose(pausedByUser = false))
    }

    @Test fun pausedIsNotARunningState() {
        // Selectors and navigation re-enable while paused; transport work states stay running.
        for (state in listOf("connecting", "active", "closing")) {
            assertTrue(state, VoiceSessionLifecycle.isRunningState(state))
        }
        for (state in listOf("paused", "idle", "ended", "failed")) {
            assertFalse(state, VoiceSessionLifecycle.isRunningState(state))
        }
    }

    @Test fun resumeNeedsAPausedVoiceSessionWithAConversation() {
        assertTrue(VoiceSessionLifecycle.canResume(state = "paused", hasSession = true))
        assertFalse(VoiceSessionLifecycle.canResume(state = "paused", hasSession = false))
        assertFalse(VoiceSessionLifecycle.canResume(state = "active", hasSession = true))
        assertFalse(VoiceSessionLifecycle.canResume(state = "idle", hasSession = true))
        assertFalse(VoiceSessionLifecycle.canResume(state = "ended", hasSession = true))
    }

    @Test fun restartReplacesThePausedConversationWithAFreshVoiceStart() {
        assertEquals("idle", VoiceSessionLifecycle.stateBeforeFreshStart(state = "paused"))
        assertEquals("idle", VoiceSessionLifecycle.stateBeforeFreshStart(state = "idle"))
    }

    @Test fun typedRepliesContinueActiveOrPausedConversations() {
        assertTrue(VoiceSessionLifecycle.acceptsTyped(state = "active"))
        assertTrue(VoiceSessionLifecycle.acceptsTyped(state = "paused"))
        // Ended/idle conversations still open a new typed session (unchanged behaviour);
        // connecting/closing reject typed input while the transport settles.
        assertFalse(VoiceSessionLifecycle.acceptsTyped(state = "connecting"))
        assertFalse(VoiceSessionLifecycle.acceptsTyped(state = "closing"))
    }
}
