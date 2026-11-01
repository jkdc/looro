package com.jkdc.app.core.engines

import com.jkdc.app.core.TokenProxyBlock
import com.jkdc.app.network.APIResult
import com.jkdc.app.network.APIUsage
import com.jkdc.app.network.HelperPurpose
import com.jkdc.app.network.TeachingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicReference

/** Per-provider constants for any OpenAI-compatible /chat/completions backend. */
data class RemoteEngineConfig(
    val id: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Environment variable holding the provider key (resolved at runtime, never stored in the build). */
    val keyEnvVar: String,
    /** Provider-specific body fields merged into every request (e.g. Zhipu thinking switch). */
    val extraBody: JsonObject? = null,
    /** Header that must carry a fresh UUID per request (the OpenCode Go gateway
        routes per conversation and rejects session-less requests). */
    val sessionHeader: String? = null,
) {
    companion object {
        val DEEPSEEK = RemoteEngineConfig("deepseek", "https://api.deepseek.com/v1/", "deepseek-chat", "DEEPSEEK_API_KEY")
        // Zhipu z.ai (international) platform. Both models ride the coding-plan
        // endpoint: coding-plan keys carry no general platform balance, so the
        // default /api/paas/v4 answers 1113 (insufficient balance) even for
        // paid models, while the coding endpoint serves both flashes.
        val GLM = RemoteEngineConfig(
            id = "glm",
            defaultBaseUrl = "https://api.z.ai/api/coding/paas/v4/",
            defaultModel = "glm-5.3-flash",
            keyEnvVar = "ZHIPUAI_API_KEY",
            // GLM-5.3 always thinks: the API rejects `thinking.type=disabled`
            // (error 1210), so the lowest effort level is requested instead.
            extraBody = buildJsonObject {
                put("thinking", buildJsonObject { put("type", "enabled"); put("level", "low") })
            },
        )
        // The free-tier flash model, selectable from Settings. Unlike 5.3 it
        // supports switching thinking off, and shares one rate-limit pool.
        val GLM_45 = RemoteEngineConfig(
            id = "glm-4.5",
            defaultBaseUrl = "https://api.z.ai/api/coding/paas/v4/",
            defaultModel = "glm-4.5-flash",
            keyEnvVar = "ZHIPUAI_API_KEY",
            extraBody = buildJsonObject { put("thinking", buildJsonObject { put("type", "disabled") }) },
        )
        // The Palique token proxy relays the body verbatim to the operator's
        // provider (`PROXY_PROVIDER_URL`), so the model must match that provider;
        // the seeded rate version 2026-09-minimax-m pins MiniMax M as the launch
        // provider. Auth is the app session bearer, never an environment key.
        val HOSTED_PROXY = RemoteEngineConfig("hosted-proxy", "", "MiniMax-M2", "")
        // OpenCode Go gateway (same preset as the server's LLM_PRESETS.opencode):
        // one key unlocks a multi-vendor catalog, selectable from Settings via
        // EngineModelCatalog; glm-5.3-flash is the preset's default model.
        val OPENCODE = RemoteEngineConfig(
            id = "opencode",
            defaultBaseUrl = "https://opencode.ai/zen/go/v1",
            defaultModel = "glm-5.3-flash",
            keyEnvVar = "OPENCODE_API_KEY",
            sessionHeader = "x-opencode-session",
        )
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
    // Streaming replies can take minutes on slow providers/networks: the default
    // OkHttp 10 s read timeout aborts them mid-flight (seen on Pixel over WiFi).
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
    private val baseUrl: HttpUrl? = null,
    /** Path appended to [baseUrl]; the proxy relays `v1/proxy/responses` instead
        of the default OpenAI-compatible `chat/completions`. */
    private val completionsPath: String = "chat/completions",
    /** Credential overriding [deps.keyLookup]: the hosted-proxy engine passes the
        app session bearer (the same AccountSessionStore the hosted client reads),
        so no second token store and no environment variable exist for it. */
    private val credential: (suspend () -> String?)? = null,
    /** Turns 402/403/429 proxy rejections into readable messages instead of a raw payload dump. */
    private val proxyErrorMessages: Boolean = false,
    /** Model override resolved per call (the Settings model picker); null or a
        null result keeps [RemoteEngineConfig.defaultModel]. */
    private val modelOverride: (() -> String?)? = null,
) : TeachingClient {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** Shown when the hosted-proxy engine has no valid app session to authenticate with. */
        const val PROXY_SIGN_IN_MESSAGE = "Sign in to your Palique account to use Palique (tokens)."
    }

    /** The in-flight HTTP call, tracked so [cancelInFlight] can abort a hung request. */
    private val inFlight = AtomicReference<Call?>()

    /** Lets callers (e.g. readiness gates) check this engine's key without a network round trip.
        A credential-backed engine (hosted-proxy) authenticates by account session, so no
        environment key exists and gates must not demand one. */
    override val keyEnvVar: String? get() = if (credential != null) null else config.keyEnvVar

    /** Cancels the in-flight HTTP call, if any, so session teardown is not held by a hung request. */
    override fun cancelInFlight() {
        inFlight.getAndSet(null)?.cancel()
    }

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?,
    ): APIResult = withContext(Dispatchers.IO) {
        val key = if (credential != null) {
            credential.invoke() ?: throw EngineException(PROXY_SIGN_IN_MESSAGE)
        } else {
            deps.keyLookup(config.keyEnvVar)
                ?: throw EngineException("${config.id} engine needs the ${config.keyEnvVar} environment variable or system property")
        }

        val systemPrompt = if (schema != null) {
            "$instructions\n\nRespond with exactly one JSON object conforming to this JSON schema:\n$schema"
        } else {
            instructions
        }
        val body = buildJsonObject {
            put("model", modelOverride?.invoke() ?: config.defaultModel)
            put("messages", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("role", "system"); put("content", systemPrompt) },
                buildJsonObject { put("role", "user"); put("content", input) },
            )))
            if (schema != null) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            config.extraBody?.let { extra -> extra.forEach { (k, v) -> put(k, v) } }
            put("stream", false)
        }.toString()

        // HOSTED_PROXY never carries a defaultBaseUrl (the origin is runtime
        // configuration); a blank resolved URL must fail clearly, not with a raw
        // IllegalArgumentException from "".toHttpUrl().
        val resolved = baseUrl ?: config.defaultBaseUrl.takeIf { it.isNotBlank() }?.toHttpUrl()
            ?: throw EngineException("${config.id} engine needs a base URL (no default configured for ${config.id})")
        val url = resolved.newBuilder().addPathSegments(completionsPath).build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .apply {
                // One fresh UUID per request, the same criterion the server's
                // opencode transport uses (one session id per generation run).
                if (config.sessionHeader != null) {
                    header(config.sessionHeader, java.util.UUID.randomUUID().toString())
                    header("User-Agent", "palique-android/1.0")
                }
            }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        inFlight.set(call)
        val result = try {
            call.execute()
        } catch (e: java.io.IOException) {
            // A cancel() from cancelInFlight() usually means the session ended:
            // let cancellation win over an "unreachable" engine failure.
            ensureActive()
            throw EngineException("${config.id} engine unreachable: ${e.message}", retryable = true)
        } finally {
            inFlight.compareAndSet(call, null)
        }
        result.use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (proxyErrorMessages) throw proxyFailure(response.code, payload)
                throw EngineException(
                    "${config.id} engine HTTP ${response.code}: ${payload.take(200)}",
                    retryable = response.code in listOf(429) || response.code >= 500,
                )
            }
            parseResponse(payload)
        }
    }

    /** Readable mirror of the proxy's error matrix (docs/token-billing.md): the
        EngineException message is what the voice transport surfaces to the UI,
        and proxyBlock routes 402/403 to the matching commerce surface. */
    private fun proxyFailure(status: Int, payload: String): EngineException {
        val block = when (status) {
            402 -> TokenProxyBlock.BALANCE_EXHAUSTED
            403 -> TokenProxyBlock.UNLOCK_REQUIRED
            else -> null
        }
        val readable = when (status) {
            402 -> "Your Palique token balance is used up. Buy more tokens to keep practising."
            403 -> "Palique (tokens) needs a one-time unlock purchase before this account can use it."
            429 -> "Palique (tokens) is still handling a previous request. Try again in a moment."
            else -> null
        }
        return EngineException(
            readable ?: "${config.id} engine HTTP $status: ${payload.take(200)}",
            retryable = status == 429 || status >= 500,
            proxyBlock = block,
        )
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
