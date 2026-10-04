import XCTest
@testable import LearningCore

/// The one-way SwiftData-payload migration, its fail-safe contract and the
/// startup recovery — the Swift mirror of Android's `LearningDbStoreTest`,
/// adapted to the iOS reality that the legacy source is the archive payload
/// (the SwiftData `StoredArchive` record) instead of a learning.json file:
/// the rename-to-.migrated becomes the migrated marker, and the retry-ledger
/// decode disappears (the iOS legacy payload never carried
/// androidFinalAssessments — there is nothing to migrate into
/// assessment_tickets, and recovery is what writes them).
final class LearningDbStoreTests: XCTestCase {
    private var db: SqlDatabase!
    private let now = 812.0

    override func setUpWithError() throws {
        db = try LearningDatabase.openInMemory()
    }

    override func tearDown() {
        db?.close()
        db = nil
    }

    /// The store's source abstraction over an in-memory payload (the gate
    /// seam, like the Kotlin Files test double).
    private final class MockSource: LearningMigrationSource {
        var payload: Data?
        private(set) var migrated = false
        var markFails = false
        init(_ payload: Data?) { self.payload = payload }
        var hasPayload: Bool { payload != nil && !migrated }
        func readPayload() throws -> Data {
            guard let payload else { throw ArchiveError.invalid }
            return payload
        }
        func markMigrated() throws {
            if markFails { throw NSError(domain: "test", code: 1) }
            migrated = true
        }
    }

    // MARK: fixtures

    /// A small but complete archive: one ended session with a fragment, one
    /// lesson, one expression, one completion mark, one skill error.
    private func fixtureArchive() -> Archive {
        var archive = Archive()
        archive.preferences.learningLanguageID = "es"
        var session = SessionRecord(languageID: "es", title: "Café")
        session.id = UUID(uuidString: "11111111-1111-4111-8111-111111111111")!
        session.startedAt = Date(timeIntervalSinceReferenceDate: 100)
        session.endedAt = Date(timeIntervalSinceReferenceDate: 200)
        session.endReason = "Ended by you"
        session.append(Fragment(id: "f1", speaker: .user, text: "hola que tal",
                                startMS: 0, endMS: 1000, receivedAt: Date(timeIntervalSinceReferenceDate: 100)))
        archive.sessions = [session]
        archive.lessons = [LessonState(lessonID: "es-l1", attempts: 2, lastSeen: 5)]
        archive.expressions = [ExpressionState(text: "hola", meaning: "hello", source: "lookup",
                                               lastSeen: 1, dueAt: 2, languageID: "es")]
        archive.completions = [CompletionMark(kind: ProgressEngine.lesson, itemID: "es-l1",
                                              at: 7, bestScore: 90, languageID: "es")]
        archive.errors = [SkillError(skillID: "grammar:test", count: 2, lastAt: 3, lastPrompt: "p")]
        return archive
    }

    /// Two marks differing only by language at the same instant: the archive
    /// accepts both (the markKey keeps the language), the completions primary
    /// key (kind, item_id, at) keeps only the first — the documented
    /// schema-pinned narrowing that count verification must catch.
    private func crossLanguageInstantArchive() -> Archive {
        var archive = fixtureArchive()
        archive.completions = [
            CompletionMark(kind: ProgressEngine.lesson, itemID: "es-l1", at: 7, bestScore: 90, languageID: "es"),
            CompletionMark(kind: ProgressEngine.lesson, itemID: "es-l1", at: 7, bestScore: 90, languageID: nil),
        ]
        return archive
    }

    private func encoded(_ archive: Archive) throws -> Data { try archive.encoded() }

    /// Archive structs are not Equatable on iOS; equality goes through the
    /// archive's own canonical encoding (the established pattern).
    private func assertExportEquals(_ archive: Archive, _ db: SqlDatabase,
                                    file: StaticString = #filePath, line: UInt = #line) throws {
        let exported = try ArchiveMapper.export(db)
        XCTAssertEqual(try archive.encoded(), try exported.encoded(),
                       "export(DB) must equal the expected archive", file: file, line: line)
    }

    private func count(_ table: String, _ db: SqlDatabase? = nil) throws -> Int64 {
        try (db ?? self.db).query("SELECT COUNT(*) AS n FROM \(table)").first!.long("n")
    }

    private func meta(_ key: String, _ db: SqlDatabase? = nil) throws -> String? {
        try MetaDao.get(db ?? self.db, key)
    }

    private func open(_ source: LearningMigrationSource?) throws -> LearningDbStore {
        try LearningDbStore.open(database: db, source: source, clock: { self.now })
    }

