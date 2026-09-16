package chat.mural.engines.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class LocalLlamaEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRuntime : LlamaRuntime {
        var handleValue = 42L
        var initCalls = 0
        var freeCalls = 0
        var failInit = false
        var emitTokens: String = "42"
        var lastSchema: String? = null
        var lastRoles: Array<String>? = null
        var outStats = longArrayOf(30, 9)
        var status = 0
        var generationMsFloor = false

        override fun init(modelPath: String, nCtx: Int, nThreads: Int, nBatch: Int): Long {
            initCalls++
            if (failInit) return 0
            return handleValue
        }

        override fun generate(
            handle: Long,
            roles: Array<String>,
            contents: Array<String>,
            schema: String,
            useGrammar: Boolean,
            maxTokens: Int,
            temperature: Float,
            seed: Int,
            tokenCallback: LlamaRuntime.TokenCallback?,
            abortFlag: AtomicBoolean,
            outStats: LongArray,
        ): Int {
            lastSchema = schema.ifBlank { null }
            lastRoles = roles
            if (tokenCallback != null && emitTokens.isNotEmpty()) tokenCallback.onToken(emitTokens)
            outStats[0] = this.outStats[0]
            outStats[1] = this.outStats[1]
            return status
        }

        override fun free(handle: Long) {
            freeCalls++
        }
    }

    private fun engineWithModel(runtime: FakeRuntime): LocalLlamaEngine {
        val dir = tmp.newFolder("models").resolve("llm").apply { mkdirs() }
        File(dir, "qwen2.5-3b-instruct-q4_k_m.gguf").writeBytes(ByteArray(10))
        return LocalLlamaEngine(dir, runtime = runtime)
    }

    @Test
    fun `complete returns text and honest usage`() = runTest {
        val runtime = FakeRuntime().apply { outStats = longArrayOf(120, 48) }
        val engine = engineWithModel(runtime)

        val result = engine.complete("Be a coach", "Hello", schemaJson = null)

        assertEquals("42", result.text)
        assertEquals(120L, result.promptTokens)
        assertEquals(48L, result.generatedTokens)
        assertEquals(arrayOf("system", "user").toList(), runtime.lastRoles!!.toList())
        assertEquals(null, runtime.lastSchema)
    }

    @Test
    fun `schema json is forwarded to the runtime with grammar enabled`() = runTest {
        val runtime = FakeRuntime().apply { emitTokens = "{\"a\":1}" }
        val engine = engineWithModel(runtime)

        val result = engine.complete("sys", "user", schemaJson = """{"type":"object"}""")

        assertEquals("""{"type":"object"}""", runtime.lastSchema)
        assertEquals("{\"a\":1}", result.text)
    }

    @Test
    fun `missing model reports the expected path and does not crash the process`() = runTest {
        val engine = LocalLlamaEngine(tmp.newFolder("empty"), runtime = FakeRuntime())
        try {
            engine.complete("s", "u")
            fail("expected LocalEngineException")
        } catch (e: LocalEngineException) {
            assertTrue(e.message!!.contains("Local model not found"))
            assertTrue(e.message!!.contains("models/llm") || e.message!!.contains(".gguf"))
        }
    }

    @Test
    fun `init failure surfaces the model name`() = runTest {
        val runtime = FakeRuntime().apply { failInit = true }
        val engine = engineWithModel(runtime)
        try {
            engine.complete("s", "u")
            fail("expected LocalEngineException")
        } catch (e: LocalEngineException) {
            assertTrue(e.message!!.contains("qwen2.5-3b-instruct"))
        }
    }

    @Test
    fun `generation error code maps to retryable failure`() = runTest {
        val runtime = FakeRuntime().apply { status = -1 }
        val engine = engineWithModel(runtime)
        try {
            engine.complete("s", "u")
            fail("expected LocalEngineException")
        } catch (e: LocalEngineException) {
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `sanitizer strips template header echo`() {
        val engine = engineWithModel(FakeRuntime())
        val qwen = "<|im_start|>assistant\n{\"reply\":\"hi\"}"
        assertEquals("{\"reply\":\"hi\"}", engine.sanitizeCompletion(qwen))
        assertEquals("plain text", engine.sanitizeCompletion("  plain text"))
        // non-ChatML output passes through untouched
        assertEquals("Just text.", engine.sanitizeCompletion("Just text."))
        // header markers only
        assertEquals("", engine.sanitizeCompletion("<|im_start|>assistant\n"))
    }

    @Test
    fun `model manager prefers known conversation models`() {
        val dir = tmp.newFolder("prefs").resolve("llm").apply { mkdirs() }
        File(dir, "tiny-random.gguf").writeBytes(ByteArray(100))
        File(dir, "qwen2.5-3b-instruct-q4_k_m.gguf").writeBytes(ByteArray(200))
        val manager = LocalModelManager(dir)
        assertEquals("qwen2.5-3b-instruct-q4_k_m.gguf", manager.pick()?.name)
        assertEquals(2, manager.models().size)
    }
}
