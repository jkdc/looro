package chat.mural

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.engines.ConversationEngineDeps
import chat.mural.core.engines.ConversationEngineFactory
import chat.mural.core.engines.OpenAICompatibleEngine
import chat.mural.core.engines.RemoteEngineConfig
import chat.mural.network.APIClient
import chat.mural.network.TeachingClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of every conversation engine implementation.
 *
 * The `local` tests run the REAL llama.cpp stack against the REAL GGUF model
 * previously pushed to the app's external files dir (models/llm). They are slow
 * on the emulator (model load + CPU generation) — timeouts are generous.
 */
@RunWith(AndroidJUnit4::class)
class ConversationEnginesInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun deps(keyLookup: (String) -> String? = { null }) = ConversationEngineDeps(
        appContext = context,
        openai = APIClient(null, OkHttpClient(), "https://api.openai.com/v1/".toHttpUrl()),
        keyLookup = keyLookup,
        json = Json,
    )

    companion object {
        // One engine for the whole class: loading the 2GB model per test leaks
        // native contexts (handle-based) and crashes the process by the last test.
        private lateinit var sharedLocalAdapter: chat.mural.core.engines.LocalLlamaEngineAdapter

        @BeforeClass
        @JvmStatic
        fun createSharedEngine() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val deps = ConversationEngineDeps(ctx, APIClient(null, OkHttpClient(), "https://api.openai.com/v1/".toHttpUrl()), { null }, Json)
            sharedLocalAdapter = chat.mural.core.engines.LocalLlamaEngineAdapter(
                chat.mural.engines.local.LocalLlamaEngine(
                    chat.mural.core.engines.LocalLlamaEngineAdapter.defaultModelsDir(ctx),
                ),
            )
            runBlocking { sharedLocalAdapter.warmup() }
        }

        @AfterClass
        @JvmStatic
        fun releaseSharedEngine() {
            sharedLocalAdapter.release()
        }
    }

    // ---- openai (adapter construction only: no network in this suite) ----

    @Test
    fun openaiAdapterIsConstructedAndDelegates() {
        val engine = ConversationEngineFactory.create("openai", deps())
        assertTrue(engine is chat.mural.core.engines.OpenAIEngineAdapter)
    }

    // ---- local (real model, real JNI, on-device) ----

    /**
     * Run this BEFORE pushing the GGUF with adb: the model directory must be
     * created BY THE APP (not by adb shell) or FUSE/MediaProvider denies the
     * app read access to it. Sequence: install -> run this test -> adb push
     * model -> run the local engine tests.
     */
    @Test(timeout = 60_000)
    fun setupLocalModelDir() {
        val base = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
        for (dir in listOf(java.io.File(base, "llm"), java.io.File(base, "whisper"))) {
            val created = dir.mkdirs() || dir.isDirectory
            assertTrue("could not create ${dir.absolutePath}", created)
        }
    }


    @Test(timeout = 900_000)
    fun localEngineGeneratesReplyWithRealModel() = runBlocking {
        val engine = ConversationEngineFactory.create("local", deps())
        val result = engine.respond(
            instructions = "You are a friendly English coach. Reply with one short sentence.",
            input = "Hello! How are you today?",
            schema = null,
            search = false,
            purpose = null,
        )
        assertTrue("expected non-empty reply, got: '${result.text}'", result.text.isNotBlank())
        assertTrue("expected prompt tokens > 0, got ${result.usage.input}", result.usage.input > 0)
        assertTrue("expected generated tokens > 0, got ${result.usage.output}", result.usage.output > 0)
    }

    @Test(timeout = 900_000)
    fun localEngineHonorsJsonSchema() = runBlocking {
        val engine = ConversationEngineFactory.create("local", deps())
        val schema = Json.parseToJsonElement(
            """{"type":"object","properties":{"reply":{"type":"string"}},"required":["reply"]}""",
        ).jsonObject
        val result = sharedLocalAdapter.respond(
            instructions = "You are a coach.",
            input = "Greet the student warmly.",
            schema = schema,
            search = false,
            purpose = null,
        )
        val parsed = Json.parseToJsonElement(result.text).jsonObject
        val reply = parsed["reply"]?.jsonPrimitive?.content
        assertTrue("expected a 'reply' string field in: ${result.text}", !reply.isNullOrBlank())
    }

    // ---- deepseek / glm (on-device: construction + key handling; full HTTP
    // contract is covered by the JVM MockWebServer suite, since plaintext
    // localhost is blocked by the app's network security policy) ----

    @Test(timeout = 60_000)
    fun compatibleEnginesAreConstructedOnDevice() {
        assertTrue(
            ConversationEngineFactory.create("deepseek", deps { if (it == "DEEPSEEK_API_KEY") "k1" else null })
                is OpenAICompatibleEngine,
        )
        assertTrue(
            ConversationEngineFactory.create("glm", deps { if (it == "ZHIPUAI_API_KEY") "k2" else null })
                is OpenAICompatibleEngine,
        )
    }

    @Test(timeout = 60_000)
    fun missingRemoteKeyFailsWithProviderMessage() {
        val glm = OpenAICompatibleEngine(RemoteEngineConfig.GLM, deps(), client = OkHttpClient())
        try {
            runBlocking { glm.respond("i", "in", null, false, null) }
            fail("expected EngineException")
        } catch (e: chat.mural.core.engines.EngineException) {
            assertTrue(e.message!!.contains("ZHIPUAI_API_KEY"))
        }
    }

    // ---- local LLM benchmark: prefill + generation speed on THIS device ----

    @Test(timeout = 1_800_000)
    fun llmSpeedBenchmarkWithRealisticPrompt() = runBlocking {
        // The shared engine was warmed during createSharedEngine().
        val client: chat.mural.network.TeachingClient = sharedLocalAdapter

        // ~600-token realistic system prompt (same ballpark as TeachingPolicy.voice)
        val filler = ("You are a friendly language coach. Keep replies short and natural. " +
            "Ask one follow-up question at a time. Be encouraging. ").repeat(14)
        val instructions = "You are a friendly English speaking coach. " + filler
        val report = File(context.getExternalFilesDir(null), "bench.txt")
        val lines = mutableListOf<String>()
        lines.add("bench start ${java.util.Date()}")
        for (round in 1..3) {
            val t0 = System.currentTimeMillis()
            val result = client.respond(instructions, "Hello! How are you today?", null, false, null)
            val total = System.currentTimeMillis() - t0
            val out = result.usage.output
            val line = "round $round: total=${total}ms tokens=$out " +
                "tok/s=${"%.1f".format(java.util.Locale.US, out * 1000.0 / total.coerceAtLeast(1))} " +
                "replyLen=${result.text.length} prompt=${result.usage.input}"
            lines.add(line)
            android.util.Log.i("BENCH", line)
            File(context.getExternalFilesDir(null), "bench.txt").writeText(lines.joinToString("\n"))
        }
        lines.add("bench end")
        File(context.getExternalFilesDir(null), "bench.txt").writeText(lines.joinToString("\n"))
    }

    // ---- local voice: STT leg with the REAL whisper model (TTS round-trip) ----

    @Test(timeout = 900_000)
    fun whisperTranscribesTtsAudioOnDevice() = runBlocking {
        val tts = chat.mural.engines.local.LocalTtsEngine(context)
        assertTrue(tts.initialize())
        val wav = java.io.File(context.cacheDir, "stt-test.wav")
        wav.delete()
        assertTrue(tts.synthesizeToFile("Yesterday I go to the beach. It was amazing.", wav))

        val pcm = chat.mural.engines.local.WavReader.read16bitMono(wav)
        val resampled = chat.mural.engines.local.WavReader.resample(pcm.samples, pcm.sampleRate)

        val sttDir = chat.mural.core.engines.LocalLlamaEngineAdapter.defaultWhisperModelsDir(context)
        val stt = chat.mural.engines.local.WhisperStt(sttDir)
        val result = stt.transcribe(resampled, "en")
        assertTrue("expected transcription, got: ''", result.text.isNotBlank())
        assertTrue("expected 'beach' in: ${result.text}", result.text.lowercase().contains("beach"))
        assertTrue(result.processingMs > 0)
        tts.release()
    }

    // ---- local: missing model gives an actionable error (no crash) ----

    @Test(timeout = 60_000)
    fun localEngineWithEmptyModelDirFailsGracefully() = runBlocking {
        val emptyDir = java.io.File(context.cacheDir, "no-models/llm").apply { mkdirs() }
        val engine = chat.mural.engines.local.LocalLlamaEngine(emptyDir)
        try {
            engine.complete("s", "u")
            fail("expected LocalEngineException")
        } catch (e: chat.mural.engines.local.LocalEngineException) {
            assertTrue(e.message!!.contains("Local model not found"))
        }
        Unit
    }
}
