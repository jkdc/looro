package com.jkdc.app.core.engines

import com.jkdc.app.BuildConfig
import com.jkdc.app.network.HostedConfiguration
import com.jkdc.app.network.TeachingClient

/**
 * Single construction point for conversation engine adapters.
 *
 * The engine id comes from `BuildConfig.CONVERSATION_ENGINE`, resolved at build
 * time from (highest first): `-Papp.conversationEngine`, `local.properties`,
 * `APP_CONVERSATION_ENGINE`, default `openai`.
 *
 * The former `local` (on-device llama.cpp) engine was removed on 2026-09-17:
 * on-device LLM inference proved too slow for voice turns (45-110 s/turn on a
 * Pixel 9). The whisper STT + system TTS voice pipeline in `:core:engine-local`
 * is retained — `deepseek`/`glm` still run STT/TTS on device and the LLM
 * remotely.
 *
 * Adding a provider = add one registry row + (only when not OpenAI-compatible)
 * one adapter class. Nothing else in the app changes.
 */
object ConversationEngineFactory {

    data class EngineInfo(val id: String, val label: String)

    /** Rows that are available in this build. Kept explicit for discoverability. */
    private val registry: Map<String, (ConversationEngineDeps) -> TeachingClient> = mapOf(
        "openai" to { deps -> OpenAIEngineAdapter(deps.openai) },
        "deepseek" to { deps -> OpenAICompatibleEngine(RemoteEngineConfig.DEEPSEEK, deps) },
        "glm" to { deps -> OpenAICompatibleEngine(RemoteEngineConfig.GLM, deps) },
        "glm-4.5" to { deps -> OpenAICompatibleEngine(RemoteEngineConfig.GLM_45, deps) },
        "hosted-proxy" to { deps -> hostedProxy(deps) },
    )

    val available: List<EngineInfo> = listOf(
        EngineInfo("openai", "OpenAI (personal key)"),
        EngineInfo("deepseek", "DeepSeek API"),
        EngineInfo("glm", "Zhipu GLM 5.3 Flash"),
        EngineInfo("glm-4.5", "Zhipu GLM 4.5 Flash"),
        EngineInfo("hosted-proxy", "Palique (tokens)"),
    )

    /** Validates and normalizes an engine id; throws listing the known ids. */
    fun validate(id: String): String {
        val normalized = id.trim().lowercase()
        require(normalized in available.map { it.id }) {
            "Unknown conversation engine '$id'. Valid engines: ${available.joinToString { it.id }}"
        }
        return normalized
    }

    fun create(engineId: String, deps: ConversationEngineDeps): TeachingClient {
        val id = validate(engineId)
        val builder = registry[id]
            ?: throw EngineException("Engine '$id' is declared but not available in this build (missing module or provider wiring)")
        return builder(deps)
    }

    /** The token-billing proxy: the same OpenAI-compatible engine pointed at the
        backend's `/v1/proxy/responses`, authenticated by the app session bearer
        (the AccountSessionStore the hosted client reads — never a second token
        store) and always non-streaming (v1 contract). Voice STT/TTS stays on
        device; only the LLM call is proxied and metered server-side. */
    private fun hostedProxy(deps: ConversationEngineDeps): TeachingClient {
        val readSession = deps.readSession
            ?: throw EngineException("hosted-proxy engine needs the app account session source")
        val origin = HostedConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN)?.origin
            ?: throw EngineException("hosted-proxy engine needs a configured Palique API origin")
        return OpenAICompatibleEngine(
            RemoteEngineConfig.HOSTED_PROXY,
            deps,
            baseUrl = origin,
            completionsPath = "v1/proxy/responses",
            credential = { readSession()?.takeIf { it.isValid(System.currentTimeMillis()) }?.accessToken },
            proxyErrorMessages = true,
        )
    }
}
