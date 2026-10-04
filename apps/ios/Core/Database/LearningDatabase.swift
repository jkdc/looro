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
            return "Learning schema has no upgrade path yet (found \(found), want \(want))"
        case .neverDowngrades(let found, let want):
            return "Learning schema never downgrades (found \(found), want \(want))"
        }
    }
}

/**
 * Opens the learner's learning database (schema v1, shared/db/schema.sql via
 * `SchemaData`) — the Swift mirror of the Kotlin `LearningDb` opener. A fresh
 * database (PRAGMA user_version 0) receives every schema statement inside one
 * transaction, which the schema's final `PRAGMA user_version = 1` completes;
 * any other version has no migration path yet and throws. Foreign keys are
 * enforced on every connection, as the schema header requires
 * (`Configuration.foreignKeysEnabled`, GRDB's equivalent of AndroidX's
 * onConfigure).
 */
public enum LearningDatabase {
    /// Bump only together with a migration; shared/db/schema.sql pins the shape.
    public static let version = 1
    public static let name = "learning.db"

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
        if userVersion == 0 {
            // All-or-nothing like SQLiteOpenHelper.onCreate: a crash midway
            // leaves user_version 0 and the statements simply run again.
            try db.transaction { tx in
                for statement in SchemaData.learningSchemaStatements {
                    try tx.exec(statement)
                }
            }
        } else if userVersion < Int64(version) {
            throw LearningDatabaseError.noUpgradePath(found: Int(userVersion), want: version)
        } else if userVersion > Int64(version) {
            throw LearningDatabaseError.neverDowngrades(found: Int(userVersion), want: version)
        }
        return db
    }
}
