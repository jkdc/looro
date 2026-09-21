package com.jkdc.looro.voice

import com.jkdc.looro.core.voice.AdaptiveVad
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveVadTest {
    @Test
    fun `a quiet room keeps quiet speech detectable`() {
        val vad = AdaptiveVad()
        repeat(60) { assertFalse(vad.observe(0.001f, inSpeech = false)) }
        // The floor decayed toward the room; the base threshold is the guard.
        assertTrue(vad.observe(0.02f, inSpeech = false))
    }

    @Test
    fun `sustained hum above the old fixed threshold is not speech and raises the floor`() {
        val vad = AdaptiveVad()
        repeat(200) { assertFalse(vad.observe(0.02f, inSpeech = false)) }
        assertTrue(vad.floor > 0.015f)
        assertTrue(vad.startThreshold > 0.02f)
        // Real speech still opens the turn over the raised floor.
        assertTrue(vad.observe(0.35f, inSpeech = false))
    }

    @Test
    fun `hysteresis keeps soft trailing syllables inside an open turn`() {
        val vad = AdaptiveVad()
        assertTrue(vad.observe(0.35f, inSpeech = false))
        // Below the start threshold but above the continue threshold.
        assertTrue(vad.observe(0.025f, inSpeech = true))
        // True silence still closes the turn.
        assertFalse(vad.observe(0.001f, inSpeech = true))
    }

    @Test
    fun `speech frames never raise the noise floor`() {
        val vad = AdaptiveVad()
        val before = vad.floor
        repeat(50) { assertTrue(vad.observe(0.35f, inSpeech = false)) }
        assertTrue(vad.floor <= before)
    }
}
