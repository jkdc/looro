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

/** Provenance scope of pre-existing learning content when the migration
 *  gate is open without a pending claim - see [LearningDbStore.legacyResidueScope]. */
private sealed interface LegacyResidue {
    data object FRESH : LegacyResidue
    data object OWN : LegacyResidue
    data class AMBIGUOUS(val where: String) : LegacyResidue
}

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
 *  - the pending claim: meta 'migration_pending_owner' (+ the source
 *    bytes' hash) names the FIRST identity that attempts the one-way
 *    migration, written in its own transaction before any content move.
 *    A DIFFERENT active identity finding the claim open must not adopt -
 *    or clear - another account's backup: it opens in database mode with
 *    none of it and the JSON stays for its owner's retry (the legacy
 *    null-identity path writes no claim);
 *  - retry hygiene: with the gate open, learning rows that belong to the
 *    claiming owner are residue of an earlier ABORTED attempt, and the
 *    import is insert-only - it never deletes - so they are CLEARED first
 *    (inside the content transaction): the eight learning tables + the
 *    owner's mastery meta rows (preferences and the non-mastery meta rows
 *    are not learning data and stay untouched, and another account's rows
 *    are never cleared). This keeps a retry count-stable in ALL cases,
 *    including a JSON that legitimately evolved between attempts (the user
 *    deleted expressions, pruned skill errors while the app served the
 *    JSON path). A committed migration - hash present - never reaches this
 *    and is never cleared;
 *  - ONE content transaction: the clear + the archive import through
 *    [ArchiveMapper.importInTransaction] (never [ArchiveMerger], which is
 *    restore-only) + the retry-ledger rows + COUNT VERIFICATION against
 *    the decoded source, scoped to the importing owner (the tables are
 *    shared across accounts: another identity's legitimate rows must
 *    neither break the counts nor authorize clearing them) + meta
 *    'migrated_at' (Apple-epoch seconds) + meta 'migration_hash' (SHA-256
 *    of the raw JSON bytes) written LAST - the hash's presence is the
 *    commit point the gate reads - and the pending claim removed. Any
 *    count mismatch or any failure rolls the whole transaction back:
 *    nothing but the pending claim outlives an aborted attempt;
 *  - only after that commit: rename learning.json → learning.json.migrated.
 *    The JSON is never deleted; the rename is post-commit cleanup, so a
 *    failure there (or a crash in the window) leaves the file in place where
 *    the hash gate simply ignores it on every later launch.
 *
 * Crash windows and aborts are all safe. Before the hash is committed the
 * JSON stays the authority: an abort rolls the single content transaction
 * back - no rows are ever committed without the marker - and the pending
 * claim is what survives, naming the one identity that may resume. Its
 * retry clears that owner's stale rows first, which is what makes a retry
 * count-stable against an EVOLVED JSON: while the app served the JSON path
 * the archive could legitimately change (expressions deleted, skill errors
 * pruned), and a plain re-import over the stale rows would mismatch its
 * counts on every later launch - the wedge. Clearing first lets the retry
 * succeed with the evolved counts (pinned by LearningDbStoreTest's retry
 * regression). A crash after the commit leaves the hash, so the next launch
 * is in database mode and never re-migrates - a learning.json that
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

        /** The one-way migration's pending claim: the FIRST identity that
         *  started it, plus the source bytes' hash, written before any content
         *  move and removed in the same transaction as the commit hash. A
         *  different identity finding it open must not adopt (or clear) the
         *  backup — see [migrate]. */
        const val MIGRATION_PENDING_OWNER_KEY = "migration_pending_owner"
        const val MIGRATION_PENDING_HASH_KEY = "migration_pending_hash"

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
            return synchronized(LearnerOwnershipDao) {
                val files = androidFiles(context)
                val adopted = adoptPreimportDatabase(context.getDatabasePath(LearningDb.NAME))
                if (adopted) {
                    println("LearningDbStore: adopted stranded learning.db.preimport " +
                        "(an import died between the two renames; the previous database is back)")
                }
                try {
                    open(LearningDb.open(context), files, activeIdentity = activeIdentity, clock = clock)
                } catch (failure: Exception) {
                    if (!files.learningJsonExists()) throw failure
                    if (failure is QuarantinedLegacyArchive) throw failure
                    throw QuarantinedLegacyArchive(failure)
                }
            }
        }

        /** Platform-free core of [open] (the JVM-testable seam). Active
         *  identities quarantine failures; a null identity keeps the legacy
         *  JSON-mode behavior for migration tooling and historical tests. */
        fun open(database: SqlDatabase, files: Files, activeIdentity: String? = null,
                 clock: () -> Double = ::nowSeconds): LearningDbStore {
            return synchronized(LearnerOwnershipDao) {
                try {
                    // Inspect original provenance BEFORE activation can backfill ownerless
                    // rows or namespace mastery. Synthetic attribution is not migration lineage.
                    if (activeIdentity != null && MetaDao.get(database, MIGRATION_HASH_KEY) == null
                        && files.learningJsonExists()
                        && MetaDao.get(database, MIGRATION_PENDING_OWNER_KEY) == null) {
                        val residue = legacyResidueScope(database, activeIdentity)
                        if (residue is LegacyResidue.AMBIGUOUS) {
                            throw IllegalStateException(
                                "migration aborted: unattributed legacy content without a pending claim (${residue.where})")
                        }
                    }
                    activeIdentity?.let { LearnerOwnershipDao.activate(database, it) }
                    if (MetaDao.get(database, MIGRATION_HASH_KEY) == null && files.learningJsonExists()) {
                        migrate(database, files, clock)
                    }
                    ProgressRecoveryDao.recover(database, System.currentTimeMillis())
                    activeIdentity?.let { ExamHistoryDao.assignOwnerlessToIdentityOnce(database, it) }
                    LearningDbStore(database, clock, jsonMode = false, jsonModeNotice = null)
                } catch (failure: Exception) {
                    // Migration aborted (or the database broke underneath it):
                    // content remains fenced to this identity and production can
                    // retry without serving the unattributed source.
                    if (!files.learningJsonExists()) throw failure
                    if (activeIdentity != null) throw QuarantinedLegacyArchive(failure)
                    jsonMode(files, failure)
                }
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
            val sourceHash = sha256Hex(bytes)
            val ownerKey = activeOwnerKey(database)
            if (ownerKey != null) {
                // The one-way migration belongs to the FIRST identity that
                // claimed it. A different active identity finding the claim
                // open must not adopt - or clear - another account's backup:
                // it opens in database mode with none of it, and the JSON
                // stays here for its owner's retry.
                val pendingOwner = MetaDao.get(database, MIGRATION_PENDING_OWNER_KEY)
                if (pendingOwner != null && pendingOwner != ownerKey) return
                if (pendingOwner == null) {
                    // No claim yet: either a fresh install or a database the
                    // PRE-atomic version left with committed-but-unmarked
                    // content. Only unambiguous provenance may claim now -
                    // a fresh install, or residue that belongs exclusively
                    // to THIS identity (its own earlier attempt). Content of
                    // another owner, or unattributed rows, can neither be
                    // cleared nor reattributed (N01): the migration aborts
                    // and the open quarantines with the database and the
                    // source untouched.
                    val residue = legacyResidueScope(database, ownerKey)
                    if (residue is LegacyResidue.AMBIGUOUS) {
                        throw IllegalStateException(
                            "migration aborted: unattributed legacy content without a pending claim (${residue.where})")
                    }
                }
                database.transaction { tx ->
                    MetaDao.put(tx, MIGRATION_PENDING_OWNER_KEY, ownerKey)
                    MetaDao.put(tx, MIGRATION_PENDING_HASH_KEY, sourceHash)
                }
            }
            // ONE transaction for clear + import + tickets + verification +
            // bookkeeping (the transaction wrapper does not nest): the old
            // import-commits-first split left rows on disk without the
            // marker whenever the bookkeeping failed, and the next attempt -
            // any identity - found residue it could neither attribute nor
            // verify. Any failure now rolls back to the pre-migration state;
            // the pending claim is what survives, naming the owner.
            database.transaction { tx ->
                clearLearningTablesInTransaction(tx)
                ArchiveMapper.importInTransaction(tx, archive)
                tickets.forEach { ticket ->
                    AssessmentTicketsDao.upsert(tx, AssessmentTicketRow(
                        ticket.sessionID, ticket.attempts, ticket.lastAttemptAt))
                }
                verifyCounts(tx, archive, tickets)
                MetaDao.put(tx, MIGRATED_AT_KEY, clock().toString())
                // the commit point: written last, read first by the gate above
                MetaDao.put(tx, MIGRATION_HASH_KEY, sourceHash)
                tx.update("DELETE FROM meta WHERE key IN (?, ?)",
                    listOf(MIGRATION_PENDING_OWNER_KEY, MIGRATION_PENDING_HASH_KEY))
            }
            try {
                files.renameLearningJsonToMigrated()
            } catch (cleanup: Exception) {
                // Post-commit cleanup only: the hash is already written, so a
                // stranded learning.json is ignored on every later launch.
                // Failing the migration here would discard a verified commit.
                println("LearningDbStore: committed migration but could not rename learning.json - $cleanup")
            }
        }

        /** Provenance of the learning content already in the database while
         *  the gate is open WITHOUT a pending claim (a pre-atomic version
         *  could commit content without the marker). FRESH: no learning rows
         *  at all - safe to claim. OWN: every learning row is this owner's -
         *  a demonstrated earlier attempt of the same identity, safe to
         *  clear and re-import. AMBIGUOUS: any other-owner or unattributed
         *  row exists - clearing or reattributing is not authorized, and the
         *  migration must be refused (the ambiguous state is preserved and
         *  communicated, never reported as recovered). */
        private fun legacyResidueScope(db: SqlDatabase, ownerKey: String): LegacyResidue {
            var anyContent = false
            for (table in listOf("fragments", "sessions", "expressions", "lesson_state",
                    "completions", "skill_errors", "usage_outbox", "assessment_tickets")) {
                val foreign = db.query(
                    "SELECT EXISTS(SELECT 1 FROM $table WHERE owner_key IS NULL OR owner_key = '' " +
                        "OR owner_key != ?) AS any_row", listOf(ownerKey)).single().bool("any_row")
                if (foreign) return LegacyResidue.AMBIGUOUS(table)
                if (!anyContent) {
                    anyContent = db.query("SELECT EXISTS(SELECT 1 FROM $table) AS any_row",
                        emptyList()).single().bool("any_row")
                }
            }
            val foreignMastery = db.query(
                "SELECT EXISTS(SELECT 1 FROM meta WHERE key LIKE '${MetaDao.MASTERY_PREFIX}%' " +
                    "AND key NOT LIKE 'mastery:member:%' AND key NOT LIKE 'mastery:guest:%') AS any_row",
                emptyList()).single().bool("any_row")
            if (foreignMastery) return LegacyResidue.AMBIGUOUS("mastery-unattributed")
            val scopedPrefix = "${MetaDao.MASTERY_PREFIX}$ownerKey%"
            val otherOwnerMastery = db.query(
                "SELECT EXISTS(SELECT 1 FROM meta WHERE key LIKE '${MetaDao.MASTERY_PREFIX}%' " +
                    "AND key NOT LIKE ?) AS any_row", listOf(scopedPrefix)).single().bool("any_row")
            if (otherOwnerMastery) {
                val sample = db.query(
                    "SELECT key FROM meta WHERE key LIKE '${MetaDao.MASTERY_PREFIX}%' " +
                        "AND key NOT LIKE ? ORDER BY key LIMIT 3",
                    listOf(scopedPrefix)).map { it.text("key") }
                return LegacyResidue.AMBIGUOUS("mastery-other-owner:$sample")
            }
            val ownMastery = db.query(
                "SELECT EXISTS(SELECT 1 FROM meta WHERE key LIKE ?) AS any_row",
                listOf(scopedPrefix)).single().bool("any_row")
            return if (anyContent || ownMastery) LegacyResidue.OWN else LegacyResidue.FRESH
        }

        /** Deletes every learning row of the CLAIMING owner - everything
         *  [ArchiveMapper.importInTransaction] can write, plus the owner's
         *  mastery meta rows it replaces - so a retry starts from the same
         *  empty state as a fresh install and count verification sees only
         *  the current JSON's content. preferences and the non-mastery meta
         *  rows are not learning data and stay untouched, and another
         *  account's rows are never cleared: the delete runs inside the
         *  caller's migration transaction (transactions do not nest) and is
         *  owner-scoped like every read it must stay consistent with. Only
         *  ever called with the gate open (no migration_hash): a committed
         *  migration's rows are never cleared. Fragments get their own
         *  DELETE although sessions would cascade - explicit over
         *  FK-dependent. */
        private fun clearLearningTablesInTransaction(tx: SqlDatabase) {
            for (table in listOf("fragments", "sessions", "expressions", "lesson_state",
                    "completions", "skill_errors", "usage_outbox", "assessment_tickets")) {
                tx.update("DELETE FROM $table WHERE ${ownerClause(tx)}", ownerArguments(tx))
            }
            val prefix = activeOwnerKey(tx)?.let { "${MetaDao.MASTERY_PREFIX}$it:" } ?: MetaDao.MASTERY_PREFIX
            tx.update("DELETE FROM meta WHERE key LIKE ?", listOf("$prefix%"))
        }

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

        /** ANY mismatch aborts the migration (the throw rolls the content
         *  transaction back). Every count is scoped to the importing owner:
         *  the tables are shared across accounts, so another identity's
         *  legitimate rows must neither break the verification nor authorize
         *  clearing them (the old whole-table counts wedged every later
         *  attempt once any foreign rows existed). The source of truth for
         *  every expectation is the decoded archive itself - tickets come
         *  from its androidFinalAssessments ledger and the outbox has no
         *  archive-side source (it starts empty; usage events were never
         *  persisted in the JSON). */
        private fun verifyCounts(db: SqlDatabase, archive: Archive, tickets: List<FinalAssessmentTicket>) {
            val clause = ownerClause(db)
            val args = ownerArguments(db)
            fun requireCount(label: String, sql: String, expected: Long,
                             bind: List<Any?> = args) {
                val actual = db.query(sql, bind).single().long("n")
                if (actual != expected) {
                    throw IllegalStateException("migration aborted: $label count $actual != $expected")
                }
            }
            requireCount("sessions", "SELECT COUNT(*) AS n FROM sessions WHERE $clause",
                archive.sessions.size.toLong())
            requireCount("fragments", "SELECT COUNT(*) AS n FROM fragments WHERE $clause",
                archive.sessions.sumOf { it.fragments.size }.toLong())
            requireCount("lessons", "SELECT COUNT(*) AS n FROM lesson_state WHERE $clause",
                archive.lessons.size.toLong())
            requireCount("expressions", "SELECT COUNT(*) AS n FROM expressions WHERE $clause",
                archive.expressions.size.toLong())
            requireCount("completions", "SELECT COUNT(*) AS n FROM completions WHERE $clause",
                archive.completions.orEmpty().size.toLong())
            requireCount("skill errors", "SELECT COUNT(*) AS n FROM skill_errors WHERE $clause",
                archive.errors.orEmpty().size.toLong())
            val masteryPrefix = activeOwnerKey(db)?.let { "${MetaDao.MASTERY_PREFIX}$it:" } ?: MetaDao.MASTERY_PREFIX
            requireCount("mastery meta rows",
                "SELECT COUNT(*) AS n FROM meta WHERE key LIKE ?",
                archive.masteryCache.orEmpty().size.toLong(),
                bind = listOf("$masteryPrefix%"))
            if (MetaDao.get(db, MetaDao.SCHEMA_FLAGS_KEY) == null) {
                throw IllegalStateException("migration aborted: schema_flags row missing after import")
            }
            requireCount("assessment tickets",
                "SELECT COUNT(*) AS n FROM assessment_tickets WHERE $clause", tickets.size.toLong())
            requireCount("usage outbox", "SELECT COUNT(*) AS n FROM usage_outbox WHERE $clause", 0)
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
