package com.jkdc.app.core

/** Listening comprehension: authored texts read aloud by the local voice and
    answered in writing. Questions reuse the Exercise shape so ExerciseEngine
    grades them unchanged; the data lives in `ListeningData` (generated from the
    canonical JSON in shared/listening by scripts/export_listening.py — never
    edit it by hand). */
data class ListeningText(val id: String, val languageID: String, val title: String, val level: String,
                         val text: String, val questions: List<Exercise>)

object ListeningRegistry {
    val all get() = ListeningData.all
    fun forLanguage(id: String) = all.filter { it.languageID == id }
    fun text(id: String) = all.firstOrNull { it.id == id }
}
