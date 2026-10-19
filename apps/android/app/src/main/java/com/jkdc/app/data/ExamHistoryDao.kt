package com.jkdc.app.data

import java.util.UUID
import com.jkdc.app.network.LevelTestSnapshotAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
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
    /** Returns only the authenticated owner's durable pending events, in revision order. */
    fun pendingOutbox(db: SqlDatabase, ownerAccountID: String): List<Row> = db.query(
        "SELECT o.* FROM exam_sync_outbox o JOIN exam_attempts a ON a.attempt_id = o.attempt_id " +
            "WHERE a.owner_account_id = ? AND a.sync_state != 'conflict' AND o.delivery_state = 'pending' " +
            "ORDER BY o.attempt_id, o.revision", listOf(ownerAccountID)
    )

    /** Acknowledges exactly one server-confirmed event; newer edits remain pending. */
    fun acknowledgeOutbox(db: SqlDatabase, ownerAccountID: String, attemptID: String,
                         eventID: String, revision: Long, acknowledgedAt: Double,
                         acceptedAttemptRevision: Long = revision): Boolean = db.transaction { tx ->
        val event = tx.query("SELECT o.delivery_state, o.event_type FROM exam_sync_outbox o JOIN exam_attempts a " +
            "ON a.attempt_id = o.attempt_id WHERE o.event_id = ? AND o.attempt_id = ? AND o.revision = ? " +
            "AND a.owner_account_id = ?", listOf(eventID, attemptID, revision, ownerAccountID)).singleOrNull()
            ?: return@transaction false
        if (acceptedAttemptRevision < revision ||
            (acceptedAttemptRevision > revision && event.text("event_type") != "created")) return@transaction false
        val currentRevision = tx.query("SELECT revision FROM exam_attempts WHERE attempt_id = ? AND owner_account_id = ?",
            listOf(attemptID, ownerAccountID)).singleOrNull()?.long("revision") ?: return@transaction false
        if (acceptedAttemptRevision > currentRevision) return@transaction false
        if (event.text("delivery_state") != "acknowledged") {
            tx.update("UPDATE exam_sync_outbox SET delivery_state = 'acknowledged', acknowledged_at = ? " +
                "WHERE event_id = ? AND attempt_id = ? AND revision = ? AND delivery_state IN ('pending','in_flight')",
                listOf(acknowledgedAt, eventID, attemptID, revision))
            val acknowledged = tx.query("SELECT 1 AS found FROM exam_sync_outbox WHERE event_id = ? AND attempt_id = ? " +
                "AND revision = ? AND delivery_state = 'acknowledged'", listOf(eventID, attemptID, revision)).isNotEmpty()
            if (!acknowledged) return@transaction false
        }
        if (acceptedAttemptRevision > revision) {
            tx.update("UPDATE exam_sync_outbox SET delivery_state = 'superseded' WHERE attempt_id = ? " +
                "AND event_id != ? AND revision > ? AND revision <= ? AND delivery_state IN ('pending','in_flight')",
                listOf(attemptID, eventID, revision, acceptedAttemptRevision))
            val coveredPending = tx.query("SELECT 1 AS found FROM exam_sync_outbox WHERE attempt_id = ? " +
                "AND revision > ? AND revision <= ? AND delivery_state IN ('pending','in_flight')",
                listOf(attemptID, revision, acceptedAttemptRevision)).isNotEmpty()
            if (coveredPending) return@transaction false
        }
        tx.update("UPDATE exam_attempts SET sync_state = CASE WHEN EXISTS (" +
                "SELECT 1 FROM exam_sync_outbox WHERE attempt_id = ? AND delivery_state IN ('pending','in_flight')" +
            ") THEN 'pending' ELSE 'synced' END WHERE attempt_id = ? AND owner_account_id = ? AND sync_state != 'conflict'",
            listOf(attemptID, attemptID, ownerAccountID))
        true
    }

    /** Preserves the local branch by marking it conflicted; callers must persist the server version separately. */
    fun markConflict(db: SqlDatabase, ownerAccountID: String, attemptID: String): Boolean = db.transaction { tx ->
        tx.update("UPDATE exam_attempts SET sync_state = 'conflict' WHERE attempt_id = ? AND owner_account_id = ?",
            listOf(attemptID, ownerAccountID))
        tx.query("SELECT 1 AS found FROM exam_attempts WHERE attempt_id = ? AND owner_account_id = ? AND sync_state = 'conflict'",
            listOf(attemptID, ownerAccountID)).isNotEmpty()
    }

    /** Stores the server branch beside a frozen copy of local state before exposing a conflict. */
    fun persistConflict(db: SqlDatabase, ownerAccountID: String, attemptID: String,
                        serverAttemptJSON: String, receivedAt: Double): Boolean = db.transaction { tx ->
        val server = runCatching { Json.parseToJsonElement(serverAttemptJSON).jsonObject }.getOrNull() ?: return@transaction false
        val serverID = server["attemptId"]?.jsonPrimitive?.contentOrNull
        val serverRevision = server["revision"]?.jsonPrimitive?.intOrNull
        if (serverID != attemptID || serverRevision == null || serverRevision < 1) return@transaction false
        val attempt = tx.query("SELECT * FROM exam_attempts WHERE attempt_id = ? AND owner_account_id = ?",
            listOf(attemptID, ownerAccountID)).singleOrNull() ?: return@transaction false
        val localAnswers = tx.query("SELECT question_id, revision, response_json FROM exam_answers " +
            "WHERE attempt_id = ? ORDER BY revision, question_id", listOf(attemptID)).map { row ->
            buildJsonObject {
                put("questionId", row.text("question_id")); put("revision", row.long("revision"))
                put("response", Json.parseToJsonElement(row.text("response_json")))
            }
        }
        val localResult = tx.query("SELECT result_json FROM exam_results WHERE attempt_id = ?", listOf(attemptID)).singleOrNull()
        val local = buildJsonObject {
            put("attempt", buildJsonObject {
                put("attemptId", attemptID); put("examId", attempt.text("exam_id")); put("state", attempt.text("state"))
                put("position", attempt.long("position")); put("revision", attempt.long("revision"))
                put("startedAt", attempt.real("started_at")); put("updatedAt", attempt.real("updated_at"))
                attempt.realOrNull("completed_at")?.let { put("completedAt", it) }
            })
            put("answers", kotlinx.serialization.json.JsonArray(localAnswers))
            localResult?.let { put("result", Json.parseToJsonElement(it.text("result_json"))) }
        }
        val existing = tx.query("SELECT 1 AS found FROM exam_sync_conflicts WHERE attempt_id = ? AND owner_account_id = ?",
            listOf(attemptID, ownerAccountID)).isNotEmpty()
        if (existing) {
            tx.update("UPDATE exam_sync_conflicts SET server_revision = ?, server_attempt_json = ?, " +
                "local_attempt_json = ?, received_at = ?, resolution = NULL, resolved_at = NULL " +
                "WHERE attempt_id = ? AND owner_account_id = ?",
                listOf(serverRevision, serverAttemptJSON, local.toString(), receivedAt, attemptID, ownerAccountID))
        } else {
            tx.insert("exam_sync_conflicts", mapOf("attempt_id" to attemptID, "owner_account_id" to ownerAccountID,
                "server_revision" to serverRevision, "server_attempt_json" to serverAttemptJSON,
                "local_attempt_json" to local.toString(), "received_at" to receivedAt))
        }
        tx.update("UPDATE exam_attempts SET sync_state = 'conflict' WHERE attempt_id = ? AND owner_account_id = ?",
            listOf(attemptID, ownerAccountID))
        tx.query("SELECT 1 AS found FROM exam_attempts WHERE attempt_id = ? AND owner_account_id = ? AND sync_state = 'conflict'",
            listOf(attemptID, ownerAccountID)).isNotEmpty()
    }

    fun conflict(db: SqlDatabase, ownerAccountID: String, attemptID: String): Row? = db.query(
        "SELECT * FROM exam_sync_conflicts WHERE attempt_id = ? AND owner_account_id = ?",
        listOf(attemptID, ownerAccountID)).singleOrNull()

    /** Explicitly adopts the confirmed server branch and queues a revision-guarded takeover event. */
    fun resolveConflictTakeover(db: SqlDatabase, ownerAccountID: String, attemptID: String,
                                resolvedAt: Double): Boolean = db.transaction { tx ->
        val conflict = tx.query("SELECT server_attempt_json FROM exam_sync_conflicts " +
            "WHERE attempt_id = ? AND owner_account_id = ? AND resolution IS NULL", listOf(attemptID, ownerAccountID)).singleOrNull()
            ?: return@transaction false
        val server = runCatching { Json.parseToJsonElement(conflict.text("server_attempt_json")).jsonObject }.getOrNull()
            ?: return@transaction false
        val serverID = server["attemptId"]?.jsonPrimitive?.contentOrNull
        val examID = server["examId"]?.jsonPrimitive?.contentOrNull
        val revision = server["revision"]?.jsonPrimitive?.longOrNull
        val position = server["position"]?.jsonPrimitive?.intOrNull
        val state = server["state"]?.jsonPrimitive?.contentOrNull
        val startedAt = server["startedAt"]?.jsonPrimitive?.contentOrNull?.let(::parseISOSeconds)
        val updatedAt = server["updatedAt"]?.jsonPrimitive?.contentOrNull?.let(::parseISOSeconds)
        if (serverID != attemptID || examID.isNullOrBlank() || revision == null || revision < 1 || position == null || position < 0 ||
            state !in setOf("in_progress", "completed") || startedAt == null || updatedAt == null) return@transaction false
        val answers = server["answers"]?.jsonArray ?: return@transaction false
        if (state == "completed" && server["result"] !is JsonObject) return@transaction false
        val preparedAnswers = runCatching { answers.map { value ->
            val answer = value.jsonObject
            Triple(answer.getValue("questionId").jsonPrimitive.content,
                answer.getValue("revision").jsonPrimitive.longOrNull ?: error("Missing answer revision"),
                answer.getValue("response"))
        } }.getOrNull() ?: return@transaction false
        val completedAt = server["completedAt"]?.jsonPrimitive?.contentOrNull?.let(::parseISOSeconds)
        if (state == "completed" && completedAt == null) return@transaction false
        tx.update("UPDATE exam_sync_outbox SET delivery_state = 'superseded' WHERE attempt_id = ? " +
            "AND delivery_state IN ('pending','in_flight')", listOf(attemptID))
        tx.update("DELETE FROM exam_answers WHERE attempt_id = ?", listOf(attemptID))
        tx.update("DELETE FROM exam_results WHERE attempt_id = ?", listOf(attemptID))
        preparedAnswers.forEach { (questionID, answerRevision, response) ->
            tx.insert("exam_answers", mapOf("attempt_id" to attemptID, "question_id" to questionID,
                "revision" to answerRevision, "response_json" to response.toString(), "saved_at" to updatedAt))
        }
        (server["result"] as? JsonObject)?.let { result ->
            tx.insert("exam_results", mapOf("attempt_id" to attemptID, "result_json" to result.toString(),
                "recorded_at" to (completedAt ?: updatedAt)))
        }
        val syncState = if (state == "completed") "synced" else "pending"
        val localRevision = if (state == "completed") revision else revision + 1
        val updateSQL = if (completedAt == null) {
            "UPDATE exam_attempts SET state = ?, sync_state = ?, position = ?, revision = ?, " +
                "started_at = ?, updated_at = ?, completed_at = NULL WHERE attempt_id = ? AND owner_account_id = ?"
        } else {
            "UPDATE exam_attempts SET state = ?, sync_state = ?, position = ?, revision = ?, " +
                "started_at = ?, updated_at = ?, completed_at = ? WHERE attempt_id = ? AND owner_account_id = ?"
        }
        val updateArgs = if (completedAt == null) listOf(state, syncState, position, localRevision,
            startedAt, updatedAt, attemptID, ownerAccountID) else listOf(state, syncState, position, revision,
            startedAt, updatedAt, completedAt, attemptID, ownerAccountID)
        tx.update(updateSQL, updateArgs)
        val storedRevision = tx.query("SELECT revision FROM exam_attempts WHERE attempt_id = ? AND owner_account_id = ? " +
            "AND state = ?", listOf(attemptID, ownerAccountID, state)).singleOrNull()?.long("revision")
        if (storedRevision != localRevision)
            return@transaction false
        if (state == "in_progress") {
            val eventAt = maxOf(resolvedAt, updatedAt)
            enqueue(tx, attemptID, revision + 1, "takeover", buildJsonObject {
                put("updatedAt", isoTimestamp(eventAt))
            }.toString(), eventAt)
        }
        tx.update("UPDATE exam_sync_conflicts SET resolution = 'takeover', resolved_at = ? " +
            "WHERE attempt_id = ? AND owner_account_id = ? AND resolution IS NULL", listOf(resolvedAt, attemptID, ownerAccountID))
        tx.query("SELECT 1 AS found FROM exam_sync_conflicts WHERE attempt_id = ? AND owner_account_id = ? AND resolution = 'takeover'",
            listOf(attemptID, ownerAccountID)).isNotEmpty()
    }

    /** Forks the latest local conflict branch into a new immutable attempt identity. */
    fun resolveConflictFork(db: SqlDatabase, ownerAccountID: String, attemptID: String,
                            forkAttemptID: String, forkedAt: Double): Boolean = db.transaction { tx ->
        if (forkAttemptID.isBlank() || forkAttemptID == attemptID) return@transaction false
        if (tx.query("SELECT 1 AS found FROM exam_attempts WHERE attempt_id = ?", listOf(forkAttemptID)).isNotEmpty())
            return@transaction false
        if (tx.query("SELECT 1 AS found FROM exam_sync_conflicts WHERE attempt_id = ? " +
                "AND owner_account_id = ? AND resolution IS NULL", listOf(attemptID, ownerAccountID)).isEmpty())
            return@transaction false
        val source = tx.query("SELECT a.*, i.content_id FROM exam_attempts a JOIN exam_snapshot_ids i " +
            "ON i.exam_id = a.exam_id WHERE a.attempt_id = ? AND a.owner_account_id = ?",
            listOf(attemptID, ownerAccountID)).singleOrNull() ?: return@transaction false
        val snapshot = tx.query("SELECT * FROM exam_snapshots WHERE content_id = ?",
            listOf(source.text("content_id"))).singleOrNull() ?: return@transaction false
        val state = source.text("state")
        if (state !in setOf("in_progress", "completed")) return@transaction false
        val position = source.long("position").toInt()
        val completedAt = source.realOrNull("completed_at")
        val resultRow = tx.query("SELECT result_json FROM exam_results WHERE attempt_id = ?",
            listOf(attemptID)).singleOrNull()
        val result = resultRow?.text("result_json")?.parseJSONObject()
        if (state == "completed" && (completedAt == null || result == null)) return@transaction false
        if (state == "in_progress" && result != null) return@transaction false
        val preparedAnswers = tx.query(
            "SELECT a.question_id, a.response_json FROM exam_answers a WHERE a.attempt_id = ? AND a.revision = " +
                "(SELECT MAX(a2.revision) FROM exam_answers a2 WHERE a2.attempt_id = a.attempt_id " +
                "AND a2.question_id = a.question_id) ORDER BY a.question_id", listOf(attemptID)).map { answer ->
            val responseJSON = answer.text("response_json")
            responseJSON.parseJSONObject()
            answer.text("question_id") to responseJSON
        }
        val effectiveAt = maxOf(forkedAt, source.real("started_at"), source.real("updated_at"))
        val contentID = source.text("content_id")
        val content = Json.parseToJsonElement(snapshot.text("content_json")).jsonObject
        val snapshotPayload = buildJsonObject {
            content.forEach { (key, value) -> put(key, value) }
            put("examId", source.text("exam_id")); put("contentId", contentID)
            put("contentHash", snapshot.text("content_hash")); put("schemaVersion", snapshot.long("schema_version"))
            put("contentRevision", snapshot.long("content_revision")); put("gradingVersion", snapshot.long("grading_version"))
            put("source", snapshot.text("source")); put("languageID", snapshot.text("language_id"))
            put("level", snapshot.text("level")); put("weekId", snapshot.long("week_id"))
        }
        val initialAttempt = buildJsonObject {
            put("attemptId", forkAttemptID); put("examId", source.text("exam_id")); put("state", state)
            put("position", position); put("revision", 1)
            put("startedAt", isoTimestamp(source.real("started_at"))); put("updatedAt", isoTimestamp(effectiveAt))
            completedAt?.let { put("completedAt", isoTimestamp(it)) }
            put("answers", JsonArray(preparedAnswers.map { (questionID, responseJSON) ->
                buildJsonObject {
                    put("questionId", questionID); put("revision", 1)
                    put("response", Json.parseToJsonElement(responseJSON))
                }
            }))
            result?.let { put("result", it) }
        }
        val inserted = tx.insert("exam_attempts", mapOf("attempt_id" to forkAttemptID,
            "exam_id" to source.text("exam_id"), "owner_account_id" to ownerAccountID, "state" to state,
            "sync_state" to "pending", "position" to position, "revision" to 1,
            "started_at" to source.real("started_at"), "updated_at" to effectiveAt, "completed_at" to completedAt))
        if (inserted == -1L) return@transaction false
        preparedAnswers.forEach { (questionID, responseJSON) ->
            check(tx.insert("exam_answers", mapOf("attempt_id" to forkAttemptID, "question_id" to questionID,
                "revision" to 1, "response_json" to responseJSON, "saved_at" to effectiveAt)) != -1L) {
                "Could not persist forked exam answer"
            }
        }
        result?.let { value ->
            check(tx.insert("exam_results", mapOf("attempt_id" to forkAttemptID,
                "result_json" to value.toString(), "recorded_at" to (completedAt ?: effectiveAt))) != -1L) {
                "Could not persist forked exam result"
            }
        }
        enqueue(tx, forkAttemptID, 1, "created", buildJsonObject {
            put("attemptId", forkAttemptID); put("examId", source.text("exam_id")); put("snapshot", snapshotPayload)
            put("forkedLocalState", true); put("initialAttempt", initialAttempt)
        }.toString(), effectiveAt)
        tx.update("UPDATE exam_sync_outbox SET delivery_state = 'superseded' WHERE attempt_id = ? " +
            "AND delivery_state IN ('pending','in_flight')", listOf(attemptID))
        tx.update("UPDATE exam_sync_conflicts SET resolution = 'fork', resolved_at = ? WHERE attempt_id = ? " +
            "AND owner_account_id = ? AND resolution IS NULL", listOf(effectiveAt, attemptID, ownerAccountID))
        check(tx.query("SELECT 1 AS found FROM exam_sync_conflicts WHERE attempt_id = ? AND owner_account_id = ? " +
            "AND resolution = 'fork'", listOf(attemptID, ownerAccountID)).isNotEmpty()) {
            "Could not mark source exam attempt as forked"
        }
        true
    }

    /**
     * Insert-only import of one server-confirmed attempt with its frozen
     * snapshot (cross-device/reinstall restore). An attempt that already exists
     * locally is never overwritten and an anonymous local identity is left
     * untouched; returns false when the attempt was skipped. No upload events
     * are created: the server already confirmed this state.
     * The snapshot JSON must carry its content-addressed identity
     * (contentId = "sha256:" + 64-hex contentHash, examId present) and must
     * rehydrate through [com.jkdc.app.network.LevelTestSnapshotAdapter.restore].
     */
    fun restorePage(db: SqlDatabase, ownerAccountID: String, snapshotJSON: String, attemptJSON: String): Boolean {
        require(ownerAccountID.isNotBlank()) { "restore requires an owning account" }
        val content = Json.parseToJsonElement(snapshotJSON).jsonObject
        val attempt = Json.parseToJsonElement(attemptJSON).jsonObject
        val examID = content.getValue("examId").jsonPrimitive.content
        val contentID = content.getValue("contentId").jsonPrimitive.content
        val contentHash = content.getValue("contentHash").jsonPrimitive.content
        require(examID.isNotBlank() && Regex("^sha256:[a-f0-9]{64}$").matches(contentID) &&
            contentHash == contentID.removePrefix("sha256:")) { "restore snapshot identity mismatch" }
        require(content.getValue("schemaVersion").jsonPrimitive.content == "1") { "unsupported snapshot schema" }
        val attemptID = attempt.getValue("attemptId").jsonPrimitive.content
        val state = attempt.getValue("state").jsonPrimitive.content
        require(state in setOf("in_progress", "completed")) { "invalid restored state" }
        val revision = attempt.getValue("revision").jsonPrimitive.content.toLong()
        require(revision > 0) { "invalid restored revision" }
        val startedAt = java.time.Instant.parse(attempt.getValue("startedAt").jsonPrimitive.content)
        val updatedAt = java.time.Instant.parse(attempt.getValue("updatedAt").jsonPrimitive.content)
        require(!updatedAt.isBefore(startedAt)) { "restored timestamps out of order" }
        val completedAt = attempt["completedAt"]?.takeIf { it !is JsonNull }
            ?.let { java.time.Instant.parse(it.jsonPrimitive.content) }
        if (state == "completed") require(completedAt != null && !completedAt.isBefore(startedAt)) { "completed restore needs a valid completion time" }
        val answers = attempt.getValue("answers").jsonArray
        require(answers.size <= 100) { "restored answers out of bounds" }
        val result = attempt["result"]?.takeIf { it is JsonObject }
        if (state == "completed") require(result != null) { "completed restore needs its frozen result" }

        // The frozen content must rehydrate before anything is written.
        com.jkdc.app.network.LevelTestSnapshotAdapter.restore(snapshotJSON)

        val record = ExamSnapshotRecord(
            examID = examID, contentID = contentID, contentHash = contentHash,
            schemaVersion = 1,
            contentRevision = content.getValue("contentRevision").jsonPrimitive.content.toInt(),
            gradingVersion = content.getValue("gradingVersion").jsonPrimitive.content.toInt(),
            source = content.getValue("source").jsonPrimitive.content,
            languageID = content.getValue("languageID").jsonPrimitive.content,
            level = content.getValue("level").jsonPrimitive.content,
            weekID = content.getValue("weekId").jsonPrimitive.content.toLong(),
            contentJSON = snapshotJSON, createdAt = System.currentTimeMillis() / 1000.0,
        )
        var imported = false
        db.transaction { tx ->
            tx.insert("exam_snapshots", mapOf(
                "content_id" to record.contentID, "content_hash" to record.contentHash,
                "schema_version" to record.schemaVersion, "content_revision" to record.contentRevision,
                "grading_version" to record.gradingVersion, "source" to record.source,
                "language_id" to record.languageID, "level" to record.level,
                "week_id" to record.weekID, "content_json" to record.contentJSON,
                "created_at" to record.createdAt,
            ), ConflictStrategy.IGNORE)
            val stored = tx.query("SELECT * FROM exam_snapshots WHERE content_hash = ?",
                listOf(record.contentHash)).singleOrNull()
                ?: error("Restored snapshot insert was not durable")
            check(stored.text("content_json") == snapshotJSON &&
                stored.text("content_id") == record.contentID) {
                "Content hash already belongs to a different immutable exam snapshot"
            }
            tx.insert("exam_snapshot_ids", mapOf(
                "exam_id" to examID, "content_id" to stored.text("content_id"),
                "created_at" to record.createdAt,
            ), ConflictStrategy.IGNORE)
            val inserted = tx.insert("exam_attempts", mapOf(
                "attempt_id" to attemptID, "exam_id" to examID,
                "owner_account_id" to ownerAccountID, "state" to state,
                "sync_state" to "synced", "position" to attempt.getValue("position").jsonPrimitive.content.toInt(),
                "revision" to revision,
                "started_at" to startedAt.toEpochMilli() / 1000.0,
                "updated_at" to updatedAt.toEpochMilli() / 1000.0,
                "completed_at" to completedAt?.toEpochMilli()?.toDouble()?.div(1000.0),
            ), ConflictStrategy.IGNORE)
            imported = inserted > 0
            if (imported) {
                for (answer in answers) {
                    val row = answer.jsonObject
                    tx.insert("exam_answers", mapOf(
                        "attempt_id" to attemptID, "question_id" to row.getValue("questionId").jsonPrimitive.content,
                        "revision" to row.getValue("revision").jsonPrimitive.content.toLong(),
                        "response_json" to row.getValue("response").jsonObject.toString(),
                        "saved_at" to updatedAt.toEpochMilli() / 1000.0,
                    ))
                }
                if (result != null) {
                    tx.insert("exam_results", mapOf(
                        "attempt_id" to attemptID, "result_json" to result.toString(),
                        "recorded_at" to (completedAt ?: updatedAt).toEpochMilli() / 1000.0,
                    ))
                }
            }
        }
        return imported
    }

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
            // Identity by CANONICAL CONTENT, not by serialization bytes: the
            // same exam may have been stored from a different JSON encoding
            // (server serializer vs app serializer), which still hashes equal.
            val sameCanonicalContent = storedSnapshot.text("content_json") == snapshot.contentJSON ||
                LevelTestSnapshotAdapter.canonicalHash(storedSnapshot.text("content_json")) ==
                    LevelTestSnapshotAdapter.canonicalHash(snapshot.contentJSON)
            check(sameCanonicalContent &&
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
        return db.transaction { tx -> saveAnswerInTransaction(tx, attemptID, questionID, responseJSON, position, savedAt) }
    }

    /** Completion is immutable and safely repeatable only for the same result. */
    fun complete(
        db: SqlDatabase,
        attemptID: String,
        resultJSON: String,
        completedAt: Double,
        finalAnswers: Map<String, String> = emptyMap(),
        position: Int? = null,
    ): Boolean {
        val resultContent = resultJSON.parseJSONObject()
        return db.transaction { tx ->
            val attempt = tx.query("SELECT state, revision, started_at, updated_at FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
                .singleOrNull() ?: error("Exam attempt does not exist")
            check(tx.query("SELECT 1 AS found FROM exam_sync_conflicts WHERE attempt_id = ? AND resolution = 'fork'",
                listOf(attemptID)).isEmpty()) { "Forked exam attempts are immutable" }
            if (attempt.text("state") == "completed") {
                val existing = tx.query("SELECT result_json FROM exam_results WHERE attempt_id = ?", listOf(attemptID))
                    .singleOrNull() ?: error("Completed exam has no result")
                check(existing.text("result_json") == resultJSON) { "Completed exam result cannot be changed" }
                return@transaction false
            }
            val answerTime = maxOf(completedAt, attempt.real("started_at"), attempt.real("updated_at"))
            finalAnswers.forEach { (questionID, response) ->
                saveAnswerInTransaction(tx, attemptID, questionID,
                    buildJsonObject { put("value", JsonPrimitive(response)) }.toString(),
                    position ?: tx.query("SELECT position FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
                        .single().int("position"), answerTime)
            }
            val current = tx.query("SELECT revision, started_at, updated_at FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID)).single()
            val effectiveAt = maxOf(completedAt, current.real("started_at"), current.real("updated_at"))
            val revision = current.long("revision") + 1
            tx.insert("exam_results", mapOf("attempt_id" to attemptID,
                "result_json" to resultJSON, "recorded_at" to effectiveAt))
            tx.update("UPDATE exam_attempts SET state = 'completed', revision = ?, updated_at = ?, completed_at = ?, " +
                "sync_state = CASE WHEN owner_account_id IS NULL THEN 'local_only' " +
                "WHEN EXISTS (SELECT 1 FROM exam_sync_conflicts c WHERE c.attempt_id = exam_attempts.attempt_id " +
                "AND c.resolution IS NULL) THEN 'conflict' ELSE 'pending' END " +
                "WHERE attempt_id = ? AND state = 'in_progress' AND revision = ?",
                listOf(revision, effectiveAt, effectiveAt, attemptID, revision - 1))
            check(tx.query("SELECT state, revision FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
                .single().let { it.text("state") == "completed" && it.long("revision") == revision }) {
                "Exam attempt changed concurrently"
            }
            enqueue(tx, attemptID, revision, "completed", buildJsonObject {
                put("attemptId", JsonPrimitive(attemptID))
                put("result", resultContent)
            }.toString(), effectiveAt)
            true
        }
    }

    private fun saveAnswerInTransaction(
        tx: SqlDatabase,
        attemptID: String,
        questionID: String,
        responseJSON: String,
        position: Int,
        savedAt: Double,
    ): Long {
        require(questionID.isNotBlank() && position >= 0)
        val response = responseJSON.parseJSONObject()
        val attempt = tx.query("SELECT state, revision, started_at, updated_at FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
            .singleOrNull() ?: error("Exam attempt does not exist")
        check(tx.query("SELECT 1 AS found FROM exam_sync_conflicts WHERE attempt_id = ? AND resolution = 'fork'",
            listOf(attemptID)).isEmpty()) { "Forked exam attempts are immutable" }
        check(attempt.text("state") == "in_progress") { "Completed exam attempts are immutable" }
        val effectiveAt = maxOf(savedAt, attempt.real("started_at"), attempt.real("updated_at"))
        val revision = attempt.long("revision") + 1
        tx.insert("exam_answers", mapOf("attempt_id" to attemptID, "question_id" to questionID,
            "revision" to revision, "response_json" to responseJSON, "saved_at" to effectiveAt))
        tx.update("UPDATE exam_attempts SET position = ?, revision = ?, updated_at = ?, " +
            "sync_state = CASE WHEN owner_account_id IS NULL THEN 'local_only' " +
            "WHEN EXISTS (SELECT 1 FROM exam_sync_conflicts c WHERE c.attempt_id = exam_attempts.attempt_id " +
            "AND c.resolution IS NULL) THEN 'conflict' ELSE 'pending' END " +
            "WHERE attempt_id = ? AND state = 'in_progress' AND revision = ?",
            listOf(position, revision, effectiveAt, attemptID, revision - 1))
        check(tx.query("SELECT revision FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
            .single().long("revision") == revision) { "Exam attempt changed concurrently" }
        val payload = buildJsonObject {
            put("attemptId", JsonPrimitive(attemptID))
            put("questionId", JsonPrimitive(questionID))
            put("response", response)
            put("position", JsonPrimitive(position))
        }.toString()
        enqueue(tx, attemptID, revision, "progress", payload, effectiveAt)
        return revision
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

    private fun parseISOSeconds(value: String): Double? = runCatching {
        java.time.Instant.parse(value).toEpochMilli() / 1000.0
    }.getOrNull()

    private fun isoTimestamp(seconds: Double): String = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
        .format(java.time.Instant.ofEpochMilli((seconds * 1000).toLong()))
}