    // MARK: migration

    func testMigrationCommitsWithVerifiedCountsHashAndMark() throws {
        let payload = try encoded(fixtureArchive())
        let source = MockSource(payload)
        let store = try open(source)

        XCTAssertFalse(store.jsonMode)
        XCTAssertNil(store.jsonModeNotice)
        XCTAssertEqual(sha256Hex(payload), try meta(LearningDbStore.migrationHashKey))
        XCTAssertEqual(String(now), try meta(LearningDbStore.migratedAtKey))
        XCTAssertTrue(source.migrated, "the commit point closes the gate only after the mark")
        XCTAssertEqual(Int64(1), try count("sessions"))
        XCTAssertEqual(Int64(1), try count("fragments"))
        XCTAssertEqual(Int64(1), try count("lesson_state"))
        XCTAssertEqual(Int64(1), try count("expressions"))
        XCTAssertEqual(Int64(1), try count("completions"))
        XCTAssertEqual(Int64(1), try count("skill_errors"))
        XCTAssertEqual(Int64(0), try count("assessment_tickets"),
                       "the iOS legacy payload carries no retry ledger")
        XCTAssertEqual(Int64(0), try count("usage_outbox"))
        try assertExportEquals(fixtureArchive(), db)
        // the one-shot startup export is the same projection
        XCTAssertEqual(try fixtureArchive().encoded(), try store.loadInitialArchive().encoded())
    }

    func testReopenWithHashPresentIgnoresAReappearingPayload() throws {
        let source = MockSource(try encoded(fixtureArchive()))
        _ = try open(source)
        XCTAssertTrue(source.migrated)

        // the payload record "reappears" (the SwiftData record is never
        // destroyed) with evolved content, as if the marker were gone — the
        // committed hash still closes the gate: no re-migration, no merge
        var evolved = fixtureArchive()
        evolved.sessions = []
        source.payload = try encoded(evolved)
        source.migrated = false
        _ = try open(source)

        try assertExportEquals(fixtureArchive(), db)
        XCTAssertEqual(sha256Hex(try encoded(fixtureArchive())), try meta(LearningDbStore.migrationHashKey))
    }

    func testCorruptPayloadEngagesFailSafeAndLeavesTheSourceUntouched() throws {
        let source = MockSource(Data("this is not an archive".utf8))
        let store = try open(source)

        XCTAssertTrue(store.jsonMode)
        XCTAssertNotNil(store.jsonModeNotice)
        XCTAssertFalse(source.migrated)
        XCTAssertNil(try meta(LearningDbStore.migrationHashKey))
        XCTAssertEqual(Int64(0), try count("sessions"), "the aborted migration's writes rolled back")
        XCTAssertEqual(Int64(0), try count("expressions"))
    }

    func testOversizePayloadEngagesFailSafeAndLeavesTheSourceUntouched() throws {
        let source = MockSource(Data(count: Archive.maximumEncodedBytes + 1))
        let store = try open(source)

        XCTAssertTrue(store.jsonMode)
        XCTAssertNotNil(store.jsonModeNotice)
        XCTAssertFalse(source.migrated)
        XCTAssertNil(try meta(LearningDbStore.migrationHashKey))
    }

    func testCountMismatchFromSameInstantCrossLanguageCompletionsAbortsMigration() throws {
        let source = MockSource(try encoded(crossLanguageInstantArchive()))
        let store = try open(source)

        XCTAssertTrue(store.jsonMode, "the schema-pinned completions narrowing must abort, not lose a mark")
        XCTAssertFalse(source.migrated)
        XCTAssertNil(try meta(LearningDbStore.migrationHashKey))
        // the import's own transaction committed, but the bookkeeping (with
        // the hash) rolled back: the residue is unreachable without the hash
        XCTAssertEqual(Int64(1), try count("sessions"))
        XCTAssertEqual(Int64(1), try count("completions"),
                       "the primary key kept only the first same-instant mark")
    }

    func testRetryAfterAnAbortedMigrationClearsResidueAndMigratesAnEvolvedPayload() throws {
        let source = MockSource(try encoded(crossLanguageInstantArchive()))
        let failed = try open(source)
        XCTAssertTrue(failed.jsonMode)

        // the retry faces the residue the aborted attempt left in the
        // database plus an EVOLVED payload (the user kept using the fallback
        // path: one session gone, the ledger pruned)
        var evolved = fixtureArchive()
        evolved.sessions = []
        evolved.completions = [CompletionMark(kind: ProgressEngine.lesson, itemID: "es-l1",
                                              at: 9, bestScore: 0, languageID: "es")]
        source.payload = try encoded(evolved)
        source.migrated = false

        let store = try open(source)
        XCTAssertFalse(store.jsonMode, "the gated clear makes the retry count-stable")
        XCTAssertTrue(source.migrated)
        XCTAssertEqual(sha256Hex(try encoded(evolved)), try meta(LearningDbStore.migrationHashKey))
        XCTAssertEqual(Int64(0), try count("sessions"), "the aborted attempt's residue was cleared")
        XCTAssertEqual(Int64(1), try count("completions"))
        try assertExportEquals(evolved, db)
    }

