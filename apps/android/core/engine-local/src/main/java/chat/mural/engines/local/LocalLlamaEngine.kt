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

private const val LOG_TAG = "MuralLocalEngine"

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
        val maxOutputTokens: Int = 120,
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

    init {
        // Claim the model dir as this app's uid so adb-pushed models are readable.
        modelManager.ensureDirs()
    }

    val loadedModelName: String?
        get() = synchronized(this) { if (handle == 0L) null else loadedName }

    @Volatile private var loadedName: String? = null

    suspend fun complete(system: String, user: String, schemaJson: String? = null): LocalResult = mutex.withLock {
        ensureLoaded()
        val startNs = System.nanoTime()
        val outStats = LongArray(2)
        val abort = AtomicBoolean(false)
        val raw = StringBuilder()

        var firstTokenAtNs = 0L
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
                { piece ->
                    if (firstTokenAtNs == 0L) {
                        firstTokenAtNs = System.nanoTime()
                        android.util.Log.i(
                            LOG_TAG,
                            "LLM first token in ${(firstTokenAtNs - startNs) / 1_000_000} ms " +
                                "(prompt ${outStats[0]} tok)",
                        )
                    }
                    raw.append(piece)
                },
                abort,
                outStats,
            )
        }
        val generationMs = (System.nanoTime() - startNs) / 1_000_000
        android.util.Log.i(
            LOG_TAG,
            "LLM done: total=${generationMs}ms ttft=${if (firstTokenAtNs > 0) (firstTokenAtNs - startNs) / 1_000_000 else -1}ms " +
                "tokens=${outStats[1]} tok/s=${"%.1f".format(java.util.Locale.US, outStats[1] * 1000.0 / generationMs.coerceAtLeast(1))}",
        )

        when (status) {
            0 -> LocalResult(
                text = sanitizeCompletion(raw.toString()),
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

    /**
     * Some models echo the chat template's generation header as their first
     * tokens (Qwen emits `<|im_start|>assistant\n` because its GGUF template
     * omits it from the prompt). Strip any leading special-token renders and
     * assistant header so callers always see the model's actual content.
     */
    internal fun sanitizeCompletion(raw: String): String {
        var i = 0
        val n = raw.length
        while (i < n) {
            when {
                raw[i].isWhitespace() -> i++
                raw.startsWith("<|", i) -> {
                    val close = raw.indexOf("|>", i + 2)
                    if (close < 0) return "" // only special markers: no content
                    i = close + 2
                }
                else -> break
            }
        }
        var rest = raw.substring(i)
        // A bare header word left after the markers (e.g. "assistant\n").
        val headerEnd = rest.indexOf('\n')
        if (headerEnd in 0..24 && rest.take(headerEnd).matches(Regex("[A-Za-z ]+"))) {
            rest = rest.substring(headerEnd + 1)
        }
        return rest.trim()
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
        android.util.Log.i(LOG_TAG, "model loaded: ${model.name}")
    }
}
