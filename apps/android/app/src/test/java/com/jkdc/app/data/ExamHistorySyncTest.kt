package com.jkdc.app.data

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamHistorySyncTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)

    @After fun closeConnection() { connection.close() }

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
