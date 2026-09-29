package com.jkdc.app.core

/** The discrete speech-rate choices offered in Settings (multipliers on the
    system TTS rate). Ids are the plain numeric strings used for storage. */
object SpeechRates {
    val ids = listOf("0.6", "0.8", "1.0", "1.2")

    fun idOf(rate: Double?): String = format(rate ?: 1.0)

    fun parse(id: String): Double? = id.toDoubleOrNull()

    fun format(value: Double): String = when (value) {
        0.6 -> "0.6"
        0.8 -> "0.8"
        1.2 -> "1.2"
        else -> "1.0"
    }
}
