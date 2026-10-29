package com.jkdc.app.core.voice

import android.content.Context
import com.jkdc.app.BuildConfig
import com.jkdc.app.engines.local.AudioFrameSource
import com.jkdc.app.engines.local.SpeechSink
import com.jkdc.app.engines.local.MicRecorder
import com.jkdc.app.engines.local.SttResult
import com.jkdc.app.engines.local.WhisperStt
import com.jkdc.app.network.LiveSessionProvider
import com.jkdc.app.network.TeachingClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
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
 * A bounded frame producer remains independent of the serial STT/LLM/TTS turn
 * consumer. Without usable AEC, TTS pauses capture and drains stale frames
 * before resuming. With supported barge-in, capture stays live, playback frames
 * are discarded, and sustained voice energy stops TTS without becoming a turn.
 */
private const val LOG_TAG = "AppLocalEngine"

private data class ConversationMessage(val role: String, val content: String) {
    fun render(): String = "$role: $content"
}

private sealed interface FrameHandoff {
    class Audio(val samples: FloatArray, val epoch: Int) : FrameHandoff
    data object Overflow : FrameHandoff
    data object Flush : FrameHandoff
}

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

    /** When the current turn's speech ended (or the turn was force-ended), for perceived-latency logs. */
    @Volatile private var endOfSpeechAtMs = 0L

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
    @Volatile private var openingInProgress = false
    private var openingReady = CompletableDeferred<Unit>()
    private val captureEpoch = AtomicInteger(0)
    @Volatile private var speaking = false

    /** Test/diagnostic snapshot of the capture-window flags (N05 flake triage). */
    internal val captureWindowState: String
        get() = "running=$running opening=$openingInProgress speaking=$speaking " +
            "muted=$muted epoch=$captureEpoch"
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
     * Beginner mode: the VAD never auto-ends the turn. The turn is processed
     * when [endTurn] is called — or the buffer is DISCARDED (not processed) at
     * the [MAX_TURN_MS] idle cap so a forgotten open turn can never grow
     * unbounded. An [endTurn] press with no voice buffered stays armed and
     * captures the next spoken phrase (see the listen loop).
     */
    @Volatile var manualTurn = false
    @Volatile private var turnEndRequested = false

    /**
     * True from the moment the listen loop CLOSES a turn (manual close,
     * armed-phrase close, or auto-VAD close) until the whole turn pipeline —
     * STT → LLM → TTS — completes. An end-turn press held armed during silence
     * does NOT set it: the flag tracks processing, not the request, so the
     * button's pending state starts when the post-press phrase closes, not at
     * the press itself. Changes are mirrored to [onTurnInFlight] so the UI can
     * hold Compose-observable state (a plain getter would never recompose).
     */
    @Volatile var turnInFlight = false
        private set

    /** Mirrors every [turnInFlight] change; called from the IO loop thread. */
    var onTurnInFlight: ((Boolean) -> Unit)? = null

    private fun setTurnInFlight(value: Boolean) {
        turnInFlight = value
        onTurnInFlight?.invoke(value)
    }

    /**
     * Cloud transcription providers by id ("openai", "groq", …) for FINAL turns
     * when the user selects one in settings. Read at transcription time through
     * [cloudSttSelection], so changing the setting applies on the next turn
     * without restarting the session. Failures (no key, offline, provider
     * error) fall back to the local pass.
     */
    var cloudStts: Map<String, com.jkdc.app.network.CloudStt> = emptyMap()
    @Volatile var cloudSttSelection: () -> String? = { null }

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
    private val rollingTurns = ArrayDeque<ConversationMessage>()
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
        synchronized(rollingTurns) {
            parseHistoryMessages(history).forEach(rollingTurns::addLast)
        }
        sessionLanguage = language?.take(2)?.lowercase() ?: "en"
        sessionStartedAtMs = System.currentTimeMillis()
        openingReady = CompletableDeferred()
        openingInProgress = openOnStart

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
                try {
                    // Wait for AppViewModel's session.started handler to append
                    // the initial instructions before building the greeting.
                    openingReady.await()
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
                        if (!running) return@launchSessionJob
                        appendConversationMessage("Assistant", result.text)
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
                } finally {
                    openingInProgress = false
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startListening() {
        listenJob = scope.launch(Dispatchers.IO) {
            val source = frameSourceProvider()
            if (!source.start()) {
                onFailure?.invoke("Microphone unavailable")
                return@launch
            }
            recorder = source
            val handoff = Channel<FrameHandoff>(FRAME_QUEUE_CAPACITY)
            val captureJob = launch(Dispatchers.IO) {
                var bargeMs = 0
                var overflowPending = false
                var wasTurnInFlight = false
                while (isActive && running) {
                    if (frameDelayMs > 0) delay(frameDelayMs)
                    if (overflowPending && handoff.isEmpty && handoff.trySend(FrameHandoff.Overflow).isSuccess) {
                        overflowPending = false
                        continue
                    }
                    val frame = source.readFrame()
                    if (frame == null) {
                        delay(10)
                        continue
                    }
                    if (muted) {
                        bargeMs = 0
                        continue
                    }
                    if (speaking) {
                        if (bargeIn && MicRecorder.rms(frame) >= BARGE_RMS) {
                            bargeMs += frame.size * 1000 / MicRecorder.SAMPLE_RATE
                            if (bargeMs >= BARGE_TRIGGER_MS) {
                                log("barge-in detected ($bargeMs ms over $BARGE_RMS): stopping playback")
                                bargeMs = 0
                                tts.stop()
                            }
                        } else bargeMs = 0
                        continue
                    }
                    bargeMs = 0
                    if (turnInFlight) {
                        if (!wasTurnInFlight) {
                            while (handoff.tryReceive().isSuccess) { }
                            overflowPending = handoff.trySend(FrameHandoff.Flush).isFailure
                            wasTurnInFlight = true
                        }
                        continue
                    }
                    if (wasTurnInFlight) wasTurnInFlight = false
                    if (overflowPending) continue
                    if (handoff.trySend(FrameHandoff.Audio(frame, captureEpoch.get())).isFailure) {
                        // Keep the queue's oldest frames (including utterance onset) and
                        // drop newest frames until it drains, then insert a barrier before
                        // admitting fresh audio. Never replay source backlog from the gap.
                        overflowPending = true
                    } else if (frameDelayMs == 0L) {
                        // Test sources may supply frames faster than the real 16 kHz
                        // recorder cadence. Briefly suspend so the bounded consumer can observe
                        // each frame instead of manufacturing overflow from scheduling.
                        delay(1)
                    }
                }
            }
            val vad = AdaptiveVad()
            try {
                var speech: MutableList<FloatArray> = ArrayList()
                var speechMs = 0
                var trailingSilenceMs = 0
                var turnMs = 0
                var turnSamples = 0
                var captureLimitNotified = false
                var manualWaitMs = 0
                var partialClockMs = 0
                var lastUsageEmit = System.currentTimeMillis()
                val preroll = ArrayDeque<FloatArray>()
                val openingFrames = ArrayDeque<FrameHandoff>()
                var openingBufferedSamples = 0
                var openingCaptureFull = false
                var bufferEpoch = captureEpoch.get()
                var discardUntilSilenceMs = 0
                // Manual-mode armed-press state: a press that lands while the
                // buffer holds no voice stays armed and captures the NEXT
                // spoken phrase whole, closing when the phrase trails into
                // silence — not on its first voiced frame.
                var voicedInBuffer = 0
                var armedPhraseEnd = false
                var armedTrailingMs = 0
                // Close and process whatever the current turn buffered (the
                // manual end request: immediate close and armed-phrase close).
                suspend fun endBufferedTurn(reason: String) {
                    val samples = flattenFrames(speech)
                    speech = ArrayList(); speechMs = 0; trailingSilenceMs = 0; turnMs = 0; turnSamples = 0; partialClockMs = 0
                    captureLimitNotified = false
                    manualWaitMs = 0
                    voicedInBuffer = 0; armedPhraseEnd = false; armedTrailingMs = 0
                    preroll.clear()
                    lastPartialClockMs = 0
                    log(reason)
                    if (samples.isNotEmpty()) {
                        setTurnInFlight(true)
                        try {
                            processTurn(samples)
                        } finally {
                            setTurnInFlight(false)
                        }
                    }
                }
                fun resetCapture() {
                    speech = ArrayList(); speechMs = 0; trailingSilenceMs = 0
                    turnMs = 0; turnSamples = 0; partialClockMs = 0; voicedInBuffer = 0
                    captureLimitNotified = false
                    manualWaitMs = 0
                    armedPhraseEnd = false; armedTrailingMs = 0; preroll.clear()
                    lastPartialClockMs = 0
                }
                fun appendBounded(frame: FloatArray): Int {
                    val remaining = MAX_CAPTURE_SAMPLES - turnSamples
                    if (remaining <= 0) return 0
                    val accepted = minOf(frame.size, remaining)
                    speech.add(if (accepted == frame.size) frame else frame.copyOf(accepted))
                    turnSamples += accepted
                    turnMs = turnSamples * 1000 / MicRecorder.SAMPLE_RATE
                    return accepted
                }
                fun notifyManualCaptureLimit() {
                    if (captureLimitNotified) return
                    captureLimitNotified = true
                    emit(buildJsonObject { put("type", "session.capture.limit_reached") })
                }
                while (coroutineContext.isActive && running) {
                    // A retained opening frame is drained with the flag set so
                    // press paths cannot close the turn mid-prefix; the last
                    // drained frame applies the held press to the WHOLE prefix.
                    val drainingOpening = !openingInProgress && openingFrames.isNotEmpty()
                    val item = if (drainingOpening) openingFrames.removeFirst()
                    else kotlinx.coroutines.withTimeoutOrNull(25) { handoff.receiveCatching().getOrNull() }
                    val currentEpoch = captureEpoch.get()
                    if (currentEpoch != bufferEpoch) {
                        resetCapture()
                        // N05: a new capture epoch (mute toggles, session
                        // restart) invalidates the retained opening prefix
                        // too - stale frames must neither consume the new
                        // epoch's buffer capacity nor block the phrase that
                        // follows the unmute.
                        openingFrames.clear()
                        openingBufferedSamples = 0
                        openingCaptureFull = false
                        bufferEpoch = currentEpoch
                    }
                    if (item == null) {
                        // A manual end press may arrive after the final frame is
                        // already buffered. Wake from the bounded receive wait so
                        // it closes without requiring another microphone frame.
                        if (manualTurn && turnEndRequested && voicedInBuffer > 0 && !armedPhraseEnd && !drainingOpening) {
                            turnEndRequested = false
                            endOfSpeechAtMs = System.currentTimeMillis()
                            endBufferedTurn("manual end of turn (voice buffered)")
                            lastUsageEmit = maybeEmitUsage(lastUsageEmit)
                        }
                        continue
                    }
                    if (!coroutineContext.isActive || !running) break
                    if (openingInProgress && item is FrameHandoff.Audio) {
                        if (item.epoch == captureEpoch.get() && !muted && !speaking && !openingCaptureFull) {
                            val remaining = MAX_CAPTURE_SAMPLES - openingBufferedSamples
                            if (remaining > 0) {
                                val accepted = minOf(item.samples.size, remaining)
                                val retained = if (accepted == item.samples.size) item.samples else item.samples.copyOf(accepted)
                                openingFrames.addLast(FrameHandoff.Audio(retained, item.epoch))
                                openingBufferedSamples += accepted
                            }
                            if (openingBufferedSamples >= MAX_CAPTURE_SAMPLES || remaining <= 0) {
                                openingCaptureFull = true
                                // Saturating the opening buffer is only the
                                // memory bound: the retained prefix is still
                                // drained once the greeting ends, so this must
                                // not latch the turn-capture gate.
                                emit(buildJsonObject { put("type", "session.capture.limit_reached") })
                            }
                        }
                        continue
                    }
                    if (openingInProgress && item === FrameHandoff.Overflow) {
                        openingFrames.addLast(item)
                        continue
                    }
                    if (item === FrameHandoff.Flush) {
                        openingFrames.clear()
                        openingBufferedSamples = 0
                        openingCaptureFull = false
                        resetCapture()
                        continue
                    }
                    if (item === FrameHandoff.Overflow) {
                        if (manualTurn && voicedInBuffer > 0) {
                            notifyManualCaptureLimit()
                        } else if (!manualTurn && speechMs >= SPEECH_START_MS) {
                            endOfSpeechAtMs = System.currentTimeMillis()
                            endBufferedTurn("frame handoff overflow: preserving queued utterance prefix")
                        } else resetCapture()
                        discardUntilSilenceMs = OVERFLOW_SILENCE_BARRIER_MS
                        continue
                    }
                    item as FrameHandoff.Audio
                    if (item.epoch != captureEpoch.get() || muted || speaking) {
                        resetCapture()
                        continue
                    }

                    val frame = item.samples
                    val frameMs = frame.size * 1000 / MicRecorder.SAMPLE_RATE
                    if (manualTurn && !drainingOpening && speechMs == 0 && voicedInBuffer == 0) {
                        manualWaitMs += frameMs
                        if (manualWaitMs >= MAX_TURN_MS) {
                            if (turnEndRequested) turnEndRequested = false
                            resetCapture()
                            log("manual silent wait window reset — press the button to end a turn")
                            continue
                        }
                    }

                    // Manual mode: "He terminado" closes the turn immediately —
                    // but only when the buffer already carries VOICE (the user
                    // spoke, then pressed). A press during silence stays ARMED:
                    // never consumed by quiet air, and the next spoken phrase
                    // is captured whole (see the armed-phrase logic below) —
                    // within the 20 s window the press is never lost nor
                    // truncates the speech that follows it.
                    if (manualTurn && turnEndRequested && voicedInBuffer > 0 && !armedPhraseEnd && !drainingOpening) {
                        turnEndRequested = false
                        endOfSpeechAtMs = System.currentTimeMillis()
                        endBufferedTurn("manual end of turn (voice buffered)")
                        lastUsageEmit = maybeEmitUsage(lastUsageEmit)
                        continue
                    }
                    if (manualTurn && captureLimitNotified) continue

                    val rms = MicRecorder.rms(frame)
                    onLevels?.invoke(rms.toDouble(), 0.0)
                    if (discardUntilSilenceMs > 0) {
                        if (rms < END_TURN_RMS) discardUntilSilenceMs -= frameMs
                        else discardUntilSilenceMs = OVERFLOW_SILENCE_BARRIER_MS
                        if (discardUntilSilenceMs <= 0) {
                            discardUntilSilenceMs = 0
                            resetCapture()
                        }
                        continue
                    }
                    val voiced = rms >= END_TURN_RMS
                    if (voiced) voicedInBuffer += 1

                    val isSpeech = if (manualTurn) voiced else vad.observe(rms, speechMs > 0)
                    if (speechMs == 0 && !isSpeech) {
                        preroll.addLast(frame)
                        while (preroll.size > PRE_SPEECH_PREROLL_FRAMES) preroll.removeFirst()
                        if (manualTurn && turnEndRequested) {
                            // A press during silence remains armed for the next phrase.
                        }
                        continue
                    }
                    if (speechMs == 0) {
                        log("speech detected (rms=$rms, floor=${vad.floor})")
                        while (preroll.isNotEmpty()) appendBounded(preroll.removeFirst())
                    }
                    if (appendBounded(frame) == 0) {
                        if (manualTurn) notifyManualCaptureLimit() else {
                            endOfSpeechAtMs = System.currentTimeMillis()
                            endBufferedTurn("turn cap reached (${MAX_TURN_MS} ms)")
                        }
                        discardUntilSilenceMs = OVERFLOW_SILENCE_BARRIER_MS
                        continue
                    }
                    if (!manualTurn) {
                        speechMs += if (isSpeech) frameMs else 0
                        if (isSpeech) trailingSilenceMs = 0 else trailingSilenceMs += frameMs
                        val turnComplete = speechMs >= SPEECH_START_MS && trailingSilenceMs >= silenceEndMs
                        val capReached = turnMs >= MAX_TURN_MS
                        if (turnComplete || capReached) {
                            endOfSpeechAtMs = System.currentTimeMillis()
                            if (turnComplete) log("end of speech detected (${speechMs} ms spoken, ${trailingSilenceMs} ms silent)")
                            else log("turn cap reached (${turnMs} ms): closing the turn")
                            endBufferedTurn("turn complete")
                            lastUsageEmit = maybeEmitUsage(lastUsageEmit)
                            continue
                        }
                        partialClockMs = speechMs
                    } else {
                        speechMs += if (voiced) frameMs else 0
                        partialClockMs = turnMs
                        if (!drainingOpening && turnEndRequested && voiced && voicedInBuffer == 1 && !armedPhraseEnd) {
                            // An armed press catching the phrase start: hold the
                            // request and close when the phrase trails into
                            // silence, so the whole post-press speech is
                            // captured instead of a single voiced frame.
                            armedPhraseEnd = true
                            armedTrailingMs = 0
                        }
                        if (armedPhraseEnd) {
                            if (voiced) armedTrailingMs = 0 else armedTrailingMs += frameMs
                            if (armedTrailingMs >= ARMED_PHRASE_END_MS) {
                                turnEndRequested = false
                                endOfSpeechAtMs = System.currentTimeMillis()
                                endBufferedTurn("manual end of turn (armed phrase ended)")
                                lastUsageEmit = maybeEmitUsage(lastUsageEmit)
                                continue
                            }
                        }
                        if (turnMs >= MAX_TURN_MS) {
                            armedPhraseEnd = false; armedTrailingMs = 0
                            notifyManualCaptureLimit()
                        }
                    }
                    maybeEmitPartial(speech, partialClockMs)
                    if (System.currentTimeMillis() - lastUsageEmit >= 15_000) {
                        lastUsageEmit = maybeEmitUsage(lastUsageEmit)
                    }
                    if (drainingOpening && openingFrames.isEmpty() &&
                        manualTurn && turnEndRequested && voicedInBuffer > 0 && !armedPhraseEnd) {
                        // the retained opening prefix is fully consumed: the
                        // press held since the greeting now applies to the
                        // whole prefix, not just its first voiced frame
                        turnEndRequested = false
                        endOfSpeechAtMs = System.currentTimeMillis()
                        endBufferedTurn("manual end of turn (opening prefix drained)")
                        lastUsageEmit = maybeEmitUsage(lastUsageEmit)
                    }
                }
            } finally {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        captureJob.cancelAndJoin()
                    }
                } finally {
                    handoff.close()
                    recorder = null
                    source.stop()
                }
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
            try {
                val stt = stt.transcribe(MicRecorder.trimSilence(samples), sessionLanguage)
                if (stt.isEmpty) return@launchSessionJob
                if (stt.text == lastPartialText) return@launchSessionJob
                lastPartialText = stt.text
                logContent("partial: \"${stt.text}\"")
                emit(buildJsonObject {
                    put("type", "session.input_transcript.partial")
                    put("event_id", UUID.randomUUID().toString())
                    put("delta", stt.text)
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Partials are provisional UI text; a failure must not fail the
                // turn — but it must be discoverable (e.g. a missing whisper
                // model otherwise just looks like "captions never appear").
                log("partial STT failed: ${e.message}")
            }
        }
    }

    private fun flattenFrames(frames: List<FloatArray>): FloatArray {
        val samples = FloatArray(frames.sumOf { it.size })
        var offset = 0
        for (frame in frames) {
            frame.copyInto(samples, destinationOffset = offset)
            offset += frame.size
        }
        return samples
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
    /** Pause: stop any in-flight TTS playback (the mic pause is mute's job). */
    fun stopPlayback() {
        tts.stop()
    }

    /** Speak a passage through the session TTS with the same mic suppression as
        a reply, so the replay cannot feed the microphone. */
    suspend fun replay(text: String) {
        speakSuppressed(text)
    }

    private suspend fun speakSuppressed(text: String): com.jkdc.app.engines.local.TtsStats? {
        val source = recorder
        val suppressMic = !bargeIn
        if (source != null && suppressMic) source.pause()
        speakingDepth.incrementAndGet()
        speaking = true
        try {
            return tts.speak(text)
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

    /**
     * Final turn transcription: the cloud service when the user enabled it and
     * it is available, always the local whisper pass on failure or when
     * disabled. Partial transcriptions never go to the cloud — they run every
     * couple of seconds and are provisional UI text only.
     */
    private suspend fun transcribeTurn(samples: FloatArray, language: String): SttResult {
        val selected = cloudSttSelection()
        val cloud = selected?.let { cloudStts[it] }
        if (cloud != null) {
            try {
                val result = cloud.transcribe(samples, language)
                logContent("cloud[$selected] STT done in ${result.processingMs} ms: \"${result.text.take(80)}\"")
                return result
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.w(LOG_TAG, "cloud[$selected] STT failed (${e.javaClass.simpleName}), falling back to local: ${e.message}")
            }
        }
        return stt.transcribe(samples, language)
    }

    private suspend fun processTurn(samples: FloatArray) {
        partialJob?.cancel()
        partialJob = null
        endOfSpeechAtMs = System.currentTimeMillis()
        log("turn end: captured ${samples.size * 1000L / MicRecorder.SAMPLE_RATE} ms of audio")
        val t0 = System.currentTimeMillis()
        val trimmed = MicRecorder.trimSilence(samples)
        val maxSamples = MAX_STT_INPUT_MS * MicRecorder.SAMPLE_RATE / 1000
        val capped = if (trimmed.size > maxSamples) {
            val duration = trimmed.size * 1000L / MicRecorder.SAMPLE_RATE
            log("STT input capped to the first $MAX_STT_INPUT_MS ms of $duration ms")
            // The cut is decided at finalize (capture stayed under its own
            // cap): surface it through the same persistent notice instead of
            // silently dropping the tail (one signal per turn).
            emit(buildJsonObject { put("type", "session.capture.limit_reached") })
            trimmed.copyOfRange(0, maxSamples)
        } else trimmed
        val durationMs = capped.size * 1000L / MicRecorder.SAMPLE_RATE
        if (durationMs < 250) {
            log("turn discarded: only ${durationMs} ms of usable audio (noise blip)")
            clearPartial()
            return
        }
        log("STT start (${durationMs} ms of audio, language='$sessionLanguage')")
        // Note: the whisper JNI call cannot be interrupted on cancellation; the
        // recorder stop in stopLoop() is what protects the microphone here.
        val stt = try {
            transcribeTurn(capped, sessionLanguage)
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

        val conversationSoFar = synchronized(rollingTurns) { rollingTurns.joinToString("\n") { it.render() } }
        val system = buildString {
            append(baseInstructions)
            synchronized(appendedContext) {
                while (appendedContext.isNotEmpty()) append("\n").append(appendedContext.removeFirst())
            }
            if (conversationSoFar.isNotBlank()) {
                append("\n\nConversation so far (most recent last):")
                append("\n").append(conversationSoFar)
            }
        }
        appendConversationMessage("User", stt.text)
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
        if (!running) return
        logContent("LLM done in ${System.currentTimeMillis() - llmT0} ms (${result.usage.input}+${result.usage.output} tok): \"${result.text.take(120)}\"")
        if (result.text.isBlank()) return
        appendConversationMessage("Assistant", result.text)

        val replyStart = relativeMs()
        emit(transcriptEvent(isInput = false, text = result.text, startMs = replyStart, endMs = replyStart + 500))

        log("TTS start")
        val ttsT0 = System.currentTimeMillis()
        try {
            val stats = speakSuppressed(result.text)
            if (stats != null) log("TTS speaking in ${stats.startLatencyMs} ms — first audio ${(ttsT0 + stats.startLatencyMs) - endOfSpeechAtMs} ms after end of speech")
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
            type.startsWith("session.user_message.append") -> {
                appendConversationMessage("User", content)
                return true
            }
            type.startsWith("session.instructions.append") || type.startsWith("session.thinking.append") -> {
                synchronized(appendedContext) {
                    appendedContext.addLast(content.take(MAX_CONTEXT_MESSAGE_CHARS))
                    while (appendedContext.size > MAX_ROLLING_TURNS) appendedContext.removeFirst()
                }
                if (type.startsWith("session.instructions.append") && openOnStart) openingReady.complete(Unit)
                return true
            }
            type.startsWith("session.commentary.append") -> {
                appendConversationMessage("Assistant", content)
                val start = relativeMs()
                emit(transcriptEvent(isInput = false, text = content, startMs = start, endMs = start + 500))
                launchSessionJob(Dispatchers.IO) {
                    try {
                        speakSuppressed(content)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        // A silent assistant is worse than a failed one: the
                        // user typed commentary and heard nothing.
                        log("commentary speech failed: ${e.message}")
                        onFailure?.invoke("Could not speak the commentary: ${e.message ?: "unknown error"}")
                    }
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
    internal fun renderHistory(history: JsonArray): String {
        return parseHistoryMessages(history).joinToString("\n") { it.render() }
    }

    private fun parseHistoryMessages(history: JsonArray): List<ConversationMessage> {
        val messages = ArrayDeque<ConversationMessage>()
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
            messages.addLast(ConversationMessage(role.replaceFirstChar { it.uppercase() }, text.take(MAX_CONTEXT_MESSAGE_CHARS)))
            while (messages.size > MAX_ROLLING_TURNS) messages.removeFirst()
        }
        return messages.toList()
    }

    private fun appendConversationMessage(role: String, text: String) {
        synchronized(rollingTurns) {
            rollingTurns.addLast(ConversationMessage(role, text.take(MAX_CONTEXT_MESSAGE_CHARS)))
            while (rollingTurns.size > MAX_ROLLING_TURNS) rollingTurns.removeFirst()
        }
    }



    override fun mute(muted: Boolean) {
        if (this.muted != muted) captureEpoch.incrementAndGet()
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
        const val MAX_TURN_MS = 20_000
        private const val MAX_CONTEXT_MESSAGE_CHARS = 2_000
        private const val PRE_SPEECH_PREROLL_FRAMES = 3
        private const val MAX_CAPTURE_SAMPLES = MAX_TURN_MS * MicRecorder.SAMPLE_RATE / 1000
        private const val FRAME_QUEUE_CAPACITY = 8
        private const val OVERFLOW_SILENCE_BARRIER_MS = 384
        /** Voice energy an armed "He terminado" press waits for before closing. */
        const val END_TURN_RMS = 0.02f
        /** Whisper's cost scales with input length; a capped turn is mostly
            trailing noise, so STT never sees more than this trailing window. */
        private const val MAX_STT_INPUT_MS = 18_000
        /** Armed press: silence this long after the post-press phrase's voice
            closes the turn — the phrase is captured whole, not on its first
            voiced frame (which trims to <250 ms and would be discarded). */
        private const val ARMED_PHRASE_END_MS = 700

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
