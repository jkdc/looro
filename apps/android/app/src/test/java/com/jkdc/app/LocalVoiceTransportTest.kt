package com.jkdc.app

import com.jkdc.app.core.ConversationHistory
import com.jkdc.app.core.Fragment
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.Speaker
import com.jkdc.app.core.voice.LocalVoiceTransport
import com.jkdc.app.engines.local.AudioFrameSource
import com.jkdc.app.engines.local.MicRecorder
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
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
        // Concurrent queue: the test thread feeds frames while the transport's
        // IO loop drains them — an unsynchronized ArrayDeque corrupts here.
        val queue = java.util.concurrent.ConcurrentLinkedQueue<FloatArray>()
        var started = false
        @Volatile var stopped = false
        var paused = false
        var resumeCount = 0
        var drainedFrames = 0
        val framesRead = java.util.concurrent.atomic.AtomicInteger()
        private val readWaiters = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, CountDownLatch>>()
        override fun start(): Boolean { started = true; return true }
        override fun readFrame(): FloatArray? {
            if (paused) return null
            val frame = queue.poll() ?: return null
            val count = framesRead.incrementAndGet()
            readWaiters.forEach { waiter ->
                if (count >= waiter.first && readWaiters.remove(waiter)) waiter.second.countDown()
            }
            return frame
        }
        fun awaitFramesRead(target: Int, timeoutMs: Long): Boolean {
            if (framesRead.get() >= target) return true
            val latch = CountDownLatch(1)
            val waiter = target to latch
            readWaiters.add(waiter)
            if (framesRead.get() >= target && readWaiters.remove(waiter)) latch.countDown()
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
        override fun stop() { stopped = true }
        override fun pause() { paused = true }
        override fun drainStale() { drainedFrames += queue.size; queue.clear() }
        override fun resume(): Boolean { paused = false; resumeCount++; return true }
    }

    private class ThrowingFrameSource : AudioFrameSource {
        @Volatile var stopped = false
        @Volatile var failed = false
        override fun start(): Boolean = true
        override fun readFrame(): FloatArray? {
            failed = true
            throw IllegalStateException("synthetic capture failure")
        }
        override fun stop() { stopped = true }
        override fun pause() {}
        override fun drainStale() {}
        override fun resume(): Boolean = true
    }

    private class FakeWhisperRuntime : WhisperRuntime {
        var transcription = "Hello there"
        @Volatile var lastSamples: FloatArray? = null
        val transcribeCalls = java.util.concurrent.atomic.AtomicInteger()
        override fun init(modelPath: String): Long = 7L
        override fun transcribe(handle: Long, samples: FloatArray, numSamples: Int, threads: Int, language: String): String {
            transcribeCalls.incrementAndGet()
            lastSamples = samples.copyOf(numSamples)
            return transcription
        }
        override fun free(handle: Long) {}
    }

    private class FakeTeachingClient : TeachingClient {
        val received = mutableListOf<Pair<String, String>>() // system to user
        val reply = "Nice! Tell me more about it."
        val replies = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var cancelCalls = 0
        override fun cancelInFlight() { cancelCalls++ }
        override suspend fun respond(instructions: String, input: String, schema: kotlinx.serialization.json.JsonObject?, search: Boolean, purpose: com.jkdc.app.network.HelperPurpose?): com.jkdc.app.network.APIResult {
            received.add(instructions to input)
            gate?.await()
            return com.jkdc.app.network.APIResult(text = replies.poll() ?: reply, sources = emptyList(), usage = com.jkdc.app.network.APIUsage(10, 5))
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
    private fun speech(amplitude: Float = 0.5f): FloatArray = FloatArray(2048) { i -> (amplitude * sin(2.0 * Math.PI * 220 * i / 16000.0)).toFloat() }

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
    fun `initialSilenceRetainsBoundedSamples`() {
        val source = FakeFrameSource()
        val runtime = FakeWhisperRuntime()
        val engine = FakeTeachingClient()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), runtime), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 1,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }

        // Three minutes of silence must not be retained as a giant turn buffer.
        repeat(1_406) { source.queue.add(silence()) }
        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        val deadline = System.currentTimeMillis() + 10_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)

        assertEquals(1, engine.received.size)
        assertTrue("the retained audio must include the whole post-silence utterance",
            (runtime.lastSamples?.size ?: 0) >= 4 * 2_048)
        assertTrue("the retained audio must fit the 20 s capture bound", (runtime.lastSamples?.size ?: 0) <= 320_000)
        transport.close()
    }

    @Test
    fun `speechAfterSilenceRetainsFirstSyllable`() {
        val source = FakeFrameSource()
        val runtime = FakeWhisperRuntime()
        val events = mutableListOf<JsonObject>()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), runtime), FakeTeachingClient(), FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { events.add(it) }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }

        repeat(100) { source.queue.add(silence()) }
        source.queue.add(FloatArray(2_048) { if (it < 256) 0.4f else 0.1f })
        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        val deadline = System.currentTimeMillis() + 10_000
        while (events.none { it["type"]?.jsonPrimitive?.content == "session.input_transcript.delta" } &&
            System.currentTimeMillis() < deadline) Thread.sleep(25)

        val captured = runtime.lastSamples ?: error("STT did not receive audio")
        assertTrue("the first high-energy onset must survive silence trimming", captured.any { it == 0.4f })
        transport.close()
    }

    @Test
    fun `voiceReplyCanBeInterruptedWhileTtsBlocked`() {
        val source = FakeFrameSource()
        val tts = FakeTts()
        val engine = FakeTeachingClient()
        val runtime = FakeWhisperRuntime()
        val playback = CountDownLatch(1)
        tts.onSpeak = { playback.await() }
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), runtime), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.bargeIn = true
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }
        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (tts.spoken.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals("the voice turn must reach TTS", 1, tts.spoken.size)

        val sttCallsBeforeInterruptionDrain = runtime.transcribeCalls.get()
        val llmCallsBeforeInterruptionDrain = engine.received.size
        val interruptionFrameStart = source.framesRead.get()
        repeat(8) { source.queue.add(speech(0.7f)) }
        val stopDeadline = System.currentTimeMillis() + 5_000
        while (tts.stopCalls == 0 && System.currentTimeMillis() < stopDeadline) Thread.sleep(25)
        assertTrue("capture must detect barge-in while voice TTS is blocked", tts.stopCalls > 0)
        assertTrue("capture producer acknowledges reading all interruption frames",
            source.awaitFramesRead(interruptionFrameStart + 8, 5_000))
        assertEquals("discarded interruption frames do not invoke STT", sttCallsBeforeInterruptionDrain, runtime.transcribeCalls.get())
        assertEquals("discarded interruption frames do not invoke LLM", llmCallsBeforeInterruptionDrain, engine.received.size)

        playback.countDown()
        val idleDeadline = System.currentTimeMillis() + 5_000
        while (transport.turnInFlight && System.currentTimeMillis() < idleDeadline) Thread.sleep(25)

        repeat(4) { source.queue.add(speech(0.25f)) }
        repeat(10) { source.queue.add(silence()) }
        val nextTurnDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.size < llmCallsBeforeInterruptionDrain + 1 && System.currentTimeMillis() < nextTurnDeadline) Thread.sleep(25)
        assertEquals("a later valid utterance still reaches the tutor", llmCallsBeforeInterruptionDrain + 1, engine.received.size)
        assertEquals("only the later valid utterance invokes STT", sttCallsBeforeInterruptionDrain + 1, runtime.transcribeCalls.get())
        val freshUtteranceWithTrailingSilence = FloatArray(14 * 2_048) { i ->
            if (i < 4 * 2_048) speech(0.25f)[i % 2_048] else 0f
        }
        assertArrayEquals("only the later valid utterance reaches STT", MicRecorder.trimSilence(freshUtteranceWithTrailingSilence), runtime.lastSamples!!, 0f)
        transport.close()
    }

    @Test
    fun `mute with an empty queue invalidates buffered prefix before the next turn`() {
        val source = FakeFrameSource()
        val runtime = FakeWhisperRuntime()
        val engine = FakeTeachingClient()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), runtime), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.manualTurn = true
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }
        repeat(4) { source.queue.add(speech()) }
        Thread.sleep(200)
        assertTrue("the prefix must already be consumed before mute", source.queue.isEmpty())

        transport.mute(true)
        Thread.sleep(100)
        transport.mute(false)
        repeat(4) { source.queue.add(speech()) }
        Thread.sleep(200)
        transport.endTurn()
        assertTrue("new post-mute utterance is processed", awaitTurns(engine, 1))

        val expected = MicRecorder.trimSilence(FloatArray(4 * 2_048) { i -> speech()[i % 2_048] })
        assertArrayEquals("STT receives exactly the fresh post-mute samples", expected, runtime.lastSamples!!, 0f)
        transport.close()
    }

    @Test
    fun `capture failure still stops and releases its frame source`() {
        val source = ThrowingFrameSource()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }),
            stt(createTempDir(), FakeWhisperRuntime()), FakeTeachingClient(), FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }

        val deadline = System.currentTimeMillis() + 5_000
        while (!source.stopped && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue("synthetic frame-source failure was exercised", source.failed)
        assertTrue("source.stop must run when the capture child throws", source.stopped)
    }

    @Test
    fun `restored multiline history keeps role messages intact at the cap`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        val history = JsonArray((0 until LocalVoiceTransport.MAX_ROLLING_TURNS).map { index ->
            buildJsonObject {
                put("role", if (index % 2 == 0) "user" else "assistant")
                put("content", "message-$index line one\nmessage-$index line two")
            }
        })
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", history, language = "en") }
        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        assertTrue("restored history reaches the next request", awaitTurns(engine, 1))

        val context = engine.received.single().first
        assertEquals("each restored record keeps exactly one role header", LocalVoiceTransport.MAX_ROLLING_TURNS,
            Regex("(?:User|Assistant):").findAll(context).count())
        assertTrue("oldest in-cap message and its continuation remain associated", context.contains("User: message-0 line one\nmessage-0 line two"))
        assertTrue("newest role remains in order", context.indexOf("Assistant: message-15") > context.indexOf("User: message-14"))
        transport.close()
    }

    @Test
    fun `openingAppearsExactlyOnceInNextRequest`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient().apply {
            replies.add("Opening unique question?")
            replies.add("A useful answer.")
        }
        val events = mutableListOf<JsonObject>()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0, openOnStart = true,
        )
        transport.onEvent = { events.add(it) }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }
        assertTrue(transport.send(buildAppend("session.instructions.append", "greet the learner")))
        val openingDeadline = System.currentTimeMillis() + 5_000
        while (events.none { it["delta"]?.jsonPrimitive?.content == "Opening unique question?" } &&
            System.currentTimeMillis() < openingDeadline) Thread.sleep(25)
        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        val requestDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.size < 2 && System.currentTimeMillis() < requestDeadline) Thread.sleep(25)

        assertEquals(2, engine.received.size)
        assertEquals(1, Regex("Assistant: Opening unique question\\?").findAll(engine.received[1].first).count())
        transport.close()
    }

    @Test
    fun `typedAndVoiceTurnsShareRoleOrderedContext`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val tts = FakeTts()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "Be a coach", language = "en") }
        transport.send(buildAppend("session.user_message.append", "Typed question"))
        transport.send(buildAppend("session.commentary.append", "Typed answer"))
        val ttsDeadline = System.currentTimeMillis() + 5_000
        while (tts.spoken.isEmpty() && System.currentTimeMillis() < ttsDeadline) Thread.sleep(25)

        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        val firstDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.size < 1 && System.currentTimeMillis() < firstDeadline) Thread.sleep(25)
        assertEquals(1, engine.received.size)
        assertTrue(engine.received[0].first.indexOf("User: Typed question") <
            engine.received[0].first.indexOf("Assistant: Typed answer"))

        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        val secondDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.size < 2 && System.currentTimeMillis() < secondDeadline) Thread.sleep(25)
        assertEquals(2, engine.received.size)
        val secondContext = engine.received[1].first
        assertTrue(secondContext.indexOf("User: Typed question") < secondContext.indexOf("Assistant: Typed answer"))
        assertTrue(secondContext.indexOf("Assistant: Typed answer") < secondContext.indexOf("User: Hello there"))
        assertTrue(secondContext.indexOf("User: Hello there") < secondContext.indexOf("Assistant: Nice! Tell me more about it."))
        transport.close()
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
    fun `turnInFlight spans the manual turn from endTurn until the reply is spoken`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.gate = gate
        val tts = FakeTts()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime().apply { transcription = "Habla lenta" }), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.manualTurn = true
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }
        assertFalse("idle: no turn in flight", transport.turnInFlight)

        // Buffer the voiced speech first (deterministic), then press "He
        // terminado": the close fires at once and in-flight holds through the
        // gated reply, clearing once the reply is spoken.
        repeat(4) { source.queue.add(speech()) }
        Thread.sleep(300)
        transport.endTurn()
        val inFlightDeadline = System.currentTimeMillis() + 5_000
        while (!transport.turnInFlight && System.currentTimeMillis() < inFlightDeadline) Thread.sleep(25)
        assertTrue("in flight during the gated reply", transport.turnInFlight)
        assertTrue("no reply spoken yet", tts.spoken.isEmpty())

        gate.complete(Unit)
        val spokenDeadline = System.currentTimeMillis() + 5_000
        while (tts.spoken.isEmpty() && System.currentTimeMillis() < spokenDeadline) Thread.sleep(25)
        val doneDeadline = System.currentTimeMillis() + 5_000
        while (transport.turnInFlight && System.currentTimeMillis() < doneDeadline) Thread.sleep(25)
        assertFalse("in flight must clear after the reply is spoken", transport.turnInFlight)
        transport.close()
    }

    @Test
    fun `a press during silence stays armed and captures the next spoken phrase`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val tts = FakeTts()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime().apply { transcription = "Al final hablo" }), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.manualTurn = true
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        // A press with NOTHING voiced buffered stays armed: silence frames never
        // satisfy it, and the next spoken phrase is captured WHOLE — closed when
        // the phrase trails into silence, never on its first voiced frame
        // (which would trim to <250 ms and be discarded, losing the press).
        transport.endTurn()
        repeat(10) { source.queue.add(silence()) }
        Thread.sleep(400)
        assertEquals("silence must not produce a turn", 0, engine.received.size)
        repeat(4) { source.queue.add(speech()) }
        Thread.sleep(400)
        assertEquals("the phrase start must not close an armed turn", 0, engine.received.size)
        repeat(8) { source.queue.add(silence()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals("the phrase end (trailing silence) closes the armed turn", 1, engine.received.size)
        val ttsDeadline = System.currentTimeMillis() + 5_000
        while (tts.spoken.isEmpty() && System.currentTimeMillis() < ttsDeadline) Thread.sleep(25)
        assertEquals("Nice! Tell me more about it.", tts.spoken.single())
        transport.close()
    }

    // ---- Cloud STT selection: per-turn dispatch with local fallback ----

    /** Canned OpenAI-style response, answered WITHOUT touching the network. */
    private fun cloudChain(chain: Interceptor.Chain, code: Int, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code).message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private class CloudCounter {
        val hits = java.util.concurrent.atomic.AtomicInteger(0)
        fun client(answer: (Interceptor.Chain) -> Response): OkHttpClient =
            OkHttpClient.Builder().addInterceptor { chain -> hits.incrementAndGet(); answer(chain) }.build()
    }

    private fun manualTransport(localText: String): kotlin.Triple<LocalVoiceTransport, FakeFrameSource, FakeTeachingClient> {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime().apply { transcription = localText }), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.manualTurn = true
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }
        return Triple(transport, source, engine)
    }

    /** Speak a phrase and close it: 4 voiced + 8 silent frames (128 ms each). */
    private fun speakPhrase(source: FakeFrameSource) {
        repeat(4) { source.queue.add(speech()) }
        repeat(8) { source.queue.add(silence()) }
    }

    private fun awaitTurns(engine: FakeTeachingClient, count: Int, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (engine.received.size < count && System.currentTimeMillis() < deadline) Thread.sleep(25)
        return engine.received.size >= count
    }

    @Test
    fun `a selected cloud provider transcribes the final turn instead of the local pass`() {
        val cloud = CloudCounter()
        val (transport, source, engine) = manualTransport("LOCAL TEXT")
        transport.cloudStts = mapOf("openai" to com.jkdc.app.network.CloudStt(
            apiKey = { "sk-test" },
            client = cloud.client { chain -> cloudChain(chain, 200, """{"text": "CLOUD TEXT"}""") },
        ))
        transport.cloudSttSelection = { "openai" }

        speakPhrase(source)
        transport.endTurn()
        assertTrue("cloud turn processed", awaitTurns(engine, 1))
        assertEquals("CLOUD TEXT", engine.received.single().second)
        assertEquals("cloud called exactly once", 1, cloud.hits.get())
        transport.close()
    }

    @Test
    fun `cloud provider failure falls back to the local transcription without failing the turn`() {
        val cloud = CloudCounter()
        val (transport, source, engine) = manualTransport("LOCAL TEXT")
        transport.cloudStts = mapOf("openai" to com.jkdc.app.network.CloudStt(
            apiKey = { "sk-test" },
            client = cloud.client { chain -> cloudChain(chain, 500, """{"error": "boom"}""") },
        ))
        transport.cloudSttSelection = { "openai" }

        speakPhrase(source)
        transport.endTurn()
        assertTrue("the turn still completes", awaitTurns(engine, 1))
        assertEquals("LOCAL TEXT", engine.received.single().second)
        assertEquals("the cloud attempt was made", 1, cloud.hits.get())
        transport.close()
    }

    @Test
    fun `an unknown stt engine selection silently uses the local pass`() {
        val cloud = CloudCounter()
        val (transport, source, engine) = manualTransport("LOCAL TEXT")
        transport.cloudStts = mapOf("openai" to com.jkdc.app.network.CloudStt(
            apiKey = { "sk-test" },
            client = cloud.client { chain -> cloudChain(chain, 200, """{"text": "CLOUD TEXT"}""") },
        ))
        transport.cloudSttSelection = { "stale-preference-value" }

        speakPhrase(source)
        transport.endTurn()
        assertTrue("the turn still completes", awaitTurns(engine, 1))
        assertEquals("LOCAL TEXT", engine.received.single().second)
        assertEquals("no cloud call for an unknown id", 0, cloud.hits.get())
        transport.close()
    }

    @Test
    fun `an armed press shows turnInFlight through the gated reply`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.gate = gate
        val tts = FakeTts()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime().apply { transcription = "Al final hablo" }), engine, tts,
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.manualTurn = true
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }
        assertFalse("idle: no turn in flight", transport.turnInFlight)

        // Armed press → phrase → trailing silence closes and processes: the
        // armed close must drive turnInFlight exactly like the immediate one.
        transport.endTurn()
        repeat(10) { source.queue.add(silence()) }
        Thread.sleep(300)
        speakPhrase(source)
        val inFlightDeadline = System.currentTimeMillis() + 5_000
        while (!transport.turnInFlight && System.currentTimeMillis() < inFlightDeadline) Thread.sleep(25)
        assertTrue("in flight during the gated reply", transport.turnInFlight)
        assertTrue("no reply spoken yet", tts.spoken.isEmpty())

        gate.complete(Unit)
        val doneDeadline = System.currentTimeMillis() + 5_000
        while (transport.turnInFlight && System.currentTimeMillis() < doneDeadline) Thread.sleep(25)
        assertFalse("in flight must clear after the reply is spoken", transport.turnInFlight)
        transport.close()
    }

    @Test
    fun `a stale armed press is consumed by the manual window reset`() {
        val (transport, source, engine) = manualTransport("late")
        transport.endTurn()
        repeat(160) { source.queue.add(silence()) } // 20.5 s: crosses the idle cap
        Thread.sleep(500)
        assertEquals("the cap reset must not process anything", 0, engine.received.size)
        // The press is dead: a full phrase afterwards must NOT auto-close.
        speakPhrase(source)
        val negativeDeadline = System.currentTimeMillis() + 2_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < negativeDeadline) Thread.sleep(25)
        assertEquals("a stale armed press must not close a later phrase", 0, engine.received.size)
        // A fresh press closes the buffered phrase immediately.
        transport.endTurn()
        assertTrue("fresh press processes the open turn", awaitTurns(engine, 1))
        transport.close()
    }

    @Test
    fun `an armed press survives mute and captures the next phrase whole`() {
        val (transport, source, engine) = manualTransport("after mute")
        transport.endTurn()
        repeat(10) { source.queue.add(silence()) }
        Thread.sleep(300)
        transport.mute(true)
        repeat(5) { source.queue.add(silence()) }
        transport.mute(false)
        speakPhrase(source)
        assertTrue("the press survives mute and closes the phrase", awaitTurns(engine, 1))
        transport.close()
    }

    @Test
    fun `a pause longer than the armed window ends the press's reach`() {
        val (transport, source, engine) = manualTransport("two phrases")
        transport.endTurn()
        repeat(10) { source.queue.add(silence()) }
        Thread.sleep(300)
        speakPhrase(source) // phrase 1 closes at its trailing silence
        assertTrue("phrase one processed", awaitTurns(engine, 1))
        speakPhrase(source) // phrase 2: the press is spent, must NOT auto-close
        val negativeDeadline = System.currentTimeMillis() + 2_000
        while (engine.received.size > 1 && System.currentTimeMillis() < negativeDeadline) Thread.sleep(25)
        assertEquals("one press covers exactly one phrase", 1, engine.received.size)
        transport.endTurn()
        assertTrue("a fresh press closes phrase two", awaitTurns(engine, 2))
        transport.close()
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
        assertTrue(transport.send(buildAppend("session.instructions.append", "greet the learner")))

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
        assertTrue(transport.send(buildAppend("session.instructions.append", "greet the learner")))
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
            frameSourceProvider = { source }, frameDelayMs = 5,
        )
        transport.manualTurn = true
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        // 150 loud frames = 19.2 s of speech: below the 20 s manual safety cap the
        // turn must NOT auto-process — previously ambient noise rode the cap and
        // re-processed the app's own voice in a loop.
        repeat(150) { source.queue.add(speech()) }
        Thread.sleep(1_000)
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
    fun `manual capture excludes wait silence preserves onset at limit and notifies without auto submit`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val runtime = FakeWhisperRuntime()
        val scope = CoroutineScope(Dispatchers.IO)
        val events = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, scope,
            stt(createTempDir(), runtime), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 1,
        )
        transport.manualTurn = true
        transport.onEvent = { event -> events.add(event) }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        var frameTarget = 0
        fun feed(frame: FloatArray) {
            source.queue.add(frame)
            frameTarget++
            assertTrue("capture frame $frameTarget", source.awaitFramesRead(frameTarget, 2_000))
            Thread.sleep(2)
        }

        repeat(170) { feed(silence()) }
        repeat(16) { feed(speech(amplitude = 0.1f)) }
        repeat(160) { feed(speech(amplitude = 0.8f)) }
        Thread.sleep(100)

        assertTrue(events.any { it["type"]?.jsonPrimitive?.content == "session.capture.limit_reached" })
        assertEquals("manual capture must wait for explicit end-turn", 0, engine.received.size)

        transport.endTurn()
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(1, engine.received.size)
        val captured = runtime.lastSamples ?: throw AssertionError("STT must receive the bounded captured prefix")
        assertTrue(captured.size <= 18 * 16_000)
        val onsetPeak = captured.take(8_000).maxOf { kotlin.math.abs(it) }
        assertTrue("the low-amplitude utterance onset must survive the STT cap, peak=$onsetPeak", onsetPeak < 0.2f)
        scope.cancel()
    }

    @Test
    fun `manual frame overflow keeps queued speech until explicit end and signals limit`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val scope = CoroutineScope(Dispatchers.IO)
        val events = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        val consumerEntered = CountDownLatch(1)
        val releaseConsumer = CountDownLatch(1)
        val blockedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val transport = LocalVoiceTransport(
            null as android.content.Context?, scope,
            stt(createTempDir(), FakeWhisperRuntime()), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0,
        )
        transport.manualTurn = true
        transport.onEvent = { event -> events.add(event) }
        transport.onLevels = { input, _ ->
            if (input > 0.01 && blockedOnce.compareAndSet(false, true)) {
                consumerEntered.countDown()
                releaseConsumer.await(2, TimeUnit.SECONDS)
            }
        }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        source.queue.add(speech(amplitude = 0.5f))
        assertTrue(consumerEntered.await(2, TimeUnit.SECONDS))
        repeat(50) { source.queue.add(speech(amplitude = 0.5f)) }
        assertTrue(source.awaitFramesRead(51, 2_000))
        releaseConsumer.countDown()

        val limitDeadline = System.currentTimeMillis() + 2_000
        while (events.none { it["type"]?.jsonPrimitive?.content == "session.capture.limit_reached" } && System.currentTimeMillis() < limitDeadline) Thread.sleep(10)
        assertTrue("overflow must produce a nonterminal capture-limit event", events.any { it["type"]?.jsonPrimitive?.content == "session.capture.limit_reached" })
        assertEquals("manual overflow must not submit speech automatically", 0, engine.received.size)

        transport.endTurn()
        val submitDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < submitDeadline) Thread.sleep(25)
        assertEquals("the retained queued prefix is submitted only after explicit end", 1, engine.received.size)
        scope.cancel()
    }

    @Test
    fun `saturating the opening buffer keeps the retained speech for the held end press`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val greetingGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.gate = greetingGate
        val runtime = FakeWhisperRuntime()
        val scope = CoroutineScope(Dispatchers.IO)
        val events = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, scope,
            stt(createTempDir(), runtime), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0, openOnStart = true,
        )
        transport.manualTurn = true
        transport.onEvent = { event -> events.add(event) }
        runBlocking { transport.connect(FakeProvider(), "base instructions", language = "en") }

        assertTrue(transport.send(buildAppend("session.instructions.append", "greet the learner")))
        val greetingDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < greetingDeadline) Thread.sleep(25)
        assertEquals("the opening greeting is the first request", 1, engine.received.size)

        // 160 speech frames = 20.5 s: the opening buffer saturates at its 20 s
        // memory bound and only the excess is dropped — the retained prefix
        // must survive for the drain once the greeting ends.
        repeat(160) { source.queue.add(speech(amplitude = 0.6f)) }
        assertTrue(source.awaitFramesRead(160, 5_000))
        val limitDeadline = System.currentTimeMillis() + 5_000
        while (events.none { it["type"]?.jsonPrimitive?.content == "session.capture.limit_reached" } &&
            System.currentTimeMillis() < limitDeadline) Thread.sleep(10)
        assertTrue("saturating the opening buffer must surface its limit",
            events.any { it["type"]?.jsonPrimitive?.content == "session.capture.limit_reached" })
        assertEquals("a saturated opening buffer must not submit the turn", 1, engine.received.size)

        // the user presses End while the greeting is still talking: the press
        // must survive and apply to the WHOLE retained prefix after the drain
        transport.endTurn()
        greetingGate.complete(Unit)
        val turnDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.size < 2 && System.currentTimeMillis() < turnDeadline) Thread.sleep(25)
        assertEquals("the retained prefix must become exactly one reply request", 2, engine.received.size)
        assertEquals("the drained prefix is the reply input", "Hello there", engine.received[1].second)
        val captured = runtime.lastSamples ?: throw AssertionError("STT must receive the retained opening prefix")
        assertTrue("the retained prefix carries voice, not silence", captured.size >= 15 * 16_000)
        assertTrue("the STT pass must receive at most its 18 s input cap", captured.size <= 18 * 16_000)
        transport.close()
        scope.cancel()
    }

    @Test
    fun `mute toggles during a full opening buffer clear it for the post unmute phrase`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val greetingGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.gate = greetingGate
        val runtime = FakeWhisperRuntime()
        val scope = CoroutineScope(Dispatchers.IO)
        val events = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        // Paced producer (1 ms per frame): a 0 ms burst artificially
        // overflows the handoff and the producer then holds frames back, so
        // the saturation below would depend on scheduling instead of the
        // buffer logic under test.
        val transport = LocalVoiceTransport(
            null as android.content.Context?, scope,
            stt(createTempDir(), runtime), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 1, openOnStart = true,
        )
        transport.manualTurn = true
        transport.onEvent = { event -> events.add(event) }
        runBlocking { transport.connect(FakeProvider(), "base instructions", language = "en") }

        assertTrue(transport.send(buildAppend("session.instructions.append", "greet the learner")))
        val greetingDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < greetingDeadline) Thread.sleep(25)
        assertEquals(1, engine.received.size)

        // Saturate the retained opening prefix, then toggle mute twice: the
        // epoch change must invalidate the stale prefix instead of leaving it
        // parked at full capacity over the new epoch.
        // epoch change must invalidate the stale prefix instead of leaving it
        // parked at full capacity over the new epoch. The fill is topped up
        // until the limit event fires: a stray Flush mid-fill resets the
        // buffer and the cap must be reached regardless of that interleaving.
        var fed = 160
        repeat(160) { source.queue.add(speech(amplitude = 0.6f)) }
        val limitDeadline = System.currentTimeMillis() + 5_000
        while (events.none { it["type"]?.jsonPrimitive?.content == "session.capture.limit_reached" } &&
            System.currentTimeMillis() < limitDeadline) {
            Thread.sleep(25)
            if (fed < 220) {
                source.queue.add(speech(amplitude = 0.6f))
                fed += 1
            }
        }
        assertTrue(source.awaitFramesRead(fed, 5_000))
        assertTrue(
            "saturating the opening buffer must surface its limit; fed=$fed "
                + "state=<${transport.captureWindowState}> "
                + "events=${events.joinToString(prefix = "[", postfix = "]") { it["type"].toString() }}",
            events.any { it["type"]?.jsonPrimitive?.content == "session.capture.limit_reached" })

        transport.mute(true)
        transport.mute(false)
        transport.mute(true)
        transport.mute(false)

        // The post-unmute phrase is the only voice that may survive.
        repeat(8) { source.queue.add(speech(amplitude = 0.8f)) }
        assertTrue(source.awaitFramesRead(fed + 8, 5_000))
        Thread.sleep(150)
        transport.endTurn()
        greetingGate.complete(Unit)
        val turnDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.size < 2 && System.currentTimeMillis() < turnDeadline) Thread.sleep(25)
        assertEquals("the post-unmute phrase becomes exactly one reply request", 2, engine.received.size)
        val captured = runtime.lastSamples ?: throw AssertionError("STT must receive the post-unmute phrase")
        assertTrue("the stale prefix must not reach STT", captured.size <= 10 * 16_000)
        assertTrue("the post-unmute phrase must reach STT", captured.size >= 12_000)
        transport.close()
        scope.cancel()
    }

    @Test
    fun `mute before the opening saturates keeps the buffer usable after unmute`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val greetingGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.gate = greetingGate
        val runtime = FakeWhisperRuntime()
        val scope = CoroutineScope(Dispatchers.IO)
        val transport = LocalVoiceTransport(
            null as android.content.Context?, scope,
            stt(createTempDir(), runtime), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0, openOnStart = true,
        )
        transport.manualTurn = true
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "base instructions", language = "en") }

        assertTrue(transport.send(buildAppend("session.instructions.append", "greet the learner")))
        val greetingDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < greetingDeadline) Thread.sleep(25)

        // Muted frames are ignored; repeated toggles before saturation must
        // leave the buffer usable for the phrase that follows the unmute.
        transport.mute(true)
        repeat(20) { source.queue.add(speech()) }
        assertTrue(source.awaitFramesRead(20, 5_000))
        transport.mute(false)
        transport.mute(true)
        transport.mute(false)
        repeat(10) { source.queue.add(speech(amplitude = 0.8f)) }
        assertTrue(source.awaitFramesRead(30, 5_000))
        Thread.sleep(150)
        transport.endTurn()
        greetingGate.complete(Unit)
        val turnDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.size < 2 && System.currentTimeMillis() < turnDeadline) Thread.sleep(25)
        assertEquals(2, engine.received.size)
        val captured = runtime.lastSamples ?: throw AssertionError("STT must receive the post-unmute phrase")
        assertTrue(captured.size <= 12 * 16_000)
        assertTrue(captured.size >= 12_000)
        transport.close()
        scope.cancel()
    }

    @Test
    fun `a turn cut at the stt cap signals the visible trim instead of dropping the tail silently`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val runtime = FakeWhisperRuntime()
        val scope = CoroutineScope(Dispatchers.IO)
        val events = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        val transport = LocalVoiceTransport(
            null as android.content.Context?, scope,
            stt(createTempDir(), runtime), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 1,
        )
        transport.manualTurn = true
        transport.onEvent = { event -> events.add(event) }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        // 150 speech frames = 19.2 s: under the 20 s capture cap, over the
        // 18 s STT input cap — the tail beyond 18 s must not vanish silently.
        repeat(150) { source.queue.add(speech(amplitude = 0.6f)) }
        assertTrue(source.awaitFramesRead(150, 5_000))
        Thread.sleep(150)
        assertEquals("no auto submit in manual mode", 0, engine.received.size)

        transport.endTurn()
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(1, engine.received.size)
        assertTrue("finalizing past the STT cap must surface the visible trim",
            events.any { it["type"]?.jsonPrimitive?.content == "session.capture.limit_reached" })
        val captured = runtime.lastSamples!!
        assertTrue("the STT prefix stays within its cap", captured.size <= 18 * 16_000)
        transport.close()
        scope.cancel()
    }

    @Test
    fun `opening greeting waits for initial instructions before processing voice`() {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val greetingGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.gate = greetingGate
        val runtime = FakeWhisperRuntime()
        val scope = CoroutineScope(Dispatchers.IO)
        val transport = LocalVoiceTransport(
            null as android.content.Context?, scope,
            stt(createTempDir(), runtime), engine, FakeTts(),
            frameSourceProvider = { source }, frameDelayMs = 0, openOnStart = true,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "base instructions", language = "en") }

        Thread.sleep(300)
        assertTrue("no voice request or greeting may run before initial instructions arrive", engine.received.isEmpty())

        assertTrue(transport.send(buildAppend("session.instructions.append", "greet the learner")))
        val greetingDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.isEmpty() && System.currentTimeMillis() < greetingDeadline) Thread.sleep(25)
        assertEquals("the first provider request must be the opening greeting", 1, engine.received.size)
        assertEquals("(Begin the conversation now as instructed: greet the learner and open the theme.)", engine.received.single().second)
        assertTrue(engine.received.single().first.contains("greet the learner"))

        repeat(4) { source.queue.add(speech()) }
        repeat(10) { source.queue.add(silence()) }
        assertTrue(source.awaitFramesRead(14, 2_000))
        Thread.sleep(250)
        assertEquals("the first voice turn waits behind a blocked greeting", 1, engine.received.size)
        greetingGate.complete(Unit)
        val firstTurnDeadline = System.currentTimeMillis() + 5_000
        while (engine.received.size < 2 && System.currentTimeMillis() < firstTurnDeadline) Thread.sleep(25)
        assertEquals("voice captured during the greeting must be processed afterward", 2, engine.received.size)
        assertEquals("Hello there", engine.received[1].second)
        assertTrue("the first turn context includes the completed greeting", engine.received[1].first.contains("Assistant: Nice! Tell me more about it."))
        transport.close()
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
        assertTrue(transport.send(buildAppend("session.instructions.append", "greet the learner")))
        val deadline = System.currentTimeMillis() + 5_000
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(failures.single().contains("opening greeting failed"))
        assertTrue(failures.single().contains("engine offline"))
        transport.close()
    }
    @Test
    fun `manual microphone mute does not stop tutor playback`() {
        val tts = FakeTts()
        val speaking = CountDownLatch(1)
        val allowPlaybackToFinish = CountDownLatch(1)
        tts.onSpeak = {
            speaking.countDown()
            allowPlaybackToFinish.await(5, TimeUnit.SECONDS)
        }
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), FakeTeachingClient(), tts,
            frameSourceProvider = { FakeFrameSource() }, frameDelayMs = 0,
        )
        transport.onEvent = { }
        runBlocking { transport.connect(FakeProvider(), "i", language = "en") }

        assertTrue(transport.send(buildAppend("session.commentary.append", "Tutor output continues")))
        assertTrue("tutor output started", speaking.await(5, TimeUnit.SECONDS))
        transport.mute(true)
        assertEquals("mute changes microphone input only", 0, tts.stopCalls)
        allowPlaybackToFinish.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (transport.turnInFlight && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf("Tutor output continues"), tts.spoken)
        transport.close()
    }
    @Test
    fun `commentary flushing the greeting preserves retained manual speech`() {
        overlappingOpeningSpeech(greetingFinishesFirst = true)
    }

    @Test
    fun `commentary finishing before the greeting preserves retained manual speech`() {
        overlappingOpeningSpeech(greetingFinishesFirst = false)
    }

    @Test
    fun `mute invalidates the retained prefix while commentary outlives the greeting`() {
        overlappingOpeningSpeech(greetingFinishesFirst = true, invalidate = "mute")
    }

    @Test
    fun `disconnect cancels the retained prefix while commentary outlives the greeting`() {
        overlappingOpeningSpeech(greetingFinishesFirst = true, invalidate = "disconnect")
    }

    private fun overlappingOpeningSpeech(greetingFinishesFirst: Boolean, invalidate: String? = null) {
        val source = FakeFrameSource()
        val engine = FakeTeachingClient()
        val generationGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.gate = generationGate
        val greetingSpeechGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val commentaryGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val greetingStarted = CountDownLatch(1)
        val commentaryStarted = CountDownLatch(1)
        val commentaryReturned = CountDownLatch(1)
        val answerSpoken = CountDownLatch(1)
        val speechCalls = java.util.concurrent.atomic.AtomicInteger()
        val sink = object : SpeechSink {
            override suspend fun initialize() = true
            override suspend fun speak(text: String): TtsStats? {
                if (text == "typed commentary") {
                    commentaryStarted.countDown()
                    // Model QUEUE_FLUSH: the interrupted greeting completes,
                    // but the replacement utterance still owns suppression.
                    if (greetingFinishesFirst) greetingSpeechGate.complete(Unit)
                    commentaryGate.await()
                    commentaryReturned.countDown()
                } else if (speechCalls.incrementAndGet() == 1) {
                    greetingStarted.countDown()
                    greetingSpeechGate.await()
                } else answerSpoken.countDown()
                return TtsStats(1, 1)
            }
            override fun stop() { commentaryGate.complete(Unit); greetingSpeechGate.complete(Unit) }
            override fun setSpeechRate(rate: Float) {}
            override fun setLanguage(languageTag: String) = true
        }
        val runtime = FakeWhisperRuntime()
        val scope = CoroutineScope(Dispatchers.IO)
        val transport = LocalVoiceTransport(null as android.content.Context?, scope,
            stt(createTempDir(), runtime), engine, sink,
            frameSourceProvider = { source }, frameDelayMs = 1, openOnStart = true)
        transport.bargeIn = false
        transport.manualTurn = true
        fun awaitState(description: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(description, predicate())
        }
        // Distinct amplitudes prove prefix order and exclude commentary echo.
        val retained = List(10) { speech(0.2f + it * 0.02f) }
        val expected = retained.flatMap { it.asIterable() }.toFloatArray()
        try {
            runBlocking { transport.connect(FakeProvider(), "base instructions", language = "en") }
            transport.send(buildAppend("session.instructions.append", "greet the learner"))
            awaitState("the greeting generation has started") { engine.received.size == 1 }
            retained.forEach { source.queue.add(it) }
            assertTrue(source.awaitFramesRead(10, 5_000))
            awaitState("the serial consumer has admitted the complete prefix") {
                transport.captureWindowState.contains("retained=20480")
            }
            transport.endTurn()
            generationGate.complete(Unit)
            assertTrue(greetingStarted.await(5, TimeUnit.SECONDS))
            transport.send(buildAppend("session.commentary.append", "typed commentary"))
            assertTrue(commentaryStarted.await(5, TimeUnit.SECONDS))
            if (greetingFinishesFirst) {
                awaitState("commentary keeps capture suppressed after the greeting") {
                    transport.captureWindowState.contains("opening=false speaking=true")
                }
            } else {
                commentaryGate.complete(Unit)
                assertTrue(commentaryReturned.await(5, TimeUnit.SECONDS))
                awaitState("the greeting keeps capture suppressed after commentary") { source.paused }
                assertTrue(transport.captureWindowState.contains("opening=true speaking=true"))
            }
            // This audio was captured during playback, unlike the retained prefix.
            repeat(4) { source.queue.add(speech(0.9f)) }
            assertEquals("no transcription before all speech finishes", 0, runtime.transcribeCalls.get())
            assertEquals(1, engine.received.size)
            when (invalidate) {
                "mute" -> {
                    transport.mute(true)
                    awaitState("mute invalidates the admitted prefix") {
                        transport.captureWindowState.contains("retained=0")
                    }
                    transport.mute(false)
                }
                "disconnect" -> transport.disconnect()
            }
            commentaryGate.complete(Unit)
            greetingSpeechGate.complete(Unit)
            if (invalidate == null) {
                awaitTurns(engine, 2)
                assertEquals("retained speech produces exactly one answer", 2, engine.received.size)
                assertTrue(answerSpoken.await(5, TimeUnit.SECONDS))
                assertArrayEquals("STT receives the complete ordered prefix without echo", expected, runtime.lastSamples!!, 0f)
                assertEquals(1, runtime.transcribeCalls.get())
            } else {
                awaitState("speech suppression and the retained prefix are released") {
                    !transport.captureWindowState.contains("speaking=true") &&
                        transport.captureWindowState.contains("retained=0")
                }
            }
            awaitState("playback has drained echo and the pipeline is idle") {
                (if (invalidate == "disconnect") source.stopped else source.drainedFrames >= 4) &&
                    !transport.turnInFlight &&
                    !transport.captureWindowState.contains("speaking=true")
            }
            assertEquals("neither echo nor a retained prefix is resent", if (invalidate == null) 2 else 1, engine.received.size)
            assertEquals(if (invalidate == null) 1 else 0, runtime.transcribeCalls.get())
        } finally {
            generationGate.complete(Unit)
            greetingSpeechGate.complete(Unit)
            commentaryGate.complete(Unit)
            transport.close()
            scope.cancel()
        }
    }

}
