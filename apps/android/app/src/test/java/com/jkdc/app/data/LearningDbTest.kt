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

/** The learning schema must open verbatim through the shared statement list. */
class LearningDbTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)

    @After fun closeConnection() { connection.close() }

    @Test fun schemaOpensAtVersionThreeWithExactlyThePinnedTables() {
        assertEquals(3L, db.query("PRAGMA user_version").single().values.single())
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .map { it["name"] as String }
        // fragments_fts is the pinned v2 virtual table; fragments_fts_* are its
        // FTS4 shadow tables (SQLite internals, like sqlite_sequence).
        assertEquals(listOf("assessment_tickets", "completions", "exam_answers", "exam_attempts",
            "exam_results", "exam_snapshot_ids", "exam_snapshots", "exam_sync_outbox",
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
            assertEquals(3L, v1.query("PRAGMA user_version").single().values.single())
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

    private fun sampleSnapshot(examID: String, contentID: String) = ExamSnapshotRecord(
        examID, contentID, "hash-$contentID", 1, 1, 1, "bundled", "es", "B1", 202641,
        "{\"question\":\"frozen\"}", 1.0)
}
