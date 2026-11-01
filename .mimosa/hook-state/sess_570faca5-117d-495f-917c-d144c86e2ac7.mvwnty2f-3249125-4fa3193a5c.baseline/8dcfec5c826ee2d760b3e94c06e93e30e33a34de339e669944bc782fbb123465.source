package com.jkdc.app.network

import com.jkdc.app.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Pack catalog/orders/statuses ride the existing minute commerce routes
    (services/api merges the pack catalog into /v1/minutes/products), so the
    client must parse them without inventing fields: kind, price and credit are
    server constants echoed back verbatim. */
class PackCommerceClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MinuteCommerceClient
    private lateinit var stripe: MinuteCommerceClient
    private val id = "12345678-1234-1234-1234-123456789012"
    private val session = AccountSession(id, "a".repeat(43), 100_000)
    private val unlockProduct = """{"sku":"palique-unlock","providerProduct":"palique_unlock","currency":"usd","totalMinor":699,"environment":"test","entitlementKind":"byok_unlock","creditNanoUSD":"0"}"""
    private val packProduct = """{"sku":"palique-tokens","providerProduct":"palique_tokens","currency":"usd","totalMinor":499,"environment":"test","entitlementKind":"token_pack","creditNanoUSD":"2160000000"}"""

    // Credit in the launch pack is a server constant (pack-products.ts): the
    // client echoes it, it never computes it.
    private val packOrder = """{"orderID":"$id","sku":"palique-tokens","providerProduct":"palique_tokens","currency":"usd","totalMinor":499,"environment":"test","entitlementKind":"token_pack","creditNanoUSD":"2160000000","payment":{"orderID":"$id","obfuscatedAccountID":"${"b".repeat(64)}","obfuscatedProfileID":"${"c".repeat(64)}"}}"""
    private val sandboxUnlockStatus = """{"orderID":"$id","entitlementKind":"byok_unlock","state":"purchased","grantedNanoUSD":"0","reversedNanoUSD":"0","reversalOutstandingNanoUSD":"0","fulfillmentRecorded":false,"unlockedAt":null,"environment":"test"}"""
    private val liveUnlockStatus = sandboxUnlockStatus.replace("\"fulfillmentRecorded\":false", "\"fulfillmentRecorded\":true")
        .replace("\"unlockedAt\":null", "\"unlockedAt\":\"2026-10-02T10:00:00.000Z\"")
        .replace("\"environment\":\"test\"", "\"environment\":\"live\"")
    private val refundedUnlockStatus = sandboxUnlockStatus
        .replace("\"environment\":\"test\"", "\"environment\":\"live\"")
        .replace("\"reversalOutstandingNanoUSD\":\"0\"", "\"reversalOutstandingNanoUSD\":\"6990000000\"")
    private val packStatus = """{"orderID":"$id","entitlementKind":"token_pack","state":"purchased","grantedNanoUSD":"2160000000","reversedNanoUSD":"0","reversalOutstandingNanoUSD":"0","fulfillmentRecorded":true,"unlockedAt":null,"environment":"test"}"""

    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = MinuteCommerceClient(server.url("/"), OkHttpClient(), now = { 1_000 })
        stripe = MinuteCommerceClient(server.url("/"), OkHttpClient(), now = { 1_000 }, channel = PurchaseChannel.STRIPE)
    }
    @After fun teardown() { server.shutdown() }

    @Test fun packOnlyCatalogParsesKindAndCreditWithoutMinuteMath() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"available":true,"billingBasis":"prepaid-service-credit","products":[$unlockProduct,$packProduct]}"""))
        val catalog = api.catalog()
        assertTrue(catalog.available)
        val (unlock, pack) = catalog.products.let { it.first { p -> p.entitlementKind == PACK_UNLOCK_KIND } to it.first { p -> p.entitlementKind == PACK_TOKEN_KIND } }
        assertEquals("palique-unlock", unlock.sku); assertEquals(0, unlock.minutes); assertTrue(unlock.isPack)
        assertEquals(699, unlock.totalMinor)
        // Server constant: the unlock echoes credit 0; only the pack carries credit.
        assertEquals(0, unlock.creditNanoUSD)
        assertEquals("palique-tokens", pack.sku); assertEquals(2_160_000_000L, pack.creditNanoUSD)
        assertEquals("/v1/minutes/products?provider=play", server.takeRequest().path)
    }
    @Test fun mixedCatalogParsesPacksAlongsideAIValue() = runBlocking {
        val value = """"entitlementKind":"ai_value","billingBasis":"actual-ai-usage","estimate":true,"aiValueNanoUSD":"2000000000","estimatedMilliseconds":1200000,"quote":{"currency":"usd","currencyExponent":2,"aiValueMinor":200,"serviceFeeBasisPoints":1500,"serviceFeeMinor":30,"processingEstimateMinor":39,"processingBufferMinor":2,"totalMinor":271,"policyVersion":1,"exchangeRateVersion":"synthetic-usd","estimateRateVersion":"synthetic-estimate"}"""
        val aiProduct = """{"sku":"synthetic-value","providerProduct":"synthetic_value","currency":"usd","totalMinor":271,"environment":"test",$value}"""
        server.enqueue(MockResponse().setBody(
            """{"available":true,"billingBasis":"actual-ai-usage","products":[$aiProduct,$packProduct]}"""))
        val products = api.catalog().products
        assertEquals(2, products.size)
        assertNotNull(products.single { it.aiValue != null })
        assertEquals(PACK_TOKEN_KIND, products.single { it.isPack }.entitlementKind)
    }
    @Test fun packOrdersMatchTheirProductsOnBothProviders() = runBlocking {
        val product = MinuteProduct("palique-tokens", "palique_tokens", 0, "usd", 499, "test",
            entitlementKind = PACK_TOKEN_KIND, creditNanoUSD = 2_160_000_000L)
        server.enqueue(MockResponse().setBody(packOrder))
        val playOrder = api.create(session, product.sku, "pack-order-001")
        assertTrue(playOrder.matches(product))
        assertEquals(2_160_000_000L, playOrder.creditNanoUSD)
        server.takeRequest()
        val url = "https://checkout.stripe.com/c/pay/cs_test_synthetic#opaque"
        server.enqueue(MockResponse().setBody(packOrder.replace(
            "\"payment\":{\"orderID\":\"$id\",\"obfuscatedAccountID\":\"${"b".repeat(64)}\",\"obfuscatedProfileID\":\"${"c".repeat(64)}\"}",
            "\"payment\":{\"orderID\":\"$id\",\"checkoutURL\":\"$url\"}")))
        val stripeOrder = stripe.createStripe(session, product.sku, id)
        assertTrue(stripeOrder.matches(product))
        assertNull(stripeOrder.aiValue)
        val request = server.takeRequest()
        assertEquals("\"stripe\"", request.body.readUtf8().let { body ->
            kotlinx.serialization.json.Json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }["provider"].toString() })
    }
    @Test fun packStatusesKeepFulfillmentSemantics() = runBlocking {
        // Sandbox unlock: purchased but never fulfilled — the documented
        // test-mode contract, not a verification failure.
        server.enqueue(MockResponse().setBody(sandboxUnlockStatus))
        val sandbox = api.status(session, id)
        assertEquals(PACK_UNLOCK_KIND, sandbox.entitlementKind)
        assertFalse(sandbox.fulfillmentRecorded)
        assertEquals(0, sandbox.grantedMilliseconds)
        assertEquals("test", sandbox.environment)
        server.enqueue(MockResponse().setBody(liveUnlockStatus))
        val live = api.status(session, id)
        assertTrue(live.fulfillmentRecorded)
        assertEquals("2026-10-02T10:00:00.000Z", live.unlockedAt)
        assertEquals("live", live.environment)
        // A refunded live unlock carries its money-returned evidence (699 minor
        // → 6_990_000_000 nano) so the controller words REVERSED, never sandbox.
        server.enqueue(MockResponse().setBody(refundedUnlockStatus))
        val refunded = api.status(session, id)
        assertEquals(6_990_000_000L, refunded.reversalOutstandingNanoUSD)
        assertEquals("live", refunded.environment)
        server.enqueue(MockResponse().setBody(packStatus))
        val credited = api.status(session, id)
        assertEquals(PACK_TOKEN_KIND, credited.entitlementKind)
        assertEquals(2_160_000_000L, credited.grantedNanoUSD)
        assertTrue(credited.fulfillmentRecorded)
        assertEquals(0, credited.reversalOutstandingNanoUSD)
    }
    @Test fun malformedPackFieldsFailClosed() = runBlocking {
        for (body in listOf(
            packProduct.replace("\"creditNanoUSD\":\"2160000000\"", "\"creditNanoUSD\":\"-1\""),
            packProduct.replace("\"creditNanoUSD\":\"2160000000\"", "\"creditNanoUSD\":\"2160000000.0\""),
            packProduct.replace("\"entitlementKind\":\"token_pack\"", "\"entitlementKind\":\"ai_value\",\"minutes\":30"),
            sandboxUnlockStatus.replace("\"grantedNanoUSD\":\"0\"", "\"grantedNanoUSD\":\"many\""),
            // Only the unlock may carry a non-zero outstanding reversal.
            packStatus.replace("\"reversalOutstandingNanoUSD\":\"0\"", "\"reversalOutstandingNanoUSD\":\"5\""),
            sandboxUnlockStatus.replace("\"reversalOutstandingNanoUSD\":\"0\"", "\"reversalOutstandingNanoUSD\":\"-1\""),
            // Every pack status echoes its order's environment for the sandbox rule.
            sandboxUnlockStatus.replace(",\"environment\":\"test\"", ""),
            sandboxUnlockStatus.replace("\"environment\":\"test\"", "\"environment\":\"sandbox\""),
            sandboxUnlockStatus.replace("\"fulfillmentRecorded\":false", "\"fulfillmentRecorded\":true"),
            packStatus.replace("\"fulfillmentRecorded\":true", "\"fulfillmentRecorded\":false"))) {
            if (body.contains("totalMinor") && body.contains("providerProduct")) {
                server.enqueue(MockResponse().setBody("""{"available":true,"billingBasis":"prepaid-service-credit","products":[$body]}"""))
                try { api.catalog(); fail("bad pack product accepted: $body") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
            } else {
                server.enqueue(MockResponse().setBody(body))
                try { api.status(session, id); fail("bad pack status accepted: $body") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
            }
        }
    }
    @Test fun tokenWalletIsAValidatedDisplayHintOnTheFixedRoute() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"currency":"USD","balanceNanoUSD":"2160000000","reservedNanoUSD":"0","availableNanoUSD":"2160000000"}"""))
        val wallet = api.tokenWallet(session)
        assertEquals(2_160_000_000L, wallet.availableNanoUSD)
        val request = server.takeRequest()
        assertEquals("/v1/wallet", request.path)
        assertEquals("Bearer ${session.accessToken}", request.getHeader("Authorization"))
        for (body in listOf(
            """{"currency":"EUR","balanceNanoUSD":"1","reservedNanoUSD":"0","availableNanoUSD":"1"}""",
            """{"currency":"USD","balanceNanoUSD":"2","reservedNanoUSD":"0","availableNanoUSD":"1"}""",
            """{"currency":"USD","balanceNanoUSD":"x","reservedNanoUSD":"0","availableNanoUSD":"0"}""",
            """{"currency":"USD","balanceNanoUSD":"1","reservedNanoUSD":"0"}""")) {
            server.enqueue(MockResponse().setBody(body))
            try { api.tokenWallet(session); fail("bad wallet accepted: $body") } catch (_: MinuteCommerceFailure.InvalidResponse) { }
        }
    }
}
