package com.jkdc.app

import com.jkdc.app.core.TokenProxyBlock
import com.jkdc.app.core.engines.ConversationEngineDeps
import com.jkdc.app.core.engines.EngineException
import com.jkdc.app.core.engines.OpenAICompatibleEngine
import com.jkdc.app.core.engines.RemoteEngineConfig
import com.jkdc.app.network.APIClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The hosted-proxy engine tags its 402/403 rejections so the UI can route them
    to the matching purchase surface (paywall / pack sheet); 429 stays a plain
    retryable message (docs/token-billing.md error matrix). */
class ProxyErrorBlockTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: OpenAICompatibleEngine

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        engine = OpenAICompatibleEngine(
            config = RemoteEngineConfig.HOSTED_PROXY,
            deps = ConversationEngineDeps(appContext = null, openai = APIClient(null, OkHttpClient(), server.url("/")),
                keyLookup = { null }, json = Json, readSession = { null }),
            client = OkHttpClient(),
            baseUrl = server.url("/"),
            credential = { "session-token" },
            proxyErrorMessages = true,
        )
    }
    @After fun tearDown() { server.shutdown() }

    private fun block(status: Int, body: String = """{"error":{"code":"x"}}"""): EngineException {
        var caught: EngineException? = null
        runTest {
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            try { engine.respond("i", "in"); fail("expected EngineException") }
            catch (e: EngineException) { caught = e }
        }
        return caught ?: throw AssertionError("no EngineException raised")
    }

    @Test fun balanceExhaustionTagsThePackSurface() {
        val failure = block(402)
        assertEquals(TokenProxyBlock.BALANCE_EXHAUSTED, failure.proxyBlock)
        assertTrue(!failure.retryable)
        assertTrue(failure.message!!.contains("balance"))
    }
    @Test fun unlockRequirementTagsThePaywall() {
        val failure = block(403)
        assertEquals(TokenProxyBlock.UNLOCK_REQUIRED, failure.proxyBlock)
        assertTrue(!failure.retryable)
        assertTrue(failure.message!!.contains("unlock"))
    }
    @Test fun accountBusyStaysAPlainRetryableMessage() {
        val failure = block(429)
        assertEquals(null, failure.proxyBlock)
        assertTrue(failure.retryable)
    }
    @Test fun otherFailuresCarryNoBlock() {
        val failure = block(502)
        assertEquals(null, failure.proxyBlock)
    }
}
