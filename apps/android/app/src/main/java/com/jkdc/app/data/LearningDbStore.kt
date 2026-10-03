package com.jkdc.app.data

import android.content.Context
import android.util.AtomicFile
import com.jkdc.app.core.Archive
import com.jkdc.app.core.ArchiveCodec
import com.jkdc.app.core.FinalAssessmentRecovery
import com.jkdc.app.core.FinalAssessmentTicket
import com.jkdc.app.core.nowSeconds
import java.io.ByteArrayOutputStream
import java.io.File
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
 *  - the archive itself lands through [ArchiveMapper.import] (atomic by
 *    construction — never [ArchiveMerger], which is restore-only; its single
 *    transaction is the migration's only content transaction, and the
 *    transaction wrapper does not nest, so the bookkeeping gets its own);
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
 * JSON stays the authority: an abort leaves the store in JSON mode (the
 * imported archive content of an aborted migration may remain committed in
 * the database, but without migration_hash nothing ever reads it), and the
 * next launch retries — a re-import over already-imported content is
 * count-stable, pinned by ArchiveMapperTest. A crash after the commit leaves
 * the hash, so the next launch is in database mode and never re-migrates —
 * a learning.json that reappears later is IGNORED, not merged again.
 *
 * Fail-safe contract for Task 2: [open] never throws while learning.json
 * exists. When the database cannot be opened or the migration aborts (corrupt
 * JSON, oversized file, invalid retry ledger, verified-count mismatch), the
 * store comes back with [jsonMode] = true and [jsonModeNotice] set: the app
 * must spend that launch on LearningRepository's JSON path (which still owns
 * the intact file) and surface the notice. In JSON mode [db] throws, so no
 * code can write to a database the next launch would not read. The migration
 * retries on the next launch because the hash gate only closes after a
 * verified commit. Without a JSON to fall back to, [open] rethrows — there is
 * no data to serve either way.
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
                    tx.update("DELETE FROM assessment_tickets WHERE session_id = ?", listOf(session.id))
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

        /** Production entry point: open (or create) learning.db and migrate
         *  learning.json when due. Never throws while a learning.json exists
         *  to fall back on — see the fail-safe contract in the class KDoc. */
        fun open(context: Context, clock: () -> Double = ::nowSeconds): LearningDbStore {
            val files = androidFiles(context)
            return try {
                open(LearningDb.open(context), files, clock)
            } catch (failure: Exception) {
                jsonMode(files, failure)
            }
        }

        /** Platform-free core of [open] (the JVM-testable seam): the caller
         *  supplies the opened database and the file operations. */
        fun open(database: SqlDatabase, files: Files, clock: () -> Double = ::nowSeconds): LearningDbStore {
            return try {
                if (MetaDao.get(database, MIGRATION_HASH_KEY) == null && files.learningJsonExists()) {
                    migrate(database, files, clock)
                }
                LearningDbStore(database, clock, jsonMode = false, jsonModeNotice = null)
            } catch (failure: Exception) {
                // Migration aborted (or the database broke underneath it): the
                // import transaction rolled back, the JSON is untouched, and
                // the hash gate stays open so the next launch retries.
                jsonMode(files, failure)
            }
        }

        private fun jsonMode(files: Files, failure: Exception): LearningDbStore {
            println("LearningDbStore: staying on learning.json — ${failure.javaClass.simpleName}: ${failure.message}")
            return LearningDbStore(database = null, clock = { nowSeconds() }, jsonMode = true,
                jsonModeNotice = "No se pudo migrar el respaldo local: la app seguirá usando el archivo JSON de aprendizaje.")
        }

        private fun migrate(database: SqlDatabase, files: Files, clock: () -> Double) {
            val bytes = files.readLearningJson()
            require(bytes.size <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { "El respaldo local es demasiado grande." }
            val encoded = bytes.toString(Charsets.UTF_8)
            val archive = ArchiveCodec.decode(encoded)
            val tickets = decodeTickets(encoded)
            // the archive in import's own single transaction (the transaction
            // wrapper does not nest — see the KDoc for the two-step split)
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

        /** The Android [Files]: AtomicFile read (same bytes LearningRepository
         *  reads, .bak fallback included), plain same-directory rename. */
        private fun androidFiles(context: Context): Files {
            val base = File(context.applicationContext.filesDir, "learning.json")
            val atomic = AtomicFile(base)
            return object : Files {
                override fun learningJsonExists() = base.exists() || File(base.path + ".bak").exists()
                override fun readLearningJson(): ByteArray {
                    atomic.openRead().use { input ->
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
                override fun renameLearningJsonToMigrated() {
                    val migrated = File(base.path + ".migrated")
                    if (base.exists()) check(base.renameTo(migrated)) { "could not rename $base to $migrated" }
                    else check(File(base.path + ".bak").renameTo(File(base.path + ".migrated.bak"))) {
                        "could not rename the learning.json backup"
                    }
                }
            }
        }
    }
}
