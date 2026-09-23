package com.jkdc.app

import com.jkdc.app.core.ConversationHistory
import com.jkdc.app.core.Fragment
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.Speaker
import com.jkdc.app.core.voice.LocalVoiceTransport
import com.jkdc.app.engines.local.AudioFrameSource
import com.jkdc.app.engines.local.SpeechSink
import com.jkdc.app.engines.local.TtsStats
import com.jkdc.app.engines.local.WhisperRuntime
import com.jkdc.app.engines.local.WhisperStt
import com.jkdc.app.network.APIClient
import com.jkdc.app.network.TeachingClient
import java.util.concurrent.CountDownLatch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class LocalVoiceTransportTest {

    /**
     * Models a recording source honestly: [readFrame] is non-blocking and a
     * null means "nothing buffered right now", never "end of audio". The
     * stale-audio discard is the explicit [drainStale] contract, not a
     * read-until-null loop — a loop the real (non-blocking) source cannot
     * express and this fake could not disprove.
     */
    private class FakeFrameSource : AudioFrameSource {
        val queue = ArrayDeque<FloatArray>()
        var started = false
        var stopped = false
        var paused = false
        var resumeCount = 0
        var drainedFrames = 0
        override fun start(): Boolean { started = true; return true }
        override fun readFrame(): FloatArray? { if (paused) return null; return queue.removeFirstOrNull() }
        override fun stop() { stopped = true }
        override fun pause() { paused = true }
        override fun drainStale() { drainedFrames += queue.size; queue.clear() }
        override fun resume(): Boolean { paused = false; resumeCount++; return true }
    }

    private class FakeWhisperRuntime : WhisperRuntime {
        var transcription = "Hello there"
        override fun init(modelPath: String): Long = 7L
        override fun transcribe(handle: Long, samples: FloatArray, numSamples: Int, threads: Int, language: String): String = transcription
        override fun free(handle: Long) {}
    }

    private class FakeTeachingClient : TeachingClient {
        val received = mutableListOf<Pair<String, String>>() // system to user
        val reply = "Nice! Tell me more about it."
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var cancelCalls = 0
        override fun cancelInFlight() { cancelCalls++ }
        override suspend fun respond(instructions: String, input: String, schema: kotlinx.serialization.json.JsonObject?, search: Boolean, purpose: com.jkdc.app.network.HelperPurpose?): com.jkdc.app.network.APIResult {
            received.add(instructions to input)
            gate?.await()
            return com.jkdc.app.network.APIResult(text = reply, sources = emptyList(), usage = com.jkdc.app.network.APIUsage(10, 5))
        }
    }

    private class FakeTts(private val usable: Boolean = true) : SpeechSink {
        val spoken = mutableListOf<String>()
        val rates = mutableListOf<Float>()
        val languages = mutableListOf<String>()
        var languageResult = true
        var stopCalls = 0
        var initialized = false
        var onSpeak: (() -> Unit)? = null
        override suspend fun initialize(): Boolean { initialized = true; return usable }
        override suspend fun speak(text: String): TtsStats? { spoken.add(text); onSpeak?.invoke(); return TtsStats(1, 1) }
        override fun stop() { stopCalls++ }
        override fun setSpeechRate(rate: Float) { rates.add(rate) }
        override fun setLanguage(languageTag: String): Boolean { languages.add(languageTag); return languageResult }
    }

    private fun stt(tempDir: File, runtime: FakeWhisperRuntime): WhisperStt {
        val dir = File(tempDir, "whisper").apply { mkdirs() }
        File(dir, "ggml-base-q5_1.bin").writeBytes(ByteArray(8))
        return WhisperStt(dir, runtime = runtime)
    }

    private fun silence(): FloatArray = FloatArray(2048)
    private fun speech(): FloatArray = FloatArray(2048) { i -> (0.5 * sin(2.0 * Math.PI * 220 * i / 16000.0)).toFloat() }

    private fun events(runs: (LocalVoiceTransport) -> Unit): Pair<MutableList<JsonObject>, FakeTts> {
        val events = mutableListOf<JsonObject>()
        val source = FakeFrameSource()
        val sttRuntime = FakeWhisperRuntime()
        val engine = FakeTeachingClient()
        val tts = FakeTts()
        val transport = LocalVoiceTransport(
            context = null as android.content.Context?,
            scope = CoroutineScope(Dispatchers.IO),
            stt = stt(tempDir = createTempDir(), runtime = sttRuntime),
            engine = engine,
            tts = tts,
            frameSourceProvider = { source },
            frameDelayMs = 0,
        )
        transport.onEvent = { events.add(it) }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }
        runs(transport)
        return events to tts
    }

    @Test
    fun slowSpeechPreferenceConfiguresTheTtsRate() {
        val source = FakeFrameSource()
        val tts = FakeTts()
        val transport = LocalVoiceTransport(
            context = null as android.content.Context?,
            scope = CoroutineScope(Dispatchers.IO),
            stt = stt(tempDir = createTempDir(), runtime = FakeWhisperRuntime()),
            engine = FakeTeachingClient(),
            tts = tts,
            frameSourceProvider = { source },
            frameDelayMs = 0,
        )
        transport.speechRate = LocalVoiceTransport.TTS_SLOW_RATE
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }
        assertEquals(listOf(LocalVoiceTransport.TTS_SLOW_RATE), tts.rates)
        transport.close()
    }

    @Test
    fun defaultPaceConfiguresNormalTtsRate() {
        val source = FakeFrameSource()
        val tts = FakeTts()
        val transport = LocalVoiceTransport(
            context = null as android.content.Context?,
            scope = CoroutineScope(Dispatchers.IO),
            stt = stt(tempDir = createTempDir(), runtime = FakeWhisperRuntime()),
            engine = FakeTeachingClient(),
            tts = tts,
            frameSourceProvider = { source },
            frameDelayMs = 0,
        )
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }
        assertEquals(listOf(LocalVoiceTransport.TTS_NORMAL_RATE), tts.rates)
        transport.close()
    }

    class FakeProvider : com.jkdc.app.network.LiveSessionProvider {
        override suspend fun createLiveSession(request: com.jkdc.app.network.LiveSessionRequest) =
            throw UnsupportedOperationException()
    }

    private fun awaitLatch(latch: CountDownLatch, seconds: Long = 10): Boolean = latch.await(seconds, TimeUnit.SECONDS)

    // ---- tests ------------------------------------------------------------------

    @Test
    fun `connect emits session created and started`() {
        val (events, tts) = events { }
        assertEquals("app.session.created", events[0]["type"]!!.jsonPrimitive.content)
        assertEquals("session.started", events[1]["type"]!!.jsonPrimitive.content)
        assertTrue(tts.initialized)
    }

    @Test
    fun `spoken turn produces input transcript, reply, and speech`() {
        val latch = CountDownLatch(2) // input + output transcript events
        val source = FakeFrameSource()
        val sttRuntime = FakeWhisperRuntime()
        val engine = FakeTeachingClient()
        val tts = FakeTts()
        val events = mutableListOf<JsonObject>()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), sttRuntime), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { e ->
            events.add(e)
            val type = e["type"]?.jsonPrimitive?.content
            if (type == "session.input_transcript.delta" || type == "session.output_transcript.delta") latch.countDown()
        }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }

        // script a spoken turn: 4 speech frames then 10 silent frames
        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        assertTrue(awaitLatch(latch))

        val input = events.first { it["type"]?.jsonPrimitive?.content == "session.input_transcript.delta" }
        assertEquals("Hello there", input["delta"]!!.jsonPrimitive.content)
        val output = events.first { it["type"]?.jsonPrimitive?.content == "session.output_transcript.delta" }
        assertEquals("Nice! Tell me more about it.", output["delta"]!!.jsonPrimitive.content)
        val ttsDeadline = System.currentTimeMillis() + 5_000
        while (tts.spoken.isEmpty() && System.currentTimeMillis() < ttsDeadline) Thread.sleep(50)
        assertEquals(listOf("Nice! Tell me more about it."), tts.spoken)
        assertEquals("Hello there", engine.received.single().second)
        assertTrue(engine.received.single().first.contains("Be a coach"))
    }

    @Test
    fun `silence produces no turns and no llm calls`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val tts = FakeTts()
        val events = mutableListOf<JsonObject>()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { events.add(it) }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }
        repeat(40) { source.queue.add(silence()) }
        Thread.sleep(900) // let the loop drain
        assertEquals(0, engine.received.size)
        assertTrue(events.none { (it["type"]?.jsonPrimitive?.content ?: "").contains("transcript") })
    }

    @Test
    fun `commentary append is spoken and emitted as assistant transcript`() {
        val latch = CountDownLatch(3)
        val tts = FakeTts()
        val events = mutableListOf<JsonObject>()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), FakeTeachingClient(), tts,
            frameSourceProvider = { FakeFrameSource() }, frameDelayMs = 0,
        )
        transport.onEvent = { e ->
            events.add(e)
            if (latch.count > 0) latch.countDown()
        }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }
        assertTrue(transport.send(buildAppend("session.commentary.append", "Hello from typed input")))
        assertTrue(awaitLatch(latch))
        val deadline = System.currentTimeMillis() + 5_000
        while (tts.spoken.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("Hello from typed input", tts.spoken.single())
        assertTrue(events.any { (it["type"]?.jsonPrimitive?.content ?: "") == "session.output_transcript.delta" &&
            it["delta"]!!.jsonPrimitive.content == "Hello from typed input" })
    }

    @Test
    fun `mute drops frames and produces no turns`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }
        transport.mute(true)
        repeat(25) { source.queue.add(speech()) }
        Thread.sleep(900)
        assertEquals(0, engine.received.size)
    }

    @Test
    fun `rolling context carries previous turns into the next prompt`() {
        val source = FakeFrameSource()
        val sttRuntime = FakeWhisperRuntime()
        sttRuntime.transcription = "First sentence"
        val engine = FakeTeachingClient()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), sttRuntime), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }

        // Turn 1
        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        val turn1 = CountDownLatch(1)
        transport.onEvent = { e ->
            if ((e["type"]?.jsonPrimitive?.content ?: "") == "session.output_transcript.delta") turn1.countDown()
        }
        // re-arm: latch above set after connect; wait manually for turn1 output instead
        Thread.sleep(300)
        val deadline1 = System.currentTimeMillis() + 5_000
        while (engine.received.size < 1 && System.currentTimeMillis() < deadline1) Thread.sleep(25)

        // Turn 2 with different transcription
        sttRuntime.transcription = "Second sentence"
        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        val deadline2 = System.currentTimeMillis() + 5_000
        while (engine.received.size < 2 && System.currentTimeMillis() < deadline2) Thread.sleep(25)

        assertTrue(engine.received.size >= 2)
        val system2 = engine.received[1].first
        assertTrue("system prompt must contain turn 1 user text", system2.contains("User: First sentence"))
        assertTrue("system prompt must contain turn 1 reply", system2.contains("Assistant: Nice! Tell me more about it."))
    }

    @Test
    fun `renderHistory reads text parts from ConversationHistory messages`() {
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), FakeTeachingClient(), FakeTts(),
            frameSourceProvider = { FakeFrameSource() }, frameDelayMs = 0,
        )
        val session = SessionRecord(languageID = "es").apply {
            append(Fragment(speaker = Speaker.user, text = "Hi", startMS = 0, endMS = 1))
            append(Fragment(speaker = Speaker.assistant, text = "Hello", startMS = 2, endMS = 3))
        }
        val rendered = transport.renderHistory(ConversationHistory.messages(session))
        assertTrue(rendered.contains("User: Hi"))
        assertTrue(rendered.contains("Assistant: Hello"))
    }

    @Test
    fun `renderHistory skips non text parts and still reads plain string content`() {
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), FakeTeachingClient(), FakeTts(),
            frameSourceProvider = { FakeFrameSource() }, frameDelayMs = 0,
        )
        val history = JsonArray(listOf(
            // ConversationHistory.messages() shape: content is an array of typed parts
            buildJsonObject {
                put("type", "message"); put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "input_audio"); put("audio", "audio-id") }) // no text: skipped
                    add(buildJsonObject { put("type", "input_text"); put("text", "Hi") })
                }
            },
            // tolerate legacy/hand-built plain string content
            buildJsonObject { put("role", "assistant"); put("content", JsonPrimitive("Hello")) },
        ))
        val rendered = transport.renderHistory(history)
        assertTrue(rendered.contains("User: Hi"))
        assertFalse("non-text parts must not leak into the transcript", rendered.contains("audio-id"))
        assertTrue(rendered.contains("Assistant: Hello"))
    }

    @Test
    fun `continuous speech past the 30 second cap is processed without manual end`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        // 250 frames of continuous speech = 32 s. The VAD alone cannot close the
        // turn (no trailing silence); the 30 s cap must.
        repeat(250) { source.queue.add(speech()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals("the turn cap must end the turn without manual end-of-turn", 1, engine.received.size)
        assertEquals("Hello there", engine.received.single().second)
    }

    @Test
    fun `audio buffered while speaking does not become a phantom turn`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val staleQueued = CountDownLatch(1)
        val tts = FakeTts()
        tts.onSpeak = {
            // the mic "captures" while TTS plays: loud speech plus enough trailing
            // silence (12 x 128 ms) that the VAD would close a phantom turn
            repeat(6) { source.queue.add(speech()) }
            repeat(12) { source.queue.add(silence()) }
            staleQueued.countDown()
        }
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        // a normal spoken turn first
        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(1, engine.received.size)
        assertTrue("stale frames must be queued during (fake) playback", staleQueued.await(5, TimeUnit.SECONDS))

        // audio buffered while speaking must be discarded on resume: no second turn
        Thread.sleep(1_500)
        assertEquals("no phantom turn may start from stale buffered audio", 1, engine.received.size)
        assertTrue("the recorder must be paused around playback", source.resumeCount >= 1)
        assertEquals("exactly the 18 frames captured during playback must be drained",
            18, source.drainedFrames)
    }

    @Test
    fun `pure idle silence never trips the turn cap into a phantom stt pass`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        // 250 frames = 32 s of pure silence: past the 30 s cap, but with no
        // speech buffered the cap must not open an STT pass (trimSilence keeps
        // all-silence audio whole, so an unguarded cap would reach the LLM).
        repeat(250) { source.queue.add(silence()) }
        Thread.sleep(1_500)
        assertEquals(0, engine.received.size)
    }

    @Test
    fun `tts init failure fails the session loudly instead of text-only turns`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val tts = FakeTts(usable = false)
        val events = mutableListOf<JsonObject>()
        val failures = mutableListOf<String>()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onFailure = { failures.add(it) }
        transport.onEvent = { events.add(it) }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        assertTrue("TTS init failure must surface a failure with a reason", failures.isNotEmpty() && failures[0].isNotBlank())
        assertFalse("the session must not start listening", source.started)
        assertFalse("the session must not be marked started", transport.started)
        assertTrue(events.none { it["type"]?.jsonPrimitive?.content == "session.started" })

        // frames arriving afterwards never produce turns
        repeat(10) { source.queue.add(speech()) }
        Thread.sleep(400)
        assertEquals(0, engine.received.size)
    }


    @Test
    fun `cancelling mid-turn does not surface a cancellation failure`() {
        val source = FakeFrameSource()
        val sttRuntime = FakeWhisperRuntime()
        val engine = FakeTeachingClient()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.gate = gate
        val tts = FakeTts()
        val failures = mutableListOf<String>()
        val scope = CoroutineScope(Dispatchers.IO)
        val transport = LocalVoiceTransport(
            null as android.content.Context?, scope,
            stt(createTempDir(), sttRuntime), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onFailure = { failures.add(it) }
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)

        // End/timeout mid-generation cancels the transport job.
        transport.close()
        val releaseDeadline = System.currentTimeMillis() + 5_000
        gate.complete(Unit)
        while (failures.isEmpty() && System.currentTimeMillis() < releaseDeadline) Thread.sleep(25)

        assertTrue("cancellation must not be reported as failure: $failures", failures.isEmpty())
        scope.cancel()
    }

    @Test
    fun `manual mode holds the turn until endTurn is requested`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val tts = FakeTts()
        val events = mutableListOf<JsonObject>()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime().apply { transcription = "Habla lenta" }), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.manualTurn = true
        transport.onEvent = { events.add(it) }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        // habla + silencio muy por encima del umbral automático: NO debe cerrar el turno
        repeat(4) { source.queue.add(speech()) }
        repeat(30) { source.queue.add(silence()) }
        Thread.sleep(700)
        assertEquals(0, engine.received.size)
        // Provisional partials may stream, but the stored transcript waits for "He terminado".
        assertTrue(events.none { (it["type"]?.jsonPrimitive?.content ?: "") == "session.input_transcript.delta" })

        // el usuario pulsa "He terminado"
        transport.endTurn()
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)

        assertEquals(1, engine.received.size)
        assertEquals("Habla lenta", engine.received[0].second)
        val ttsDeadline = System.currentTimeMillis() + 5_000
        while (tts.spoken.isEmpty() && System.currentTimeMillis() < ttsDeadline) Thread.sleep(25)
        assertEquals("Nice! Tell me more about it.", tts.spoken.single())
        assertTrue(events.any { (it["type"]?.jsonPrimitive?.content ?: "") == "session.input_transcript.delta" })
    }

    @Test
    fun `custom silence window gates the auto turn`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime().apply { transcription = "Lenta" }), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.silenceEndMs = 3_000
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        // habla + 1,3 s de silencio: con la ventana por defecto (1,2 s) cerraría; con 3 s no
        repeat(4) { source.queue.add(speech()) }
        repeat(12) { source.queue.add(silence()) }
        Thread.sleep(500)
        assertEquals(0, engine.received.size)

        // superar los 3 s de silencio cierra el turno
        repeat(14) { source.queue.add(silence()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(1, engine.received.size)
    }

    @Test
    fun `ending during an in-flight greeting leaves no stale output in the restarted session`() {
        val source = FakeFrameSource()
        val sttRuntime = FakeWhisperRuntime()
        val engine = FakeTeachingClient()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.gate = gate
        val tts = FakeTts()
        val events = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        val scope = CoroutineScope(Dispatchers.IO)
        val transport = LocalVoiceTransport(
            null as android.content.Context?, scope,
            stt(createTempDir(), sttRuntime), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
            openOnStart = true,
        )
        transport.onEvent = { events.add(it) }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }

        // The greeting is parked inside engine.respond when the user ends the
        // session; the cancellation must land while it is still suspended.
        transport.close()
        Thread.sleep(200)
        gate.complete(Unit)
        Thread.sleep(200)

        // Restart on the same instance: exactly one greeting (the new one) may
        // emit and speak — a ghost of the cancelled one must not leak through.
        events.clear()
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }
        val deadline = System.currentTimeMillis() + 5_000
        while (tts.spoken.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        Thread.sleep(300) // give a hypothetical ghost greeting time to leak
        val outputDeltas = events.filter {
            (it["type"]?.jsonPrimitive?.content ?: "") == "session.output_transcript.delta"
        }
        assertEquals("only the restarted session's greeting may emit an output delta", 1, outputDeltas.size)
        assertEquals("the ghost greeting must not speak into the new session", 1, tts.spoken.size)
        scope.cancel()
    }

    @Test
    fun `manual mode never auto-processes and only the button ends a turn`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val scope = CoroutineScope(Dispatchers.IO)
        val transport = LocalVoiceTransport(
            null as android.content.Context?, scope,
            stt(createTempDir(), FakeWhisperRuntime()), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.manualTurn = true
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        // 250 loud frames = 32 s of speech: below the 60 s manual safety cap the
        // turn must NOT auto-process — previously ambient noise rode the cap and
        // re-processed the app's own voice in a loop.
        repeat(250) { source.queue.add(speech()) }
        Thread.sleep(500)
        assertEquals("manual mode must not auto-process below the safety cap", 0, engine.received.size)

        // Only the end-turn button (the beginner-mode contract) closes the turn.
        transport.endTurn()
        val deadline2 = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline2) Thread.sleep(25)
        assertEquals("endTurn must process the open turn", 1, engine.received.size)

        // The idle window after the turn resets silently: silence must not
        // produce a ghost turn.
        val resumed = System.currentTimeMillis() + 5_000
        while (source.resumeCount < 1 && System.currentTimeMillis() < resumed) Thread.sleep(25)
        repeat(170) { source.queue.add(silence()) }
        Thread.sleep(1_500)
        assertEquals("an idle manual window must reset without processing", 1, engine.received.size)
        scope.cancel()
    }

    @Test
    fun `disconnect cancels in-flight engine work and stops the recorder promptly`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.gate = gate
        val scope = CoroutineScope(Dispatchers.IO)
        val transport = LocalVoiceTransport(
            null as android.content.Context?, scope,
            stt(createTempDir(), FakeWhisperRuntime()), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        // A turn parked in a (blocking) LLM call when the user ends the session.
        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        Thread.sleep(100) // let the turn reach the gated engine.respond

        transport.close()

        assertEquals("end must cancel in-flight engine work", 1, engine.cancelCalls)
        assertTrue("the recorder must be stopped without waiting for the call to return", source.stopped)
        gate.complete(Unit)
        scope.cancel()
    }

    @Test
    fun `an endTurn pressed during an automatic session does not arm a later manual session`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime().apply { transcription = "Lenta" }), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        // "He terminado" pressed during the automatic session is ignored there…
        transport.endTurn()
        Thread.sleep(100)
        transport.close()

        // …but it must not instantly close the first turn of the next session.
        transport.manualTurn = true
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }
        repeat(4) { source.queue.add(speech()) }
        repeat(12) { source.queue.add(silence()) }
        Thread.sleep(700)
        assertEquals("a stale endTurn must not close the first manual turn", 0, engine.received.size)

        // A fresh "He terminado" closes the turn normally.
        transport.endTurn()
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(1, engine.received.size)
    }

    private fun buildAppend(type: String, content: String): JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("type", type)
        put("content", kotlinx.serialization.json.JsonPrimitive(content))
    }

    @Test
    fun `partial transcripts stream while the turn is open and stay provisional`() {
        val events = mutableListOf<JsonObject>()
        val source = FakeFrameSource()
        val transport = LocalVoiceTransport(
            context = null as android.content.Context?,
            scope = CoroutineScope(Dispatchers.IO),
            stt = stt(tempDir = createTempDir(), runtime = FakeWhisperRuntime()),
            engine = FakeTeachingClient(),
            tts = FakeTts(),
            frameSourceProvider = { source },
            frameDelayMs = 0,
        )
        transport.onEvent = { events.add(it) }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }

        // ~5 s of speech: crosses the 1 s minimum and one 2 s cadence step.
        repeat(40) { source.queue.add(speech()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (events.none { it["type"]?.jsonPrimitive?.content == "session.input_transcript.partial" } &&
            System.currentTimeMillis() < deadline) Thread.sleep(25)
        val partials = events.filter { it["type"]?.jsonPrimitive?.content == "session.input_transcript.partial" }
        assertTrue("at least one partial", partials.isNotEmpty())
        assertEquals("Hello there", partials.first()["delta"]?.jsonPrimitive?.content)

        // Silence closes the turn; the final transcription arrives as a normal input delta.
        repeat(14) { source.queue.add(silence()) }
        val finalDeadline = System.currentTimeMillis() + 5_000
        while (events.none { it["type"]?.jsonPrimitive?.content == "session.input_transcript.delta" } &&
            System.currentTimeMillis() < finalDeadline) Thread.sleep(25)
        assertTrue(events.any { it["type"]?.jsonPrimitive?.content == "session.input_transcript.delta" })
        transport.close()
    }

    @Test
    fun `barge-in stops playback and never buffers the interrupted speech as a turn`() {
        val events = mutableListOf<JsonObject>()
        val source = FakeFrameSource()
        val tts = FakeTts()
        val engine = FakeTeachingClient()
        val playback = CountDownLatch(1)
        tts.onSpeak = { playback.await() } // speak() blocks: the transport stays "speaking"
        val transport = LocalVoiceTransport(
            context = null as android.content.Context?,
            scope = CoroutineScope(Dispatchers.IO),
            stt = stt(tempDir = createTempDir(), runtime = FakeWhisperRuntime()),
            engine = engine,
            tts = tts,
            frameSourceProvider = { source },
            frameDelayMs = 0,
        )
        transport.bargeIn = true
        transport.onEvent = { events.add(it) }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }

        // Typed commentary starts playback through speakSuppressed.
        transport.send(buildAppend("session.commentary.append", "Claro, te cuento"))
        val spokenDeadline = System.currentTimeMillis() + 5_000
        while (tts.spoken.isEmpty() && System.currentTimeMillis() < spokenDeadline) Thread.sleep(25)
        assertEquals(1, tts.spoken.size)

        // Sustained loud energy during playback (barge window) must stop the TTS.
        repeat(8) { source.queue.add(speech()) }
        val stopDeadline = System.currentTimeMillis() + 5_000
        while (tts.stopCalls == 0 && System.currentTimeMillis() < stopDeadline) Thread.sleep(25)
        assertTrue("barge-in stops the playback", tts.stopCalls >= 1)
        playback.countDown()

        // The frames captured during playback were dropped, never buffered.
        repeat(14) { source.queue.add(silence()) }
        Thread.sleep(400)
        assertTrue(events.none { it["type"]?.jsonPrimitive?.content == "session.input_transcript.delta" })
        assertEquals("no turn reached the engine", 0, engine.received.size)
        transport.close()
    }

    @Test
    fun `without barge-in the mic is paused during playback like before`() {
        val source = FakeFrameSource()
        val tts = FakeTts()
        val playback = CountDownLatch(1)
        tts.onSpeak = { playback.await() }
        val transport = LocalVoiceTransport(
            context = null as android.content.Context?,
            scope = CoroutineScope(Dispatchers.IO),
            stt = stt(tempDir = createTempDir(), runtime = FakeWhisperRuntime()),
            engine = FakeTeachingClient(),
            tts = tts,
            frameSourceProvider = { source },
            frameDelayMs = 0,
        )
        transport.bargeIn = false
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }
        transport.send(buildAppend("session.commentary.append", "Claro"))
        val spokenDeadline = System.currentTimeMillis() + 5_000
        while (tts.spoken.isEmpty() && System.currentTimeMillis() < spokenDeadline) Thread.sleep(25)
        assertTrue("capture paused during playback", source.paused)
        playback.countDown()
        val resumeDeadline = System.currentTimeMillis() + 5_000
        while (source.resumeCount == 0 && System.currentTimeMillis() < resumeDeadline) Thread.sleep(25)
        assertEquals(1, source.resumeCount)
        transport.close()
    }

    @Test
    fun `connect feeds the session language to the TTS`() {
        val tts = FakeTts()
        val transport = LocalVoiceTransport(
            context = null as android.content.Context?,
            scope = CoroutineScope(Dispatchers.IO),
            stt = stt(tempDir = createTempDir(), runtime = FakeWhisperRuntime()),
            engine = FakeTeachingClient(),
            tts = tts,
            frameSourceProvider = { FakeFrameSource() },
            frameDelayMs = 0,
        )
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "es") }
        assertEquals(listOf("es"), tts.languages)
        transport.close()
    }

    @Test
    fun `a missing TTS voice degrades instead of failing the session`() {
        val events = mutableListOf<JsonObject>()
        val tts = FakeTts().apply { languageResult = false }
        val transport = LocalVoiceTransport(
            context = null as android.content.Context?,
            scope = CoroutineScope(Dispatchers.IO),
            stt = stt(tempDir = createTempDir(), runtime = FakeWhisperRuntime()),
            engine = FakeTeachingClient(),
            tts = tts,
            frameSourceProvider = { FakeFrameSource() },
            frameDelayMs = 0,
        )
        val failures = mutableListOf<String>()
        transport.onEvent = { events.add(it) }
        transport.onFailure = { failures.add(it) }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "nb") }
        assertTrue(tts.languages.contains("nb"))
        assertTrue("session still starts", events.any { it["type"]?.jsonPrimitive?.content == "session.started" })
        assertTrue("no failure surfaced", failures.isEmpty())
        transport.close()
    }

    @Test
    fun `a discarded turn retracts the provisional caption`() {
        val events = mutableListOf<JsonObject>()
        val source = FakeFrameSource()
        val runtime = FakeWhisperRuntime().apply { transcription = "Hola" }
        val transport = LocalVoiceTransport(
            context = null as android.content.Context?,
            scope = CoroutineScope(Dispatchers.IO),
            stt = stt(tempDir = createTempDir(), runtime = runtime),
            engine = FakeTeachingClient(),
            tts = FakeTts(),
            frameSourceProvider = { source },
            frameDelayMs = 0,
        )
        transport.onEvent = { events.add(it) }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }

        // Enough speech for a partial…
        repeat(20) { source.queue.add(speech()) }
        val partialDeadline = System.currentTimeMillis() + 5_000
        while (events.none { it["type"]?.jsonPrimitive?.content == "session.input_transcript.partial" } &&
            System.currentTimeMillis() < partialDeadline) Thread.sleep(25)

        // …then the turn ends with an empty transcription: the partial must be retracted.
        runtime.transcription = ""
        repeat(14) { source.queue.add(silence()) }
        val clearedDeadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < clearedDeadline) {
            val last = events.lastOrNull { it["type"]?.jsonPrimitive?.content == "session.input_transcript.partial" }
            if (last != null && last["delta"]?.jsonPrimitive?.content?.isEmpty() == true) break
            Thread.sleep(25)
        }
        val partials = events.filter { it["type"]?.jsonPrimitive?.content == "session.input_transcript.partial" }
        assertTrue("a clearing empty partial was emitted", partials.isNotEmpty() &&
            partials.last()["delta"]?.jsonPrimitive?.content?.isEmpty() == true)
        assertTrue("no final fragment for the discarded turn",
            events.none { it["type"]?.jsonPrimitive?.content == "session.input_transcript.delta" })
        transport.close()
    }

    @Test
    fun `a failed opening greeting fails the session loudly`() {
        val events = mutableListOf<JsonObject>()
        val failures = mutableListOf<String>()
        val failingEngine = object : TeachingClient {
            override fun cancelInFlight() {}
            override suspend fun respond(instructions: String, input: String, schema: kotlinx.serialization.json.JsonObject?, search: Boolean, purpose: com.jkdc.app.network.HelperPurpose?): com.jkdc.app.network.APIResult {
                throw IllegalStateException("engine offline")
            }
        }
        val transport = LocalVoiceTransport(
            context = null as android.content.Context?,
            scope = CoroutineScope(Dispatchers.IO),
            stt = stt(tempDir = createTempDir(), runtime = FakeWhisperRuntime()),
            engine = failingEngine,
            tts = FakeTts(),
            frameSourceProvider = { FakeFrameSource() },
            frameDelayMs = 0,
        )
        transport.openOnStart = true
        transport.onEvent = { events.add(it) }
        transport.onFailure = { failures.add(it) }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }
        val deadline = System.currentTimeMillis() + 5_000
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(failures.single().contains("opening greeting failed"))
        assertTrue(failures.single().contains("engine offline"))
        transport.close()
    }
}
