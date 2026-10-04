import XCTest
@testable import LearningCore

/// The learning schema must open verbatim through the shared statement list,
/// and the GRDB backend must honor the `SqlDatabase` contract — the Swift
/// mirror of Android's `LearningDbTest` (opener) and `JvmSqlDatabase`
/// behavior (conflict strategies, counts, transactions, null-arg rejection).
final class LearningDatabaseTests: XCTestCase {
    private var db: SqlDatabase!

    override func setUpWithError() throws {
        db = try LearningDatabase.openInMemory()
    }

    override func tearDown() {
        db?.close()
        db = nil
    }

    // MARK: opener

    func testSchemaOpensAtVersionOneWithExactlyThePinnedTables() throws {
        XCTAssertEqual(Int64(1), try db.query("PRAGMA user_version").first!.long("user_version"))
        let tables = try db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .map { $0.text("name") }
        XCTAssertEqual(["assessment_tickets", "completions", "expressions", "fragments",
                        "lesson_state", "meta", "preferences", "sessions", "skill_errors", "usage_outbox"],
                       tables)
        XCTAssertFalse(tables.contains("words"),
                       "words must never be stored; hidden words live in preferences")
    }

    func testForeignKeysAreEnforcedOnTheConnection() throws {
        XCTAssertEqual(Int64(1), try db.query("PRAGMA foreign_keys").first!.long("foreign_keys"))
    }

    func testForeignKeysRejectOrphanFragments() throws {
        XCTAssertThrowsError(try db.insert("fragments", [
            "id": "f1", "session_id": "missing", "sort_index": 0, "speaker": "user",
            "text": "Hola", "revision": 0, "start_ms": 0, "end_ms": 100, "received_at": 1.0,
        ]))
    }

    func testAFileDatabasePersistsAndNeverDowngrades() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("learningdb-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent(LearningDatabase.name).path

        let fileDb = try LearningDatabase.open(at: path)
        XCTAssertEqual(Int64(1), try fileDb.query("PRAGMA user_version").first!.long("user_version"))
        _ = try fileDb.insert("meta", ["key": "k", "value": "persisted"])
        try fileDb.exec("PRAGMA user_version = 2")
        // using a closed database throws (the backup/restore close-before-replace contract)
        fileDb.close()
        XCTAssertThrowsError(try fileDb.query("SELECT COUNT(*) AS n FROM meta"))
        // the bumped user_version persisted, so the reopen must refuse it
        XCTAssertThrowsError(try LearningDatabase.open(at: path)) { error in
            XCTAssertEqual(LearningDatabaseError.neverDowngrades(found: 2, want: 1),
                           error as? LearningDatabaseError)
        }
    }

    // MARK: SqlDatabase contract

    func testInsertResolvesConflictsWithTheRequestedStrategy() throws {
        _ = try db.insert("meta", ["key": "k", "value": "first"])
        XCTAssertEqual(Int64(-1), try db.insert("meta", ["key": "k", "value": "second"], strategy: .ignore))
        XCTAssertEqual("first", try db.query("SELECT value FROM meta WHERE key = 'k'").first!.text("value"))
        XCTAssertThrowsError(try db.insert("meta", ["key": "k", "value": "third"], strategy: .abort)) { error in
            // the database rejected it, not the contract
            XCTAssertFalse(error is SqlDatabaseError, "\(error)")
        }
    }

    func testInsertReturnsTheNewRowIdAndRejectsUnsupportedValues() throws {
        let rowId = try db.insert("meta", ["key": "k", "value": "first"])
        XCTAssertEqual(Int64(1), rowId)
        XCTAssertEqual("00000000-0000-0000-0000-000000000001",
                       QueuedUsageEvent.eventID(forRowId: rowId).uuidString.lowercased())
        XCTAssertThrowsError(try db.insert("meta", ["key": "k2", "value": [1, 2]])) { error in
            XCTAssertTrue(error is SqlDatabaseError, "\(error)")
            XCTAssertTrue(String(describing: error).contains("Unsupported SQLite value"), "\(error)")
        }
        // booleans store as 0/1, the schema's one boolean convention
        _ = try db.insert("completions", ["kind": "lesson", "item_id": "i", "at": 1.0, "best_score": true])
        XCTAssertEqual(Int64(1), try db.query("SELECT best_score FROM completions").first!.long("best_score"))
    }

    func testUpdateReportsAffectedRows() throws {
        _ = try db.insert("meta", ["key": "a", "value": "1"])
        _ = try db.insert("meta", ["key": "b", "value": "1"])
        _ = try db.insert("meta", ["key": "c", "value": "2"])
        XCTAssertEqual(Int64(2), try db.update("UPDATE meta SET value = '9' WHERE value = ?", args: ["1"]))
        XCTAssertEqual(Int64(2), try db.query("SELECT COUNT(*) AS n FROM meta WHERE value = '9'").first!.long("n"))
        XCTAssertEqual(Int64(0), try db.update("UPDATE meta SET value = '9' WHERE key = 'zz'"))
    }

    func testTransactionCommitsTheWorkDoneInsideTheBlock() throws {
        _ = try db.transaction { tx in try tx.insert("meta", ["key": "k", "value": "kept"]) }
        XCTAssertEqual("kept", try db.query("SELECT value FROM meta WHERE key = 'k'").first!.text("value"))
    }

    func testTransactionRollsBackOnFailureAndRejectsNesting() throws {
        struct Boom: Error {}
        XCTAssertThrowsError(try db.transaction { tx in
            _ = try tx.insert("meta", ["key": "k", "value": "lost"])
            throw Boom()
        })
        XCTAssertEqual(Int64(0), try count("meta"))
        XCTAssertThrowsError(try db.transaction { tx in
            try tx.transaction { _ in }
        }) { error in
            XCTAssertEqual(SqlDatabaseError.nestedTransaction, error as? SqlDatabaseError)
        }
        XCTAssertEqual(Int64(0), try count("meta"))
    }

    func testNullBindArgumentsAreRejectedOnQueryAndUpdate() throws {
        _ = try db.insert("meta", ["key": "k", "value": "v"])
        XCTAssertThrowsError(try db.query("SELECT value FROM meta WHERE key != ?", args: [nil])) { error in
            XCTAssertEqual(SqlDatabaseError.nullBindArgument(position: 1), error as? SqlDatabaseError)
        }
        XCTAssertThrowsError(try db.update("UPDATE meta SET value = 'x' WHERE key != ?", args: [nil])) { error in
            XCTAssertEqual(SqlDatabaseError.nullBindArgument(position: 1), error as? SqlDatabaseError)
        }
        // inserts still carry null values: that is column data, not a comparison
        _ = try db.insert("completions", ["kind": "lesson", "item_id": "i1", "at": 1.0, "language_id": nil])
        XCTAssertEqual(Int64(1), try count("completions"))
    }

    private func count(_ table: String) throws -> Int64 {
        return try db.query("SELECT COUNT(*) AS n FROM \(table)").first!.long("n")
    }
}
