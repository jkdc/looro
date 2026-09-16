package chat.mural.engines.local

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

/** Sink seam so voice pipelines are testable without the system TTS. */
interface SpeechSink {
    suspend fun initialize(): Boolean
    suspend fun speak(text: String): TtsStats?
    fun stop()
}

/**
 * Android system TTS for the local voice pipeline. One shared
 * UtteranceProgressListener routes signals by utterance id; also supports
 * synthesize-to-file (used by the STT instrumented test and the POC self-test).
 */
class LocalTtsEngine(context: Context) : SpeechSink {

    private class UtteranceSignals(
        val started: CompletableDeferred<Unit> = CompletableDeferred(),
        val done: CompletableDeferred<Unit> = CompletableDeferred(),
        @Volatile var startedAtNs: Long = 0L,
    )

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var counter = 0
    private val signals = HashMap<String, UtteranceSignals>()

    private fun takeSignals(id: String?): UtteranceSignals? = synchronized(this) { signals.remove(id) }

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
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val s = synchronized(this@LocalTtsEngine) {
                    signals[utteranceId]?.also { it.startedAtNs = System.nanoTime() }
                }
                s?.started?.complete(Unit)
            }

            override fun onDone(utteranceId: String?) {
                takeSignals(utteranceId)?.done?.complete(Unit)
            }

            override fun onError(utteranceId: String?) {
                takeSignals(utteranceId)?.done?.completeExceptionally(RuntimeException("TTS error (legacy callback)"))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                takeSignals(utteranceId)?.done?.completeExceptionally(RuntimeException("TTS error $errorCode"))
            }
        })
        tts = engine
        ready = usable
        usable
    }

    /** Speaks [text], suspending until playback finishes. Null if it never started. */
    override suspend fun speak(text: String): TtsStats? = withContext(Dispatchers.IO) {
        val engine = tts
        if (engine == null || !ready || text.isBlank()) return@withContext null
        val id = "voice-${counter++}"
        val current = UtteranceSignals()
        synchronized(this@LocalTtsEngine) { signals[id] = current }
        val requestNs = System.nanoTime()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        runCatching { withTimeout(4_000) { current.started.await() } }
        val startedAt = current.startedAtNs
        if (startedAt == 0L) {
            takeSignals(id)
            return@withContext null
        }
        val startLatencyMs = (startedAt - requestNs) / 1_000_000
        runCatching { withTimeout(60_000) { current.done.await() } }
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        takeSignals(id)
        TtsStats(startLatencyMs = startLatencyMs, durationMs = durationMs)
    }

    suspend fun synthesizeToFile(text: String, outFile: File): Boolean = withContext(Dispatchers.IO) {
        val engine = tts
        if (engine == null || !ready) return@withContext false
        val id = "file-${counter++}"
        val current = UtteranceSignals()
        synchronized(this@LocalTtsEngine) { signals[id] = current }
        val result = engine.synthesizeToFile(text, Bundle(), outFile, id)
        if (result != TextToSpeech.SUCCESS) {
            takeSignals(id)
            return@withContext false
        }
        runCatching { withTimeout(30_000) { current.done.await() } }
        takeSignals(id)
        outFile.exists() && outFile.length() > 44
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}

data class TtsStats(val startLatencyMs: Long, val durationMs: Long)
