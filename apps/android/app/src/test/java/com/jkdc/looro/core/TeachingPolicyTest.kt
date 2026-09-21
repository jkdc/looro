package com.jkdc.looro.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachingPolicyTest {
    private val language: LanguageModule = LanguageRegistry.all.first()
    private val learner = LearnerState(challenge = 1, observationCount = 0, nextGoal = "A goal",
        capabilities = emptyList(), words = emptyList())
    private val theme = ConversationTheme("coffee", "A coffee?", "Something warm, please", "cup",
        "Everyday", "You work in a cosy café. Help the learner order, then chat naturally.", 0)
    private val lesson = CourseRegistry.lesson("en-foundations-u1-to-be")!!

    @Test
    fun `practice prompt carries the rigor policy and every question`() {
        val prompt = TeachingPolicy.lessonPractice(lesson, language)
        assertTrue(prompt.contains("IN ORDER, one at a time"))
        assertTrue(prompt.contains("say explicitly whether it was correct"))
        assertTrue(prompt.contains("recast the correct form"))
        assertTrue(prompt.contains(lesson.title))
        assertTrue(prompt.contains(lesson.goal))
        for (question in lesson.practice.questions) {
            assertTrue(lesson.id, prompt.contains(question.prompt))
            assertTrue(lesson.id, prompt.contains(question.focus))
            assertTrue(lesson.id, prompt.contains(question.watch))
        }
    }

    @Test
    fun `lesson opening introduces the lesson`() {
        val opening = TeachingPolicy.lessonOpening(lesson, language)
        assertTrue(opening.contains(lesson.title))
        assertTrue(opening.contains("first practice question"))
        assertTrue(opening.contains(language.name))
    }

    @Test
    fun `typed reply references the lesson only when one is set`() {
        assertTrue(TeachingPolicy.typedReply(language, lesson).contains(lesson.title))
        assertTrue(TeachingPolicy.typedReply(language, lesson).contains(lesson.goal))
        assertFalse(TeachingPolicy.typedReply(language).contains("lesson"))
    }

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
    fun `a chosen theme anchors the session instructions`() {
        val prompt = TeachingPolicy.voice(language, learner, theme, "", "English")
        assertTrue(prompt.contains("Theme: A coffee?"))
        assertTrue(prompt.contains(theme.situation))
        assertTrue(prompt.contains(TeachingPolicy.themeSteering(language)))
        assertTrue(prompt.contains("drifts too far"))
        assertFalse(prompt.contains(TeachingPolicy.freeConversationContext()))
    }

    @Test
    fun `without a theme the instructions stay free-conversation`() {
        val prompt = TeachingPolicy.voice(language, learner, null, "", "English")
        assertTrue(prompt.contains(TeachingPolicy.freeConversationContext()))
        assertFalse(prompt.contains(TeachingPolicy.themeSteering(language)))
    }

    @Test
    fun `the opening greeting steps into the theme only when one is set`() {
        val themed = TeachingPolicy.greeting(language, theme)
        assertTrue(themed.contains(TeachingPolicy.themeOpening()))
        assertTrue(themed.contains(language.greeting))
        assertFalse(TeachingPolicy.greeting(language).contains(TeachingPolicy.themeOpening()))
    }

    @Test
    fun `mid-session theme switch anchors the conversation`() {
        val instruction = TeachingPolicy.theme(theme, language)
        assertTrue(instruction.contains("Anchor the conversation here"))
        assertTrue(instruction.contains("drifts too far"))
        assertTrue(instruction.contains(theme.situation))
        assertFalse(TeachingPolicy.theme(null, language).contains(theme.situation))
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