    func testMarkFailureAfterCommitKeepsTheMigrationComplete() throws {
        let payload = try encoded(fixtureArchive())
        let source = MockSource(payload)
        source.markFails = true
        let store = try open(source)

        // Post-commit cleanup only: the verified commit must not be
        // discarded because the mark (the rename's counterpart) failed.
        XCTAssertFalse(store.jsonMode)
        XCTAssertEqual(sha256Hex(payload), try meta(LearningDbStore.migrationHashKey))
        try assertExportEquals(fixtureArchive(), db)
    }

    // MARK: recovery (the legacy load's mirror)

    /// An unfinished session whose newest user passage is long enough and
    /// unassessed; one whose passage was already assessed; one already ended.
    private func recoveryArchive() -> Archive {
        var archive = Archive()
        archive.preferences.learningLanguageID = "es"

        var openSession = SessionRecord(languageID: "es", title: "Live")
        openSession.id = UUID(uuidString: "22222222-2222-4222-8222-222222222222")!
        openSession.startedAt = Date(timeIntervalSinceReferenceDate: 10)
        openSession.append(Fragment(id: "o-f1", speaker: .user, text: "un café con leche",
                                    startMS: 0, endMS: 100, receivedAt: Date(timeIntervalSinceReferenceDate: 10)))

        var assessed = SessionRecord(languageID: "es", title: "Assessed")
        assessed.id = UUID(uuidString: "33333333-3333-4333-8333-333333333333")!
        assessed.startedAt = Date(timeIntervalSinceReferenceDate: 20)
        assessed.append(Fragment(id: "a-f1", speaker: .user, text: "hasta luego",
                                 startMS: 0, endMS: 100, receivedAt: Date(timeIntervalSinceReferenceDate: 20)))
        let passage = assessed.passages[0]
        assessed.assessments = [Assessment(passageID: passage.id, revisionKey: passage.revisionKey,
                                           outcome: .success, suggestedLevel: 1, nextGoal: "", capability: "",
                                           words: [], createdAt: Date(timeIntervalSinceReferenceDate: 21))]

        var ended = SessionRecord(languageID: "es", title: "Ended")
        ended.id = UUID(uuidString: "44444444-4444-4444-8444-444444444444")!
        ended.startedAt = Date(timeIntervalSinceReferenceDate: 30)
        ended.endedAt = Date(timeIntervalSinceReferenceDate: 40)
        ended.endReason = "Ended by you"

        archive.sessions = [openSession, assessed, ended]
        return archive
    }

    func testRecoveryMirrorsTheLegacyLoadAndEnqueuesTheTicket() throws {
        let source = MockSource(nil) // straight into database mode
        let store = try open(source)
        try ArchiveMapper.import(recoveryArchive(), db)

        let recovered = try store.recoverUnfinishedSessions(try store.loadInitialArchive(), now: now)

        let unfinished = recovered.sessions.filter { $0.id != UUID(uuidString: "44444444-4444-4444-8444-444444444444")! }
        XCTAssertEqual(2, unfinished.count)
        for session in unfinished {
            XCTAssertEqual(Date(timeIntervalSinceReferenceDate: now), session.endedAt)
            XCTAssertEqual(LearningDbStore.recoveryEndReason, session.endReason)
        }
        // only the unassessed-passage session gets a ticket (attempts = 0, the
        // freshly-recovered form); the assessed one gets the removal branch
        let tickets = try AssessmentTicketsDao.all(db)
        XCTAssertEqual([AssessmentTicketRow("22222222-2222-4222-8222-222222222222", attempts: 0, lastAttemptAt: nil)],
                       tickets)
        // the returned archive is the working copy the database now holds
        try assertExportEquals(recovered, db)
    }

    func testRecoveryIsIdempotentOnTheNextLaunch() throws {
        let source = MockSource(nil)
        let store = try open(source)
        try ArchiveMapper.import(recoveryArchive(), db)

        let first = try store.recoverUnfinishedSessions(try store.loadInitialArchive(), now: now)
        let second = try store.recoverUnfinishedSessions(first, now: now + 60)

        XCTAssertEqual(try first.encoded(), try second.encoded())
        XCTAssertEqual(Date(timeIntervalSinceReferenceDate: now),
                       second.sessions.first { $0.title == "Live" }!.endedAt,
                       "the first recovery's stamp survives")
        XCTAssertEqual(1, try AssessmentTicketsDao.all(db).count)
    }

