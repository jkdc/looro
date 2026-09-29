package com.jkdc.app

import com.jkdc.app.core.MinuteBalance
import com.jkdc.app.core.SubscriptionInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionBalanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun balance(availableMilliseconds: Long, subscription: SubscriptionInfo? = null) = MinuteBalance(
        unit = "milliseconds", billingBasis = "connected-conversation-time", balanceMilliseconds = availableMilliseconds,
        reservedMilliseconds = 0, availableMilliseconds = availableMilliseconds, subscription = subscription)

    @Test
    fun `extended minutes payload parses with tier drip and features`() {
        val decoded = json.decodeFromString<MinuteBalance>(
            """{"unit":"milliseconds","billingBasis":"connected-conversation-time","balanceMilliseconds":25200000,
               "reservedMilliseconds":0,"availableMilliseconds":25200000,
               "subscription":{"tier":"pro","status":"active","trialing":false,
                 "periodEnd":"2026-11-01T00:00:00.000Z",
                 "features":{"aiReports":true,"unlimitedThemes":true},
                 "drip":{"enabled":true,"minutesPerMonth":5}}}""")
        val subscription = decoded.subscription!!
        assertEquals("pro", subscription.tier)
        assertEquals("active", subscription.status)
        assertFalse(subscription.trialing)
        assertEquals(5, subscription.drip?.minutesPerMonth)
        assertEquals(true, subscription.features["aiReports"])
    }

    @Test
    fun `balance without subscription keeps the free defaults`() {
        val decoded = json.decodeFromString<MinuteBalance>(
            """{"unit":"milliseconds","billingBasis":"connected-conversation-time","balanceMilliseconds":300000,
               "reservedMilliseconds":0,"availableMilliseconds":300000}""")
        assertNull(decoded.subscription)
        assertEquals(1, decoded.warningLevel)
        assertFalse(decoded.canStartConversation == false && decoded.readinessMilliseconds > 0)
    }

    @Test
    fun `warning ladder follows the configured thresholds`() {
        assertEquals(0, balance(6L * 60_000).warningLevel)
        assertEquals(1, balance(5L * 60_000).warningLevel)
        assertEquals(1, balance(3L * 60_000).warningLevel)
        assertEquals(2, balance(2L * 60_000).warningLevel)
        assertEquals(2, balance(30_000).warningLevel)
        assertEquals(3, balance(0).warningLevel)
    }

    @Test
    fun `trials mark the tier without changing enforcement`() {
        val trial = SubscriptionInfo(tier = "pro", status = "trialing", trialing = true)
        assertTrue(trial.trialing)
        assertEquals("pro", trial.tier)
        // The client never enforces: exhaustion is decided by the server's 402.
        assertEquals(3, balance(0, trial).warningLevel)
    }
}
