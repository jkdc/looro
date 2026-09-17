package chat.mural.core.voice

import kotlinx.serialization.json.JsonObject

/**
 * The voice-transport surface MuralViewModel consumes. Implemented by
 * [chat.mural.network.LiveTransport] (WebRTC/OpenAI) and
 * [chat.mural.core.voice.LocalVoiceTransport] (on-device pipeline), so the
 * ViewModel and UI carry no engine-specific branches.
 *
 * Threading contract: implementations may invoke [onEvent], [onFailure] and
 * [onLevels] from any thread and concurrently (the local transport emits from
 * the Main connect flow, the IO listen loop and Default greeting/commentary
 * coroutines). Consumers must therefore serialize handling themselves;
 * MuralViewModel funnels every callback through a single-consumer channel and
 * performs all session-state mutation on that one coroutine.
 */
interface VoiceTransport {
    var onEvent: ((JsonObject) -> Unit)?
    var onFailure: ((String) -> Unit)?
    var onLevels: ((Double, Double) -> Unit)?

    suspend fun connect(
        api: chat.mural.network.LiveSessionProvider,
        instructions: String,
        history: kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(emptyList()),
        language: String? = null,
    )

    /** Delivers `session.<kind>.append` style events to the transport. */
    fun send(event: JsonObject): Boolean

    fun mute(muted: Boolean)

    fun close()

    fun disconnect()
}
