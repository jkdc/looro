package chat.mural.core.voice

import android.content.Context

/**
 * Persiste el "modo principiante" de voz (turno cerrado con botón).
 * Fuera del archive a propósito: es una preferencia de UX del motor local,
 * no parte del contrato cross-platform de aprendizaje.
 */
class VoiceManualTurnStore(context: Context) {

    private val prefs = context.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)

    fun get(): Boolean = prefs.getBoolean(KEY, false)

    fun set(enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    companion object {
        private const val KEY = "voice_manual_turn"
    }
}
