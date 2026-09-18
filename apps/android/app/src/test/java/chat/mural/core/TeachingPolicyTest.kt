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

    @Test
    fun `listening story opens the exercise instead of the greeting`() {
        val story = TeachingPolicy.listeningStory(language)
        assertTrue(story.contains("listening comprehension"))
        assertTrue(story.contains(language.name))
        assertTrue(story != TeachingPolicy.greeting(language))
    }

    @Test
    fun `session mode records the listening exercise`() {
        assertFalse(SessionRecord().isListening)
        val listening = SessionRecord(mode = "listening")
        assertTrue(listening.isListening)
        // The mode survives a backup round trip like every stored session field.
        val archive = Archive(sessions = mutableListOf(listening))
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(archive))
        assertTrue(restored.sessions.single().isListening)
    }
}
