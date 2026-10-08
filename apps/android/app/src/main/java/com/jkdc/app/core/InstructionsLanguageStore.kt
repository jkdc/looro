package com.jkdc.app.core

import android.content.Context

/** Instruction-language preference (placement-test): which language explains
    UI helpers like the placement test. `subtitles` follows the meaning
    language (today's behaviour); an explicit code pins a translated language.
    Generic by design — future screens can consume the same setting. */
class InstructionsLanguageStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun get(): String = preferences.getString(KEY, null) ?: SUBTITLES

    fun set(value: String) {
        if (value in OPTIONS) preferences.edit().putString(KEY, value).commit()
    }

    companion object {
        const val SUBTITLES = "subtitles"
        val OPTIONS = listOf(SUBTITLES, "en", "es")
        private const val KEY = "instructions_language"
        private const val PREFERENCES = "instructions_language"
    }
}
