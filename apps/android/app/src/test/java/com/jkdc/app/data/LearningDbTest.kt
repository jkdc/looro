package com.jkdc.app.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.jkdc.app.core.AccountSession
import com.jkdc.app.network.ExamAttemptOutboxSync
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The learning schema must open verbatim through the shared statement list. */
class LearningDbTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)

    @After fun closeConnection() { connection.close() }

    @Test fun schemaOpensAtVersionFiveWithExactlyThePinnedTables() {
        assertEquals(5L, db.query("PRAGMA user_version").single().values.single())
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .map { it["name"] as String }
        // fragments_fts is the pinned v2 virtual table; fragments_fts_* are its
        // FTS4 shadow tables (SQLite internals, like sqlite_sequence).
        assertEquals(listOf("assessment_tickets", "completions", "exam_answers", "exam_attempts",
            "exam_results", "exam_snapshot_ids", "exam_snapshots", "exam_sync_conflicts", "exam_sync_outbox",
            "expressions", "fragments",
            "fragments_fts", "fragments_fts_docsize", "fragments_fts_segdir",
            "fragments_fts_segments", "fragments_fts_stat",
            "lesson_state", "meta", "preferences", "sessions", "skill_errors", "usage_outbox"), tables)
        assertFalse("words must never be stored; hidden words live in preferences", tables.contains("words"))
        val virtual = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'fragments_fts'")
            .single().values.single() as String
        // FTS4 — not FTS5 — because Android's framework SQLite enables the
        // FTS3/FTS4 module only; sqlite-jdbc happens to bundle FTS5 too, so
        // this assertion is what keeps a future edit from regressing to it.
        assertTrue(virtual.contains("USING fts4"))
        assertTrue(virtual.contains("content='fragments'"))
        assertTrue(virtual.contains("tokenize='unicode61'"))
        assertFalse("FTS5 does not exist in the Android framework build",
            virtual.contains("fts5"))
        assertEquals(listOf("exam_snapshot_ids_no_update", "exam_snapshots_no_update",
            "fragments_fts_after_insert", "fragments_fts_after_update",
            "fragments_fts_before_delete", "fragments_fts_before_update"),
            db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
                .map { it["name"] as String })
    }

    @Test fun fts4IndexUsableThroughTheSchemaStatementsOnTheJvmBackend() {
        // The JVM backend (xerial sqlite-jdbc) bundles the same FTS3/FTS4
        // module the Android framework enables, so this proves the statements
        // AND the module are usable end to end: insert → indexed by the
        // trigger, edit → reindexed, cascade delete → unindexed.
        db.insert("sessions", mapOf("id" to "s1", "language_id" to "es", "started_at" to 0.0,
            "title" to "Hola"))
        db.insert("fragments", mapOf("id" to "f1", "session_id" to "s1", "sort_index" to 0,
            "speaker" to "user", "text" to "Humo de prueba", "revision" to 0,
            "start_ms" to 0L, "end_ms" to 1L, "received_at" to 1.0))
        assertEquals(1L, db.query(
            "SELECT COUNT(*) AS n FROM fragments_fts WHERE fragments_fts MATCH 'humo'")
            .single()["n"])
        db.update("UPDATE fragments SET text = 'Cambio limpio' WHERE id = 'f1'")
        assertEquals(0L, db.query(
            "SELECT COUNT(*) AS n FROM fragments_fts WHERE fragments_fts MATCH 'humo'")
            .single()["n"])
        assertEquals(1L, db.query(
            "SELECT COUNT(*) AS n FROM fragments_fts WHERE fragments_fts MATCH 'limpio'")
            .single()["n"])
        db.exec("DELETE FROM sessions")
        assertEquals(0L, db.query(
            "SELECT COUNT(*) AS n FROM fragments_fts WHERE fragments_fts MATCH 'limpio'")
            .single()["n"])
        // DEVICE CAVEAT: the JVM backend cannot prove what AOSP compiled into
        // the framework SQLite. The device-side probe (real emulator, real
        // framework SQLite opening this schema) is owned by the phase-G
        // task-2 e2e evidence run.
    }

    @Test fun upgradeFromV1KeepsFragmentsSearchableAndConvergesOnTheFreshInventory() {
        // a separate in-memory database: setUp's already holds a fresh v2
        val v1Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            val v1 = LearningDb.openJvmV1(v1Connection)
            v1.insert("sessions", mapOf("id" to "s1", "language_id" to "es", "started_at" to 1.0,
                "title" to "Historia"))
            v1.insert("fragments", mapOf("id" to "f1", "session_id" to "s1", "sort_index" to 0,
                "speaker" to "user", "text" to "palabra heredada", "revision" to 0,
                "start_ms" to 0L, "end_ms" to 1L, "received_at" to 1.0))
            assertThrows(SQLException::class.java) {
                v1.query("SELECT rowid FROM fragments_fts WHERE fragments_fts MATCH 'heredada'")
            }
            // what the Helper's onUpgrade does, verbatim: one transaction with
            // the delta, then the recorded version
            v1.transaction { LearningDb.upgrade(it, 1) }
            v1.exec("PRAGMA user_version = ${LearningDb.VERSION}")
            assertEquals(5L, v1.query("PRAGMA user_version").single().values.single())
            // the 'rebuild' indexed the fragment v1 already had, and a NEW write
            // (the insert trigger) is searchable too
            assertEquals(listOf("s1"), SearchDao.search(v1, "heredada", "es").map { it.session.id })
            v1.insert("fragments", mapOf("id" to "f2", "session_id" to "s1", "sort_index" to 1,
                "speaker" to "assistant", "text" to "respuesta nueva", "revision" to 0,
                "start_ms" to 1L, "end_ms" to 2L, "received_at" to 2.0))
            assertEquals(listOf("s1"), SearchDao.search(v1, "respuesta", "es").map { it.session.id })
            // upgrade and fresh install converge: same objects in sqlite_master,
            // same stored statements
            fun inventory(target: SqlDatabase) = target.query(
                "SELECT type, name, COALESCE(sql, '') AS sql FROM sqlite_master " +
                    "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
                .map { Triple(it.text("type"), it.text("name"), it.text("sql")) }
            assertEquals(inventory(db), inventory(v1))
        } finally {
            v1Connection.close()
        }
    }

    @Test fun upgradeRejectsVersionsItCannotReach() {
        assertThrows(IllegalArgumentException::class.java) { LearningDb.upgrade(db, 0) }
        assertThrows(IllegalArgumentException::class.java) { LearningDb.upgrade(db, LearningDb.VERSION) }
        assertThrows(IllegalArgumentException::class.java) { LearningDb.upgrade(db, 99) }
    }

    @Test fun foreignKeysAreEnforcedOnTheConnection() {
        assertEquals(1L, db.query("PRAGMA foreign_keys").single().values.single())
    }

    @Test fun startingAndSavingAnExamKeepsItsSnapshotAnswersPositionAndOutboxTogether() {
        val started = ExamHistoryDao.start(db, sampleSnapshot("exam-1", "content-1"),
            ExamAttemptRecord("attempt-1", "exam-1", startedAt = 10.0))
        assertEquals("{\"question\":\"frozen\"}", started.snapshot.text("content_json"))
        assertEquals("in_progress", started.attempt.text("state"))
        assertEquals("created", db.query("SELECT event_type FROM exam_sync_outbox").single().text("event_type"))
        assertEquals(2L, ExamHistoryDao.saveAnswer(db, "attempt-1", "q1", "{\"answer\":\"a\"}", 1, 11.0))
        val resumed = ExamHistoryDao.load(db, "attempt-1")!!
        assertEquals(1L, resumed.attempt.long("position"))
        assertEquals("{\"answer\":\"a\"}", resumed.answers.single().text("response_json"))
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox").single().long("n"))
    }

    @Test fun completionIsAtomicAndIdempotentButCannotReplaceTheOriginalResult() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-2", "content-2"),
            ExamAttemptRecord("attempt-2", "exam-2", startedAt = 20.0))
        val result = "{\"score\":80,\"verdicts\":[true]}"
        assertTrue(ExamHistoryDao.complete(db, "attempt-2", result, 30.0))
        assertFalse(ExamHistoryDao.complete(db, "attempt-2", result, 31.0))
        assertThrows(IllegalStateException::class.java) {
            ExamHistoryDao.complete(db, "attempt-2", "{\"score\":90}", 32.0)
        }
        val history = ExamHistoryDao.load(db, "attempt-2")!!
        assertEquals("completed", history.attempt.text("state"))
        assertEquals(result, history.result!!.text("result_json"))
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox").single().long("n"))
    }

    @Test fun failedProgressOutboxRollsBackTheAnswerAndPosition() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-progress", "content-progress"),
            ExamAttemptRecord("attempt-progress", "exam-progress", startedAt = 21.0))
        db.exec("CREATE TRIGGER reject_progress BEFORE INSERT ON exam_sync_outbox " +
            "WHEN new.event_type = 'progress' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertThrows(SQLException::class.java) {
            ExamHistoryDao.saveAnswer(db, "attempt-progress", "q1", "{\"answer\":\"lost\"}", 9, 22.0)
        }
        val history = ExamHistoryDao.load(db, "attempt-progress")!!
        assertEquals(0L, history.attempt.long("position"))
        assertEquals(1L, history.attempt.long("revision"))
        assertTrue(history.answers.isEmpty())
        assertEquals(1L, count("exam_sync_outbox"))
    }

    @Test fun failedCompletionOutboxRollsBackTheResultAndState() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-complete", "content-complete"),
            ExamAttemptRecord("attempt-complete", "exam-complete", startedAt = 23.0))
        db.exec("CREATE TRIGGER reject_completion BEFORE INSERT ON exam_sync_outbox " +
            "WHEN new.event_type = 'completed' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertThrows(SQLException::class.java) {
            ExamHistoryDao.complete(db, "attempt-complete", "{\"score\":80}", 24.0)
        }
        val history = ExamHistoryDao.load(db, "attempt-complete")!!
        assertEquals("in_progress", history.attempt.text("state"))
        assertEquals(null, history.result)
        assertEquals(1L, history.attempt.long("revision"))
    }

    @Test fun localHistoryReadsAreScopedToTheActiveAccount() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-owner-a", "content-owner-a"),
            ExamAttemptRecord("attempt-owner-a", "exam-owner-a", "account-a", 25.0))
        assertEquals("account-a", ExamHistoryDao.load(db, "attempt-owner-a", "account-a")!!
            .attempt.text("owner_account_id"))
        assertEquals(null, ExamHistoryDao.load(db, "attempt-owner-a", "account-b"))
        assertEquals(null, ExamHistoryDao.load(db, "attempt-owner-a"))
    }

    @Test fun outboxAcknowledgementIsRevisionExactAndPreservesNewerEdits() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-sync", "content-sync"),
            ExamAttemptRecord("attempt-sync", "exam-sync", "account-sync", 25.0))
        val created = db.query("SELECT event_id FROM exam_sync_outbox WHERE revision = 1").single().text("event_id")
        assertTrue(ExamHistoryDao.acknowledgeOutbox(db, "account-sync", "attempt-sync", created, 1, 26.0))
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").single().text("sync_state"))

        assertEquals(2L, ExamHistoryDao.saveAnswer(db, "attempt-sync", "q1", "{\"answer\":\"first\"}", 1, 27.0))
        assertEquals("pending", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").single().text("sync_state"))
        assertEquals(3L, ExamHistoryDao.saveAnswer(db, "attempt-sync", "q1", "{\"answer\":\"newer\"}", 2, 28.0))
        val revisionTwo = db.query("SELECT event_id FROM exam_sync_outbox WHERE revision = 2").single().text("event_id")
        assertTrue(ExamHistoryDao.acknowledgeOutbox(db, "account-sync", "attempt-sync", revisionTwo, 2, 29.0))
        assertEquals("pending", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").single().text("sync_state"))
        assertEquals("pending", db.query("SELECT delivery_state FROM exam_sync_outbox WHERE revision = 3").single().text("delivery_state"))
        assertTrue(ExamHistoryDao.acknowledgeOutbox(db, "account-sync", "attempt-sync", revisionTwo, 2, 30.0))
        assertFalse(ExamHistoryDao.acknowledgeOutbox(db, "other-account", "attempt-sync", revisionTwo, 2, 30.0))
        val revisionThree = db.query("SELECT event_id FROM exam_sync_outbox WHERE revision = 3").single().text("event_id")
        assertTrue(ExamHistoryDao.acknowledgeOutbox(db, "account-sync", "attempt-sync", revisionThree, 3, 31.0))
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").single().text("sync_state"))
        assertTrue(ExamHistoryDao.markConflict(db, "account-sync", "attempt-sync"))
        assertEquals("conflict", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").single().text("sync_state"))
    }

    @Test fun examSyncDaoDoesNotDependOnAndroidUpdateRowCounts() {
        val owner = "account-no-row-count"
        ExamHistoryDao.start(db, sampleSnapshot("exam-no-count", "content-no-count"),
            ExamAttemptRecord("attempt-no-count", "exam-no-count", owner, 40.0))
        val androidContract = NoUpdateCountDatabase(db)
        val created = db.query("SELECT event_id FROM exam_sync_outbox WHERE attempt_id = ?", listOf("attempt-no-count"))
            .single().text("event_id")
        assertTrue(ExamHistoryDao.acknowledgeOutbox(androidContract, owner, "attempt-no-count", created, 1, 41.0))
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = ?", listOf("attempt-no-count"))
            .single().text("sync_state"))

        val server = """{"attemptId":"attempt-no-count","examId":"exam-no-count","revision":4,"state":"in_progress","position":0,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(androidContract, owner, "attempt-no-count", server, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictTakeover(androidContract, owner, "attempt-no-count", 43.0))
        assertEquals("takeover", db.query("SELECT event_type FROM exam_sync_outbox WHERE attempt_id = ? AND delivery_state = 'pending'",
            listOf("attempt-no-count")).single().text("event_type"))

        assertTrue(ExamHistoryDao.persistConflict(androidContract, owner, "attempt-no-count",
            server.replace("\"revision\":4", "\"revision\":6"), 44.0))
        assertTrue(ExamHistoryDao.resolveConflictFork(androidContract, owner, "attempt-no-count", "attempt-no-count-fork", 45.0))
    }

    @Test fun pendingOutboxOrdersEachAttemptByRevisionInsteadOfWallClock() {
        val owner = "account-clock-rollback"
        ExamHistoryDao.start(db, sampleSnapshot("exam-clock", "content-clock"),
            ExamAttemptRecord("attempt-clock", "exam-clock", owner, 100.0))
        ExamHistoryDao.saveAnswer(db, "attempt-clock", "q1", "{\"answer\":1}", 1, 101.0)
        ExamHistoryDao.saveAnswer(db, "attempt-clock", "q1", "{\"answer\":2}", 1, 99.0)
        assertEquals(listOf(1L, 2L, 3L), ExamHistoryDao.pendingOutbox(db, owner)
            .map { it.long("revision") })
    }

    @Test fun aConflictPersistsBothLocalAndServerBranchesBeforeResolution() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-branch", "content-branch"),
            ExamAttemptRecord("attempt-branch", "exam-branch", "account-branch", 40.0))
        ExamHistoryDao.saveAnswer(db, "attempt-branch", "q1", "{\"answer\":\"local\"}", 2, 41.0)
        val server = """{"attemptId":"attempt-branch","revision":2,"state":"in_progress","position":1,"answers":[{"questionId":"q1","response":{"answer":"server"}}]}"""
        assertTrue(ExamHistoryDao.persistConflict(db, "account-branch", "attempt-branch", server, 42.0))
        val conflict = ExamHistoryDao.conflict(db, "account-branch", "attempt-branch")!!
        assertEquals(2L, conflict.long("server_revision"))
        assertEquals(server, conflict.text("server_attempt_json"))
        assertTrue(conflict.text("local_attempt_json").contains("local"))
        assertEquals("conflict", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-branch'").single().text("sync_state"))
        assertFalse(ExamHistoryDao.persistConflict(db, "other-account", "attempt-branch", server, 43.0))
    }

    @Test fun explicitTakeoverAdoptsServerBranchAndSupersedesLocalEvents() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-takeover", "content-takeover"),
            ExamAttemptRecord("attempt-takeover", "exam-takeover", "account-takeover", 40.0))
        ExamHistoryDao.saveAnswer(db, "attempt-takeover", "q1", "{\"answer\":\"local\"}", 2, 41.0)
        val server = """{"attemptId":"attempt-takeover","examId":"exam-takeover","revision":4,"state":"in_progress","position":1,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[{"questionId":"q1","revision":3,"response":{"answer":"server"}}]}"""
        assertTrue(ExamHistoryDao.persistConflict(db, "account-takeover", "attempt-takeover", server, 43.0))
        assertTrue(ExamHistoryDao.resolveConflictTakeover(db, "account-takeover", "attempt-takeover", 44.0))
        val attempt = db.query("SELECT revision, sync_state FROM exam_attempts WHERE attempt_id = 'attempt-takeover'").single()
        assertEquals(5L, attempt.long("revision")); assertEquals("pending", attempt.text("sync_state"))
        assertEquals("server", db.query("SELECT response_json FROM exam_answers WHERE attempt_id = 'attempt-takeover'")
            .single().text("response_json").let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["answer"]!!.jsonPrimitive.content })
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox WHERE attempt_id = 'attempt-takeover' AND delivery_state = 'superseded'").single().long("n"))
        val takeover = db.query("SELECT event_type, revision FROM exam_sync_outbox WHERE attempt_id = 'attempt-takeover' AND delivery_state = 'pending'").single()
        assertEquals("takeover", takeover.text("event_type")); assertEquals(5L, takeover.long("revision"))
        assertEquals("takeover", ExamHistoryDao.conflict(db, "account-takeover", "attempt-takeover")!!.text("resolution"))
    }

    @Test fun takeoverCanResolveASecondConflictAfterTheServerAdvancesAgain() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-repeat", "content-repeat"),
            ExamAttemptRecord("attempt-repeat", "exam-repeat", "account-repeat", 40.0))
        val first = """{"attemptId":"attempt-repeat","examId":"exam-repeat","revision":2,"state":"in_progress","position":0,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, "account-repeat", "attempt-repeat", first, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictTakeover(db, "account-repeat", "attempt-repeat", 43.0))
        val second = first.replace("\"revision\":2", "\"revision\":4").replace("00:00:42", "00:00:44")
        assertTrue(ExamHistoryDao.persistConflict(db, "account-repeat", "attempt-repeat", second, 44.0))
        assertEquals(null, ExamHistoryDao.conflict(db, "account-repeat", "attempt-repeat")!!["resolution"])
        assertTrue(ExamHistoryDao.resolveConflictTakeover(db, "account-repeat", "attempt-repeat", 45.0))
    }

    @Test fun takingOverNullableServerResultDoesNotBlockLaterCompletion() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-null-result", "content-null-result"),
            ExamAttemptRecord("attempt-null-result", "exam-null-result", "account-null-result", 40.0))
        val server = """{"attemptId":"attempt-null-result","examId":"exam-null-result","revision":2,"state":"in_progress","position":0,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, "account-null-result", "attempt-null-result", server, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictTakeover(db, "account-null-result", "attempt-null-result", 43.0))
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM exam_results WHERE attempt_id = 'attempt-null-result'").single().long("n"))
        assertTrue(ExamHistoryDao.complete(db, "attempt-null-result", "{\"score\":80}", 44.0))
    }

    @Test fun explicitForkCreatesNewPendingAttemptWithLocalAnswersAndFrozenSnapshot() = runBlocking {
        val forkOwner = "11111111-1111-4111-8111-111111111111"
        ExamHistoryDao.start(db, sampleSnapshot("exam-fork", "content-fork"),
            ExamAttemptRecord("attempt-fork-source", "exam-fork", forkOwner, 40.0))
        ExamHistoryDao.saveAnswer(db, "attempt-fork-source", "q1", "{\"answer\":\"local\"}", 2, 41.0)
        val server = """{"attemptId":"attempt-fork-source","examId":"exam-fork","revision":3,"state":"in_progress","position":1,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, forkOwner, "attempt-fork-source", server, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictFork(db, forkOwner, "attempt-fork-source", "attempt-fork-copy", 43.0))
        val fork = db.query("SELECT state, sync_state, exam_id FROM exam_attempts WHERE attempt_id = 'attempt-fork-copy'").single()
        assertEquals("in_progress", fork.text("state")); assertEquals("pending", fork.text("sync_state")); assertEquals("exam-fork", fork.text("exam_id"))
        assertEquals("local", kotlinx.serialization.json.Json.parseToJsonElement(
            db.query("SELECT response_json FROM exam_answers WHERE attempt_id = 'attempt-fork-copy'").single().text("response_json")
        ).jsonObject["answer"]!!.jsonPrimitive.content)
        val created = db.query("SELECT event_type FROM exam_sync_outbox WHERE attempt_id = 'attempt-fork-copy'").single()
        assertEquals("created", created.text("event_type"))
        assertEquals("fork", ExamHistoryDao.conflict(db, forkOwner, "attempt-fork-source")!!.text("resolution"))
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox WHERE attempt_id = 'attempt-fork-source' " +
            "AND delivery_state = 'superseded'").single().long("n"))
        assertEquals(2L, ExamHistoryDao.saveAnswer(db, "attempt-fork-copy", "q1", "{\"answer\":\"later\"}", 1, 44.0))
        var uploadedAttempt: kotlinx.serialization.json.JsonObject? = null
        val sentEvents = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val sync = ExamAttemptOutboxSync(
            sendCreation = { _, _, attempt ->
                uploadedAttempt = attempt
                buildJsonObject { put("accepted", buildJsonArray { add(buildJsonObject {
                    put("attemptId", "attempt-fork-copy"); put("revision", 1)
                }) }) }
            },
            sendEvent = { _, event ->
                sentEvents += event
                buildJsonObject { put("acks", buildJsonArray { add(buildJsonObject {
                    put("eventId", event.getValue("eventId")); put("attemptId", event.getValue("attemptId"))
                    put("revision", event.getValue("revision")); put("status", "accepted")
                }) }) }
            },
            acknowledge = { account, id, event, revision, acceptedRevision, at ->
                ExamHistoryDao.acknowledgeOutbox(db, account, id, event, revision, at, acceptedRevision)
            },
            conflict = { _, _, _, _ -> error("Unexpected conflict") },
        )
        sync.drain(db, AccountSession(forkOwner, "A".repeat(43), 9_999_999_999_999L)) { 44.0 }
        val uploadedAnswer = uploadedAttempt!!["answers"]!!.jsonArray.single().jsonObject
        assertEquals("q1", uploadedAnswer["questionId"]!!.jsonPrimitive.content)
        assertTrue(uploadedAnswer["response"].toString().contains("local"))
        assertEquals(1L, uploadedAttempt!!["revision"]!!.jsonPrimitive.content.toLong())
        assertEquals(2L, sentEvents.single()["revision"]!!.jsonPrimitive.content.toLong())
        assertTrue(sentEvents.single()["payload"].toString().contains("later"))
    }

    @Test fun legacyForkCreationEventWithoutFrozenAttemptStillReplays() = runBlocking {
        val owner = "11111111-1111-4111-8111-111111111111"
        ExamHistoryDao.start(db, sampleSnapshot("exam-fork-legacy", "content-fork-legacy"),
            ExamAttemptRecord("attempt-fork-source-legacy", "exam-fork-legacy", owner, 40.0))
        ExamHistoryDao.saveAnswer(db, "attempt-fork-source-legacy", "q1", "{\"answer\":\"local\"}", 2, 41.0)
        val server = """{"attemptId":"attempt-fork-source-legacy","examId":"exam-fork-legacy","revision":3,"state":"in_progress","position":1,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, owner, "attempt-fork-source-legacy", server, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictFork(db, owner, "attempt-fork-source-legacy", "attempt-fork-legacy", 43.0))
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(
            db.query("SELECT payload_json FROM exam_sync_outbox WHERE attempt_id = 'attempt-fork-legacy' AND event_type = 'created'").single().text("payload_json")
        ).jsonObject - "initialAttempt"
        assertEquals("true", payload["forkedLocalState"]?.jsonPrimitive?.content)
        assertEquals(1, db.query("SELECT question_id FROM exam_answers WHERE attempt_id = 'attempt-fork-legacy'").size)
        db.transaction { tx ->
            tx.update("UPDATE exam_sync_outbox SET payload_json = ? WHERE attempt_id = ? AND event_type = 'created'",
                listOf(kotlinx.serialization.json.JsonObject(payload).toString(), "attempt-fork-legacy"))
        }
        assertEquals(2L, ExamHistoryDao.saveAnswer(db, "attempt-fork-legacy", "q1", "{\"answer\":\"later\"}", 1, 43.5))
        var uploaded: kotlinx.serialization.json.JsonObject? = null
        val sync = ExamAttemptOutboxSync(
            sendCreation = { _, _, attempt ->
                uploaded = attempt
                buildJsonObject { put("accepted", buildJsonArray { add(buildJsonObject {
                    put("attemptId", "attempt-fork-legacy"); put("revision", attempt.getValue("revision"))
                }) }) }
            },
            sendEvent = { _, _ -> error("The accepted creation snapshot must cover its earlier edits") },
            acknowledge = { account, id, event, revision, acceptedRevision, at ->
                ExamHistoryDao.acknowledgeOutbox(db, account, id, event, revision, at, acceptedRevision)
            },
            conflict = { _, _, _, _ -> error("Unexpected conflict") },
        )

        sync.drain(db, AccountSession(owner, "A".repeat(43), 9_999_999_999_999L)) { 44.0 }

        assertEquals(2L, uploaded!!.getValue("revision").jsonPrimitive.content.toLong())
        assertTrue(uploaded!!.getValue("answers").jsonArray.isNotEmpty())
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-fork-legacy'").single().text("sync_state"))
    }

    @Test fun legacyForkCreationRetryKeepsSnapshotAfterLostAckAndOfflineEdit() = runBlocking {
        val owner = "11111111-1111-4111-8111-111111111111"
        ExamHistoryDao.start(db, sampleSnapshot("exam-fork-retry", "content-fork-retry"),
            ExamAttemptRecord("attempt-fork-source-retry", "exam-fork-retry", owner, 40.0))
        ExamHistoryDao.saveAnswer(db, "attempt-fork-source-retry", "q1", "{\"answer\":\"original\"}", 2, 41.0)
        val server = """{"attemptId":"attempt-fork-source-retry","examId":"exam-fork-retry","revision":3,"state":"in_progress","position":1,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, owner, "attempt-fork-source-retry", server, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictFork(db, owner, "attempt-fork-source-retry", "attempt-fork-retry", 43.0))
        db.transaction { tx ->
            val row = tx.query("SELECT payload_json FROM exam_sync_outbox WHERE attempt_id = ? AND event_type = 'created'", listOf("attempt-fork-retry")).single()
            val payload = kotlinx.serialization.json.Json.parseToJsonElement(row.text("payload_json")).jsonObject - "initialAttempt"
            tx.update("UPDATE exam_sync_outbox SET payload_json = ? WHERE attempt_id = ? AND event_type = 'created'",
                listOf(kotlinx.serialization.json.JsonObject(payload).toString(), "attempt-fork-retry"))
        }
        var firstCreation: kotlinx.serialization.json.JsonObject? = null
        var creationCalls = 0
        fun sync() = ExamAttemptOutboxSync(
            sendCreation = { _, _, attempt ->
                creationCalls++
                if (firstCreation == null) {
                    firstCreation = attempt
                    error("Simulated lost creation ACK")
                }
                assertEquals(firstCreation, attempt)
                buildJsonObject { put("accepted", buildJsonArray { add(buildJsonObject {
                    put("attemptId", "attempt-fork-retry"); put("revision", attempt.getValue("revision"))
                }) }) }
            },
            sendEvent = { _, event ->
                assertEquals(2L, event.getValue("revision").jsonPrimitive.content.toLong())
                assertTrue(event.getValue("payload").toString().contains("edited-after-lost-ack"))
                buildJsonObject { put("acks", buildJsonArray { add(buildJsonObject {
                    put("eventId", event.getValue("eventId")); put("attemptId", event.getValue("attemptId"))
                    put("revision", event.getValue("revision")); put("status", "accepted")
                }) }) }
            },
            acknowledge = { account, id, event, revision, acceptedRevision, at ->
                ExamHistoryDao.acknowledgeOutbox(db, account, id, event, revision, at, acceptedRevision)
            },
            conflict = { _, _, _, _ -> error("Unexpected conflict") },
        )

        var lostAck = false
        try {
            sync().drain(db, AccountSession(owner, "A".repeat(43), 9_999_999_999_999L)) { 44.0 }
        } catch (_: IllegalStateException) {
            lostAck = true
        }
        assertTrue(lostAck)
        ExamHistoryDao.saveAnswer(db, "attempt-fork-retry", "q1", "{\"answer\":\"edited-after-lost-ack\"}", 1, 44.5)
        sync().drain(db, AccountSession(owner, "A".repeat(43), 9_999_999_999_999L)) { 45.0 }

        assertEquals(2, creationCalls)
        assertEquals(1L, firstCreation!!.getValue("revision").jsonPrimitive.content.toLong())
        assertTrue(firstCreation!!.getValue("answers").jsonArray.single().toString().contains("original"))
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-fork-retry'").single().text("sync_state"))
    }

    @Test fun conflictPausesOnlyItsAttemptAndKeepsItPausedAcrossDrains() = runBlocking {
        val owner = "11111111-1111-4111-8111-111111111111"
        val attemptA = "attempt-conflict-a"
        val attemptB = "attempt-conflict-b"
        ExamHistoryDao.start(db, sampleSnapshot("exam-conflict-a", "content-conflict-a"),
            ExamAttemptRecord(attemptA, "exam-conflict-a", owner, 100.0))
        ExamHistoryDao.start(db, sampleSnapshot("exam-conflict-b", "content-conflict-b"),
            ExamAttemptRecord(attemptB, "exam-conflict-b", owner, 100.0))
        for (attemptID in listOf(attemptA, attemptB)) {
            val created = ExamHistoryDao.pendingOutbox(db, owner).single {
                it.text("attempt_id") == attemptID && it.text("event_type") == "created"
            }
            assertTrue(ExamHistoryDao.acknowledgeOutbox(db, owner, attemptID, created.text("event_id"),
                created.long("revision"), 101.0, 1))
        }
        ExamHistoryDao.saveAnswer(db, attemptA, "q1", "{\"answer\":\"a1\"}", 1, 102.0)
        ExamHistoryDao.saveAnswer(db, attemptA, "q2", "{\"answer\":\"a2\"}", 2, 103.0)
        ExamHistoryDao.saveAnswer(db, attemptB, "q1", "{\"answer\":\"b1\"}", 1, 102.0)

        val sent = mutableListOf<String>()
        val sync = ExamAttemptOutboxSync(
            sendCreation = { _, _, _ -> error("Creation events were already acknowledged") },
            sendEvent = { _, event ->
                val id = event.getValue("attemptId").jsonPrimitive.content
                val eventID = event.getValue("eventId").jsonPrimitive.content
                sent += eventID
                if (id == attemptA && event.getValue("revision").jsonPrimitive.content.toLong() == 2L) {
                    buildJsonObject { put("conflict", buildJsonObject { put("attempt", buildJsonObject {
                        put("attemptId", attemptA); put("revision", 9)
                    }) }) }
                } else {
                    buildJsonObject { put("acks", buildJsonArray { add(buildJsonObject {
                        put("eventId", eventID); put("attemptId", id)
                        put("revision", event.getValue("revision")); put("status", "accepted")
                    }) }) }
                }
            },
            acknowledge = { account, id, event, revision, acceptedRevision, at ->
                ExamHistoryDao.acknowledgeOutbox(db, account, id, event, revision, at, acceptedRevision)
            },
            conflict = { account, id, _, _ -> ExamHistoryDao.markConflict(db, account, id) },
        )
        val session = AccountSession(owner, "A".repeat(43), 9_999_999_999_999L)

        val bEventID = ExamHistoryDao.pendingOutbox(db, owner).single {
            it.text("attempt_id") == attemptB && it.text("event_type") == "progress"
        }.text("event_id")

        sync.drain(db, session) { 104.0 }
        val aStates = db.query("SELECT revision, delivery_state FROM exam_sync_outbox WHERE attempt_id = ? " +
            "AND event_type = 'progress' ORDER BY revision", listOf(attemptA))
        assertEquals(listOf(2L, 3L), aStates.map { it.long("revision") })
        assertEquals(listOf("pending", "pending"), aStates.map { it.text("delivery_state") })
        assertTrue(sent.contains(bEventID))
        assertEquals("acknowledged", db.query("SELECT delivery_state FROM exam_sync_outbox WHERE event_id = ?",
            listOf(bEventID)).single().text("delivery_state"))
        assertTrue(ExamHistoryDao.pendingOutbox(db, owner).none { it.text("attempt_id") == attemptA })
        assertEquals("conflict", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = ?", listOf(attemptA))
            .single().text("sync_state"))

        val sentAfterConflict = sent.toList()
        sync.drain(db, session) { 105.0 }
        assertEquals(sentAfterConflict, sent)
        assertEquals(listOf("pending", "pending"), db.query(
            "SELECT delivery_state FROM exam_sync_outbox WHERE attempt_id = ? AND event_type = 'progress' ORDER BY revision",
            listOf(attemptA)).map { it.text("delivery_state") })
    }

    @Test fun outboxSyncCreatesInitialAttemptThenSendsRevisionEventsAndAcknowledgesExactRows() = runBlocking {
        val owner = "11111111-1111-4111-8111-111111111111"
        val attemptID = "22222222-2222-4222-8222-222222222222"
        ExamHistoryDao.start(db, sampleSnapshot("exam-sync-flow", "content-sync-flow"),
            ExamAttemptRecord(attemptID, "exam-sync-flow", owner, 100.125))
        ExamHistoryDao.saveAnswer(db, attemptID, "q1", "{\"answer\":\"local\"}", 1, 101.0)
        ExamHistoryDao.complete(db, attemptID, "{\"score\":80}", 102.0)
        val delivered = mutableListOf<String>()
        val sync = ExamAttemptOutboxSync(
            sendCreation = { session, snapshot, initial ->
                assertEquals(owner, session.accountID)
                assertEquals(attemptID, initial.getValue("attemptId").toString().trim('"'))
                assertEquals(1L, initial.getValue("revision").toString().toLong())
                assertEquals("content-sync-flow", snapshot.getValue("contentId").toString().trim('"'))
                delivered += "created"
                buildJsonObject { put("accepted", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject { put("attemptId", attemptID); put("revision", 1) })
                }) }
            },
            sendEvent = { _, event ->
                val type = event.getValue("type").toString().trim('"')
                delivered += type
                buildJsonObject { put("acks", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("eventId", event.getValue("eventId")); put("attemptId", event.getValue("attemptId"))
                        put("revision", event.getValue("revision")); put("status", "accepted")
                    })
                }) }
            },
            acknowledge = { account, id, event, revision, acceptedRevision, at ->
                ExamHistoryDao.acknowledgeOutbox(db, account, id, event, revision, at, acceptedRevision)
            },
            conflict = { _, _, _, _ -> error("Unexpected conflict") },
        )
        sync.drain(db, AccountSession(owner, "A".repeat(43), 9_999_999_999_999L)) { 103.0 }
        assertEquals(listOf("created", "progress", "completed"), delivered)
        assertTrue(ExamHistoryDao.pendingOutbox(db, owner).isEmpty())
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
            .single().text("sync_state"))
    }

    @Test fun failedOutboxInsertRollsBackAnExamStart() {
        db.exec("CREATE TRIGGER reject_exam_outbox BEFORE INSERT ON exam_sync_outbox BEGIN " +
            "SELECT RAISE(ABORT, 'test failure'); END")
        assertThrows(SQLException::class.java) {
            ExamHistoryDao.start(db, sampleSnapshot("exam-3", "content-3"),
                ExamAttemptRecord("attempt-3", "exam-3", startedAt = 40.0))
        }
        assertEquals(0L, (count("exam_snapshots") as Number).toLong())
        assertEquals(0L, (count("exam_snapshot_ids") as Number).toLong())
        assertEquals(0L, (count("exam_attempts") as Number).toLong())
    }

    @Test fun equalContentSnapshotsRetainDistinctExamIDs() {
        val first = sampleSnapshot("exam-a", "content-shared")
        val firstAttempt = ExamAttemptRecord("attempt-a", "exam-a", startedAt = 50.0)
        ExamHistoryDao.start(db, first, firstAttempt)
        ExamHistoryDao.start(db, first, firstAttempt)
        ExamHistoryDao.start(db, first.copy(examID = "exam-b"),
            ExamAttemptRecord("attempt-b", "exam-b", startedAt = 51.0))
        assertEquals(1L, (count("exam_snapshots") as Number).toLong())
        assertEquals(2L, (count("exam_snapshot_ids") as Number).toLong())
        assertEquals(2L, (count("exam_sync_outbox") as Number).toLong())
        assertEquals("exam-b", ExamHistoryDao.load(db, "attempt-b")!!.attempt.text("exam_id"))
    }

    @Test fun deletingASessionCascadesToItsFragments() {
        db.insert("sessions", mapOf("id" to "s1", "language_id" to "es", "started_at" to 0.0, "title" to "Hola"))
        db.insert("fragments", mapOf("id" to "f1", "session_id" to "s1", "sort_index" to 0,
            "speaker" to "user", "text" to "Hola", "revision" to 0,
            "start_ms" to 0L, "end_ms" to 100L, "received_at" to 1.0))
        assertEquals(1L, count("fragments"))
        db.exec("DELETE FROM sessions")
        assertEquals(0L, count("fragments"))
    }

    @Test fun insertResolvesConflictsWithTheRequestedStrategy() {
        db.insert("meta", mapOf("key" to "k", "value" to "first"))
        assertEquals(-1L, db.insert("meta", mapOf("key" to "k", "value" to "second"), ConflictStrategy.IGNORE))
        assertEquals("first", db.query("SELECT value FROM meta WHERE key = 'k'").single()["value"])
        assertThrows(SQLException::class.java) {
            db.insert("meta", mapOf("key" to "k", "value" to "third"), ConflictStrategy.ABORT)
        }
    }

    @Test fun updateReportsAffectedRowsOnTheJvmBackend() {
        db.insert("meta", mapOf("key" to "a", "value" to "1"))
        db.insert("meta", mapOf("key" to "b", "value" to "1"))
        db.insert("meta", mapOf("key" to "c", "value" to "2"))
        assertEquals(2L, db.update("UPDATE meta SET value = '9' WHERE value = ?", listOf("1")))
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM meta WHERE value = '9'").single()["n"])
    }

    @Test fun transactionCommitsTheWorkDoneInsideTheBlock() {
        db.transaction { it.insert("meta", mapOf("key" to "k", "value" to "kept")) }
        assertEquals("kept", db.query("SELECT value FROM meta WHERE key = 'k'").single()["value"])
    }

    @Test fun transactionRollsBackOnFailureAndRejectsNesting() {
        assertThrows(IllegalStateException::class.java) {
            db.transaction { d ->
                d.insert("meta", mapOf("key" to "k", "value" to "lost"))
                error("boom")
            }
        }
        assertEquals(0L, count("meta"))
        assertThrows(IllegalStateException::class.java) {
            db.transaction { it.transaction { } }
        }
        assertEquals(0L, count("meta"))
    }

    @Test fun nullBindArgumentsAreRejectedOnQueryAndUpdate() {
        db.insert("meta", mapOf("key" to "k", "value" to "v"))
        assertThrows(IllegalArgumentException::class.java) {
            db.query("SELECT value FROM meta WHERE key != ?", listOf<Any?>(null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            db.update("UPDATE meta SET value = 'x' WHERE key != ?", listOf<Any?>(null))
        }
        // inserts still carry null values: that is column data, not a comparison
        db.insert("completions", mapOf("kind" to "lesson", "item_id" to "i1", "at" to 1.0, "language_id" to null))
        assertEquals(1L, count("completions"))
    }

    private fun count(table: String) = db.query("SELECT COUNT(*) AS n FROM $table").single()["n"]

    private class NoUpdateCountDatabase(private val delegate: SqlDatabase) : SqlDatabase {
        override fun exec(statement: String) = delegate.exec(statement)
        override fun insert(table: String, row: Map<String, Any?>, strategy: ConflictStrategy) =
            delegate.insert(table, row, strategy)
        override fun query(sql: String, args: List<Any?>) = delegate.query(sql, args)
        override fun update(sql: String, args: List<Any?>): Long {
            delegate.update(sql, args)
            return -1L
        }
        override fun <T> transaction(block: (SqlDatabase) -> T): T =
            delegate.transaction { tx -> block(NoUpdateCountDatabase(tx)) }
        override fun close() = delegate.close()
    }

    private fun sampleSnapshot(examID: String, contentID: String) = ExamSnapshotRecord(
        examID, contentID, "hash-$contentID", 1, 1, 1, "bundled", "es", "B1", 202641,
        "{\"question\":\"frozen\"}", 1.0)
}
