package chat.mural.engines.local

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Testable seam over the llama.cpp JNI bridge. The real implementation is
 * [JniLlamaRuntime]; unit tests supply fakes.
 */
interface LlamaRuntime {
    fun init(modelPath: String, nCtx: Int, nThreads: Int, nBatch: Int): Long

    /**
     * @param outStats receives {promptTokens, generatedTokens}
     * @return 0 ok, -1 generic failure, -2 prompt too large, -3 aborted
     */
    fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        schema: String,
        useGrammar: Boolean,
        maxTokens: Int,
        temperature: Float,
        seed: Int,
        tokenCallback: TokenCallback?,
        abortFlag: AtomicBoolean,
        outStats: LongArray,
    ): Int

    fun free(handle: Long)

    /** Receives incremental output pieces on the generation (worker) thread. */
    fun interface TokenCallback {
        fun onToken(piece: String)
    }
}

/** Real JNI bridge; the native library is shared-library "mural_llama". */
object JniLlamaRuntime : LlamaRuntime {
    init {
        System.loadLibrary("mural_llama")
    }

    external override fun init(modelPath: String, nCtx: Int, nThreads: Int, nBatch: Int): Long

    external override fun generate(
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
    ): Int

    external override fun free(handle: Long)
}
