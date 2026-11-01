package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test

class ExerciseEngineTest {
    private fun exercise(kind: String, prompt: String = "p", options: List<String> = emptyList(),
                         answers: List<String>, explain: String = "e") =
        Exercise(id = "test-exercise", kind = kind, prompt = prompt, options = options, answers = answers, explain = explain)

    @Test fun `normalize strips punctuation case and spacing`() {
        assertEquals("i'm ana", ExerciseEngine.normalize("  I'm Ana.  "))
        assertEquals("where are you from", ExerciseEngine.normalize("¿Where ARE you FROM?"))
        assertEquals("one two", ExerciseEngine.normalize("one   two"))
    }

    @Test fun `choice compares the picked option exactly`() {
        val e = exercise(ExerciseEngine.CHOICE, options = listOf("is", "are"), answers = listOf("is"))
        assertTrue(ExerciseEngine.isCorrect(e, "is"))
        assertTrue(ExerciseEngine.isCorrect(e, " is "))
        assertFalse(ExerciseEngine.isCorrect(e, "IS"))
        assertFalse(ExerciseEngine.isCorrect(e, "are"))
    }

    @Test fun `order compares normalized token sequences`() {
        val e = exercise(ExerciseEngine.ORDER, answers = listOf("I am from Spain"))
        assertTrue(ExerciseEngine.isCorrect(e, "I am from Spain"))
        assertTrue(ExerciseEngine.isCorrect(e, "i am from spain"))
        assertFalse(ExerciseEngine.isCorrect(e, "am I from Spain"))
    }

    @Test fun `gap accepts the normalized missing word`() {
        val e = exercise(ExerciseEngine.GAP, prompt = "They ___ my friends.", answers = listOf("are"))
        assertTrue(ExerciseEngine.isCorrect(e, "are"))
        assertTrue(ExerciseEngine.isCorrect(e, " Are "))
        assertFalse(ExerciseEngine.isCorrect(e, "is"))
    }

    @Test fun `written accepts variants and placeholder spans`() {
        val e = exercise(ExerciseEngine.WRITTEN, answers = listOf("I'm <name>", "I am <name>", "My name is <name>"))
        assertTrue(ExerciseEngine.isCorrect(e, "I'm Ana"))
        assertTrue(ExerciseEngine.isCorrect(e, "I am Maria."))
        assertTrue(ExerciseEngine.isCorrect(e, "my name is Inma"))
        assertFalse(ExerciseEngine.isCorrect(e, "I'm"))
        assertFalse(ExerciseEngine.isCorrect(e, "Ana am I"))
    }

    @Test fun `every authored exercise grades its own answers correct`() {
        for (lesson in CourseRegistry.all.flatMap { it.units }.flatMap { it.lessons }) {
            for (exercise in lesson.exercises) {
                val attempt = when (exercise.kind) {
                    ExerciseEngine.CHOICE -> exercise.answers.single()
                    ExerciseEngine.ORDER -> exercise.answers.single()
                    else -> exercise.answers.first()
                }
                assertTrue("${exercise.id}: '$attempt' must grade correct", ExerciseEngine.isCorrect(exercise, attempt))
            }
        }
    }
}
