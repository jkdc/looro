package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test

class CoursesTest {
    @Test fun `foundations course is registered for english`() {
        val courses = CourseRegistry.forLanguage("en")
        assertEquals(1, courses.size)
        val course = courses.first()
        assertEquals("en-foundations", course.id)
        assertEquals(6, course.units.size)
        for (unit in course.units) assertEquals(3, unit.lessons.size)
        assertEquals(18, course.units.sumOf { it.lessons.size })
        assertTrue(course.units.first().themes.size >= 2)
    }

    @Test fun `every unit carries its level label`() {
        val levels = CourseRegistry.forLanguage("en").first().units.map { it.level }
        assertTrue(levels.all { it.isNotBlank() })
        assertEquals(listOf("A1", "A1", "A1", "A1", "A2", "A2"), levels)
    }

    @Test fun `other languages have no courses yet`() {
        assertTrue(CourseRegistry.forLanguage("es").isEmpty())
        assertTrue(CourseRegistry.forLanguage("nb").isEmpty())
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
}
