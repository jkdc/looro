package com.jkdc.app.core.engines

import com.jkdc.app.network.TeachingClient

/**
 * Single construction point for conversation engine adapters.
 *
 * The engine id comes from `BuildConfig.CONVERSATION_ENGINE`, resolved at build
 * time from (highest first): `-Plooro.conversationEngine`, `local.properties`,
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
    )

    val available: List<EngineInfo> = listOf(
        EngineInfo("openai", "OpenAI (personal key)"),
        EngineInfo("deepseek", "DeepSeek API"),
        EngineInfo("glm", "Zhipu GLM API"),
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
}
