package com.jkdc.app.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyPracticeTest {
    @Test fun dailyListeningCarriesTheProfileSpeechLocaleWithItsTranscript() {
        val listening = DailyListening(
            speechLocale = "en-US",
            transcript = "A short local listening transcript.",
            questions = emptyList(),
        )

        assertEquals("en-US", listening.speechLocale)
        assertEquals("A short local listening transcript.", listening.transcript)
    }

    @Test fun unavailableLocalVoiceSkipIsRecordedAsAnExplicitPartialReason() {
        val event = DailyAttemptReducer.stageSkippedEvent(
            DailyAttemptStage.LISTENING,
            "local_tts_unavailable",
        )

        assertEquals("stage_skipped", event["type"]?.toString()?.trim('"'))
        assertEquals("listening", event["stage"]?.toString()?.trim('"'))
        assertEquals("local_tts_unavailable", event["reason"]?.toString()?.trim('"'))
    }

    @Test fun helpUseIsPersistedAcrossAttemptResume() {
        val state = DailyAttemptLocalState.start("33333333-3333-4333-8333-333333333333")
        val aided = DailyAttemptReducer.markHelpUsed(state, listOf("transcript", "hint_listen_main"))
        val restored = DailyAttemptReducer.decode(DailyAttemptReducer.encode(aided))

        assertEquals(setOf("transcript", "hint_listen_main"), restored.helpUsed)
    }

    @Test fun manualTargetMarksPersistWithoutAdvancingTheAttempt() {
        val state = DailyAttemptLocalState.start("33333333-3333-4333-8333-333333333333")
        val marked = DailyAttemptReducer.markTarget(state, "expression-one")
        val restored = DailyAttemptReducer.decode(DailyAttemptReducer.encode(marked))

        assertEquals(setOf("expression-one"), restored.markedTargets)
        assertEquals(DailyAttemptStage.PREPARATION, restored.stage)
    }

    @Test fun sharedEnglishFixtureDecodesWithCommonTopicAndStageCounts() {
        val fixture = File("../../../shared/contracts/daily-practice/fixtures/day-en.json").readText()
        val daily = DailyPracticeCodec.decodePackage(fixture)
        assertEquals("city-gardens", daily.topic.id)
        assertEquals("en-US", daily.listening.speechLocale)
        assertEquals(5, daily.expressions.size)
        assertEquals(3, daily.listening.questions.size)
        assertEquals(4, daily.practice.questions.size)
    }

    @Test fun packageRejectsSpeechLocaleOutsideItsLanguageProfile() {
        val fixture = File("../../../shared/contracts/daily-practice/fixtures/day-en.json").readText()
        val mismatched = fixture.replace("\"speechLocale\": \"en-US\"", "\"speechLocale\": \"fr-FR\"")

        assertThrows(IllegalArgumentException::class.java) { DailyPracticeCodec.decodePackage(mismatched) }
    }

    @Test fun localAttemptResumesIncrementalAnswersWithoutTimeBasedClosure() {
        val initial = DailyAttemptReducer.completePreparation(DailyAttemptLocalState.start("attempt-1"))
        val answered = DailyAttemptReducer.answer(initial, "listen_1", "option_b")
        val encoded = DailyAttemptReducer.encode(answered)
        val restored = DailyAttemptReducer.decode(encoded)

        assertEquals("option_b", restored.answers["listen_1"])
        assertEquals(DailyAttemptStage.LISTENING, restored.stage)
        assertFalse(DailyAttemptReducer.isFinished(restored, elapsedMinutes = 10_000))

        val afterListening = DailyAttemptReducer.submitStage(restored, DailyAttemptStage.LISTENING)
        assertEquals(DailyAttemptStage.SPEAKING, afterListening.stage)
        val speaking = DailyAttemptReducer.openSpeaking(afterListening)
        assertTrue(speaking.speakingOpen)
        assertFalse(DailyAttemptReducer.isFinished(speaking, elapsedMinutes = 10_000))
    }

    @Test fun attemptFinishesOnlyAfterExplicitSpeakingCloseAndFinalStage() {
        val prepared = DailyAttemptReducer.completePreparation(DailyAttemptLocalState.start("attempt-2"))
        val listeningSkipped = DailyAttemptReducer.skip(prepared, DailyAttemptStage.LISTENING)
        val speaking = DailyAttemptReducer.openSpeaking(listeningSkipped)
        val closed = DailyAttemptReducer.closeSpeaking(speaking)
        val practiceSkipped = DailyAttemptReducer.skip(closed, DailyAttemptStage.PRACTICE)

        assertFalse(DailyAttemptReducer.isFinished(practiceSkipped, elapsedMinutes = 60))
        assertTrue(DailyAttemptReducer.isFinished(DailyAttemptReducer.finish(practiceSkipped)))
        assertEquals(setOf(DailyAttemptStage.LISTENING, DailyAttemptStage.PRACTICE),
            practiceSkipped.skippedStages)
    }

    @Test fun speakingFragmentsAreChunkedWithoutDroppingOrExceedingRequestBudget() {
        val fragments = (0 until 70).map { index ->
            DailySpeechFragment(
                id = "00000000-0000-4000-8000-%012d".format(index), role = "learner",
                text = "turn-$index " + "x".repeat(700), voiceSegmentId = "00000000-0000-4000-8000-000000000001",
                sequence = index,
            )
        }

        val chunks = DailyAttemptReducer.speakingFragmentChunks(fragments)

        assertTrue(chunks.size > 1)
        assertEquals(fragments, chunks.flatten())
        assertTrue(chunks.all { DailyAttemptReducer.encodedFragmentBytes(it) <= 22_000 })
        assertTrue(chunks.all { it.size <= 20 })
    }

    @Test fun confirmedConflictRebaseRequiresSameOpenAttemptAndUnresolvedStage() {
        assertTrue(DailyAttemptReducer.canRebaseConfirmedConflict(
            eventType = "stage_submitted", stageResolved = false, attemptOpen = true,
            sameAttempt = true, samePackage = true, evidenceCollision = false,
        ))
        assertFalse(DailyAttemptReducer.canRebaseConfirmedConflict(
            eventType = "stage_submitted", stageResolved = true, attemptOpen = true,
            sameAttempt = true, samePackage = true, evidenceCollision = false,
        ))
        assertFalse(DailyAttemptReducer.canRebaseConfirmedConflict(
            eventType = "finished", stageResolved = false, attemptOpen = true,
            sameAttempt = true, samePackage = true, evidenceCollision = false,
        ))
    }

    @Test fun acceptedRebasedEventUsesServerAdvancedRevision() {
        assertEquals(8L, DailyAttemptReducer.acceptedRebasedRevision(7L, 8L))
        assertThrows(IllegalStateException::class.java) {
            DailyAttemptReducer.acceptedRebasedRevision(7L, 7L)
        }
    }

    @Test fun speakingEvidenceQueueSurvivesResumeAndChunkingKeepsEveryFragment() {
        val opened = DailyAttemptReducer.openSpeaking(
            DailyAttemptLocalState.start("33333333-3333-4333-8333-333333333333")
                .copy(stage = DailyAttemptStage.SPEAKING),
        )
        val fragments = (0 until 70).map { index ->
            DailySpeechFragment("00000000-0000-4000-8000-%012d".format(index), "learner",
                "turn-$index " + "x".repeat(700), "00000000-0000-4000-8000-000000000001", index)
        }
        val queued = DailyAttemptReducer.queueSpeakingFragments(opened, fragments)
        val restored = DailyAttemptReducer.decode(DailyAttemptReducer.encode(queued))
        val chunks = DailyAttemptReducer.speakingFragmentChunks(restored.pendingSpeakingFragments)

        assertTrue(chunks.size > 1)
        assertEquals(fragments, chunks.flatten())
        assertTrue(chunks.all { DailyAttemptReducer.encodedFragmentBytes(it) <= 22_000 })
        assertTrue(chunks.all { it.size <= 20 })
        assertEquals(fragments.drop(chunks.first().size),
            DailyAttemptReducer.dequeueSpeakingFragments(restored, chunks.first().size).pendingSpeakingFragments)
    }

    @Test fun canonicalAttemptRestoresFirstUnresolvedStageFromServerProgress() {
        val initial = DailyAttemptLocalState.start("33333333-3333-4333-8333-333333333333")
        val restored = DailyAttemptReducer.restoreServerProgress(initial,
            completedStages = setOf("preparation", "listening"), skippedStages = emptySet())

        assertEquals(DailyAttemptStage.SPEAKING, restored.stage)
        assertFalse(restored.speakingOpen)
    }

    @Test fun canonicalFinishedAttemptRestoresReviewAsFinished() {
        val restored = DailyAttemptReducer.restoreServerProgress(
            DailyAttemptLocalState.start("33333333-3333-4333-8333-333333333333"),
            completedStages = setOf("preparation", "listening", "speaking", "practice"),
            skippedStages = emptySet(), attemptFinished = true)

        assertEquals(DailyAttemptStage.REVIEW, restored.stage)
        assertTrue(restored.finished)
    }

    @Test fun apiInProgressStateAllowsBoundedConflictReconciliation() {
        assertTrue(DailyAttemptReducer.isServerAttemptInProgress("in_progress"))
        assertFalse(DailyAttemptReducer.isServerAttemptInProgress("open"))
        assertFalse(DailyAttemptReducer.isServerAttemptInProgress("completed"))
    }
}
