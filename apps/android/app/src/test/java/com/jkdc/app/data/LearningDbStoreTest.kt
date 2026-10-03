package com.jkdc.app.data

import com.jkdc.app.core.Archive
import com.jkdc.app.core.ArchiveCodec
import com.jkdc.app.core.CompletionMark
import com.jkdc.app.core.FinalAssessmentRecovery
import com.jkdc.app.core.FinalAssessmentTicket
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * LearningDbStore on real SQLite (sqlite-jdbc) with the file operations backed
 * by a real temp directory: the one-way learning.json migration (counts
 * verified against the pinned case-basic fixture), the hash gate that keeps a
 * reappearing JSON ignored, the fail-safe mode for corrupt input or verified
 * count mismatches, and the unfinished-session recovery that must behave
 * exactly like LearningRepository.load + FinalAssessmentRecovery.enqueue do
 * today. The production Android wiring (LearningDb.open + AtomicFile) is a
 * one-liner exercised by the e2e task, not here.
 */
class LearningDbStoreTest {
    private val now = 812_700_000.0
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)
    private val fileConnections = mutableListOf<Connection>()

    @get:Rule val tmp = TemporaryFolder()

    @After fun closeConnections() {
        connection.close()
        fileConnections.forEach { it.close() }
    }

    @Test fun caseBasicMigratesWithVerifiedCountsHashAndRename() {
        val bytes = fixtureBytes("case-basic.json")
        val files = TempJsonFiles(tmp.newFolder(), bytes)
        val store = storeOver(files)
        assertFalse("a healthy migration must not engage JSON mode", store.jsonMode)
        assertNull(store.jsonModeNotice)

        // every pinned table count holds (case-basic.expected.json), with the
        // meta table carrying the two migration bookkeeping rows on top
        val expected = Json.parseToJsonElement(fixture("case-basic.expected.json"))
            .jsonObject.getValue("tables").jsonObject
        assertEquals(10, expected.size)
        expected.forEach { (table, rowCount) ->
            if (table == "meta") return@forEach
            assertEquals("row count of $table", rowCount.jsonPrimitive.long, count(table))
        }
        assertEquals(3L + 2L, count("meta"))
        assertEquals(listOf("mastery:function:describe-people", "mastery:grammar:articles",
            "migrated_at", "migration_hash", "schema_flags"),
            db.query("SELECT key FROM meta ORDER BY key").map { it.text("key") })

        assertEquals(sha256(bytes), MetaDao.get(db, LearningDbStore.MIGRATION_HASH_KEY))
        assertEquals(now.toString(), MetaDao.get(db, LearningDbStore.MIGRATED_AT_KEY))

        // rename happened, and it was a rename: the migrated file holds the original bytes
        assertTrue(files.migrated.exists())
        assertFalse(files.json.exists())
        assertTrue(files.migrated.readBytes().contentEquals(bytes))

        // the database hands back exactly the decoded archive (schema v1's
        // pinned lesson projection: bars/dueAt are not stored)
        val decoded = ArchiveCodec.decode(fixture("case-basic.json"))
        val persistedProjection = decoded.copy(
            lessons = decoded.lessons.map { it.copy(bars = 0, dueAt = 0.0) }.toMutableList())
        assertEquals(persistedProjection, store.loadInitialArchive())
    }

    @Test fun reopenWithHashPresentIgnoresAReappearingJson() {
        // a real restart: file-backed database closed and reopened
        val dir = tmp.newFolder()
        val dbPath = File(dir, "learning.db")
        val files = TempJsonFiles(dir, fixtureBytes("case-basic.json"))
        val first = LearningDbStore.open(openJvmFileBacked(dbPath.path), files) { now }
        assertFalse(first.jsonMode)
        val firstHash = MetaDao.get(first.db, LearningDbStore.MIGRATION_HASH_KEY)

        // learning.json reappears (here: corrupt leftovers) — must be ignored
        files.rewriteJson("{not json".toByteArray())
        val second = LearningDbStore.open(openJvmFileBacked(dbPath.path), files) { now }
        assertFalse(second.jsonMode)
        assertEquals("no re-migration: the sessions stay the migrated three",
            3L, count(second.db, "sessions"))
        assertEquals(4L, count(second.db, "expressions"))
        assertEquals(0L, count(second.db, "assessment_tickets"))
        assertEquals("hash untouched", firstHash,
            MetaDao.get(second.db, LearningDbStore.MIGRATION_HASH_KEY))
        assertTrue("the ignored JSON is left exactly where it is", files.json.exists())
        assertTrue("the first launch's migrated copy is untouched",
            files.migrated.readBytes().contentEquals(fixtureBytes("case-basic.json")))
    }

    @Test fun corruptJsonEngagesFailSafeAndLeavesTheJsonUntouched() {
        val bytes = "{not json".toByteArray()
        val files = TempJsonFiles(tmp.newFolder(), bytes)
        val store = storeOver(files)
        assertTrue(store.jsonMode)
        assertNotNull(store.jsonModeNotice)
        assertThrows("the database must be unreachable in JSON mode",
            IllegalArgumentException::class.java) { store.db }
        assertThrows(IllegalArgumentException::class.java) { store.loadInitialArchive() }

        assertTrue("the JSON is never deleted or renamed in fail-safe mode", files.json.exists())
        assertTrue(files.json.readBytes().contentEquals(bytes))
        assertFalse(files.migrated.exists())
        assertNull(MetaDao.get(db, LearningDbStore.MIGRATION_HASH_KEY))
        assertEquals(0L, count("sessions"))
    }

    @Test fun countMismatchFromSameInstantCrossLanguageCompletionsAbortsMigration() {
        // a codec-VALID archive whose completions only differ by language at
        // the same instant: the completions primary key keeps one, so verified
        // counts catch the dropped mark and abort instead of losing data
        val decoded = ArchiveCodec.decode(fixture("case-basic.json"))
        decoded.completions!!.add(CompletionMark("lesson", "es-foundations-u1-ser",
            at = 812577400.0, bestScore = 50, languageID = "en"))
        val poisoned = ArchiveCodec.encode(decoded).toByteArray()
        assertEquals("the poisoned archive must still be codec-valid", 6,
            ArchiveCodec.decode(String(poisoned, Charsets.UTF_8)).completions!!.size)

        val files = TempJsonFiles(tmp.newFolder(), poisoned)
        val store = storeOver(files)
        assertTrue("ANY verified count mismatch must fail safe", store.jsonMode)
        assertNotNull(store.jsonModeNotice)
        assertTrue(files.json.readBytes().contentEquals(poisoned))
        assertFalse(files.migrated.exists())
        // the bookkeeping rolled back: no hash means the (already imported)
        // content is unreachable and the next launch retries into fail-safe
        assertNull(MetaDao.get(db, LearningDbStore.MIGRATION_HASH_KEY))
        assertNull(MetaDao.get(db, LearningDbStore.MIGRATED_AT_KEY))
        assertEquals(0L, count("assessment_tickets"))
        // the import's own rows (schema_flags + mastery) are committed content
        assertEquals(3L, count("meta"))
        assertEquals(3L, count("sessions"))
        assertEquals(5L, count("completions"))
    }

    @Test fun retryLedgerTicketsMigrateIntoAssessmentTickets() {
        val root = Json.parseToJsonElement(fixture("case-basic.json")).jsonObject
        val withLedger = JsonObject(root + ("androidFinalAssessments" to Json.parseToJsonElement(
            """[{"sessionID":"7f6c2a1e-58d4-4b3a-9e2f-0c8d1a4b6e90","passageID":"p",
                "revisionKey":"r","attempts":2,"lastAttemptAt":812577400.5}]""")))
        val files = TempJsonFiles(tmp.newFolder(), withLedger.toString().toByteArray())
        val store = storeOver(files)
        assertFalse(store.jsonMode)
        assertEquals(listOf(AssessmentTicketRow("7f6c2a1e-58d4-4b3a-9e2f-0c8d1a4b6e90",
            attempts = 2, lastAttemptAt = 812577400.5)), AssessmentTicketsDao.all(db))
    }

    @Test fun invalidRetryLedgerAbortsMigration() {
        val root = Json.parseToJsonElement(fixture("case-basic.json")).jsonObject
        val duplicated = JsonObject(root + ("androidFinalAssessments" to Json.parseToJsonElement(
            """[{"sessionID":"s","passageID":"p","revisionKey":"r"},
                {"sessionID":"s","passageID":"p2","revisionKey":"r2"}]""")))
        val files = TempJsonFiles(tmp.newFolder(), duplicated.toString().toByteArray())
        val store = storeOver(files)
        assertTrue("LearningRepository.load's ledger validation must abort the migration",
            store.jsonMode)
        assertTrue(files.json.exists())
        assertNull(MetaDao.get(db, LearningDbStore.MIGRATION_HASH_KEY))
        assertEquals(0L, count("assessment_tickets"))
    }

    @Test fun recoveryMirrorsLearningRepositoryLoadAndEnqueue() {
        val files = TempJsonFiles(tmp.newFolder(), fixtureBytes("case-basic.json"))
        val store = storeOver(files)
        val archive = store.loadInitialArchive()

        // reference: the exact loop LearningRepository.load runs today
        val decoded = ArchiveCodec.decode(fixture("case-basic.json"))
        var reference = emptyList<FinalAssessmentTicket>()
        decoded.sessions.filter { it.endedAt == null }.forEach {
            it.endedAt = now; it.endReason = LearningDbStore.RECOVERY_END_REASON
            reference = FinalAssessmentRecovery.enqueue(it, reference)
        }
        assertEquals("the fixture must hold exactly one unfinished, unassessed session",
            listOf(UNFINISHED_SESSION), reference.map { it.sessionID })

        val recovered = store.recoverUnfinishedSessions(archive, now)
        assertSame("the working copy objects are updated in place", archive, recovered)
        val session = recovered.sessions.single { it.id == UNFINISHED_SESSION }
        assertEquals(now as Double?, session.endedAt)
        assertEquals(LearningDbStore.RECOVERY_END_REASON, session.endReason)

        // the database holds exactly the reference enqueue outcome (a fresh
        // attempts=0 ticket for that session's unassessed passage)
        val rows = AssessmentTicketsDao.all(db)
        assertEquals(reference.map { it.sessionID }, rows.map { it.sessionID })
        assertEquals(reference.map { it.attempts }, rows.map { it.attempts })
        assertEquals(reference.map { it.lastAttemptAt }, rows.map { it.lastAttemptAt })

        // finished sessions are never touched
        assertEquals(812577360.0 as Double?,
            recovered.sessions.single { it.id == FINISHED_ES_SESSION }.endedAt)
        assertEquals(812490960.0 as Double?,
            recovered.sessions.single { it.id == SCENARIO_FR_SESSION }.endedAt)
        assertEquals("database and working copy agree", archive, store.loadInitialArchive())
    }

    @Test fun recoveryIsIdempotentOnTheNextLaunch() {
        val files = TempJsonFiles(tmp.newFolder(), fixtureBytes("case-basic.json"))
        val store = storeOver(files)
        val firstLaunch = store.recoverUnfinishedSessions(store.loadInitialArchive(), now)

        val secondLaunch = store.recoverUnfinishedSessions(store.loadInitialArchive(), now + 60.0)
        assertEquals("nothing left to recover: the archive is stable",
            firstLaunch, secondLaunch)
        assertEquals(1L, count("assessment_tickets"))
        assertEquals(now as Double?, secondLaunch.sessions.single { it.id == UNFINISHED_SESSION }.endedAt)
    }

    @Test fun recoveryWithoutRecoverablePassageEndsTheSessionWithoutTicket() {
        // the user passage is too short to assess: FinalAssessmentRecovery.enqueue
        // adds no ticket (its removal branch deletes instead) — same in the DB
        val decoded = ArchiveCodec.decode(fixture("case-basic.json"))
        decoded.sessions.single { it.id == UNFINISHED_SESSION }
            .fragments.single { it.id == UNASSESSED_PASSAGE }.text = "Sí"
        val files = TempJsonFiles(tmp.newFolder(), ArchiveCodec.encode(decoded).toByteArray())
        val store = storeOver(files)
        assertFalse(store.jsonMode)

        val recovered = store.recoverUnfinishedSessions(store.loadInitialArchive(), now)
        assertEquals(now as Double?,
            recovered.sessions.single { it.id == UNFINISHED_SESSION }.endedAt)
        assertEquals("the session ends but never gets a ticket", 0L, count("assessment_tickets"))
    }

    @Test fun renameFailureAfterCommitKeepsTheMigrationComplete() {
        val files = TempJsonFiles(tmp.newFolder(), fixtureBytes("case-basic.json"))
        files.failRename()
        val store = storeOver(files)
        assertFalse("a committed migration survives a failed post-commit rename", store.jsonMode)
        assertNotNull(MetaDao.get(db, LearningDbStore.MIGRATION_HASH_KEY))
        assertEquals(3L, count("sessions"))
        assertTrue("the stranded JSON stays in place, where the hash gate ignores it",
            files.json.exists())
    }

    @Test fun freshDatabaseWithoutJsonOpensStraightIntoDatabaseMode() {
        val files = TempJsonFiles(tmp.newFolder(), jsonBytes = null)
        val store = storeOver(files)
        assertFalse(store.jsonMode)
        assertNull(store.jsonModeNotice)
        assertEquals(Archive(), store.loadInitialArchive())
        assertNull(MetaDao.get(db, LearningDbStore.MIGRATION_HASH_KEY))
    }

    // -- helpers -----------------------------------------------------------

    private fun storeOver(files: LearningDbStore.Files): LearningDbStore =
        LearningDbStore.open(db, files) { now }

    private fun fixture(name: String): String =
        File("../../../shared/fixtures/db/migration/$name").readText()

    private fun fixtureBytes(name: String): ByteArray =
        File("../../../shared/fixtures/db/migration/$name").readBytes()

    private fun count(table: String) = count(db, table)

    private fun count(target: SqlDatabase, table: String): Long =
        target.query("SELECT COUNT(*) AS n FROM $table").single().long("n")

    /** The restart twin of LearningDb.openJvm for a file-backed path: applies
     *  the canonical schema only when the database file is brand new. */
    private fun openJvmFileBacked(path: String): SqlDatabase {
        val connection = DriverManager.getConnection("jdbc:sqlite:$path")
        fileConnections += connection
        val target = JvmSqlDatabase(connection)
        target.exec("PRAGMA foreign_keys = ON")
        if (target.query("PRAGMA user_version").single().values.single() == 0L) {
            for (statement in SchemaData.LEARNING_SCHEMA_STATEMENTS) target.exec(statement)
        }
        return target
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class TempJsonFiles(dir: File, jsonBytes: ByteArray?) : LearningDbStore.Files {
        val json = File(dir, "learning.json")
        val migrated = File(dir, "learning.json.migrated")
        private var renameThrows = false

        init { jsonBytes?.let { json.writeBytes(it) } }

        fun rewriteJson(bytes: ByteArray) { json.writeBytes(bytes) }

        fun failRename() { renameThrows = true }

        override fun learningJsonExists() = json.exists()

        override fun readLearningJson(): ByteArray = json.readBytes()

        override fun renameLearningJsonToMigrated() {
            if (renameThrows) throw IOException("simulated rename failure")
            check(json.renameTo(migrated)) { "rename learning.json -> learning.json.migrated failed" }
        }
    }

    companion object {
        private const val UNFINISHED_SESSION = "a3d94b12-6f57-4c08-b1a2-93e4f5a6b7c8"
        private const val UNASSESSED_PASSAGE = "66f6a7b8-92a3-4eb5-dc6d-ef5a6b7c8d95"
        private const val FINISHED_ES_SESSION = "7f6c2a1e-58d4-4b3a-9e2f-0c8d1a4b6e90"
        private const val SCENARIO_FR_SESSION = "c5e16f28-7a09-4d31-82b3-a4f5b6c7d8e9"
    }
}
