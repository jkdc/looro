package com.jkdc.app

import com.jkdc.app.core.ExerciseEngine
import com.jkdc.app.core.LevelTestEngine
import com.jkdc.app.core.LevelTestItem
import com.jkdc.app.core.LevelTestSection
import com.jkdc.app.core.LevelTestsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Weekly composition and grading for the level-verification tests, against
    the real exported banks (EN/ES, A1–B1). */
class LevelTestEngineTest {

    private val english = LevelTestsRegistry.forLanguage("en")!!

    @Test
    fun `banks ship for en and es with a1 a2 b1 only`() {
        assertEquals(listOf("en", "es"), LevelTestsRegistry.all.map { it.languageID })
        for (bank in LevelTestsRegistry.all) {
            assertEquals(listOf("A1", "A2", "B1"), bank.levels.map { it.level })
        }
    }

    @Test
    fun `composition is deterministic within a week and differs across weeks`() {
        val level = english.levels.first { it.level == "B1" }
        val week = 202641L
        val first = LevelTestEngine.compose(level, week)
        val second = LevelTestEngine.compose(level, week)
        assertEquals(first.items.map { it.id }, second.items.map { it.id })
        val nextWeek = LevelTestEngine.compose(level, week + 1)
        val overlap = nextWeek.items.map { it.id }.toSet().intersect(first.items.map { it.id }.toSet()).size
        assertTrue("consecutive weeks overlap too much ($overlap)", overlap <= first.items.size / 2)
        assertNotEquals(first.readingPassageIds, nextWeek.readingPassageIds)
    }

    @Test
    fun `golden vector pins the shared weekly contract for B1 week 202641`() {
        // This vector is asserted byte-for-byte by the server's TypeScript
        // composer too (tests/level-tests.test.ts): the shared rotation
        // contract between app and backend.
        val level = english.levels.first { it.level == "B1" }
        val exam = LevelTestEngine.compose(level, 202641L)
        assertEquals(
            listOf("en-business-u1-negotiation-e3", "en-care-u1-appointment-e3", "en-care-u1-instructions-e2",
                "en-hosp-u1-complaints-e1", "en-foundations-connections-first-conditional-e1", "en-business-u1-followup-e3",
                "en-foundations-experiences-used-to-e3", "en-foundations-change-phrasals-e2", "en-foundations-telling-collocations-e3",
                "en-hosp-u1-checkin-e3", "en-foundations-change-will-e1", "en-foundations-done-phrasals-e2",
                "en-listen-job-change-q2#r", "en-listen-job-change-q3#r", "en-listen-job-change-q1#r",
                "en-listen-never-been-q2#r", "en-listen-never-been-q3#r", "en-listen-never-been-q1#r",
                "en-listen-monday-predictions-q1#l", "en-listen-monday-predictions-q2#l",
                "en-listen-news-swap-q1#l", "en-listen-news-swap-q2#l",
                "en-foundations-connections-suggestions-e4", "en-foundations-telling-reacting-e4", "en-hosp-u1-checkin-e4"),
            exam.items.map { it.id })
        assertEquals(listOf("en-listen-never-been", "en-listen-job-change"), exam.readingPassageIds)
        assertEquals(listOf("en-listen-monday-predictions", "en-listen-news-swap"), exam.listeningPassageIds)
    }

    @Test
    fun `section counts and order follow the fixed exam shape`() {
        val level = english.levels.first { it.level == "A1" }
        val exam = LevelTestEngine.compose(level, 202641L)
        val sections = exam.items.map { it.section }
        assertEquals(25, exam.items.size)
        // Fixed order: recognition block, then reading, then listening, then writing.
        val firstIndexes = mapOf(
            LevelTestSection.RECOGNITION to sections.indexOf(LevelTestSection.RECOGNITION),
            LevelTestSection.READING to sections.indexOf(LevelTestSection.READING),
            LevelTestSection.LISTENING to sections.indexOf(LevelTestSection.LISTENING),
            LevelTestSection.WRITING to sections.indexOf(LevelTestSection.WRITING))
        assertEquals(firstIndexes.values.sorted(), firstIndexes.values.toList().sorted())
        assertEquals(LevelTestEngine.RECOGNITION_COUNT, sections.count { it == LevelTestSection.RECOGNITION })
        assertEquals(LevelTestEngine.WRITING_COUNT, sections.count { it == LevelTestSection.WRITING })
        assertEquals(LevelTestEngine.READING_PASSAGES * LevelTestEngine.READING_PER_PASSAGE,
            sections.count { it == LevelTestSection.READING })
        assertEquals(LevelTestEngine.LISTENING_PASSAGES * LevelTestEngine.LISTENING_PER_PASSAGE,
            sections.count { it == LevelTestSection.LISTENING })
    }

