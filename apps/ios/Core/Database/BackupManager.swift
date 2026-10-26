import Foundation
import GRDB

/// Why a database backup was rejected by the validation gate — the Swift
/// mirror of the Kotlin `BackupManager.BackupIssue`. The App layer resolves
/// the user-facing notice from it, so the user-facing copy lives with every
/// other notice and never as an inline literal in the data layer.
public enum BackupIssue: Equatable, Sendable, Error {
    /// Not a SQLite file, or failed integrity / foreign-key / version checks.
    case corrupt
    /// A backup from a newer schema must never be applied by an older app.
    case futureVersion
    /// A well-formed database that is not a learning database.
    case missingTables
    /// Zero bytes.
    case empty
    /// Over `BackupManager.maximumDatabaseBytes`.
    case tooLarge
}

/// Raised when an incoming (or exported) database backup fails validation.
/// `issue` is the typed reason the App layer maps to its notice; `cause` is
/// the underlying backend failure when one exists (a file that could not
/// open, a PRAGMA that could not run) — for logs, never for UI. Equality
/// compares the issue only, which is what every caller branches on.
public struct InvalidBackup: Error, Equatable {
    public let issue: BackupIssue
    public let cause: (any Error)?

    public init(_ issue: BackupIssue, cause: (any Error)? = nil) {
        self.issue = issue
        self.cause = cause
    }

    public static func == (lhs: InvalidBackup, rhs: InvalidBackup) -> Bool {
        lhs.issue == rhs.issue
    }
}

/// The non-validation failures the backup flow throws (the Kotlin
/// `error(...)` calls, typed).
public enum BackupManagerError: Error, CustomStringConvertible {
    /// The WAL TRUNCATE checkpoint could not finish (busy != 0 — a
    /// concurrent reader or writer held the WAL): the copy would verify
    /// clean but could be stale, so the export fails loudly instead.
    case checkpointBusy
    /// The staged backup could not be renamed into place.
    case installFailed(String)
    /// Authored lesson ids no longer start with their language id, so the
    /// per-language lesson_state delete prefix would be wrong.
    case lessonPrefixMismatch(String)

    public var description: String {
        switch self {
        case .checkpointBusy:
            return "learning.db WAL checkpoint did not complete (busy); the export would "
                + "miss the newest commits — retry with no pending writes"
        case .installFailed(let target):
            return "could not move the staged backup into place at \(target)"
        case .lessonPrefixMismatch(let languageID):
            return "lesson ids no longer start with \"\(languageID)-\"; the per-language "
                + "lesson_state delete prefix is wrong"
        }
    }
}

/// Per-language delete row counts. GRDB's `update` reports real counts (the
/// JVM-backend convenience of the Kotlin `LanguageDeletion`, never
/// cross-platform state).
public struct LanguageDeletion: Equatable, Sendable {
    public var sessions: Int64
    public var fragments: Int64
    public var completions: Int64
    public var expressions: Int64
    public var lessonStates: Int64
    public var tickets: Int64
    public var examAttempts: Int64
    public var examSnapshots: Int64

    public init(sessions: Int64, fragments: Int64, completions: Int64,
                expressions: Int64, lessonStates: Int64, tickets: Int64,
                examAttempts: Int64 = 0, examSnapshots: Int64 = 0) {
        self.sessions = sessions
        self.fragments = fragments
        self.completions = completions
        self.expressions = expressions
        self.lessonStates = lessonStates
        self.tickets = tickets
        self.examAttempts = examAttempts
        self.examSnapshots = examSnapshots
    }
}

