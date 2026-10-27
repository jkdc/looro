package com.jkdc.app.data

import android.content.Context
import com.jkdc.app.core.Archive
import com.jkdc.app.core.ArchiveCodec
import com.jkdc.app.core.FinalAssessmentRecovery
import com.jkdc.app.core.FinalAssessmentTicket
import com.jkdc.app.core.nowSeconds
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * LearningDbStore — the learner's runtime persistence facade over the learning
 * database (phase C). It takes over LearningRepository's RUNTIME role (open the
 * durable store, hand out the working archive, recover sessions the app died
 * inside); LearningRepository itself stays untouched as the phase-D legacy
 * import/backup path.
 *
 * Startup contract (the sequence Task 2 wires into AppViewModel):
 *
 *  1. `LearningDbStore.open(context)` opens the database and performs the
 *     ONE-WAY learning.json migration when it is due (below).
 *  2. `loadInitialArchive()` exports the database into the working
 *     `mutableStateOf` archive (one-shot at startup — the phase-C plan pins
 *     ArchiveMapper.export as allowed exactly there).
 *  3. `recoverUnfinishedSessions(archive)` mirrors LearningRepository.load's
 *     recovery over that exported archive: sessions still open when the app
 *     died get endedAt/endReason written through SessionsDao and a final-
 *     assessment retry ticket through AssessmentTicketsDao.
 *
 * One-way migration (runs inside [open] when meta 'migration_hash' is absent
 * and learning.json exists):
 *
 *  - read the JSON bytes → [ArchiveCodec.decode] → decode the
 *    `androidFinalAssessments` retry ledger with LearningRepository.load's
 *    exact validation — all before any write;
 *  - retry hygiene: with the gate open, any learning rows already in the
 *    database are residue of an earlier ABORTED attempt (import commits
 *    before the bookkeeping does), and [ArchiveMapper.import] is insert-only
 *    — it never deletes — so they are CLEARED first, in their own
 *    transaction: the eight learning tables + the mastery meta rows
 *    (preferences and the non-mastery meta rows are not learning data and
 *    stay untouched). This keeps a retry count-stable in ALL cases, including
 *    a JSON that legitimately evolved between attempts (the user deleted
 *    expressions, pruned skill errors while the app served the JSON path). A
 *    committed migration — hash present — never reaches this and is never
 *    cleared;
 *  - the archive itself lands through [ArchiveMapper.import] (atomic by
 *    construction — never [ArchiveMerger], which is restore-only; its single
 *    transaction is the migration's only content transaction, and the
 *    transaction wrapper does not nest, so the pre-import clear and the
 *    bookkeeping each get their own);
 *  - one bookkeeping transaction: the retry-ledger rows + COUNT VERIFICATION
 *    against the decoded source (sessions, fragments, lessons, expressions,
 *    completions, skill errors, mastery meta rows, schema flags, tickets,
 *    outbox) + meta 'migrated_at' (Apple-epoch seconds) + meta
 *    'migration_hash' (SHA-256 of the raw JSON bytes) written LAST — the
 *    hash's presence is the commit point the gate reads. Any count mismatch
 *    or any failure rolls the bookkeeping back;
 *  - only after that commit: rename learning.json → learning.json.migrated.
 *    The JSON is never deleted; the rename is post-commit cleanup, so a
 *    failure there (or a crash in the window) leaves the file in place where
 *    the hash gate simply ignores it on every later launch.
 *
 * Crash windows and aborts are all safe. Before the hash is committed the
 * JSON stays the authority: an abort leaves the store in JSON mode, and the
 * residue an aborted attempt leaves in the database (its import commits
 * before the bookkeeping) is unreachable without migration_hash — and is
 * cleared before the next attempt. That clear is what makes a retry
 * count-stable against an EVOLVED JSON: while the app served the JSON path
 * the archive could legitimately change (expressions deleted, skill errors
 * pruned), and a plain re-import over the stale rows would mismatch its
 * counts on every later launch — the wedge. Clearing first lets the retry
 * succeed with the evolved counts (pinned by LearningDbStoreTest's retry
 * regression). A crash after the commit leaves the hash, so the next launch
 * is in database mode and never re-migrates — a learning.json that
 * reappears later is IGNORED, not merged again.
 *
 * Runtime fail-safe: when opening the database or migrating learning.json
 * fails for an active learner, [open] quarantines the ownerless JSON rather
 * than serving it to that identity. The file is preserved for a later retry.
 * The platform-free nullable-identity overload retains JSON mode for legacy
 * migration tests; the Android entry point always requires an identity.
 *
 * Known fail-safe trigger, accepted by design: an archive holding two
 * completions that differ only by language at the same instant passes
 * ArchiveCodec.validate (its completionKey keeps both) but the completions
 * primary key (kind, item_id, at) keeps only the first — the schema-pinned
 * narrowing documented on [CompletionsDao]. Count verification catches the
 * dropped mark and aborts, so such an archive stays on JSON mode instead of
 * silently losing data.
 *
 * Recovery translation, pinned by the schema: assessment_tickets stores a
 * ticket's session_id/attempts/last_attempt_at but not passageID/revisionKey,
 * so FinalAssessmentRecovery.enqueue's "newest ticket wins" upsert is the DB
 * form (a freshly recovered session always enqueues attempts = 0), and
 * enqueue's removal branch (no unassessed user passage) deletes the row.
 * Recovered sessions cannot legitimately carry an earlier ticket — enqueuing
 * requires endedAt != null — so this loses nothing on real paths.
 */
