package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test

class SpeechRateTest {
    @Test fun `effective rate derives from choice then legacy flag`() {
        val prefs = Preferences()
        assertEquals(1.0, prefs.effectiveSpeechRate(), 0.0)
        assertTrue(prefs.copy(slowSpeech = true).effectiveSpeechRate() < 1.0)
        assertEquals(0.6, prefs.copy(speechRate = 0.6, slowSpeech = true).effectiveSpeechRate(), 0.0)
        assertEquals(1.2, prefs.copy(speechRate = 1.2).effectiveSpeechRate(), 0.0)
    }

    @Test fun `archive round trip preserves the rate choice`() {
        val archive = Archive()
        archive.preferences.speechRate = 0.6
        val decoded = ArchiveCodec.decode(ArchiveCodec.encode(archive))
        assertEquals(0.6, decoded.preferences.speechRate!!, 0.0)
    }

    @Test fun `shared fixture carries the rate pin`() {
        val fixture = java.io.File("../../../shared/fixtures/cross-platform/archive.json").readText()
        assertEquals(0.8, ArchiveCodec.decode(fixture).preferences.speechRate!!, 0.0)
    }
}
