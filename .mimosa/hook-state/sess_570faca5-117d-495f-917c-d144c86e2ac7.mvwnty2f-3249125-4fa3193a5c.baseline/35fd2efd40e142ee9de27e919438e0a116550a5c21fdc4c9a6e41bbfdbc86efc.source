package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test

class PronunciationEngineTest {
    @Test fun `exact match scores one hundred regardless of case and punctuation`() {
        assertEquals(100, PronunciationEngine.score("This is my brother.", "this is my brother"))
        assertEquals(100, PronunciationEngine.score("Three thin thieves", "  Three   THIN thieves!! "))
    }

    @Test fun `one wrong word in five scores eighty`() {
        assertEquals(80, PronunciationEngine.score("I would like the fish", "I would like the dish"))
    }

    @Test fun `a dropped word and an extra word cost the same`() {
        assertEquals(80, PronunciationEngine.score("I would like the fish", "I would like fish"))
        assertEquals(80, PronunciationEngine.score("I would like the fish", "I would really like the fish"))
    }

    @Test fun `empty transcript scores zero and nothing scores nothing`() {
        assertEquals(0, PronunciationEngine.score("Thank you", ""))
        assertEquals(100, PronunciationEngine.score("", ""))
        assertEquals(0, PronunciationEngine.score("", "hello"))
    }

    @Test fun `threshold passes at fifty and fails at forty nine`() {
        // One dropped word on a 2-word target → WER 0.5 → score 50.
        assertEquals(50, PronunciationEngine.score("thank you", "thank"))
        assertTrue(PronunciationEngine.passed(50))
        assertFalse(PronunciationEngine.passed(49))
    }

    @Test fun `fully unrelated transcript scores zero`() {
        assertEquals(0, PronunciationEngine.score("thank you very much", "la casa azul"))
    }
}
