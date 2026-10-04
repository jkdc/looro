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

    @Test fun schemaOpensAtVersionTwoWithExactlyThePinnedTables() {
        assertEquals(2L, db.query("PRAGMA user_version").single().values.single())
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .map { it["name"] as String }
        // fragments_fts is the pinned v2 virtual table; fragments_fts_* are its
        // FTS5 shadow tables (SQLite internals, like sqlite_sequence — an
        // external-content table keeps no _content of its own).
        assertEquals(listOf("assessment_tickets", "completions", "expressions", "fragments",
            "fragments_fts", "fragments_fts_config", "fragments_fts_data",
            "fragments_fts_docsize", "fragments_fts_idx",
            "lesson_state", "meta", "preferences", "sessions", "skill_errors",
            "usage_outbox"), tables)
        assertFalse("words must never be stored; hidden words live in preferences", tables.contains("words"))
        val virtual = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'fragments_fts'")
            .single().values.single() as String
        assertTrue(virtual.contains("USING fts5"))
        assertTrue(virtual.contains("content='fragments'"))
        assertEquals(listOf("fragments_fts_delete", "fragments_fts_insert", "fragments_fts_update"),
            db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
                .map { it["name"] as String })
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
            assertEquals(2L, v1.query("PRAGMA user_version").single().values.single())
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
}
