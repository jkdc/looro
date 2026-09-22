package com.jkdc.app.core.voice

import android.content.Context
import com.jkdc.app.BuildConfig
import com.jkdc.app.engines.local.AudioFrameSource
import com.jkdc.app.engines.local.SpeechSink
import com.jkdc.app.engines.local.MicRecorder
import com.jkdc.app.engines.local.WhisperStt
import com.jkdc.app.network.LiveSessionProvider
import com.jkdc.app.network.TeachingClient
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
 * vocabulary the WebRTC transport produces, so AppViewModel needs no
 * engine-specific branches.
 *
 * Turn-taking: continuous listening with a noise-floor-tracked energy VAD
 * ([AdaptiveVad]) — a turn ends after [silenceEndMs] of trailing silence
 * once [SPEECH_START_MS] of speech accumulated, capped at [MAX_TURN_MS].
 * While TTS speaks the microphone is paused entirely ([AudioFrameSource.pause])
 * and restarted afterwards with the stale buffered audio discarded, so speaker
 * output is never re-captured as a phantom turn; with barge-in enabled (AEC
 * devices) the mic stays live instead, the loop drops playback-period frames,
 * and sustained voice energy stops the playback. Levels may go quiet during
 * playback for the same reason.
 */
private const val LOG_TAG = "AppLocalEngine"

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

    /** Learner-facing pace preference: multiplies the system TTS rate (the prompt carries the instruction for realtime engines). */
    @Volatile var speechRate: Float = 1.0f

    /**
     * Voice barge-in while the assistant speaks. Gated on the device's AEC
     * capability: without it, loudspeaker echo could self-trigger. Frames
     * captured during playback NEVER enter a turn buffer, so even a
     * mis-trigger only interrupts the utterance — it can never create a
     * phantom turn.
     */
    @Volatile var bargeIn: Boolean = AecAvailability.isSupported()

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

    /** In-flight partial transcription; superseded or cancelled at each new partial / turn end. */
    private var partialJob: Job? = null
    private var lastPartialClockMs = 0
    private var lastPartialText: String? = null

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
        partialJob?.cancel(); partialJob = null
        lastPartialClockMs = 0; lastPartialText = null
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
        tts.setSpeechRate(speechRate)
        // Synthesize in the session language; a missing system voice degrades
        // to whatever the engine had (logged) rather than failing the session.
        if (!tts.setLanguage(sessionLanguage)) {
            log("no system voice for '$sessionLanguage': keeping the previous TTS language")
        }
        running = true

        emit(buildJsonObject {
            put("type", "app.session.created")
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
                    // A silent assistant with an "active" session is worse than
                    // a failed session: surface the greeting failure loudly.
                    log("opening failed: ${e.message}")
                    onFailure?.invoke("The opening greeting failed: ${e.message ?: "unknown error"}")
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
            val vad = AdaptiveVad()
            try {
                var speech: MutableList<FloatArray> = ArrayList()
                var speechMs = 0
                var trailingSilenceMs = 0
                var turnMs = 0
                var partialClockMs = 0
                var bargeMs = 0
                var lastUsageEmit = System.currentTimeMillis()
                // Close and process whatever the current turn buffered (shared
                // by the manual end request and the manual-mode safety cap).
                suspend fun endBufferedTurn(reason: String) {
                    val samples = speech.flatMap { it.asSequence() }.toList().toFloatArray()
                    speech = ArrayList(); speechMs = 0; trailingSilenceMs = 0; turnMs = 0; partialClockMs = 0
                    lastPartialClockMs = 0
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
                    val frameMs = frame.size * 1000 / MicRecorder.SAMPLE_RATE

                    if (muted) { // suppressed: drop audio, keep levels alive
                        speech = ArrayList(); speechMs = 0; trailingSilenceMs = 0; turnMs = 0; partialClockMs = 0
                        continue
                    }
                    if (speaking) {
                        // Barge-in: detect sustained voice energy over the
                        // playback, but never buffer this audio (echo guard).
                        if (bargeIn && rms >= BARGE_RMS) {
                            bargeMs += frameMs
                            if (bargeMs >= BARGE_TRIGGER_MS) {
                                log("barge-in detected ($bargeMs ms over $BARGE_RMS): stopping playback")
                                bargeMs = 0
                                tts.stop()
                            }
                        } else bargeMs = 0
                        continue
                    }
                    bargeMs = 0

                    speech.add(frame)
                    turnMs += frameMs

                    if (!manualTurn) {
                        // Auto VAD: noise-floor-tracked with hysteresis.
                        val isSpeech = vad.observe(rms, speechMs > 0)
                        if (isSpeech) {
                            if (speechMs == 0) log("speech detected (rms=$rms, floor=${vad.floor})")
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
                            speech = ArrayList(); speechMs = 0; trailingSilenceMs = 0; turnMs = 0; partialClockMs = 0
                            lastPartialClockMs = 0
                            processTurn(samples)
                            lastUsageEmit = maybeEmitUsage(lastUsageEmit)
                            continue
                        }
                        partialClockMs = speechMs
                    } else {
                        partialClockMs = turnMs
                        if (turnMs >= MAX_TURN_MS) {
                            // Safety cap (both modes): a forgotten open manual turn
                            // must not buffer audio unbounded.
                            endBufferedTurn("turn cap reached in manual mode (${turnMs} ms): closing the turn")
                            continue
                        }
                    }
                    maybeEmitPartial(speech, partialClockMs)
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
     * While a turn is open, every [PARTIAL_EVERY_MS] of spoken audio runs a
     * bounded whisper pass over a snapshot of the buffer and emits
     * `session.input_transcript.partial` — provisional UI text only; the
     * turn-final transcription still produces the stored fragment.
     */
    private fun maybeEmitPartial(speech: List<FloatArray>, clockMs: Int) {
        if (clockMs < PARTIAL_MIN_SPEECH_MS || clockMs - lastPartialClockMs < PARTIAL_EVERY_MS) return
        lastPartialClockMs = clockMs
        partialJob?.cancel()
        val snapshot = speech.flatMap { it.asSequence() }.toList()
        val maxSamples = MAX_PARTIAL_AUDIO_MS * MicRecorder.SAMPLE_RATE / 1000
        val tail = if (snapshot.size > maxSamples) snapshot.subList(snapshot.size - maxSamples, snapshot.size) else snapshot
        val samples = tail.toFloatArray()
        partialJob = launchSessionJob(Dispatchers.Default) {
            runCatching {
                val stt = stt.transcribe(MicRecorder.trimSilence(samples), sessionLanguage)
                if (stt.isEmpty) return@runCatching
                if (stt.text == lastPartialText) return@runCatching
                lastPartialText = stt.text
                logContent("partial: \"${stt.text}\"")
                emit(buildJsonObject {
                    put("type", "session.input_transcript.partial")
                    put("event_id", UUID.randomUUID().toString())
                    put("delta", stt.text)
                })
            }
        }
    }

    /**
     * Speaks [text] with capture suppressed. Without barge-in the mic is
     * paused before playback and restarted afterwards, with the audio retained
     * across the pause discarded first — without this, pre-playback mic audio
     * is replayed into the VAD as a phantom turn once speaking clears.
     *
     * With barge-in enabled the mic stays live instead: the listen loop keeps
     * receiving frames, discards them for turn buffering, and watches for
     * sustained voice energy ([BARGE_RMS] for [BARGE_TRIGGER_MS]) — which stops
     * this playback through [SpeechSink.stop], whose interrupted-utterance
     * completion unwinds us here through the normal depth accounting.
     *
     * Reentrancy-safe: concurrent callers each hold a [speakingDepth] slot;
     * mic resume, stale-audio discard and clearing [speaking] happen only when
     * the LAST caller finishes, so one speaker's QUEUE_FLUSH of another never
     * reopens the capture window mid-playback.
     */
    private suspend fun speakSuppressed(text: String) {
        val source = recorder
        val suppressMic = !bargeIn
        if (source != null && suppressMic) source.pause()
        speakingDepth.incrementAndGet()
        speaking = true
        try {
            tts.speak(text)
        } finally {
            val remaining = speakingDepth.decrementAndGet()
            speaking = remaining > 0
            if (remaining == 0 && running && source != null && suppressMic) {
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
        partialJob?.cancel()
        partialJob = null
        log("turn end: captured ${samples.size * 1000L / MicRecorder.SAMPLE_RATE} ms of audio")
        val t0 = System.currentTimeMillis()
        val trimmed = MicRecorder.trimSilence(samples)
        val durationMs = trimmed.size * 1000L / MicRecorder.SAMPLE_RATE
        if (durationMs < 250) {
            log("turn discarded: only ${durationMs} ms of usable audio (noise blip)")
            clearPartial()
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
            clearPartial()
            lastPartialText = null
            return // silence/hallucination guard: no fragments, no LLM call
        }
        // The final transcription supersedes any partial, emitted or not.
        lastPartialText = null

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

    /**
     * A turn discarded without a final transcription must retract any
     * provisional caption: the empty delta tells the ViewModel to clear it.
     */
    private fun clearPartial() {
        if (lastPartialText == null) return
        lastPartialText = null
        emit(buildJsonObject {
            put("type", "session.input_transcript.partial")
            put("event_id", UUID.randomUUID().toString())
            put("delta", "")
        })
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
     * Renders the session-history JSON as transcript lines. [com.jkdc.app.core.ConversationHistory.messages]
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
        const val SPEECH_START_MS = 300
        const val MAX_TURN_MS = 30_000

        /** System-TTS speech rates for the learner-facing pace preference. */
        const val TTS_NORMAL_RATE = 1.0f
        const val TTS_SLOW_RATE = 0.8f
        const val TTS_FAST_RATE = 1.2f
        const val TTS_VERY_SLOW_RATE = 0.6f

        /** Partial transcription cadence and bounds (spoken-audio clock). */
        const val PARTIAL_MIN_SPEECH_MS = 1_000
        const val PARTIAL_EVERY_MS = 2_000
        const val MAX_PARTIAL_AUDIO_MS = 8_000

        /** Barge-in: loud, sustained energy during playback stops the TTS. */
        const val BARGE_RMS = 0.06f
        const val BARGE_TRIGGER_MS = 250

        /** Surfaced through [onFailure] when the device has no usable TTS. */
        const val TTS_UNAVAILABLE = "Speech output is unavailable on this device"

        /** Rolling in-session turns kept as context (limits prompt growth). */
        const val MAX_ROLLING_TURNS = 16
    }
}

/**
 * Reports the device's hardware echo-cancellation capability without letting
 * the missing android.media.audiofx runtime (JVM unit tests) fail the call.
 */
object AecAvailability {
    fun isSupported(): Boolean = runCatching { android.media.audiofx.AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
}
