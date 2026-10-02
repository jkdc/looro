package com.jkdc.app

import com.jkdc.app.core.engines.ConversationEngineDeps
import com.jkdc.app.core.engines.EngineException
import com.jkdc.app.core.engines.OpenAICompatibleEngine
import com.jkdc.app.core.engines.RemoteEngineConfig
import com.jkdc.app.network.HelperPurpose
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The hosted-proxy engine: the OpenAI-compatible engine aimed at the backend's
    `/v1/proxy/responses` with the app session bearer (token billing v1 contract). */
class HostedProxyEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: OpenAICompatibleEngine

    /** The signed-in session the engine resolves per request (mirrors the factory's
        credential: the session bearer, only while it is still valid). */
    private var session: com.jkdc.app.core.AccountSession? =
        session(expiresAt = System.currentTimeMillis() + 3_600_000)

    private fun session(expiresAt: Long) = com.jkdc.app.core.AccountSession(
        "0189d0e6-1111-4111-8111-111111111111", "a".repeat(43), expiresAt)

    private fun deps() = ConversationEngineDeps(
        appContext = null,
        openai = com.jkdc.app.network.APIClient(null, OkHttpClient(), server.url("/")),
        keyLookup = { null },
        json = Json,
        readSession = { session },
    )

    private fun proxyEngine() = OpenAICompatibleEngine(
        config = RemoteEngineConfig.HOSTED_PROXY,
        deps = deps(),
        client = OkHttpClient(),
        baseUrl = server.url("/"),
        completionsPath = "v1/proxy/responses",
        credential = { session?.takeIf { it.isValid(System.currentTimeMillis()) }?.accessToken },
        proxyErrorMessages = true,
    )

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        engine = proxyEngine()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `posts to the proxy path with the session bearer and a non-streaming body`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"role":"assistant","content":"¡Hola!"}}],
               "usage":{"prompt_tokens":30,"completion_tokens":6},
               "metering":{"rateVersion":"2026-09-minimax-m","nanoUSDSettled":"90000"}}""",
        ))

        val result = engine.respond("You are a coach", "Say hi", purpose = HelperPurpose.TYPED_REPLY)

        assertEquals("¡Hola!", result.text)
        assertEquals(30, result.usage.input)
        assertEquals(6, result.usage.output)

        val recorded = server.takeRequest()
        assertEquals("/v1/proxy/responses", recorded.path)
        assertEquals("Bearer " + "a".repeat(43), recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("MiniMax-M2", body["model"]!!.jsonPrimitive.content)
        assertFalse("v1 is non-streaming by contract", body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(2, body["messages"]!!.jsonArray.size)
    }

    @Test
    fun `schema request still embeds json mode like the other compatible engines`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"content":"{}"}}],"usage":{}}""",
        ))
        val schema = kotlinx.serialization.json.buildJsonObject { put("type", "object") }

        engine.respond("Coach", "input", schema = schema)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `http 402 token_balance_exhausted surfaces a readable non-retryable message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(402)
            .setBody("""{"error":{"code":"token_balance_exhausted"}}"""))
        try {
            engine.respond("i", "in")
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("token balance is used up"))
            assertFalse(e.retryable)
        }
    }

    @Test
    fun `http 403 unlock_required surfaces a readable non-retryable message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403)
            .setBody("""{"error":{"code":"unlock_required"}}"""))
        try {
            engine.respond("i", "in")
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("unlock purchase"))
            assertFalse(e.retryable)
        }
    }

    @Test
    fun `http 429 account_busy is retryable with a readable message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429)
            .setBody("""{"error":{"code":"account_busy"}}"""))
        try {
            engine.respond("i", "in")
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("Try again in a moment"))
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `unexpected proxy failures keep the raw status surface`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503)
            .setBody("""{"error":{"code":"proxy_unavailable"}}"""))
        try {
            engine.respond("i", "in")
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("503"))
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `missing session fails without a network call`() = runTest {
        session = null
        try {
            engine.respond("i", "in")
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertEquals(OpenAICompatibleEngine.PROXY_SIGN_IN_MESSAGE, e.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `expired session is treated as signed out`() = runTest {
        session = session(expiresAt = 1L)
        try {
            engine.respond("i", "in")
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertEquals(OpenAICompatibleEngine.PROXY_SIGN_IN_MESSAGE, e.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `hosted-proxy exposes no environment key so gates do not demand one`() {
        assertNull(engine.keyEnvVar)
    }
}
