package com.jkdc.app.data

import com.jkdc.app.core.Archive
import com.jkdc.app.core.ArchiveCodec
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Archive-to-rows roundtrip over the shared migration fixtures on real
 * SQLite (sqlite-jdbc): decode the fixture archive with ArchiveCodec, import
 * it into the learning database, export it back and require struct equality.
 * The row counts also assert against the pinned case-basic.expected.json, so
 * this test and scripts/check_db_schema.py pin the same migration outcome.
 */
class ArchiveMapperTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)

    @After fun closeConnection() { connection.close() }

    private fun fixture(name: String): String =
        File("../../../shared/fixtures/db/migration/$name").readText()

    @Test fun caseBasicRoundTripsThroughRows() {
        val decoded = ArchiveCodec.decode(fixture("case-basic.json"))
        // lesson bars/dueAt are deliberately not stored in schema v1 (shared/db/schema.sql,
        // lesson_state); normalize the decoded archive to the persisted projection
        val archived = decoded.copy(lessons = decoded.lessons.map { it.copy(bars = 0, dueAt = 0.0) }.toMutableList())
        assertTrue("the normalization must be doing real work on this fixture",
            decoded.lessons.any { it.bars != 0 || it.dueAt != 0.0 })
        ArchiveMapper.import(archived, db)
        assertEquals(archived, ArchiveMapper.export(db))
    }

    @Test fun caseEmptyRoundTripsAndStaysEmpty() {
        val decoded = ArchiveCodec.decode(fixture("case-empty.json"))
        ArchiveMapper.import(decoded, db)
        val exported = ArchiveMapper.export(db)
        assertEquals(decoded, exported)
        assertNull(exported.completions)
        assertNull(exported.errors)
        assertNull(exported.masteryCache)
        assertEquals(0, exported.sessions.size)
        assertEquals(0, exported.lessons.size)
        assertEquals("nb", exported.preferences.learningLanguageID)
        assertNull(exported.preferences.speechRate)
    }

    @Test fun importMatchesThePinnedExpectedRowCounts() {
        val expected = Json.parseToJsonElement(fixture("case-basic.expected.json"))
            .jsonObject.getValue("tables").jsonObject
        assertEquals(10, expected.size)
        ArchiveMapper.import(ArchiveCodec.decode(fixture("case-basic.json")), db)
        expected.forEach { (table, rowCount) ->
            assertEquals("row count of $table", rowCount.jsonPrimitive.long,
                db.query("SELECT COUNT(*) AS n FROM $table").single().long("n"))
        }
    }

    @Test fun legacyNullLanguageExpressionsCoexistWithLanguageRows() {
        ArchiveMapper.import(ArchiveCodec.decode(fixture("case-basic.json")), db)
        val legacy = db.query("SELECT text FROM expressions WHERE language_id IS NULL")
            .map { it.text("text") }
        assertEquals(listOf("Buenos días"), legacy)
        // two non-legacy rows share one norm_key across two distinct languages
        val languages = db.query(
            "SELECT language_id FROM expressions WHERE norm_key = 'important' ORDER BY language_id")
            .map { it.text("language_id") }
        assertEquals(listOf("es", "fr"), languages)
    }

    @Test fun deletingASessionCascadesToItsFragments() {
        ArchiveMapper.import(ArchiveCodec.decode(fixture("case-basic.json")), db)
        assertEquals(9L, db.query("SELECT COUNT(*) AS n FROM fragments").single().long("n"))
        val first = SessionsDao.all(db).first()
        val itsFragments = FragmentsDao.bySession(db, first.id).size
        assertTrue(itsFragments > 0)
        SessionsDao.delete(db, first.id)
        assertEquals(9L - itsFragments, db.query("SELECT COUNT(*) AS n FROM fragments").single().long("n"))
        assertTrue(FragmentsDao.bySession(db, first.id).isEmpty())
    }

    @Test fun importLeavesThePlatformLocalQueuesAlone() {
        UsageOutboxDao.enqueue(db, "app_open", 1_727_859_600_000L)
        AssessmentTicketsDao.upsert(db, AssessmentTicketRow("queued-session", attempts = 0, lastAttemptAt = null))
        ArchiveMapper.import(ArchiveCodec.decode(fixture("case-basic.json")), db)
        assertEquals(1, UsageOutboxDao.pending(db).size)
        assertEquals("queued-session", AssessmentTicketsDao.all(db).single().sessionID)
    }

    @Test fun importWritesSchemaFlagsAndOneMasteryMetaRowPerSkill() {
        ArchiveMapper.import(ArchiveCodec.decode(fixture("case-basic.json")), db)
        val keys = db.query("SELECT key FROM meta ORDER BY key").map { it.text("key") }
        assertEquals(listOf("mastery:function:describe-people", "mastery:grammar:articles", "schema_flags"), keys)
        // the mastery rows export back as the cache and schema_flags does not leak
        assertEquals(mapOf("grammar:articles" to 2, "function:describe-people" to 1),
            ArchiveMapper.export(db).masteryCache)
    }

    @Test fun anEmptyDatabaseExportsAFreshArchive() {
        assertEquals(Archive(), ArchiveMapper.export(db))
    }
}
