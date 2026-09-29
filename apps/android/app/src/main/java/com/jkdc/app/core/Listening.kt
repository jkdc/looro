package com.jkdc.app.core

/** Listening comprehension: authored texts read aloud by the local voice and
    answered in writing. Questions reuse the Exercise shape so ExerciseEngine
    grades them unchanged; the data lives in `ListeningData` (generated from the
    canonical JSON in shared/listening by scripts/export_listening.py — never
    edit it by hand). `topic` labels the text; `unitID` links it to a course
    unit when one exists; `skills`/`exposureSkills` reference the shared
    skill catalog. */
data class ListeningText(val id: String, val languageID: String, val title: String, val level: String,
                         val topic: String, val unitID: String? = null,
                         val text: String, val questions: List<Exercise>,
                         val skills: List<String> = emptyList(), val exposureSkills: List<String> = emptyList())

object ListeningRegistry {
    private val LEVEL_ORDER = listOf("A1", "A2", "B1", "B2", "C1", "C2")
    val all get() = ListeningData.all

    /** A language's texts in teaching order: level, then course-unit order,
        then id — so the Listen tab can group by level and follow the temario. */
    fun forLanguage(id: String): List<ListeningText> {
        val unitOrder = CourseRegistry.all.firstOrNull { it.languageID == id }?.units?.map { it.id } ?: emptyList()
        return all.filter { it.languageID == id }.sortedWith(compareBy(
            { levelRank(it.level) },
            { it.unitID?.let(unitOrder::indexOf)?.takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE },
            { it.id }))
    }

    fun text(id: String) = all.firstOrNull { it.id == id }

    private fun levelRank(level: String) =
        LEVEL_ORDER.indexOf(level).takeIf { it >= 0 } ?: LEVEL_ORDER.size
}
