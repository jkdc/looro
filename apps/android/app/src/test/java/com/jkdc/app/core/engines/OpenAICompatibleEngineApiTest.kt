package com.jkdc.app.core.engines

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `api` engine delegates provider/model/credentials to the server: the
    request body carries no model field, no purpose header, and the call does
    not depend on any prior policy fetch. */
class OpenAICompatibleEngineApiTest {
    @Test
    fun `api engine sends no model field and no purpose header`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"content":"hola"}}],"usage":{"prompt_tokens":3,"completion_tokens":1}}"""
            )
        )
        val engine = OpenAICompatibleEngine(
            RemoteEngineConfig.API,
            ConversationEngineDeps(
                appContext = null,
                openai = null,
                keyLookup = { null },
                json = Json,
                readSession = { null },
            ),
            baseUrl = server.url("/"),
            completionsPath = "v1/proxy/responses",
            credential = { "session-bearer" },
        )
        engine.respond("instructions", "hola")
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("x-palique-purpose"))
        assertEquals("Bearer session-bearer", recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertNull(body["model"])
        assertEquals(false, body["stream"]?.toString()?.toBooleanStrict())
        server.shutdown()
    }
}
