package chat.mural.engines.local

/** Testable seam over the whisper.cpp JNI bridge. */
interface WhisperRuntime {
    fun init(modelPath: String): Long

    fun transcribe(handle: Long, samples: FloatArray, numSamples: Int, threads: Int, language: String): String

    fun free(handle: Long)
}

/** Real JNI bridge; symbols live in the shared library "mural_whisper". */
object JniWhisperRuntime : WhisperRuntime {
    init {
        System.loadLibrary("mural_whisper")
    }

    external override fun init(modelPath: String): Long

    external override fun transcribe(handle: Long, samples: FloatArray, numSamples: Int, threads: Int, language: String): String

    external override fun free(handle: Long)
}
