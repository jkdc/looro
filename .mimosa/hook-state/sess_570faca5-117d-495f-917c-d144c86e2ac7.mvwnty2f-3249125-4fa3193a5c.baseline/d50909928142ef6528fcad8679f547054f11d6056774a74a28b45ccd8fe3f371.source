package com.jkdc.app.core.voice

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

    /** Milisegundos de silencio que cierran el turno (modo automático). */
    fun getSilenceEndMs(): Long =
        prefs.getLong(KEY_SILENCE_MS, DEFAULT_SILENCE_MS)

    fun setSilenceEndMs(ms: Long) {
        prefs.edit().putLong(KEY_SILENCE_MS, ms.coerceIn(600, 3_000)).apply()
    }

    companion object {
        private const val KEY = "voice_manual_turn"
        private const val KEY_SILENCE_MS = "silence_end_ms"
        const val DEFAULT_SILENCE_MS = 1_200L
    }
}
