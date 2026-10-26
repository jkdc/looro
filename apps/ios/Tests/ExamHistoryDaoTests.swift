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

    func testForeignOwnerCannotMutateAttemptOrAdvanceItsRevisionAndPosition() throws {
        try LearnerOwnershipDao.activate(db, identity: "member:owner-a")
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-foreign", "content-foreign"),
            attempt: ExamAttemptRecord(attemptID: "attempt-foreign", examID: "exam-foreign",
                ownerAccountID: "owner-a", startedAt: 10))
        let before = try db.query("SELECT revision, position, state FROM exam_attempts WHERE attempt_id = 'attempt-foreign'").first!

        try LearnerOwnershipDao.activate(db, identity: "member:owner-b")
        XCTAssertThrowsError(try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-foreign", questionID: "q1",
            responseJSON: "{\"answer\":\"foreign\"}", position: 9, savedAt: 20)) {
            XCTAssertEqual($0 as? ExamHistoryError, .missingAttempt)
        }
        XCTAssertThrowsError(try ExamHistoryDao.complete(db, attemptID: "attempt-foreign",
            resultJSON: "{\"score\":100}", completedAt: 21)) {
            XCTAssertEqual($0 as? ExamHistoryError, .missingAttempt)
        }

        let after = try db.query("SELECT revision, position, state FROM exam_attempts WHERE attempt_id = 'attempt-foreign'").first!
        XCTAssertEqual(before.long("revision"), after.long("revision"))
        XCTAssertEqual(before.long("position"), after.long("position"))
        XCTAssertEqual(before.text("state"), after.text("state"))
        XCTAssertEqual(0, try db.query("SELECT COUNT(*) AS n FROM exam_answers WHERE attempt_id = 'attempt-foreign'").first!.long("n"))
        XCTAssertEqual(0, try db.query("SELECT COUNT(*) AS n FROM exam_results WHERE attempt_id = 'attempt-foreign'").first!.long("n"))
        XCTAssertEqual(["created"], try db.query("SELECT event_type FROM exam_sync_outbox WHERE attempt_id = 'attempt-foreign'")
            .map { $0.text("event_type") })
    }

    func testLatestAnswerRevisionIsSelectedInsideTheAttemptOwnerPartition() throws {
        try LearnerOwnershipDao.activate(db, identity: "member:owner-a")
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-owner-revision", "content-owner-revision"),
            attempt: ExamAttemptRecord(attemptID: "attempt-owner-revision", examID: "exam-owner-revision",
                ownerAccountID: "owner-a", startedAt: 10))
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-owner-revision", questionID: "q1",
            responseJSON: "{\"answer\":\"owner-a\"}", position: 1, savedAt: 11)
        _ = try db.insert("exam_answers", ["attempt_id": "attempt-owner-revision", "question_id": "q1",
            "revision": 99, "response_json": "{\"answer\":\"owner-b\"}", "saved_at": 99,
            "owner_key": "member:owner-b"])

        let visible = try XCTUnwrap(ExamHistoryDao.load(db, attemptID: "attempt-owner-revision", ownerAccountID: "owner-a"))
        XCTAssertEqual(["{\"answer\":\"owner-a\"}"], visible.answers.map { $0.text("response_json") })
    }

    func testCreatedOutboxRetainsImmutableInitialAttemptAfterOfflineEdits() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-create-retry", "content-create-retry"),
            attempt: ExamAttemptRecord(attemptID: "attempt-create-retry", examID: "exam-create-retry",
                ownerAccountID: "account-create-retry", startedAt: 100))
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-create-retry", questionID: "q1",
            responseJSON: "{\"answer\":\"later\"}", position: 1, savedAt: 101)
        let payload = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(
            db.query("SELECT payload_json FROM exam_sync_outbox WHERE event_type = 'created'").first!.text("payload_json").utf8
        )) as? [String: Any])
        let initial = try XCTUnwrap(payload["initialAttempt"] as? [String: Any])
        XCTAssertEqual("attempt-create-retry", initial["attemptId"] as? String)
        XCTAssertEqual(1, initial["revision"] as? Int)
        XCTAssertEqual(0, initial["position"] as? Int)
        XCTAssertTrue((initial["answers"] as? [Any])?.isEmpty == true)
        XCTAssertTrue(initial["result"] is NSNull)
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

    func testHistoryListReturnsOnlyTheSelectedOwnerInMostRecentlyUpdatedOrder() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-list-a", "content-list-a"),
            attempt: ExamAttemptRecord(attemptID: "attempt-list-a", examID: "exam-list-a",
                                      ownerAccountID: "account-a", startedAt: 10))
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-list-b", "content-list-b"),
            attempt: ExamAttemptRecord(attemptID: "attempt-list-b", examID: "exam-list-b",
                                      ownerAccountID: "account-b", startedAt: 20))
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-list-a", questionID: "q1",
            responseJSON: "{\"answer\":\"a\"}", position: 1, savedAt: 30)

        let ownerA = try ExamHistoryDao.history(db, ownerAccountID: "account-a")
        XCTAssertEqual(["attempt-list-a"], ownerA.map { $0.attempt.text("attempt_id") })
        XCTAssertEqual([], try ExamHistoryDao.history(db, ownerAccountID: "account-missing"))
    }

    func testOwnerlessExamHistoryIsBackfilledOnceToTheMigrationIdentity() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-legacy-owner", "content-legacy-owner"),
            attempt: ExamAttemptRecord(attemptID: "attempt-legacy-owner", examID: "exam-legacy-owner", startedAt: 10))

        XCTAssertTrue(try ExamHistoryDao.assignOwnerlessToIdentityOnce(db, ownerAccountID: "member-a"))
        XCTAssertFalse(try ExamHistoryDao.assignOwnerlessToIdentityOnce(db, ownerAccountID: "member-b"))

        XCTAssertEqual("member-a", try ExamHistoryDao.load(db, attemptID: "attempt-legacy-owner",
            ownerAccountID: "member-a")?.attempt.text("owner_account_id"))
        XCTAssertNil(try ExamHistoryDao.load(db, attemptID: "attempt-legacy-owner", ownerAccountID: "member-b"))
        XCTAssertNil(try ExamHistoryDao.load(db, attemptID: "attempt-legacy-owner"))
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

    func testForkUsesLatestLocalAnswersPositionAndResultAfterConflictCapture() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-fork-latest", "content-fork-latest"),
            attempt: ExamAttemptRecord(attemptID: "attempt-fork-latest", examID: "exam-fork-latest",
                ownerAccountID: "account-fork-latest", startedAt: 40))
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-fork-latest", questionID: "q1",
            responseJSON: "{\"answer\":\"before-conflict\"}", position: 1, savedAt: 41)
        let server = "{\"attemptId\":\"attempt-fork-latest\",\"examId\":\"exam-fork-latest\",\"revision\":3,\"state\":\"in_progress\",\"position\":0,\"startedAt\":\"1970-01-01T00:00:40.000Z\",\"updatedAt\":\"1970-01-01T00:00:42.000Z\",\"answers\":[],\"result\":null}"
        XCTAssertTrue(try ExamHistoryDao.persistConflict(db, ownerAccountID: "account-fork-latest",
            attemptID: "attempt-fork-latest", serverAttemptJSON: server, receivedAt: 42))

        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-fork-latest", questionID: "q1",
            responseJSON: "{\"answer\":\"after-conflict\"}", position: 3, savedAt: 43)
        XCTAssertEqual("conflict", try db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = ?",
            args: ["attempt-fork-latest"]).first!.text("sync_state"))
        XCTAssertTrue(try ExamHistoryDao.complete(db, attemptID: "attempt-fork-latest",
            resultJSON: "{\"score\":91}", completedAt: 44))
        XCTAssertEqual("conflict", try db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = ?",
            args: ["attempt-fork-latest"]).first!.text("sync_state"))

        XCTAssertTrue(try ExamHistoryDao.resolveConflictFork(db, ownerAccountID: "account-fork-latest",
            attemptID: "attempt-fork-latest", forkAttemptID: "attempt-fork-latest-copy", forkedAt: 45))
        let fork = try XCTUnwrap(ExamHistoryDao.load(db, attemptID: "attempt-fork-latest-copy",
            ownerAccountID: "account-fork-latest"))
        XCTAssertEqual("completed", fork.attempt.text("state"))
        XCTAssertEqual(3, fork.attempt.long("position"))
        XCTAssertTrue(fork.answers.first!.text("response_json").contains("after-conflict"))
        XCTAssertTrue(fork.result!.text("result_json").contains("91"))
        XCTAssertEqual("fork", try ExamHistoryDao.conflict(db, ownerAccountID: "account-fork-latest",
            attemptID: "attempt-fork-latest")?.text("resolution"))
    }

    func testForkedSourceRejectsLaterAnswerAndCompletionWrites() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-fork-immutable", "content-fork-immutable"),
            attempt: ExamAttemptRecord(attemptID: "attempt-fork-immutable", examID: "exam-fork-immutable",
                ownerAccountID: "account-fork-immutable", startedAt: 40))
        let server = "{\"attemptId\":\"attempt-fork-immutable\",\"examId\":\"exam-fork-immutable\",\"revision\":2,\"state\":\"in_progress\",\"position\":0,\"startedAt\":\"1970-01-01T00:00:40.000Z\",\"updatedAt\":\"1970-01-01T00:00:42.000Z\",\"answers\":[],\"result\":null}"
        XCTAssertTrue(try ExamHistoryDao.persistConflict(db, ownerAccountID: "account-fork-immutable",
            attemptID: "attempt-fork-immutable", serverAttemptJSON: server, receivedAt: 42))
        XCTAssertTrue(try ExamHistoryDao.resolveConflictFork(db, ownerAccountID: "account-fork-immutable",
            attemptID: "attempt-fork-immutable", forkAttemptID: "attempt-fork-immutable-copy", forkedAt: 43))

        XCTAssertThrowsError(try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-fork-immutable",
            questionID: "q1", responseJSON: "{\"answer\":\"late\"}", position: 1, savedAt: 44))
        XCTAssertThrowsError(try ExamHistoryDao.complete(db, attemptID: "attempt-fork-immutable",
            resultJSON: "{\"score\":70}", completedAt: 44))
        XCTAssertEqual(1, try db.query("SELECT revision FROM exam_attempts WHERE attempt_id = ?",
            args: ["attempt-fork-immutable"]).first!.long("revision"))
    }

    func testRejectedForkLeavesSourceOutboxAndConflictUntouched() throws {
        _ = try ExamHistoryDao.start(db, snapshot: snapshot("exam-fork-rollback", "content-fork-rollback"),
            attempt: ExamAttemptRecord(attemptID: "attempt-fork-rollback", examID: "exam-fork-rollback",
                ownerAccountID: "account-fork-rollback", startedAt: 40))
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "attempt-fork-rollback", questionID: "q1",
            responseJSON: "{\"answer\":\"local\"}", position: 1, savedAt: 41)
        let server = "{\"attemptId\":\"attempt-fork-rollback\",\"examId\":\"exam-fork-rollback\",\"revision\":2,\"state\":\"in_progress\",\"position\":0,\"startedAt\":\"1970-01-01T00:00:40.000Z\",\"updatedAt\":\"1970-01-01T00:00:42.000Z\",\"answers\":[],\"result\":null}"
        XCTAssertTrue(try ExamHistoryDao.persistConflict(db, ownerAccountID: "account-fork-rollback",
            attemptID: "attempt-fork-rollback", serverAttemptJSON: server, receivedAt: 42))
        try db.update("UPDATE exam_sync_conflicts SET local_attempt_json = ? WHERE attempt_id = ?",
            args: ["{\"attempt\":{\"state\":\"invalid\",\"position\":1},\"answers\":[],\"result\":null}",
                   "attempt-fork-rollback"])

        XCTAssertFalse(try ExamHistoryDao.resolveConflictFork(db, ownerAccountID: "account-fork-rollback",
            attemptID: "attempt-fork-rollback", forkAttemptID: "attempt-fork-rollback-copy", forkedAt: 43))
        XCTAssertTrue(try db.query("SELECT 1 AS found FROM exam_attempts WHERE attempt_id = ?",
            args: ["attempt-fork-rollback-copy"]).isEmpty)
        XCTAssertTrue(try db.query("SELECT delivery_state FROM exam_sync_outbox WHERE attempt_id = ?",
            args: ["attempt-fork-rollback"]).allSatisfy { $0.text("delivery_state") == "pending" })
        XCTAssertNil(try ExamHistoryDao.conflict(db, ownerAccountID: "account-fork-rollback",
            attemptID: "attempt-fork-rollback")?.textOrNull("resolution"))
    }

    func testRestoreImportsSyncedAttemptWithoutCreatingOutbox() throws {
        let contentID = "sha256:" + String(repeating: "a", count: 64)
        let snapshot = ExamSnapshotRecord(examID: contentID, contentID: contentID,
            contentHash: String(repeating: "a", count: 64), schemaVersion: 1, contentRevision: 1,
            gradingVersion: 1, source: "bundled", languageID: "es", level: "B1", weekID: 202641,
            contentJSON: "{\"examId\":\"\(contentID)\",\"contentId\":\"\(contentID)\",\"contentHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"schemaVersion\":1,\"contentRevision\":1,\"gradingVersion\":1,\"source\":\"bundled\",\"languageID\":\"es\",\"level\":\"B1\",\"weekId\":202641,\"items\":[{\"id\":\"q1\"}]}", createdAt: 1)
        let remote = """
        {"attemptId":"00000000-0000-4000-8000-000000000001","examId":"\(contentID)","state":"in_progress","position":1,"revision":2,"startedAt":"2026-10-17T10:00:00.000Z","updatedAt":"2026-10-17T10:01:00.000Z","completedAt":null,"answers":[{"questionId":"q1","revision":2,"response":{"answer":"saved"}}],"result":null}
        """
        XCTAssertTrue(try ExamHistoryDao.restore(db, snapshot: snapshot, attemptJSON: remote,
            ownerAccountID: "account-restore", restoredAt: 100))
        let history = try XCTUnwrap(ExamHistoryDao.load(db,
            attemptID: "00000000-0000-4000-8000-000000000001", ownerAccountID: "account-restore"))
        XCTAssertEqual("synced", history.attempt.text("sync_state"))
        XCTAssertEqual(2, history.attempt.long("revision"))
        XCTAssertEqual(1, history.answers.count)
        XCTAssertTrue(try db.query("SELECT 1 AS found FROM exam_sync_outbox").isEmpty)
    }

    func testRestorePageRollsBackEveryAttemptWhenOneImportFails() throws {
        let first = restoreRecord(hash: String(repeating: "c", count: 64),
                                  attemptID: "00000000-0000-4000-8000-000000000003")
        let second = restoreRecord(hash: String(repeating: "d", count: 64),
                                   attemptID: "00000000-0000-4000-8000-000000000004")
        try db.exec("CREATE TRIGGER reject_second_restore BEFORE INSERT ON exam_attempts " +
                    "WHEN new.attempt_id = '00000000-0000-4000-8000-000000000004' " +
                    "BEGIN SELECT RAISE(ABORT, 'test failure'); END")

        XCTAssertThrowsError(try ExamHistoryDao.restorePage(db, records: [first, second],
            ownerAccountID: "account-page", restoredAt: 200))
        XCTAssertNil(try ExamHistoryDao.load(db, attemptID: first.attemptID, ownerAccountID: "account-page"))
        XCTAssertEqual(0, try db.query("SELECT COUNT(*) AS n FROM exam_snapshots").first!.long("n"))
        XCTAssertEqual(0, try db.query("SELECT COUNT(*) AS n FROM exam_attempts").first!.long("n"))
        XCTAssertEqual(0, try db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox").first!.long("n"))
    }

    func testRestoreNeverOverwritesAnExistingAnonymousAttempt() throws {
        let contentID = "sha256:" + String(repeating: "b", count: 64)
        let snapshot = ExamSnapshotRecord(examID: contentID, contentID: contentID,
            contentHash: String(repeating: "b", count: 64), schemaVersion: 1, contentRevision: 1,
            gradingVersion: 1, source: "bundled", languageID: "es", level: "B1", weekID: 202641,
            contentJSON: "{\"examId\":\"\(contentID)\",\"contentId\":\"\(contentID)\",\"contentHash\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"schemaVersion\":1,\"contentRevision\":1,\"gradingVersion\":1,\"source\":\"bundled\",\"languageID\":\"es\",\"level\":\"B1\",\"weekId\":202641,\"items\":[{\"id\":\"q1\"}]}", createdAt: 1)
        let attemptID = "00000000-0000-4000-8000-000000000002"
        _ = try ExamHistoryDao.start(db, snapshot: snapshot,
            attempt: ExamAttemptRecord(attemptID: attemptID, examID: contentID, startedAt: 10))
        let remote = """
        {"attemptId":"\(attemptID)","examId":"\(contentID)","state":"in_progress","position":0,"revision":1,"startedAt":"1970-01-01T00:00:10.000Z","updatedAt":"1970-01-01T00:00:10.000Z","completedAt":null,"answers":[],"result":null}
        """
        XCTAssertFalse(try ExamHistoryDao.restore(db, snapshot: snapshot, attemptJSON: remote,
            ownerAccountID: "account-restore", restoredAt: 100))
        XCTAssertNotNil(try ExamHistoryDao.load(db, attemptID: attemptID))
        XCTAssertNil(try ExamHistoryDao.load(db, attemptID: attemptID, ownerAccountID: "account-restore"))
    }

    /// T12 exact-restore proof: the imported attempt must carry the frozen
    /// content — item order, options, passage text — plus the account's own
    /// answers, position and original result, byte for byte.
    func testRestorePreservesExactFrozenContentOrderOptionsAnswersAndResult() throws {
        let passage = LevelTestPassage(id: "p1", title: "Frozen passage", text: "Frozen passage text.")
        let items = [
            LevelTestItem(id: "q3", section: .reading,
                exercise: Exercise(id: "q3", kind: "choice", prompt: "Third asked?",
                    options: ["a3", "b3", "c3"], answers: ["a3"], explain: "Because a3."),
                unitID: "u1", passageId: "p1", speech: nil),
            LevelTestItem(id: "q1", section: .recognition,
                exercise: Exercise(id: "q1", kind: "written", prompt: "First asked?",
                    options: [], answers: ["ans1"], explain: "Rule one."),
                unitID: nil, passageId: nil, speech: nil),
            LevelTestItem(id: "q2", section: .writing,
                exercise: Exercise(id: "q2", kind: "written", prompt: "Second asked?",
                    options: [], answers: ["ans2"], explain: "Rule two."),
                unitID: nil, passageId: nil, speech: nil),
        ]
        let level = LevelTestLevel(languageID: "en", level: "A1", version: 3,
            thresholds: ["overall": 70], passages: [passage], pool: items)
        let exam = LevelTestEngine.Exam(weekIndex: 202640, level: "A1", items: items,
            passageById: ["p1": passage], readingPassageIDs: ["p1"], listeningPassageIDs: [])
        let record = try ExamSnapshotFactory.bundled(level: level, exam: exam,
            now: Date(timeIntervalSince1970: 100), source: "deterministic")
        let attemptID = "00000000-0000-4000-8000-000000000010"
        let result: [String: Any] = ["overall": 66, "passed": false,
            "verdicts": ["q1": true, "q2": false, "q3": false]]
        let remote: [String: Any] = [
            "attemptId": attemptID, "examId": record.examID, "state": "completed",
            "position": 2, "revision": 4,
            "startedAt": "2026-10-17T10:00:00.000Z", "updatedAt": "2026-10-17T10:05:00.000Z",
            "completedAt": "2026-10-17T10:05:00.000Z",
            "answers": [
                ["questionId": "q1", "revision": 2, "response": ["answer": "ans1"]],
                ["questionId": "q3", "revision": 4, "response": ["answer": "wrong"]],
            ],
            "result": result,
        ]
        let remoteBytes = try JSONSerialization.data(withJSONObject: remote, options: [.sortedKeys])
        let restored = try ExamHistoryDao.restore(db,
            snapshot: record, attemptJSON: String(data: remoteBytes, encoding: .utf8)!,
            ownerAccountID: "account-exact", restoredAt: 500)
        XCTAssertTrue(restored)

        let history = try XCTUnwrap(ExamHistoryDao.load(db,
            attemptID: attemptID, ownerAccountID: "account-exact"))
        // Frozen content: exact order, options and passage text survive.
        let content = try XCTUnwrap(try JSONSerialization.jsonObject(
            with: Data(history.snapshot.text("content_json").utf8)) as? [String: Any])
        let contentItems = try XCTUnwrap(content["items"] as? [[String: Any]])
        XCTAssertEqual(["q3", "q1", "q2"], contentItems.map { $0["id"] as? String })
        XCTAssertEqual(["a3", "b3", "c3"], (contentItems[0]["options"] as? [String]))
        let contentPassages = try XCTUnwrap(content["passages"] as? [[String: Any]])
        XCTAssertEqual("Frozen passage text.", contentPassages.first?["text"] as? String)
        XCTAssertEqual("deterministic", history.snapshot.text("source"))
        // Progress and ownership: position, per-item answers, original result.
        XCTAssertEqual(2, history.attempt.long("position"))
        XCTAssertEqual("synced", history.attempt.text("sync_state"))
        let savedAnswers = Dictionary(uniqueKeysWithValues: history.answers.map {
            ($0.text("question_id"), $0.text("response_json"))
        })
        XCTAssertEqual("{\"answer\":\"ans1\"}", savedAnswers["q1"])
        XCTAssertEqual("{\"answer\":\"wrong\"}", savedAnswers["q3"])
        let storedResult = try XCTUnwrap(try JSONSerialization.jsonObject(
            with: Data(history.result!.text("result_json").utf8)) as? [String: Any])
        XCTAssertEqual(66, storedResult["overall"] as? Int)
        XCTAssertEqual(false, storedResult["passed"] as? Bool)
        XCTAssertTrue(try db.query("SELECT 1 AS found FROM exam_sync_outbox").isEmpty)
    }

    /// Retired generated content keeps restoring: a snapshot whose source is
    /// `llm` and whose week is long gone imports exactly like a bundled one.
    func testRestoreAcceptsRetiredGeneratedSnapshot() throws {
        let passage = LevelTestPassage(id: "p1", title: "Generated", text: "Generated passage text.")
        let items = [
            LevelTestItem(id: "g1", section: .recognition,
                exercise: Exercise(id: "g1", kind: "choice", prompt: "Generated?",
                    options: ["x", "y"], answers: ["x"], explain: "X is right."),
                unitID: nil, passageId: nil, speech: nil),
        ]
        let level = LevelTestLevel(languageID: "en", level: "A1", version: 2,
            thresholds: ["overall": 70], passages: [passage], pool: items)
        let exam = LevelTestEngine.Exam(weekIndex: 202639, level: "A1", items: items,
            passageById: ["p1": passage], readingPassageIDs: [], listeningPassageIDs: [])
        let record = try ExamSnapshotFactory.bundled(level: level, exam: exam,
            now: Date(timeIntervalSince1970: 100), source: "llm")
        let attemptID = "00000000-0000-4000-8000-000000000011"
        let remote: [String: Any] = [
            "attemptId": attemptID, "examId": record.examID, "state": "in_progress",
            "position": 0, "revision": 1,
            "startedAt": "2026-09-21T08:00:00.000Z", "updatedAt": "2026-09-21T08:00:00.000Z",
            "completedAt": NSNull(), "answers": [Any](), "result": NSNull(),
        ]
        let remoteBytes = try JSONSerialization.data(withJSONObject: remote, options: [.sortedKeys])
        XCTAssertTrue(try ExamHistoryDao.restore(db, snapshot: record,
            attemptJSON: String(data: remoteBytes, encoding: .utf8)!,
            ownerAccountID: "account-retired", restoredAt: 500))
        let history = try XCTUnwrap(ExamHistoryDao.load(db,
            attemptID: attemptID, ownerAccountID: "account-retired"))
        XCTAssertEqual("llm", history.snapshot.text("source"))
        XCTAssertEqual(Int64(202639), history.snapshot.long("week_id"))
        XCTAssertEqual("in_progress", history.attempt.text("state"))
    }

    private func restoreRecord(hash: String, attemptID: String) -> ExamRestoreRecord {
        let contentID = "sha256:" + hash
        let snapshot = ExamSnapshotRecord(examID: contentID, contentID: contentID,
            contentHash: hash, schemaVersion: 1, contentRevision: 1, gradingVersion: 1,
            source: "bundled", languageID: "es", level: "B1", weekID: 202641,
            contentJSON: "{\"examId\":\"\(contentID)\",\"contentId\":\"\(contentID)\",\"contentHash\":\"\(hash)\",\"schemaVersion\":1,\"contentRevision\":1,\"gradingVersion\":1,\"source\":\"bundled\",\"languageID\":\"es\",\"level\":\"B1\",\"weekId\":202641,\"items\":[{\"id\":\"q1\"}]}", createdAt: 1)
        let attempt = """
        {"attemptId":"\(attemptID)","examId":"\(contentID)","state":"in_progress","position":0,"revision":1,"startedAt":"2026-10-17T10:00:00.000Z","updatedAt":"2026-10-17T10:00:00.000Z","completedAt":null,"answers":[],"result":null}
        """
        return ExamRestoreRecord(snapshot: snapshot, attemptJSON: attempt)
    }

    private func snapshot(_ examID: String, _ contentID: String) -> ExamSnapshotRecord {
        ExamSnapshotRecord(examID: examID, contentID: contentID, contentHash: "hash-\(contentID)",
            schemaVersion: 1, contentRevision: 1, gradingVersion: 1, source: "bundled",
            languageID: "es", level: "B1", weekID: 202641,
            contentJSON: "{\"question\":\"frozen\"}", createdAt: 1)
    }
}
