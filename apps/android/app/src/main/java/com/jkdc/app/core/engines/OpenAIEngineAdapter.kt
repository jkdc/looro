package com.jkdc.app.core.engines

import com.jkdc.app.network.APIClient
import com.jkdc.app.network.APIResult
import com.jkdc.app.network.HelperPurpose
import com.jkdc.app.network.TeachingClient
import kotlinx.serialization.json.JsonObject

/**
 * Default engine: delegates to the existing BYOK OpenAI client, preserving
 * today's behavior exactly. Exists so the factory is uniform and evolution of
 * the OpenAI path happens in one place.
 */
class OpenAIEngineAdapter(private val api: APIClient) : TeachingClient {

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?,
    ): APIResult = api.respond(instructions, input, schema, search, purpose)
}
