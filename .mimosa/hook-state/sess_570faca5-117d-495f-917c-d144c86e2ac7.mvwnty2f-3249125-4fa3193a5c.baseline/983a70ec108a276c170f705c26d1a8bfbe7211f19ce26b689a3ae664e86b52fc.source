package com.jkdc.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordPracticeTest {
    @Test
    fun `exact form is accepted`() {
        assertTrue(WordPractice.isCorrect(answer = "radio", form = "radio", lemma = "radio"))
    }

    @Test
    fun `case and surrounding punctuation are ignored`() {
        assertTrue(WordPractice.isCorrect(answer = "  Radio!! ", form = "radio", lemma = "radio"))
        assertTrue(WordPractice.isCorrect(answer = "\"gikk\"", form = "gikk", lemma = "å gå"))
    }

    @Test
    fun `the lemma is accepted as well`() {
        assertTrue(WordPractice.isCorrect(answer = "volere", form = "vorrei", lemma = "volere"))
    }

    @Test
    fun `diacritics are significant`() {
        assertFalse(WordPractice.isCorrect(answer = "fragor", form = "frågor", lemma = "fråga"))
        assertTrue(WordPractice.isCorrect(answer = "FRÅGOR", form = "frågor", lemma = "fråga"))
    }

    @Test
    fun `an empty answer is never correct`() {
        assertFalse(WordPractice.isCorrect(answer = "   ", form = "radio", lemma = "radio"))
    }

    @Test
    fun `normalize collapses whitespace and strips surrounding punctuation`() {
        assertEquals("un buen día", WordPractice.normalize("  ¡Un   buen día! "))
    }

    @Test
    fun `masked example blanks the form and reports absence as empty`() {
        assertEquals("Jeg ___ i skogen.", WordPractice.maskedExample("Jeg gikk i skogen.", form = "gikk"))
        assertEquals("", WordPractice.maskedExample("Ingen treff her.", form = "gikk"))
    }
}
