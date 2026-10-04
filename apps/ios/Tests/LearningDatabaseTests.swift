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

    func testSchemaOpensAtVersionTwoWithExactlyThePinnedTables() throws {
        XCTAssertEqual(Int64(2), try db.query("PRAGMA user_version").first!.long("user_version"))
        let tables = try db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .map { $0.text("name") }
        // fragments_fts is the pinned v2 virtual table; fragments_fts_* are its
        // FTS5 shadow tables (SQLite internals, like sqlite_sequence — an
        // external-content table keeps no _content of its own).
        XCTAssertEqual(["assessment_tickets", "completions", "expressions", "fragments",
                        "fragments_fts", "fragments_fts_config", "fragments_fts_data",
                        "fragments_fts_docsize", "fragments_fts_idx",
                        "lesson_state", "meta", "preferences", "sessions", "skill_errors",
                        "usage_outbox"],
                       tables)
        XCTAssertFalse(tables.contains("words"),
                       "words must never be stored; hidden words live in preferences")
        let virtual = try db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'fragments_fts'").first!
        XCTAssertTrue(virtual.text("sql").contains("USING fts5"))
        XCTAssertTrue(virtual.text("sql").contains("content='fragments'"))
        XCTAssertEqual(["fragments_fts_delete", "fragments_fts_insert", "fragments_fts_update"],
                       try db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
                           .map { $0.text("name") })
    }

    func testFreshIndexStaysInSyncThroughTheTriggers() throws {
        _ = try db.insert("sessions", ["id": "s1", "language_id": "es", "started_at": 1.0, "title": "T"])
        _ = try db.insert("fragments", ["id": "f1", "session_id": "s1", "sort_index": 0,
                                        "speaker": "user", "text": "Humo de prueba", "revision": 0,
                                        "start_ms": 0, "end_ms": 1, "received_at": 1.0])
        XCTAssertEqual(Int64(1), try ftsCount("humo"))
        // an edit reindexes both directions (delete + insert trigger)
        _ = try db.update("UPDATE fragments SET text = 'Cambio limpio' WHERE id = 'f1'")
        XCTAssertEqual(Int64(0), try ftsCount("humo"))
        XCTAssertEqual(Int64(1), try ftsCount("limpio"))
        // the cascade delete takes the index entry with the row
        try db.exec("DELETE FROM sessions")
        XCTAssertEqual(Int64(0), try ftsCount("limpio"))
    }

    private func ftsCount(_ term: String) throws -> Int64 {
        return try db.query("SELECT COUNT(*) AS n FROM fragments_fts WHERE fragments_fts MATCH ?",
                            args: [term]).first!.long("n")
    }

    func testUpgradeFromV1KeepsTranscriptsSearchableAndConvergesOnTheFreshInventory() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("learningdb-upgrade-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent(LearningDatabase.name).path
        // the session id is a UUID string on this platform (the DAO re-parses it)
        let sessionID = "11111111-1111-1111-1111-111111111111"

        // a real shipped-v1 database, with a transcript and no FTS to search it
        let v1 = try LearningDatabase.openV1(at: path)
        XCTAssertEqual(Int64(1), try v1.query("PRAGMA user_version").first!.long("user_version"))
        _ = try v1.insert("sessions", ["id": sessionID, "language_id": "es", "started_at": 1.0,
                                       "title": "Historia"])
        _ = try v1.insert("fragments", ["id": "f1", "session_id": sessionID, "sort_index": 0,
                                        "speaker": "user", "text": "palabra heredada", "revision": 0,
                                        "start_ms": 0, "end_ms": 1, "received_at": 1.0])
        XCTAssertThrowsError(try v1.query("SELECT rowid FROM fragments_fts WHERE fragments_fts MATCH 'heredada'"))
        v1.close()

        // reopening with the current opener migrates in place: same version,
        // same objects in sqlite_master, and the OLD text is searchable
        let migrated = try LearningDatabase.open(at: path)
        XCTAssertEqual(Int64(LearningDatabase.version),
                       try migrated.query("PRAGMA user_version").first!.long("user_version"))
        let hits = try SearchDao.search(migrated, "heredada", languageID: "es")
        XCTAssertEqual([sessionID], hits.map { $0.session.id.uuidString })
        // a NEW write lands in the index too (the insert trigger)
        _ = try migrated.insert("fragments", ["id": "f2", "session_id": sessionID, "sort_index": 1,
                                              "speaker": "assistant", "text": "respuesta nueva",
                                              "revision": 0, "start_ms": 1, "end_ms": 2, "received_at": 2.0])
        XCTAssertEqual([sessionID], try SearchDao.search(migrated, "respuesta", languageID: "es")
            .map { $0.session.id.uuidString })

        let fresh = try LearningDatabase.openInMemory()
        func inventory(_ target: SqlDatabase) throws -> [String] {
            return try target.query(
                "SELECT type, name, COALESCE(sql, '') AS sql FROM sqlite_master "
                    + "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
                .map { "\($0.text("type"))|\($0.text("name"))|\($0.text("sql"))" }
        }
        XCTAssertEqual(try inventory(fresh), try inventory(migrated))
        migrated.close()
        fresh.close()
    }

    func testUpgradeRejectsVersionsItCannotReach() throws {
        XCTAssertThrowsError(try LearningDatabase.upgrade(db, from: 0)) { error in
            XCTAssertEqual(LearningDatabaseError.noUpgradePath(found: 0, want: 2),
                           error as? LearningDatabaseError)
        }
        XCTAssertThrowsError(try LearningDatabase.upgrade(db, from: LearningDatabase.version)) { error in
            XCTAssertEqual(LearningDatabaseError.noUpgradePath(found: 2, want: 2),
                           error as? LearningDatabaseError)
        }
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
        XCTAssertEqual(Int64(LearningDatabase.version),
                       try fileDb.query("PRAGMA user_version").first!.long("user_version"))
        _ = try fileDb.insert("meta", ["key": "k", "value": "persisted"])
        try fileDb.exec("PRAGMA user_version = \(LearningDatabase.version + 1)")
        // using a closed database throws (the backup/restore close-before-replace contract)
        fileDb.close()
        XCTAssertThrowsError(try fileDb.query("SELECT COUNT(*) AS n FROM meta"))
        // the bumped user_version persisted, so the reopen must refuse it
        XCTAssertThrowsError(try LearningDatabase.open(at: path)) { error in
            XCTAssertEqual(LearningDatabaseError
                               .neverDowngrades(found: LearningDatabase.version + 1,
                                                want: LearningDatabase.version),
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

    func testInsertCarriesNullColumnsAlongsideValues() throws {
        // the row's [String: Any?] values are optionals inside the dictionary:
        // a present-key nil must bind as SQL NULL while its neighbors bind as
        // data (a boxed Optional here would make every insert throw)
        _ = try db.insert("completions", [
            "kind": "lesson", "item_id": "es-foundations-u1-ser", "at": 100.0,
            "best_score": 90, "language_id": nil,
        ])
        let row = try db.query(
            "SELECT kind, item_id, at, best_score, language_id FROM completions").first!
        XCTAssertEqual("lesson", row.text("kind"))
        XCTAssertEqual("es-foundations-u1-ser", row.text("item_id"))
        XCTAssertEqual(100.0, row.real("at"))
        XCTAssertEqual(90, row.int("best_score"))
        XCTAssertNil(row["language_id"])
        XCTAssertNil(row.textOrNull("language_id"))
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

    func testTransactionReturnsTheBlockResultEvenWhenItIsNil() throws {
        // T may itself be Optional; a nil result must come back as nil
        let value: String? = try db.transaction { _ -> String? in nil }
        XCTAssertNil(value)
        let kept: String? = try db.transaction { _ -> String? in "kept" }
        XCTAssertEqual("kept", kept)
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
