import XCTest
@testable import LearningCore

final class ExamHistoryDaoTests: XCTestCase {
    private var db: SqlDatabase!

    override func setUpWithError() throws { db = try LearningDatabase.openInMemory() }
    override func tearDown() { db?.close(); db = nil }

    func testStartAndAutosavePersistFrozenContentAnswerPositionAndOutbox() throws {
        let history = try ExamHistoryDao.start(db, snapshot: snapshot("exam-1", "content-1"),
            attempt: ExamAttemptRecord(attemptID: "attempt-1", examID: "exam-1", startedAt: 10))
        XCTAssertEqual("{\"question\":\"frozen\"}", history.snapshot.text("content_json"))
        XCTAssertEqual("in_progress", history.attempt.text("state"))
        XCTAssertEqual("created", try db.query("SELECT event_type FROM exam_sync_outbox").first!.text("event_type"))
        XCTAssertEqual(2, try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-1", questionID: "q1",
            responseJSON: "{\"answer\":\"a\"}", position: 1, savedAt: 11))
        let resumed = try ExamHistoryDao.load(db, attemptID: "attempt-1")!
        XCTAssertEqual(Int64(1), resumed.attempt.long("position"))
        XCTAssertEqual("{\"answer\":\"a\"}", resumed.answers.first!.text("response_json"))
        XCTAssertEqual(Int64(2), try db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox").first!.long("n"))
    }

