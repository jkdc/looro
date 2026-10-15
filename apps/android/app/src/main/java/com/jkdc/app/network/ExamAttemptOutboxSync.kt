package com.jkdc.app.network

import com.jkdc.app.data.ExamHistoryDao
import com.jkdc.app.data.Row
import com.jkdc.app.data.SqlDatabase
import com.jkdc.app.data.long
import com.jkdc.app.data.real
import com.jkdc.app.data.realOrNull
import com.jkdc.app.data.text
import com.jkdc.app.core.AccountSession
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

/** Drains one owner's durable attempt events in revision order; failures leave the row pending. */
class ExamAttemptOutboxSync(
    private val sendCreation: suspend (AccountSession, JsonObject, JsonObject) -> JsonObject,
    private val sendEvent: suspend (AccountSession, JsonObject) -> JsonObject,
    private val acknowledge: (String, String, String, Long, Long, Double) -> Boolean,
    private val conflict: (String, String, String, Double) -> Boolean,
) {
    suspend fun drain(db: SqlDatabase, session: AccountSession, now: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
        val events = ExamHistoryDao.pendingOutbox(db, session.accountID)
        for (event in events) {
            val attemptID = event.text("attempt_id")
            val eventID = event.text("event_id")
            val revision = event.long("revision")
            val stillPending = db.query("SELECT o.delivery_state FROM exam_sync_outbox o JOIN exam_attempts a " +
                "ON a.attempt_id = o.attempt_id WHERE o.event_id = ? AND o.attempt_id = ? AND o.revision = ? " +
                "AND a.owner_account_id = ?", listOf(eventID, attemptID, revision, session.accountID))
                .singleOrNull()?.text("delivery_state") == "pending"
            if (!stillPending) continue
            val eventTime = event.real("created_at")
            var acceptedAttemptRevision = revision
            val result = if (event.text("event_type") == "created") {
                val (snapshot, attempt) = initialPayload(db, event)
                acceptedAttemptRevision = attempt["revision"]?.jsonPrimitive?.longOrNull ?: return
                sendCreation(session, snapshot, attempt)
            } else {
                val parsed = Json.parseToJsonElement(event.text("payload_json")).jsonObject
                val payload = when (event.text("event_type")) {
                    "progress" -> buildJsonObject {
                        put("questionId", parsed.getValue("questionId")); put("response", parsed.getValue("response"))
                        put("position", parsed.getValue("position")); put("updatedAt", iso(eventTime))
                    }
                    "completed" -> buildJsonObject {
                        put("result", parsed.getValue("result")); put("completedAt", iso(eventTime))
                    }
                    "takeover" -> buildJsonObject { put("updatedAt", iso(eventTime)) }
                    else -> return
                }
                val wireEvent = buildJsonObject {
                    put("eventId", eventID); put("attemptId", attemptID)
                    put("baseRevision", revision - 1); put("revision", revision)
                    put("type", event.text("event_type")); put("payload", payload)
                }
                sendEvent(session, wireEvent)
            }
            val confirmed = if (event.text("event_type") == "created") {
                result["accepted"]?.jsonArray?.any { value ->
                    val accepted = value.jsonObject
                    accepted["attemptId"]?.jsonPrimitive?.content == attemptID &&
                        accepted["revision"]?.jsonPrimitive?.longOrNull == acceptedAttemptRevision
                } == true
            } else {
                val returnedConflict = result["conflict"]?.jsonObject?.get("attempt") as? JsonObject
                if (returnedConflict != null) {
                    conflict(session.accountID, attemptID, returnedConflict.toString(), now())
                    return
                }
                result["acks"]?.jsonArray?.any { value ->
                    val ack = value.jsonObject
                    ack["eventId"]?.jsonPrimitive?.content == eventID &&
                        ack["attemptId"]?.jsonPrimitive?.content == attemptID &&
                        ack["revision"]?.jsonPrimitive?.longOrNull == revision &&
                        ack["status"]?.jsonPrimitive?.content == "accepted"
                } == true
            }
            if (!confirmed || !acknowledge(session.accountID, attemptID, eventID, revision,
                    acceptedAttemptRevision, now())) return
        }
    }

    private fun initialPayload(db: SqlDatabase, event: Row): Pair<JsonObject, JsonObject> {
        val attemptID = event.text("attempt_id")
        val forkedLocalState = runCatching {
            Json.parseToJsonElement(event.text("payload_json")).jsonObject["forkedLocalState"]?.jsonPrimitive?.booleanOrNull == true
        }.getOrDefault(false)
        if (forkedLocalState) {
            val payload = Json.parseToJsonElement(event.text("payload_json")).jsonObject
            val snapshot = payload.getValue("snapshot").jsonObject
            payload["initialAttempt"]?.jsonObject?.let { return snapshot to it }
        }
        val attempt = db.query("SELECT a.*, i.content_id FROM exam_attempts a " +
            "JOIN exam_snapshot_ids i ON i.exam_id = a.exam_id WHERE a.attempt_id = ?", listOf(attemptID)).single()
        val snapshot = db.query("SELECT * FROM exam_snapshots WHERE content_id = ?", listOf(attempt.text("content_id"))).single()
        val content = Json.parseToJsonElement(snapshot.text("content_json")).jsonObject
        val answers = if (forkedLocalState) db.query("SELECT question_id, revision, response_json FROM exam_answers WHERE attempt_id = ? " +
            "AND revision = (SELECT MAX(a2.revision) FROM exam_answers a2 WHERE a2.attempt_id = exam_answers.attempt_id " +
            "AND a2.question_id = exam_answers.question_id) ORDER BY question_id", listOf(attemptID)).map { answer ->
            buildJsonObject {
                put("questionId", answer.text("question_id")); put("revision", answer.long("revision"))
                put("response", Json.parseToJsonElement(answer.text("response_json")))
            }
        } else emptyList()
        val fullSnapshot = JsonObject(content + mapOf(
            "examId" to JsonPrimitive(attempt.text("exam_id")),
            "contentId" to JsonPrimitive(snapshot.text("content_id")),
            "contentHash" to JsonPrimitive(snapshot.text("content_hash")),
            "schemaVersion" to JsonPrimitive(snapshot.long("schema_version")),
            "contentRevision" to JsonPrimitive(snapshot.long("content_revision")),
            "gradingVersion" to JsonPrimitive(snapshot.long("grading_version")),
            "source" to JsonPrimitive(snapshot.text("source")),
            "languageID" to JsonPrimitive(snapshot.text("language_id")),
            "level" to JsonPrimitive(snapshot.text("level")),
            "weekId" to JsonPrimitive(snapshot.long("week_id")),
        ))
        val started = iso(attempt.real("started_at"))
        val initialAttempt = buildJsonObject {
            put("attemptId", attemptID); put("examId", attempt.text("exam_id"))
            put("state", if (forkedLocalState) attempt.text("state") else "in_progress")
            put("position", if (forkedLocalState) attempt.long("position") else 0)
            put("revision", if (forkedLocalState) attempt.long("revision") else 1)
            put("startedAt", started); put("updatedAt", if (forkedLocalState) iso(attempt.real("updated_at")) else started)
            if (forkedLocalState) attempt.realOrNull("completed_at")?.let { put("completedAt", iso(it)) }
            put("answers", JsonArray(answers))
            if (forkedLocalState) db.query("SELECT result_json FROM exam_results WHERE attempt_id = ?", listOf(attemptID)).singleOrNull()
                ?.let { put("result", Json.parseToJsonElement(it.text("result_json"))) }
        }
        return fullSnapshot to initialAttempt
    }

    private fun iso(seconds: Double): String = ISO.format(Instant.ofEpochMilli((seconds * 1000).toLong()))

    companion object {
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
