package com.jkdc.app.data

import com.jkdc.app.core.Archive
import com.jkdc.app.core.CompletionMark
import com.jkdc.app.core.CourseRegistry
import com.jkdc.app.core.ExpressionState
import com.jkdc.app.core.Fragment
import com.jkdc.app.core.LessonState
import com.jkdc.app.core.Preferences
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.Speaker
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BackupManager on real SQLite (sqlite-jdbc), file-backed like the production
 * database: the export→wipe→import roundtrip that must hand back the original
 * archive, the validation gate (corrupt bytes, a future user_version, a
 * database without the canonical tables), the crash-safe stage-then-swap
 * install with its startup adoption, and the per-language delete whose
 * survivors pin the semantics — other languages, legacy NULL-language rows
 * and the languageless queues stay exactly where they were.
 */
class BackupManagerTest {
    private val connections = mutableListOf<Connection>()

    @get:Rule val tmp = TemporaryFolder()

    @After fun closeConnections() {
        connections.forEach { runCatching { it.close() } }
    }

    // -- export / import roundtrip ------------------------------------------

    @Test fun exportWipeImportRoundtripReturnsTheOriginalArchive() {
        val dir = tmp.newFolder()
        val dbFile = File(dir, "learning.db")
        val db = fileBacked(dbFile.path)
        seed(db)
        ExamHistoryDao.start(db, sampleExam("backup-exam", "backup-content", "en"),
            ExamAttemptRecord("backup-attempt", "backup-exam", startedAt = 12.0))
        ExamHistoryDao.saveAnswer(db, "backup-attempt", "q1", "{\"answer\":\"saved\"}", 1, 13.0)
        val original = ArchiveMapper.export(db)

        // The JVM backend's premise for the export checkpoint: xerial uses
        // rollback-journal (delete) mode, so PRAGMA wal_checkpoint(TRUNCATE)
        // is a no-op here — it does real work only on Android's WAL. The
        // roundtrip below is the proof the no-op path still exports whole.
        assertEquals("delete", db.query("PRAGMA journal_mode").single().values.single())

        val output = ByteArrayOutputStream()
        BackupManager.exportTo(db, dbFile, output, opener())
        val backup = output.toByteArray()
        assertTrue("the exported bytes are the database file's bytes",
            backup.contentEquals(dbFile.readBytes()))
        db.close()

        // wipe: the file is gone and a fresh empty schema takes its place
        assertTrue(dbFile.delete())
        val fresh = fileBacked(dbFile.path)
        assertEquals(0L, count(fresh, "sessions"))
        assertEquals(0L, count(fresh, "expressions"))
        fresh.close()

        // restore: install the (already validated) bytes and reopen — the
        // store side of AppViewModel.importDbFrom
        BackupManager.installDatabase(dbFile, backup)
        val restored = fileBacked(dbFile.path)
        assertEquals("the roundtrip hands back the original archive",
            original, ArchiveMapper.export(restored))
        val restoredExam = ExamHistoryDao.load(restored, "backup-attempt")!!
        assertEquals("{\"question\":\"frozen\"}", restoredExam.snapshot.text("content_json"))
        assertEquals("{\"answer\":\"saved\"}", restoredExam.answers.single().text("response_json"))
    }

    @Test fun wholeInstallBackupPreservesEveryOwnerPartitionOnRestore() {
        val dir = tmp.newFolder()
        val dbFile = File(dir, "learning.db")
        val db = fileBacked(dbFile.path)
        val id = "same-session-id"
        fun saveSession(identity: String, title: String) {
            LearnerOwnershipDao.activate(db, identity)
            SessionsDao.upsert(db, SessionRecord(id = id, languageID = "en", startedAt = 1.0,
                title = title))
        }
        saveSession("member:backup-a", "A session")
        saveSession("member:backup-b", "B session")
        saveSession("guest:installation-g", "Guest session")
        LearnerOwnershipDao.activate(db, "member:backup-a")

        val output = ByteArrayOutputStream()
        BackupManager.exportTo(db, dbFile, output, opener())
        val bytes = BackupManager.readValidatedBackup(
            ByteArrayInputStream(output.toByteArray()), tmp.newFolder(), opener())
        db.close()
        assertTrue(dbFile.delete())
        BackupManager.installDatabase(dbFile, bytes)

        val restored = fileBacked(dbFile.path)
        LearnerOwnershipDao.activate(restored, "member:backup-a")
        assertEquals(listOf("guest:installation-g", "member:backup-a", "member:backup-b"),
            restored.query("SELECT owner_key FROM sessions ORDER BY owner_key").map { it.text("owner_key") })
        assertEquals(listOf("A session"), ArchiveMapper.export(restored).sessions.map { it.title })
        LearnerOwnershipDao.activate(restored, "member:backup-b")
        assertEquals(listOf("B session"), ArchiveMapper.export(restored).sessions.map { it.title })
    }

