package com.jkdc.app

import com.jkdc.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Pack purchases ride the shared minute purchase controllers. The commercial
    rules under test: pack catalog entries surface as packs, orders match their
    product, a sandbox (test-mode) unlock purchase — purchased with
    fulfillmentRecorded:false, the server's documented test-mode contract — is
    not treated as a verification failure, and a live refunded unlock carries
    reversal evidence so it words REVERSED instead of the sandbox copy. */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenPackPurchaseTest {
    private val id = "12345678-1234-1234-1234-123456789012"
    private val member = AccountSession(id, "a".repeat(43), 100_000)
    private val packProduct = MinuteProduct("palique-tokens", "palique_tokens", 0, "usd", 499, "test",
        entitlementKind = PACK_TOKEN_KIND, creditNanoUSD = 2_160_000_000L)
    private val unlockProduct = MinuteProduct("palique-unlock", "palique_unlock", 0, "usd", 699, "test",
        entitlementKind = PACK_UNLOCK_KIND, creditNanoUSD = 0)
    private val minuteProduct = MinuteProduct("fixture-30", "fixture_30", 30, "usd", 599, "test")

    private inner class API(vararg statuses: MinutePurchaseStatus) : MinuteCommerceService {
        var statusIndex = 0
        val statuses = statuses.toList()
        var created = 0
        override suspend fun catalog() = MinuteCatalog(true, listOf(minuteProduct, packProduct, unlockProduct))
        override suspend fun create(session: AccountSession, sku: String, idempotencyKey: String): MinuteOrder =
            when (sku) {
                packProduct.sku -> MinuteOrder(id, 0, "usd", 499, PlayOrderBinding(id, "b".repeat(64), "c".repeat(64)),
                    entitlementKind = PACK_TOKEN_KIND, creditNanoUSD = 2_160_000_000L)
                unlockProduct.sku -> MinuteOrder(id, 0, "usd", 699, PlayOrderBinding(id, "b".repeat(64), "c".repeat(64)),
                    entitlementKind = PACK_UNLOCK_KIND, creditNanoUSD = 0)
                else -> MinuteOrder(id, 30, "usd", 599, PlayOrderBinding(id, "b".repeat(64), "c".repeat(64)))
            }.also { created++ }
        override suspend fun status(session: AccountSession, orderID: String): MinutePurchaseStatus = next()
        override suspend fun verify(session: AccountSession, orderID: String, token: String): MinutePurchaseStatus = next()
        override suspend fun recover(session: AccountSession, token: String): MinutePurchaseStatus = next()
        override suspend fun balance(session: AccountSession) = MinuteBalance("milliseconds", "connected-conversation-time", 60_000, 0, 60_000)
        private fun next(): MinutePurchaseStatus = statuses[minOf(statusIndex, statuses.lastIndex)].also { statusIndex++ }
    }
    private inner class Store(private val purchases: List<MinuteStorePurchase> = emptyList()) : MinuteStoreGateway {
        override val events = MutableSharedFlow<MinuteStoreEvent>()
        override suspend fun connect() { }
        override suspend fun offers(productIDs: List<String>) = productIDs.map { productID ->
            val product = listOf(minuteProduct, packProduct, unlockProduct).single { it.providerProduct == productID }
            MinuteStoreOffer(id, productID, "USD", product.totalMinor * 10_000L, "$${product.totalMinor / 100}.${product.totalMinor % 100}")
        }
        override suspend fun purchases() = this.purchases
        override fun close() { }
    }

    @Test fun packCatalogSurfacesPacksWithoutBreakingMinutes() = runTest {
        val api = API()
        val controller = MinutePurchaseController(backgroundScope, api, Store(), { member }, enabled = true, now = { 1_000 })
        controller.refresh()
        val state = controller.state.value
        assertTrue(state.available)
        assertEquals(setOf(minuteProduct.sku, packProduct.sku, unlockProduct.sku), state.packs.map { it.sku }.toSet())
        val pack = state.packs.single { it.entitlementKind == PACK_TOKEN_KIND }
        assertEquals(2_160_000_000L, pack.creditNanoUSD)
        assertEquals(0, pack.minutes)
        assertTrue(state.packs.single { it.entitlementKind == PACK_UNLOCK_KIND }.isPack)
        controller.close()
    }

    @Test fun packOrderCreationRidesTheSameBuyPath() = runTest {
        val api = API()
        val controller = MinutePurchaseController(backgroundScope, api, Store(), { member }, enabled = true, now = { 1_000 })
        controller.refresh()
        var launched = 0
        controller.buy(packProduct.sku) { prepared ->
            launched++
            assertTrue(prepared.order.matches(packProduct))
            assertTrue(prepared.product.isPack)
            MinuteStoreOutcome.OPENED
        }
        assertEquals(1, launched)
        assertEquals(1, api.created)
        assertTrue(controller.state.value.purchaseInProgress)
        controller.close()
    }

    @Test fun sandboxUnlockPurchaseIsRecordedNotAnError() = runTest {
        val sandbox = MinutePurchaseStatus(id, "purchased", 0, 0, 0, fulfillmentRecorded = false,
            entitlementKind = PACK_UNLOCK_KIND, grantedNanoUSD = 0, reversedNanoUSD = 0, unlockedAt = null,
            reversalOutstandingNanoUSD = 0, environment = "test")
        val api = API(sandbox)
        val controller = MinutePurchaseController(backgroundScope, api, Store(listOf(MinuteStorePurchase("store-token", MinuteStorePurchaseState.PURCHASED))), { member },
            enabled = true, now = { 1_000 })
        controller.refresh()
        assertEquals(MinutePurchaseNotice.SANDBOX_RECORDED, controller.state.value.notice)
        assertFalse(controller.state.value.purchaseInProgress)
        controller.close()
    }

    @Test fun refundedLiveUnlockWordsReversedNeverSandbox() = runTest {
        // A live unlocked order whose unlock was later refunded carries the
        // money-returned evidence: REVERSED, never the sandbox copy.
        val refunded = MinutePurchaseStatus(id, "purchased", 0, 0, 0, fulfillmentRecorded = false,
            entitlementKind = PACK_UNLOCK_KIND, grantedNanoUSD = 0, reversedNanoUSD = 0, unlockedAt = null,
            reversalOutstandingNanoUSD = 6_990_000_000L, environment = "live")
        val api = API(refunded)
        val controller = MinutePurchaseController(backgroundScope, api, Store(listOf(MinuteStorePurchase("store-token", MinuteStorePurchaseState.PURCHASED))), { member },
            enabled = true, now = { 1_000 })
        controller.refresh()
        assertEquals(MinutePurchaseNotice.REVERSED, controller.state.value.notice)
        controller.close()
        // The sandbox rule is test-environment only: a live unfulfilled unlock
        // (no refund evidence) is not the documented sandbox case.
        val liveUnfulfilled = refunded.copy(reversalOutstandingNanoUSD = 0)
        val liveApi = API(liveUnfulfilled)
        val liveController = MinutePurchaseController(backgroundScope, liveApi, Store(listOf(MinuteStorePurchase("store-token", MinuteStorePurchaseState.PURCHASED))), { member },
            enabled = true, now = { 1_000 })
        liveController.refresh()
        assertEquals(MinutePurchaseNotice.VERIFICATION_FAILED, liveController.state.value.notice)
        liveController.close()
    }

    @Test fun liveUnlockAndCreditedPacksConfirmWhileReversalsStayErrors() = runTest {
        val live = MinutePurchaseStatus(id, "purchased", 0, 0, 0, fulfillmentRecorded = true,
            entitlementKind = PACK_UNLOCK_KIND, grantedNanoUSD = 0, reversedNanoUSD = 0, unlockedAt = "2026-10-02T10:00:00.000Z",
            reversalOutstandingNanoUSD = 0, environment = "live")
        val credited = MinutePurchaseStatus(id, "purchased", 0, 0, 0, fulfillmentRecorded = true,
            entitlementKind = PACK_TOKEN_KIND, grantedNanoUSD = 2_160_000_000L, reversedNanoUSD = 0, unlockedAt = null,
            reversalOutstandingNanoUSD = 0, environment = "live")
        val reversed = MinutePurchaseStatus(id, "purchased", 0, 0, 0, fulfillmentRecorded = true,
            entitlementKind = PACK_TOKEN_KIND, grantedNanoUSD = 2_160_000_000L, reversedNanoUSD = 2_160_000_000L, unlockedAt = null,
            reversalOutstandingNanoUSD = 0, environment = "live")
        for (status in listOf(live, credited)) {
            val api = API(status)
            val controller = MinutePurchaseController(backgroundScope, api, Store(listOf(MinuteStorePurchase("store-token", MinuteStorePurchaseState.PURCHASED))), { member },
                enabled = true, now = { 1_000 })
            controller.refresh()
            assertEquals(MinutePurchaseNotice.ADDED, controller.state.value.notice)
            controller.close()
        }
        val api = API(reversed)
        val controller = MinutePurchaseController(backgroundScope, api, Store(listOf(MinuteStorePurchase("store-token", MinuteStorePurchaseState.PURCHASED))), { member },
            enabled = true, now = { 1_000 })
        controller.refresh()
        assertEquals(MinutePurchaseNotice.REVERSED, controller.state.value.notice)
        controller.close()
    }

    @Test fun stripePacksSellThroughTheSameControllerAndSandboxRuleMatches() = runTest {
        val sandbox = MinutePurchaseStatus(id, "purchased", 0, 0, 0, fulfillmentRecorded = false,
            entitlementKind = PACK_UNLOCK_KIND, grantedNanoUSD = 0, reversedNanoUSD = 0, unlockedAt = null,
            reversalOutstandingNanoUSD = 0, environment = "test")
        // A live unlocked-then-refunded Stripe order words REVERSED, never sandbox.
        val refundedLive = MinutePurchaseStatus(id, "purchased", 0, 0, 0, fulfillmentRecorded = false,
            entitlementKind = PACK_UNLOCK_KIND, grantedNanoUSD = 0, reversedNanoUSD = 0, unlockedAt = null,
            reversalOutstandingNanoUSD = 6_990_000_000L, environment = "live")
        val storage = object : StripePurchaseAttemptStorage {
            var saved: StripePurchaseAttempt? = null
            override suspend fun read(accountID: String) = saved?.takeIf { it.accountID == accountID }
            override suspend fun save(attempt: StripePurchaseAttempt) { saved = attempt }
            override suspend fun remove(accountID: String) { if (saved?.accountID == accountID) saved = null }
        }
        val api = object : StripeCommerceService {
            var refunded = false
            override suspend fun catalog() = MinuteCatalog(true, listOf(packProduct, unlockProduct))
            override suspend fun createStripe(session: AccountSession, sku: String, idempotencyKey: String): StripeMinuteOrder {
                val product = if (sku == packProduct.sku) packProduct else unlockProduct
                return StripeMinuteOrder(id, "usd", product.totalMinor,
                    StripeCheckoutURL.checked("https://checkout.stripe.com/c/pay/cs_test_synthetic#opaque"),
                    null, entitlementKind = product.entitlementKind, creditNanoUSD = product.creditNanoUSD)
            }
            override suspend fun findStripeOrder(session: AccountSession, idempotencyKey: String): String? = null
            override suspend fun status(session: AccountSession, orderID: String): MinutePurchaseStatus =
                if (refunded) refundedLive else sandbox
            override suspend fun balance(session: AccountSession) = MinuteBalance("milliseconds", "connected-conversation-time", 60_000, 0, 60_000)
        }
        val controller = StripeMinutePurchaseController(api, storage, { member }, enabled = true, expectedEnvironment = "test", now = { 1_000 })
        controller.refresh()
        val state = controller.state.value
        assertTrue(state.available)
        assertEquals(setOf(packProduct.sku, unlockProduct.sku), state.packs.map { it.sku }.toSet())
        assertTrue(state.packs.all { it.isPack })
        assertTrue(state.packs.single { it.entitlementKind == PACK_TOKEN_KIND }.formattedPrice.isNotBlank())
        var opened = 0
        controller.buy(unlockProduct.sku) { opened++; MinuteStoreOutcome.OPENED }
        assertEquals(1, opened)
        // Refresh re-reads the owned order: the sandbox unlock purchase surfaces
        // as recorded-for-sandbox, never as a verification failure.
        controller.refresh()
        assertEquals(MinutePurchaseNotice.SANDBOX_RECORDED, controller.state.value.notice)
        // After the provider reports the refund, the same live order words REVERSED.
        api.refunded = true
        storage.save(StripePurchaseAttempt(member.accountID, unlockProduct.sku, "12345678-1234-1234-1234-123456789012", id))
        controller.refresh()
        assertEquals(MinutePurchaseNotice.REVERSED, controller.state.value.notice)
        controller.close()
    }
}
