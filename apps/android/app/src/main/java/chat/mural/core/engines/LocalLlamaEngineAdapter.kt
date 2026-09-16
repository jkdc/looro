package chat.mural.core.engines

import chat.mural.engines.local.LocalLlamaEngine
import chat.mural.network.APIResult
import chat.mural.network.APIUsage
import chat.mural.network.HelperPurpose
import chat.mural.network.TeachingClient
import kotlinx.serialization.json.JsonObject

/**
 * Bridges the `:core:engine-local` module into Mural's `TeachingClient` contract.
 * Lives in `:app` so the local module never needs to know about app types.
 *
 * Degradations (documented in docs/conversation-engines.md):
 *  - `search=true` is ignored (no web on-device).
 *  - output tokens map to `APIUsage`; there are no cost nanoUSD on-device.
 */
class LocalLlamaEngineAdapter(private val engine: LocalLlamaEngine) : TeachingClient {

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?,
    ): APIResult {
        val result = engine.complete(
            system = instructions,
            user = input,
            schemaJson = schema?.toString(),
        )
        return APIResult(
            text = result.text,
            sources = emptyList(),
            usage = APIUsage(
                input = result.promptTokens.toInt(),
                output = result.generatedTokens.toInt(),
            ),
        )
    }

    companion object {
        /** Model directory (app external files) expected by the local engine. */
        fun defaultModelsDir(context: android.content.Context) =
            java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "models/llm")
    }
}
