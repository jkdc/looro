package chat.mural.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachingPolicyTest {
    private val language: LanguageModule = LanguageRegistry.all.first()
    private val learner = LearnerState(challenge = 1, observationCount = 0, nextGoal = "A goal",
        capabilities = emptyList(), words = emptyList())

    @Test
    fun `natural pace line appears by default`() {
        val prompt = TeachingPolicy.voice(language, learner, null, "", "English")
        assertTrue(prompt.contains(TeachingPolicy.paceNormal()))
        assertFalse(prompt.contains(TeachingPolicy.paceSlow()))
    }

    @Test
    fun `slow pace line replaces the natural one`() {
        val prompt = TeachingPolicy.voice(language, learner, null, "", "English", slowSpeech = true)
        assertTrue(prompt.contains(TeachingPolicy.paceSlow()))
        assertFalse(prompt.contains(TeachingPolicy.paceNormal()))
    }
}
