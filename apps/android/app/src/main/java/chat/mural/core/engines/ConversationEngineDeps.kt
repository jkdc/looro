package chat.mural.core.engines

import android.content.Context
import chat.mural.network.APIClient
import kotlinx.serialization.json.Json

/**
 * Everything an engine adapter may need, built once in the composition root.
 * Keys for remote engines are resolved lazily through [keyLookup] (environment /
 * system properties) and never travel through BuildConfig.
 *
 * [appContext] is only used by engines that need device resources (the local
 * engine); remote adapters keep it null-untouched, which also keeps them
 * unit-testable on the JVM.
 */
class ConversationEngineDeps(
    val appContext: Context?,
    /** The existing BYOK OpenAI client; reused by the default engine. */
    val openai: APIClient,
    /** Resolves provider key environment variable names to key material, or null. */
    val keyLookup: (String) -> String?,
    val json: Json,
) {
    companion object {
        /** Default key lookup: environment first, then JVM system properties (unit tests). */
        fun defaultKeyLookup(): (String) -> String? = { name ->
            System.getenv(name) ?: System.getProperty(name)
        }
    }
}
