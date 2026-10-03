package com.jkdc.app.core

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Why the token proxy refused a call (docs/token-billing.md error matrix). The
    engine tags [com.jkdc.app.core.engines.EngineException] with this so the UI
    can open the paywall (unlock) or prompt a pack purchase (balance) instead of
    showing a raw failure. 429 account_busy stays a retryable message, not a
    commerce surface. */
enum class TokenProxyBlock { UNLOCK_REQUIRED, BALANCE_EXHAUSTED }

/** Server-provided display hint from GET /v1/wallet. Display only — admission
    re-reserves under the account lock, and the design forbids any offline
    balance caching beyond this hint. Values are integer nanoUSD (1 USD =
    1,000,000,000 nanoUSD); the balance is prepaid *service credit*, never a
    currency the learner owns. */
data class TokenWallet(val balanceNanoUSD: Long, val reservedNanoUSD: Long, val availableNanoUSD: Long) {
    init {
        require(balanceNanoUSD in 0..MAX_NANO && reservedNanoUSD in 0..balanceNanoUSD)
        require(availableNanoUSD == balanceNanoUSD - reservedNanoUSD)
    }
    companion object {
        const val MAX_NANO = 1_000_000_000_000_000L // $1,000,000 of credit — far above any real wallet
        /** Strict parse of the server shape; anything else fails closed (null). */
        fun parse(currency: String?, balance: String?, reserved: String?, available: String?): TokenWallet? {
            if (currency != "USD") return null
            val balanceValue = balance?.nanoUSDOrNull() ?: return null
            val reservedValue = reserved?.nanoUSDOrNull() ?: return null
            val availableValue = available?.nanoUSDOrNull() ?: return null
            return try { TokenWallet(balanceValue, reservedValue, availableValue) } catch (_: IllegalArgumentException) { null }
        }
        private fun String.nanoUSDOrNull(): Long? =
            takeIf { it.isNotEmpty() && it.length <= 16 && it.all(Char::isDigit) }?.toLongOrNull()
    }
}

/** Human text for a nanoUSD credit amount, e.g. $2.16. Formatting only. */
internal fun tokenCreditText(availableNanoUSD: Long, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getCurrencyInstance(locale).format(BigDecimal.valueOf(availableNanoUSD).movePointLeft(9))
