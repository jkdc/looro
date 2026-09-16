package chat.mural.engines.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Failure raised by the local engine; [retryable] mirrors remote-engine semantics. */
class LocalEngineException(message: String, val retryable: Boolean = false) : IOException(message)

/**
 * On-device conversation engine: llama.cpp over a GGUF model, fully offline.
 *
 * Serves Mural's `TeachingClient` shape: system=[instructions], user=[input],
 * and when a JSON schema is provided the output is grammar-constrained to that
 * schema (llama.cpp structured output via the model's own chat template).
 *
 * The model loads lazily on first completion so an `engine=local` build without
 * models present starts fine and reports an actionable error instead of crashing.
 */
class LocalLlamaEngine(
    modelsDir: File,
    private val config: Config = Config(),
    private val runtime: LlamaRuntime = JniLlamaRuntime,
) {
    data class Config(
        val contextSize: Int = 2048,
        val threads: Int = 4,
        val batchSize: Int = 256,
        val maxOutputTokens: Int = 400,
        val temperature: Float = 0.7f,
        val useJsonGrammar: Boolean = true,
        val seed: Int = 42, // fixed: stability over variety
    )

    data class LocalResult(
        val text: String,
        val raw: String,
        val promptTokens: Long,
        val generatedTokens: Long,
        val generationMs: Long,
    ) {
        val tokensPerSecond: Double
            get() = if (generationMs > 0) generatedTokens * 1000.0 / generationMs else 0.0
    }

    private val modelManager = LocalModelManager(modelsDir)
    private val mutex = Mutex()
    private var handle: Long = 0L

    val loadedModelName: String?
        get() = synchronized(this) { if (handle == 0L) null else loadedName }

    @Volatile private var loadedName: String? = null

    suspend fun complete(system: String, user: String, schemaJson: String? = null): LocalResult = mutex.withLock {
        ensureLoaded()
        val startNs = System.nanoTime()
        val outStats = LongArray(2)
        val abort = AtomicBoolean(false)
        val raw = StringBuilder()

        val status = withContext(Dispatchers.Default) {
            runtime.generate(
                handle,
                arrayOf("system", "user"),
                arrayOf(system, user),
                schemaJson ?: "",
                config.useJsonGrammar && schemaJson != null,
                config.maxOutputTokens,
                config.temperature,
                config.seed,
                { piece -> raw.append(piece) },
                abort,
                outStats,
            )
        }
        val generationMs = (System.nanoTime() - startNs) / 1_000_000

        when (status) {
            0 -> LocalResult(
                text = raw.toString().trim(),
                raw = raw.toString(),
                promptTokens = outStats[0],
                generatedTokens = outStats[1],
                generationMs = generationMs,
            )
            -2 -> throw LocalEngineException(
                "Local model: prompt exceeded context size (${config.contextSize} tokens)",
            )
            -3 -> throw LocalEngineException("Local generation aborted", retryable = true)
            else -> throw LocalEngineException("Local model generation failed (code $status)", retryable = true)
        }
    }

    fun release() {
        synchronized(this) {
            if (handle != 0L) {
                runtime.free(handle)
                handle = 0L
                loadedName = null
            }
        }
    }

    private suspend fun ensureLoaded() {
        if (handle != 0L) return
        val model = modelManager.pick()
            ?: throw LocalEngineException(
                "Local model not found. Push a .gguf to ${modelManager.dir} (see docs/conversation-engines.md)",
            )
        handle = withContext(Dispatchers.IO) {
            runtime.init(model.file.absolutePath, config.contextSize, config.threads, config.batchSize)
        }
        if (handle == 0L) {
            throw LocalEngineException("Local model failed to load: ${model.name}")
        }
        loadedName = model.name
    }
}
