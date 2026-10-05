package com.jkdc.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMetricsTest {
    private fun outbox(now: Long = 1_000) = UsageOutbox(capacity = 3, ttlMilliseconds = 1_000).apply { prune(now) }

    @Test fun catalogMatchesTheServerContract() {
        // The API's CHECK constraint and USAGE_CATALOG pin this list; a rename here is a cross-platform break.
        assertEquals(20, UsageEvents.all.size)
        assertEquals(UsageEvents.all.distinct().size, UsageEvents.all.size)
        assertTrue(UsageEvents.all.all { it.matches("[a-z_]+".toRegex()) })
    }

    @Test fun bucketsStayCoarse() {
        assertEquals(0, UsageBuckets.duration(29)); assertEquals(1, UsageBuckets.duration(30))
        assertEquals(3, UsageBuckets.duration(899)); assertEquals(5, UsageBuckets.duration(1800))
        assertEquals(0, UsageBuckets.turns(2)); assertEquals(2, UsageBuckets.turns(19)); assertEquals(5, UsageBuckets.turns(61))
        assertEquals(4, UsageBuckets.score(95)); assertEquals(2, UsageBuckets.score(40)); assertEquals(0, UsageBuckets.score(19))
        // archive_health: ordinal bucket strings, never exact bytes or counts
        assertEquals("empty", UsageBuckets.dbBytes(0))
        assertEquals("<1mb", UsageBuckets.dbBytes(1))
        assertEquals("1-10mb", UsageBuckets.dbBytes(1 shl 20))
        assertEquals("10-100mb", UsageBuckets.dbBytes(10 shl 20))
        assertEquals(">=100mb", UsageBuckets.dbBytes(100 shl 20))
        assertEquals("0", UsageBuckets.sessions(0))
        assertEquals("1-10", UsageBuckets.sessions(1))
        assertEquals("1-10", UsageBuckets.sessions(10))
        assertEquals("11-50", UsageBuckets.sessions(11))
        assertEquals("51-200", UsageBuckets.sessions(200))
        assertEquals(">200", UsageBuckets.sessions(201))
    }

    @Test fun outboxKeepsOrderDeduplicatesAndRespectsCapacity() {
        val box = outbox()
        box.record("a", UsageEvents.APP_OPEN, 1_000)
        box.record("b", UsageEvents.WORD_LOOKUP, 1_001, buildJsonObject { put("source", "talk") })
        box.record("a", UsageEvents.APP_OPEN, 1_002) // duplicate id never queues twice
        box.record("c", "not_in_catalog", 1_003) // unknown name rejected client-side too
        assertEquals(2, box.size)
        box.record("d", UsageEvents.APP_OPEN, 1_004)
        box.record("e", UsageEvents.APP_OPEN, 1_005) // capacity 3: oldest ("a") dropped
        assertEquals(listOf("b", "d", "e"), box.snapshot().map { it.id })
        assertEquals(listOf("b", "d"), box.take(2).map { it.id })
        assertEquals(1, box.size)
    }

    @Test fun restorePutsFailedEventsBackInFront() {
        val box = outbox()
        box.record("a", UsageEvents.APP_OPEN, 1_000)
        box.record("b", UsageEvents.APP_OPEN, 1_001)
        val taken = box.take(1)
        assertEquals(listOf("a"), taken.map { it.id })
        box.record("c", UsageEvents.APP_OPEN, 1_002)
        box.restore(taken) // restored events go back in front, ahead of "c" and "b"
        assertEquals(listOf("a", "b", "c"), box.snapshot().map { it.id })
    }

    @Test fun pruneDropsOnlyEventsOlderThanTheTtl() {
        val box = outbox()
        box.record("a", UsageEvents.APP_OPEN, 500) // exactly at the ttl boundary: kept
        box.record("b", UsageEvents.APP_OPEN, 499)
        box.record("c", UsageEvents.APP_OPEN, 1_000)
        box.prune(1_500)
        assertEquals(listOf("a", "c"), box.snapshot().map { it.id })
    }

    @Test fun reporterRecordsOnlyWhenEnabledAndRestoresFailedBatches() {
        val box = UsageOutbox()
        val sent = mutableListOf<String>()
        var succeed = false
        val reporter = UsageReporter("android", "1.0 (1)", outbox = box,
            newID = { "id-${box.size}" }, clock = { 5_000 },
            send = { batch, _ -> sent += batch; succeed })
        reporter.enabled = true
        reporter.record(UsageEvents.APP_OPEN)
        reporter.record(UsageEvents.SESSION_STARTED) { put("input", "voice"); put("provider", "byok") }
        assertEquals(2, box.size)
        reporter.enabled = false
        reporter.record(UsageEvents.REPLAY_USED)
        assertEquals(2, box.size) // disabled recording is a no-op

        kotlinx.coroutines.runBlocking {
            assertFalse(reporter.flush()) // send fails -> events restored
            assertEquals(2, box.size)
            succeed = true
            assertTrue(reporter.flush())
            assertEquals(0, box.size)
        }
        // Two deliveries (one failed, one good), then one wire envelope with platform,
        // appVersion and both events; app_open stays prop-free.
        assertEquals("""{"platform":"android","appVersion":"1.0 (1)","events":[""" +
            """{"id":"id-0","name":"app_open","at":5000},""" +
            """{"id":"id-1","name":"session_started","at":5000,"props":{"input":"voice","provider":"byok"}}]}""",
            sent.last())
    }

    // -- the durable usage_outbox drain (schema v2) ---------------------------

    /** The durable-outbox stub: rows leave [rows] only through markDispatched,
        exactly like DB rows leave dispatched = 0. */
    private class StubDurableOutbox : DurableUsageOutbox {
        var rows = listOf<DurableUsageEvent>()
        val dispatched = mutableListOf<Long>()
        var nextRowId = 1L
        var requestedCap = 0

        fun enqueue(name: String, props: JsonObject = JsonObject(emptyMap())) {
            val rowId = nextRowId++
            rows += DurableUsageEvent(rowId, PendingUsageEvent(
                durableUsageEventID(rowId), name, 5_000, props))
        }

        override suspend fun pending(cap: Int): List<DurableUsageEvent> {
            requestedCap = cap
            return rows.take(cap)
        }

        override suspend fun markDispatched(rowIds: List<Long>) {
            dispatched += rowIds
            rows = rows.filterNot { it.rowId in rowIds }
        }
    }

    @Test fun flushUploadsDurableRowsAndMarksThemDispatchedWithoutAnInMemoryRecord() {
        val durable = StubDurableOutbox()
        durable.enqueue(UsageEvents.ARCHIVE_HEALTH, buildJsonObject {
            put("dbBytesBucket", "1-10mb"); put("sessionsBucket", "1-10")
        })
        durable.enqueue(UsageEvents.APP_OPEN)
        durable.enqueue(UsageEvents.SESSION_RESUMED)
        val batches = mutableListOf<List<PendingUsageEvent>>()
        val reporter = UsageReporter("android", "1.0 (1)",
            send = { _, events -> batches += events; true }, durable = durable)
        kotlinx.coroutines.runBlocking {
            assertTrue(reporter.flush())
        }
        // one POST with the three durable rows (rowid order), the in-memory
        // queue never involved: archive_health rides its durable row alone
        assertEquals(1, batches.size)
        assertEquals(listOf("archive_health", "app_open", "session_resumed"),
            batches.single().map { it.name })
        assertEquals(buildJsonObject {
            put("dbBytesBucket", "1-10mb"); put("sessionsBucket", "1-10")
        }, batches.single().first().props)
        assertTrue(batches.single().all {
            it.id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
        }) // the server accepts UUID-V4 ids only
        // all three marked dispatched; nothing left pending for the next flush
        assertEquals(listOf(1L, 2L, 3L), durable.dispatched)
        assertEquals(0, durable.rows.size)
    }

    @Test fun failedFlushKeepsDurableRowsPendingAndTheNextFlushRetriesThem() {
        val durable = StubDurableOutbox()
        repeat(3) { durable.enqueue(UsageEvents.APP_OPEN) }
        var succeed = false
        val batches = mutableListOf<List<PendingUsageEvent>>()
        val reporter = UsageReporter("android", "1.0 (1)",
            send = { _, events -> batches += events; succeed }, durable = durable)
        kotlinx.coroutines.runBlocking {
            assertFalse(reporter.flush()) // send fails -> rows stay pending
            assertEquals(0, durable.dispatched.size)
            assertEquals(3, durable.rows.size)
            succeed = true
            assertTrue(reporter.flush()) // the next flush retries all three
        }
        assertEquals(2, batches.size)
        assertEquals(3, batches.last().size)
        assertEquals(listOf(1L, 2L, 3L), durable.dispatched)
        assertEquals(0, durable.rows.size)
    }

    @Test fun durableDrainReadsAtMostTwoHundredRowsAndPostsAtMostOneHundredEach() {
        val durable = StubDurableOutbox()
        repeat(250) { durable.enqueue(UsageEvents.APP_OPEN) }
        val batches = mutableListOf<List<PendingUsageEvent>>()
        val reporter = UsageReporter("android", "1.0 (1)",
            send = { _, events -> batches += events; true }, durable = durable)
        kotlinx.coroutines.runBlocking {
            assertTrue(reporter.flush())
        }
        assertEquals(200, durable.requestedCap) // one capped read per flush
        assertEquals(listOf(100, 100), batches.map { it.size }) // ≤ the server's batch cap
        assertEquals(200, durable.dispatched.size)
        assertEquals(50, durable.rows.size) // the tail waits for a later flush
    }

    @Test fun thePostCapIsSharedBetweenInMemoryAndDurableRows() {
        val box = UsageOutbox()
        val durable = StubDurableOutbox()
        durable.enqueue(UsageEvents.APP_OPEN)
        var n = 0
        val batches = mutableListOf<List<PendingUsageEvent>>()
        val reporter = UsageReporter("android", "1.0 (1)", outbox = box,
            newID = { "id-${n++}" }, clock = { 5_000 },
            send = { _, events -> batches += events; true }, durable = durable)
        reporter.enabled = true
        repeat(100) { reporter.record(UsageEvents.SESSION_RESUMED) } // fills a whole batch
        kotlinx.coroutines.runBlocking {
            assertTrue(reporter.flush())
        }
        // first POST: the 100 in-memory events, 0 durable (cap shared); the
        // durable row rides the next POST and gets marked with it
        assertEquals(listOf(100, 1), batches.map { it.size })
        assertEquals("id-0", batches.first().first().id)
        assertEquals("app_open", batches.last().single().name)
        assertEquals(listOf(1L), durable.dispatched)
        assertEquals(0, box.size)
    }
}
