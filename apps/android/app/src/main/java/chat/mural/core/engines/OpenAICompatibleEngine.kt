package chat.mural.core.engines

import chat.mural.network.APIResult
import chat.mural.network.APIUsage
import chat.mural.network.HelperPurpose
import chat.mural.network.TeachingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Per-provider constants for any OpenAI-compatible /chat/completions backend. */
data class RemoteEngineConfig(
    val id: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Environment variable holding the provider key (resolved at runtime, never stored in the build). */
    val keyEnvVar: String,
) {
    companion object {
        val DEEPSEEK = RemoteEngineConfig("deepseek", "https://api.deepseek.com/v1/", "deepseek-chat", "DEEPSEEK_API_KEY")
        val GLM = RemoteEngineConfig("glm", "https://open.bigmodel.cn/api/paas/v4/", "glm-4-flash", "ZHIPUAI_API_KEY")
    }
}

/**
 * Adapter for any OpenAI-compatible chat completions API (DeepSeek, GLM, free
 * gateways, self-hosted servers). One class, one config row per provider:
 * adding a compatible provider must never require a new adapter.
 *
 * Structured output (`schema`) degrades gracefully: JSON mode is requested when
 * the schema is present and the schema itself is embedded in the system prompt,
 * because /chat/completions has no cross-provider json_schema guarantee.
 */
class OpenAICompatibleEngine(
    private val config: RemoteEngineConfig,
    private val deps: ConversationEngineDeps,
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl? = null,
) : TeachingClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?,
    ): APIResult = withContext(Dispatchers.IO) {
        val key = deps.keyLookup(config.keyEnvVar)
            ?: throw EngineException("${config.id} engine needs the ${config.keyEnvVar} environment variable or system property")

        val systemPrompt = if (schema != null) {
            "$instructions\n\nRespond with exactly one JSON object conforming to this JSON schema:\n$schema"
        } else {
            instructions
        }
        val body = buildJsonObject {
            put("model", config.defaultModel)
            put("messages", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("role", "system"); put("content", systemPrompt) },
                buildJsonObject { put("role", "user"); put("content", input) },
            )))
            if (schema != null) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            put("stream", false)
        }.toString()

        val url = (baseUrl ?: config.defaultBaseUrl.toHttpUrl()).newBuilder()
            .addPathSegments("chat/completions").build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val result = runCatching { client.newCall(request).execute() }
            .getOrElse { throw EngineException("${config.id} engine unreachable: ${it.message}", retryable = true) }
        result.use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw EngineException(
                    "${config.id} engine HTTP ${response.code}: ${payload.take(200)}",
                    retryable = response.code in listOf(429) || response.code >= 500,
                )
            }
            parseResponse(payload)
        }
    }

    internal fun parseResponse(payload: String): APIResult {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw EngineException("${config.id} engine returned invalid JSON") }
        val choice = runCatching {
            root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
        }.getOrNull()
            ?: throw EngineException("${config.id} engine response missing choices[0].message.content")
        val usage = root["usage"]?.jsonObject
        return APIResult(
            text = choice,
            sources = emptyList(),
            usage = APIUsage(
                input = usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                output = usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            ),
        )
    }
}
