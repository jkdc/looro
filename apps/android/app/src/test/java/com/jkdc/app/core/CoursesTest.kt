package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test

class CoursesTest {
    @Test fun `foundations course is registered for english`() {
        val courses = CourseRegistry.forLanguage("en")
        assertEquals(6, courses.size)
        val course = courses.first { it.id == "en-foundations" }
        assertEquals("en-foundations", course.id)
        assertEquals(27, course.units.size)
        assertEquals(82, course.units.sumOf { it.lessons.size })
        assertTrue(course.units.first().themes.size >= 2)
    }

    @Test fun `every unit carries its level label`() {
        val levels = CourseRegistry.forLanguage("en").first { it.id == "en-foundations" }.units.map { it.level }
        assertTrue(levels.all { it.isNotBlank() })
        assertEquals(listOf("A1", "A1", "A1", "A1", "A1", "A1", "A1", "A1", "A1", "A1", "A1", "A1", "A1", "A1", "A2", "A2", "A2", "A2", "A2", "A2", "B1", "B1", "B1", "B1", "B2", "B2", "C1"), levels)
    }

    @Test fun `the five English professional tracks are registered`() {
        val trackIDs = listOf("en-work-track", "en-business-track", "en-technology-track",
            "en-hospitality-track", "en-healthcare-track")
        for (id in trackIDs) {
            val track = CourseRegistry.forLanguage("en").first { it.id == id }
            assertEquals(1, track.units.size)
            assertEquals(3, track.units.sumOf { it.lessons.size })
        }
    }

    @Test fun `each supported course language carries exactly one foundations`() {
        // Spanish has the full temario block; others have 2 units for now.
        val esCourse = CourseRegistry.forLanguage("es").first { it.id.endsWith("-foundations") }
        assertTrue("es should have 27+ units", esCourse.units.size >= 27)
        for (language in listOf("fr", "it", "de", "pt")) {
            val courses = CourseRegistry.forLanguage(language)
            assertEquals(2, courses.size) // foundations + work track
            val course = courses.first { it.id.endsWith("-foundations") }
            assertTrue("$language should have 6+ units", course.units.size >= 6)
        }
        assertTrue(CourseRegistry.forLanguage("nb").isEmpty())
        assertTrue(CourseRegistry.forLanguage("zh").isEmpty())
    }

    @Test fun `lesson lookup finds an authored lesson from every unit`() {
        assertEquals("The verb “to be”", CourseRegistry.lesson("en-foundations-u1-to-be")?.title)
        for (unit in CourseRegistry.forLanguage("en").first().units) {
            val lesson = CourseRegistry.lesson(unit.lessons.first().id)
            assertNotNull("missing lesson for unit ${unit.id}", lesson)
        }
        assertNull(CourseRegistry.lesson("missing-lesson"))
    }

    @Test fun `ids are unique across the registry`() {
        val all = CourseRegistry.all
        val lessons = all.flatMap { it.units }.flatMap { it.lessons }
        assertEquals(lessons.size, lessons.map { it.id }.toSet().size)
        val units = all.flatMap { it.units }
        assertEquals(units.size, units.map { it.id }.toSet().size)
    }

    @Test fun `every lesson follows the authored shape`() {
        for (lesson in CourseRegistry.all.flatMap { it.units }.flatMap { it.lessons }) {
            assertTrue(lesson.id, lesson.intro.explanation.size >= 2)
            assertTrue(lesson.id, lesson.intro.examples.size >= 5)
            assertTrue(lesson.id, lesson.expressions.size >= 5)
            assertTrue(lesson.id, lesson.practice.questions.size >= 5)
        }
    }

    @Test fun `every practice question carries its guidance`() {
        for (lesson in CourseRegistry.all.flatMap { it.units }.flatMap { it.lessons }) {
            for (question in lesson.practice.questions) {
                assertTrue(lesson.id, question.expected.isNotEmpty())
                assertTrue(lesson.id, question.focus.isNotBlank())
                assertTrue(lesson.id, question.watch.isNotBlank())
            }
        }
    }

    @Test fun `every lesson carries a graded exercise set`() {
        val kinds = setOf(ExerciseEngine.CHOICE, ExerciseEngine.GAP, ExerciseEngine.ORDER, ExerciseEngine.WRITTEN)
        for (lesson in CourseRegistry.all.flatMap { it.units }.flatMap { it.lessons }) {
            assertTrue(lesson.id, lesson.exercises.size >= 3)
            assertEquals(lesson.id, lesson.exercises.size, lesson.exercises.map { it.id }.toSet().size)
            for (exercise in lesson.exercises) {
                assertTrue(exercise.id, exercise.kind in kinds)
                assertTrue(exercise.id, exercise.answers.isNotEmpty())
                assertTrue(exercise.id, exercise.explain.isNotBlank())
                if (exercise.kind == ExerciseEngine.CHOICE) {
                    assertTrue(exercise.id, exercise.options.size >= 2)
                    assertTrue(exercise.id, exercise.answers.all { it in exercise.options })
                } else {
                    assertTrue(exercise.id, exercise.options.isEmpty())
                    if (exercise.kind == ExerciseEngine.GAP) assertTrue(exercise.id, "___" in exercise.prompt)
                    if (exercise.kind == ExerciseEngine.ORDER) {
                        assertEquals(exercise.id, 1, exercise.answers.size)
                        assertTrue(exercise.id, exercise.answers.single().split(" ").size >= 3)
                    }
                }
            }
        }
    }

    @Test fun `authored exercises grade deterministically`() {
        val lesson = CourseRegistry.lesson("en-foundations-u1-to-be")!!
        val choice = lesson.exercises.first { it.kind == ExerciseEngine.CHOICE }
        val choiceAnswer = choice.answers.single()
        val wrongChoice = choice.options.firstOrNull { it != choiceAnswer }
        assertTrue(ExerciseEngine.isCorrect(choice, choiceAnswer))
        if (wrongChoice != null) assertFalse(ExerciseEngine.isCorrect(choice, wrongChoice))
        val gap = lesson.exercises.first { it.kind == ExerciseEngine.GAP }
        assertTrue(ExerciseEngine.isCorrect(gap, gap.answers.single().uppercase()))
        assertFalse(ExerciseEngine.isCorrect(gap, "was"))
    }
}

class CheckpointCoverageTest {
    @Test fun `every supported language carries A1 and A2 checkpoints`() {
        for (language in listOf("en", "es", "fr", "it", "de", "pt")) {
            val checkpoints = CheckpointRegistry.forLanguage(language)
            assertTrue("$language needs A1+A2", setOf("A1", "A2").all { it in checkpoints.map { c -> c.level } })
            for (checkpoint in checkpoints) {
                assertEquals(checkpoint.id, 16, checkpoint.questions.size)
            }
        }
    }
}
