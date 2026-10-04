import Foundation
import CryptoKit

/**
 * Where the one-way migration reads its legacy payload from — the Swift
 * counterpart of the Kotlin `LearningDbStore.Files` seam. The production
 * implementation (App target, `SwiftDataMigrationSource`) serves the
 * SwiftData `StoredArchive` payload, the archive store this cutover
 * replaces; there is no legacy learning.json on iOS. The abstraction keeps
 * the whole store logic platform-free and testable at the macOS gate.
 */
public protocol LearningMigrationSource: AnyObject {
    /// The legacy payload is present and has not been marked migrated yet.
    var hasPayload: Bool { get }

    /// The raw payload bytes (checked against `Archive.maximumEncodedBytes`
    /// by `Archive.decode`, like the streamed reads before it).
    func readPayload() throws -> Data

    /// Post-commit cleanup: the `learning.json → learning.json.migrated`
    /// rename's SwiftData form. The payload itself is never destroyed — like
    /// the renamed file, it stays on disk where the hash gate ignores it.
    func markMigrated() throws
}

/**
 * LearningDbStore — the learner's runtime persistence facade over the learning
 * database (phase E, the Swift mirror of the Kotlin phase-C store). It takes
 * over LearningStore's RUNTIME role (open the durable store, hand out the
 * working archive, recover sessions the app died inside); LearningStore itself
 * keeps the working in-memory archive and becomes the directed-write facade,
 * with the SwiftData store retained as the JSON-mode fallback path this phase.
 *
 * Startup contract (the sequence the cutover wires into LearningStore.init):
 *
 *  1. `LearningDbStore.open` opens the database and performs the ONE-WAY
 *     payload migration when it is due (below).
 *  2. `loadInitialArchive()` exports the database into the working archive
 *     (one-shot at startup — ArchiveMapper.export as allowed exactly there).
 *  3. `recoverUnfinishedSessions(archive)` mirrors the legacy load's recovery
 *     over that exported archive: sessions still open when the app died get
 *     endedAt/endReason written through SessionsDao and a final-assessment
 *     retry ticket through AssessmentTicketsDao (its removal branch deletes a
 *     row instead). iOS has no runtime retry-ledger consumer yet (the
 *     FinalAssessmentQueue is runtime-only); the rows are the schema-shared
 *     durable form, kept for parity with the Kotlin store and phase F.
 *
 * One-way migration (runs inside `open` when meta 'migration_hash' is absent
 * and the source has a payload):
 *
 *  - read the payload → `Archive.decode` (its validation and the
 *    v1→v2 language-modules migration included) — all before any write. The
 *    iOS legacy payload carries no retry ledger (Android-only metadata) and
 *    no mastery cache (the iOS Archive model has no such field), so the
 *    migration writes no ticket rows and routes `nil` as the mapper's
 *    optional `masteryCache` parameter — the mapper's documented default;
 *  - retry hygiene: with the gate open, any learning rows already in the
 *    database are residue of an earlier ABORTED attempt (import commits
 *    before the bookkeeping does), and `ArchiveMapper.import` is insert-only
 *    — it never deletes — so they are CLEARED first, in their own
 *    transaction: the eight learning tables + the mastery meta rows
 *    (preferences and the non-mastery meta rows are not learning data and
 *    stay untouched). This keeps a retry count-stable in ALL cases, including
 *    a payload that legitimately evolved between attempts (the user kept
 *    using the app on the SwiftData fallback path). A committed migration —
 *    hash present — never reaches this and is never cleared;
 *  - the archive lands through `ArchiveMapper.import` (its single
 *    transaction is the migration's only content transaction; the
 *    transaction wrapper does not nest, so the pre-import clear and the
 *    bookkeeping each get their own);
 *  - one bookkeeping transaction: COUNT VERIFICATION against the decoded
 *    source (sessions, fragments, lessons, expressions, completions, skill
 *    errors, mastery meta rows, schema flags, tickets, outbox) + meta
 *    'migrated_at' (Apple-epoch seconds) + meta 'migration_hash' (SHA-256 of
 *    the raw payload bytes) written LAST — the hash's presence is the commit
 *    point the gate reads. Any count mismatch or any failure rolls the
 *    bookkeeping back;
 *  - only after that commit: `source.markMigrated()`. The payload is never
 *    deleted; the mark is post-commit cleanup, so a failure there (or a
 *    crash in the window) leaves the record in place where the hash gate
 *    simply ignores it on every later launch.
 *
 * Crash windows and aborts are all safe. Before the hash is committed the
 * payload stays the authority: an abort leaves the store in JSON mode, and
 * the residue an aborted attempt leaves in the database is unreachable
 * without migration_hash — and is cleared before the next attempt. That
 * clear is what makes a retry count-stable against an EVOLVED payload: while
 * the app served the SwiftData path the archive could legitimately change,
 * and a plain re-import over the stale rows would mismatch its counts on
 * every later launch — the wedge. A crash after the commit leaves the hash,
 * so the next launch is in database mode and never re-migrates — a payload
 * record that survives is IGNORED, not merged again.
 *
 * Fail-safe contract: `open` never throws while the source has a payload.
 * When the database cannot be opened or the migration aborts (corrupt
 * payload, oversized file, verified-count mismatch), the store comes back
 * with `jsonMode` = true and `jsonModeNotice` set: the app must spend that
 * launch on the SwiftData path (which still owns the intact record) and
 * surface the notice. In JSON mode `db` throws, so no code can write to a
 * database the next launch would not read. The migration retries on the next
 * launch because the hash gate only closes after a verified commit. Without
 * a payload to fall back on, `open` RETHROWS (a database that cannot open
 * included): there is no data to serve either way, and a silent jsonMode
 * would strand a session's work with no durable copy.
 *
 * Known fail-safe trigger, accepted by design (shared with the schema): an
 * archive holding two completions that differ only by language at the same
 * instant passes Archive.validate (its markKey keeps both) but the
 * completions primary key (kind, item_id, at) keeps only the first — the
 * schema-pinned narrowing documented on `CompletionsDao`. Count verification
 * catches the dropped mark and aborts, so such a payload stays on JSON mode
 * instead of silently losing data.
 */
