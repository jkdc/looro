package com.jkdc.app.data

import com.jkdc.app.core.LevelTestEngine
import com.jkdc.app.core.LevelTestsRegistry
import com.jkdc.app.network.LevelTestSnapshotAdapter
import java.sql.DriverManager
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class LevelTestAttemptStoreTest {
    private lateinit var db: SqlDatabase
    private lateinit var databaseFile: File

    @Before fun setUp() {
        databaseFile = Files.createTempFile("level-test-attempts", ".sqlite").toFile()
        db = openDatabase(initialize = true)
    }
    @After fun tearDown() { db.close(); databaseFile.delete() }

    @Test fun `start stores a durable immutable attempt that reloads by attempt id`() {
        val level = requireNotNull(LevelTestsRegistry.level("en", "A1"))
        val selection = LevelTestSnapshotAdapter.fromBundled(level, LevelTestEngine.compose(level, 202642))
        val store = LevelTestAttemptStore(db)

        val started = store.start(selection, attemptID = "attempt-local-1", startedAt = 1.0)
        db.close()
        db = openDatabase(initialize = false)
        val restored = LevelTestAttemptStore(db).load("attempt-local-1")

        assertNotNull(started)
        assertNotNull(restored)
        assertEquals(selection.snapshotJSON, restored!!.selection.snapshotJSON)
        assertEquals(0, restored.position)
        assertEquals(1_000L, restored.startedAt)
        assertEquals(emptyMap<String, String>(), restored.responses)
        assertEquals("local_only", restored.syncState)
        assertEquals(listOf("attempt-local-1"), LevelTestAttemptStore(db).list("en", "A1").map { it.attemptID })
    }

    @Test fun `answer and completion survive reload and cannot be completed twice`() {
        val level = requireNotNull(LevelTestsRegistry.level("en", "A1"))
        val selection = LevelTestSnapshotAdapter.fromBundled(level, LevelTestEngine.compose(level, 202642))
        val store = LevelTestAttemptStore(db)
        store.start(selection, attemptID = "attempt-local-2", startedAt = 1.0)
        val questionID = selection.exam.items.first().id

        assertEquals(2L, store.saveAnswer("attempt-local-2", questionID, "A", 1, 2.0))
        val resultJSON = """{"languageID":"en","level":"A1","weekId":202642,"overall":4,"sections":{"recognition":8},"passed":false,"nextBand":null,"weakSections":["recognition"],"weakUnitIDs":[],"at":3000,"questionReviews":[{"questionId":"$questionID","response":"A","acceptedAnswers":["A"],"correct":true,"explanation":"original explanation","passageId":null}]}"""
        assertEquals(true, store.complete("attempt-local-2", resultJSON, 3.0))
        assertEquals(false, store.complete("attempt-local-2", resultJSON, 4.0))
        val restored = store.load("attempt-local-2")

        assertEquals(1, restored!!.position)
        assertEquals("A", restored.responses[questionID])
        assertEquals(4, restored.result?.overall)
        assertEquals(1, restored.questionReviews.size)
        assertEquals("original explanation", restored.questionReviews.single().explanation)
        assertEquals(true, restored.questionReviews.single().correct)
    }

    @Test fun `completion atomically persists final answers and retry does not mutate completed attempt`() {
        val level = requireNotNull(LevelTestsRegistry.level("en", "A1"))
        val selection = LevelTestSnapshotAdapter.fromBundled(level, LevelTestEngine.compose(level, 202642))
        val store = LevelTestAttemptStore(db)
        store.start(selection, attemptID = "attempt-final-retry", startedAt = 1.0)
        val questionID = selection.exam.items.first().id
        store.saveAnswer("attempt-final-retry", questionID, "draft", 1, 2.0)
        val resultJSON = """{"languageID":"en","level":"A1","weekId":202642,"overall":4,"sections":{"recognition":8},"passed":false,"nextBand":null,"weakSections":["recognition"],"weakUnitIDs":[],"at":3000,"questionReviews":[{"questionId":"$questionID","response":"final","acceptedAnswers":["final"],"correct":true,"explanation":"original explanation","passageId":null}]}"""
        val answers = mapOf(questionID to "final")

        assertEquals(true, store.complete("attempt-final-retry", resultJSON, 3.0, answers, 19))
        val completedRevision = db.query("SELECT revision FROM exam_attempts WHERE attempt_id = ?",
            listOf("attempt-final-retry")).single().long("revision")
        val completedEvents = db.query("SELECT count(*) AS total FROM exam_sync_outbox WHERE attempt_id = ?",
            listOf("attempt-final-retry")).single().int("total")
        assertEquals(false, store.complete("attempt-final-retry", resultJSON, 4.0, answers, 19))

        val restored = store.load("attempt-final-retry")!!
        assertEquals(completedRevision, db.query("SELECT revision FROM exam_attempts WHERE attempt_id = ?",
            listOf("attempt-final-retry")).single().long("revision"))
        assertEquals(completedEvents, db.query("SELECT count(*) AS total FROM exam_sync_outbox WHERE attempt_id = ?",
            listOf("attempt-final-retry")).single().int("total"))
        assertEquals(19, restored.position)
        assertEquals("final", restored.responses[questionID])
        assertEquals("final", restored.questionReviews.single().response)
    }

    private fun openDatabase(initialize: Boolean): SqlDatabase {
        val connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")
        return if (initialize) LearningDb.openJvm(connection)
        else JvmSqlDatabase(connection).also { it.exec("PRAGMA foreign_keys = ON") }
    }
}
