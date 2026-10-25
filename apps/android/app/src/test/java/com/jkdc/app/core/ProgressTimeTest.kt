package com.jkdc.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTimeTest {
    private val nowUnixMilliseconds = 1_792_929_600_000.0

    @Test
    fun distinctProgressInstantsRoundTrip() {
        val firstAt = 1_792_929_599_000.0
        val lastAt = 1_792_929_600_000.0

        val firstLocal = ProgressTime.fromUnixMilliseconds(firstAt, nowUnixMilliseconds)
        val lastLocal = ProgressTime.fromUnixMilliseconds(lastAt, nowUnixMilliseconds)

        assertEquals(firstAt.toLong(), ProgressTime.toUnixMilliseconds(firstLocal!!, nowUnixMilliseconds)!!)
        assertEquals(lastAt.toLong(), ProgressTime.toUnixMilliseconds(lastLocal!!, nowUnixMilliseconds)!!)
        assertEquals(1.0, lastLocal - firstLocal, 0.0)
    }

    @Test
    fun firstSupportedMillisecondIsIncludedButDatabaseCutoffIsExcluded() {
        val earliestSupported = ProgressSyncContractData.EARLIEST_UNIX_MILLISECONDS

        assertEquals(
            earliestSupported.toLong(),
            ProgressTime.toUnixMilliseconds(
                ProgressTime.fromUnixMilliseconds(earliestSupported, nowUnixMilliseconds)!!,
                nowUnixMilliseconds,
            ),
        )
        assertNull(ProgressTime.fromUnixMilliseconds(earliestSupported - 1, nowUnixMilliseconds))
    }

    @Test
    fun unixEpochConversionIsDistinctFromUnsupportedProgressRecordZero() {
        val local = ProgressTime.fromUnixMilliseconds(
            0.0,
            nowUnixMilliseconds,
            earliestUnixMilliseconds = 0.0,
        )

        assertEquals(-978_307_200.0, local!!, 0.0)
        assertEquals(
            0L,
            ProgressTime.toUnixMilliseconds(local, nowUnixMilliseconds, earliestUnixMilliseconds = 0.0),
        )
        assertNull(ProgressTime.fromUnixMilliseconds(0.0, nowUnixMilliseconds))
    }

    @Test
    fun encodingRoundsToTheNearestMillisecond() {
        val local = ProgressTime.fromUnixMilliseconds(1_792_929_599_000.0, nowUnixMilliseconds)!! + 0.0006

        assertEquals(1_792_929_599_001L, ProgressTime.toUnixMilliseconds(local, nowUnixMilliseconds))
    }

    @Test
    fun invalidTimeIsNotClamped() {
        assertNull(ProgressTime.fromUnixMilliseconds(Double.NaN, nowUnixMilliseconds))
        assertNull(ProgressTime.fromUnixMilliseconds(Double.POSITIVE_INFINITY, nowUnixMilliseconds))
        assertNull(ProgressTime.fromUnixMilliseconds(9_007_199_254_740_992.0, nowUnixMilliseconds))
        assertNull(ProgressTime.fromUnixMilliseconds(1_735_689_600_000.0, nowUnixMilliseconds))
        assertNull(ProgressTime.fromUnixMilliseconds(1_735_689_600_000.0, 1_735_516_799_999.0))
        assertNull(ProgressTime.fromUnixMilliseconds(nowUnixMilliseconds + 172_800_001, nowUnixMilliseconds))
        assertNull(ProgressTime.toUnixMilliseconds(Double.NaN, nowUnixMilliseconds))
        assertNull(ProgressTime.toUnixMilliseconds(Double.POSITIVE_INFINITY, nowUnixMilliseconds))
        assertNull(ProgressTime.toUnixMilliseconds(Double.MAX_VALUE, nowUnixMilliseconds))
    }
}