public final class LearningDbStore {
    private let clock: () -> Double
    private let database: SqlDatabase?

    public let jsonMode: Bool
    public let jsonModeNotice: String?

    init(database: SqlDatabase?, clock: @escaping () -> Double,
         jsonMode: Bool, jsonModeNotice: String?) {
        precondition(jsonMode == (jsonModeNotice != nil),
                     "jsonMode and jsonModeNotice must go together")
        self.database = database
        self.clock = clock
        self.jsonMode = jsonMode
        self.jsonModeNotice = jsonModeNotice
    }

    /// The open learning database; only touch this when `jsonMode` is false.
    public var db: SqlDatabase {
        get throws {
            guard let database else {
                throw LearningDbStoreError.jsonModeUnavailable(
                    "learning database unavailable: JSON fail-safe mode (\(jsonModeNotice ?? ""))")
            }
            return database
        }
    }

    public enum LearningDbStoreError: Error, Equatable {
        /// `db` was touched in JSON fail-safe mode.
        case jsonModeUnavailable(String)
        /// The one-way migration aborted; the throw rolls its bookkeeping
        /// transaction back and leaves the payload untouched.
        case migrationAborted(String)
    }

    /// One-shot startup export of the whole database into the working archive.
    /// Returns the persisted projection; pass it to
    /// `recoverUnfinishedSessions` before handing it to the UI.
    public func loadInitialArchive() throws -> Archive {
        try ArchiveMapper.export(db)
    }

    /// The legacy load's recovery, database-backed. Marks every session whose
    /// endedAt is still null as ended at `now` ("App closed before
    /// finalization", the legacy load's exact reason string), persists it
    /// through `SessionsDao.upsert`, and enqueues the final-assessment retry
    /// ticket when the session ends with an unassessed user passage (the
    /// removal branch deletes a row instead). Pass the archive
    /// `loadInitialArchive` returned: its sessions are updated AND persisted,
    /// so the returned archive is the working copy the database now holds
    /// (struct semantics: the updates are written back into the returned
    /// copy, which is what the Kotlin mutation of the shared session objects
    /// achieved). Startup only; a no-op (still returning the archive) when
    /// nothing was unfinished.
    @discardableResult
    public func recoverUnfinishedSessions(_ archive: Archive, now: Double? = nil) throws -> Archive {
        let at = now ?? clock()
        var archive = archive
        let unfinished = archive.sessions.indices.filter { archive.sessions[$0].endedAt == nil }
        if unfinished.isEmpty { return archive }
        try db.transaction { tx in
            for index in unfinished {
                archive.sessions[index].endedAt = Date(timeIntervalSinceReferenceDate: at)
                archive.sessions[index].endReason = Self.recoveryEndReason
                let session = archive.sessions[index]
                try SessionsDao.upsert(tx, session)
                if Self.recoverablePassage(session) == nil {
                    _ = try tx.update("DELETE FROM assessment_tickets WHERE session_id = ?",
                                      args: [session.id.uuidString])
                } else {
                    try AssessmentTicketsDao.upsert(tx, AssessmentTicketRow(
                        session.id.uuidString, attempts: 0, lastAttemptAt: nil))
                }
            }
        }
        return archive
    }

