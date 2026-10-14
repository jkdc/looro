package com.jkdc.app.data

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Durable, device-local exam history. JSON payloads are immutable snapshots of
 * the exact content/grading inputs and are never recomposed from the current bank. */
data class ExamSnapshotRecord(
    val examID: String,
    val contentID: String,
    val contentHash: String,
    val schemaVersion: Int,
    val contentRevision: Int,
    val gradingVersion: Int,
    val source: String,
    val languageID: String,
    val level: String,
    val weekID: Long,
    val contentJSON: String,
    val createdAt: Double,
)

data class ExamAttemptRecord(
    val attemptID: String,
    val examID: String,
    val ownerAccountID: String? = null,
    val startedAt: Double,
)

data class ExamHistory(
    val attempt: Row,
    val snapshot: Row,
    val answers: List<Row>,
    val result: Row?,
)

/** Transactional persistence for the local-first exam lifecycle. */
object ExamHistoryDao {
    fun start(db: SqlDatabase, snapshot: ExamSnapshotRecord, attempt: ExamAttemptRecord): ExamHistory {
        require(snapshot.schemaVersion > 0 && snapshot.contentRevision > 0 && snapshot.gradingVersion > 0)
        val snapshotContent = snapshot.contentJSON.parseJSONObject()
        require(snapshot.weekID > 0)
        require(snapshot.source in setOf("bundled", "deterministic", "llm"))
        require(attempt.attemptID.isNotBlank() && attempt.examID == snapshot.examID)

        db.transaction { tx ->
            tx.insert("exam_snapshots", mapOf(
                "content_id" to snapshot.contentID, "content_hash" to snapshot.contentHash,
                "schema_version" to snapshot.schemaVersion, "content_revision" to snapshot.contentRevision,
                "grading_version" to snapshot.gradingVersion, "source" to snapshot.source,
                "language_id" to snapshot.languageID, "level" to snapshot.level,
                "week_id" to snapshot.weekID, "content_json" to snapshot.contentJSON,
                "created_at" to snapshot.createdAt,
            ), ConflictStrategy.IGNORE)
            val storedSnapshot = tx.query(
                "SELECT * FROM exam_snapshots WHERE content_hash = ?", listOf(snapshot.contentHash)
            ).singleOrNull() ?: error("Exam snapshot insert was not durable")
            check(storedSnapshot.text("content_json") == snapshot.contentJSON &&
                storedSnapshot.text("content_id") == snapshot.contentID &&
                storedSnapshot.int("schema_version") == snapshot.schemaVersion &&
                storedSnapshot.int("content_revision") == snapshot.contentRevision &&
                storedSnapshot.int("grading_version") == snapshot.gradingVersion &&
                storedSnapshot.text("source") == snapshot.source &&
                storedSnapshot.text("language_id") == snapshot.languageID &&
                storedSnapshot.text("level") == snapshot.level &&
                storedSnapshot.long("week_id") == snapshot.weekID) {
                "Content hash already belongs to a different immutable exam snapshot"
            }
            tx.insert("exam_snapshot_ids", mapOf(
                "exam_id" to snapshot.examID, "content_id" to storedSnapshot.text("content_id"),
                "created_at" to snapshot.createdAt,
            ), ConflictStrategy.IGNORE)
            check(tx.query("SELECT content_id FROM exam_snapshot_ids WHERE exam_id = ?", listOf(snapshot.examID))
                .single().text("content_id") == storedSnapshot.text("content_id")) {
                "Exam identity already points to different snapshot content"
            }

            val inserted = tx.insert("exam_attempts", mapOf(
                "attempt_id" to attempt.attemptID, "exam_id" to attempt.examID,
                "owner_account_id" to attempt.ownerAccountID, "state" to "in_progress",
                "sync_state" to if (attempt.ownerAccountID == null) "local_only" else "pending",
                "position" to 0, "revision" to 1, "started_at" to attempt.startedAt,
                "updated_at" to attempt.startedAt, "completed_at" to null,
            ), ConflictStrategy.IGNORE)
            val storedAttempt = tx.query("SELECT * FROM exam_attempts WHERE attempt_id = ?", listOf(attempt.attemptID))
                .singleOrNull() ?: error("Exam attempt insert was not durable")
            check(storedAttempt.text("exam_id") == attempt.examID &&
                storedAttempt["owner_account_id"] == attempt.ownerAccountID) {
                "Attempt identity already belongs to different exam or owner"
            }
            if (inserted != -1L) enqueue(tx, attempt.attemptID, storedAttempt.long("revision"), "created",
                buildJsonObject {
                    put("attemptId", JsonPrimitive(attempt.attemptID))
                    put("examId", JsonPrimitive(attempt.examID))
                    put("snapshot", snapshotContent)
                }.toString(), attempt.startedAt)
        }
        return load(db, attempt.attemptID, attempt.ownerAccountID)
            ?: error("Started exam attempt could not be reloaded")
    }