    func testRecoveryWithoutRecoverablePassageEndsTheSessionAndClearsItsTicket() throws {
        var archive = Archive()
        archive.preferences.learningLanguageID = "es"
        var short = SessionRecord(languageID: "es", title: "Short")
        short.id = UUID(uuidString: "55555555-5555-4555-8555-555555555555")!
        short.startedAt = Date(timeIntervalSinceReferenceDate: 10)
        short.append(Fragment(id: "s-f1", speaker: .user, text: "sí",
                              startMS: 0, endMS: 100, receivedAt: Date(timeIntervalSinceReferenceDate: 10)))
        archive.sessions = [short]
        let source = MockSource(nil)
        let store = try open(source)
        try ArchiveMapper.import(archive, db)
        // a ticket row left over from an earlier recovery: the session's
        // next save/recovery with no recoverable passage must remove it
        try AssessmentTicketsDao.upsert(db, AssessmentTicketRow(short.id.uuidString, attempts: 1, lastAttemptAt: 5))

        let recovered = try store.recoverUnfinishedSessions(try store.loadInitialArchive(), now: now)

        let session = recovered.sessions[0]
        XCTAssertEqual(Date(timeIntervalSinceReferenceDate: now), session.endedAt)
        XCTAssertEqual(LearningDbStore.recoveryEndReason, session.endReason)
        XCTAssertEqual(0, try AssessmentTicketsDao.all(db).count,
                       "the removal branch deletes the row instead")
        try assertExportEquals(recovered, db)
    }

    // MARK: fail-safe split and crash-window adoption

    func testFreshDatabaseWithoutPayloadOpensStraightIntoDatabaseMode() throws {
        let store = try open(nil)
        XCTAssertFalse(store.jsonMode)
        // the export projection of an empty database: default preferences,
        // every content list empty, the canonical nil ledger/memory
        let exported = try store.loadInitialArchive()
        XCTAssertTrue(exported.sessions.isEmpty)
        XCTAssertTrue((exported.lessons ?? []).isEmpty)
        XCTAssertTrue((exported.expressions ?? []).isEmpty)
        XCTAssertNil(exported.completions)
        XCTAssertNil(exported.errors)
        XCTAssertEqual(LanguageRegistry.defaultID, exported.preferences.learningLanguageID)
    }

    func testABrokenDatabaseWithoutPayloadRethrowsInsteadOfJsonMode() throws {
        let badPath = NSTemporaryDirectory() + "missing-\(UUID().uuidString)/learning.db"
        XCTAssertThrowsError(try LearningDbStore.open(at: badPath, source: MockSource(nil)))
    }

    func testABrokenDatabaseWithAPayloadEngagesFailSafe() throws {
        let badPath = NSTemporaryDirectory() + "missing-\(UUID().uuidString)/learning.db"
        let store = try LearningDbStore.open(at: badPath, source: MockSource(Data("x".utf8)))
        XCTAssertTrue(store.jsonMode)
        XCTAssertNotNil(store.jsonModeNotice)
    }

    func testAdoptPreimportAdoptsTheStrandedKeepCopy() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("adopt-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let live = directory.appendingPathComponent("learning.db")
        let keep = URL(fileURLWithPath: live.path + ".preimport")
        try Data("stranded".utf8).write(to: keep)

        XCTAssertTrue(LearningDbStore.adoptPreimportDatabase(at: live), "the exact crash signature: live missing, keep present")
        XCTAssertTrue(FileManager.default.fileExists(atPath: live.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: keep.path))
        XCTAssertEqual("stranded", try String(data: Data(contentsOf: live), encoding: .utf8))

        // after a completed restore both exist and the installed bytes win
        try Data("installed".utf8).write(to: live)
        try Data("old".utf8).write(to: keep)
        XCTAssertFalse(LearningDbStore.adoptPreimportDatabase(at: live))
        XCTAssertEqual("installed", try String(data: Data(contentsOf: live), encoding: .utf8))

        // nothing stranded: no-op
        XCTAssertFalse(LearningDbStore.adoptPreimportDatabase(at: directory.appendingPathComponent("other.db")))
    }

    // MARK: hash helper

    private func sha256Hex(_ data: Data) -> String {
        LearningDbStore.sha256Hex(data)
    }
}