    @Test fun exportIsDeterministicAndItsCopyPassesTheImportGate() {
        val dir = tmp.newFolder()
        val dbFile = File(dir, "learning.db")
        val db = fileBacked(dbFile.path)
        seed(db)

        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()
        BackupManager.exportTo(db, dbFile, first, opener())
        BackupManager.exportTo(db, dbFile, second, opener())
        assertTrue("two exports of an unchanged database are byte-identical",
            first.toByteArray().contentEquals(second.toByteArray()))

        val validated = BackupManager.readValidatedBackup(
            ByteArrayInputStream(first.toByteArray()), tmp.newFolder(), opener())
        assertTrue("the gate hands back exactly the bytes it verified",
            validated.contentEquals(first.toByteArray()))
    }

    // -- import validation rejections ---------------------------------------

    @Test fun importRejectsCorruptBytes() {
        val thrown = assertThrows(InvalidBackup::class.java) {
            BackupManager.readValidatedBackup(
                "esto no es una base de datos sqlite".toByteArray().inputStream(),
                tmp.newFolder(), opener())
        }
        assertEquals(BackupManager.BackupIssue.CORRUPT, thrown.issue)
    }

    @Test fun importRejectsAnOversizedBackup() {
        val thrown = assertThrows(InvalidBackup::class.java) {
            BackupManager.readValidatedBackup(
                ByteArrayInputStream(ByteArray(BackupManager.MAXIMUM_DATABASE_BYTES + 1)),
                tmp.newFolder(), opener())
        }
        assertEquals(BackupManager.BackupIssue.TOO_LARGE, thrown.issue)
    }

    @Test fun importRejectsAFutureSchemaVersion() {
        val file = File(tmp.newFolder(), "future.db")
        val db = fileBacked(file.path)
        db.exec("PRAGMA user_version = ${LearningDb.VERSION + 1}")
        db.close()

        val thrown = assertThrows(InvalidBackup::class.java) {
            BackupManager.readValidatedBackup(file.inputStream(), tmp.newFolder(), opener())
        }
        assertEquals(BackupManager.BackupIssue.FUTURE_VERSION, thrown.issue)
    }