/**
 * Phase-F database backup/restore for the learning database (schema v3) —
 * the Swift mirror of the Kotlin `BackupManager`. File-level, unlike the
 * JSON archive backup: the .db bytes travel whole, so everything the JSON
 * backup never carried (the usage_outbox queue, the platform's
 * assessment_tickets rows, the mastery meta rows) moves with it.
 *
 * `exportTo` never trusts the copy it made: after a WAL TRUNCATE checkpoint
 * on the LIVE database (so the main file holds every committed page — see
 * the WAL note below), it copies the file bytes, writes the copy to a temp
 * file, opens THAT copy through a second `SqlDatabase` and demands
 *
 *   - `PRAGMA integrity_check` → exactly one row, "ok"
 *   - `PRAGMA foreign_key_check` → no rows
 *   - `PRAGMA user_version` in 1...`LearningDatabase.version` (a
 *     CURRENT-schema backup installs as-is; a v1 backup — every shipped
 *     release before the FTS migration — also passes and migrates on the
 *     next open, because the opener's upgrade path reaches the current
 *     schema; a backup from a newer schema must never be applied by an
 *     older app, and a file at user_version 0 was never a learning database)
 *   - the ten canonical tables of shared/db/schema.sql present
 *
 * and only then hands the bytes back. The verification proves the copy's
 * INTEGRITY, not its freshness: freshness is the checkpoint's job, so the
 * (busy, log, checkpointed) row is checked and a checkpoint that could not
 * finish (busy != 0) fails the export loudly instead of shipping a stale
 * backup that would still verify clean.
 *
 * WAL note: Android's framework SQLite opens databases in WAL mode by
 * default (API 28+), where a plain file copy would silently miss commits
 * still sitting in learning.db-wal — hence the checkpoint first. GRDB's
 * DatabaseQueue uses rollback-journal (delete) mode by default, so
 * `PRAGMA wal_checkpoint(TRUNCATE)` is a no-op there (it returns
 * busy = 0, log = -1, checkpointed = -1 and touches nothing); the file copy
 * is already complete on that backend. A WAL-mode database restored from an
 * Android is checkpointed by the same call. Both paths pinned by
 * BackupManagerTests.
 *
 * `readValidatedBackup` is the import gate: bounded read, then the SAME
 * verification over the incoming bytes (through a throwaway temp copy), so
 * a corrupt file, a future-schema backup or a database without the learning
 * tables is rejected BEFORE the caller touches the live database. The
 * caller (LearningStore) owns the live-file dance: close the store
 * (`SqlDatabase.close` — the one call site that E kept open for this),
 * `stageDatabase` the new bytes beside the live file, set the previous file
 * aside, `installDatabase` the staged file over it (same-directory rename,
 * atomic on POSIX), reopen the store — and put the previous file back if
 * anything in that sequence fails (never by deleting: the keep-copy's
 * rename atomically replaces whatever is in place). The current database is
 * never destroyed unless validation fully passed, and a crash between the
 * two renames strands it as learning.db.preimport, which
 * `LearningDbStore.adoptPreimportDatabase` adopts back at startup.
 *
 * `deleteLearningDataForLanguage` implements the per-language delete in ONE
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
 *     `CourseRegistry` in `lessonStatePattern`
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
public enum BackupManager {
    public static let maximumDatabaseBytes = 64 * 1024 * 1024

    /// The sixteen tables shared/db/schema.sql creates, pinned by
    /// scripts/check_db_schema.py. A backup missing any of them is not a
    /// learning database.
    public static let canonicalTables: [String] = [
        "meta", "preferences", "sessions", "fragments", "expressions",
        "lesson_state", "completions", "skill_errors", "usage_outbox",
        "assessment_tickets", "exam_snapshots", "exam_snapshot_ids",
        "exam_attempts", "exam_answers", "exam_results", "exam_sync_outbox",
    ]

    /// Tables present before schema v3 introduced durable exam history.
    private static let legacyCanonicalTables = Array(canonicalTables.prefix(10))

    // User-facing rejection copy lives with the App-layer notices; the gate
    // throws `InvalidBackup` with the typed `BackupIssue` instead.

    /// The production verification opener: a short-lived second GRDB
    /// connection over a candidate file (the exported copy or the incoming
    /// backup), foreign keys on like every learning-database connection.
    /// `verify` always closes what this opens — the single-writer queue's
    /// `close()` is why `SqlDatabase` grew one in phase E.
    public static func openForVerification(_ file: URL) throws -> SqlDatabase {
        let configuration = Configuration()
        configuration.foreignKeysEnabled = true
        configuration.maximumReaderCount = 1
        return GRDBDatabase(queue: try DatabaseQueue(path: file.path, configuration: configuration))
    }

    /**
     * Copies the live database behind `databaseFile` — verbatim and verified,
     * see the enum doc — and returns the verified bytes. The caller owns
     * draining pending writes first (the iOS directed writes are synchronous,
     * so there is nothing to drain) and writing/handing on the returned
     * bytes; the verification temp file lives beside `databaseFile` and is
     * always deleted.
     */
    public static func exportTo(_ database: SqlDatabase, databaseFile: URL,
                                openForVerify: (URL) throws -> SqlDatabase = BackupManager.openForVerification) throws -> Data {
        // Framework SQLite runs WAL by default on Android: fold learning.db-wal
        // into the main file so the bytes below are the whole story. A no-op in
        // rollback-journal mode (GRDB's default: busy = 0, log = -1,
        // checkpointed = -1). busy != 0 means the truncate could not finish
        // (a concurrent reader/writer held the WAL) — the copy would be VALID
        // (verification catches integrity) but possibly STALE (it cannot
        // catch freshness), so the export fails loudly instead.
        let checkpoint = try database.query("PRAGMA wal_checkpoint(TRUNCATE)")
        if let busy = checkpoint.first?["busy"] as? Int64, busy != 0 {
            throw BackupManagerError.checkpointBusy
        }
        let bytes = try Data(contentsOf: databaseFile)
        let copy = databaseFile.deletingLastPathComponent()
            .appendingPathComponent(databaseFile.lastPathComponent + ".backup-verify")
        defer { try? FileManager.default.removeItem(at: copy) }
        try bytes.write(to: copy)
        try verify(copy, openForVerify)
        return bytes
    }

    /**
     * The import gate: reads at most `maximumDatabaseBytes` from `url`,
     * verifies the bytes as a learning database through a throwaway copy in
     * `workDirectory` (same checks as `exportTo`), and only then hands them
     * back for `installDatabase`. Throws `InvalidBackup` with the typed
     * `BackupIssue` otherwise — the live database is not even open here. A
     * file that cannot be opened at all is NOT a validation rejection: its
     * own (platform) error propagates raw, for the caller's generic failure
     * notice — the Kotlin mirror's plain exception.
     */
    public static func readValidatedBackup(from url: URL, workDirectory: URL,
                                           openForVerify: (URL) throws -> SqlDatabase = BackupManager.openForVerification) throws -> Data {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var data = Data()
        while true {
            let chunk = try file.read(upToCount: 64 * 1024) ?? Data()
            if chunk.isEmpty { break }
            if data.count + chunk.count > maximumDatabaseBytes { throw InvalidBackup(.tooLarge) }
            data.append(chunk)
        }
        if data.isEmpty { throw InvalidBackup(.empty) }
        let copy = workDirectory.appendingPathComponent("backup-import-verify-\(UUID().uuidString).db")
        defer { try? FileManager.default.removeItem(at: copy) }
        try data.write(to: copy)
        try verify(copy, openForVerify)
        return data
    }

    /// The verified backup's validation half, shared by export and import.
    private static func verify(_ candidate: URL,
                               _ openForVerify: (URL) throws -> SqlDatabase) throws {
        let db: SqlDatabase
        do { db = try openForVerify(candidate) }
        catch { throw InvalidBackup(.corrupt, cause: error) }
        defer { db.close() }
        do {
            let integrity = try db.query("PRAGMA integrity_check")
            guard integrity.count == 1, integrity[0].values.count == 1,
                  integrity[0].values[0] as? String == "ok" else { throw InvalidBackup(.corrupt) }
            guard let version = try db.query("PRAGMA user_version").first?.values.first as? Int64
            else { throw InvalidBackup(.corrupt) }
            if version > Int64(LearningDatabase.version) { throw InvalidBackup(.futureVersion) }
            // v1 is still a learning database: the gate accepts it and the
            // post-install open migrates it in place (LearningDatabase.upgrade
            // — fragments keep their text, the FTS index 'rebuild' covers them).
            if version < 1 { throw InvalidBackup(.corrupt) }
            if try !db.query("PRAGMA foreign_key_check").isEmpty { throw InvalidBackup(.corrupt) }
            let tables = try db.query("SELECT name FROM sqlite_master WHERE type = 'table'")
                .map { $0.text("name") }
            let requiredTables = version >= 3 ? canonicalTables : legacyCanonicalTables
            if !requiredTables.allSatisfy({ tables.contains($0) }) { throw InvalidBackup(.missingTables) }
        } catch let failure as InvalidBackup {
            throw failure
        } catch {
            throw InvalidBackup(.corrupt, cause: error)
        }
    }

    /**
     * Step one of the crash-safe install: writes `bytes` to `staged`
     * (target.path + ".install-tmp") WITHOUT touching the live database file.
     * Between `stageDatabase` and `installDatabase` BOTH files exist, so a
     * process death in that window leaves the previous database intact (and
     * a leftover staged file that the next install overwrites). Callers
     * clean a failed stage's leftover up themselves.
     */
    public static func stageDatabase(_ staged: URL, bytes: Data) throws {
        try bytes.write(to: staged)
    }

    /**
     * Step two: renames the staged bytes over `target` (atomic on POSIX —
     * readers see the whole old file or the whole new one). Callers set the
     * previous file aside FIRST (rename it away) so a failure here leaves
     * the old bytes recoverable as learning.db.preimport — which
     * `LearningDbStore.adoptPreimportDatabase` adopts back at startup after
     * a crash. `renameItem` replaces whatever is in place (POSIX rename
     * semantics) and THROWS on failure, unlike the C call — the caller's
     * recovery handles both outcomes through the keep-copy rename, never by
     * deleting a database file.
     */
    public static func installDatabase(_ staged: URL, target: URL) throws {
        do {
            try FileManager.default.renameItem(at: staged, to: target)
        } catch {
            throw BackupManagerError.installFailed(target.path)
        }
    }

    /// Convenience two-step install in one call (stage + swap).
    public static func installDatabase(_ target: URL, bytes: Data) throws {
        let staged = URL(fileURLWithPath: target.path + ".install-tmp")
        try stageDatabase(staged, bytes: bytes)
        try installDatabase(staged, target: target)
    }

    /**
     * Deletes every row of `languageID`'s learning data in ONE transaction —
     * the exact semantics documented in the enum doc. Returns the per-table
     * row counts.
     */
    public static func deleteLearningDataForLanguage(_ db: SqlDatabase,
                                                     languageID: String) throws -> LanguageDeletion {
        let lessonPattern = try lessonStatePattern(languageID)
        return try db.transaction { tx in
            let fragments = try tx.query(
                "SELECT COUNT(*) AS n FROM fragments WHERE session_id IN " +
                "(SELECT id FROM sessions WHERE language_id = ? AND \(ownerClause(tx))) AND \(ownerClause(tx))",
                args: [languageID] + (try ownerArguments(tx)) + (try ownerArguments(tx)))
                .first!.long("n")
            // Tickets first: after the sessions go, the subselect finds nothing.
            let tickets = try tx.update(
                "DELETE FROM assessment_tickets WHERE session_id IN " +
                "(SELECT id FROM sessions WHERE language_id = ? AND \(ownerClause(tx))) AND \(ownerClause(tx))",
                args: [languageID] + (try ownerArguments(tx)) + (try ownerArguments(tx)))
            let sessions = try tx.update("DELETE FROM sessions WHERE language_id = ? AND \(ownerClause(tx))",
                                         args: [languageID] + (try ownerArguments(tx)))
            let completions = try tx.update("DELETE FROM completions WHERE language_id = ? AND \(ownerClause(tx))",
                                            args: [languageID] + (try ownerArguments(tx)))
            let expressions = try tx.update("DELETE FROM expressions WHERE language_id = ? AND \(ownerClause(tx))",
                                            args: [languageID] + (try ownerArguments(tx)))
            let lessonStates = try tx.update(
                "DELETE FROM lesson_state WHERE lesson_id LIKE ? ESCAPE '\\' AND \(ownerClause(tx))",
                args: [lessonPattern] + (try ownerArguments(tx)))
            let examAttempts = try tx.query("SELECT COUNT(*) AS n FROM exam_attempts WHERE exam_id IN " +
                "(SELECT i.exam_id FROM exam_snapshot_ids i JOIN exam_snapshots s ON s.content_id = i.content_id " +
                "WHERE s.language_id = ?) AND \(ownerClause(tx))",
                args: [languageID] + (try ownerArguments(tx))).first!.long("n")
            _ = try tx.update("DELETE FROM exam_attempts WHERE exam_id IN (SELECT i.exam_id FROM exam_snapshot_ids i " +
                "JOIN exam_snapshots s ON s.content_id = i.content_id WHERE s.language_id = ?) AND \(ownerClause(tx))",
                args: [languageID] + (try ownerArguments(tx)))
            let examSnapshots: Int64 = 0 // Immutable exam snapshots are device-global content cache.
            return LanguageDeletion(sessions: sessions, fragments: fragments,
                                    completions: completions, expressions: expressions,
                                    lessonStates: lessonStates, tickets: tickets,
                                    examAttempts: examAttempts, examSnapshots: examSnapshots)
            // usage_outbox, meta, preferences, skill_errors: deliberately
            // untouched — see the enum doc.
        }
    }

    /**
     * The lesson_state selector for `languageID`: '<languageID>-%', with
     * LIKE wildcards in the id escaped (defense in depth — registry ids are
     * plain lowercase slugs today, so `%`/`_` never occur, but an unescaped
     * `_` would silently over-match other languages' lesson rows). The
     * prefix itself is derived, never hardcoded — and verified at runtime
     * against the actual `CourseRegistry` content, exactly as the
     * shared/courses JSON pins it today: every authored lesson id begins
     * with its language id plus a dash (en-foundations-u1-to-be,
     * en-care-u1-appointment, es-work-u1-reuniones, de-foundations-u1-sein),
     * so the language prefix alone selects exactly this language's
     * lesson_state rows — including stale rows left by courses since removed
     * from the catalog, which per-course prefixes would miss. A language
     * with no registry courses falls back to the plain language prefix. If a
     * future content change breaks the invariant, the throw fails loudly and
     * the delete aborts instead of deleting the wrong rows. Case
     * sensitivity: SQL LIKE is ASCII-case-insensitive while the working-copy
     * purge matches with `hasPrefix` — acceptable divergence because
     * registry ids (and every authored lesson id) are lowercase-only,
     * pinned by `CourseRegistry`.
     */
    static func lessonStatePattern(_ languageID: String) throws -> String {
        let lessonIDs = CourseRegistry.forLanguage(languageID)
            .flatMap { $0.units.flatMap { $0.lessons } }.map { $0.id }
        guard lessonIDs.allSatisfy({ $0.hasPrefix("\(languageID)-") }) else {
            throw BackupManagerError.lessonPrefixMismatch(languageID)
        }
        return escapeLike("\(languageID)-") + "%"
    }

    /// Escapes the LIKE wildcards for the `ESCAPE '\'` clause on the
    /// per-language delete. Backslash itself is escaped first.
    private static func escapeLike(_ text: String) -> String {
        return text.reduce(into: "") { escaped, character in
            if character == "\\" || character == "%" || character == "_" { escaped.append("\\") }
            escaped.append(character)
        }
    }
}
