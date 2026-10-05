package com.jkdc.app.core

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Closed usage-event catalog, mirrored from services/api/src/usage.ts (and
    Core/UsageMetrics.swift). The API rejects unknown names, unknown prop keys
    and any free-text value, so an event cannot carry conversation content. */
object UsageEvents {
    const val APP_OPEN = "app_open"
    const val SESSION_STARTED = "session_started"
    const val SESSION_ENDED = "session_ended"
    const val SESSION_RESUMED = "session_resumed"
    const val THEME_SELECTED = "theme_selected"
    const val LESSON_STARTED = "lesson_started"
    const val LESSON_COMPLETED = "lesson_completed"
    const val ASSESSMENT_COMPLETED = "assessment_completed"
    const val PRACTICE_COMPLETED = "practice_completed"
    const val WORD_LOOKUP = "word_lookup"
    const val REPLAY_USED = "replay_used"
    const val TTS_SPEED_CHANGED = "tts_speed_changed"
    const val HELP_REQUESTED = "help_requested"
    const val REPORT_REQUESTED = "report_requested"
    const val PAYWALL_VIEWED = "paywall_viewed"
    const val EXERCISE_COMPLETED = "exercise_completed"
    const val LISTENING_COMPLETED = "listening_completed"
    const val SCENARIO_STARTED = "scenario_started"
    const val SCENARIO_COMPLETED = "scenario_completed"
    const val ARCHIVE_HEALTH = "archive_health"

    val all = listOf(APP_OPEN, SESSION_STARTED, SESSION_ENDED, SESSION_RESUMED, THEME_SELECTED,
        LESSON_STARTED, LESSON_COMPLETED, ASSESSMENT_COMPLETED, PRACTICE_COMPLETED, WORD_LOOKUP,
        REPLAY_USED, TTS_SPEED_CHANGED, HELP_REQUESTED, REPORT_REQUESTED, PAYWALL_VIEWED,
        EXERCISE_COMPLETED, LISTENING_COMPLETED, SCENARIO_STARTED, SCENARIO_COMPLETED,
        ARCHIVE_HEALTH)
}

/** Coarse buckets shared with Core/UsageMetrics.swift; never exact durations,
    counts or scores, so the wire carries no reconstructable transcript shape. */
object UsageBuckets {
    fun duration(seconds: Int): Int = when {
        seconds < 30 -> 0; seconds < 120 -> 1; seconds < 300 -> 2; seconds < 900 -> 3; seconds < 1800 -> 4; else -> 5
    }

    fun turns(userTurns: Int): Int = when {
        userTurns < 3 -> 0; userTurns < 10 -> 1; userTurns < 20 -> 2; userTurns < 40 -> 3; userTurns < 60 -> 4; else -> 5
    }

    fun score(percent: Int): Int = when {
        percent >= 80 -> 4; percent >= 60 -> 3; percent >= 40 -> 2; percent >= 20 -> 1; else -> 0
    }

    /** archive_health's database-size bucket: a coarse ordinal string, never
        exact bytes (mirrored by Core/UsageMetrics.swift and the API catalog). */
    fun dbBytes(bytes: Long): String = when {
        bytes <= 0 -> "empty"
        bytes < 1 shl 20 -> "<1mb"
        bytes < 10 shl 20 -> "1-10mb"
        bytes < 100 shl 20 -> "10-100mb"
        else -> ">=100mb"
    }

    /** archive_health's session-count bucket, same coarse-string rule. */
    fun sessions(count: Int): String = when {
        count <= 0 -> "0"
        count <= 10 -> "1-10"
        count <= 50 -> "11-50"
        count <= 200 -> "51-200"
        else -> ">200"
    }
}

@Serializable
data class PendingUsageEvent(
    val id: String,
    val name: String,
    val at: Long,
    val props: JsonObject = JsonObject(emptyMap()),
)

/** Wire envelope for POST /v1/usage/events. */
fun usageBatchJson(platform: String, appVersion: String, events: List<PendingUsageEvent>): String =
    buildJsonObject {
        put("platform", platform)
        put("appVersion", appVersion)
        put("events", buildJsonArray {
            for (event in events) add(buildJsonObject {
                put("id", event.id)
                put("name", event.name)
                put("at", event.at)
                if (event.props is JsonObject && event.props.isNotEmpty()) put("props", event.props)
            })
        })
    }.toString()

/** The durable usage_outbox row's wire id: a deterministic UUID-V4-shaped
    string (00000000-0000-4000-8000-<12 hex of the rowid>) — the server's
    usage endpoint accepts UUID-V4 event ids only (services/api/src/usage.ts
    UUID_V4), and the stable id makes client retries dedupe server-side
    (ON CONFLICT (client_event_id) DO NOTHING). Rowids beyond 48 bits truncate
    into the hex tail; a collision would at worst drop one heartbeat. */
