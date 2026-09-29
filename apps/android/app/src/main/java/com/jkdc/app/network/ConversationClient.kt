package com.jkdc.app.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class HelperPurpose(val wireValue: String) {
    MEANING("meaning"), ASSESSMENT("assessment"), LOOKUP("lookup"), DELEGATION("delegation"),
    TYPED_REPLY("typed_reply"), TOPIC("topic"), HELP("help")
}

interface TeachingClient {
    /**
     * Environment/system property this engine resolves its provider key from,
     * or null when key handling lives outside the engine (the OpenAI
     * credential store behind the hosted-provider gates). Gates like
     * `AppViewModel.cloudReady` use it to decide whether the active engine
     * is usable without an OpenAI key.
     */
    val keyEnvVar: String? get() = null

    /**
     * Best-effort interruption of the engine's in-flight work so ending a
     * session is not held by a hung network call. Defaulted no-op: engines
     * without blocking calls need nothing.
     */
    fun cancelInFlight() {}

    suspend fun respond(instructions: String, input: String, schema: JsonObject? = null,
        search: Boolean = false, purpose: HelperPurpose? = null): APIResult
}

data class LiveSessionRequest(val sdp: String, val instructions: String, val history: JsonArray = JsonArray(emptyList()),
    val language: String? = null, val requestID: String = UUID.randomUUID().toString(), val requestedMilliseconds: Long? = null) {
    override fun toString() = "LiveSessionRequest([redacted])"
}

interface LiveSessionProvider {
    suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection
}

/** Lease metadata is opaque to the native audio layer. It never contains credentials or prompt text. */
interface LiveSessionLease {
    val sessionID: String
    suspend fun requestClose()
}

data class LiveSessionConnection(val sdp: String, val providerSessionID: String?, val lease: LiveSessionLease? = null) {
    override fun toString() = "LiveSessionConnection([redacted])"
}

/** A create result can arrive after local disposal. Either ordering must still request server cutoff. */
internal class LiveSessionOwnership(private val cleanupScope: CoroutineScope) {
    private var lease: LiveSessionLease? = null
    private var disposed = false
    private val requested = AtomicBoolean(false)

    @Synchronized fun adopt(value: LiveSessionLease?) {
        check(lease == null) { "A voice attempt can own only one hosted lease" }
        lease = value
        if (disposed) closeLease()
    }

    @Synchronized fun close() { disposed = true; closeLease() }

    private fun closeLease() {
        val current = lease ?: return
        if (!requested.compareAndSet(false, true)) return
        // Local cleanup must not wait for the network, and a UI cancellation must not cancel cutoff.
        cleanupScope.launch { try { current.requestClose() } catch (_: Exception) { } }
    }
}
