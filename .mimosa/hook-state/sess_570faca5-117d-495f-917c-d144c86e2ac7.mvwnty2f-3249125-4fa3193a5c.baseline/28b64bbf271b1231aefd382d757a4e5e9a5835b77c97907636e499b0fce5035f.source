package com.jkdc.app.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** Day boundaries are pinned to UTC on both platforms (see the Swift core test). */
class LearningStatsTest {
    private val utc: ZoneId = ZoneOffset.UTC

    /** `unixDay` is a `LocalDate.toEpochDay()`; sessions carry Apple-epoch seconds. */
    private fun session(unixDay: Long, hour: Int = 10, minutes: Double = 5.0): SessionRecord {
        val record = SessionRecord(languageID = "en", startedAt = unixDay * 86400.0 + hour * 3600.0 - APPLE_EPOCH_UNIX_SECONDS)
        record.voiceSeconds = minutes * 60
        return record
    }

    private fun nowAt(unixDay: Long): Double = unixDay * 86400.0 - APPLE_EPOCH_UNIX_SECONDS

    private fun stats(sessions: List<SessionRecord>, today: Long, learner: LearnerState = LearnerState(0, 0, "", emptyList(), emptyList())) =
        LearningEngine.stats(sessions, learner, utc, nowAt(today))

    @Test
    fun `empty archive projects to zeros`() {
        val result = stats(emptyList(), today = 20_000)
        assertEquals(0, result.totalSessions)
        assertEquals(0, result.totalVoiceMinutes)
        assertEquals(0, result.activeStreakDays)
        assertEquals(0, result.longestStreakDays)
        assertEquals(listOf(0, 0, 0, 0), result.wordsByBars)
        assertEquals(14, result.last14DaysMinutes.size)
    }

    @Test
    fun `consecutive days build a streak across a month boundary`() {
        val day = { date: LocalDate -> date.toEpochDay() }
        val sessions = listOf(
            session(day(LocalDate.of(2024, 1, 30))), session(day(LocalDate.of(2024, 1, 31))),
            session(day(LocalDate.of(2024, 2, 1))), session(day(LocalDate.of(2024, 2, 2))))
        val result = stats(sessions, today = day(LocalDate.of(2024, 2, 2)))
        assertEquals(4, result.activeStreakDays)
        assertEquals(4, result.longestStreakDays)
        assertEquals(20, result.totalVoiceMinutes)
    }

    @Test
    fun `a gap day keeps the longest streak and restarts the active one`() {
        val sessions = listOf(session(100), session(101), session(102), session(104))
        val result = stats(sessions, today = 104)
        assertEquals(1, result.activeStreakDays)
        assertEquals(3, result.longestStreakDays)
    }

    @Test
    fun `yesterday anchors the active streak when today has no session`() {
        val result = stats(listOf(session(100), session(101)), today = 102)
        assertEquals(2, result.activeStreakDays)
    }

    @Test
    fun `several sessions on one day count once for the streak`() {
        val result = stats(listOf(session(100, hour = 9), session(100, hour = 20)), today = 100)
        assertEquals(1, result.activeStreakDays)
        assertEquals(10, result.totalVoiceMinutes)
    }

    @Test
    fun `histogram covers the last fourteen days oldest first`() {
        val today = 1_000L
        val result = stats(listOf(session(today - 13, minutes = 7.5), session(today, minutes = 2.0)), today = today)
        assertEquals(14, result.last14DaysMinutes.size)
        assertEquals(7.5, result.last14DaysMinutes.first(), 0.0001)
        assertEquals(2.0, result.last14DaysMinutes.last(), 0.0001)
        assertEquals(0.0, result.last14DaysMinutes[1], 0.0001)
    }

    @Test
    fun `word bars and capabilities come from the learner projection`() {
        fun word(bars: Int) = WordState("w$bars", "lemma$bars", "sense", "form", "example", bars, 0, 0, 0.0, 0.0)
        val learner = LearnerState(2, 4, "goal", listOf("Can order food", "Can greet people"),
            listOf(word(0), word(2), word(3), word(3)))
        val result = stats(emptyList(), today = 100, learner = learner)
        assertEquals(listOf(1, 0, 1, 2), result.wordsByBars)
        assertEquals(2, result.capabilityCount)
        assertEquals("Can greet people", result.latestCapability)
    }
}