fun durableUsageEventID(rowId: Long): String =
    "00000000-0000-4000-8000-" + (rowId and 0xFFFF_FFFF_FFFFL).toString(16).padStart(12, '0')

/** One durable outbox row: the wire event plus the durable row id the drain
    releases once the row's batch is delivered (the data layer's
    QueuedUsageEvent — event.id already encodes [durableUsageEventID]). */
data class DurableUsageEvent(val rowId: Long, val event: PendingUsageEvent)

/** The durable usage_outbox drain (schema v2's table): implemented by the
    layer that owns the learning database and injected into [UsageReporter] —
    core stays DB-free, the adapter runs its reads/marks on its own dispatcher
    and degrades to an empty drain when no database serves the run (JSON
    fail-safe mode). */
interface DurableUsageOutbox {
    /** Up to [cap] undispatched rows, oldest first. */
    suspend fun pending(cap: Int): List<DurableUsageEvent>

    /** Runs after the rows' batch was delivered; a crash before it re-sends
        the same rows, whose stable ids dedupe server-side. */
    suspend fun markDispatched(rowIds: List<Long>)
}

/** Durable, bounded queue of content-free usage events (the final-assessment-ticket
    pattern): age-pruned, capacity-capped, order-preserving when a flush fails. */
class UsageOutbox(
    private val capacity: Int = 2_000,
    private val ttlMilliseconds: Long = 7 * 24 * 60 * 60_000L,
) {
    private val pending = ArrayDeque<PendingUsageEvent>()

    val size: Int get() = pending.size
    fun snapshot(): List<PendingUsageEvent> = pending.toList()

    fun record(id: String, name: String, at: Long, props: JsonObject = JsonObject(emptyMap())) {
        if (name !in UsageEvents.all) return
        if (pending.any { it.id == id }) return
        pending.addLast(PendingUsageEvent(id, name, at, props))
        while (pending.size > capacity) pending.removeFirst()
    }

    fun prune(now: Long) {
        pending.removeAll { now - it.at > ttlMilliseconds }
    }

    /** Removes the oldest events for sending; put them back with [restore] on failure. */
    fun take(batch: Int): List<PendingUsageEvent> {
        val taken = mutableListOf<PendingUsageEvent>()
        while (taken.size < batch && pending.isNotEmpty()) taken += pending.removeFirst()
        return taken
    }

    fun restore(events: List<PendingUsageEvent>) {
        for (event in events.reversed()) pending.addFirst(event)
    }
}

/** Records and ships content-free usage events. Recording never throws and
    never blocks a feature; a failed flush keeps events queued for next time. */
class UsageReporter(
    private val platform: String,
    private val appVersion: String,
    private val outbox: UsageOutbox = UsageOutbox(),
    private val newID: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val send: suspend (batch: String, events: List<PendingUsageEvent>) -> Boolean,
    /** The durable usage_outbox drain (null: no durable queue — pure
        in-memory callers, tests without a database). */
    private val durable: DurableUsageOutbox? = null,
) {
    /** The user's metrics choice; wiring keeps this in step with preferences. */
    @Volatile var enabled: Boolean = false

    fun record(name: String, props: JsonObjectBuilder.() -> Unit = {}) {
        if (!enabled) return
        outbox.record(newID(), name, clock(), buildJsonObject(props))
    }

    /** Returns true when every taken batch was delivered. The durable
        usage_outbox drains through the SAME POST batches: one capped read
        per flush, its rows topping each batch up to [batch] (a POST never
        exceeds [batch] events — the server rejects larger batches), with
        markDispatched only after that upload succeeded. On failure the rows
        stay pending (dispatched = 0) and the next flush — this run or a
        later one — retries them. */
    suspend fun flush(batch: Int = 100): Boolean {
        outbox.prune(clock())
        var durableLeft = durable?.pending(DURABLE_BATCH) ?: emptyList()
        var delivered = true
        while (true) {
            val events = outbox.take(batch)
            val durableBatch = durableLeft.take(batch - events.size)
            durableLeft = durableLeft.drop(durableBatch.size)
            if (events.isEmpty() && durableBatch.isEmpty()) break
            val merged = events + durableBatch.map { it.event }
            if (send(usageBatchJson(platform, appVersion, merged), merged)) {
                if (durableBatch.isNotEmpty()) durable?.markDispatched(durableBatch.map { it.rowId })
            } else {
                outbox.restore(events); delivered = false; break
            }
        }
        return delivered
    }

    companion object {
        /** Durable rows read per flush (the brief's cap): bounds the drain's
            DB read while leaving older rows for later flushes. */
        const val DURABLE_BATCH = 200
    }
}
