package chat.mural.core.voice

import android.content.Context
import chat.mural.BuildConfig
import chat.mural.engines.local.AudioFrameSource
import chat.mural.engines.local.SpeechSink
import chat.mural.engines.local.MicRecorder
import chat.mural.engines.local.WhisperStt
import chat.mural.network.LiveSessionProvider
import chat.mural.network.TeachingClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * On-device voice pipeline behind the [VoiceTransport] contract: mic → Whisper
 * STT → the selected [TeachingClient] → TTS. Emits the same JSON event
 * vocabulary the WebRTC transport produces, so MuralViewModel needs no
 * engine-specific branches.
 *
 * Turn-taking (v1): continuous listening with an energy VAD — a turn ends
 * after [SILENCE_END_MS] of trailing silence once [SPEECH_START_MS] of speech
 * accumulated, capped at [MAX_TURN_MS]. While TTS speaks the microphone is
 * paused entirely ([AudioFrameSource.pause]) and restarted afterwards with the
 * stale buffered audio discarded, so speaker output is never re-captured as a
 * phantom turn. Levels may go quiet during playback for the same reason.
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
     * Depth of concurrent [speakSuppressed] callers (turn reply, opening
     * greeting, typed commentary run on different coroutines). The mic only
     * resumes when the depth reaches zero, so one speaker finishing can never
     * reopen capture while another is still playing.
     */
    private val speakingDepth = AtomicInteger(0)
    /** The live frame source; paused while TTS plays back so it never captures speaker audio. */
    @Volatile private var recorder: AudioFrameSource? = null

    /**
     * Beginner mode: when true the VAD never auto-ends the turn; the turn is
     * processed only when [endTurn] is called — or at the [MAX_TURN_MS] safety
     * cap, which applies in both modes so a forgotten open turn can never
     * buffer audio unbounded.
     */
    @Volatile var manualTurn = false
    @Volatile private var turnEndRequested = false

    /** Requested by the UI ("He terminado"): close and process the current turn. */
    fun endTurn() {
        turnEndRequested = true
    }
    @Volatile private var listenJob: Job? = null
    @Volatile private var sessionStartedAtMs = 0L

    /**
     * Side jobs launched outside the listen loop (the opening greeting, typed
     * commentary): each is tied to the live session and cancelled by
     * [stopLoop], so a slow in-flight reply can never outlive its session and
     * speak into — or steal context from — the next one.
     */
    private val sessionJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    /** Launches session-scoped work; [stopLoop] cancels everything tracked here. */
    private fun launchSessionJob(dispatcher: CoroutineDispatcher, block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(dispatcher, block = block)
        sessionJobs.add(job)
        job.invokeOnCompletion { sessionJobs.remove(job) }
        return job
    }

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
        // A "He terminado" armed by a previous session must not close the first
        // turn of this one.
        turnEndRequested = false
        historyContext = renderHistory(history)
        sessionLanguage = language?.take(2)?.lowercase() ?: "en"
        sessionStartedAtMs = System.currentTimeMillis()

        // Fail the session loudly before announcing it: without TTS the pipeline
        // would silently degrade to text-only turns.
        if (!tts.initialize()) {
            log("TTS initialization failed: failing the session")
            onFailure?.invoke(TTS_UNAVAILABLE)
            return
        }
        running = true

        emit(buildJsonObject {
            put("type", "mural.session.created")
            put("session", buildJsonObject { put("id", UUID.randomUUID().toString()) })
        })
        emit(buildJsonObject { put("type", "session.started") })
        log("voice session started (language='$sessionLanguage')")
        startListening()
        if (openOnStart) {
            launchSessionJob(Dispatchers.Default) {
                // The ViewModel appends the greeting instruction when it handles
                // the session.started event (transport callbacks are funneled
                // through a channel, so that happens asynchronously after
                // connect returns); this delay lets that handling land before
                // the greeting prompt is built.
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
                        runCatching { speakSuppressed(result.text) }
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
            val source = frameSourceProvider()
            if (!source.start()) {
                onFailure?.invoke("Microphone unavailable")
                return@launch
            }
            recorder = source
            try {
                var speech: MutableList<FloatArray> = ArrayList()
                var speechMs = 0
                var trailingSilenceMs = 0
                var turnMs = 0
                var lastUsageEmit = System.currentTimeMillis()
                // Close and process whatever the current turn buffered (shared
                // by the manual end request and the manual-mode safety cap).
                suspend fun endBufferedTurn(reason: String) {
                    val samples = speech.flatMap { it.asSequence() }.toList().toFloatArray()
                    speech = ArrayList(); speechMs = 0; trailingSilenceMs = 0; turnMs = 0
                    log(reason)
                    if (samples.isNotEmpty()) processTurn(samples)
                }
                while (coroutineContext.isActive && running) {
                    if (frameDelayMs > 0) delay(frameDelayMs)

                    // Manual mode: the user asked to close and process the turn now.
                    if (manualTurn && turnEndRequested) {
                        turnEndRequested = false
                        endBufferedTurn("manual end of turn")
                        continue
                    }

                    val frame = source.readFrame()
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
                    val frameMs = frame.size * 1000 / MicRecorder.SAMPLE_RATE
                    turnMs += frameMs

                    if (!manualTurn) {
                        // Auto VAD (only in automatic mode).
                        if (rms >= VAD_SPEECH_RMS) {
                            if (speechMs == 0) log("speech detected (rms=$rms)")
                            speechMs += frameMs
                            trailingSilenceMs = 0
                        } else if (speechMs > 0) {
                            trailingSilenceMs += frameMs
                        }
                        val turnComplete = speechMs >= SPEECH_START_MS && trailingSilenceMs >= silenceEndMs
                        val capReached = speechMs > 0 && turnMs >= MAX_TURN_MS
                        if (turnComplete || capReached) {
                            if (turnComplete) log("end of speech detected (${speechMs} ms spoken, ${trailingSilenceMs} ms silent)")
                            else log("turn cap reached (${turnMs} ms): closing the turn")
                            val samples = speech.flatMap { it.asSequence() }.toList().toFloatArray()
                            speech = ArrayList(); speechMs = 0; trailingSilenceMs = 0; turnMs = 0
                            processTurn(samples)
                            lastUsageEmit = maybeEmitUsage(lastUsageEmit)
                        }
                    } else if (turnMs >= MAX_TURN_MS) {
                        // Safety cap (both modes): a forgotten open manual turn
                        // must not buffer audio unbounded.
                        endBufferedTurn("turn cap reached in manual mode (${turnMs} ms): closing the turn")
                    }
                    if (System.currentTimeMillis() - lastUsageEmit >= 15_000) {
                        lastUsageEmit = maybeEmitUsage(lastUsageEmit)
                    }
                }
            } finally {
                recorder = null
                source.stop()
            }
        }
    }

    /**
     * Speaks [text] with the microphone paused: capture stops before playback
     * and restarts afterwards, with the audio retained across the pause
     * discarded first — without this, pre-playback mic audio is replayed into
     * the VAD as a phantom turn once speaking clears.
     *
     * Reentrancy-safe: concurrent callers each hold a [speakingDepth] slot;
     * mic resume, stale-audio discard and clearing [speaking] happen only when
     * the LAST caller finishes, so one speaker's QUEUE_FLUSH of another never
     * reopens the capture window mid-playback.
     */
    private suspend fun speakSuppressed(text: String) {
        val source = recorder
        source?.pause()
        speakingDepth.incrementAndGet()
        speaking = true
        try {
            tts.speak(text)
        } finally {
            val remaining = speakingDepth.decrementAndGet()
            speaking = remaining > 0
            if (remaining == 0 && running && source != null) {
                // Capture is stopped, so the drain is a bounded sweep over the
                // samples retained across pause() — never fresh audio.
                source.drainStale()
                if (!source.resume()) {
                    log("mic could not be resumed after playback")
                    onFailure?.invoke("Microphone unavailable")
                }
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
        // Note: the whisper JNI call cannot be interrupted on cancellation; the
        // recorder stop in stopLoop() is what protects the microphone here.
        val stt = try {
            stt.transcribe(trimmed, sessionLanguage)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            log("STT failed after ${System.currentTimeMillis() - t0} ms: ${e.message}")
            onFailure?.invoke(e.message ?: "STT failed"); return
        }
        logContent("STT done in ${stt.processingMs} ms: \"${stt.text}\"")
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
        logContent("LLM done in ${System.currentTimeMillis() - llmT0} ms (${result.usage.input}+${result.usage.output} tok): \"${result.text.take(120)}\"")
        if (result.text.isBlank()) return
        synchronized(rollingTurns) {
            rollingTurns.addLast("User: ${stt.text}")
            rollingTurns.addLast("Assistant: ${result.text}")
            while (rollingTurns.size > MAX_ROLLING_TURNS) rollingTurns.removeFirst()
        }

        val replyStart = relativeMs()
        emit(transcriptEvent(isInput = false, text = result.text, startMs = replyStart, endMs = replyStart + 500))

        log("TTS start")
        val ttsT0 = System.currentTimeMillis()
        try {
            speakSuppressed(result.text)
            log("TTS done in ${System.currentTimeMillis() - ttsT0} ms")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            log("TTS cancelled after ${System.currentTimeMillis() - ttsT0} ms")
            throw cancelled
        } catch (e: Exception) {
            log("TTS failed: ${e.message}")
            onFailure?.invoke(e.message ?: "TTS failed")
        }
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
                launchSessionJob(Dispatchers.IO) {
                    runCatching { speakSuppressed(content) }
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

    /**
     * Debug-only variant for anything embedding conversation content: STT and
     * LLM transcripts must never reach release logcat.
     */
    private fun logContent(message: String) {
        if (BuildConfig.DEBUG) log(message)
    }

    private fun relativeMs(): Long = System.currentTimeMillis() - sessionStartedAtMs

    /**
     * Renders the session-history JSON as transcript lines. [chat.mural.core.ConversationHistory.messages]
     * emits `content` as a JSON array of typed parts (`{"type":"input_text"|"output_text","text":...}`);
     * non-text parts carry no `text` and are skipped. A plain string `content`
     * is tolerated for hand-built or legacy payloads.
     */
    internal fun renderHistory(history: JsonArray): String = buildString {
        for (element in history) {
            val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
            val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: continue
            val text = when (val content = obj["content"]) {
                is JsonPrimitive -> content.contentOrNull
                is JsonArray -> content.mapNotNull { part ->
                    runCatching { part.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                }.joinToString(" ").ifBlank { null }
                else -> null
            } ?: continue
            if (text.isBlank()) continue
            append(role.replaceFirstChar { it.uppercase() }).append(": ").append(text).append("\n")
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
        // Stop capture FIRST, synchronously: a blocking LLM/STT call can hold
        // the listen loop (and its finally) far past session end, and only the
        // recorder stop reliably releases the microphone and the OS recording
        // indicator. The listen-loop finally stays as a safety net.
        //
        // Concurrency contract: this stop runs on the caller's thread and can
        // land while the loop is inside source.readFrame(). The source owns
        // the serialization — MicRecorder holds a lock across its non-blocking
        // native calls — so the release never races an in-flight read, and the
        // stopper waits at most one read before the mic is released.
        recorder?.stop()
        recorder = null
        // Side jobs (opening greeting, typed commentary) must not outlive the
        // session; cancels an in-flight greeting/commentary before it can emit
        // or speak into a restarted session.
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        listenJob?.cancel()
        listenJob = null
        tts.stop()
        // Best-effort abort of a hung network call (no-op for engines without
        // one). The whisper JNI call cannot be interrupted; the recorder stop
        // above is what actually protects the microphone.
        engine.cancelInFlight()
    }

    companion object {
        const val VAD_SPEECH_RMS = 0.012f
        const val SPEECH_START_MS = 300
        const val SILENCE_END_MS = 1_200
        const val MAX_TURN_MS = 30_000

        /** Surfaced through [onFailure] when the device has no usable TTS. */
        const val TTS_UNAVAILABLE = "Speech output is unavailable on this device"

        /** Rolling in-session turns kept as context (limits prompt growth). */
        const val MAX_ROLLING_TURNS = 16
    }
}
