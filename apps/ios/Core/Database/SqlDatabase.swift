import Foundation
import GRDB

/// One SQLite row: column names in order with their values, plus a name lookup.
/// The Swift mirror of the Kotlin data layer's `Row` (named `SqlRow` to stay
/// unambiguous next to GRDB's own `Row`). Values arrive typed by the backend
/// (GRDB's `DatabaseValue.storage`: INTEGER as Int64, REAL as Double, TEXT as
/// String, BLOB as Data, NULL as nil), so writes must stay schema-conformant
/// for the typed reads to stay predictable.
public struct SqlRow {
    public let names: [String]
    public let values: [Any?]
    private let byName: [String: Any?]

    public init(names: [String], values: [Any?]) {
        precondition(names.count == values.count, "SqlRow names/values length mismatch")
        self.names = names
        self.values = values
        var byName: [String: Any?] = [:]
        // duplicate column names keep the last one, like Kotlin's toMap
        for (name, value) in zip(names, values) {
            byName[name] = value
        }
        self.byName = byName
    }

    /// The value of the column with this name (nil for SQL NULL or an unknown name).
    public subscript(name: String) -> Any? {
        byName[name] ?? nil
    }
}

/// Typed column readers for the DAO layer; every column is narrowed at the
/// DAO boundary exactly like the Kotlin `Row.bool/int/long/real/text`
/// extensions. A stored type that does not match the schema's declaration is
/// a programmer error and traps, like the Kotlin cast.
extension SqlRow {
    func bool(_ name: String) -> Bool { (self[name] as! Int64) != 0 }
    func int(_ name: String) -> Int { Int(self[name] as! Int64) }
    func long(_ name: String) -> Int64 { self[name] as! Int64 }
    func real(_ name: String) -> Double { self[name] as! Double }
    func text(_ name: String) -> String { self[name] as! String }
    func boolOrNull(_ name: String) -> Bool? { (self[name] as? Int64).map { $0 != 0 } }
    func intOrNull(_ name: String) -> Int? { (self[name] as? Int64).map(Int.init) }
    func realOrNull(_ name: String) -> Double? { self[name] as? Double }
    func textOrNull(_ name: String) -> String? { self[name] as? String }
}

/// SQLite conflict strategies; the name is the SQL keyword of INSERT OR <name>.
public enum ConflictStrategy: String, Sendable {
    case rollback = "ROLLBACK"
    case abort = "ABORT"
    case fail = "FAIL"
    case ignore = "IGNORE"
    case replace = "REPLACE"
}

/// The failures the contract below throws, mirroring the Kotlin exceptions.
public enum SqlDatabaseError: Error, Equatable, CustomStringConvertible {
    /// A null bind argument reached query/update (position is 1-based).
    case nullBindArgument(position: Int)
    /// A transaction block opened another transaction.
    case nestedTransaction
    /// A bind or row value the backend cannot carry.
    case unsupportedValue(String)

    public var description: String {
        switch self {
        case .nullBindArgument(let position):
            return "null bind argument at position \(position): null checks must be spelled IS NULL"
        case .nestedTransaction:
            return "nested transactions are not supported"
        case .unsupportedValue(let detail):
            return "Unsupported SQLite value: \(detail)"
        }
    }
}

/**
 * The SQLite surface the learning store needs, one GRDB implementation
 * (`GRDBDatabase`) — the Swift mirror of the Kotlin `SqlDatabase` interface,
 * whose Android and JVM backends it replaces here. DAOs written against this
 * protocol run on both platforms with identical semantics. Values are the
 * SQLite types Swift can carry: nil, Int/Int64, Double, Bool (stored as 0/1),
 * String, Data. Bind arguments of `query` and `update` must never be nil —
 * both platforms throw — because SQL NULL inside a comparison (`!= ?`,
 * `NOT IN`) behaves differently per backend; null checks are spelled
 * `IS NULL`.
 *
 * The single-threaded contract of the Kotlin backends carries over: one
 * `SqlDatabase` value belongs to one flow of control; keep transactions short.
 */
public protocol SqlDatabase: AnyObject {
    /// Runs one statement (DDL, PRAGMA or update) that returns no rows.
    func exec(_ statement: String) throws

    /// Inserts one row into `table`, resolving conflicts with `strategy`;
    /// returns the new row id, or -1 when the strategy swallowed the insert.
    func insert(_ table: String, _ row: [String: Any?], strategy: ConflictStrategy) throws -> Int64

    /// Runs a parameterized query and returns its rows (empty when none match).
    func query(_ sql: String, args: [Any?]) throws -> [SqlRow]

    /// Runs a parameterized DML statement (UPDATE/DELETE and friends) and
    /// returns the affected row count. The count is a single-connection
    /// convenience, never cross-platform state (Android's execSQL cannot
    /// report one and returns -1 there).
    func update(_ sql: String, args: [Any?]) throws -> Int64

    /**
     * Runs `block` inside one transaction: BEGIN IMMEDIATE … COMMIT, or
     * ROLLBACK when `block` throws (the failure is rethrown either way).
     * Not re-entrant: a nested call throws. Keep transactions short and
     * single-threaded, like SQLiteDatabase itself.
     */
    func transaction<T>(_ block: (SqlDatabase) throws -> T) throws -> T

    /**
     * Closes the underlying connection. One call site only: the database
     * backup/restore flow closes the live database BEFORE an imported backup
     * file replaces it, then reopens a fresh store — writing to a replaced
     * file through the old connection would land on the unlinked inode and
     * vanish. Using a closed database throws.
     */
    func close()
}

// Convenience overloads for the Kotlin default arguments.
public extension SqlDatabase {
    func insert(_ table: String, _ row: [String: Any?]) throws -> Int64 {
        try insert(table, row, strategy: .abort)
    }

