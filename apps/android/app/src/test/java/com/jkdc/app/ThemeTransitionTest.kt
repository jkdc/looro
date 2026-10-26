package com.jkdc.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeTransitionTest {
    @Test fun cancelTopicSelectionLeavesSessionUnchanged() = runTest {
        val gate = TopicTransitionGate<String>()
        val token = gate.request("new topic")
        var appliedSelections = 0

        gate.cancel(token)
        val confirmed = gate.confirm(token, { true }) { appliedSelections++ }

        assertFalse(confirmed)
        assertEquals(0, appliedSelections)
        assertEquals(null, gate.pending)
    }

    @Test fun resumedTypedSessionCloseCompletesTopicTransitionAfterTerminalClosure() = runTest {
        val gate = TopicTransitionGate<String>()
        val token = gate.request("chosen topic")
        var terminallyClosed = false
        var starts = 0

        val confirmed = gate.confirm(token, {
            // This is the production branch used by AppViewModel.end for resumed typed sessions.
            terminallyClosed = true
            topicCloseSettledSuccessfully(voiceSession = false, connecting = false)
        }, stillCurrent = { terminallyClosed }) { starts++ }

        assertTrue("typed terminal closure should settle successfully", confirmed)
        assertEquals(1, starts)
    }

    @Test fun delayedCloseStartsExactlyOnceEvenWhenConfirmationIsRepeated() = runTest {
        val gate = TopicTransitionGate<String>()
        val close = CompletableDeferred<Boolean>()
        var starts = 0
        var applied: String? = null
        val token = gate.request("chosen topic")

        val first = async { gate.confirm(token, { close.await() }) { applied = it; starts++ } }
        runCurrent()
        val duplicate = gate.confirm(token, { close.await() }) { applied = it; starts++ }
        assertFalse(duplicate)
        assertEquals(0, starts)

        close.complete(true)
        assertTrue(first.await())
        assertEquals(1, starts)
        assertEquals("chosen topic", applied)
    }

    @Test fun timeoutDoesNotStartNewSession() = runTest {
        val gate = TopicTransitionGate<String>()
        var starts = 0
        val token = gate.request("chosen topic")

        val result = withTimeoutOrNull(1) { gate.confirm(token, { awaitCancellation() }) { starts++ } }
        assertEquals(null, result)
        assertEquals(0, starts)
        assertEquals(null, gate.pending)
    }

    @Test fun closeFailureDoesNotStartNewSession() = runTest {
        val gate = TopicTransitionGate<String>()
        var starts = 0
        val token = gate.request("chosen topic")

        assertFalse(gate.confirm(token, { throw IllegalStateException("close rejected") }) { starts++ })
        assertEquals(0, starts)
        assertEquals(null, gate.pending)
    }

    @Test fun staleIntentCannotRestartAfterNewIntentOrInvalidation() = runTest {
        val gate = TopicTransitionGate<String>()
        val close = CompletableDeferred<Boolean>()
        var starts = 0
        val old = gate.request("old topic")
        val attempt = async { gate.confirm(old, { close.await() }) { starts++ } }
        runCurrent()

        gate.request("new topic")
        close.complete(true)

        assertFalse(attempt.await())
        assertEquals(0, starts)
        assertEquals("new topic", gate.pending?.value)

        gate.invalidate()
        assertEquals(null, gate.pending)
    }

    @Test fun accountOrNavigationChangeDuringClosePreventsRestart() = runTest {
        val gate = TopicTransitionGate<String>()
        val close = CompletableDeferred<Boolean>()
        var navigationCurrent = true
        var accountCurrent = true
        var starts = 0
        val token = gate.request("chosen topic")
        val attempt = async {
            gate.confirm(token, { close.await() }, stillCurrent = { navigationCurrent && accountCurrent }) { starts++ }
        }
        runCurrent()

        navigationCurrent = false
        accountCurrent = false
        close.complete(true)

        assertFalse(attempt.await())
        assertEquals(0, starts)
        assertEquals(null, gate.pending)
    }
}
