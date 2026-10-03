package com.jkdc.app.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** The learning schema must open verbatim through the shared statement list. */
class LearningDbTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)

    @After fun closeConnection() { connection.close() }

    @Test fun schemaOpensAtVersionOneWithExactlyThePinnedTables() {
        assertEquals(1L, db.query("PRAGMA user_version").single().values.single())
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .map { it["name"] as String }
        assertEquals(listOf("assessment_tickets", "completions", "expressions", "fragments",
            "lesson_state", "meta", "preferences", "sessions", "skill_errors", "usage_outbox"), tables)
        assertFalse("words must never be stored; hidden words live in preferences", tables.contains("words"))
    }

    @Test fun foreignKeysAreEnforcedOnTheConnection() {
        assertEquals(1L, db.query("PRAGMA foreign_keys").single().values.single())
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

    private fun count(table: String) = db.query("SELECT COUNT(*) AS n FROM $table").single()["n"]
}
