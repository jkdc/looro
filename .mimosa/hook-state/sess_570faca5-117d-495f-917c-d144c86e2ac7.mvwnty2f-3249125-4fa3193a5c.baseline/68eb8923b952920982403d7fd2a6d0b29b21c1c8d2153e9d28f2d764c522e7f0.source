package com.jkdc.app.core

import kotlin.math.roundToLong

/** Checked conversion between progress wire milliseconds and Apple-reference seconds. */
internal object ProgressTime {
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

    fun fromUnixMilliseconds(
        unixMilliseconds: Double,
        nowUnixMilliseconds: Double,
        earliestUnixMilliseconds: Double = ProgressSyncContractData.EARLIEST_UNIX_MILLISECONDS,
    ): Double? {
        val latestUnixMilliseconds = nowUnixMilliseconds + ProgressSyncContractData.FUTURE_SKEW_MILLISECONDS
        if (!isSafeInteger(nowUnixMilliseconds) || !isSafeInteger(earliestUnixMilliseconds) ||
            !isSafeInteger(latestUnixMilliseconds) || earliestUnixMilliseconds > latestUnixMilliseconds ||
            !isSafeInteger(unixMilliseconds) || unixMilliseconds < earliestUnixMilliseconds ||
            unixMilliseconds > latestUnixMilliseconds
        ) return null

        return unixMilliseconds / 1000.0 - ProgressSyncContractData.APPLE_EPOCH_OFFSET_SECONDS
    }

    fun toUnixMilliseconds(
        appleReferenceSeconds: Double,
        nowUnixMilliseconds: Double,
        earliestUnixMilliseconds: Double = ProgressSyncContractData.EARLIEST_UNIX_MILLISECONDS,
    ): Long? {
        if (!appleReferenceSeconds.isFinite()) return null
        val rawUnixMilliseconds =
            (appleReferenceSeconds + ProgressSyncContractData.APPLE_EPOCH_OFFSET_SECONDS) * 1000.0
        if (!rawUnixMilliseconds.isFinite() || kotlin.math.abs(rawUnixMilliseconds) > MAX_SAFE_INTEGER) return null

        val roundedUnixMilliseconds = rawUnixMilliseconds.roundToLong().toDouble()
        if (fromUnixMilliseconds(roundedUnixMilliseconds, nowUnixMilliseconds, earliestUnixMilliseconds) == null) return null
        return roundedUnixMilliseconds.toLong()
    }

    private fun isSafeInteger(value: Double): Boolean =
        value.isFinite() && kotlin.math.abs(value) <= MAX_SAFE_INTEGER && value % 1.0 == 0.0
}