    @Test fun importRejectsADatabaseWithoutTheCanonicalTables() {
        // a well-formed SQLite file at the right user_version that is not a
        // learning database: the table inventory is what catches it
        val file = File(tmp.newFolder(), "empty.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = ${LearningDb.VERSION}") }
        }

        val thrown = assertThrows(InvalidBackup::class.java) {
            BackupManager.readValidatedBackup(file.inputStream(), tmp.newFolder(), opener())
        }
        assertEquals(BackupManager.BackupIssue.MISSING_TABLES, thrown.issue)
    }

    @Test fun importAcceptsAV1BackupWhichMigratesOnTheNextOpen() {
        // the shipped-release backup: schema v1 with a real transcript, no FTS
        val v1File = File(tmp.newFolder(), "v1.db")
        val v1 = LearningDb.openJvmV1(DriverManager.getConnection("jdbc:sqlite:${v1File.path}"))
        connections += (v1 as JvmSqlDatabase).connection
        v1.insert("sessions", mapOf("id" to "s1", "language_id" to "es", "started_at" to 1.0,
            "title" to "Historia"))
        v1.insert("fragments", mapOf("id" to "f1", "session_id" to "s1", "sort_index" to 0,
            "speaker" to "user", "text" to "palabra buscable", "revision" to 0,
            "start_ms" to 0L, "end_ms" to 1L, "received_at" to 1.0))
        assertEquals(1L, v1.query("PRAGMA user_version").single().values.single())
        assertThrows(SQLException::class.java) {
            v1.query("SELECT rowid FROM fragments_fts WHERE fragments_fts MATCH 'buscable'")
        }

        // the gate accepts v1 (a v1 backup migrates on open) and hands the
        // exact bytes back
        val bytes = v1File.readBytes()
        val validated = BackupManager.readValidatedBackup(
            ByteArrayInputStream(bytes), tmp.newFolder(), opener())
        assertTrue(validated.contentEquals(bytes))

        // install + reopen: the open migrates in place and the transcript the
        // v1 file already held is searchable through the FTS index
        val live = File(tmp.newFolder(), "learning.db")
        BackupManager.installDatabase(live, validated)
        val reopened = fileBacked(live.path)
        assertEquals(LearningDb.VERSION.toLong(),
            reopened.query("PRAGMA user_version").single().values.single())
        assertEquals(listOf("s1"), SearchDao.search(reopened, "buscable", "es").map { it.session.id })
    }

    @Test fun importAcceptsAV2BackupWithoutExamTables() {
        val file = File(tmp.newFolder(), "v2.db")
        val db = LearningDb.openJvmV1(DriverManager.getConnection("jdbc:sqlite:${file.path}"))
        connections += (db as JvmSqlDatabase).connection
        db.transaction { tx ->
            LearningDb.upgrade(tx, 1)
            tx.exec("PRAGMA user_version = 2")
        }
        val bytes = file.readBytes()
        val validated = BackupManager.readValidatedBackup(
            ByteArrayInputStream(bytes), tmp.newFolder(), opener())
        assertTrue(validated.contentEquals(bytes))
    }

    // -- per-language delete --------------------------------------------------

    @Test fun deleteByLanguageKeepsOtherLanguagesLegacyRowsAndQueues() {
        val db = LearningDb.openJvm()
        connections += (db as JvmSqlDatabase).connection
        val seeded = seed(db)
        ExamHistoryDao.start(db, sampleExam("en-exam", "en-content", "en"),
            ExamAttemptRecord("en-attempt", "en-exam", startedAt = 10.0))
        ExamHistoryDao.start(db, sampleExam("es-exam", "es-content", "es"),
            ExamAttemptRecord("es-attempt", "es-exam", startedAt = 11.0))
        ExamHistoryDao.saveAnswer(db, "en-attempt", "q1", "{\"answer\":\"private\"}", 0, 12.0)

        val deletion = BackupManager.deleteLearningDataForLanguage(db, "en")
        assertEquals(2L, deletion.sessions)
        assertEquals(3L, deletion.fragments)
        assertEquals(1L, deletion.completions)
        assertEquals(1L, deletion.expressions)
        assertEquals(2L, deletion.lessonStates)
        assertEquals(1L, deletion.tickets)
        assertEquals(1L, deletion.examAttempts)
        assertEquals(0L, deletion.examSnapshots)
        assertEquals(1L, count(db, "exam_attempts"))
        assertEquals(2L, count(db, "exam_snapshots"))
        assertEquals("es-attempt", db.query("SELECT attempt_id FROM exam_attempts").single().text("attempt_id"))

        // the survivors, row by row
        assertEquals(listOf("s-es-1"), db.query("SELECT id FROM sessions ORDER BY rowid").map { it.text("id") })
        assertEquals("es fragments rode along, the en ones went with their sessions",
            2L, count(db, "fragments"))
        assertEquals(listOf("s-es-1", "orphan"),
            db.query("SELECT session_id FROM assessment_tickets ORDER BY rowid").map { it.text("session_id") })
        assertEquals("the usage outbox carries no language and is never touched",
            2L, count(db, "usage_outbox"))
        assertEquals("legacy NULL-language completions are global activity and stay",
            listOf("es-foundations-u1-ser", "legacy-thing"),
            CompletionsDao.all(db).map { it.itemID })
        assertEquals("legacy NULL-language expressions are visible everywhere and stay",
            listOf("nice to meet you", "ciao"),
            ExpressionsDao.all(db).map { it.text })
        assertEquals("lesson_state follows the language prefix; other languages and non-prefixed ids stay",
            listOf("es-foundations-u1-ser", "de-foundations-u1-sein", "zz-legacy-lesson"),
            LessonStateDao.all(db).map { it.lessonID })

        // export(DB) == the archive with the SAME rows purged — the invariant
        // AppViewModel's working-copy purge must reproduce
        val expected = seeded.copy(
            sessions = seeded.sessions.filterNot { it.languageID == "en" }.toMutableList(),
            completions = seeded.completions.orEmpty()
                .filterNot { it.languageID == "en" }.takeIf { it.isNotEmpty() }?.toMutableList(),
            expressions = seeded.expressions.filterNot { it.languageID == "en" }.toMutableList(),
            lessons = seeded.lessons.filterNot { it.lessonID.startsWith("en-") }.toMutableList(),
        )
        assertEquals(expected, ArchiveMapper.export(db))
    }

    @Test fun deleteByLanguageIsIdempotentAndSafeForLanguagesWithoutData() {
        val db = LearningDb.openJvm()
        connections += (db as JvmSqlDatabase).connection
        seed(db)
        BackupManager.deleteLearningDataForLanguage(db, "en")
        val afterFirst = tableCounts(db)

        val again = BackupManager.deleteLearningDataForLanguage(db, "en")
        assertEquals("every count is zero on the second pass",
            LanguageDeletion(0, 0, 0, 0, 0, 0), again)

        val noData = BackupManager.deleteLearningDataForLanguage(db, "fr")
        assertEquals("a language with no rows deletes nothing",
            LanguageDeletion(0, 0, 0, 0, 0, 0), noData)
        assertEquals("nothing moved", afterFirst, tableCounts(db))
    }

    @Test fun deleteByLanguageSurvivesAnExportRoundtrip() {
        // the composite the settings flow produces: delete, then back up what
        // is left, and the backup must be a valid learning database
        val dir = tmp.newFolder()
        val dbFile = File(dir, "learning.db")
        val db = fileBacked(dbFile.path)
        seed(db)
        BackupManager.deleteLearningDataForLanguage(db, "de")
        val remaining = ArchiveMapper.export(db)

        val output = ByteArrayOutputStream()
        BackupManager.exportTo(db, dbFile, output, opener())
        val validated = BackupManager.readValidatedBackup(
            ByteArrayInputStream(output.toByteArray()), tmp.newFolder(), opener())

        db.close()
        assertTrue(dbFile.delete())
        BackupManager.installDatabase(dbFile, validated)
        val reopened = fileBacked(dbFile.path)
        assertEquals(remaining, ArchiveMapper.export(reopened))
        assertEquals("the de seed has no sessions, so none went",
            3L, count(reopened, "sessions"))
        assertEquals("de lesson_state rows went with the prefix",
            0, LessonStateDao.all(reopened).count { it.lessonID.startsWith("de-") })
    }

    // -- crash-safe install (stage-then-swap) ---------------------------------

    @Test fun stageAndSwapKeepsTheLiveFileUntilTheSwap() {
        val dir = tmp.newFolder()
        val dbFile = File(dir, "learning.db")
        val db = fileBacked(dbFile.path)
        seed(db)
        val before = dbFile.readBytes()
        db.close()

        // the incoming backup: a verified export of a second, empty database
        val freshFile = File(dir, "fresh.db")
        val fresh = fileBacked(freshFile.path)
        val outgoing = ByteArrayOutputStream()
        BackupManager.exportTo(fresh, freshFile, outgoing, opener())
        fresh.close()
        val incoming = outgoing.toByteArray()

        val staged = File(dbFile.path + ".install-tmp")
        BackupManager.stageDatabase(staged, incoming)
        assertTrue("the staged bytes exist beside the live file", staged.exists())
        assertTrue("the live file is untouched until the swap",
            dbFile.readBytes().contentEquals(before))

        BackupManager.installDatabase(staged, dbFile)
        assertFalse("the swap consumes the staged file", staged.exists())
        assertTrue("the live file now holds the incoming bytes",
            dbFile.readBytes().contentEquals(incoming))
    }

    @Test fun startupAdoptionRecoversAStrandedPreimportAfterACrashInTheRestoreWindow() {
        val dir = tmp.newFolder()
        val dbFile = File(dir, "learning.db")
        val db = fileBacked(dbFile.path)
        seed(db)
        val original = ArchiveMapper.export(db)
        db.close()

        // the exact crash signature: the live file was renamed aside and the
        // process died before the staged bytes were swapped in
        val keep = File(dbFile.path + ".preimport")
        assertTrue(dbFile.renameTo(keep))
        assertFalse(dbFile.exists())

        assertTrue("the stranded previous database is adopted back",
            LearningDbStore.adoptPreimportDatabase(dbFile))
        assertTrue(dbFile.exists())
        assertFalse("adoption is a rename, not a copy", keep.exists())
        assertEquals("the learner keeps the data they had",
            original, ArchiveMapper.export(fileBacked(dbFile.path)))

        // both files missing: a fresh install, nothing to adopt
        assertFalse(LearningDbStore.adoptPreimportDatabase(File(dir, "absent.db")))

        // a completed import (both files exist): the installed bytes win and
        // a leftover keep-copy is never touched by adoption
        val live = fileBacked(dbFile.path)
        LessonStateDao.upsert(live, LessonState("en-foundations-u1-to-be", attempts = 9, lastSeen = 1.0))
        val staleKeep = File(dbFile.path + ".preimport").apply { writeBytes(ByteArray(1)) }
        assertFalse("adoption must not run when the live file exists",
            LearningDbStore.adoptPreimportDatabase(dbFile))
        assertTrue("the stale keep-copy is left alone", staleKeep.exists())
    }

    // -- LIKE-wildcard defense in the per-language delete ----------------------

    @Test fun deleteByLanguageEscapesLikeWildcardsInThePrefix() {
        val db = LearningDb.openJvm()
        connections += (db as JvmSqlDatabase).connection
        // an unescaped '_' is a single-char wildcard: a pattern built from
        // the id "zz_z" would also match "zzaz-…" and delete rows that are
        // not the language's — the ESCAPE clause must keep it literal
        LessonStateDao.upsert(db, LessonState("zz_z-lesson", attempts = 1, lastSeen = 10.0))
        LessonStateDao.upsert(db, LessonState("zzaz-lesson", attempts = 2, lastSeen = 20.0))
        assertEquals("zz\\_z-%", BackupManager.lessonStatePattern("zz_z"))

        BackupManager.deleteLearningDataForLanguage(db, "zz_z")
        assertEquals(listOf("zzaz-lesson"), LessonStateDao.all(db).map { it.lessonID })
    }

    // -- lesson_state prefix derivation ---------------------------------------

    @Test fun lessonStatePrefixIsDerivedAndVerifiedAgainstTheCourseRegistry() {
        assertEquals("en-%", BackupManager.lessonStatePattern("en"))
        assertEquals("es-%", BackupManager.lessonStatePattern("es"))
        // the premise itself, against the real generated content: every
        // authored lesson id starts with its language id (shared/courses)
        CourseRegistry.all.forEach { course ->
            course.units.flatMap { it.lessons }.forEach { lesson ->
                assertTrue("${lesson.id} must start with \"${course.languageID}-\"",
                    lesson.id.startsWith("${course.languageID}-"))
            }
        }
        // an id with no registry courses falls back to the explicit prefix
        assertEquals("zz-%", BackupManager.lessonStatePattern("zz"))
    }

    // -- helpers ---------------------------------------------------------------

    private fun fileBacked(path: String): SqlDatabase {
        val connection = DriverManager.getConnection("jdbc:sqlite:$path")
        connections += connection
        val target = JvmSqlDatabase(connection)
        target.exec("PRAGMA foreign_keys = ON")
        when (val found = target.query("PRAGMA user_version").single().values.single()) {
            0L -> for (statement in SchemaData.LEARNING_SCHEMA_STATEMENTS) target.exec(statement)
            // already current
            LearningDb.VERSION.toLong() -> {}
            // what the Helper's onUpgrade does on the first open after an
            // import of an older (v1) backup
            else -> {
                target.transaction { LearningDb.upgrade(it, (found as Long).toInt()) }
                target.exec("PRAGMA user_version = ${LearningDb.VERSION}")
            }
        }
        return target
    }

    /** Verification opener twin of the Android wiring: a fresh JDBC
        connection over the candidate file, closed by BackupManager. */
    private fun opener() = VerificationOpener { file ->
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
        connections += connection
        val target = JvmSqlDatabase(connection)
        target.exec("PRAGMA foreign_keys = ON")
        VerificationHandle(target) { connection.close() }
    }

    /** One deterministic archive covering three languages plus the legacy
        NULL-language rows the delete must conserve. */
    private fun seededArchive(): Archive {
        fun session(id: String, languageID: String, fragments: Int) = SessionRecord(
            id = id, languageID = languageID, startedAt = 800_000_000.0, endedAt = 800_000_100.0,
            endReason = "Completed",
        ).apply {
            repeat(fragments) { i ->
                append(Fragment(id = "$id-f$i", speaker = Speaker.user, text = "text $i",
                    startMS = i * 1000, endMS = i * 1000 + 500, receivedAt = 800_000_001.0 + i))
            }
        }
        return Archive(
            schemaVersion = 2,
            sessions = mutableListOf(
                session("s-en-1", "en", 2),
                session("s-en-2", "en", 1),
                session("s-es-1", "es", 2),
            ),
            preferences = Preferences(learningLanguageID = "en"),
            lessons = mutableListOf(
                LessonState("en-foundations-u1-to-be", attempts = 2, lastSeen = 100.0),
                LessonState("en-foundations-u1-questions", attempts = 1, lastSeen = 90.0),
                LessonState("es-foundations-u1-ser", attempts = 3, lastSeen = 80.0),
                LessonState("de-foundations-u1-sein", attempts = 1, lastSeen = 70.0),
                LessonState("zz-legacy-lesson", attempts = 4, lastSeen = 60.0),
            ),
            expressions = mutableListOf(
                ExpressionState("to be or not to be", "ser o no ser", "lesson",
                    lessonID = "en-foundations-u1-to-be", lastSeen = 100.0, dueAt = 200.0,
                    languageID = "en"),
                ExpressionState("nice to meet you", "encantado de conocerte", "lesson",
                    lessonID = "es-foundations-u1-ser", lastSeen = 90.0, dueAt = 190.0,
                    languageID = "es"),
                // legacy: NULL language, visible and adoptable in every language
                ExpressionState("ciao", "hola", "lookup", lastSeen = 80.0, dueAt = 180.0,
                    languageID = null),
            ),
            completions = mutableListOf(
                CompletionMark("lesson", "en-foundations-u1-to-be", at = 100.0, bestScore = 90,
                    languageID = "en"),
                CompletionMark("lesson", "es-foundations-u1-ser", at = 99.0, bestScore = 80,
                    languageID = "es"),
                CompletionMark("recall", "legacy-thing", at = 98.0, bestScore = 0,
                    languageID = null),
            ),
        )
    }

    /** Imports [seededArchive], then adds the languageless platform queues:
        retry-ledger rows (including an orphan whose session row does not
        exist — the delete's LEFT-JOIN semantics must keep it) and two
        usage-outbox events. */
    private fun seed(db: SqlDatabase): Archive {
        val archive = seededArchive()
        ArchiveMapper.import(archive, db)
        AssessmentTicketsDao.upsert(db, AssessmentTicketRow("s-en-1", attempts = 1, lastAttemptAt = 100.5))
        AssessmentTicketsDao.upsert(db, AssessmentTicketRow("s-es-1", attempts = 0, lastAttemptAt = null))
        AssessmentTicketsDao.upsert(db, AssessmentTicketRow("orphan", attempts = 2, lastAttemptAt = 5.0))
        UsageOutboxDao.enqueue(db, "app_open", 1_700_000_000_000)
        UsageOutboxDao.enqueue(db, "lesson_completed", 1_700_000_001_000)
        return archive
    }

    private fun sampleExam(examID: String, contentID: String, language: String) = ExamSnapshotRecord(
        examID, contentID, "hash-$contentID", 1, 1, 1, "bundled", language, "B1", 202641,
        "{\"question\":\"frozen\"}", 1.0)

    private fun count(target: SqlDatabase, table: String): Long =
        target.query("SELECT COUNT(*) AS n FROM $table").single().long("n")

    private fun tableCounts(db: SqlDatabase) =
        BackupManager.CANONICAL_TABLES.associateWith { count(db, it) }
}
