package chat.mural

import chat.mural.core.engines.ConversationEngineDeps
import chat.mural.core.engines.EngineException
import chat.mural.core.engines.OpenAICompatibleEngine
import chat.mural.core.engines.OpenAIEngineAdapter
import chat.mural.core.engines.RemoteEngineConfig
import chat.mural.network.APIClient
import chat.mural.network.HelperPurpose
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAICompatibleEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: OpenAICompatibleEngine

    private fun deps(keys: Map<String, String> = emptyMap()) = ConversationEngineDeps(
        appContext = null,
        openai = APIClient(null, OkHttpClient(), server.url("/")),
        keyLookup = { keys[it] },
        json = Json,
    )

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        engine = OpenAICompatibleEngine(
            config = RemoteEngineConfig.DEEPSEEK,
            deps = deps(mapOf("DEEPSEEK_API_KEY" to "test-key-123")),
            client = OkHttpClient(),
            baseUrl = server.url("/v1/"),
        )
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `maps chat completions response to APIResult with usage`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"role":"assistant","content":"¡Hola! ¿Qué tal?"}}],
               "usage":{"prompt_tokens":21,"completion_tokens":9}}""",
        ))

        val result = engine.respond("You are a coach", "Say hi", purpose = HelperPurpose.TYPED_REPLY)

        assertEquals("¡Hola! ¿Qué tal?", result.text)
        assertEquals(21, result.usage.input)
        assertEquals(9, result.usage.output)
        assertTrue(result.sources.isEmpty())

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer test-key-123", recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("deepseek-chat", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("You are a coach", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Say hi", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(body["response_format"] == null)
    }

    @Test
    fun `schema switches request to json mode and embeds schema in system prompt`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"content":"{}"}}],"usage":{}}""",
        ))
        val schema = buildJsonObject { put("type", "object") }

        engine.respond("Coach", "input", schema = schema)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("JSON schema"))
        assertTrue(system.contains("\"type\""))
    }

    @Test
    fun `missing key fails with actionable message and never hits the network`() = runTest {
        val noKeyEngine = OpenAICompatibleEngine(RemoteEngineConfig.GLM, deps(), client = OkHttpClient())
        try {
            noKeyEngine.respond("i", "in")
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("ZHIPUAI_API_KEY"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `http 500 is retryable and surfaces the status code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        try {
            engine.respond("i", "in")
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertTrue(e.retryable)
            assertTrue(e.message!!.contains("500"))
        }
    }

    @Test
    fun `http 401 is not retryable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        try {
            engine.respond("i", "in")
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertTrue(!e.retryable)
        }
    }

    @Test
    fun `malformed provider payload fails with parse error`() = runTest {
        server.enqueue(MockResponse().setBody("""{"unexpected": true}"""))
        try {
            engine.respond("i", "in")
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("choices"))
        }
    }

    @Test
    fun `meanings translation request is a plain chat completion parsed into the meanings model`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"role":"assistant","content":"the radio"}}],
               "usage":{"prompt_tokens":148,"completion_tokens":3}}""",
        ))
        val instructions = "Translate the supplied Spanish transcript faithfully into English. Return only the translation."
        val transcript = "la radio que oímos ayer"

        val result = engine.respond(instructions, transcript, purpose = HelperPurpose.MEANING)

        // Same shape the OpenAI meanings path produces: plain text reply + token usage.
        assertEquals("the radio", result.text)
        assertEquals(148, result.usage.input)
        assertEquals(3, result.usage.output)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals(instructions, messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(transcript, messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        // Meanings never request structured output or schema embedding.
        assertTrue(body["response_format"] == null)
    }

    @Test
    fun `cancelInFlight aborts a hung in-flight call so teardown is not blocked`() {
        // NO_RESPONSE parks execute() until the 30 s read timeout unless the
        // call is cancelled — the situation a user pressing End runs into.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val hung = OpenAICompatibleEngine(
            config = RemoteEngineConfig.DEEPSEEK,
            deps = deps(mapOf("DEEPSEEK_API_KEY" to "test-key-123")),
            client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(),
            baseUrl = server.url("/v1/"),
        )
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                kotlinx.coroutines.runBlocking { hung.respond("i", "in") }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        thread.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (server.requestCount == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(100) // execute() is now parked waiting for the absent response
        hung.cancelInFlight()
        thread.join(5_000)
        assertTrue("cancelInFlight must abort the hung call, got: ${failure.get()}", failure.get() is EngineException)
        assertTrue(failure.get()!!.message!!.contains("unreachable"))
    }

    @Test
    fun `keyEnvVar exposes the provider variable so gates can skip the OpenAI key`() {
        assertEquals("DEEPSEEK_API_KEY", engine.keyEnvVar)
        assertEquals("ZHIPUAI_API_KEY",
            OpenAICompatibleEngine(RemoteEngineConfig.GLM, deps(), client = OkHttpClient()).keyEnvVar)
        // The OpenAI engine keeps credential-store gating, so it exposes no environment variable.
        assertEquals(null, OpenAIEngineAdapter(APIClient(null, OkHttpClient(), server.url("/"))).keyEnvVar)
    }
}
