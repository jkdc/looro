package com.jkdc.app.network

import com.jkdc.app.core.AccountSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ProgressClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ProgressClient
    private val session = AccountSession("12345678-1234-1234-1234-123456789012", "a".repeat(43), 100_000)

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = ProgressClient(server.url("/"), OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun exactVersionUnavailableIsTypedSeparatelyFromOtherFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"progress_sync_version_unavailable"}}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"temporarily_unavailable"}}"""))

        assertTrue(client.fetchV2(session) is ProgressFetchResult.VersionUnavailable)
        assertFalse(client.fetchV2(session) is ProgressFetchResult.VersionUnavailable)
    }

    @Test fun uploadUsesV2EnvelopeAndValidatesWholeBatchAcknowledgement() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"merged":1}"""))
        val result = client.sendV2(session, """[{"kind":"lesson","itemID":"en-l1","language":"en","firstAt":1792929599000,"lastAt":1792929600000,"attempts":1,"bestScore":0}]""")
        assertEquals(ProgressSendResult.Acknowledged(1), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("2", payload["version"].toString())
        assertEquals(1, payload["entries"]!!.jsonArray.size)
    }

    @Test fun malformedPageIsRejectedInsteadOfBecomingBootstrapData() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":2,"progress":[{"kind":"lesson","itemID":"x","firstAt":2000,"lastAt":1000,"attempts":1,"bestScore":0,"language":"en"}],"nextCursor":null,"complete":true}"""))
        assertTrue(client.fetchV2(session) is ProgressFetchResult.Failure)
    }

    @Test fun fractionalUnixMillisecondsAreRejectedBeforePersistence() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":2,"progress":[{"kind":"lesson","itemID":"x","firstAt":1792929599000.5,"lastAt":1792929600000,"attempts":1,"bestScore":0,"language":"en"}],"nextCursor":null,"complete":true}"""))

        assertTrue(client.fetchV2(session) is ProgressFetchResult.Failure)
    }

    @Test fun bootstrapRequestsTheVersionedProgressContract() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":2,"progress":[],"nextCursor":null,"complete":true}"""))

        client.fetch(session)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("2", request.requestUrl?.queryParameter("version"))
    }
}
