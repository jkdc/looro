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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
 * reappearing JSON ignored, the retry that must recover (clearing an aborted
 * attempt's residue) when the JSON evolved between attempts, the fail-safe
 * mode for corrupt, oversized input or verified count mismatches, the rethrow
 * when there is no JSON to fall back on, and the unfinished-session recovery
 * that must behave exactly like LearningRepository.load +
 * FinalAssessmentRecovery.enqueue do today. The production Android wiring
 * (LearningDb.open + AtomicFile) is a one-liner exercised by the e2e task,
 * not here.
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
        assertEquals(17, expected.size)
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

    @Test fun corruptOwnerlessFallbackIsQuarantinedAcrossAccountChanges() {
        val bytes = "{not json".toByteArray()
        val files = TempJsonFiles(tmp.newFolder(), bytes)

        for (owner in listOf("member:account-a", "member:account-b", "guest:device")) {
            assertThrows("An active identity must not access shared fallback data", IllegalStateException::class.java) {
                LearningDbStore.open(db, files, activeIdentity = owner, clock = { now })
            }
            assertTrue("The legacy backup must remain byte-identical", files.json.readBytes().contentEquals(bytes))
            assertFalse("A failed migration must not rename the backup", files.migrated.exists())
        }
    }

    @Test fun concurrentOwnerOpenCannotImportFirstLearnersArchiveUnderSecondOwner() {
        val aBytes = fixtureBytes("case-basic.json")
        val bBytes = ArchiveCodec.encode(Archive()).toByteArray()
        val aFiles = TempJsonFiles(tmp.newFolder(), aBytes)
        val bFilesDelegate = TempJsonFiles(tmp.newFolder(), bBytes)
        val aReadStarted = CountDownLatch(1)
        val continueARead = CountDownLatch(1)
        val bLookupStarted = CountDownLatch(1)
        val aFilesBlocking = object : LearningDbStore.Files {
            override fun learningJsonExists() = aFiles.learningJsonExists()
            override fun readLearningJson(): ByteArray {
                val bytes = aFiles.readLearningJson()
                aReadStarted.countDown()
                check(continueARead.await(5, TimeUnit.SECONDS))
                return bytes
            }
            override fun renameLearningJsonToMigrated() = aFiles.renameLearningJsonToMigrated()
        }
        val bFilesSignaling = object : LearningDbStore.Files {
            override fun learningJsonExists(): Boolean {
                bLookupStarted.countDown()
                return bFilesDelegate.learningJsonExists()
            }
            override fun readLearningJson() = bFilesDelegate.readLearningJson()
            override fun renameLearningJsonToMigrated() = bFilesDelegate.renameLearningJsonToMigrated()
        }
        val databasePath = File(tmp.newFolder(), "learning.db").path
        val aDatabase = openJvmFileBacked(databasePath)
        val bDatabase = openJvmFileBacked(databasePath)
        val legacyHash = "d".repeat(64)
        ExamHistoryDao.start(aDatabase,
            ExamSnapshotRecord("exam-legacy-owner", "sha256:$legacyHash", legacyHash, 1, 1, 1,
                "bundled", "en", "B1", 202640, "{}", 10.0),
            ExamAttemptRecord("attempt-legacy-owner", "exam-legacy-owner", startedAt = 10.0))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val aOpen = executor.submit<LearningDbStore> {
                LearningDbStore.open(aDatabase, aFilesBlocking, activeIdentity = "member:account-a", clock = { now })
            }
            assertTrue("A should pause inside its migration source read", aReadStarted.await(5, TimeUnit.SECONDS))
            val bOpenStarted = CountDownLatch(1)
            val bOpen = executor.submit<LearningDbStore> {
                bOpenStarted.countDown()
                LearningDbStore.open(bDatabase, bFilesSignaling, activeIdentity = "member:account-b", clock = { now })
            }
            assertTrue("B open should be scheduled while A is suspended", bOpenStarted.await(5, TimeUnit.SECONDS))
            assertFalse("B must not enter its open/migration while A owns the identity boundary",
                bLookupStarted.await(250, TimeUnit.MILLISECONDS))
            continueARead.countDown()
            aOpen.get(5, TimeUnit.SECONDS)
            bOpen.get(5, TimeUnit.SECONDS)

            assertEquals(sha256(aBytes), MetaDao.get(bDatabase, LearningDbStore.MIGRATION_HASH_KEY))
            val owners = bDatabase.query("SELECT owner_key FROM sessions").map { it.text("owner_key") }
            assertTrue(owners.isNotEmpty())
            assertTrue(owners.all { it == "member:account-a" })
            assertEquals("account-a", bDatabase.query("SELECT owner_account_id FROM exam_attempts WHERE attempt_id = ?",
                listOf("attempt-legacy-owner")).single().textOrNull("owner_account_id"))
            assertEquals("member:account-a", bDatabase.query("SELECT owner_key FROM exam_attempts WHERE attempt_id = ?",
                listOf("attempt-legacy-owner")).single().text("owner_key"))
            assertTrue(bFilesDelegate.json.exists())
            assertFalse(bFilesDelegate.migrated.exists())
        } finally {
            continueARead.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun failedMetadataCommitStaysPendingForItsOwnerWithoutWedgingOtherAccounts() {
        val bytes = fixtureBytes("case-basic.json")
        val archive = ArchiveCodec.decode(fixture("case-basic.json"))
        val files = TempJsonFiles(tmp.newFolder(), bytes)
        val databasePath = File(tmp.newFolder(), "learning.db").path

        // Launch 1, account A: the clock throws while the bookkeeping
        // transaction is still open. The whole migration (clear + import +
        // tickets + verification + marker) must roll back together — only the
        // pending marker survives, naming A as the migration's one owner.
        val broken = openJvmFileBacked(databasePath)
        assertThrows("the metadata failure must abort the open",
            LearningDbStore.QuarantinedLegacyArchive::class.java) {
            LearningDbStore.open(broken, files, activeIdentity = "member:account-a",
                clock = { error("injected metadata commit failure") })
        }
        assertEquals("the rollback leaves no imported rows behind", 0L, count(broken, "sessions"))
        assertEquals("the pending marker names A the migration's owner",
            "member:account-a", MetaDao.get(broken, LearningDbStore.MIGRATION_PENDING_OWNER_KEY))
        assertEquals("the pending hash is the original source's", sha256(bytes),
            MetaDao.get(broken, LearningDbStore.MIGRATION_PENDING_HASH_KEY))
        assertNull(MetaDao.get(broken, LearningDbStore.MIGRATION_HASH_KEY))
        assertTrue("the JSON must survive its owner's failed attempt", files.json.exists())
        assertFalse(files.migrated.exists())

        // Launch 2, account B: the gate is open and the JSON exists, but the
        // pending marker names A — B must not claim (or clear) another
        // identity's one-way migration. B opens in database mode with none of
        // A's data instead of being wedged by foreign rows.
        val second = openJvmFileBacked(databasePath)
        val bStore = LearningDbStore.open(second, files,
            activeIdentity = "member:account-b", clock = { now })
        assertFalse("B's open must succeed without the wedged counts", bStore.jsonMode)
        assertEquals(0L, count(second, "sessions"))
        assertEquals("the backup still belongs to A", "member:account-a",
            MetaDao.get(second, LearningDbStore.MIGRATION_PENDING_OWNER_KEY))
        assertNull("B's open must not commit the migration",
            MetaDao.get(second, LearningDbStore.MIGRATION_HASH_KEY))
        assertTrue("the JSON stays available for A's retry", files.json.exists())

        // Launch 3, account A: the pending owner resumes and completes the
        // migration with the original bytes' hash.
        val aStore = LearningDbStore.open(second, files,
            activeIdentity = "member:account-a", clock = { now })
        assertFalse(aStore.jsonMode)
        assertEquals("the commit point hashes the original bytes", sha256(bytes),
            MetaDao.get(second, LearningDbStore.MIGRATION_HASH_KEY))
        assertEquals(now.toString(), MetaDao.get(second, LearningDbStore.MIGRATED_AT_KEY))
        assertNull(MetaDao.get(second, LearningDbStore.MIGRATION_PENDING_OWNER_KEY))
        assertEquals(archive.sessions.size.toLong(),
            countOwner(second, "member:account-a", "sessions"))
        assertEquals(0L, countOwner(second, "member:account-b", "sessions"))
        assertTrue(files.migrated.exists())
        assertFalse("the committed JSON is archived, not kept", files.json.exists())
        val projection = archive.copy(
            lessons = archive.lessons.map { it.copy(bars = 0, dueAt = 0.0) }.toMutableList())
        assertEquals("the database holds exactly the original archive", projection,
            aStore.loadInitialArchive())

        // Launch 4, reopen: A's verified rows survive and B still has none —
        // no cross-account clearing, no reattribution.
        val reopened = openJvmFileBacked(databasePath)
        val again = LearningDbStore.open(reopened, files,
            activeIdentity = "member:account-b", clock = { now })
        assertFalse(again.jsonMode)
        assertEquals(archive.sessions.size.toLong(),
            countOwner(reopened, "member:account-a", "sessions"))
        assertEquals(0L, countOwner(reopened, "member:account-b", "sessions"))
        assertTrue(files.migrated.exists())
    }

    @Test fun verificationIsScopedToTheMigratingOwnerAndNeverClearsOtherAccounts() {
        val bytes = fixtureBytes("case-basic.json")
        val archive = ArchiveCodec.decode(fixture("case-basic.json"))
        val files = TempJsonFiles(tmp.newFolder(), bytes)
        val databasePath = File(tmp.newFolder(), "learning.db").path
        val target = openJvmFileBacked(databasePath)

        // Residue from the pre-atomic world: two identities each hold their
        // own committed-but-unmarked rows (the old flow imported before the
        // bookkeeping), so the shared tables hold two copies of the archive.
        LearnerOwnershipDao.activate(target, "member:account-a")
        ArchiveMapper.import(archive, target)
        LearnerOwnershipDao.activate(target, "member:account-b")
        ArchiveMapper.import(archive, target)
        assertEquals(archive.sessions.size.toLong(), countOwner(target, "member:account-a", "sessions"))
        assertEquals(archive.sessions.size.toLong(), countOwner(target, "member:account-b", "sessions"))

        // A's retry must be scoped to A: B's rows neither break the counts
        // (the old whole-table verification wedged BOTH identities forever)
        // nor get cleared.
        val store = LearningDbStore.open(target, files,
            activeIdentity = "member:account-a", clock = { now })
        assertFalse("A's retry must recover instead of wedging", store.jsonMode)
        assertEquals(sha256(bytes), MetaDao.get(target, LearningDbStore.MIGRATION_HASH_KEY))
        assertEquals(archive.sessions.size.toLong(), countOwner(target, "member:account-a", "sessions"))
        assertEquals("the other account's rows are preserved, not cleared",
            archive.sessions.size.toLong(), countOwner(target, "member:account-b", "sessions"))
        assertEquals("the other account's mastery rows are preserved, not cleared", 2L,
            target.query("SELECT COUNT(*) AS n FROM meta WHERE key LIKE 'mastery:member:account-b:%'")
                .single().long("n"))
        assertTrue(files.migrated.exists())
    }

    @Test fun contentFailureRollsBackTheWholeMigrationAndSparesOtherOwnersRows() {
        val archive = ArchiveCodec.decode(fixture("case-basic.json"))
        val completions = archive.completions ?: mutableListOf()
        val first = completions.first()
        completions.add(CompletionMark(first.kind, first.itemID, first.at, 5, "fr"))
        archive.completions = completions
        val collidingBytes = ArchiveCodec.encode(archive).toByteArray()
        val files = TempJsonFiles(tmp.newFolder(), collidingBytes)
        val databasePath = File(tmp.newFolder(), "learning.db").path
        val target = openJvmFileBacked(databasePath)

        // B used the database first (fresh install, no JSON): its session is
        // legitimate data the migration must never touch.
        val bFiles = TempJsonFiles(tmp.newFolder(), null)
        val bStore = LearningDbStore.open(target, bFiles,
            activeIdentity = "member:account-b", clock = { now })
        assertFalse(bStore.jsonMode)
        val bSession = archive.sessions.first().copy(id = "b-owned-session")
        SessionsDao.upsert(bStore.db, bSession)
        FragmentsDao.replaceBySession(bStore.db, bSession.id, bSession.fragments)
        assertEquals(1L, countOwner(target, "member:account-b", "sessions"))

        // A's attempt: count verification fails INSIDE the content
        // transaction (the colliding completion is swallowed by the primary
        // key). The rollback must restore the pre-migration state — the
        // pre-import clear included — leaving B's row and no A residue, with
        // only the pending marker left for A's later retry.
        assertThrows("the colliding completion must abort the migration",
            IllegalStateException::class.java) {
            LearningDbStore.open(target, files,
                activeIdentity = "member:account-a", clock = { now })
        }
        assertEquals("the rollback restores the pre-migration state", 1L,
            countOwner(target, "member:account-b", "sessions"))
        assertEquals("no A residue survives the rollback", 0L,
            countOwner(target, "member:account-a", "sessions"))
        assertEquals("the pending marker names A for its later retry",
            "member:account-a", MetaDao.get(target, LearningDbStore.MIGRATION_PENDING_OWNER_KEY))
        assertNull(MetaDao.get(target, LearningDbStore.MIGRATION_HASH_KEY))
        assertTrue("the colliding JSON stays untouched", files.json.exists())
    }

    @Test fun legacyReadPreservesAtomicBaseBackupAndNewFilesWithoutRecoveryMutation() {
        val dir = tmp.newFolder()
        val base = File(dir, "learning.json")
        val backup = File(dir, "learning.json.bak")
        val pending = File(dir, "learning.json.new")
        val baseBytes = "base bytes".toByteArray()
        val backupBytes = "backup bytes".toByteArray()
        val pendingBytes = "pending bytes".toByteArray()
        base.writeBytes(baseBytes)
        backup.writeBytes(backupBytes)
        pending.writeBytes(pendingBytes)
        val files = AndroidLearningFiles(base)

        assertArrayEquals(backupBytes, files.readLearningJson())
        assertArrayEquals(baseBytes, base.readBytes())
        assertArrayEquals(backupBytes, backup.readBytes())
        assertArrayEquals(pendingBytes, pending.readBytes())

        assertTrue(base.delete())
        assertArrayEquals(backupBytes, files.readLearningJson())
        assertFalse(base.exists())
        assertArrayEquals(backupBytes, backup.readBytes())
        assertArrayEquals(pendingBytes, pending.readBytes())
    }

    @Test fun failedMigrationPreservesAllAtomicRecoveryFiles() {
        val dir = tmp.newFolder()
        val base = File(dir, "learning.json")
        val backup = File(dir, "learning.json.bak")
        val pending = File(dir, "learning.json.new")
        val baseBytes = "incomplete base".toByteArray()
        val backupBytes = "unreadable recovery backup".toByteArray()
        val pendingBytes = "unfinished write".toByteArray()
        base.writeBytes(baseBytes); backup.writeBytes(backupBytes); pending.writeBytes(pendingBytes)

        assertThrows(LearningDbStore.QuarantinedLegacyArchive::class.java) {
            LearningDbStore.open(db, AndroidLearningFiles(base), activeIdentity = "member:account-a")
        }

        assertArrayEquals(baseBytes, base.readBytes())
        assertArrayEquals(backupBytes, backup.readBytes())
        assertArrayEquals(pendingBytes, pending.readBytes())
        assertFalse(File(base.path + ".migrated").exists())
        assertFalse(File(base.path + ".migrated.bak").exists())
    }

    @Test fun successfulMigrationArchivesExactlyTheAtomicBackupThatWasRead() {
        val dir = tmp.newFolder()
        val base = File(dir, "learning.json")
        val backup = File(dir, "learning.json.bak")
        val pending = File(dir, "learning.json.new")
        val baseBytes = ArchiveCodec.encode(Archive(completions = mutableListOf(
            CompletionMark("exercise_set", "incomplete-base", now, 10, "en")))).toByteArray()
        val backupBytes = ArchiveCodec.encode(Archive(completions = mutableListOf(
            CompletionMark("exercise_set", "recovered-backup", now, 90, "en")))).toByteArray()
        val pendingBytes = "unfinished write".toByteArray()
        base.writeBytes(baseBytes); backup.writeBytes(backupBytes); pending.writeBytes(pendingBytes)

        val store = LearningDbStore.open(db, AndroidLearningFiles(base),
            activeIdentity = "member:account-a", clock = { now })

        assertFalse(store.jsonMode)
        assertEquals(listOf("recovered-backup"), CompletionsDao.all(db).map { it.itemID })
        assertArrayEquals(baseBytes, base.readBytes())
        assertArrayEquals(backupBytes, File(base.path + ".migrated.bak").readBytes())
        assertFalse(backup.exists())
        assertArrayEquals(pendingBytes, pending.readBytes())
    }

    @Test fun migratedBackupCollisionDoesNotReplaceEitherRecoveryCopy() {
        val dir = tmp.newFolder()
        val base = File(dir, "learning.json")
        val backup = File(dir, "learning.json.bak")
        val archived = File(dir, "learning.json.migrated.bak")
        val backupBytes = "recovery backup".toByteArray()
        val archivedBytes = "previous migrated backup".toByteArray()
        backup.writeBytes(backupBytes)
        archived.writeBytes(archivedBytes)
        val files = AndroidLearningFiles(base)
        assertArrayEquals(backupBytes, files.readLearningJson())

        assertThrows(IllegalStateException::class.java) { files.renameLearningJsonToMigrated() }

        assertArrayEquals(backupBytes, backup.readBytes())
        assertArrayEquals(archivedBytes, archived.readBytes())
    }

    @Test fun oversizeJsonEngagesFailSafeAndLeavesTheFileUntouched() {
        // one byte past the cap: the store's guard fires before any decode,
        // mirroring LearningRepository's streamed read of the same limit
        val bytes = ByteArray(ArchiveCodec.MAXIMUM_ENCODED_BYTES + 1)
        val files = TempJsonFiles(tmp.newFolder(), bytes)
        val store = storeOver(files)
        assertTrue("an oversized learning.json must fail safe", store.jsonMode)
        assertNotNull(store.jsonModeNotice)
        assertThrows("the database must be unreachable in JSON mode",
            IllegalArgumentException::class.java) { store.db }

        assertTrue("the oversized file is never touched", files.json.exists())
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
        // the single content transaction rolled back completely: no hash, no
        // migrated_at, no rows — the old import-commits-first split used to
        // leave schema_flags, mastery and the archive's rows on disk without
        // the marker (the residue that wedged later attempts), and atomicity
        // means the next launch retries from the same clean state instead
        assertNull(MetaDao.get(db, LearningDbStore.MIGRATION_HASH_KEY))
        assertNull(MetaDao.get(db, LearningDbStore.MIGRATED_AT_KEY))
        assertEquals(0L, count("assessment_tickets"))
        assertEquals("the rollback is total: no schema_flags, no mastery", 0L, count("meta"))
        assertEquals(0L, count("sessions"))
        assertEquals(0L, count("completions"))
    }

    @Test fun retryAfterAnAbortedMigrationClearsResidueAndMigratesAnEvolvedJson() {
        val files = TempJsonFiles(tmp.newFolder(), fixtureBytes("case-basic.json"))

        // launch 1 dies in the two-transaction window: import's rows are
        // committed, the bookkeeping (and its hash) never ran — the gate is
        // open over residue. ArchiveMapper.import IS the migration's content
        // transaction, so this is exactly the state a crash there leaves.
        val crashed = ArchiveCodec.decode(fixture("case-basic.json"))
        ArchiveMapper.import(crashed, db)
        assertEquals(4L, count("expressions"))
        assertNull(MetaDao.get(db, LearningDbStore.MIGRATION_HASH_KEY))

        // the JSON path keeps serving the app, so the archive legitimately
        // evolves: the user deletes a saved expression (skill errors are
        // pruned the same way)
        val evolved = ArchiveCodec.decode(fixture("case-basic.json"))
        evolved.expressions.removeAt(0)
        val evolvedBytes = ArchiveCodec.encode(evolved).toByteArray()
        files.rewriteJson(evolvedBytes)

        // launch 2, same database: the gate is open but the stale rows are
        // still there — the retry must clear them and migrate the EVOLVED
        // counts. Without the pre-import clear, import (insert-only) leaves
        // the residue's fourth expression behind and verifyCounts mismatches
        // on every later launch: the permanent jsonMode wedge.
        val second = storeOver(files)
        assertFalse("a retry over an evolved JSON must succeed, not wedge", second.jsonMode)
        assertNull(second.jsonModeNotice)
        assertEquals("the commit point hashes the EVOLVED bytes", sha256(evolvedBytes),
            MetaDao.get(db, LearningDbStore.MIGRATION_HASH_KEY))
        assertEquals(3L, count("expressions"))
        assertEquals("the residue is gone with the clear, not unioned", 5L, count("completions"))
        assertEquals(3L, count("sessions"))
        assertEquals("2 mastery + migrated_at + hash + schema_flags", 5L, count("meta"))
        val projection = evolved.copy(
            lessons = evolved.lessons.map { it.copy(bars = 0, dueAt = 0.0) }.toMutableList())
        assertEquals("the database holds exactly the evolved archive", projection,
            second.loadInitialArchive())
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

    @Test fun aBrokenDatabaseWithoutJsonRethrowsInsteadOfJsonMode() {
        // the fail-safe contract's boundary: jsonMode promises a learning.json
        // fallback that still owns the data. With NO file and a database that
        // cannot serve, open must RETHROW — a silent jsonMode would strand the
        // session's work with no durable copy (dropped on the next launch).
        val dir = tmp.newFolder()
        val dead = DriverManager.getConnection("jdbc:sqlite:${File(dir, "learning.db").path}")
        dead.close()
        val files = TempJsonFiles(dir, jsonBytes = null)
        assertThrows(Exception::class.java) {
            LearningDbStore.open(JvmSqlDatabase(dead), files) { now }
        }
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

    private fun countOwner(target: SqlDatabase, owner: String, table: String): Long =
        target.query("SELECT COUNT(*) AS n FROM $table WHERE owner_key = ?",
            listOf(owner)).single().long("n")

    /** The restart twin of LearningDb.openJvm for a file-backed path: applies
     *  the canonical schema when the database file is brand new and the
     *  pinned upgrade deltas when it predates LearningDb.VERSION — exactly
     *  what the Helper's onCreate/onUpgrade do. */
    private fun openJvmFileBacked(path: String): SqlDatabase {
        val connection = DriverManager.getConnection("jdbc:sqlite:$path")
        fileConnections += connection
        val target = JvmSqlDatabase(connection)
        target.exec("PRAGMA foreign_keys = ON")
        when (val found = target.query("PRAGMA user_version").single().values.single()) {
            0L -> for (statement in SchemaData.LEARNING_SCHEMA_STATEMENTS) target.exec(statement)
            LearningDb.VERSION.toLong() -> {}
            else -> {
                target.transaction { LearningDb.upgrade(it, (found as Long).toInt()) }
                target.exec("PRAGMA user_version = ${LearningDb.VERSION}")
            }
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
