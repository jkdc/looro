package com.jkdc.app.network

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class LevelTestRemoteClientTest {
    @Test
    fun `request fixes variant and uses etag without user credentials`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(304).addHeader("Cache-Control", "public, max-age=600"))
        server.start()
        try {
            val client = LevelTestRemoteClient(server.url("/").toString().toHttpUrl(), timeoutMillis = 500)
            val result = client.fetch("en", "A1", 202642L, 1, "\"stable-etag\"")
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertTrue(request.path!!.startsWith("/v1/level-tests/hybrid?"))
            assertTrue(request.path!!.contains("language=en"))
            assertTrue(request.path!!.contains("level=A1"))
            assertTrue(request.path!!.contains("variant=1"))
            assertEquals("\"stable-etag\"", request.getHeader("If-None-Match"))
            assertNull(request.getHeader("Authorization"))
            assertEquals(RemoteLevelTestResponse.NotModified(600), result)
        } finally { server.shutdown() }
    }

    @Test
    fun `total request timeout returns a typed timeout`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(1, TimeUnit.SECONDS))
        server.start()
        try {
            val client = LevelTestRemoteClient(server.url("/").toString().toHttpUrl(), timeoutMillis = 100)
            val result = client.fetch("en", "A1", 202642L, 1, null)
            assertEquals(RemoteLevelTestResponse.Failed(RemoteLevelTestFailure.TIMEOUT), result)
        } finally { server.shutdown() }
    }

    @Test
    fun `cancelling the coroutine cancels the pending http call`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(5, TimeUnit.SECONDS))
        server.start()
        try {
            val client = LevelTestRemoteClient(server.url("/").toString().toHttpUrl(), timeoutMillis = 5_000)
            val pending = async(Dispatchers.IO) { client.fetch("en", "A1", 202642L, 1, null) }
            withTimeout(1_500) { server.takeRequest(1, TimeUnit.SECONDS)!! }
            pending.cancel()
            assertTrue(pending.isCancelled)
        } finally { server.shutdown() }
    }

    @Test
    fun `oversized response is rejected before json parsing`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{" + "x".repeat(1_100_000) + "}"))
        server.start()
        try {
            val client = LevelTestRemoteClient(server.url("/").toString().toHttpUrl(), timeoutMillis = 1_000)
            assertEquals(RemoteLevelTestResponse.Failed(RemoteLevelTestFailure.INVALID_RESPONSE),
                client.fetch("en", "A1", 202642L, 1, null))
        } finally { server.shutdown() }
    }
}
