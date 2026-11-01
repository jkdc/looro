package com.jkdc.app.core.engines

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** One selectable model of a remote provider's catalog. */
data class EngineModel(val id: String, val name: String, val isFree: Boolean)

/**
 * Live catalog of a remote provider's models, plus the per-engine selection
 * store. Only the opencode gateway exposes a public `/models` listing today;
 * the shape is OpenAI-style `{"object":"list","data":[…]}` with a `cost.text`
 * pricing block per model, where free models price both input and output at 0.
 */
object EngineModelCatalog {

    private val json = Json { ignoreUnknownKeys = true }

    /** Shown when the live catalog cannot be fetched: mirrors the server preset
        (`LLM_PRESETS.opencode.models` in services/api/src/llm-config.ts). The
        preset carries no pricing, so no free markers are claimed there. */
    val OPENCODE_FALLBACK: List<EngineModel> = listOf(
        "glm-5.3-flash", "glm-5.3", "glm-5.2", "kimi-k3", "kimi-k2.7-code",
        "deepseek-v4.1-flash", "deepseek-v4-pro", "mimo-v2.6-pro", "longcat-2.0",
    ).map { EngineModel(it, it, isFree = false) }

    /** GET `{baseUrl}/models`; null on HTTP error, unreachable host or a
        payload without a usable model list, so callers fall back to the
        preset. The key is optional: the listing is public, a configured key
        is forwarded so subscription-scoped catalogs resolve correctly. */
    suspend fun fetchModels(
        baseUrl: HttpUrl,
        apiKey: String?,
        client: OkHttpClient,
    ): List<EngineModel>? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("models").build())
            .get()
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                parseModels(response.body?.string().orEmpty())
            }
        } catch (_: IOException) {
            null
        }
    }

    /** Pure parse; null when the payload carries no usable model list. A model
        is free when its text input AND output prices are both 0 (the shape
        opencode's `/models` uses, e.g. `beam-free`). Entries without an id are
        skipped; a list with none usable counts as a failure. */
    internal fun parseModels(payload: String): List<EngineModel>? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val entries = root["data"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        val models = entries.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val id = str(obj, "id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            EngineModel(id, str(obj, "name")?.takeIf { it.isNotBlank() } ?: id, isFree(obj))
        }
        return models.takeIf { it.isNotEmpty() }
    }

    private fun str(obj: JsonObject, key: String): String? =
        obj[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun isFree(obj: JsonObject): Boolean =
        obj.price("cost", "text", "input") == 0.0 && obj.price("cost", "text", "output") == 0.0

    private fun JsonObject.at(vararg path: String): JsonObject? {
        var current: JsonObject? = this
        for (key in path) current = current?.get(key) as? JsonObject
        return current
    }

    private fun JsonObject.price(vararg path: String): Double? =
        (at(*path)?.get("price") as? JsonPrimitive)?.doubleOrNull
}

/** Persists the chosen model per engine id. Selections are validated against
    the catalog at choice time; [resolveEngineModelSelection] re-checks on
    read so a model the catalog dropped falls back to the engine default. */
class EngineModelsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun selectedRaw(engineId: String): String? =
        preferences.getString(key(engineId), null)?.trim()?.takeIf { it.isNotEmpty() }

    fun select(engineId: String, modelId: String) {
        preferences.edit().putString(key(engineId), modelId.trim()).commit()
    }

    private fun key(engineId: String) = "selected_$engineId"

    private companion object {
        const val PREFERENCES = "engine_model_selection"
    }
}

/** A stored model id only survives while the catalog still offers it; blank
    or unknown stored values never win over the default. Pure for unit tests. */
fun resolveEngineModelSelection(stored: String?, availableIds: List<String>, default: String): String =
    stored?.takeIf { it.isNotBlank() && it in availableIds } ?: default
