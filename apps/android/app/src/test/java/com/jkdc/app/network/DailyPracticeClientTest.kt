package com.jkdc.app.network

import com.jkdc.app.core.AccountSession
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DailyPracticeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DailyPracticeClient
    private val session = AccountSession("12345678-1234-4123-8123-123456789012", "a".repeat(43), 1_800_000_000_000)

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = DailyPracticeClient(server.url("/"), OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun currentPackageUsesTheFixedPublicEndpointAndDecodesTheSharedFixture() = runBlocking {
        val fixture = java.io.File("../../../shared/contracts/daily-practice/fixtures/day-en.json").readText()
        server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"serverNow":1800000000000,"editorialDate":"2027-01-15","package":$fixture}"""))

        val daily = client.currentPackage("en")

        assertEquals("city-gardens", daily.topic.id)
        assertEquals("en-US", daily.listening.speechLocale)
        val request = server.takeRequest()
        assertEquals("/v1/daily-practice/current?language=en", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun attemptCreationSendsBearerIdempotencyAndTheExactConsentBody() = runBlocking {
        val attempt = "33333333-3333-4333-8333-333333333333"
        val packageID = "22222222-2222-4222-8222-222222222222"
        server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"attemptId":"$attempt","attemptNumber":1,"packageVersionId":"$packageID","language":"en","editorialDate":"2027-01-15","explanationLanguage":"es","revision":0,"state":"in_progress","startedAt":1800000000000,"completedStages":[],"activeMs":0,"stopReason":null,"reviewState":"not_started","reviewId":null,"inputHash":null}"""))

        val result = client.createAttempt(session, attempt, packageID, "es", 1, attempt)

        assertEquals(attempt, result["attemptId"]!!.jsonPrimitive.content)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/daily-practice/attempts", request.path)
        assertEquals("Bearer ${session.accessToken}", request.getHeader("Authorization"))
        assertEquals(attempt, request.getHeader("Idempotency-Key"))
        assertEquals("no-store", request.getHeader("Cache-Control"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(attempt, body["attemptId"]!!.jsonPrimitive.content)
        assertEquals(packageID, body["packageVersionId"]!!.jsonPrimitive.content)
        assertEquals("es", body["explanationLanguage"]!!.jsonPrimitive.content)
        assertEquals(1, body["dailyConsentVersion"]!!.jsonPrimitive.int)
    }

    @Test fun anUncertainAttemptCreateIsNotReportedAsAConfirmedFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try {
            client.createAttempt(session, "33333333-3333-4333-8333-333333333333",
                "22222222-2222-4222-8222-222222222222", "en", 1, "33333333-3333-4333-8333-333333333333")
            throw AssertionError("expected unknown outcome")
        } catch (failure: DailyPracticeFailure.OutcomeUnknown) {
            assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        }
    }

    @Test fun attemptPackageRestoresTheFrozenPublicSnapshot() = runBlocking {
        val attempt = "33333333-3333-4333-8333-333333333333"
        val fixture = java.io.File("../../../shared/contracts/daily-practice/fixtures/day-en.json").readText()
        server.enqueue(MockResponse().setBody(fixture))

        val daily = client.attemptPackage(session, attempt)

        assertEquals("city-gardens", daily.topic.id)
        val request = server.takeRequest()
        assertEquals("/v1/daily-practice/attempts/$attempt/package", request.path)
        assertEquals("Bearer ${session.accessToken}", request.getHeader("Authorization"))
    }

    @Test fun eventAppendPreservesExactEnvelopeAndTreatsTransportAmbiguityAsUnknown() = runBlocking {
        val attempt = "33333333-3333-4333-8333-333333333333"
        val event = "44444444-4444-4444-8444-444444444444"
        val envelope = Json.parseToJsonElement("""{"schemaVersion":1,"eventId":"$event","expectedRevision":0,"event":{"type":"preparation_completed","activeMs":0}}""").jsonObject
        server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"attemptId":"$attempt","revision":1}"""))

        client.appendEvent(session, attempt, envelope)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/daily-practice/attempts/$attempt/events", request.path)
        assertEquals(envelope.toString(), request.body.readUtf8())

        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try {
            client.appendEvent(session, attempt, envelope)
            throw AssertionError("expected unknown outcome")
        } catch (_: DailyPracticeFailure.OutcomeUnknown) {
            assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        }
    }

    @Test fun serverFailureDuringEventWriteIsClassifiedAsUnknownOutcome() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"unavailable\"}"))
        try {
            client.appendEvent(session, "33333333-3333-4333-8333-333333333333",
                Json.parseToJsonElement("""{"schemaVersion":1,"eventId":"44444444-4444-4444-8444-444444444444","expectedRevision":0,"event":{"type":"preparation_completed","activeMs":0}}""").jsonObject)
            throw AssertionError("expected unknown outcome")
        } catch (_: DailyPracticeFailure.OutcomeUnknown) {
            assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        }
    }

    @Test fun aResponseBodyFailureAfterTheServerRespondsKeepsTheWriteOutcomeUnknown() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Length", "4096")
            .setBody("{\"schemaVersion\":1}").setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try {
            client.createAttempt(session, "33333333-3333-4333-8333-333333333333",
                "22222222-2222-4222-8222-222222222222", "en", 1, "33333333-3333-4333-8333-333333333333")
            throw AssertionError("expected unknown outcome")
        } catch (_: DailyPracticeFailure.OutcomeUnknown) {
            assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        }
    }
}
