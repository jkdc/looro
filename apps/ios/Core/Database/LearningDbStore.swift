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
    /// Whether a legacy payload is present and not migrated; read errors throw
    /// so a failed lookup cannot be mistaken for an absent source.
    var hasPayload: Bool { get throws }

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
 * with the SwiftData source retained only for one-way migration and recovery.
 * Production opens carry an active learner identity; an open or migration
 * failure then throws instead of serving an unattributed shared payload.
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
 *  - the pending claim: meta 'migration_pending_owner' (+ the source
 *    bytes' hash) names the FIRST identity that attempts the one-way
 *    migration, written in its own transaction before any content move.
 *    A DIFFERENT active identity finding the claim open must not adopt -
 *    or clear - another account's backup: it opens in database mode with
 *    none of it and the payload stays for its owner's retry (the
 *    identity-less tooling path writes no claim);
 *  - retry hygiene + ONE content transaction: learning rows that belong
 *    to the claiming owner are residue of an earlier ABORTED attempt,
 *    and the import is insert-only - it never deletes - so they are
 *    CLEARED first (inside the content transaction), then the archive
 *    lands through `ArchiveMapper.importInTransaction`, then COUNT
 *    VERIFICATION scoped to the importing owner (the tables are shared
 *    across accounts: another identity's legitimate rows must neither
 *    break the counts nor authorize clearing them), then meta
 *    'migrated_at' (Apple-epoch seconds) + meta 'migration_hash' (SHA-256
 *    of the raw payload bytes) written LAST - the hash's presence is the
 *    commit point the gate reads - and the claim removed. Any mismatch
 *    or any failure rolls the whole transaction back: nothing but the
 *    pending claim outlives an aborted attempt;
 *  - historical caution: with learning content already in the database
 *    and NO claim (a pre-atomic version committed content without the
 *    marker), only unambiguous provenance may claim - a fresh install,
 *    or residue that belongs exclusively to the opening identity.
 *    Another owner's or unattributed content REFUSES the migration: the
 *    database and the payload stay untouched and the open throws
 *    (preserved and communicated, never silently reattributed);
 *  - only after that commit: `source.markMigrated()`. The payload is never
 *    deleted; the mark is post-commit cleanup, so a failure there (or a
 *    crash in the window) leaves the record in place where the hash gate
 *    simply ignores it on every later launch.
 *
 * Crash windows and aborts are all safe. Before the hash is committed
 * the source bytes stay intact: an abort rolls the single content
 * transaction back - no rows are ever committed without the marker - and
 * the pending claim is what survives, naming the one identity that may
 * resume. Its retry clears that owner's stale rows first, so a payload
 * that evolved between attempts still satisfies count verification. A
 * crash after the commit leaves the hash, so the next launch is in
 * database mode and never re-migrates - a payload record that survives
 * is IGNORED, not merged again. An ambiguous unclaimed residue (another
 * identity's or unattributed rows, from a pre-atomic version) refuses
 * the migration instead of being cleared: the state is preserved and
 * the open throws until an explicit recovery resolves the provenance.
 *
 * Fail-safe contract: production calls `open` with an active learner
 * identity. Database-open or migration failure then throws without exposing
 * or mutating the legacy payload; AppRoot offers a non-destructive retry.
 * The identity-less jsonMode path remains only for migration tooling and
 * historical tests, not the running application.
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
                    _ = try tx.update("DELETE FROM assessment_tickets WHERE session_id = ? AND \(ownerClause(tx))",
                                      args: [session.id.uuidString] + (try ownerArguments(tx)))
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
    /// Keep in sync with that guard — the two are the startup-recovery and
    /// runtime halves of one rule, and the recovery's ticket/removal branch
    /// assumes the queue would reach the same passage.
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

    /// The one-way migration's pending claim: the FIRST identity that
    /// started it plus the source bytes' hash, written before any content
    /// move and removed in the same transaction as the commit hash (the
    /// Swift mirror of the Kotlin store's pending-claim protection).
    public static let migrationPendingOwnerKey = "migration_pending_owner"
    public static let migrationPendingHashKey = "migration_pending_hash"

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
                            activeIdentity: String? = nil,
                            clock: @escaping () -> Double = { Date.now.timeIntervalSinceReferenceDate }) throws -> LearningDbStore {
        if adoptPreimportDatabase(at: URL(fileURLWithPath: path)) {
            print("LearningDbStore: adopted stranded learning.db.preimport "
                + "(an import died between the two renames; the previous database is back)")
        }
        do {
            let database = try LearningDatabase.open(at: path)
            return try open(database: database, source: source, activeIdentity: activeIdentity, clock: clock)
        } catch {
            guard activeIdentity == nil, let source, try source.hasPayload else { throw error }
            return jsonMode(failure: error)
        }
    }

    /// Platform-free core of `open` (the gate-testable seam): the caller
    /// supplies the opened database and the migration source. Same fail-safe
    /// split: jsonMode while the source has a payload, rethrow without one.
    public static func open(database: SqlDatabase,
                            source: (any LearningMigrationSource)? = nil,
                            activeIdentity: String? = nil,
                            clock: @escaping () -> Double = { Date.now.timeIntervalSinceReferenceDate }) throws -> LearningDbStore {
        do {
            // Inspect original provenance before activation backfills ownership or moves
            // mastery. Attribution performed now cannot prove an earlier migration's lineage.
            if let activeIdentity, try MetaDao.get(database, migrationHashKey) == nil,
               let source, try source.hasPayload,
               try MetaDao.get(database, migrationPendingOwnerKey) == nil {
                if case .ambiguous(let where_) = try legacyResidueScope(database, ownerKey: activeIdentity) {
                    throw LearningDbStoreError.migrationAborted(
                        "migration aborted: unattributed legacy content without a pending claim (\(where_))")
                }
            }
            if let activeIdentity { try LearnerOwnershipDao.activate(database, identity: activeIdentity) }
            if let source, try source.hasPayload, try MetaDao.get(database, migrationHashKey) == nil {
                try migrate(database, source, clock)
            }
            try ProgressRecoveryDao.recover(database, nowUnixMilliseconds: Int64(Date.now.timeIntervalSince1970 * 1000))
            return LearningDbStore(database: database, clock: clock, jsonMode: false, jsonModeNotice: nil)
        } catch {
            // Migration aborted (or the database/source broke underneath it):
            // production owners rethrow so the UI can offer a non-destructive
            // retry. Only identity-less migration tooling retains JSON mode.
            guard activeIdentity == nil, let source, try source.hasPayload else { throw error }
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

    /// Provenance of pre-existing learning content while the gate is open
    /// WITHOUT a pending claim (a pre-atomic version could commit content
    /// without the marker). FRESH: no learning rows at all - safe to claim.
    /// OWN: every learning row is this owner's - a demonstrated earlier
    /// attempt of the same identity, safe to clear and re-import.
    /// AMBIGUOUS: any other-owner or unattributed row - clearing or
    /// reattributing is not authorized, and the migration must be refused
    /// (preserved and communicated, never reported as recovered). The iOS
    /// archive model has no mastery cache, so ANY mastery row here is
    /// unattributed legacy content.
    fileprivate enum LegacyResidue {
        case fresh
        case own
        case ambiguous(where: String)
    }

    private static func migrate(_ database: SqlDatabase,
                                _ source: any LearningMigrationSource,
                                _ clock: () -> Double) throws {
        let payload = try source.readPayload()
        let archive = try Archive.decode(payload)
        // The iOS legacy payload carries no androidFinalAssessments retry
        // ledger (Android-only metadata) - nothing to decode here, and no
        // ticket rows are migrated (pinned by verifyCounts below).
        let sourceHash = sha256Hex(payload)
        if let ownerKey = try activeOwnerKey(database) {
            // The one-way migration belongs to the FIRST identity that
            // claimed it. A different active identity finding the claim
            // open must not adopt - or clear - another account's backup:
            // it opens in database mode with none of it and the payload
            // stays here for its owner's retry.
            let pendingOwner = try MetaDao.get(database, migrationPendingOwnerKey)
            if let pendingOwner, pendingOwner != ownerKey { return }
            if pendingOwner == nil {
                // No claim yet: either a fresh install or a database a
                // PRE-atomic version left with committed-but-unmarked
                // content. Only unambiguous provenance may claim now.
                if case .ambiguous(let where_) = try legacyResidueScope(database, ownerKey: ownerKey) {
                    throw LearningDbStoreError.migrationAborted(
                        "migration aborted: unattributed legacy content "
                            + "without a pending claim (\(where_))")
                }
            }
            try database.transaction { tx in
                try MetaDao.put(tx, migrationPendingOwnerKey, ownerKey)
                try MetaDao.put(tx, migrationPendingHashKey, sourceHash)
            }
        }
        // ONE transaction for clear + import + verification + bookkeeping
        // (the transaction wrapper does not nest): the old
        // import-commits-first split left rows on disk without the marker
        // whenever the bookkeeping failed. Any failure now rolls back to
        // the pre-migration state; the pending claim is what survives.
        try database.transaction { tx in
            try clearLearningTablesInTransaction(tx)
            try ArchiveMapper.importInTransaction(tx, archive)
            try verifyCounts(tx, archive)
            try MetaDao.put(tx, migratedAtKey, String(clock()))
            // The commit point: written last, read first by the gate above.
            try MetaDao.put(tx, migrationHashKey, sourceHash)
            _ = try tx.update("DELETE FROM meta WHERE key = ? OR key = ?",
                              args: [migrationPendingOwnerKey, migrationPendingHashKey])
        }
        do {
            try source.markMigrated()
        } catch {
            // Post-commit cleanup only: the hash is already written, so a
            // stranded payload record is ignored on every later launch.
            // Failing the migration here would discard a verified commit.
            print("LearningDbStore: committed migration but could not mark the previous store migrated - \(error)")
        }
    }

    /// Provenance scope of the learning content already in the database
    /// while the gate is open WITHOUT a pending claim - see the
    /// `LegacyResidue` documentation.
    private static func legacyResidueScope(_ db: SqlDatabase,
                                   ownerKey: String) throws -> LegacyResidue {
        var anyContent = false
        for table in ["fragments", "sessions", "expressions", "lesson_state",
                      "completions", "skill_errors", "usage_outbox", "assessment_tickets"] {
            let foreign = try db.query(
                "SELECT EXISTS(SELECT 1 FROM \(table) WHERE owner_key IS NULL OR owner_key = '' "
                    + "OR owner_key != ?) AS any_row", args: [ownerKey]).first!.bool("any_row")
            if foreign { return .ambiguous(where: table) }
            if !anyContent {
                anyContent = try db.query("SELECT EXISTS(SELECT 1 FROM \(table)) AS any_row")
                    .first!.bool("any_row")
            }
        }
        let mastery = try db.query(
            "SELECT EXISTS(SELECT 1 FROM meta WHERE key LIKE '\(MetaDao.masteryPrefix)%') AS any_row")
            .first!.bool("any_row")
        if mastery { return .ambiguous(where: "mastery") }
        return anyContent ? .own : .fresh
    }

    /// Deletes every learning row of the CLAIMING owner - everything
    /// `ArchiveMapper.importInTransaction` can write, plus the owner's
    /// mastery meta rows it replaces - inside the CALLER's migration
    /// transaction (the wrapper does not nest). Another account's rows are
    /// never cleared. Only ever called with the gate open (no
    /// migration_hash): a committed migration's rows are never cleared.
    /// Fragments get their own DELETE although sessions would cascade -
    /// explicit over FK-dependent.
    static func clearLearningTablesInTransaction(_ tx: SqlDatabase) throws {
        for table in ["fragments", "sessions", "expressions", "lesson_state",
                      "completions", "skill_errors", "usage_outbox", "assessment_tickets"] {
            _ = try tx.update("DELETE FROM \(table) WHERE \(try ownerClause(tx))",
                              args: try ownerArguments(tx))
        }
        let prefix = try activeOwnerKey(tx).map { "\(MetaDao.masteryPrefix)\($0):" } ?? MetaDao.masteryPrefix
        _ = try tx.update("DELETE FROM meta WHERE key LIKE ?", args: ["\(prefix)%"])
    }

    /// ANY mismatch aborts the migration (the throw rolls the content
    /// transaction back). Every count is scoped to the importing owner:
    /// the tables are shared across accounts, so another identity's
    /// legitimate rows must neither break the verification nor authorize
    /// clearing them. The retry ledger and the outbox have no archive-side
    /// source on iOS (the legacy payload never carried tickets, and usage
    /// events were never persisted in it), so both start empty - the
    /// mastery expectation is 0 for the same model-gap reason documented
    /// on `ArchiveMapper.import`.
    static func verifyCounts(_ db: SqlDatabase, _ archive: Archive) throws {
        let clause = try ownerClause(db)
        let args = try ownerArguments(db)
        func requireCount(_ label: String, _ sql: String, _ expected: Int64,
                          bind: [Any?]? = nil) throws {
            let actual = try db.query(sql, args: bind ?? args).first!.long("n")
            if actual != expected {
                throw LearningDbStoreError.migrationAborted(
                    "migration aborted: \(label) count \(actual) != \(expected)")
            }
        }
        try requireCount("sessions",
                         "SELECT COUNT(*) AS n FROM sessions WHERE \(clause)",
                         Int64(archive.sessions.count))
        try requireCount("fragments",
                         "SELECT COUNT(*) AS n FROM fragments WHERE \(clause)",
                         Int64(archive.sessions.reduce(0) { $0 + $1.fragments.count }))
        try requireCount("lessons",
                         "SELECT COUNT(*) AS n FROM lesson_state WHERE \(clause)",
                         Int64(archive.lessons?.count ?? 0))
        try requireCount("expressions",
                         "SELECT COUNT(*) AS n FROM expressions WHERE \(clause)",
                         Int64(archive.expressions?.count ?? 0))
        try requireCount("completions",
                         "SELECT COUNT(*) AS n FROM completions WHERE \(clause)",
                         Int64(archive.completions?.count ?? 0))
        try requireCount("skill errors",
                         "SELECT COUNT(*) AS n FROM skill_errors WHERE \(clause)",
                         Int64(archive.errors?.count ?? 0))
        let masteryPrefix = try activeOwnerKey(db)
            .map { "\(MetaDao.masteryPrefix)\($0):" } ?? MetaDao.masteryPrefix
        try requireCount("mastery meta rows",
                         "SELECT COUNT(*) AS n FROM meta WHERE key LIKE ?", 0,
                         bind: ["\(masteryPrefix)%"])
        if try MetaDao.get(db, MetaDao.schemaFlagsKey) == nil {
            throw LearningDbStoreError.migrationAborted(
                "migration aborted: schema_flags row missing after import")
        }
        try requireCount("assessment tickets",
                         "SELECT COUNT(*) AS n FROM assessment_tickets WHERE \(clause)", 0)
        try requireCount("usage outbox",
                         "SELECT COUNT(*) AS n FROM usage_outbox WHERE \(clause)", 0)
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
