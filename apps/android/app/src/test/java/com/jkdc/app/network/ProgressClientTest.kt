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

    @Test fun uploadTimestampValidationUsesInjectedDeviceClock() = runBlocking {
        client = ProgressClient(server.url("/"), OkHttpClient()) { 1_750_000_000_000L }

        val result = client.sendV2(session,
            """[{"kind":"lesson","itemID":"en-l1","language":"en","firstAt":1760000000000,"lastAt":1760000000000,"attempts":1,"bestScore":0}]""")

        assertTrue(result is ProgressSendResult.Failure)
        assertEquals(0, server.requestCount)
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

    @Test fun legacyBootstrapAcceptsActualBackendFirstAndLastTimestamps() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"progress_sync_version_unavailable"}}"""))
        server.enqueue(MockResponse().setBody("""{"progress":[{"kind":"lesson","itemID":"en-l1","language":"en","firstAt":1792929599000,"lastAt":1792929600000,"attempts":2,"bestScore":80}]}"""))

        val result = client.fetchBootstrap(session, null) { true }

        assertTrue("The real v1 response must be accepted", result is ProgressFetchResult.Success)
        val body = kotlinx.serialization.json.Json.parseToJsonElement((result as ProgressFetchResult.Success).body).jsonObject
        val row = body["rows"]!!.jsonArray.single().jsonObject
        assertEquals("1792929599000", row["firstAt"].toString())
        assertEquals("1792929600000", row["lastAt"].toString())
        assertEquals("2", server.takeRequest().requestUrl?.queryParameter("version"))
        assertEquals(null, server.takeRequest().requestUrl?.queryParameter("version"))
    }

    @Test fun legacyBootstrapKeepsCompatibilityWithSingleTimestampRows() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"progress_sync_version_unavailable"}}"""))
        server.enqueue(MockResponse().setBody("""{"progress":[{"kind":"lesson","itemID":"en-l1","language":"en","at":1740000000000,"attempts":1,"bestScore":0}]}"""))

        val result = client.fetchBootstrap(session, null) { true }

        assertTrue(result is ProgressFetchResult.Success)
    }

    @Test fun legacyBootstrapRejectsInvalidTimestampRangeRatherThanPersistingIt() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"progress_sync_version_unavailable"}}"""))
        server.enqueue(MockResponse().setBody("""{"progress":[{"kind":"lesson","itemID":"en-l1","language":"en","firstAt":1740000001000,"lastAt":1740000000000,"attempts":1,"bestScore":0}]}"""))

        assertTrue(client.fetchBootstrap(session, null) { true } is ProgressFetchResult.Failure)
    }

    @Test fun legacyBootstrapRejectsInvalidProgressIdentity() = runBlocking {
        for ((kind, item, language) in listOf(
            Triple("bogus", "en-l1", "en"), Triple("lesson", "!", "en"), Triple("lesson", "en-l1", "bogus"),
        )) {
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"progress_sync_version_unavailable"}}"""))
            server.enqueue(MockResponse().setBody("""{"progress":[{"kind":"$kind","itemID":"$item","language":"$language","firstAt":1740000000000,"lastAt":1740000001000,"attempts":1,"bestScore":0}]}"""))
            assertTrue("Invalid identity $kind/$item/$language must be rejected", client.fetchBootstrap(session, null) { true } is ProgressFetchResult.Failure)
        }
    }

    @Test fun legacyBootstrapNormalizesIntegralJsonNumbersForPersistence() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"progress_sync_version_unavailable"}}"""))
        server.enqueue(MockResponse().setBody("""{"progress":[{"kind":"lesson","itemID":"en-l1","language":"en","firstAt":1740000000000.0,"lastAt":1.740000001e12,"attempts":1,"bestScore":0}]}"""))

        val result = client.fetchBootstrap(session, null) { true } as ProgressFetchResult.Success
        val row = kotlinx.serialization.json.Json.parseToJsonElement(result.body).jsonObject["rows"]!!.jsonArray.single().jsonObject
        assertEquals("1740000000000", row["firstAt"].toString())
        assertEquals("1740000001000", row["lastAt"].toString())
    }
}
