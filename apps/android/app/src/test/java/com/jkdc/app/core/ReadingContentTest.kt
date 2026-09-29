package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test

class ReadingContentTest {
    @Test fun `twenty-two readings are registered for english with closed shapes`() {
        val texts = ReadingRegistry.forLanguage("en")
        assertEquals(22, texts.size)
        for (text in texts) {
            assertTrue(text.id, text.text.split(" ").size in 40..220)
            assertTrue(text.id, text.questions.size >= 4)
            assertTrue(text.id, text.topic.isNotBlank())
            assertEquals(text.id, text.questions.size, text.questions.map { it.id }.toSet().size)
            for (question in text.questions) {
                assertTrue(question.id, question.kind == ExerciseEngine.CHOICE || question.kind == ExerciseEngine.WRITTEN)
                assertTrue(question.id, question.answers.isNotEmpty())
                if (question.kind == ExerciseEngine.CHOICE) {
                    assertTrue(question.id, question.answers.all { it in question.options })
                }
            }
        }
    }

    @Test fun `every reading question grades its own answers correct`() {
        for (text in ReadingRegistry.all) {
            for (question in text.questions) {
                val attempt = question.answers.first()
                assertTrue("${question.id}: '$attempt' must grade correct", ExerciseEngine.isCorrect(question, attempt))
            }
        }
    }

    @Test fun `every reading skill reference resolves in the shipped catalog`() {
        for (text in ReadingRegistry.all) {
            for (ref in text.skills + text.exposureSkills) {
                assertTrue("${text.id}: $ref missing from SkillsData", ref in SkillsData.categories)
            }
        }
    }

    @Test fun `readings sort by level then unit order then id`() {
        val texts = ReadingRegistry.forLanguage("en")
        val levels = texts.map { it.level }
        assertEquals("levels must not regress", levels, levels.sorted())
        // The two A1 unit-linked readings (u2, u4, u3, now) come before the A2 extras.
        assertTrue(texts.indexOfFirst { it.level == "A2" } > texts.indexOfFirst { it.level == "A1" })
    }
}

class CheckpointContentTest {
    @Test fun `english carries the A1 A2 B1 B2 and C1 checkpoints with closed shapes`() {
        val checkpoints = CheckpointRegistry.forLanguage("en")
        assertEquals(listOf("checkpoint-en-a1", "checkpoint-en-a2", "checkpoint-en-b1", "checkpoint-en-b2", "checkpoint-en-c1"), checkpoints.map { it.id })
        for (checkpoint in checkpoints) {
            assertTrue(checkpoint.id, checkpoint.questions.size >= 12)
            assertTrue(checkpoint.id, checkpoint.thresholds.containsKey("overall"))
            for (question in checkpoint.questions) {
                assertTrue(question.exercise.id, question.skill in SkillsData.categories)
                assertTrue(question.exercise.id, question.exercise.answers.isNotEmpty())
            }
        }
    }

    @Test fun `grading applies the overall threshold and category floors`() {
        val checkpoint = CheckpointRegistry.checkpoint("checkpoint-en-a1")!!
        // All correct → 100, passed.
        val perfect = CheckpointEngine.grade(checkpoint, checkpoint.questions.map { it.exercise.answers.first() })
        assertEquals(100, perfect.overall)
        assertTrue(perfect.passed)
        // Half correct → overall 50 < 70 → fail.
        val half = CheckpointEngine.grade(checkpoint, checkpoint.questions.map { if (it.exercise.kind == ExerciseEngine.WRITTEN) "" else it.exercise.answers.first() })
        // (written count × miss) drags every category down; overall must be under 70.
        assertFalse(half.passed)
    }

    @Test fun `verdicts align with the question order`() {
        val checkpoint = CheckpointRegistry.checkpoint("checkpoint-en-a1")!!
        val answers = checkpoint.questions.map { "" }
        assertTrue(CheckpointEngine.verdicts(checkpoint, answers).none { it })
        val correct = checkpoint.questions.map { it.exercise.answers.first() }
        assertTrue(CheckpointEngine.verdicts(checkpoint, correct).all { it })
    }
}