    /// The legacy recovery's passage rule, shared with the runtime
    /// FinalAssessmentQueue's submit guard: the newest user passage that is
    /// long enough to assess and carries no matching assessment yet.
    public static func recoverablePassage(_ session: SessionRecord) -> Passage? {
        guard session.endedAt != nil,
              let passage = session.passages.last(where: { $0.speaker == .user }) else { return nil }
        let assessed = session.assessments.contains {
            $0.passageID == passage.id && $0.revisionKey == passage.revisionKey
        }
        return passage.text.count >= 3 && !assessed ? passage : nil
    }

    // MARK: opening

    public static let migrationHashKey = "migration_hash"
    public static let migratedAtKey = "migrated_at"

    /// The exact reason string the legacy load writes today.
    public static let recoveryEndReason = "App closed before finalization"

    /// Production entry point: open (or create) learning.db at `path` and
    /// migrate the legacy payload when due. Never throws while the source has
    /// a payload to fall back on — see the fail-safe contract in the class
    /// doc. Without one, a failed open (a database that cannot open included)
    /// RETHROWS: jsonMode would promise a fallback that cannot serve anything
    /// and silently drop the session's work on the next launch.
    public static func open(at path: String,
                            source: (any LearningMigrationSource)? = nil,
                            clock: @escaping () -> Double = { Date.now.timeIntervalSinceReferenceDate }) throws -> LearningDbStore {
        if adoptPreimportDatabase(at: URL(fileURLWithPath: path)) {
            print("LearningDbStore: adopted stranded learning.db.preimport "
                + "(an import died between the two renames; the previous database is back)")
        }
        do {
            let database = try LearningDatabase.open(at: path)
            return try open(database: database, source: source, clock: clock)
        } catch {
            guard let source, source.hasPayload else { throw error }
            return jsonMode(failure: error)
        }
    }

    /// Platform-free core of `open` (the gate-testable seam): the caller
    /// supplies the opened database and the migration source. Same fail-safe
    /// split: jsonMode while the source has a payload, rethrow without one.
    public static func open(database: SqlDatabase,
                            source: (any LearningMigrationSource)? = nil,
                            clock: @escaping () -> Double = { Date.now.timeIntervalSinceReferenceDate }) throws -> LearningDbStore {
        do {
            if let source, source.hasPayload, try MetaDao.get(database, migrationHashKey) == nil {
                try migrate(database, source, clock)
            }
            return LearningDbStore(database: database, clock: clock, jsonMode: false, jsonModeNotice: nil)
        } catch {
            // Migration aborted (or the database broke underneath it): the
            // content writes rolled back, the payload is untouched, and the
            // hash gate stays open so the next launch retries (the pre-import
            // clear keeps the retry count-stable — see the class doc).
            // Without the payload fallback there is nothing to serve:
            // rethrow instead of a jsonMode that would drop the session's
            // work.
            guard let source, source.hasPayload else { throw error }
            return jsonMode(failure: error)
        }
    }

    private static func jsonMode(failure: Error) -> LearningDbStore {
        print("LearningDbStore: staying on the previous local store — \(failure)")
        return LearningDbStore(database: nil, clock: { Date.now.timeIntervalSinceReferenceDate },
                               jsonMode: true,
                               jsonModeNotice: "Palique couldn’t migrate your local library. "
                                   + "Nothing was lost: this session keeps using the previous store.")
    }

    private static func migrate(_ database: SqlDatabase,
                                _ source: any LearningMigrationSource,
                                _ clock: () -> Double) throws {
        let payload = try source.readPayload()
        let archive = try Archive.decode(payload)
        // The iOS legacy payload carries no androidFinalAssessments retry
        // ledger (Android-only metadata) — there is nothing to decode here,
        // and no ticket rows are migrated (pinned by verifyCounts below).
        // Retry hygiene (see the class doc): the gate is open, so any
        // learning rows in the database are an earlier ABORTED attempt's
        // residue — import is insert-only, so without this clear a payload
        // that evolved between attempts could never satisfy count
        // verification again.
        try clearLearningTables(database)
        // The archive in import's own single transaction (the transaction
        // wrapper does not nest). masteryCache stays the mapper's documented
        // optional parameter: the iOS Archive model has no cache field, and
        // nil is exactly what the Kotlin mapper writes for an archive
        // without one.
        try ArchiveMapper.import(archive, database)
        try database.transaction { tx in
            try verifyCounts(tx, archive)
            try MetaDao.put(tx, migratedAtKey, String(clock()))
            // The commit point: written last, read first by the gate above.
            try MetaDao.put(tx, migrationHashKey, sha256Hex(payload))
        }
        do {
            try source.markMigrated()
        } catch {
            // Post-commit cleanup only: the hash is already written, so a
            // stranded payload record is ignored on every later launch.
            // Failing the migration here would discard a verified commit.
            print("LearningDbStore: committed migration but could not mark the previous store migrated — \(error)")
        }
    }