    func testCompletionIsIdempotentAndTheOriginalResultCannotBeReplaced() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-2", "content-2"),
            attempt: ExamAttemptRecord(attemptID: "attempt-2", examID: "exam-2", startedAt: 20))
        let result = "{\"score\":80,\"verdicts\":[true]}"
        XCTAssertTrue(try ExamHistoryDao.complete(db, attemptID: "attempt-2", resultJSON: result, completedAt: 30))
        XCTAssertFalse(try ExamHistoryDao.complete(db, attemptID: "attempt-2", resultJSON: result, completedAt: 31))
        XCTAssertThrowsError(try ExamHistoryDao.complete(db, attemptID: "attempt-2", resultJSON: "{\"score\":90}",
                                                          completedAt: 32)) { error in
            XCTAssertEqual(ExamHistoryError.immutableResultConflict, error as? ExamHistoryError)
        }
        let history = try ExamHistoryDao.load(db, attemptID: "attempt-2")!
        XCTAssertEqual("completed", history.attempt.text("state"))
        XCTAssertEqual(result, history.result!.text("result_json"))
        XCTAssertEqual(Int64(2), try db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox").first!.long("n"))
    }

    func testFailedOutboxInsertRollsBackSnapshotAndAttemptCreation() throws {
        try db.exec("CREATE TRIGGER reject_exam_outbox BEFORE INSERT ON exam_sync_outbox BEGIN " +
                    "SELECT RAISE(ABORT, 'test failure'); END")
        XCTAssertThrowsError(try ExamHistoryDao.start(db, snapshot: snapshot("exam-3", "content-3"),
            attempt: ExamAttemptRecord(attemptID: "attempt-3", examID: "exam-3", startedAt: 40)))
        for table in ["exam_snapshots", "exam_snapshot_ids", "exam_attempts", "exam_sync_outbox"] {
            XCTAssertEqual(Int64(0), try db.query("SELECT COUNT(*) AS n FROM \(table)").first!.long("n"))
        }
    }

    func testFailedProgressOutboxRollsBackAnswerAndPosition() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-progress", "content-progress"),
            attempt: ExamAttemptRecord(attemptID: "attempt-progress", examID: "exam-progress", startedAt: 21))
        try db.exec("CREATE TRIGGER reject_progress BEFORE INSERT ON exam_sync_outbox " +
                    "WHEN new.event_type = 'progress' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        XCTAssertThrowsError(try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-progress", questionID: "q1",
            responseJSON: "{\"answer\":\"lost\"}", position: 9, savedAt: 22))
        let history = try ExamHistoryDao.load(db, attemptID: "attempt-progress")!
        XCTAssertEqual(Int64(0), history.attempt.long("position"))
        XCTAssertEqual(Int64(1), history.attempt.long("revision"))
        XCTAssertTrue(history.answers.isEmpty)
        XCTAssertEqual(Int64(1), try db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox").first!.long("n"))
    }

    func testFailedCompletionOutboxRollsBackResultAndState() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-complete", "content-complete"),
            attempt: ExamAttemptRecord(attemptID: "attempt-complete", examID: "exam-complete", startedAt: 23))
        try db.exec("CREATE TRIGGER reject_completion BEFORE INSERT ON exam_sync_outbox " +
                    "WHEN new.event_type = 'completed' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        XCTAssertThrowsError(try ExamHistoryDao.complete(db, attemptID: "attempt-complete",
            resultJSON: "{\"score\":80}", completedAt: 24))
        let history = try ExamHistoryDao.load(db, attemptID: "attempt-complete")!
        XCTAssertEqual("in_progress", history.attempt.text("state"))
        XCTAssertNil(history.result)
        XCTAssertEqual(Int64(1), history.attempt.long("revision"))
    }

    func testEqualContentRetainsEachExamIdentityAndReusesOneSnapshot() throws {
        let original = snapshot("exam-a", "content-shared")
        let firstAttempt = ExamAttemptRecord(attemptID: "attempt-a", examID: "exam-a", startedAt: 50)
        _ = try ExamHistoryDao.start(db, snapshot: original, attempt: firstAttempt)
        _ = try ExamHistoryDao.start(db, snapshot: original, attempt: firstAttempt)
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-b", "content-shared"),
            attempt: ExamAttemptRecord(attemptID: "attempt-b", examID: "exam-b", startedAt: 51))
        XCTAssertEqual(Int64(1), try db.query("SELECT COUNT(*) AS n FROM exam_snapshots").first!.long("n"))
        XCTAssertEqual(Int64(2), try db.query("SELECT COUNT(*) AS n FROM exam_snapshot_ids").first!.long("n"))
        XCTAssertEqual(Int64(2), try db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox").first!.long("n"))
        XCTAssertEqual("exam-b", try ExamHistoryDao.load(db, attemptID: "attempt-b")!.attempt.text("exam_id"))
    }

    func testSnapshotContentCannotBeUpdated() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-immutable", "content-immutable"),
            attempt: ExamAttemptRecord(attemptID: "attempt-immutable", examID: "exam-immutable", startedAt: 60))
        XCTAssertThrowsError(try db.update("UPDATE exam_snapshots SET content_json = '{}' WHERE content_id = ?",
                                           args: ["content-immutable"]))
    }

    func testHistoryReadsAreScopedToTheActiveAccount() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-owner-a", "content-owner-a"),
            attempt: ExamAttemptRecord(attemptID: "attempt-owner-a", examID: "exam-owner-a",
                                      ownerAccountID: "account-a", startedAt: 25))
        XCTAssertEqual("account-a", try ExamHistoryDao.load(db, attemptID: "attempt-owner-a",
            ownerAccountID: "account-a")?.attempt.text("owner_account_id"))
        XCTAssertNil(try ExamHistoryDao.load(db, attemptID: "attempt-owner-a", ownerAccountID: "account-b"))
        XCTAssertNil(try ExamHistoryDao.load(db, attemptID: "attempt-owner-a"))
    }

    private func snapshot(_ examID: String, _ contentID: String) -> ExamSnapshotRecord {
        ExamSnapshotRecord(examID: examID, contentID: contentID, contentHash: "hash-\(contentID)",
            schemaVersion: 1, contentRevision: 1, gradingVersion: 1, source: "bundled",
            languageID: "es", level: "B1", weekID: 202641,
            contentJSON: "{\"question\":\"frozen\"}", createdAt: 1)
    }
}
