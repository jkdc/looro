package com.jkdc.app.data

import com.jkdc.app.core.CourseRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Raised when an incoming database backup fails validation. The message is
 * the user-facing notice (an inline literal, like LearningDbStore's
 * jsonModeNotice — the settings UI task owns extracting these to resources),
 * so [AppViewModel] can surface it verbatim.
 */
class InvalidBackup(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A short-lived [SqlDatabase] over an arbitrary SQLite FILE — never the
 * learner's live learning.db — plus the close handle [BackupManager] needs
 * to release it before it declares the copy valid (SqlDatabase has no close
 * of its own by design; see [VerificationOpener]).
 */
class VerificationHandle(val db: SqlDatabase, private val onClose: () -> Unit) : AutoCloseable {
    override fun close() = onClose()
}

/**
 * Platform seam for opening a candidate database file for verification: JVM
 * tests open it through sqlite-jdbc, the Android wiring through
 * SQLiteDatabase.openDatabase. BackupManager always [VerificationHandle.close]s
 * what it opens.
 */
fun interface VerificationOpener {
    fun open(file: File): VerificationHandle
}

/** Per-language delete row counts ([SqlDatabase.update] reports -1 per row on
    Android — JVM-test convenience, never cross-platform state). */
data class LanguageDeletion(
    val sessions: Long,
    val fragments: Long,
    val completions: Long,
    val expressions: Long,
    val lessonStates: Long,
    val tickets: Long,
)

/**
 * Phase-D database backup/restore for the learning database (schema v1).
 * File-level, unlike the JSON archive backup: the .db bytes travel whole, so
 * everything the JSON backup never carried (the usage_outbox queue, the
 * platform's assessment_tickets rows, the mastery meta rows) moves with it.
 *
 * [exportTo] never trusts the copy it made: after a WAL TRUNCATE checkpoint
 * on the LIVE database (so the main file holds every committed page — see
 * the WAL note below), it copies the file bytes, writes the copy to a temp
 * file, opens THAT copy through a [VerificationOpener] and demands
 *
 *   - `PRAGMA integrity_check` → exactly one row, "ok"
 *   - `PRAGMA foreign_key_check` → no rows
 *   - `PRAGMA user_version` == [LearningDb.VERSION] (1 — a frozen schema; a
 *     backup from a newer schema must never be applied by an older app)
 *   - the ten canonical tables of shared/db/schema.sql present
 *
 * and only then writes the bytes to the destination stream. The verification
 * proves the copy's INTEGRITY, not its freshness: freshness is the
 * checkpoint's job, so the (busy, log, checkpointed) row is checked and a
 * checkpoint that could not finish (busy != 0 — a concurrent reader or
 * writer held the WAL) fails the export loudly instead of shipping a stale
 * backup that would still verify clean.
 *
 * WAL note: Android's framework SQLite opens databases in WAL mode by
 * default (API 28+), where a plain file copy would silently miss commits
 * still sitting in learning.db-wal — hence the checkpoint first. The JVM
 * test backend (xerial sqlite-jdbc) uses rollback-journal (delete) mode by
 * default, so `PRAGMA wal_checkpoint(TRUNCATE)` is a no-op there (it
 * returns busy = 0, log = -1, checkpointed = -1 and touches nothing); the
 * file copy is already complete on that backend. Both paths verified by
 * BackupManagerTest.
 *
 * [readValidatedBackup] is the import gate: bounded read, then the SAME
 * verification over the incoming bytes (through a throwaway temp copy), so
 * a corrupt file, a future-schema backup or a database without the learning
 * tables is rejected BEFORE the caller touches the live database. The
 * caller (AppViewModel) owns the live-file dance: quiesce writes, close the
 * store ([SqlDatabase.close]), [stageDatabase] the new bytes beside the
 * live file, set the previous file aside, [installDatabase] the staged file
 * over it (same-directory rename, atomic on POSIX), reopen the store — and
 * put the previous file back if anything in that sequence fails (never by
 * deleting: the keep-copy's rename atomically replaces whatever is in
 * place). The current database is never destroyed unless validation fully
 * passed, and a crash between the two renames strands it as
 * learning.db.preimport, which LearningDbStore adopts back at startup.
 *
 * [deleteLearningDataForLanguage] implements the per-language delete in ONE
 * transaction:
 *
 *   - assessment_tickets of the language's sessions (selected BEFORE the
 *     sessions go — the table has no FK, so this is a LEFT-JOIN-style
 *     delete; orphan tickets whose session row is already gone survive)
 *   - sessions WHERE language_id = ? — fragments follow by the schema's
 *     only foreign key (fragments.session_id ON DELETE CASCADE)
 *   - completions WHERE language_id = ? — legacy NULL-language marks are
 *     global activity and are CONSERVED
 *   - expressions WHERE language_id = ? — legacy NULL-language entries are
 *     visible in every language and are CONSERVED
 *   - lesson_state WHERE lesson_id LIKE '<languageID>-%' — lesson_state has
 *     no language column; the prefix is derived and verified against
 *     [CourseRegistry] in [lessonStatePattern]
 *   - usage_outbox is NOT touched: usage events carry no language (they are
 *     content-free app telemetry), so there is nothing language-scoped to
 *     delete; the queue survives per-language deletes by design.
 *
 * Mastery meta rows (meta 'mastery:*') and skill_errors stay too: they key
 * on language-neutral skill-catalog ids, so deleting a language's rows would
 * corrupt a projection the learner still owns. Callers keep the
 * export(DB) == archive invariant by purging the exact same rows from the
 * working copy archive.
 */
object BackupManager {
    const val MAXIMUM_DATABASE_BYTES: Int = 64 * 1024 * 1024

    /** The ten tables shared/db/schema.sql creates, pinned by
        scripts/check_db_schema.py. A backup missing any of them is not a
        learning database. */
    val CANONICAL_TABLES: List<String> = listOf(
        "meta", "preferences", "sessions", "fragments", "expressions",
        "lesson_state", "completions", "skill_errors", "usage_outbox",
        "assessment_tickets",
    )

    // User-facing rejection notices (inline literals; the settings-UI task
    // extracts them to string resources).
    const val NOTICE_CORRUPT = "El archivo no es una base de datos de aprendizaje válida."
    const val NOTICE_FUTURE_VERSION =
        "El respaldo pertenece a una versión más nueva de la app. Actualiza la aplicación e inténtalo de nuevo."
    const val NOTICE_MISSING_TABLES = "Al respaldo le faltan tablas de la base de datos de aprendizaje."
    const val NOTICE_EMPTY = "El archivo de respaldo está vacío."

    /**
     * Copies the live [databaseFile] behind [database] to [output], verbatim
     * and verified — see the class KDoc. The caller owns draining pending
     * writes first (AppViewModel persists through a FIFO channel) and closing
     * [output]; the verification temp file lives beside [databaseFile] and is
     * always deleted.
     */
    fun exportTo(database: SqlDatabase, databaseFile: File, output: OutputStream,
                 openForVerify: VerificationOpener) {
        // Framework SQLite runs WAL by default: fold learning.db-wal into the
        // main file so the bytes below are the whole story. A no-op in
        // rollback-journal mode (the JVM test backend: busy = 0, log = -1,
        // checkpointed = -1). busy != 0 means the truncate could not finish
        // (a concurrent reader/writer held the WAL) — the copy would be VALID
        // (verification catches integrity) but possibly STALE (it cannot
        // catch freshness), so the export fails loudly instead. Reading the
        // row through query() keeps the call portable across both backends.
        val checkpoint = database.query("PRAGMA wal_checkpoint(TRUNCATE)")
        if (checkpoint.isNotEmpty() && checkpoint.first()["busy"] != 0L) {
            error("learning.db WAL checkpoint did not complete (busy); " +
                "the export would miss the newest commits — retry with no pending writes")
        }
        val bytes = databaseFile.readBytes()
        val copy = File(databaseFile.parentFile, databaseFile.name + ".backup-verify")
        try {
            copy.writeBytes(bytes)
            verify(copy, openForVerify)
        } finally {
            copy.delete()
        }
        output.write(bytes)
        output.flush()
    }

    /**
     * The import gate: reads at most [MAXIMUM_DATABASE_BYTES] from [input],
     * verifies the bytes as a learning database through a throwaway copy in
     * [workDir] (same checks as [exportTo]), and only then hands them back
     * for [installDatabase]. Throws [InvalidBackup] with the user-facing
     * notice otherwise — the live database is not even open at this point.
     */
    fun readValidatedBackup(input: InputStream, workDir: File,
                            openForVerify: VerificationOpener): ByteArray {
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAXIMUM_DATABASE_BYTES) {
                    throw InvalidBackup("El respaldo de la base de datos es demasiado grande.")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.isEmpty()) throw InvalidBackup(NOTICE_EMPTY)
        val copy = File.createTempFile("backup-import-verify", ".db", workDir)
        try {
            copy.writeBytes(bytes)
            verify(copy, openForVerify)
        } finally {
            copy.delete()
        }
        return bytes
    }

    /** The verified backup's validation half, shared by export and import. */
    private fun verify(candidate: File, openForVerify: VerificationOpener) {
        val handle = try {
            openForVerify.open(candidate)
        } catch (failure: Exception) {
            throw InvalidBackup(NOTICE_CORRUPT, failure)
        }
        try {
            val db = handle.db
            val integrity = try {
                db.query("PRAGMA integrity_check")
            } catch (failure: Exception) {
                throw InvalidBackup(NOTICE_CORRUPT, failure)
            }
            if (integrity.size != 1 || integrity.single().values.single() != "ok") {
                throw InvalidBackup(NOTICE_CORRUPT)
            }
            val version = try {
                db.query("PRAGMA user_version").single().values.single() as Long
            } catch (failure: Exception) {
                throw InvalidBackup(NOTICE_CORRUPT, failure)
            }
            if (version > LearningDb.VERSION) throw InvalidBackup(NOTICE_FUTURE_VERSION)
            if (version != LearningDb.VERSION.toLong()) throw InvalidBackup(NOTICE_CORRUPT)
            if (db.query("PRAGMA foreign_key_check").isNotEmpty()) {
                throw InvalidBackup(NOTICE_CORRUPT)
            }
            val tables = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'").map { it.text("name") }
            if (!CANONICAL_TABLES.all { it in tables }) throw InvalidBackup(NOTICE_MISSING_TABLES)
        } finally {
            handle.close()
        }
    }

    /**
     * Step one of the crash-safe install: writes [bytes] to [staged]
     * (target.path + ".install-tmp") WITHOUT touching the live database file.
     * Between [stageDatabase] and [installDatabase] BOTH files exist, so a
     * process death in that window leaves the previous database intact (and
     * a leftover staged file that the next install overwrites). Callers
     * clean a failed stage's leftover up themselves.
     */
    fun stageDatabase(staged: File, bytes: ByteArray) {
        staged.writeBytes(bytes)
    }

    /**
     * Step two: renames the staged bytes over [target] (atomic on POSIX —
     * readers see the whole old file or the whole new one). Callers set the
     * previous file aside FIRST (rename it away) so a failure here leaves
     * the old bytes recoverable as learning.db.preimport — which
     * LearningDbStore adopts back at startup after a crash.
     */
    fun installDatabase(staged: File, target: File) {
        if (!staged.renameTo(target)) error("could not move the staged backup into place at $target")
    }

    /** Convenience two-step install in one call (stage + swap). */
    fun installDatabase(target: File, bytes: ByteArray) {
        val staged = File(target.path + ".install-tmp")
        stageDatabase(staged, bytes)
        installDatabase(staged, target)
    }

    /**
     * Deletes every row of [languageID]'s learning data in ONE transaction —
     * the exact semantics documented in the class KDoc. Returns the per-table
     * row counts (JVM; -1 fields on Android where [SqlDatabase.update] cannot
     * report counts).
     */
    fun deleteLearningDataForLanguage(db: SqlDatabase, languageID: String): LanguageDeletion {
        val lessonPattern = lessonStatePattern(languageID)
        return db.transaction { tx ->
            val fragments = tx.query(
                "SELECT COUNT(*) AS n FROM fragments WHERE session_id IN " +
                    "(SELECT id FROM sessions WHERE language_id = ?)", listOf(languageID))
                .single().long("n")
            // Tickets first: after the sessions go, the subselect finds nothing.
            val tickets = tx.update(
                "DELETE FROM assessment_tickets WHERE session_id IN " +
                    "(SELECT id FROM sessions WHERE language_id = ?)", listOf(languageID))
            val sessions = tx.update("DELETE FROM sessions WHERE language_id = ?", listOf(languageID))
            val completions = tx.update("DELETE FROM completions WHERE language_id = ?",
                listOf(languageID))
            val expressions = tx.update("DELETE FROM expressions WHERE language_id = ?",
                listOf(languageID))
            val lessonStates = tx.update(
                "DELETE FROM lesson_state WHERE lesson_id LIKE ? ESCAPE '\\'",
                listOf(lessonPattern))
            LanguageDeletion(sessions, fragments, completions, expressions, lessonStates, tickets)
            // usage_outbox, meta, preferences, skill_errors: deliberately
            // untouched — see the class KDoc.
        }
    }

    /**
     * The lesson_state selector for [languageID]: '<languageID>-%', with
     * LIKE wildcards in the id escaped (defense in depth — registry ids are
     * plain lowercase slugs today, so `%`/`_` never occur, but an unescaped
     * `_` would silently over-match other languages' lesson rows). The
     * prefix itself is derived, never hardcoded — and verified at runtime
     * against the actual [CourseRegistry] content, exactly as the
     * shared/courses JSON pins it today: every authored lesson id begins
     * with its language id plus a dash (en-foundations-u1-to-be,
     * en-care-u1-appointment, es-work-u1-reuniones, de-foundations-u1-sein),
     * so the language prefix alone selects exactly this language's
     * lesson_state rows — including stale rows left by courses since removed
     * from the catalog, which per-course prefixes would miss. A language
     * with no registry courses falls back to the plain language prefix (the
     * brief's explicit-arg shape). If a future content change breaks the
     * invariant, the require fails loudly and the delete aborts instead of
     * deleting the wrong rows. Case sensitivity: SQL LIKE is
     * ASCII-case-insensitive while the working-copy purge matches with
     * `startsWith` — acceptable divergence because registry ids (and every
     * authored lesson id) are lowercase-only, pinned by [CourseRegistry].
     */
    internal fun lessonStatePattern(languageID: String): String {
        val lessonIDs = CourseRegistry.forLanguage(languageID)
            .flatMap { course -> course.units.flatMap { it.lessons } }.map { it.id }
        require(lessonIDs.all { it.startsWith("$languageID-") }) {
            "lesson ids no longer start with \"$languageID-\"; " +
                "the per-language lesson_state delete prefix is wrong"
        }
        return escapeLike("$languageID-") + "%"
    }

    /** Escapes the LIKE wildcards for the `ESCAPE '\'` clause on the
        per-language delete. Backslash itself is escaped first. */
    private fun escapeLike(text: String): String = buildString {
        for (character in text) {
            if (character == '\\' || character == '%' || character == '_') append('\\')
            append(character)
        }
    }
}