    @Test
    fun `reading and listening passages are disjoint within one exam`() {
        val level = english.levels.first { it.level == "A2" }
        for (week in 202640L..202646L) {
            val exam = LevelTestEngine.compose(level, week)
            assertTrue(exam.readingPassageIds.isNotEmpty() && exam.listeningPassageIds.isNotEmpty())
            assertTrue(exam.readingPassageIds.toSet().intersect(exam.listeningPassageIds.toSet()).isEmpty())
            // Every reading item's passage is one of the exam's reading passages.
            exam.items.filter { it.section == LevelTestSection.READING }.forEach {
                assertTrue(it.passageId in exam.readingPassageIds)
            }
        }
    }

    @Test
    fun `listening items carry their spoken stimulus`() {
        val level = english.levels.first { it.level == "B1" }
        val exam = LevelTestEngine.compose(level, 202641L)
        exam.items.filter { it.section == LevelTestSection.LISTENING }.forEach {
            assertTrue(it.speech != null && it.speech.isNotBlank())
        }
    }

    @Test
    fun `perfect exam passes and suggests the next band`() {
        val level = english.levels.first { it.level == "A1" }
        val exam = LevelTestEngine.compose(level, 202641L)
        val attempts = exam.items.associate { it.id to it.exercise.answers.first() }
        val result = LevelTestEngine.grade(level, exam, attempts)
        assertTrue(result.passed)
        assertEquals(100, result.overall)
        assertEquals("A2", result.nextBand)
        assertTrue(result.weakSections.isEmpty() && result.weakUnitIDs.isEmpty())
    }

    @Test
    fun `failing exam reports weak sections and units to reinforce`() {
        val level = english.levels.first { it.level == "B1" }
        val exam = LevelTestEngine.compose(level, 202641L)
        val wrong = exam.items.associate { item ->
            item.id to (item.exercise.options.firstOrNull { it !in item.exercise.answers } ?: "__wrong__")
        }
        val result = LevelTestEngine.grade(level, exam, wrong)
        assertFalse(result.passed)
        assertNull(result.nextBand)
        assertTrue(result.weakSections.isNotEmpty())
        val wrongWithUnit = exam.items.filter { it.unitID != null }
        if (wrongWithUnit.isNotEmpty()) assertTrue(result.weakUnitIDs.isNotEmpty())
        assertTrue(result.weakUnitIDs.size <= 4)
    }

    @Test
    fun `ledger id stays within the server slug rules`() {
        for (bank in LevelTestsRegistry.all) for (level in bank.levels) {
            // The ledger id must be lowercase: the server slug only accepts [a-z0-9_-].
            val id = "levelcheck-${bank.languageID}-${level.level.lowercase()}"
            assertTrue(Regex("^[a-z0-9][a-z0-9_-]{0,63}$").matches(id))
        }
    }

    @Test
    fun `grading tolerates missing attempts`() {
        val level = english.levels.first { it.level == "A1" }
        val exam = LevelTestEngine.compose(level, 202641L)
        val result = LevelTestEngine.grade(level, exam, emptyMap())
        assertEquals(0, result.overall)
        assertFalse(result.passed)
    }

    @Test
    fun `week index is iso based and injectable`() {
        // ISO week-based year: Jan 5 2026 is Monday of week 2 (week 1 spans
        // Dec 29–Jan 4 because Jan 1 is a Thursday).
        val week = LevelTestEngine.weekIndex(java.time.LocalDate.of(2026, 1, 5))
        assertEquals(202602L, week)
        assertEquals(202603L, LevelTestEngine.weekIndex(java.time.LocalDate.of(2026, 1, 12)))
    }
@Test
fun `gradeByAttempts mirrors the map-based grading`() {
    val level = english.levels.first { it.level == "A1" }
    val exam = LevelTestEngine.compose(level, 202641L)
    val attempts = exam.items.map { it.exercise.answers.first() }
    val result = LevelTestEngine.gradeByAttempts(level, exam, attempts)
    assertTrue(result.passed)
    assertEquals(100, result.overall)
    assertEquals("A2", result.nextBand)
}

}
