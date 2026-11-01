package com.jkdc.app

import com.jkdc.app.core.TokenWallet
import com.jkdc.app.core.tokenCreditText
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** The wallet read is a display hint only (GET /v1/wallet); parsing must fail
    closed and the arithmetic must stay server-shaped (available = balance -
    reserved). */
class TokenWalletTest {
    @Test fun parsesConsistentNanoUSDWallets() {
        val wallet = TokenWallet.parse("USD", "2160000000", "100000000", "2060000000")
        assertEquals(2_160_000_000L, wallet!!.balanceNanoUSD)
        assertEquals(1_000_000_00L, wallet.reservedNanoUSD)
        assertEquals(2_060_000_000L, wallet.availableNanoUSD)
        assertEquals(TokenWallet(5, 0, 5), TokenWallet.parse("USD", "5", "0", "5"))
        // Zero balance is a valid wallet (fresh account or exhausted packs).
        assertNotNull(TokenWallet.parse("USD", "0", "0", "0"))
    }
    @Test fun rejectsInconsistentOrMalformedHints() {
        for (case in listOf(
            arrayOf<String?>("EUR", "100", "0", "100"),          // wrong currency
            arrayOf<String?>("USD", "100", "0", "99"),           // available ≠ balance − reserved
            arrayOf<String?>("USD", "100", "200", "-100"),       // reserved above balance
            arrayOf<String?>("USD", "-1", "0", "-1"),            // negative
            arrayOf<String?>("USD", "1e9", "0", "1000000000"),   // exponent notation
            arrayOf<String?>("USD", " 1 ", "0", "1"),            // whitespace
            arrayOf<String?>("USD", "", "0", "0"),               // empty
            arrayOf<String?>(null, "100", "0", "100"),           // missing currency
            arrayOf<String?>("USD", null, "0", "100"),
            arrayOf<String?>("USD", "100", "0", null),
            arrayOf<String?>("USD", "1".repeat(17), "0", "1".repeat(17)), // beyond the hint bound
        )) {
            assertNull("expected reject for ${case.contentToString()}", TokenWallet.parse(case[0], case[1], case[2], case[3]))
        }
    }
    @Test fun constructorKeepsInvariantsEvenForDirectUse() {
        try { TokenWallet(100, 50, 49); fail("available must equal balance - reserved") }
        catch (_: IllegalArgumentException) { }
        try { TokenWallet(-1, 0, -1); fail("negative balance") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun creditTextFormatsNanoUSDAsMoney() {
        assertEquals("$2.16", tokenCreditText(2_160_000_000L, Locale.US))
        assertEquals("$0.00", tokenCreditText(0, Locale.US))
        assertEquals("$21.60", tokenCreditText(21_600_000_000L, Locale.US))
    }
}
