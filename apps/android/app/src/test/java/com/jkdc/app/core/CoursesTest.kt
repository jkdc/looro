package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test

class CoursesTest {
    @Test fun `pilot course is registered for english`() {
        val courses = CourseRegistry.forLanguage("en")
        assertEquals(1, courses.size)
        val course = courses.first()
        assertEquals("en-foundations", course.id)
        assertEquals(1, course.units.size)
        val unit = course.units.first()
        assertEquals(1, unit.lessons.size)
        assertTrue(unit.themes.size >= 2)
    }

    @Test fun `other languages have no courses yet`() {
        assertTrue(CourseRegistry.forLanguage("es").isEmpty())
        assertTrue(CourseRegistry.forLanguage("nb").isEmpty())
    }

    @Test fun `lesson lookup finds the authored lesson`() {
        val lesson = CourseRegistry.lesson("en-foundations-u1-to-be")
        assertEquals("The verb “to be”", lesson?.title)
        assertTrue(lesson!!.intro.explanation.isNotEmpty())
        assertTrue(lesson.intro.examples.isNotEmpty())
        assertTrue(lesson.expressions.isNotEmpty())
        assertTrue(lesson.practice.questions.isNotEmpty())
        assertNull(CourseRegistry.lesson("missing-lesson"))
    }

    @Test fun `ids are unique across the registry`() {
        val all = CourseRegistry.all
        val lessons = all.flatMap { it.units }.flatMap { it.lessons }
        assertEquals(lessons.size, lessons.map { it.id }.toSet().size)
        val units = all.flatMap { it.units }
        assertEquals(units.size, units.map { it.id }.toSet().size)
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
