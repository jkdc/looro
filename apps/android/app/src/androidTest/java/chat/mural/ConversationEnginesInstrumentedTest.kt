package chat.mural

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.engines.ConversationEngineDeps
import chat.mural.core.engines.ConversationEngineFactory
import chat.mural.core.engines.OpenAICompatibleEngine
import chat.mural.core.engines.RemoteEngineConfig
import chat.mural.engines.local.LocalWhisperModelManager
import chat.mural.network.APIClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of every conversation engine implementation.
 *
 * The former `local` (on-device llama.cpp LLM) tests were removed on
 * 2026-09-17 together with the engine. The whisper STT leg of the local voice
 * pipeline is retained (deepseek/glm use it) and still covered on device.
 *
 * Manual setup for the whisper leg: install the app -> run
 * [setupWhisperModelDir] (the model dir must be created BY THE APP) -> push
 * the model with `scripts/push-local-models.sh` -> run the whisper tests.
 * Without a pushed model those tests skip via org.junit.Assume (e.g. CI,
 * which never pushes one).
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

    private fun defaultWhisperModelsDir(): java.io.File =
        java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "models/whisper")

    // ---- openai (adapter construction only: no network in this suite) ----

    @Test
    fun openaiAdapterIsConstructedAndDelegates() {
        val engine = ConversationEngineFactory.create("openai", deps())
        assertTrue(engine is chat.mural.core.engines.OpenAIEngineAdapter)
    }

    // ---- whisper model dir (created by the app, before any adb push) ----

    /**
     * Run this BEFORE pushing the whisper model with adb: the model directory
     * must be created BY THE APP (not by adb shell) or FUSE/MediaProvider denies
     * the app read access to it. Sequence: install -> run this test -> adb push
     * model -> run the whisper engine tests.
     */
    @Test(timeout = 60_000)
    fun setupWhisperModelDir() {
        val created = defaultWhisperModelsDir().let { it.mkdirs() || it.isDirectory }
        assertTrue("could not create ${defaultWhisperModelsDir().absolutePath}", created)
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

    // ---- local voice: STT leg with the REAL whisper model (TTS round-trip) ----

    @Test(timeout = 900_000)
    fun whisperTranscribesTtsAudioOnDevice() = runBlocking {
        // Same predicate the STT runtime uses in LocalWhisperModelManager.pick():
        // without a .bin in the model dir, stt.transcribe would throw
        // LocalEngineException("Whisper model not found…"). Skip instead (CI
        // runs every androidTest class but never pushes a model).
        val modelExists = LocalWhisperModelManager(defaultWhisperModelsDir()).models().isNotEmpty()
        assumeTrue(
            "Whisper model not pushed; run scripts/push-local-models.sh — see class KDoc",
            modelExists,
        )

        val tts = chat.mural.engines.local.LocalTtsEngine(context)
        assertTrue(tts.initialize())
        val wav = java.io.File(context.cacheDir, "stt-test.wav")
        wav.delete()
        assertTrue(tts.synthesizeToFile("Yesterday I go to the beach. It was amazing.", wav))

        val pcm = chat.mural.engines.local.WavReader.read16bitMono(wav)
        val resampled = chat.mural.engines.local.WavReader.resample(pcm.samples, pcm.sampleRate)

        val stt = chat.mural.engines.local.WhisperStt(defaultWhisperModelsDir())
        val result = stt.transcribe(resampled, "en")
        assertTrue("expected transcription, got: ''", result.text.isNotBlank())
        assertTrue("expected 'beach' in: ${result.text}", result.text.lowercase().contains("beach"))
        assertTrue(result.processingMs > 0)
        tts.release()
    }

    // ---- whisper: missing model gives an actionable error (no crash) ----

    @Test(timeout = 60_000)
    fun whisperWithEmptyModelDirFailsGracefully() = runBlocking {
        val emptyDir = java.io.File(context.cacheDir, "no-models/whisper").apply { mkdirs() }
        val stt = chat.mural.engines.local.WhisperStt(emptyDir)
        try {
            stt.transcribe(FloatArray(1600), "en")
            fail("expected LocalEngineException")
        } catch (e: chat.mural.engines.local.LocalEngineException) {
            assertTrue(e.message!!.contains("Whisper model not found"))
        }
        Unit
    }
}
