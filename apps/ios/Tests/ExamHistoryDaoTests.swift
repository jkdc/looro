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

    func testClockRollbackCannotRegressPersistedAttemptOrOutboxTime() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-clock", "content-clock"),
            attempt: ExamAttemptRecord(attemptID: "attempt-clock", examID: "exam-clock", startedAt: 100))
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-clock", questionID: "q1",
            responseJSON: "{\"answer\":\"one\"}", position: 1, savedAt: 99)
        XCTAssertEqual(100, try db.query("SELECT updated_at FROM exam_attempts WHERE attempt_id = 'attempt-clock'")
            .first!.real("updated_at"))
        XCTAssertEqual(100, try db.query("SELECT saved_at FROM exam_answers WHERE attempt_id = 'attempt-clock'")
            .first!.real("saved_at"))
        XCTAssertEqual(100, try db.query("SELECT created_at FROM exam_sync_outbox WHERE event_type = 'progress'")
            .first!.real("created_at"))

        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-clock", questionID: "q1",
            responseJSON: "{\"answer\":\"two\"}", position: 2, savedAt: 105)
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-clock", questionID: "q1",
            responseJSON: "{\"answer\":\"three\"}", position: 3, savedAt: 104)
        XCTAssertEqual([100, 105, 105], try db.query(
            "SELECT created_at FROM exam_sync_outbox WHERE event_type = 'progress' ORDER BY revision").map {
                $0.real("created_at")
            })

        XCTAssertTrue(try ExamHistoryDao.complete(db, attemptID: "attempt-clock",
            resultJSON: "{\"score\":90}", completedAt: 104))
        let completed = try db.query("SELECT updated_at, completed_at FROM exam_attempts " +
                                     "WHERE attempt_id = 'attempt-clock'").first!
        XCTAssertEqual(105, completed.real("updated_at"))
        XCTAssertEqual(105, completed.real("completed_at"))
        XCTAssertEqual(105, try db.query("SELECT created_at FROM exam_sync_outbox WHERE event_type = 'completed'")
            .first!.real("created_at"))
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

    func testOutboxAcknowledgementIsRevisionExactAndPreservesNewerEdits() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-sync", "content-sync"),
            attempt: ExamAttemptRecord(attemptID: "attempt-sync", examID: "exam-sync",
                                       ownerAccountID: "account-sync", startedAt: 25))
        let created = try db.query("SELECT event_id FROM exam_sync_outbox WHERE revision = 1").first!.text("event_id")
        XCTAssertTrue(try ExamHistoryDao.acknowledgeOutbox(db, ownerAccountID: "account-sync",
            attemptID: "attempt-sync", eventID: created, revision: 1, acknowledgedAt: 26))
        XCTAssertEqual("synced", try db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").first!.text("sync_state"))

        XCTAssertEqual(2, try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-sync", questionID: "q1",
            responseJSON: "{\"answer\":\"first\"}", position: 1, savedAt: 27))
        XCTAssertEqual("pending", try db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").first!.text("sync_state"))
        XCTAssertEqual(3, try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-sync", questionID: "q1",
            responseJSON: "{\"answer\":\"newer\"}", position: 2, savedAt: 28))
        let revisionTwo = try db.query("SELECT event_id FROM exam_sync_outbox WHERE revision = 2").first!.text("event_id")
        XCTAssertTrue(try ExamHistoryDao.acknowledgeOutbox(db, ownerAccountID: "account-sync",
            attemptID: "attempt-sync", eventID: revisionTwo, revision: 2, acknowledgedAt: 29))
        XCTAssertEqual("pending", try db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").first!.text("sync_state"))
        XCTAssertEqual("pending", try db.query("SELECT delivery_state FROM exam_sync_outbox WHERE revision = 3").first!.text("delivery_state"))
        XCTAssertFalse(try ExamHistoryDao.acknowledgeOutbox(db, ownerAccountID: "other-account",
            attemptID: "attempt-sync", eventID: revisionTwo, revision: 2, acknowledgedAt: 30))
        let revisionThree = try db.query("SELECT event_id FROM exam_sync_outbox WHERE revision = 3").first!.text("event_id")
        XCTAssertTrue(try ExamHistoryDao.acknowledgeOutbox(db, ownerAccountID: "account-sync",
            attemptID: "attempt-sync", eventID: revisionThree, revision: 3, acknowledgedAt: 31))
        XCTAssertEqual("synced", try db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").first!.text("sync_state"))
        XCTAssertTrue(try ExamHistoryDao.markConflict(db, ownerAccountID: "account-sync", attemptID: "attempt-sync"))
        XCTAssertEqual("conflict", try db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").first!.text("sync_state"))
    }

    func testConflictPersistsServerAndLocalBranchesWithoutCrossAccountAccess() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-conflict", "content-conflict"),
            attempt: ExamAttemptRecord(attemptID: "attempt-conflict", examID: "exam-conflict",
                                       ownerAccountID: "account-a", startedAt: 25))
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-conflict", questionID: "q1",
            responseJSON: "{\"answer\":\"local\"}", position: 1, savedAt: 26)
        let server = "{\"attemptId\":\"attempt-conflict\",\"revision\":3,\"state\":\"in_progress\",\"answers\":[{\"questionId\":\"q1\",\"response\":{\"answer\":\"server\"}}]}"
        XCTAssertTrue(try ExamHistoryDao.persistConflict(db, ownerAccountID: "account-a",
            attemptID: "attempt-conflict", serverAttemptJSON: server, receivedAt: 27))
        let conflict = try XCTUnwrap(ExamHistoryDao.conflict(db, ownerAccountID: "account-a", attemptID: "attempt-conflict"))
        XCTAssertEqual(3, conflict.long("server_revision"))
        XCTAssertEqual(server, conflict.text("server_attempt_json"))
        XCTAssertTrue(conflict.text("local_attempt_json").contains("local"))
        XCTAssertFalse(try ExamHistoryDao.persistConflict(db, ownerAccountID: "account-b",
            attemptID: "attempt-conflict", serverAttemptJSON: server, receivedAt: 28))
        XCTAssertNil(try ExamHistoryDao.conflict(db, ownerAccountID: "account-b", attemptID: "attempt-conflict"))
    }

    func testExplicitTakeoverAdoptsServerBranchAndSupersedesLocalEvents() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-takeover", "content-takeover"),
            attempt: ExamAttemptRecord(attemptID: "attempt-takeover", examID: "exam-takeover",
                                       ownerAccountID: "account-takeover", startedAt: 40))
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-takeover", questionID: "q1",
            responseJSON: "{\"answer\":\"local\"}", position: 2, savedAt: 41)
        let server = "{\"attemptId\":\"attempt-takeover\",\"examId\":\"exam-takeover\",\"revision\":4,\"state\":\"in_progress\",\"position\":1,\"startedAt\":\"1970-01-01T00:00:40.000Z\",\"updatedAt\":\"1970-01-01T00:00:42.000Z\",\"answers\":[{\"questionId\":\"q1\",\"revision\":3,\"response\":{\"answer\":\"server\"}}]}"
        XCTAssertTrue(try ExamHistoryDao.persistConflict(db, ownerAccountID: "account-takeover",
            attemptID: "attempt-takeover", serverAttemptJSON: server, receivedAt: 43))
        XCTAssertTrue(try ExamHistoryDao.resolveConflictTakeover(db, ownerAccountID: "account-takeover",
            attemptID: "attempt-takeover", resolvedAt: 44))
        let attempt = try db.query("SELECT revision, sync_state FROM exam_attempts WHERE attempt_id = ?",
                                   args: ["attempt-takeover"]).first!
        XCTAssertEqual(5, attempt.long("revision")); XCTAssertEqual("pending", attempt.text("sync_state"))
        XCTAssertTrue(try db.query("SELECT response_json FROM exam_answers WHERE attempt_id = ?",
            args: ["attempt-takeover"]).first!.text("response_json").contains("server"))
        XCTAssertEqual(2, try db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox WHERE attempt_id = ? AND delivery_state = 'superseded'",
            args: ["attempt-takeover"]).first!.long("n"))
        let takeover = try db.query("SELECT event_type, revision FROM exam_sync_outbox WHERE attempt_id = ? AND delivery_state = 'pending'",
                                    args: ["attempt-takeover"]).first!
        XCTAssertEqual("takeover", takeover.text("event_type")); XCTAssertEqual(5, takeover.long("revision"))
        XCTAssertEqual("takeover", try ExamHistoryDao.conflict(db, ownerAccountID: "account-takeover",
            attemptID: "attempt-takeover")?.text("resolution"))
    }

    func testSecondConflictCanBeResolvedAfterEarlierTakeover() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-repeat", "content-repeat"),
            attempt: ExamAttemptRecord(attemptID: "attempt-repeat", examID: "exam-repeat",
                                       ownerAccountID: "account-repeat", startedAt: 40))
        let first = "{\"attemptId\":\"attempt-repeat\",\"examId\":\"exam-repeat\",\"revision\":2,\"state\":\"in_progress\",\"position\":0,\"startedAt\":\"1970-01-01T00:00:40.000Z\",\"updatedAt\":\"1970-01-01T00:00:42.000Z\",\"answers\":[],\"result\":null}"
        XCTAssertTrue(try ExamHistoryDao.persistConflict(db, ownerAccountID: "account-repeat",
            attemptID: "attempt-repeat", serverAttemptJSON: first, receivedAt: 42))
        XCTAssertTrue(try ExamHistoryDao.resolveConflictTakeover(db, ownerAccountID: "account-repeat",
            attemptID: "attempt-repeat", resolvedAt: 43))
        let second = first.replacingOccurrences(of: "\"revision\":2", with: "\"revision\":4")
            .replacingOccurrences(of: "00:00:42", with: "00:00:44")
        XCTAssertTrue(try ExamHistoryDao.persistConflict(db, ownerAccountID: "account-repeat",
            attemptID: "attempt-repeat", serverAttemptJSON: second, receivedAt: 44))
        XCTAssertNil(try ExamHistoryDao.conflict(db, ownerAccountID: "account-repeat",
            attemptID: "attempt-repeat")?.textOrNull("resolution"))
        XCTAssertTrue(try ExamHistoryDao.resolveConflictTakeover(db, ownerAccountID: "account-repeat",
            attemptID: "attempt-repeat", resolvedAt: 45))
    }

    func testNullableServerResultDoesNotBlockCompletionAfterTakeover() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-null", "content-null"),
            attempt: ExamAttemptRecord(attemptID: "attempt-null", examID: "exam-null",
                                       ownerAccountID: "account-null", startedAt: 40))
        let server = "{\"attemptId\":\"attempt-null\",\"examId\":\"exam-null\",\"revision\":2,\"state\":\"in_progress\",\"position\":0,\"startedAt\":\"1970-01-01T00:00:40.000Z\",\"updatedAt\":\"1970-01-01T00:00:42.000Z\",\"answers\":[],\"result\":null}"
        XCTAssertTrue(try ExamHistoryDao.persistConflict(db, ownerAccountID: "account-null",
            attemptID: "attempt-null", serverAttemptJSON: server, receivedAt: 42))
        XCTAssertTrue(try ExamHistoryDao.resolveConflictTakeover(db, ownerAccountID: "account-null",
            attemptID: "attempt-null", resolvedAt: 43))
        XCTAssertEqual(0, try db.query("SELECT COUNT(*) AS n FROM exam_results WHERE attempt_id = ?",
            args: ["attempt-null"]).first!.long("n"))
        XCTAssertTrue(try ExamHistoryDao.complete(db, attemptID: "attempt-null", resultJSON: "{\"score\":80}", completedAt: 44))
    }

    func testExplicitForkCreatesNewOwnedAttemptAndRetainsLocalAnswers() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-fork", "content-fork"),
            attempt: ExamAttemptRecord(attemptID: "attempt-fork-source", examID: "exam-fork",
                                       ownerAccountID: "account-fork", startedAt: 40))
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-fork-source", questionID: "q1",
            responseJSON: "{\"answer\":\"local\"}", position: 2, savedAt: 41)
        let server = "{\"attemptId\":\"attempt-fork-source\",\"examId\":\"exam-fork\",\"revision\":3,\"state\":\"in_progress\",\"position\":1,\"startedAt\":\"1970-01-01T00:00:40.000Z\",\"updatedAt\":\"1970-01-01T00:00:42.000Z\",\"answers\":[],\"result\":null}"
        XCTAssertTrue(try ExamHistoryDao.persistConflict(db, ownerAccountID: "account-fork",
            attemptID: "attempt-fork-source", serverAttemptJSON: server, receivedAt: 42))
        XCTAssertTrue(try ExamHistoryDao.resolveConflictFork(db, ownerAccountID: "account-fork",
            attemptID: "attempt-fork-source", forkAttemptID: "attempt-fork-copy", forkedAt: 43))
        XCTAssertEqual("account-fork", try db.query("SELECT owner_account_id FROM exam_attempts WHERE attempt_id = ?",
            args: ["attempt-fork-copy"]).first!.text("owner_account_id"))
        XCTAssertTrue(try db.query("SELECT response_json FROM exam_answers WHERE attempt_id = ?",
            args: ["attempt-fork-copy"]).first!.text("response_json").contains("local"))
        XCTAssertEqual("created", try db.query("SELECT event_type FROM exam_sync_outbox WHERE attempt_id = ?",
            args: ["attempt-fork-copy"]).first!.text("event_type"))
    }

    private func snapshot(_ examID: String, _ contentID: String) -> ExamSnapshotRecord {
        ExamSnapshotRecord(examID: examID, contentID: contentID, contentHash: "hash-\(contentID)",
            schemaVersion: 1, contentRevision: 1, gradingVersion: 1, source: "bundled",
            languageID: "es", level: "B1", weekID: 202641,
            contentJSON: "{\"question\":\"frozen\"}", createdAt: 1)
    }
}
