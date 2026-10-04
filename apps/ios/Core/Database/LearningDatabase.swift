import Foundation
import GRDB

/// Failures `LearningDatabase.open` throws on a database whose user_version
/// it cannot reach — the mirror of the Kotlin helper's `onUpgrade`/
/// `onDowngrade` errors.
public enum LearningDatabaseError: Error, Equatable, CustomStringConvertible {
    case noUpgradePath(found: Int, want: Int)
    case neverDowngrades(found: Int, want: Int)

    public var description: String {
        switch self {
        case .noUpgradePath(let found, let want):
            return "Learning schema has no upgrade path (found \(found), want \(want))"
        case .neverDowngrades(let found, let want):
            return "Learning schema never downgrades (found \(found), want \(want))"
        }
    }
}

/**
 * Opens the learner's learning database (schema v2, shared/db/schema.sql via
 * `SchemaData`) — the Swift mirror of the Kotlin `LearningDb` opener. The
 * schema moves in ORDERED MIGRATION STEPS keyed by `PRAGMA user_version`
 * (the schema's own versioning mechanism, pinned by the checker):
 *
 *   - step 1: the v1 statements (the canonical list up to and including its
 *     `PRAGMA user_version = 1`);
 *   - step 2+: one entry per exporter-pinned upgrade delta
 *     (`SchemaData.schemaUpgrades`), e.g. v2's FTS index + sync triggers +
 *     one-time 'rebuild'.
 *
 * A FRESH database (user_version 0) runs every step inside ONE transaction,
 * which the closer completes by recording `version`; any other installed
 * version below it runs only the later steps (`upgrade`), so fresh and
 * upgraded databases converge on the same end state. A HIGHER user_version
 * never downgrades and throws. GRDB's `DatabaseMigrator` is deliberately NOT
 * used: its private `grdb_migrations` bookkeeping table would appear only on
 * this platform's databases (breaking the pinned sqlite_master inventory the
 * two platforms share, and diverging from the Android twin whose databases
 * travel whole through backups). Foreign keys are enforced on every
 * connection, as the schema header requires.
 */
public enum LearningDatabase {
    /// Bump only together with a migration; shared/db/schema.sql pins the shape.
    public static let version = 2
    public static let name = "learning.db"

    /// The ordered migration steps (see the KDoc). The v1 slice is cut at the
    /// schema's own v1 PRAGMA so the canonical file stays the single source.
    private static let migrationSteps: [(version: Int, statements: [String])] = {
        let all = SchemaData.learningSchemaStatements
        guard let boundary = all.firstIndex(of: "PRAGMA user_version = 1") else {
            fatalError("SchemaData lost the v1 PRAGMA terminator; the schema or the exporter drifted")
        }
        return [(1, Array(all[...boundary]))]
            + SchemaData.schemaUpgrades.keys.sorted().map { ($0, SchemaData.schemaUpgrades[$0] ?? []) }
    }()

    /// Applies every migration step after `fromVersion` inside one
    /// transaction, then records `version` — the mirror of the Kotlin
    /// Helper's onUpgrade (SQLiteOpenHelper supplies the transaction and the
    /// recorded version; here they are ours, and PRAGMA user_version is
    /// transactional like any other write).
    public static func upgrade(_ db: SqlDatabase, from fromVersion: Int) throws {
        guard fromVersion >= 1, fromVersion < version else {
            throw LearningDatabaseError.noUpgradePath(found: fromVersion, want: version)
        }
        try db.transaction { tx in
            for step in migrationSteps where step.version > fromVersion {
                for statement in step.statements {
                    try tx.exec(statement)
                }
            }
            try tx.exec("PRAGMA user_version = \(version)")
        }
    }

    /// Opens (creating if needed) the learning database at `path`.
    public static func open(at path: String) throws -> SqlDatabase {
        try open(DatabaseQueue(path: path, configuration: configuration()))
    }

    /// In-memory opener for tests and previews: the same statements, the same
    /// foreign-key enforcement, no file. GRDB's anonymous in-memory database
    /// (`named: nil`) lives only as long as the connection.
    public static func openInMemory() throws -> SqlDatabase {
        try open(DatabaseQueue(named: nil, configuration: configuration()))
    }

    /// Test support: opens a database at the SHIPPED v1 schema — the v1
    /// migration step only, left at user_version 1 with no FTS — so the
    /// upgrade paths have a real v1 database to migrate (the mirror of the
    /// JVM tests' `openJvmV1`).
    public static func openV1(at path: String) throws -> SqlDatabase {
        let db = GRDBDatabase(queue: try DatabaseQueue(path: path, configuration: configuration()))
        try db.transaction { tx in
            for statement in migrationSteps[0].statements {
                try tx.exec(statement)
            }
        }
        return db
    }

    private static func configuration() -> Configuration {
        let configuration = Configuration()
        configuration.foreignKeysEnabled = true
        // One connection for reads and writes alike, like the single-connection
        // Kotlin backends (DatabaseQueue enforces this too); using the database
        // after close() then fails with a clean SQLITE_MISUSE throw.
        configuration.maximumReaderCount = 1
        return configuration
    }

    private static func open(_ queue: DatabaseQueue) throws -> SqlDatabase {
        let db = GRDBDatabase(queue: queue)
        let userVersion = try db.query("PRAGMA user_version").first.flatMap { row -> Int64? in
            row["user_version"] as? Int64
        } ?? 0
        switch userVersion {
        case 0:
            // Fresh install: every migration step inside one transaction,
            // all-or-nothing like SQLiteOpenHelper.onCreate (a crash midway
            // leaves user_version 0 and the steps simply run again).
            try db.transaction { tx in
                for step in migrationSteps {
                    for statement in step.statements {
                        try tx.exec(statement)
                    }
                }
                try tx.exec("PRAGMA user_version = \(version)")
            }
        case Int64(version):
            break // current schema
        case let other where other >= 1 && other < Int64(version):
            try upgrade(db, from: Int(other))
        case let other:
            throw other < Int64(version)
                ? LearningDatabaseError.noUpgradePath(found: Int(other), want: version)
                : LearningDatabaseError.neverDowngrades(found: Int(other), want: version)
        }
        return db
    }
}
