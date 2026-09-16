package chat.mural.engines.local

import java.io.File

data class LocalModelFile(val file: File) {
    val sizeBytes: Long get() = file.length()
    val name: String get() = file.name
}

/**
 * Finds GGUF models pushed to the app's external files dir, under models/llm
 * with the .gguf extension. Prefers known-good conversation models by name
 * (same order as the POC), never hardcodes a single model: pushing a different
 * .gguf and relaunching switches models.
 */
class LocalModelManager(private val modelsDir: File) {

    constructor(externalFilesDir: File?, internalFilesDir: File) : this(
        File(externalFilesDir ?: internalFilesDir, "models/llm"),
    )

    fun models(): List<LocalModelFile> =
        modelsDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
            ?.map { LocalModelFile(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** Preferred model (best name rank, largest first on ties); null when none. */
    fun pick(): LocalModelFile? =
        models().minWithOrNull(
            compareBy<LocalModelFile> { preferenceRank(it.name) }.thenByDescending { it.sizeBytes },
        )

    val dir: File get() = modelsDir

    /**
     * Creates the model directory as the app (must not be done by adb shell:
     * FUSE/MediaProvider denies the app read access to dirs created by other
     * uids under Android/data). Returns true when the dir exists afterwards.
     */
    fun ensureDirs(): Boolean = modelsDir.mkdirs() || modelsDir.isDirectory

    companion object {
        // Lower = preferred. Kept in sync with the POC's validated models.
        private val PREFERENCE = listOf(
            "qwen2.5-3b-instruct", "qwen2.5", "qwen3", "qwen",
        )

        fun preferenceRank(fileName: String): Int {
            val lower = fileName.lowercase()
            PREFERENCE.forEachIndexed { index, key ->
                if (lower.contains(key)) return index
            }
            return PREFERENCE.size
        }
    }
}
