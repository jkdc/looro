package com.jkdc.app.core

/**
 * UI language options for the in-app per-app language selector.
 *
 * Pure model: the AppCompatDelegate plumbing (reading/writing application
 * locales, recreation) stays in the UI layer so this stays JVM-testable.
 * `system` means "follow the device language"; the other ids map to stable
 * BCP-47 language tags supported by both string resource folders.
 */
object AppLanguage {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val SPANISH = "es"

    val OPTIONS = listOf(SYSTEM, ENGLISH, SPANISH)

    /** Stable BCP-47 tag for a concrete language, or null to follow the system. */
    fun tag(id: String): String? = when (id) {
        ENGLISH -> "en"
        SPANISH -> "es"
        else -> null
    }

    /** Selector id for a stored language-tag list; empty or unknown means system. */
    fun fromTags(tags: List<String>): String = when (tags.firstOrNull { it.isNotBlank() }?.take(2)?.lowercase()) {
        "en" -> ENGLISH
        "es" -> SPANISH
        else -> SYSTEM
    }
}
