package com.jkdc.app.core.engines

import android.content.Context
import com.jkdc.app.core.AccountSession
import com.jkdc.app.network.APIClient
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
    /** The existing BYOK OpenAI client; reused by the default engine. Nullable
        so JVM tests can build deps without the OpenAI client — only the
        `openai` engine's registry row consumes it. */
    val openai: APIClient? = null,
    /** Resolves provider key environment variable names to key material, or null. */
    val keyLookup: (String) -> String?,
    val json: Json,
    /** Reads the signed-in app account session (the same AccountSessionStore the
        hosted client uses); the hosted-proxy engine authenticates with it. */
    val readSession: (suspend () -> AccountSession?)? = null,
    /** Resolves an engine id to its selected model id (Settings model picker,
        backed by EngineModelsStore); null keeps the engine's default model. */
    val modelLookup: ((String) -> String?)? = null,
    /** Resolves the server-chosen model for a call purpose (the Palique API
        provider, backed by ConversationPolicyStore); null means the policy
        is unavailable and the engine must refuse to guess. */
    val policyModel: ((com.jkdc.app.network.HelperPurpose?) -> String?)? = null,
) {
    companion object {
        /** Default key lookup: environment first, then JVM system properties (unit tests). */
        fun defaultKeyLookup(): (String) -> String? = { name ->
            System.getenv(name) ?: System.getProperty(name)
        }
    }
}
