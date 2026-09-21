package com.jkdc.app.core.engines

import android.content.Context
import com.jkdc.app.network.APIResult
import com.jkdc.app.network.HelperPurpose
import com.jkdc.app.network.TeachingClient
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** Persists the locally selected engine id; defaults to the build-time engine. */
class LocalEngineSelectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Validated on read; an unknown stored id falls back to the build default. */
    fun selected(defaultId: String): String = preferences.getString(KEY, null)
        ?.takeIf { id -> runCatching { ConversationEngineFactory.validate(id) }.isSuccess }
        ?: defaultId

    fun select(id: String) {
        ConversationEngineFactory.validate(id)
        preferences.edit().putString(KEY, id).commit()
    }

    private companion object {
        const val PREFERENCES = "local_engine_selection"
        const val KEY = "selected"
    }
}

/**
 * Delegates to the locally selected engine, resolving the selection per call
 * and creating adapters lazily. The UI forbids changes while a conversation
 * runs, so a switch naturally applies to the next conversation.
 */
class LocalEngineSwitcher(
    private val selected: () -> String,
    private val create: (String) -> TeachingClient,
) : TeachingClient {
    constructor(deps: ConversationEngineDeps, selected: () -> String) :
        this(selected, { id -> ConversationEngineFactory.create(id, deps) })

    private val engines = ConcurrentHashMap<String, TeachingClient>()

    private fun active(): TeachingClient = engines.computeIfAbsent(ConversationEngineFactory.validate(selected()), create)

    override val keyEnvVar: String?
        get() = runCatching { active().keyEnvVar }.getOrNull()

    override fun cancelInFlight() {
        engines.values.forEach { it.cancelInFlight() }
    }

    override suspend fun respond(instructions: String, input: String, schema: JsonObject?,
                                 search: Boolean, purpose: HelperPurpose?): APIResult =
        active().respond(instructions, input, schema, search, purpose)
}
