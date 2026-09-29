package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test

class MasteryEngineTest {
    private val now = 100 * 86_400.0
    private val day = 86_400.0

    private fun course(vararg lessons: Pair<String, List<String>>) = Course(id = "en-foundations", languageID = "en",
        title = "F", subtitle = "s", symbol = "g", description = "d", units = listOf(
        CourseUnit(id = "u1", title = "U1", subtitle = "", level = "A1", themes = emptyList(),
            lessons = lessons.map { (id, skills) ->
                Lesson(id = id, title = "T", goal = "G", intro = LessonIntro(emptyList(), emptyList()),
                    expressions = emptyList(), situation = "s", practice = LessonPractice(emptyList()), skills = skills)
            })))

    private fun listening(id: String, vararg skills: String) = listOf(
        ListeningText(id = id, languageID = "en", title = "T", level = "A1", topic = "t", text = "x",
            questions = emptyList(), skills = skills.toList()))

    private fun stats(course: Course, listenings: List<ListeningText> = emptyList(),
                      lessons: List<LessonState> = emptyList(), marks: List<CompletionMark> = emptyList()) =
        MasteryEngine.stats(course, listenings, emptyList(), lessons, marks, now)

    @Test fun `evidence is weighted and decayed with a sixty day half life`() {
        val course = course("l1" to listOf("grammar:verb-be"))
        val fresh = stats(course, marks = listOf(CompletionMark(ProgressEngine.EXERCISE_SET, "l1", now, 100)))
        assertEquals(60, fresh.skills.single().mastery)
        val aged = stats(course, marks = listOf(CompletionMark(ProgressEngine.EXERCISE_SET, "l1", now - 60 * day, 100)))
        assertEquals(30, aged.skills.single().mastery)
        assertEquals(0.5, MasteryEngine.decay(now - 60 * day, now), 0.0001)
        assertEquals(1.0, MasteryEngine.decay(now, now), 0.0001)
    }

    @Test fun `oral practice is strong evidence even without a mark`() {
        val course = course("l1" to listOf("grammar:verb-be"))
        val result = stats(course, lessons = listOf(LessonState("l1", attempts = 2, lastSeen = now)))
        // 0.9 × 75 baseline = 67.5 → 68; exercises alone at 100 would only be 60.
        assertEquals(68, result.skills.single().mastery)
        assertEquals(now, result.skills.single().lastPracticed)
    }

    @Test fun `the best evidence wins across kinds`() {
        val course = course("l1" to listOf("grammar:verb-be"))
        val result = stats(course, listenings = listening("t1", "grammar:verb-be"),
            marks = listOf(CompletionMark(ProgressEngine.EXERCISE_SET, "l1", now, 80),
                CompletionMark(ProgressEngine.LISTENING, "t1", now, 100)))
        // exercise 0.6 × 80 = 48 · listening 0.5 × 100 = 50 → 50 wins.
        assertEquals(50, result.skills.single().mastery)
    }

    @Test fun `exposure skills are never measured`() {
        val course = Course(id = "en-foundations", languageID = "en", title = "F", subtitle = "s", symbol = "g",
            description = "d", units = listOf(CourseUnit(id = "u1", title = "U1", subtitle = "", level = "A1",
                themes = emptyList(), lessons = listOf(Lesson(id = "l1", title = "T", goal = "G",
                    intro = LessonIntro(emptyList(), emptyList()), expressions = emptyList(), situation = "s",
                    practice = LessonPractice(emptyList()), skills = listOf("grammar:verb-be"),
                    exposureSkills = listOf("grammar:past-continuous"))))))
        val result = stats(course)
        assertEquals(listOf("grammar:verb-be"), result.skills.map { it.skillID })
    }

    @Test fun `categories and overall are means of their skills`() {
        val course = course("l1" to listOf("grammar:verb-be"), "l2" to listOf("grammar:plurals", "vocab:family"))
        val result = stats(course, marks = listOf(CompletionMark(ProgressEngine.EXERCISE_SET, "l1", now, 100),
            CompletionMark(ProgressEngine.EXERCISE_SET, "l2", now, 50)))
        // grammar = mean(60, 30) = 45 · vocab = 30 · overall = mean(45, 30) = 38.
        assertEquals(45, result.categories.first { it.category == "grammar" }.mastery)
        assertEquals(30, result.categories.first { it.category == "vocab" }.mastery)
        assertEquals(38, result.overall)
        assertEquals(2, result.categories.first { it.category == "grammar" }.skillCount)
    }

    @Test fun `untagged content produces empty mastery`() {
        val result = stats(course("l1" to emptyList()))
        assertEquals(0, result.overall)
        assertTrue(result.categories.isEmpty())
        assertTrue(result.skills.isEmpty())
    }

    @Test fun `every content skill reference resolves in the shipped catalog`() {
        for (course in CourseRegistry.all) {
            for (lesson in course.units.flatMap { it.lessons }) {
                for (ref in lesson.skills + lesson.prerequisites + lesson.exposureSkills) {
                    assertTrue("${lesson.id}: $ref missing from SkillsData", ref in SkillsData.categories)
                }
            }
        }
        for (text in ListeningRegistry.all) {
            for (ref in text.skills + text.exposureSkills) {
                assertTrue("${text.id}: $ref missing from SkillsData", ref in SkillsData.categories)
            }
        }
    }
}
