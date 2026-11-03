package com.jkdc.app.core

/**
 * UI language options for the in-app per-app language selector.
 *
 * Pure model: the AppCompatDelegate plumbing (reading/writing application
 * locales, recreation) stays in the UI layer so this stays JVM-testable.
 * `system` means "follow the device language"; the other ids map to stable
 * BCP-47 language tags. The set mirrors the LanguageRegistry learning
 * languages, each backed by a `values-XX/` resource folder.
 */
object AppLanguage {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val SPANISH = "es"
    const val FRENCH = "fr"
    const val GERMAN = "de"
    const val ITALIAN = "it"
    const val PORTUGUESE = "pt"
    const val NORWEGIAN = "nb"
    const val CHINESE = "zh"

    val OPTIONS = listOf(SYSTEM, ENGLISH, SPANISH, FRENCH, GERMAN, ITALIAN, PORTUGUESE, NORWEGIAN, CHINESE)

    /** Native-language display names (language names stay in their own language). */
    val NATIVE_LABELS = mapOf(ENGLISH to "English", SPANISH to "Español", FRENCH to "Français",
        GERMAN to "Deutsch", ITALIAN to "Italiano", PORTUGUESE to "Português",
        NORWEGIAN to "Norsk", CHINESE to "中文")

    private val TAGS = mapOf(ENGLISH to "en", SPANISH to "es", FRENCH to "fr", GERMAN to "de",
        ITALIAN to "it", PORTUGUESE to "pt", NORWEGIAN to "nb", CHINESE to "zh")

    /** Stable BCP-47 tag for a concrete language, or null to follow the system. */
    fun tag(id: String): String? = TAGS[id]

    /** Selector id for a stored language-tag list; empty or unknown means system. */
    fun fromTags(tags: List<String>): String {
        val tag = tags.firstOrNull { it.isNotBlank() }?.take(2)?.lowercase() ?: return SYSTEM
        return TAGS.entries.firstOrNull { it.value == tag }?.key ?: SYSTEM
    }
}
