package com.jkdc.app.core.engines

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Reads provider API keys from a properties file in the app's external files
 * dir, so keys are deployed with the same adb-push flow as the local models
 * (Android apps cannot read environment variables at runtime).
 *
 * Expected file: <external-files-dir>/api_keys.properties
 *   ZHIPUAI_API_KEY=...
 *   DEEPSEEK_API_KEY=...
 *
 * Never committed; format is java properties.
 */
object ApiKeysStore {

    fun file(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "api_keys.properties")

    fun get(context: Context, name: String): String? = readFromFile(file(context), name)

    fun readFromFile(file: File, name: String): String? = runCatching {
        if (!file.isFile) return null
        val props = Properties()
        file.inputStream().use { props.load(it) }
        props.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
