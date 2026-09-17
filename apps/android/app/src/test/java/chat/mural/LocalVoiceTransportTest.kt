package chat.mural

import chat.mural.core.voice.LocalVoiceTransport
import chat.mural.engines.local.AudioFrameSource
import chat.mural.engines.local.SpeechSink
import chat.mural.engines.local.TtsStats
import chat.mural.engines.local.WhisperRuntime
import chat.mural.engines.local.WhisperStt
import chat.mural.network.APIClient
import chat.mural.network.TeachingClient
import java.util.concurrent.CountDownLatch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class LocalVoiceTransportTest {

    private class FakeFrameSource : AudioFrameSource {
        val queue = ArrayDeque<FloatArray>()
        var started = false
        var stopped = false
        override fun start(): Boolean { started = true; return true }
        override fun readFrame(): FloatArray? = queue.removeFirstOrNull()
        override fun stop() { stopped = true }
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
        override suspend fun respond(instructions: String, input: String, schema: kotlinx.serialization.json.JsonObject?, search: Boolean, purpose: chat.mural.network.HelperPurpose?): chat.mural.network.APIResult {
            received.add(instructions to input)
            gate?.await()
            return chat.mural.network.APIResult(text = reply, sources = emptyList(), usage = chat.mural.network.APIUsage(10, 5))
        }
    }

    private class FakeTts : SpeechSink {
        val spoken = mutableListOf<String>()
        var initialized = false
        override suspend fun initialize(): Boolean { initialized = true; return true }
        override suspend fun speak(text: String): TtsStats? { spoken.add(text); return TtsStats(1, 1) }
        override fun stop() {}
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

    class FakeProvider : chat.mural.network.LiveSessionProvider {
        override suspend fun createLiveSession(request: chat.mural.network.LiveSessionRequest) =
            throw UnsupportedOperationException()
    }

    private fun awaitLatch(latch: CountDownLatch, seconds: Long = 10): Boolean = latch.await(seconds, TimeUnit.SECONDS)

    // ---- tests ------------------------------------------------------------------

    @Test
    fun `connect emits session created and started`() {
        val (events, tts) = events { }
        assertEquals("mural.session.created", events[0]["type"]!!.jsonPrimitive.content)
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
    fun `renderHistory formats openai style messages`() {
        val transport = LocalVoiceTransport(
            null as android.content.Context?, CoroutineScope(Dispatchers.IO),
            stt(createTempDir(), FakeWhisperRuntime()), FakeTeachingClient(), FakeTts(),
            frameSourceProvider = { FakeFrameSource() }, frameDelayMs = 0,
        )
        val history = JsonArray(listOf(
            buildJsonObject { put("role", JsonPrimitive("user")); put("content", JsonPrimitive("Hi")) },
            buildJsonObject { put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive("Hello")) },
        ))
        val rendered = transport.renderHistory(history)
        assertTrue(rendered.contains("User: Hi"))
        assertTrue(rendered.contains("Assistant: Hello"))
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

    private fun buildAppend(type: String, content: String): JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("type", type)
        put("content", kotlinx.serialization.json.JsonPrimitive(content))
    }
}
