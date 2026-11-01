package com.jkdc.app.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamHistorySyncTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)

    @After fun closeConnection() { connection.close() }

    @Test fun ownerlessExamHistoryIsBackfilledOnceToTheMigrationIdentity() {
        val legacyHash = "f".repeat(64)
        ExamHistoryDao.start(db,
            ExamSnapshotRecord("exam-legacy-owner", "sha256:$legacyHash", legacyHash, 1, 1, 1,
                "bundled", "en", "B1", 202640, "{}", 10.0),
            ExamAttemptRecord("attempt-legacy-owner", "exam-legacy-owner", startedAt = 10.0))

        assertTrue(ExamHistoryDao.assignOwnerlessToIdentityOnce(db, "member:member-a"))
        assertFalse(ExamHistoryDao.assignOwnerlessToIdentityOnce(db, "member:member-b"))

        assertEquals("member-a", db.query("SELECT owner_account_id FROM exam_attempts WHERE attempt_id = ?",
            listOf("attempt-legacy-owner")).single().textOrNull("owner_account_id"))
        assertEquals("member:member-a", db.query("SELECT owner_key FROM exam_attempts WHERE attempt_id = ?",
            listOf("attempt-legacy-owner")).single().textOrNull("owner_key"))
        assertEquals(null, ExamHistoryDao.load(db, "attempt-legacy-owner", "member-a"))
        LearnerOwnershipDao.activate(db, "member:member-a")
        assertEquals("member-a", ExamHistoryDao.load(db, "attempt-legacy-owner", "member-a")!!
            .attempt.text("owner_account_id"))
        LearnerOwnershipDao.activate(db, "member:member-b")
        assertEquals(null, ExamHistoryDao.load(db, "attempt-legacy-owner", "member-b"))
        assertEquals("member-a", db.query("SELECT owner_account_id FROM exam_attempts WHERE attempt_id = ?",
            listOf("attempt-legacy-owner")).single().text("owner_account_id"))
    }

    @Test fun guestExamHistoryUsesStableGuestOwnerAndCannotBeReadOrSyncedAsMember() {
        val snapshot = ExamSnapshotRecord("exam-guest", "sha256:${"a".repeat(64)}", "a".repeat(64),
            1, 1, 1, "bundled", "en", "B1", 202640, "{}", 10.0)
        LearnerOwnershipDao.activate(db, "guest:installation-1")
        ExamHistoryDao.start(db, snapshot, ExamAttemptRecord("attempt-guest", "exam-guest", startedAt = 10.0))
        assertEquals("guest:installation-1", db.query(
            "SELECT owner_key FROM exam_attempts WHERE attempt_id = ?", listOf("attempt-guest")).single().text("owner_key"))
        assertEquals(null, ExamHistoryDao.load(db, "attempt-guest", null)?.attempt?.textOrNull("owner_account_id"))

        LearnerOwnershipDao.activate(db, "member:member-1")
        assertEquals(null, ExamHistoryDao.load(db, "attempt-guest", null))
        assertTrue(ExamHistoryDao.pendingOutbox(db, "member-1").isEmpty())
    }

    @Test fun foreignOwnerCannotMutateExamAttemptOrAdvanceItsHistory() {
        LearnerOwnershipDao.activate(db, "member:owner-a")
        val hash = "d".repeat(64)
        ExamHistoryDao.start(db,
            ExamSnapshotRecord("exam-foreign-write", "sha256:$hash", hash, 1, 1, 1,
                "bundled", "en", "B1", 202640, "{}", 10.0),
            ExamAttemptRecord("attempt-foreign-write", "exam-foreign-write", "owner-a", 10.0))
        val before = db.query("SELECT state, position, revision, updated_at, owner_key FROM exam_attempts " +
            "WHERE attempt_id = 'attempt-foreign-write'").single()

        LearnerOwnershipDao.activate(db, "member:owner-b")
        assertThrows(IllegalStateException::class.java) {
            ExamHistoryDao.saveAnswer(db, "attempt-foreign-write", "q1", "{\"answer\":\"foreign\"}", 9, 20.0)
        }
        assertThrows(IllegalStateException::class.java) {
            ExamHistoryDao.complete(db, "attempt-foreign-write", "{\"score\":100}", 21.0)
        }

        val after = db.query("SELECT state, position, revision, updated_at, owner_key FROM exam_attempts " +
            "WHERE attempt_id = 'attempt-foreign-write'").single()
        assertEquals(before.text("state"), after.text("state"))
        assertEquals(before.long("position"), after.long("position"))
        assertEquals(before.long("revision"), after.long("revision"))
        assertEquals(before.real("updated_at"), after.real("updated_at"), 0.0)
        assertEquals(before.text("owner_key"), after.text("owner_key"))
        assertTrue(db.query("SELECT * FROM exam_answers WHERE attempt_id = 'attempt-foreign-write'").isEmpty())
        assertTrue(db.query("SELECT * FROM exam_results WHERE attempt_id = 'attempt-foreign-write'").isEmpty())
        assertEquals(listOf("created"), db.query(
            "SELECT event_type FROM exam_sync_outbox WHERE attempt_id = 'attempt-foreign-write'").map { it.text("event_type") })
    }

    @Test fun latestAnswerRevisionIsSelectedOnlyInsideItsOwnerPartition() {
        LearnerOwnershipDao.activate(db, "member:owner-a")
        val hash = "c".repeat(64)
        ExamHistoryDao.start(db,
            ExamSnapshotRecord("exam-owner-revision", "sha256:$hash", hash, 1, 1, 1,
                "bundled", "en", "B1", 202640, "{}", 10.0),
            ExamAttemptRecord("attempt-owner-revision", "exam-owner-revision", "owner-a", 10.0))
        ExamHistoryDao.saveAnswer(db, "attempt-owner-revision", "q1", "{\"answer\":\"owner-a\"}", 1, 11.0)
        db.insert("exam_answers", mapOf("attempt_id" to "attempt-owner-revision", "question_id" to "q1",
            "revision" to 99L, "response_json" to "{\"answer\":\"owner-b\"}", "saved_at" to 99.0,
            "owner_key" to "member:owner-b"))

        val visible = ExamHistoryDao.load(db, "attempt-owner-revision", "owner-a")
        assertEquals(listOf("{\"answer\":\"owner-a\"}"), visible!!.answers.map { it.text("response_json") })
    }

    @Test fun forkUsesLatestLocalAnswersPositionAndResultAfterConflictCapture() {
        val owner = "account-fork-latest"
        val attemptID = "attempt-fork-latest"
        val forkID = "attempt-fork-latest-copy"
        val contentHash = "b".repeat(64)
        ExamHistoryDao.start(db,
            ExamSnapshotRecord("exam-fork-latest", "sha256:$contentHash", contentHash, 1, 1, 1,
                "bundled", "en", "B1", 202640, """{"question":"frozen"}""", 90.0),
            ExamAttemptRecord(attemptID, "exam-fork-latest", owner, 40.0))
        ExamHistoryDao.saveAnswer(db, attemptID, "q1", """{"answer":"before-conflict"}""", 1, 41.0)
        val server = """{"attemptId":"attempt-fork-latest","examId":"exam-fork-latest","revision":3,"state":"in_progress","position":0,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, owner, attemptID, server, 42.0))

        ExamHistoryDao.saveAnswer(db, attemptID, "q1", """{"answer":"after-conflict"}""", 3, 43.0)
        assertEquals("conflict", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
            .single().text("sync_state"))
        assertTrue(ExamHistoryDao.complete(db, attemptID, """{"score":91}""", 44.0))
        assertEquals("conflict", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
            .single().text("sync_state"))

        assertTrue(ExamHistoryDao.resolveConflictFork(db, owner, attemptID, forkID, 45.0))
        val fork = ExamHistoryDao.load(db, forkID, owner)!!
        assertEquals("completed", fork.attempt.text("state"))
        assertEquals(3L, fork.attempt.long("position"))
        assertEquals(45.0, fork.attempt.real("updated_at"), 0.0)
        assertTrue(fork.answers.single().text("response_json").contains("after-conflict"))
        assertTrue(fork.result!!.text("result_json").contains("91"))
        val created = db.query("SELECT payload_json FROM exam_sync_outbox WHERE attempt_id = ? AND event_type = 'created'",
            listOf(forkID)).single().text("payload_json")
        assertTrue(created.contains("after-conflict"))
        assertTrue(created.contains("91"))
        assertEquals("fork", ExamHistoryDao.conflict(db, owner, attemptID)!!.text("resolution"))
        assertEquals("conflict", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
            .single().text("sync_state"))
    }

    @Test fun forkedSourceRejectsLaterAnswerAndCompletionWrites() {
        val owner = "account-fork-immutable"
        val attemptID = "attempt-fork-immutable"
        val contentHash = "c".repeat(64)
        ExamHistoryDao.start(db,
            ExamSnapshotRecord("exam-fork-immutable", "sha256:$contentHash", contentHash, 1, 1, 1,
                "bundled", "en", "B1", 202640, """{"question":"frozen"}""", 90.0),
            ExamAttemptRecord(attemptID, "exam-fork-immutable", owner, 40.0))
        val server = """{"attemptId":"attempt-fork-immutable","examId":"exam-fork-immutable","revision":2,"state":"in_progress","position":0,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, owner, attemptID, server, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictFork(db, owner, attemptID, "attempt-fork-immutable-copy", 43.0))

        assertThrows(IllegalStateException::class.java) {
            ExamHistoryDao.saveAnswer(db, attemptID, "q1", """{"answer":"late"}""", 1, 44.0)
        }
        assertThrows(IllegalStateException::class.java) {
            ExamHistoryDao.complete(db, attemptID, """{"score":70}""", 44.0)
        }
        assertEquals(1L, db.query("SELECT revision FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
            .single().long("revision"))
    }

    @Test fun rejectedForkLeavesSourceOutboxAndConflictUntouched() {
        val owner = "account-fork-rollback"
        val attemptID = "attempt-fork-rollback"
        val forkID = "attempt-fork-rollback-copy"
        val contentHash = "d".repeat(64)
        ExamHistoryDao.start(db,
            ExamSnapshotRecord("exam-fork-rollback", "sha256:$contentHash", contentHash, 1, 1, 1,
                "bundled", "en", "B1", 202640, """{"question":"frozen"}""", 90.0),
            ExamAttemptRecord(attemptID, "exam-fork-rollback", owner, 40.0))
        ExamHistoryDao.saveAnswer(db, attemptID, "q1", """{"answer":"local"}""", 1, 41.0)
        val server = """{"attemptId":"attempt-fork-rollback","examId":"exam-fork-rollback","revision":2,"state":"in_progress","position":0,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, owner, attemptID, server, 42.0))
        ExamHistoryDao.start(db,
            ExamSnapshotRecord("exam-fork-collision", "sha256:${"e".repeat(64)}", "e".repeat(64), 1, 1, 1,
                "bundled", "en", "B1", 202640, """{"question":"other"}""", 90.0),
            ExamAttemptRecord(forkID, "exam-fork-collision", owner, 40.0))

        assertFalse(ExamHistoryDao.resolveConflictFork(db, owner, attemptID, forkID, 43.0))
        assertEquals("exam-fork-collision", db.query("SELECT exam_id FROM exam_attempts WHERE attempt_id = ?",
            listOf(forkID)).single().text("exam_id"))
        assertTrue(db.query("SELECT 1 AS found FROM exam_sync_conflicts WHERE attempt_id = ? AND resolution IS NOT NULL",
            listOf(attemptID)).isEmpty())
        assertTrue(db.query("SELECT delivery_state FROM exam_sync_outbox WHERE attempt_id = ?",
            listOf(attemptID)).all { it.text("delivery_state") == "pending" })
    }

    @Test fun deviceClockRollbackCannotRegressPersistedAttemptOrOutboxTime() {
        val contentHash = "a".repeat(64)
        ExamHistoryDao.start(db,
            ExamSnapshotRecord("exam-clock", "sha256:$contentHash", contentHash, 1, 1, 1,
                "bundled", "en", "B1", 202640, """{"question":"frozen"}""", 90.0),
            ExamAttemptRecord("attempt-clock", "exam-clock", startedAt = 100.0))

        ExamHistoryDao.saveAnswer(db, "attempt-clock", "q1", """{"answer":"one"}""", 1, 99.0)
        assertEquals(100.0, db.query("SELECT updated_at FROM exam_attempts WHERE attempt_id = 'attempt-clock'")
            .single().real("updated_at"), 0.0)
        assertEquals(100.0, db.query("SELECT saved_at FROM exam_answers WHERE attempt_id = 'attempt-clock'")
            .single().real("saved_at"), 0.0)
        assertEquals(100.0, db.query("SELECT created_at FROM exam_sync_outbox WHERE event_type = 'progress'")
            .single().real("created_at"), 0.0)

        ExamHistoryDao.saveAnswer(db, "attempt-clock", "q1", """{"answer":"two"}""", 2, 105.0)
        ExamHistoryDao.saveAnswer(db, "attempt-clock", "q1", """{"answer":"three"}""", 3, 104.0)
        assertEquals(listOf(100.0, 105.0, 105.0), db.query(
            "SELECT created_at FROM exam_sync_outbox WHERE event_type = 'progress' ORDER BY revision")
            .map { it.real("created_at") })

        ExamHistoryDao.complete(db, "attempt-clock", """{"score":90}""", 104.0)
        val completed = db.query("SELECT updated_at, completed_at FROM exam_attempts WHERE attempt_id = 'attempt-clock'").single()
        assertEquals(105.0, completed.real("updated_at"), 0.0)
        assertEquals(105.0, completed.real("completed_at"), 0.0)
        assertEquals(105.0, db.query("SELECT created_at FROM exam_sync_outbox WHERE event_type = 'completed'")
            .single().real("created_at"), 0.0)
    }
}
