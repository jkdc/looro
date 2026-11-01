package com.jkdc.app.engines.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class SttResult(val text: String, val processingMs: Long) {
    val isEmpty: Boolean get() = text.isBlank()
}

/** Failure raised by the local voice engine; [retryable] mirrors remote-engine semantics. */
class LocalEngineException(message: String, val retryable: Boolean = false) : IOException(message)

/** A model file discovered on device (see [LocalWhisperModelManager]). */
data class LocalModelFile(val file: File) {
    val sizeBytes: Long get() = file.length()
    val name: String get() = file.name
}

/**
 * On-device speech-to-text with whisper.cpp. Multilingual models receive the
 * session's spoken language as a hint; model files live under models/whisper.
 */
class WhisperStt(
    modelsDir: File,
    // 6 of the Pixel's 9 cores: ~20-40% faster decode than the 4-thread default
    // on big.LITTLE SoCs, with no measurable battery cost per short turn.
    private val threads: Int = 6,
    private val runtime: WhisperRuntime = JniWhisperRuntime,
) {
    private val modelManager = LocalWhisperModelManager(modelsDir)
    private val mutex = Mutex()
    private var handle: Long = 0L

    init {
        modelManager.ensureDirs()
    }

    val loadedModelName: String?
        get() = synchronized(this) { if (handle == 0L) null else loadedName }

    @Volatile private var loadedName: String? = null

    suspend fun transcribe(samples: FloatArray, language: String): SttResult = mutex.withLock {
        ensureLoaded()
        val startNs = System.nanoTime()
        val text = withContext(Dispatchers.Default) {
            runtime.transcribe(handle, samples, samples.size, threads, language)
        }
        SttResult(text = text.trim(), processingMs = (System.nanoTime() - startNs) / 1_000_000)
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
                "Whisper model not found. Push a .bin to ${modelManager.dir} (see docs/conversation-engines.md)",
            )
        handle = withContext(Dispatchers.IO) { runtime.init(model.file.absolutePath) }
        if (handle == 0L) {
            throw LocalEngineException("Whisper model failed to load: ${model.name}")
        }
        loadedName = model.name
        android.util.Log.i("AppLocalEngine", "whisper model loaded: ${model.name}")
    }
}

/** Finds whisper .bin models on device; prefers multilingual base over
 *  English-only. The more accurate small model measured 12-16 s of STT per
 *  turn on a Pixel 9 (the 30 s encoder window dominates) — conversation-
 *  killing — so base stays the default until decoding is fast enough. */
class LocalWhisperModelManager(private val modelsDir: File) {

    fun models(): List<LocalModelFile> =
        modelsDir.listFiles { f -> f.isFile && f.extension.equals("bin", ignoreCase = true) }
            ?.map { LocalModelFile(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** Prefers multilingual base over English-only (the app coaches many languages). */
    fun pick(): LocalModelFile? =
        models().minWithOrNull(
            compareBy<LocalModelFile> { preferenceRank(it.name) }.thenByDescending { it.sizeBytes },
        )

    val dir: File get() = modelsDir

    fun ensureDirs(): Boolean = modelsDir.mkdirs() || modelsDir.isDirectory

    companion object {
        private val FAMILIES = listOf("base", "small", "tiny")
        /** Larger than the whole family span, so any multilingual model outranks
         *  any English-only one (the app coaches many languages). */
        private const val ENGLISH_ONLY_PENALTY = 100

        fun preferenceRank(fileName: String): Int {
            val lower = fileName.lowercase()
            val family = FAMILIES.indexOfFirst { lower.contains(it) }.let { if (it >= 0) it else FAMILIES.size }
            val englishOnly = lower.contains(".en")
            return family * 10 + if (englishOnly) ENGLISH_ONLY_PENALTY else 0
        }
    }
}