    /// Deletes every learning row — everything `ArchiveMapper.import` can
    /// write, plus the mastery meta rows it replaces — so a retry starts
    /// from the same empty state as a fresh install and count verification
    /// sees only the current payload's content. preferences and the
    /// non-mastery meta rows are not learning data and stay untouched. Only
    /// ever called with the gate open (no migration_hash): a committed
    /// migration's rows are never cleared. Fragments get their own DELETE
    /// although sessions would cascade — explicit over FK-dependent.
    static func clearLearningTables(_ db: SqlDatabase) throws {
        try db.transaction { tx in
            for table in ["fragments", "sessions", "expressions", "lesson_state",
                          "completions", "skill_errors", "usage_outbox", "assessment_tickets"] {
                _ = try tx.update("DELETE FROM \(table)")
            }
            try tx.exec("DELETE FROM meta WHERE key LIKE '\(MetaDao.masteryPrefix)%'")
        }
    }

    /// ANY mismatch aborts the migration (the throw rolls the transaction
    /// back). The source of truth for every expectation is the decoded
    /// archive itself; the retry ledger and the outbox have no archive-side
    /// source on iOS (the legacy payload never carried tickets, and usage
    /// events were never persisted in it), so both start empty — the mastery
    /// expectation is 0 for the same model-gap reason documented on
    /// `ArchiveMapper.import`.
    static func verifyCounts(_ db: SqlDatabase, _ archive: Archive) throws {
        func requireCount(_ label: String, _ sql: String, _ expected: Int64) throws {
            let actual = try db.query(sql).first!.long("n")
            if actual != expected {
                throw LearningDbStoreError.migrationAborted(
                    "migration aborted: \(label) count \(actual) != \(expected)")
            }
        }
        try requireCount("sessions", "SELECT COUNT(*) AS n FROM sessions", Int64(archive.sessions.count))
        try requireCount("fragments", "SELECT COUNT(*) AS n FROM fragments",
                         Int64(archive.sessions.reduce(0) { $0 + $1.fragments.count }))
        try requireCount("lessons", "SELECT COUNT(*) AS n FROM lesson_state", Int64(archive.lessons?.count ?? 0))
        try requireCount("expressions", "SELECT COUNT(*) AS n FROM expressions", Int64(archive.expressions?.count ?? 0))
        try requireCount("completions", "SELECT COUNT(*) AS n FROM completions", Int64(archive.completions?.count ?? 0))
        try requireCount("skill errors", "SELECT COUNT(*) AS n FROM skill_errors", Int64(archive.errors?.count ?? 0))
        try requireCount("mastery meta rows",
                         "SELECT COUNT(*) AS n FROM meta WHERE key LIKE '\(MetaDao.masteryPrefix)%'", 0)
        if try MetaDao.get(db, MetaDao.schemaFlagsKey) == nil {
            throw LearningDbStoreError.migrationAborted("migration aborted: schema_flags row missing after import")
        }
        try requireCount("assessment tickets", "SELECT COUNT(*) AS n FROM assessment_tickets", 0)
        try requireCount("usage outbox", "SELECT COUNT(*) AS n FROM usage_outbox", 0)
    }

    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    // MARK: restore-window crash recovery

    /**
     * Crash recovery for the phase-F restore window: the import flow closes
     * the store, stages the imported bytes beside the live file, renames the
     * live learning.db aside (learning.db.preimport) and then renames the
     * staged bytes into place — two back-to-back renames. A process death
     * between them can strand the learner's ENTIRE history in
     * learning.db.preimport with no learning.db on disk (the next open would
     * create a fresh empty one). Adoption runs before every open: only when
     * the live file is MISSING and the keep-copy exists (the exact crash
     * signature — after a completed import both exist, and the installed
     * bytes must win) is the previous database renamed back, so the launch
     * resumes on the data it had; a leftover adoption failure leaves both
     * files in place for the next launch and logs instead of throwing.
     * Wired into `open(at:)` now so phase F only adds its renames.
     *
     * - Returns: true when a stranded keep-copy was adopted.
     */
    public static func adoptPreimportDatabase(at dbURL: URL) -> Bool {
        let keep = URL(fileURLWithPath: dbURL.path + ".preimport")
        let files = FileManager.default
        if files.fileExists(atPath: dbURL.path) || !files.fileExists(atPath: keep.path) { return false }
        do {
            try files.moveItem(at: keep, to: dbURL)
            return true
        } catch {
            // Never block the launch here: without adoption the open creates
            // an empty database while the stranded copy stays on disk for the
            // next attempt (and the payload migration, if any, is still gated
            // off by its own hash).
            print("LearningDbStore: could not adopt learning.db.preimport — \(error)")
            return false
        }
    }
}
