package com.jkdc.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyWordsImportTest {
    @Test fun daily_word_import_preserves_existing_recall_state_and_deduplicates_replay() {
        val review = DailyWordsReview("review-1", "en", listOf(
            DailyWordCandidate("candidate-1", "word", "Useful", "useful", "supported")
        ))
        val existing = listOf(DailyWordExisting("useful", "en", bars = 2, attempts = 3, meaning = "my meaning"))
        val first = planDailyWordsImport(review, "owner-a", "en", existing, emptySet(), emptySet(), 10.0)
        assertEquals(DailyWordImportAction.KEEP, first.single().action)
        assertEquals("my meaning", first.single().meaning)
        val replay = planDailyWordsImport(review, "owner-a", "en", emptyList(), setOf("review:review-1:candidate-1"), emptySet(), 11.0)
        assertEquals(DailyWordImportAction.KEEP, replay.single().action)
    }

    @Test fun deleted_daily_word_stays_suppressed_and_capacity_never_evicts() {
        val review = DailyWordsReview("review-2", "en", listOf(
            DailyWordCandidate("candidate-2", "expression", "on the other hand", "contrast phrase", "supported")
        ))
        assertEquals(DailyWordImportAction.SUPPRESS, planDailyWordsImport(review, "owner-a", "en", emptyList(), emptySet(),
            setOf("en:on the other hand"), 10.0).single().action)
        val full = (1..2000).map { DailyWordExisting("word$it", "en", 0, 0, "") }
        assertEquals(DailyWordImportAction.PENDING_CAPACITY, planDailyWordsImport(review, "owner-a", "en", full, emptySet(), emptySet(), 10.0).single().action)
    }

    @Test fun capacity_is_reserved_sequentially_within_a_candidate_batch() {
        val review = DailyWordsReview("review-3", "en", listOf(
            DailyWordCandidate("candidate-3", "word", "first", "first", "supported"),
            DailyWordCandidate("candidate-4", "word", "second", "second", "supported")
        ))
        val existing = (1..1999).map { DailyWordExisting("word$it", "en", 0, 0, "") }
        assertEquals(listOf(DailyWordImportAction.INSERT, DailyWordImportAction.PENDING_CAPACITY),
            planDailyWordsImport(review, "owner-a", "en", existing, emptySet(), emptySet(), 10.0).map { it.action })
    }

    @Test fun explicit_manual_readd_uses_mark_receipt_and_can_clear_suppression() {
        val markID = "33333333-3333-4333-8333-333333333333"
        val plan = planManualDailyWord(markID, "garden", "a place to grow plants", "word", "owner-a", "en",
            existing = emptyList(), receipts = emptySet(), suppressions = setOf("en:garden"), now = 12.0).single()

        assertEquals("mark:$markID", plan.originKey)
        assertEquals(DailyWordImportAction.INSERT, plan.action)
        assertEquals("a place to grow plants", plan.meaning)
        val fresh = planManualDailyWord("44444444-4444-4444-8444-444444444444", "seedling", "young plant",
            "word", "owner-a", "en", emptyList(), emptySet(), emptySet(), 13.0).single()
        assertEquals(DailyWordImportAction.INSERT, fresh.action)
    }

    @Test fun validated_manual_candidate_reuses_the_original_mark_receipt() {
        val markID = "55555555-5555-4555-8555-555555555555"
        val validatedReview = DailyWordsReview("review-44", "en", listOf(
            DailyWordCandidate(markID, "word", "garden", "a place for plants", "supported", origin = "learner_mark")
        ))
        val candidatePlan = planDailyWordsImport(validatedReview, "owner-a", "en", emptyList(), emptySet(), emptySet(), 14.0).single()

        assertEquals("mark:$markID", candidatePlan.originKey)
    }
}