    func query(_ sql: String) throws -> [SqlRow] {
        try query(sql, args: [])
    }

    func update(_ sql: String) throws -> Int64 {
        try update(sql, args: [])
    }
}

/// Shared guard: a null bind argument would behave differently across
/// backends inside `!= ?` / `NOT IN` comparisons, so both fail loudly instead
/// and null checks are spelled `IS NULL`.
func requireNonNullArgs(_ args: [Any?]) throws {
    if let index = args.firstIndex(where: { $0 == nil }) {
        throw SqlDatabaseError.nullBindArgument(position: index + 1)
    }
}

/**
 * The one [SqlDatabase] implementation, over GRDB's DatabaseQueue. Outside a
 * transaction every call runs on the queue (reads on a reader, writes on the
 * writer); inside `transaction` all calls run on the one connection GRDB
 * handed the transaction, so the block's `SqlDatabase` behaves exactly like
 * the Kotlin backends' single connection.
 */
public final class GRDBDatabase: SqlDatabase {

    private let queue: DatabaseQueue
    /// Set for the duration of a transaction; single-threaded use per the
    /// protocol contract, like the Kotlin backends' unsynchronized flag.
    private var activeDatabase: Database?

    public init(queue: DatabaseQueue) {
        self.queue = queue
    }

    public func exec(_ statement: String) throws {
        try withDatabase { try $0.execute(sql: statement) }
    }

    public func insert(_ table: String, _ row: [String: Any?], strategy: ConflictStrategy) throws -> Int64 {
        let columns = Array(row.keys)
        let sql = "INSERT OR \(strategy.rawValue) INTO \(table) "
            + "(\(columns.joined(separator: ", "))) "
            + "VALUES (\(columns.map { _ in "?" }.joined(separator: ", ")))"
        let bindings = try columns.map { column in
            try bindable(row[column], column: column)
        }
        return try withDatabase { db in
            try db.execute(sql: sql, arguments: StatementArguments(bindings))
            // A swallowed INSERT OR IGNORE reports zero changes while
            // lastInsertedRowID would still hold the previous insert's id,
            // so the changes count decides, exactly like the -1 backends.
            return db.changesCount == 0 ? -1 : db.lastInsertedRowID
        }
    }

    public func query(_ sql: String, args: [Any?]) throws -> [SqlRow] {
        try requireNonNullArgs(args)
        let bindings = try args.map { try bindable($0) }
        // GRDB Row materializes fully inside the block, so the returned
        // values are safe to use afterwards.
        return try withReadDatabase { db in
            try Row.fetchAll(db, sql: sql, arguments: StatementArguments(bindings)).map(SqlRow.init(row:))
        }
    }

    public func update(_ sql: String, args: [Any?]) throws -> Int64 {
        try requireNonNullArgs(args)
        let bindings = try args.map { try bindable($0) }
        return try withDatabase { db in
            try db.execute(sql: sql, arguments: StatementArguments(bindings))
            return Int64(db.changesCount)
        }
    }

    public func transaction<T>(_ block: (SqlDatabase) throws -> T) throws -> T {
        guard activeDatabase == nil else { throw SqlDatabaseError.nestedTransaction }
        var result: T?
        // writeWithoutTransaction, because GRDB's write() would open its own
        // transaction around the explicit BEGIN IMMEDIATE below.
        try queue.writeWithoutTransaction { db in
            // BEGIN IMMEDIATE … COMMIT; a thrown failure rolls back and is
            // rethrown, exactly like the Kotlin backends.
            try db.inTransaction(.immediate) { () -> Database.TransactionCompletion in
                self.activeDatabase = db
                defer { self.activeDatabase = nil }
                result = try block(self)
                return .commit
            }
        }
        // set whenever inTransaction returned; a failed block rethrew instead
        return result!
    }

    public func close() {
        try? queue.close()
    }

    // MARK: plumbing

    /// Runs on the transaction's connection inside a transaction, else on the
    /// writer (every write goes through GRDB's serialized writer).
    private func withDatabase<T>(_ body: (Database) throws -> T) throws -> T {
        if let active = activeDatabase { return try body(active) }
        return try queue.write(body)
    }

    /// Same as `withDatabase`, but reads outside a transaction use a reader.
    private func withReadDatabase<T>(_ body: (Database) throws -> T) throws -> T {
        if let active = activeDatabase { return try body(active) }
        return try queue.read(body)
    }

    /// Narrows one bind value to the SQLite types the protocol carries,
    /// throwing on anything else — the mirror of the backends' `error(...)`
    /// on unsupported values. Booleans store as 0/1.
    private func bindable(_ value: Any?, column: String = "?") throws -> (any DatabaseValueConvertible)? {
        switch value {
        case nil:
            return nil
        case let value as Bool:
            return value ? Int64(1) : Int64(0)
        case let value as Int:
            return Int64(value)
        case let value as Int64:
            return value
        case let value as Double:
            return value
        case let value as String:
            return value
        case let value as Data:
            return value
        default:
            throw SqlDatabaseError.unsupportedValue("\(column): \(type(of: value))")
        }
    }
}

/// Builds a `SqlRow` from a GRDB row, keeping the backend-typed values the
/// Kotlin `Row` receives (Int64/Double/String/Data/nil, in column order).
extension SqlRow {
    init(row: GRDB.Row) {
        self.init(names: Array(row.columnNames),
                  values: row.map { (_, databaseValue) -> Any? in
                      switch databaseValue.storage {
                      case .null: return nil
                      case .int64(let value): return value
                      case .double(let value): return value
                      case .string(let value): return value
                      case .blob(let value): return value
                      }
                  })
    }
}
