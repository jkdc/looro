import XCTest
@testable import LearningCore

/// Archive-to-rows roundtrip over the shared migration fixtures on real
/// SQLite (GRDB in-memory): decode the fixture archive with the iOS
/// `Archive.decode`, import it into the learning database, export it back and
/// require equality — the Swift mirror of Android's `ArchiveMapperTest`. The
/// row counts also assert against the pinned case-basic.expected.json, so
/// this test and scripts/check_db_schema.py pin the same migration outcome.
///
/// Archives and sessions are not Equatable, so equality is asserted through
/// the archive's own canonical encoding (pretty-printed, sorted keys): two
/// encodings are byte-equal exactly when the structs are equal.
final class ArchiveMapperTests: XCTestCase {
    private var db: SqlDatabase!

    private let fixtures = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .appendingPathComponent("../../../shared/fixtures/db/migration")

    override func setUpWithError() throws {
        db = try LearningDatabase.openInMemory()
    }

    override func tearDown() {
        db?.close()
        db = nil
    }

    func testCaseBasicRoundTripsThroughRows() throws {
        let decoded = try Archive.decode(fixture("case-basic.json"))
        // lesson bars/dueAt are deliberately not stored in schema v1 (shared/db/schema.sql,
        // lesson_state); normalize the decoded archive to the persisted projection
        var archived = decoded
        archived.lessons = (decoded.lessons ?? []).map { lesson in
            LessonState(lessonID: lesson.lessonID, bars: 0, attempts: lesson.attempts,
                        lastSeen: lesson.lastSeen, dueAt: 0.0)
        }
        XCTAssertTrue((decoded.lessons ?? []).contains { $0.bars != 0 || $0.dueAt != 0 },
                      "the normalization must be doing real work on this fixture")
        try ArchiveMapper.import(archived, db, masteryCache: try fixtureMasteryCache())
        let exported = try ArchiveMapper.export(db)
        XCTAssertEqual(try archiveJSON(archived), try archiveJSON(exported))
        // export order is explicitly the archive's own list order (first-insertion)
        XCTAssertEqual(archived.sessions.map(\.id), exported.sessions.map(\.id))
        XCTAssertEqual(archived.expressions?.map(\.text), exported.expressions?.map(\.text))
        XCTAssertEqual(archived.completions?.map(\.kind), exported.completions?.map(\.kind))
    }

    func testReimportingTwiceIsStableAndStaysAValidArchive() throws {
        let archived = try archivedBasicFixture()
        try ArchiveMapper.import(archived, db, masteryCache: try fixtureMasteryCache())
        let first = try ArchiveMapper.export(db)
        XCTAssertEqual(try archiveJSON(archived), try archiveJSON(first))
        try ArchiveMapper.import(archived, db, masteryCache: try fixtureMasteryCache())
        let second = try ArchiveMapper.export(db)
        XCTAssertEqual(try archiveJSON(first), try archiveJSON(second),
                       "content and order must survive a second import")
        // the legacy row was updated in place, not duplicated (NULLs are distinct in the PK)
        XCTAssertEqual(4, second.expressions?.count)
        let keys = (second.expressions ?? []).map { RecallEngine.expressionKey($0.text, $0.languageID) }
        XCTAssertEqual(keys.count, Set(keys).count)
        // Archive.decode runs validation, so this rejects duplicate keys too
        let redecoded = try Archive.decode(try XCTUnwrap(second.encoded()))
        XCTAssertEqual(try archiveJSON(second), try archiveJSON(redecoded))
    }

    func testImportIsAtomicWhenASessionFailsMidway() throws {
        var archived = try Archive.decode(fixture("case-basic.json"))
        // fragment ids are unique per session to the archive validator, so this archive
        // is legal, but its second session collides on the fragments primary key
        archived.sessions[1].fragments[0] = archived.sessions[0].fragments[0]
        XCTAssertThrowsError(try ArchiveMapper.import(archived, db))
        for table in ["preferences", "sessions", "fragments", "expressions",
                      "lesson_state", "completions", "skill_errors", "meta"] {
            XCTAssertEqual(Int64(0), try count(table), "table \(table) must be untouched after the rollback")
        }
    }

    func testCaseEmptyRoundTripsAndStaysEmpty() throws {
        let decoded = try Archive.decode(fixture("case-empty.json"))
        try ArchiveMapper.import(decoded, db)
        let exported = try ArchiveMapper.export(db)
        XCTAssertEqual(try archiveJSON(decoded), try archiveJSON(exported))
        XCTAssertNil(exported.completions)
        XCTAssertNil(exported.errors)
        XCTAssertEqual(0, exported.sessions.count)
        XCTAssertEqual(0, exported.lessons?.count)
        XCTAssertEqual("nb", exported.preferences.learningLanguageID)
        XCTAssertNil(exported.preferences.speechRate)
    }

