package com.jkdc.app.core

/** Course temarios: authored teaching content. The data lives in `CoursesData.kt`
    (generated from the canonical course JSON by `scripts/export_courses.py` —
    never edit it by hand); these are the hand-written shapes and the lookup surface. */

data class Course(val id: String, val languageID: String, val title: String, val subtitle: String,
                  val symbol: String, val description: String, val units: List<CourseUnit>)
data class CourseUnit(val id: String, val title: String, val subtitle: String, val level: String,
                      val themes: List<ConversationTheme>, val lessons: List<Lesson>)
data class Lesson(val id: String, val title: String, val goal: String, val intro: LessonIntro,
                  val expressions: List<TargetExpression>, val situation: String, val practice: LessonPractice,
                  val exercises: List<Exercise> = emptyList())
data class LessonIntro(val explanation: List<String>, val examples: List<ExampleLine>)
data class ExampleLine(val text: String, val meaning: String)
data class TargetExpression(val text: String, val meaning: String, val note: String)
data class LessonPractice(val questions: List<PracticeQuestion>)
data class PracticeQuestion(val prompt: String, val expected: List<String>, val focus: String, val watch: String)

/** Authored offline drill. `kind` is one of ExerciseEngine's choice/gap/order/written;
    `options` is filled for choice only; `<name>` spans in answers accept any text. */
data class Exercise(val id: String, val kind: String, val prompt: String, val options: List<String>,
                    val answers: List<String>, val explain: String)

object CourseRegistry {
    val all get() = CoursesData.all
    fun forLanguage(id: String) = all.filter { it.languageID == id }
    fun lesson(id: String) = all.flatMap { it.units }.flatMap { it.lessons }.firstOrNull { it.id == id }
}
