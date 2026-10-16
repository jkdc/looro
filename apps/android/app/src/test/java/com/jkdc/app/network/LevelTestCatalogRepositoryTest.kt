package com.jkdc.app.network

import java.io.File
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LevelTestCatalogRepositoryTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
    private val body = LevelTestSnapshotAdapterTest().snapshot().toString()

    @Test
    fun `late remote response is cached for the next attempt and cannot replace captured selection`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(body).addHeader("ETag", "\"remote\"")
            .addHeader("Cache-Control", "public, max-age=600").setBodyDelay(250, java.util.concurrent.TimeUnit.MILLISECONDS))
        server.start()
        val directory = createTempDirectory()
        try {
            val repository = repository(server, directory)
            val frozen = repository.select("en", "A1", 202642L)!!
            val beforeIds = frozen.exam.items.map { it.id }
            val refresh = async(Dispatchers.Default) { repository.refresh("en", "A1", 202642L) }
            server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("bundled", frozen.source)
            assertEquals(beforeIds, frozen.exam.items.map { it.id })
            refresh.await()
            val next = repository.select("en", "A1", 202642L)!!
            assertEquals("llm", next.source)
            assertEquals("server-q-0", next.exam.items.first().id)
            assertNotEquals(beforeIds.first(), next.exam.items.first().id)
        } finally { server.shutdown(); directory.deleteRecursively() }
    }

    @Test
    fun `304 reuses only a valid matching stale cache`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(304).addHeader("Cache-Control", "public, max-age=600"))
        server.start()
        val directory = createTempDirectory()
        try {
            val cache = LevelTestCatalogCache(directory)
            assertTrue(cache.write(CachedLevelTestSnapshot("en", "A1", 1, body, "\"same\"", now - 1)))
            val repository = repository(server, directory)
            repository.refresh("en", "A1", 202642L)
            val request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("\"same\"", request.getHeader("If-None-Match"))
            val selected = repository.select("en", "A1", 202642L)!!
            assertEquals("llm", selected.source)
            assertEquals("server-q-0", selected.exam.items.first().id)
        } finally { server.shutdown(); directory.deleteRecursively() }
    }

    @Test
    fun `304 without a valid cache never exposes absent or corrupt remote content`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(304).addHeader("Cache-Control", "public, max-age=600"))
        server.start()
        val directory = createTempDirectory()
        try {
            val cache = LevelTestCatalogCache(directory)
            assertTrue(cache.write(CachedLevelTestSnapshot("en", "A1", 1, "not-json", "\"bad\"", now - 1)))
            val repository = repository(server, directory)
            repository.refresh("en", "A1", 202642L)
            assertNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!.getHeader("If-None-Match"))
            assertEquals("bundled", repository.select("en", "A1", 202642L)?.source)
        } finally { server.shutdown(); directory.deleteRecursively() }
    }

    @Test
    fun `utc week identity follows iso monday and year rollover`() {
        assertEquals(202653L, LevelTestCatalogRepository.utcWeekID(java.time.LocalDate.of(2027, 1, 3)))
        assertEquals(202701L, LevelTestCatalogRepository.utcWeekID(java.time.LocalDate.of(2027, 1, 4)))
    }

    @Test
    fun `choosing a bundled exam never reads disk synchronously`() {
        val cache = ReadCountingCache()
        val repository = LevelTestCatalogRepository(origin = null, cache = cache)

        assertEquals("bundled", repository.select("en", "A1", 202642L)?.source)
        assertEquals(0, cache.reads)
    }

    private class ReadCountingCache : LevelTestCatalogSnapshotStore {
        var reads = 0
        override fun read(language: String, level: String, variant: Int): CachedLevelTestSnapshot? {
            reads++
            return null
        }
        override fun write(entry: CachedLevelTestSnapshot): Boolean = false
    }

    private fun repository(server: MockWebServer, directory: File) = LevelTestCatalogRepository(
        origin = server.url("/").toString().toHttpUrl(),
        cache = LevelTestCatalogCache(directory),
        nowMillis = { now },
    )

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("level-tests-").toFile()
}
