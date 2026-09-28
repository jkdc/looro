package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test

class ListeningContentTest {
    @Test fun `ten texts are registered for english with closed shapes`() {
        val texts = ListeningRegistry.forLanguage("en")
        assertEquals(10, texts.size)
        for (text in texts) {
            assertTrue(text.id, text.text.split(" ").size in 40..200)
            assertTrue(text.id, text.questions.size >= 3)
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
        assertEquals("A coffee before work", ListeningRegistry.text("en-listen-coffee-morning")?.title)
        assertNull(ListeningRegistry.text("en-listen-missing"))
        assertTrue(ListeningRegistry.forLanguage("nb").isEmpty())
        for (language in listOf("es", "fr", "it", "de", "pt")) {
            assertEquals(3, ListeningRegistry.forLanguage(language).size)
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
}