    func testImportMatchesThePinnedExpectedRowCounts() throws {
        let expected = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("case-basic.expected.json"))
            as? [String: Any])
        let tables = try XCTUnwrap(expected["tables"] as? [String: Int])
        XCTAssertEqual(10, tables.count)
        try ArchiveMapper.import(try Archive.decode(fixture("case-basic.json")), db,
                                 masteryCache: try fixtureMasteryCache())
        for (table, rowCount) in tables {
            XCTAssertEqual(Int64(rowCount), try count(table), "row count of \(table)")
        }
    }

    func testLegacyNullLanguageExpressionsCoexistWithLanguageRows() throws {
        try ArchiveMapper.import(try Archive.decode(fixture("case-basic.json")), db)
        let legacy = try db.query("SELECT text FROM expressions WHERE language_id IS NULL")
            .map { $0.text("text") }
        XCTAssertEqual(["Buenos días"], legacy)
        // two non-legacy rows share one norm_key across two distinct languages
        let languages = try db.query(
            "SELECT language_id FROM expressions WHERE norm_key = 'important' ORDER BY language_id")
            .compactMap { $0.textOrNull("language_id") }
        XCTAssertEqual(["es", "fr"], languages)
    }

    func testDeletingASessionCascadesToItsFragments() throws {
        try ArchiveMapper.import(try Archive.decode(fixture("case-basic.json")), db)
        XCTAssertEqual(Int64(9), try count("fragments"))
        let first = try SessionsDao.all(db).first!
        let itsFragments = try FragmentsDao.bySession(db, first.id.uuidString).count
        XCTAssertTrue(itsFragments > 0)
        try SessionsDao.delete(db, first.id.uuidString)
        XCTAssertEqual(Int64(9 - itsFragments), try count("fragments"))
        XCTAssertTrue(try FragmentsDao.bySession(db, first.id.uuidString).isEmpty)
    }

    func testImportLeavesThePlatformLocalQueuesAlone() throws {
        _ = try UsageOutboxDao.enqueue(db, "app_open", 1_727_859_600_000)
        try AssessmentTicketsDao.upsert(db, AssessmentTicketRow("queued-session", attempts: 0,
                                                                lastAttemptAt: nil))
        try ArchiveMapper.import(try Archive.decode(fixture("case-basic.json")), db)
        XCTAssertEqual(1, try UsageOutboxDao.pending(db).count)
        XCTAssertEqual("queued-session", try AssessmentTicketsDao.all(db).first?.sessionID)
    }

    func testImportWritesSchemaFlagsAndOneMasteryMetaRowPerSkill() throws {
        // a stale mastery row from an earlier state must not survive the import
        try MetaDao.put(db, "mastery:stale-skill", "9")
        try ArchiveMapper.import(try Archive.decode(fixture("case-basic.json")), db,
                                 masteryCache: try fixtureMasteryCache())
        let keys = try db.query("SELECT key FROM meta ORDER BY key").map { $0.text("key") }
        XCTAssertEqual(["mastery:function:describe-people", "mastery:grammar:articles", "schema_flags"], keys)
        // the mastery rows read back as the cache and schema_flags does not leak
        XCTAssertEqual(["grammar:articles": 2, "function:describe-people": 1], try MetaDao.mastery(db))
    }

    func testAnEmptyDatabaseExportsAFreshArchive() throws {
        var fresh = Archive()
        fresh.lessons = []
        fresh.expressions = []
        XCTAssertEqual(try archiveJSON(fresh), try archiveJSON(try ArchiveMapper.export(db)))
    }

    func testExportReadsBackInFirstInsertionOrderAfterUpdates() throws {
        let archived = try archivedBasicFixture()
        try ArchiveMapper.import(archived, db, masteryCache: try fixtureMasteryCache())
        let idsBefore = archived.sessions.map(\.id)
        // a session-end style in-place update must not move any row (no REPLACE,
        // so the export's first-insertion order never drifts)
        var first = try SessionsDao.all(db).first!
        first.usageFinal = true
        try SessionsDao.upsert(db, first)
        XCTAssertEqual(idsBefore, try SessionsDao.all(db).map(\.id))
        XCTAssertEqual(3, try SessionsDao.all(db).count)
        // the export is deterministic across calls
        XCTAssertEqual(try archiveJSON(try ArchiveMapper.export(db)),
                       try archiveJSON(try ArchiveMapper.export(db)))
    }

    // MARK: helpers

    /// The decoded case-basic fixture, normalized to the schema-v1 projection
    /// (lesson bars/dueAt are not stored).
    private func archivedBasicFixture() throws -> Archive {
        let decoded = try Archive.decode(fixture("case-basic.json"))
        var archived = decoded
        archived.lessons = (decoded.lessons ?? []).map { lesson in
            LessonState(lessonID: lesson.lessonID, bars: 0, attempts: lesson.attempts,
                        lastSeen: lesson.lastSeen, dueAt: 0.0)
        }
        return archived
    }

    /// The fixture's masteryCache — the iOS Archive model does not carry the
    /// field yet, so it is read from the JSON and passed to the import.
    private func fixtureMasteryCache() throws -> [String: Int] {
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("case-basic.json")) as? [String: Any])
        return try XCTUnwrap(root["masteryCache"] as? [String: Int])
    }

    private func fixture(_ name: String) throws -> Data {
        try Data(contentsOf: fixtures.appendingPathComponent(name))
    }

    private func archiveJSON(_ archive: Archive) throws -> String {
        try XCTUnwrap(String(data: XCTUnwrap(archive.encoded()), encoding: .utf8))
    }

    private func count(_ table: String) throws -> Int64 {
        return try db.query("SELECT COUNT(*) AS n FROM \(table)").first!.long("n")
    }
}
