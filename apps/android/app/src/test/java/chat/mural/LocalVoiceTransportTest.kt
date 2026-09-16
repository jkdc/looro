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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
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
        override suspend fun respond(instructions: String, input: String, schema: kotlinx.serialization.json.JsonObject?, search: Boolean, purpose: chat.mural.network.HelperPurpose?): chat.mural.network.APIResult {
            received.add(instructions to input)
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
        assertEquals("Nice! Tell me more about it.", tts.spoken.single())
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

    private fun buildAppend(type: String, content: String): JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("type", type)
        put("content", kotlinx.serialization.json.JsonPrimitive(content))
    }
}
