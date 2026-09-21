package com.jkdc.looro.engines.local

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Sink seam so voice pipelines are testable without the system TTS. */
interface SpeechSink {
    suspend fun initialize(): Boolean
    suspend fun speak(text: String): TtsStats?
    fun stop()

    /** 1.0 = normal rate; a no-op for sinks without a rate control. */
    fun setSpeechRate(rate: Float) {}

    /** Selects the synthesis voice by BCP-47 tag; false when unavailable or unsupported. */
    fun setLanguage(languageTag: String): Boolean = false
}

/**
 * Routes TextToSpeech progress callbacks to per-utterance signals by utterance
 * id. Extracted from [LocalTtsEngine] so JVM unit tests can simulate engine
 * callbacks (onDone / onStop / onError) without an android.speech runtime.
 */
internal class UtteranceSignalRouter {
    class Signals(
        val started: CompletableDeferred<Unit> = CompletableDeferred(),
        val done: CompletableDeferred<Unit> = CompletableDeferred(),
        @Volatile var startedAtNs: Long = 0L,
    )

    private val signals = HashMap<String, Signals>()

    /** Registers fresh signals for [id]; the speaker awaits [Signals.done]. */
    fun register(id: String): Signals = synchronized(this) { Signals().also { signals[id] = it } }

    /** Removes and returns the signals for [id] (completion is one-shot). */
    fun take(id: String?): Signals? = synchronized(this) { id?.let { signals.remove(it) } }

    fun onStart(utteranceId: String?) {
        val s = synchronized(this) { utteranceId?.let { signals[it] }?.also { it.startedAtNs = System.nanoTime() } }
        s?.started?.complete(Unit)
    }

    fun onDone(utteranceId: String?) {
        take(utteranceId)?.done?.complete(Unit)
    }

    fun onError(utteranceId: String?, message: String) {
        take(utteranceId)?.done?.completeExceptionally(RuntimeException(message))
    }

    /**
     * A QUEUE_FLUSH-style interruption (including our own [LocalTtsEngine.stop])
     * must release the awaiting caller instead of hanging until its timeout.
     */
    fun onStop(utteranceId: String?, interrupted: Boolean) {
        take(utteranceId)?.done?.complete(Unit)
    }
}

/**
 * Android system TTS for the local voice pipeline. One shared
 * UtteranceProgressListener routes signals by utterance id; also supports
 * synthesize-to-file (used by the STT instrumented test and the POC self-test).
 */
class LocalTtsEngine(context: Context) : SpeechSink {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private val counter = AtomicLong()
    private val router = UtteranceSignalRouter()

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (ready) return@withContext true
        val initDeferred = CompletableDeferred<Boolean>()
        val engine = TextToSpeech(appContext) { status -> initDeferred.complete(status == TextToSpeech.SUCCESS) }
        val ok = runCatching { withTimeout(5_000) { initDeferred.await() } }.getOrDefault(false)
        if (!ok) {
            runCatching { engine.shutdown() }
            return@withContext false
        }
        val langResult = engine.setLanguage(Locale.US)
        val usable = langResult != TextToSpeech.LANG_MISSING_DATA &&
            langResult != TextToSpeech.LANG_NOT_SUPPORTED
        if (!usable) {
            // An unusable language is init failure too: never keep a half-ready
            // engine alive (release() would otherwise be its only cleanup).
            runCatching { engine.shutdown() }
            return@withContext false
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { router.onStart(utteranceId) }

            override fun onDone(utteranceId: String?) { router.onDone(utteranceId) }

            override fun onError(utteranceId: String?) {
                router.onError(utteranceId, "TTS error (legacy callback)")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                router.onError(utteranceId, "TTS error $errorCode")
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                router.onStop(utteranceId, interrupted)
            }
        })
        tts = engine
        ready = true
        true
    }

    /** Speaks [text], suspending until playback finishes. Null if it never started. */
    override suspend fun speak(text: String): TtsStats? = withContext(Dispatchers.IO) {
        val engine = tts
        if (engine == null || !ready || text.isBlank()) return@withContext null
        val id = "voice-${counter.incrementAndGet()}"
        val current = router.register(id)
        val requestNs = System.nanoTime()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        runCatching { withTimeout(4_000) { current.started.await() } }
        val startedAt = current.startedAtNs
        if (startedAt == 0L) {
            router.take(id)
            return@withContext null
        }
        val startLatencyMs = (startedAt - requestNs) / 1_000_000
        runCatching { withTimeout(60_000) { current.done.await() } }
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        router.take(id)
        TtsStats(startLatencyMs = startLatencyMs, durationMs = durationMs)
    }

    suspend fun synthesizeToFile(text: String, outFile: File): Boolean = withContext(Dispatchers.IO) {
        val engine = tts
        if (engine == null || !ready) return@withContext false
        val id = "file-${counter.incrementAndGet()}"
        val current = router.register(id)
        val result = engine.synthesizeToFile(text, Bundle(), outFile, id)
        if (result != TextToSpeech.SUCCESS) {
            router.take(id)
            return@withContext false
        }
        runCatching { withTimeout(30_000) { current.done.await() } }
        router.take(id)
        outFile.exists() && outFile.length() > 44
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun setSpeechRate(rate: Float) {
        runCatching { tts?.setSpeechRate(rate) }
    }

    override fun setLanguage(languageTag: String): Boolean {
        val engine = tts ?: return false
        val locale = runCatching { Locale.forLanguageTag(languageTag) }.getOrNull() ?: return false
        val result = runCatching { engine.setLanguage(locale) }.getOrNull()
            ?: return false
        // Keep the previous voice on a failed switch: a device without a
        // voice for one language must not lose the one it had.
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}

data class TtsStats(val startLatencyMs: Long, val durationMs: Long)