    /** Saves answer and next position with a revisioned outbox event atomically. */
    fun saveAnswer(db: SqlDatabase, attemptID: String, questionID: String, responseJSON: String,
                   position: Int, savedAt: Double): Long {
        require(questionID.isNotBlank() && position >= 0)
        val response = responseJSON.parseJSONObject()
        return db.transaction { tx ->
            val attempt = tx.query("SELECT state, revision FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
                .singleOrNull() ?: error("Exam attempt does not exist")
            check(attempt.text("state") == "in_progress") { "Completed exam attempts are immutable" }
            val revision = attempt.long("revision") + 1
            tx.insert("exam_answers", mapOf("attempt_id" to attemptID, "question_id" to questionID,
                "revision" to revision, "response_json" to responseJSON, "saved_at" to savedAt))
            tx.update("UPDATE exam_attempts SET position = ?, revision = ?, updated_at = ? " +
                "WHERE attempt_id = ? AND state = 'in_progress' AND revision = ?",
                listOf(position, revision, savedAt, attemptID, revision - 1))
            check(tx.query("SELECT revision FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
                .single().long("revision") == revision) { "Exam attempt changed concurrently" }
            val payload = buildJsonObject {
                put("attemptId", JsonPrimitive(attemptID))
                put("questionId", JsonPrimitive(questionID))
                put("response", response)
                put("position", JsonPrimitive(position))
            }.toString()
            enqueue(tx, attemptID, revision, "progress", payload, savedAt)
            revision
        }
    }

    /** Completion is immutable and safely repeatable only for the same result. */
    fun complete(db: SqlDatabase, attemptID: String, resultJSON: String, completedAt: Double): Boolean {
        val resultContent = resultJSON.parseJSONObject()
        return db.transaction { tx ->
            val attempt = tx.query("SELECT state, revision FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
                .singleOrNull() ?: error("Exam attempt does not exist")
            if (attempt.text("state") == "completed") {
                val existing = tx.query("SELECT result_json FROM exam_results WHERE attempt_id = ?", listOf(attemptID))
                    .singleOrNull() ?: error("Completed exam has no result")
                check(existing.text("result_json") == resultJSON) { "Completed exam result cannot be changed" }
                return@transaction false
            }
            val revision = attempt.long("revision") + 1
            tx.insert("exam_results", mapOf("attempt_id" to attemptID,
                "result_json" to resultJSON, "recorded_at" to completedAt))
            tx.update("UPDATE exam_attempts SET state = 'completed', revision = ?, updated_at = ?, completed_at = ? " +
                "WHERE attempt_id = ? AND state = 'in_progress' AND revision = ?",
                listOf(revision, completedAt, completedAt, attemptID, revision - 1))
            check(tx.query("SELECT state, revision FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
                .single().let { it.text("state") == "completed" && it.long("revision") == revision }) {
                "Exam attempt changed concurrently"
            }
            enqueue(tx, attemptID, revision, "completed", buildJsonObject {
                put("attemptId", JsonPrimitive(attemptID))
                put("result", resultContent)
            }.toString(), completedAt)
            true
        }
    }

    fun load(db: SqlDatabase, attemptID: String, ownerAccountID: String? = null): ExamHistory? {
        val attempt = if (ownerAccountID == null) {
            db.query("SELECT * FROM exam_attempts WHERE attempt_id = ? AND owner_account_id IS NULL",
                listOf(attemptID)).singleOrNull()
        } else {
            db.query("SELECT * FROM exam_attempts WHERE attempt_id = ? AND owner_account_id = ?",
                listOf(attemptID, ownerAccountID)).singleOrNull()
        } ?: return null
        val snapshot = db.query("SELECT s.* FROM exam_snapshots s " +
            "JOIN exam_snapshot_ids i ON i.content_id = s.content_id WHERE i.exam_id = ?",
            listOf(attempt.text("exam_id"))).single()
        val answers = db.query("SELECT a.* FROM exam_answers a JOIN " +
            "(SELECT question_id, MAX(revision) AS revision FROM exam_answers WHERE attempt_id = ? GROUP BY question_id) latest " +
            "ON latest.question_id = a.question_id AND latest.revision = a.revision " +
            "WHERE a.attempt_id = ? ORDER BY a.saved_at, a.question_id", listOf(attemptID, attemptID))
        val result = db.query("SELECT * FROM exam_results WHERE attempt_id = ?", listOf(attemptID)).singleOrNull()
        return ExamHistory(attempt, snapshot, answers, result)
    }

    private fun enqueue(db: SqlDatabase, attemptID: String, revision: Long, type: String,
                        payload: String, createdAt: Double) {
        db.insert("exam_sync_outbox", mapOf("event_id" to UUID.randomUUID().toString(),
            "attempt_id" to attemptID, "revision" to revision, "event_type" to type,
            "payload_json" to payload, "delivery_state" to "pending", "created_at" to createdAt,
            "acknowledged_at" to null))
    }

    private fun String.parseJSONObject(): JsonObject = Json.parseToJsonElement(this) as? JsonObject
        ?: throw IllegalArgumentException("Exam history payload must be a JSON object")
}
