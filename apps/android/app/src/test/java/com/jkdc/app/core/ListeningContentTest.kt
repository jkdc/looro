package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test

class ListeningContentTest {
    @Test fun `thirty-one texts are registered for english with closed shapes`() {
        val texts = ListeningRegistry.forLanguage("en")
        assertEquals(31, texts.size)
        for (text in texts) {
            assertTrue(text.id, text.text.split(" ").size in 40..200)
            assertTrue(text.id, text.questions.size >= 5)
            assertTrue(text.id, text.topic.isNotBlank())
            assertEquals(text.id, text.questions.size, text.questions.map { it.id }.toSet().size)
            for (question in text.questions) {
                assertTrue(question.id, question.kind == ExerciseEngine.CHOICE || question.kind == ExerciseEngine.WRITTEN)
                assertTrue(question.id, question.answers.isNotEmpty())
                assertTrue(question.id, question.explain.isNotBlank())
                if (question.kind == ExerciseEngine.CHOICE) {
                    assertTrue(question.id, question.options.size >= 2)
                    assertTrue(question.id, question.answers.all { it in question.options })
                }
            }
        }
    }

    @Test fun `lookup finds texts and misses unknown ones`() {
        assertEquals("The language café", ListeningRegistry.text("en-listen-language-cafe")?.title)
        assertNull(ListeningRegistry.text("en-listen-missing"))
        assertTrue(ListeningRegistry.forLanguage("nb").isEmpty())
        for (language in listOf("es", "fr", "it", "de", "pt")) {
            assertEquals(6, ListeningRegistry.forLanguage(language).size)
        }
    }

    @Test fun `every question grades its own answers correct`() {
        for (text in ListeningRegistry.all) {
            for (question in text.questions) {
                val attempt = when (question.kind) {
                    ExerciseEngine.CHOICE -> question.answers.single()
                    else -> question.answers.first()
                }
                assertTrue("${question.id}: '$attempt' must grade correct", ExerciseEngine.isCorrect(question, attempt))
            }
        }
    }

    @Test fun `texts sort by level then unit order then id`() {
        val texts = ListeningRegistry.forLanguage("en")
        val levels = texts.map { it.level }
        assertEquals("levels must not regress", levels, levels.sorted())
        assertEquals(listOf("en-foundations-u1", "en-foundations-u1", "en-foundations-u2", "en-foundations-u2",
                "en-foundations-u3", "en-foundations-u3", "en-foundations-u4", "en-foundations-u4",
                "en-foundations-now", "en-foundations-now", "en-foundations-days", "en-foundations-days"),
            texts.filter { it.level == "A1" }.map { it.unitID })
        assertEquals(listOf("en-foundations-experiences", "en-foundations-experiences",
                "en-foundations-connections", "en-foundations-connections",
                "en-foundations-change", "en-foundations-change"),
            texts.filter { it.level == "B1" }.map { it.unitID })
        assertEquals(listOf("en-foundations-viewpoints", "en-foundations-viewpoints"),
            texts.filter { it.level == "B2" }.map { it.unitID })
        // Extras without a unit come after the unit-linked texts of their level.
        assertEquals(listOf("en-foundations-u5", "en-foundations-u5", "en-foundations-u6", "en-foundations-u6",
                "en-foundations-compare", "en-foundations-compare", "en-foundations-plans", "en-foundations-plans",
                null, null, null), texts.filter { it.level == "A2" }.map { it.unitID })
    }

    @Test fun `unit-linked texts point at a same-level unit of their course`() {
        for (text in ListeningRegistry.all) {
            val unit = text.unitID?.let { id ->
                CourseRegistry.all.firstOrNull { it.languageID == text.languageID }?.units?.firstOrNull { it.id == id }
            }
            if (text.unitID != null) {
                assertNotNull("${text.id}: unit not found", unit)
                assertEquals("${text.id}: level mismatch", text.level, unit!!.level)
            }
        }
    }
}
