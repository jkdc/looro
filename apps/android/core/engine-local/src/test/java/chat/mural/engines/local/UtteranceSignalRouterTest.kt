package chat.mural.engines.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the TTS utterance-signal routing that LocalTtsEngine's
 * UtteranceProgressListener delegates to — in particular that a QUEUE_FLUSH
 * interruption (onStop) releases the awaiting speak caller instead of hanging
 * until its 60 s timeout.
 */
class UtteranceSignalRouterTest {

    @Test
    fun `onStop completes the awaiting caller like a QUEUE_FLUSH interruption`() = runBlocking {
        val router = UtteranceSignalRouter()
        val signals = router.register("voice-0")
        val resumed = CompletableDeferred<Unit>()
        launch {
            try {
                withTimeout(2_000) { signals.done.await() } // throws if never released
                resumed.complete(Unit)
            } catch (e: Throwable) {
                resumed.completeExceptionally(e)
            }
        }
        router.onStart("voice-0")
        withTimeout(2_000) { signals.started.await() }
        assertTrue(signals.startedAtNs > 0)

        router.onStop("voice-0", interrupted = true) // engine.speak(QUEUE_FLUSH) from stop()
        withTimeout(2_000) { resumed.await() } // fails the test if the caller was left hanging
    }

    @Test
    fun `onDone completes the awaiting caller one-shot`() = runBlocking {
        val router = UtteranceSignalRouter()
        val signals = router.register("voice-1")
        val awaiting = async { withTimeout(2_000) { signals.done.await() } }
        router.onStart("voice-1")
        router.onDone("voice-1")
        awaiting.await()
        assertNull("signals must be removed after completion", router.take("voice-1"))
    }

    @Test
    fun `onError completes the awaiting caller exceptionally`() = runBlocking {
        val router = UtteranceSignalRouter()
        val signals = router.register("voice-2")
        val outcome = CompletableDeferred<Throwable?>()
        launch {
            outcome.complete(
                try { withTimeout(2_000) { signals.done.await() }; null }
                catch (t: Throwable) { t }
            )
        }
        router.onStart("voice-2")
        router.onError("voice-2", "TTS error 1")
        val error = withTimeout(2_000) { outcome.await() }
        assertTrue("expected the TTS error to surface, got: $error",
            error is RuntimeException && error.message!!.contains("TTS error"))
    }

    @Test
    fun `signals are independent per utterance id`() = runBlocking {
        val router = UtteranceSignalRouter()
        val first = router.register("voice-3")
        val second = router.register("voice-4")
        router.onStop("voice-3", interrupted = true)
        withTimeout(2_000) { first.done.await() }
        assertTrue("an unrelated interruption must not release another utterance", second.done.isActive)
    }

    @Test
    fun `unknown utterance ids are ignored`() {
        val router = UtteranceSignalRouter()
        router.onStart("missing")
        router.onDone("missing")
        router.onStop("missing", interrupted = false)
        router.onError("missing", "TTS error 9")
        assertNull(router.take("missing"))
    }
}
