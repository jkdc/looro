package chat.mural.core.voice

import android.content.Context
import chat.mural.engines.local.AudioFrameSource
import chat.mural.engines.local.SpeechSink
import chat.mural.engines.local.MicRecorder
import chat.mural.engines.local.WhisperStt
import chat.mural.network.LiveSessionProvider
import chat.mural.network.TeachingClient
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * On-device voice pipeline behind the [VoiceTransport] contract: mic → Whisper
 * STT → the selected [TeachingClient] → TTS. Emits the same JSON event
 * vocabulary the WebRTC transport produces, so MuralViewModel needs no
 * engine-specific branches.
 *
 * Turn-taking (v1): continuous listening with an energy VAD — a turn ends
 * after [SILENCE_END_MS] of trailing silence once [SPEECH_START_MS] of speech
 * accumulated, capped at [MAX_TURN_MS]. The mic is suppressed while TTS speaks.
 */
private const val LOG_TAG = "MuralLocalEngine"

class LocalVoiceTransport(
    @Suppress("UNUSED_PARAMETER") context: Context?,
    private val scope: CoroutineScope,
    private val stt: WhisperStt,
    private val engine: TeachingClient,
    private val tts: SpeechSink,
    private val frameSourceProvider: () -> AudioFrameSource = { MicRecorder() },
    private val frameDelayMs: Long = 128, // real-time pacing; tests pass 0
    /** Generates the themed opening utterance when the session starts. */
    var openOnStart: Boolean = false,
) : VoiceTransport {

    /** Miliseconds of trailing silence that close an automatic turn (live-adjustable). */
    @Volatile var silenceEndMs: Long = 1_200

    override var onEvent: ((JsonObject) -> Unit)? = null
    override var onFailure: ((String) -> Unit)? = null
    override var onLevels: ((Double, Double) -> Unit)? = null

    @Volatile private var running = false
    @Volatile private var muted = false
    @Volatile private var speaking = false

    /**
     * Beginner mode: when true the VAD never auto-ends the turn; the turn is
     * processed only when [endTurn] is called (or at the 30 s safety cap).
     */
    @Volatile var manualTurn = false
    @Volatile private var turnEndRequested = false

    /** Requested by the UI ("He terminado"): close and process the current turn. */
    fun endTurn() {
        turnEndRequested = true
    }
    @Volatile private var listenJob: Job? = null
    @Volatile private var sessionStartedAtMs = 0L

    private var baseInstructions = ""
    private var historyContext = ""
    private val rollingTurns = ArrayDeque<String>()
    private val appendedContext = ArrayDeque<String>()
    private var sessionLanguage = "en"

    val started: Boolean get() = running
    val isMuted: Boolean get() = muted

    override suspend fun connect(
        api: LiveSessionProvider,
        instructions: String,
        history: JsonArray,
        language: String?,
    ) {
        baseInstructions = instructions
        appendedContext.clear()
        rollingTurns.clear()
        historyContext = renderHistory(history)
        sessionLanguage = language?.take(2)?.lowercase() ?: "en"
        sessionStartedAtMs = System.currentTimeMillis()
        running = true

        emit(buildJsonObject {
            put("type", "mural.session.created")
            put("session", buildJsonObject { put("id", UUID.randomUUID().toString()) })
        })
        emit(buildJsonObject { put("type", "session.started") })
        log("voice session started (language='$sessionLanguage')")
        tts.initialize()
        startListening()
        if (openOnStart) {
            scope.launch(Dispatchers.Default) {
                // The ViewModel appends the greeting instruction on session.started
                // (synchronously during emit), so the context carries it by now.
                delay(200)
                try {
                    val system = buildString {
                        append(baseInstructions)
                        synchronized(appendedContext) {
                            while (appendedContext.isNotEmpty()) append("\n").append(appendedContext.removeFirst())
                        }
                    }
                    val result = engine.respond(
                        system,
                        "(Begin the conversation now as instructed: greet the learner and open the theme.)",
                        null, false, null,
                    )
                    if (result.text.isNotBlank()) {
                        val start = relativeMs()
                        emit(transcriptEvent(isInput = false, text = result.text, startMs = start, endMs = start + 500))
                        speaking = true
                        runCatching { tts.speak(result.text) }
                        speaking = false
                        log("opening spoken (${result.text.length} chars)")
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    log("opening failed: ${e.message}") // degrades quietly
                }
            }
        }
    }

    private fun startListening() {
        listenJob = scope.launch(Dispatchers.IO) {
            val recorder = frameSourceProvider()
            if (!recorder.start()) {
                onFailure?.invoke("Microphone unavailable")
                return@launch
            }
            try {
                var speech: MutableList<FloatArray> = ArrayList()
                var speechMs = 0
                var trailingSilenceMs = 0
                var turnMs = 0
                var lastUsageEmit = System.currentTimeMillis()
                while (coroutineContext.isActive && running) {
                    if (frameDelayMs > 0) delay(frameDelayMs)

                    // Manual mode: the user asked to close and process the turn now.
                    if (manualTurn && turnEndRequested) {
                        turnEndRequested = false
                        val samples = speech.flatMap { it.asSequence() }.toList().toFloatArray()
                        speech = ArrayList(); speechMs = 0
                        log("manual end of turn (${samples.size} frames buffered)")
                        if (samples.isNotEmpty()) processTurn(samples)
                        continue
                    }

                    val frame = recorder.readFrame()
                    if (frame == null) {
                        delay(10)
                        continue
                    }
                    val rms = MicRecorder.rms(frame)
                    onLevels?.invoke(rms.toDouble(), 0.0)

                    if (muted || speaking) { // suppressed: drop audio, keep levels alive
                        speech = ArrayList(); speechMs = 0; trailingSilenceMs = 0; turnMs = 0
                        continue
                    }

                    speech.add(frame)

                    if (!manualTurn) {
                        // Auto VAD (only in automatic mode).
                        val frameMs = frame.size * 1000 / MicRecorder.SAMPLE_RATE
                        if (rms >= VAD_SPEECH_RMS) {
                            if (speechMs == 0) log("speech detected (rms=$rms)")
                            speechMs += frameMs
                            trailingSilenceMs = 0
                        } else if (speechMs > 0) {
                            trailingSilenceMs += frameMs
                        }
                        val turnComplete = speechMs >= SPEECH_START_MS && trailingSilenceMs >= silenceEndMs
                        if (turnComplete || turnMs + frameMs >= MAX_TURN_MS) {
                            if (turnComplete) log("end of speech detected (${speechMs} ms spoken, ${trailingSilenceMs} ms silent)")
                            val samples = speech.flatMap { it.asSequence() }.toList().toFloatArray()
                            speech = ArrayList(); speechMs = 0; trailingSilenceMs = 0; turnMs = 0
                            processTurn(samples)
                            lastUsageEmit = maybeEmitUsage(lastUsageEmit)
                        }
                    }
                    if (System.currentTimeMillis() - lastUsageEmit >= 15_000) {
                        lastUsageEmit = maybeEmitUsage(lastUsageEmit)
                    }
                }
            } finally {
                recorder.stop()
            }
        }
    }

    private suspend fun processTurn(samples: FloatArray) {
        log("turn end: captured ${samples.size * 1000L / MicRecorder.SAMPLE_RATE} ms of audio")
        val t0 = System.currentTimeMillis()
        val trimmed = MicRecorder.trimSilence(samples)
        val durationMs = trimmed.size * 1000L / MicRecorder.SAMPLE_RATE
        if (durationMs < 250) {
            log("turn discarded: only ${durationMs} ms of usable audio (noise blip)")
            return
        }
        log("STT start (${durationMs} ms of audio, language='$sessionLanguage')")
        val stt = try {
            stt.transcribe(trimmed, sessionLanguage)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            log("STT failed after ${System.currentTimeMillis() - t0} ms: ${e.message}")
            onFailure?.invoke(e.message ?: "STT failed"); return
        }
        log("STT done in ${stt.processingMs} ms: \"${stt.text}\"")
        if (stt.isEmpty) {
            log("empty transcription: resuming listening (no LLM call)")
            return // silence/hallucination guard: no fragments, no LLM call
        }

        val turnStart = relativeMs()
        emit(transcriptEvent(isInput = true, text = stt.text, startMs = turnStart, endMs = turnStart + durationMs))

        val conversationSoFar = synchronized(rollingTurns) { rollingTurns.joinToString("\n") }
        val system = buildString {
            append(baseInstructions)
            synchronized(appendedContext) {
                while (appendedContext.isNotEmpty()) append("\n").append(appendedContext.removeFirst())
            }
            if (historyContext.isNotBlank() || conversationSoFar.isNotBlank()) {
                append("\n\nConversation so far (most recent last):")
                if (historyContext.isNotBlank()) append("\n").append(historyContext)
                if (conversationSoFar.isNotBlank()) append("\n").append(conversationSoFar)
            }
        }
        log("LLM start")
        val llmT0 = System.currentTimeMillis()
        val result = try {
            engine.respond(system, stt.text, null, false, null)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            log("LLM failed after ${System.currentTimeMillis() - llmT0} ms: ${e.message}")
            onFailure?.invoke(e.message ?: "generation failed"); return
        }
        log("LLM done in ${System.currentTimeMillis() - llmT0} ms (${result.usage.input}+${result.usage.output} tok): \"${result.text.take(120)}\"")
        if (result.text.isBlank()) return
        synchronized(rollingTurns) {
            rollingTurns.addLast("User: ${stt.text}")
            rollingTurns.addLast("Assistant: ${result.text}")
            while (rollingTurns.size > MAX_ROLLING_TURNS) rollingTurns.removeFirst()
        }

        val replyStart = relativeMs()
        emit(transcriptEvent(isInput = false, text = result.text, startMs = replyStart, endMs = replyStart + 500))

        speaking = true
        log("TTS start")
        val ttsT0 = System.currentTimeMillis()
        try {
            tts.speak(result.text)
            log("TTS done in ${System.currentTimeMillis() - ttsT0} ms")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            speaking = false
            log("TTS cancelled after ${System.currentTimeMillis() - ttsT0} ms")
            throw cancelled
        } catch (e: Exception) {
            log("TTS failed: ${e.message}")
            onFailure?.invoke(e.message ?: "TTS failed")
        }
        speaking = false
        log("turn complete in ${System.currentTimeMillis() - t0} ms total")
    }

    /**
     * Appends from the ViewModel:
     *  - instructions/thinking → stored, prepended to the next turn's context;
     *  - commentary → spoken via TTS and emitted as assistant transcript
     *    (keeps typed input working during a voice session).
     */
    override fun send(event: JsonObject): Boolean {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return false
        val content = event["content"]?.jsonPrimitive?.contentOrNull ?: return false
        when {
            type.startsWith("session.instructions.append") || type.startsWith("session.thinking.append") -> {
                synchronized(appendedContext) { appendedContext.addLast(content) }
                return true
            }
            type.startsWith("session.commentary.append") -> {
                val start = relativeMs()
                emit(transcriptEvent(isInput = false, text = content, startMs = start, endMs = start + 500))
                scope.launch(Dispatchers.IO) {
                    speaking = true
                    runCatching { tts.speak(content) }
                    speaking = false
                }
                return true
            }
            else -> return false
        }
    }

    private fun maybeEmitUsage(lastEmit: Long): Long {
        val now = System.currentTimeMillis()
        if (now - lastEmit >= 15_000) {
            emit(buildJsonObject {
                put("type", "session.usage.updated")
                put("usage", buildJsonObject { put("seconds", (now - sessionStartedAtMs) / 1000.0) })
            })
            return now
        }
        return lastEmit
    }

    private fun transcriptEvent(isInput: Boolean, text: String, startMs: Long, endMs: Long) = buildJsonObject {
        put("type", if (isInput) "session.input_transcript.delta" else "session.output_transcript.delta")
        put("event_id", UUID.randomUUID().toString())
        put("delta", text)
        put("start_ms", startMs)
        put("end_ms", endMs)
    }

    private fun emit(event: JsonObject) {
        onEvent?.invoke(event)
    }

    private fun log(message: String) {
        android.util.Log.i(LOG_TAG, message)
    }

    private fun relativeMs(): Long = System.currentTimeMillis() - sessionStartedAtMs

    /** Renders the session-history JSON (OpenAI-style messages) as transcript lines. */
    internal fun renderHistory(history: JsonArray): String = buildString {
        for (element in history) {
            val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
            val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: continue
            val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: continue
            if (content.isBlank()) continue
            append(role.replaceFirstChar { it.uppercase() }).append(": ").append(content).append("\n")
        }
    }.trim()



    override fun mute(muted: Boolean) {
        this.muted = muted
    }

    override fun close() {
        stopLoop()
        emit(buildJsonObject { put("type", "session.closed") })
    }

    override fun disconnect() {
        stopLoop()
    }

    private fun stopLoop() {
        running = false
        listenJob?.cancel()
        listenJob = null
        tts.stop()
    }

    companion object {
        const val VAD_SPEECH_RMS = 0.012f
        const val SPEECH_START_MS = 300
        const val SILENCE_END_MS = 1_200
        const val MAX_TURN_MS = 30_000

        /** Rolling in-session turns kept as context (limits prompt growth). */
        const val MAX_ROLLING_TURNS = 16
    }
}