class LearningDbStore private constructor(
    private val database: SqlDatabase?,
    private val clock: () -> Double,
    val jsonMode: Boolean,
    val jsonModeNotice: String?,
) {
    /** A shared legacy fallback cannot safely be served to an active learner. */
    class QuarantinedLegacyArchive(cause: Throwable) : IllegalStateException(
        "The legacy learning archive is quarantined; existing data was not replaced.", cause,
    )

    init {
        require(jsonMode == (jsonModeNotice != null)) {
            "jsonMode and jsonModeNotice must go together"
        }
    }

    /** The open learning database; only touch this when [jsonMode] is false. */
    val db: SqlDatabase
        get() = requireNotNull(database) {
            "learning database unavailable: JSON fail-safe mode ($jsonModeNotice)"
        }

    /**
     * One-shot startup export of the whole database into the working archive.
     * Returns the persisted projection; pass it to
     * [recoverUnfinishedSessions] before handing it to the UI.
     */
    fun loadInitialArchive(): Archive = ArchiveMapper.export(db)

    /**
     * LearningRepository.load's recovery, database-backed. Marks every session
     * whose endedAt is still null as ended at [now] ("App closed before
     * finalization", the repository's exact reason string), persists it through
     * [SessionsDao.upsert], and enqueues the final-assessment retry ticket when
     * the session ends with an unassessed user passage (enqueue's removal
     * branch deletes a row instead). Pass the archive [loadInitialArchive]
     * returned: its session objects are updated in place AND persisted, so the
     * returned archive is the working copy the database now holds. Startup
     * only; a no-op (still returning the archive) when nothing was unfinished.
     */
    fun recoverUnfinishedSessions(archive: Archive, now: Double = clock()): Archive {
        val unfinished = archive.sessions.filter { it.endedAt == null }
        if (unfinished.isEmpty()) return archive
        db.transaction { tx ->
            unfinished.forEach { session ->
                session.endedAt = now
                session.endReason = RECOVERY_END_REASON
                SessionsDao.upsert(tx, session)
                val passage = FinalAssessmentRecovery.passage(session)
                if (passage == null) {
                    tx.update("DELETE FROM assessment_tickets WHERE session_id = ? AND ${ownerClause(tx)}",
                        listOf(session.id) + ownerArguments(tx))
                } else {
                    AssessmentTicketsDao.upsert(tx, AssessmentTicketRow(session.id,
                        attempts = 0, lastAttemptAt = null))
                }
            }
        }
        return archive
    }

    /**
     * The file operations migration needs over learning.json, abstracted so
     * the whole store logic runs (and is tested) on the JVM; the Android
     * wiring is the [companion]-level one-liner below.
     */
    interface Files {
        /** learning.json (or its AtomicFile .bak) is present and readable. */
        fun learningJsonExists(): Boolean

        /** The raw learning.json bytes (checked against
         *  [ArchiveCodec.MAXIMUM_ENCODED_BYTES] by the store, like
         *  LearningRepository's streamed read). */
        fun readLearningJson(): ByteArray

        /** Post-commit cleanup: learning.json → learning.json.migrated. */
        fun renameLearningJsonToMigrated()
    }

    companion object {
        const val MIGRATION_HASH_KEY = "migration_hash"
        const val MIGRATED_AT_KEY = "migrated_at"

        /** The exact reason string LearningRepository.load writes today. */
        const val RECOVERY_END_REASON = "App closed before finalization"

        private val metadataJson = Json { ignoreUnknownKeys = true }

        /** Selects the bytes AtomicFile.openRead() would recover, without changing any files.
         *  Android gives `.bak` precedence and normally renames it over the base. */
        internal fun legacyJsonSourceWithoutAtomicRecovery(base: File): File {
            val backup = File(base.path + ".bak")
            return when {
                backup.exists() -> backup
                base.exists() -> base
                else -> throw java.io.FileNotFoundException(base.path)
            }
        }

        internal fun readLearningJsonWithoutAtomicRecovery(base: File): ByteArray {
            val source = legacyJsonSourceWithoutAtomicRecovery(base)
            return readLegacyJsonFile(source)
        }

        internal fun readLegacyJsonFile(source: File): ByteArray {
            FileInputStream(source).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) {
                        "El respaldo local es demasiado grande."
                    }
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        }

        /** Production entry point: migrate when due, and quarantine an
         *  unattributed fallback on failure instead of exposing it to the
         *  active identity. A later launch can retry without deleting it. */
        fun open(context: Context, clock: () -> Double = ::nowSeconds,
                 activeIdentity: String): LearningDbStore {
            val files = androidFiles(context)
            val adopted = adoptPreimportDatabase(context.getDatabasePath(LearningDb.NAME))
            if (adopted) {
                println("LearningDbStore: adopted stranded learning.db.preimport " +
                    "(an import died between the two renames; the previous database is back)")
            }
            return try {
                open(LearningDb.open(context), files, activeIdentity = activeIdentity, clock = clock).also { store ->
                    ExamHistoryDao.assignOwnerlessToIdentityOnce(store.db, activeIdentity)
                }
            } catch (failure: Exception) {
                if (!files.learningJsonExists()) throw failure
                if (failure is QuarantinedLegacyArchive) throw failure
                throw QuarantinedLegacyArchive(failure)
            }
        }

        /** Platform-free core of [open] (the JVM-testable seam). Active
         *  identities quarantine failures; a null identity keeps the legacy
         *  JSON-mode behavior for migration tooling and historical tests. */
        fun open(database: SqlDatabase, files: Files, activeIdentity: String? = null,
                 clock: () -> Double = ::nowSeconds): LearningDbStore {
            return try {
                activeIdentity?.let { LearnerOwnershipDao.activate(database, it) }
                if (MetaDao.get(database, MIGRATION_HASH_KEY) == null && files.learningJsonExists()) {
                    migrate(database, files, clock)
                }
                ProgressRecoveryDao.recover(database, System.currentTimeMillis())
                LearningDbStore(database, clock, jsonMode = false, jsonModeNotice = null)
            } catch (failure: Exception) {
                // Migration aborted (or the database broke underneath it):
                // the content writes rolled back, the JSON is untouched, and
                // the hash gate stays open so the next launch retries (the
                // pre-import clear keeps the retry count-stable — see the
                // KDoc). Without the JSON fallback there is nothing to serve:
                // rethrow instead of a jsonMode that would drop the session's
                // work.
                if (!files.learningJsonExists()) throw failure
                if (activeIdentity != null) throw QuarantinedLegacyArchive(failure)
                jsonMode(files, failure)
            }
        }

        private fun jsonMode(files: Files, failure: Exception): LearningDbStore {
            println("LearningDbStore: staying on learning.json — ${failure.javaClass.simpleName}: ${failure.message}")
            return LearningDbStore(database = null, clock = { nowSeconds() }, jsonMode = true,
                jsonModeNotice = "No se pudo migrar el respaldo local: la app seguirá usando el archivo JSON de aprendizaje.")
        }

        /**
         * Crash recovery for the phase-D restore window: the import flow
         * (AppViewModel.importDbFrom) closes the store, stages the imported
         * bytes beside the live file, renames the live learning.db aside
         * (learning.db.preimport) and then renames the staged bytes into
         * place — two back-to-back renames. A process death between them can
         * strand the learner's ENTIRE history in learning.db.preimport with
         * no learning.db on disk (the next open would create a fresh empty
         * one). Adoption runs before every open: only when the live file is
         * MISSING and the keep-copy exists (the exact crash signature — after
         * a completed import both exist, and the installed bytes must win)
         * is the previous database renamed back, so the launch resumes on
         * the data it had; a leftover adoption failure leaves both files in
         * place for the next launch and logs instead of throwing.
         * Platform-free so JVM tests exercise the crash window directly.
         *
         * @return true when a stranded keep-copy was adopted.
         */
        internal fun adoptPreimportDatabase(dbFile: File): Boolean {
            try {
                val keep = File(dbFile.path + ".preimport")
                if (dbFile.exists() || !keep.exists()) return false
                check(keep.renameTo(dbFile)) { "could not adopt $keep back to $dbFile" }
                return true
            } catch (recovery: Exception) {
                // Never block the launch here: without adoption the open
                // creates an empty database while the stranded copy stays on
                // disk for the next attempt (and the JSON migration, if any,
                // is still gated off by its own hash).
                println("LearningDbStore: could not adopt learning.db.preimport — $recovery")
                return false
            }
        }

        private fun migrate(database: SqlDatabase, files: Files, clock: () -> Double) {
            val bytes = files.readLearningJson()
            require(bytes.size <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { "El respaldo local es demasiado grande." }
            val encoded = bytes.toString(Charsets.UTF_8)
            val archive = ArchiveCodec.decode(encoded)
            val tickets = decodeTickets(encoded)
            // retry hygiene (see the KDoc): the gate is open, so any learning
            // rows in the database are an earlier ABORTED attempt's residue —
            // import is insert-only, so without this clear a JSON that evolved
            // between attempts could never satisfy count verification again
            clearLearningTables(database)
            // the archive in import's own single transaction (the transaction
            // wrapper does not nest — see the KDoc for the three-step split)
            ArchiveMapper.import(archive, database)
            database.transaction { tx ->
                tickets.forEach { ticket ->
                    AssessmentTicketsDao.upsert(tx, AssessmentTicketRow(
                        ticket.sessionID, ticket.attempts, ticket.lastAttemptAt))
                }
                verifyCounts(tx, archive, tickets)
                MetaDao.put(tx, MIGRATED_AT_KEY, clock().toString())
                // the commit point: written last, read first by the gate above
                MetaDao.put(tx, MIGRATION_HASH_KEY, sha256Hex(bytes))
            }
            try {
                files.renameLearningJsonToMigrated()
            } catch (cleanup: Exception) {
                // Post-commit cleanup only: the hash is already written, so a
                // stranded learning.json is ignored on every later launch.
                // Failing the migration here would discard a verified commit.
                println("LearningDbStore: committed migration but could not rename learning.json — $cleanup")
            }
        }

        /** Deletes every learning row — everything [ArchiveMapper.import] can
         *  write, plus the mastery meta rows it replaces — so a retry starts
         *  from the same empty state as a fresh install and count verification
         *  sees only the current JSON's content. preferences and the
         *  non-mastery meta rows are not learning data and stay untouched.
         *  Only ever called with the gate open (no migration_hash): a
         *  committed migration's rows are never cleared. Fragments get their
         *  own DELETE although sessions would cascade — explicit over
         *  FK-dependent. */
        private fun clearLearningTables(db: SqlDatabase) {
            db.transaction { tx ->
                for (table in listOf("fragments", "sessions", "expressions", "lesson_state",
                        "completions", "skill_errors", "usage_outbox", "assessment_tickets")) {
                    tx.update("DELETE FROM $table WHERE ${ownerClause(tx)}", ownerArguments(tx))
                }
                val prefix = activeOwnerKey(tx)?.let { "${MetaDao.MASTERY_PREFIX}$it:" } ?: MetaDao.MASTERY_PREFIX
                tx.update("DELETE FROM meta WHERE key LIKE ?", listOf("$prefix%"))
            }
        }

        /** LearningRepository.load's retry-ledger decode, verbatim (same Json
         *  configuration, same require guards): any violation aborts the
         *  migration into fail-safe mode. */
        private fun decodeTickets(encoded: String): List<FinalAssessmentTicket> {
            val metadata = metadataJson.parseToJsonElement(encoded).jsonObject["androidFinalAssessments"]
            val tickets = metadata
                ?.let { metadataJson.decodeFromJsonElement<List<FinalAssessmentTicket>>(it) }
                ?: emptyList()
            require(tickets.size <= 10_000 && tickets.map { it.sessionID }.distinct().size == tickets.size)
            require(tickets.all { it.attempts in 0..FinalAssessmentRecovery.MAX_ATTEMPTS &&
                (it.lastAttemptAt == null || it.lastAttemptAt.isFinite()) })
            return tickets
        }

        /** ANY mismatch aborts the migration (the throw rolls the transaction
         *  back). The source of truth for every expectation is the decoded
         *  archive itself — tickets come from its androidFinalAssessments
         *  ledger and the outbox has no archive-side source (it starts empty;
         *  usage events were never persisted in the JSON). */
        private fun verifyCounts(db: SqlDatabase, archive: Archive, tickets: List<FinalAssessmentTicket>) {
            fun requireCount(label: String, sql: String, expected: Long) {
                val actual = db.query(sql).single().long("n")
                if (actual != expected) {
                    throw IllegalStateException("migration aborted: $label count $actual != $expected")
                }
            }
            requireCount("sessions", "SELECT COUNT(*) AS n FROM sessions", archive.sessions.size.toLong())
            requireCount("fragments", "SELECT COUNT(*) AS n FROM fragments",
                archive.sessions.sumOf { it.fragments.size }.toLong())
            requireCount("lessons", "SELECT COUNT(*) AS n FROM lesson_state", archive.lessons.size.toLong())
            requireCount("expressions", "SELECT COUNT(*) AS n FROM expressions", archive.expressions.size.toLong())
            requireCount("completions", "SELECT COUNT(*) AS n FROM completions",
                archive.completions.orEmpty().size.toLong())
            requireCount("skill errors", "SELECT COUNT(*) AS n FROM skill_errors",
                archive.errors.orEmpty().size.toLong())
            requireCount("mastery meta rows",
                "SELECT COUNT(*) AS n FROM meta WHERE key LIKE '${MetaDao.MASTERY_PREFIX}%'",
                archive.masteryCache.orEmpty().size.toLong())
            if (MetaDao.get(db, MetaDao.SCHEMA_FLAGS_KEY) == null) {
                throw IllegalStateException("migration aborted: schema_flags row missing after import")
            }
            requireCount("assessment tickets", "SELECT COUNT(*) AS n FROM assessment_tickets", tickets.size.toLong())
            requireCount("usage outbox", "SELECT COUNT(*) AS n FROM usage_outbox", 0)
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** Android file adapter keeps AtomicFile recovery semantics without mutating pre-commit data. */
        private fun androidFiles(context: Context): Files {
            val base = File(context.applicationContext.filesDir, "learning.json")
            return AndroidLearningFiles(base)
        }
    }
}

/** Android's AtomicFile recovery semantics implemented without pre-commit file mutation. */
internal class AndroidLearningFiles(private val base: File) : LearningDbStore.Files {
    private var migrationSource: File? = null

    override fun learningJsonExists() = base.exists() || File(base.path + ".bak").exists()

    override fun readLearningJson(): ByteArray {
        val source = LearningDbStore.legacyJsonSourceWithoutAtomicRecovery(base)
        migrationSource = source
        return LearningDbStore.readLegacyJsonFile(source)
    }

    override fun renameLearningJsonToMigrated() {
        val source = checkNotNull(migrationSource) { "legacy archive was not read before migration cleanup" }
        val suffix = if (source.path == base.path) ".migrated" else ".migrated.bak"
        val migrated = File(base.path + suffix)
        check(!migrated.exists()) { "refusing to replace existing migration archive $migrated" }
        java.nio.file.Files.move(source.toPath(), migrated.toPath())
    }
}
