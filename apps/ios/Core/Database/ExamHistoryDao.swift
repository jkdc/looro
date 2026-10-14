import Foundation

public struct ExamSnapshotRecord {
    public let examID: String
    public let contentID: String
    public let contentHash: String
    public let schemaVersion: Int
    public let contentRevision: Int
    public let gradingVersion: Int
    public let source: String
    public let languageID: String
    public let level: String
    public let weekID: Int64
    public let contentJSON: String
    public let createdAt: Double

    public init(examID: String, contentID: String, contentHash: String, schemaVersion: Int,
                contentRevision: Int, gradingVersion: Int, source: String, languageID: String,
                level: String, weekID: Int64, contentJSON: String, createdAt: Double) {
        self.examID = examID; self.contentID = contentID; self.contentHash = contentHash
        self.schemaVersion = schemaVersion; self.contentRevision = contentRevision
        self.gradingVersion = gradingVersion; self.source = source; self.languageID = languageID
        self.level = level; self.weekID = weekID; self.contentJSON = contentJSON; self.createdAt = createdAt
    }
}

public struct ExamAttemptRecord {
    public let attemptID: String
    public let examID: String
    public let ownerAccountID: String?
    public let startedAt: Double

    public init(attemptID: String, examID: String, ownerAccountID: String? = nil, startedAt: Double) {
        self.attemptID = attemptID; self.examID = examID
        self.ownerAccountID = ownerAccountID; self.startedAt = startedAt
    }
}

public struct ExamHistory {
    public let attempt: SqlRow
    public let snapshot: SqlRow
    public let answers: [SqlRow]
    public let result: SqlRow?
}

public enum ExamHistoryError: Error, Equatable {
    case invalidJSON
    case immutableSnapshotConflict
    case immutableAttemptConflict
    case missingAttempt
    case completedAttempt
    case missingResult
    case immutableResultConflict
    case concurrentAttemptChange
}

/// Transactional local persistence for frozen exam content and attempt history.
public enum ExamHistoryDao {
    @discardableResult
    public static func start(_ db: SqlDatabase, snapshot: ExamSnapshotRecord,
                             attempt: ExamAttemptRecord) throws -> ExamHistory {
        guard snapshot.schemaVersion > 0, snapshot.contentRevision > 0, snapshot.gradingVersion > 0,
              snapshot.weekID > 0, ["bundled", "deterministic", "llm"].contains(snapshot.source),
              !attempt.attemptID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !snapshot.contentID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !snapshot.contentHash.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              attempt.examID == snapshot.examID,
              let snapshotObject = object(snapshot.contentJSON) else { throw ExamHistoryError.invalidJSON }

        try db.transaction { tx in
            _ = try tx.insert("exam_snapshots", [
                "content_id": snapshot.contentID, "content_hash": snapshot.contentHash,
                "schema_version": snapshot.schemaVersion, "content_revision": snapshot.contentRevision,
                "grading_version": snapshot.gradingVersion, "source": snapshot.source,
                "language_id": snapshot.languageID, "level": snapshot.level, "week_id": snapshot.weekID,
                "content_json": snapshot.contentJSON, "created_at": snapshot.createdAt,
            ], strategy: .ignore)
            guard let stored = try tx.query("SELECT * FROM exam_snapshots WHERE content_hash = ?",
                                            args: [snapshot.contentHash]).first,
                  stored.text("content_json") == snapshot.contentJSON,
                  stored.text("content_id") == snapshot.contentID,
                  stored.int("schema_version") == snapshot.schemaVersion,
                  stored.int("content_revision") == snapshot.contentRevision,
                  stored.int("grading_version") == snapshot.gradingVersion,
                  stored.text("source") == snapshot.source,
                  stored.text("language_id") == snapshot.languageID,
                  stored.text("level") == snapshot.level,
                  stored.long("week_id") == snapshot.weekID else {
                throw ExamHistoryError.immutableSnapshotConflict
            }
            _ = try tx.insert("exam_snapshot_ids", ["exam_id": snapshot.examID,
                "content_id": stored.text("content_id"), "created_at": snapshot.createdAt], strategy: .ignore)
            guard try tx.query("SELECT content_id FROM exam_snapshot_ids WHERE exam_id = ?",
                               args: [snapshot.examID]).first?.text("content_id") == stored.text("content_id") else {
                throw ExamHistoryError.immutableSnapshotConflict
            }
            let inserted = try tx.insert("exam_attempts", ["attempt_id": attempt.attemptID,
                "exam_id": attempt.examID, "owner_account_id": attempt.ownerAccountID as Any?,
                "state": "in_progress", "sync_state": attempt.ownerAccountID == nil ? "local_only" : "pending",
                "position": 0, "revision": 1, "started_at": attempt.startedAt,
                "updated_at": attempt.startedAt, "completed_at": nil], strategy: .ignore)
            guard let storedAttempt = try tx.query("SELECT * FROM exam_attempts WHERE attempt_id = ?",
                                                  args: [attempt.attemptID]).first,
                  storedAttempt.text("exam_id") == attempt.examID,
                  storedAttempt.textOrNull("owner_account_id") == attempt.ownerAccountID else {
                throw ExamHistoryError.immutableAttemptConflict
            }
            if inserted != -1 {
                try enqueue(tx, attemptID: attempt.attemptID, revision: storedAttempt.long("revision"),
                            type: "created", payload: jsonString(["attemptId": attempt.attemptID,
                            "examId": attempt.examID, "snapshot": snapshotObject]), at: attempt.startedAt)
            }
        }
        guard let history = try load(db, attemptID: attempt.attemptID,
                                     ownerAccountID: attempt.ownerAccountID) else {
            throw ExamHistoryError.missingAttempt
        }
        return history
    }

    /// Answer, position, revision and outbox event share one SQLite transaction.
    @discardableResult
    public static func saveAnswer(_ db: SqlDatabase, attemptID: String, questionID: String,
                                  responseJSON: String, position: Int, savedAt: Double) throws -> Int64 {
        guard !questionID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              position >= 0, let response = object(responseJSON) else {
            throw ExamHistoryError.invalidJSON
        }
        return try db.transaction { tx in
            guard let attempt = try tx.query("SELECT state, revision FROM exam_attempts WHERE attempt_id = ?",
                                             args: [attemptID]).first else { throw ExamHistoryError.missingAttempt }
            guard attempt.text("state") == "in_progress" else { throw ExamHistoryError.completedAttempt }
            let revision = attempt.long("revision") + 1
            _ = try tx.insert("exam_answers", ["attempt_id": attemptID, "question_id": questionID,
                "revision": revision, "response_json": responseJSON, "saved_at": savedAt])
            _ = try tx.update("UPDATE exam_attempts SET position = ?, revision = ?, updated_at = ? " +
                "WHERE attempt_id = ? AND state = 'in_progress' AND revision = ?",
                args: [position, revision, savedAt, attemptID, revision - 1])
            guard try tx.query("SELECT revision FROM exam_attempts WHERE attempt_id = ?",
                               args: [attemptID]).first?.long("revision") == revision else {
                throw ExamHistoryError.concurrentAttemptChange
            }
            try enqueue(tx, attemptID: attemptID, revision: revision, type: "progress",
                        payload: jsonString(["attemptId": attemptID, "questionId": questionID,
                                             "response": response, "position": position]), at: savedAt)
            return revision
        }
    }

    /// The first result wins. A repeated completion with identical bytes is a no-op.
    @discardableResult
    public static func complete(_ db: SqlDatabase, attemptID: String, resultJSON: String,
                                completedAt: Double) throws -> Bool {
        guard let result = object(resultJSON) else { throw ExamHistoryError.invalidJSON }
        return try db.transaction { tx in
            guard let attempt = try tx.query("SELECT state, revision FROM exam_attempts WHERE attempt_id = ?",
                                             args: [attemptID]).first else { throw ExamHistoryError.missingAttempt }
            if attempt.text("state") == "completed" {
                guard let existing = try tx.query("SELECT result_json FROM exam_results WHERE attempt_id = ?",
                                                  args: [attemptID]).first else { throw ExamHistoryError.missingResult }
                guard existing.text("result_json") == resultJSON else { throw ExamHistoryError.immutableResultConflict }
                return false
            }
            let revision = attempt.long("revision") + 1
            _ = try tx.insert("exam_results", ["attempt_id": attemptID,
                "result_json": resultJSON, "recorded_at": completedAt])
            _ = try tx.update("UPDATE exam_attempts SET state = 'completed', revision = ?, updated_at = ?, " +
                "completed_at = ? WHERE attempt_id = ? AND state = 'in_progress' AND revision = ?",
                args: [revision, completedAt, completedAt, attemptID, revision - 1])
            guard let updated = try tx.query("SELECT state, revision FROM exam_attempts WHERE attempt_id = ?",
                                             args: [attemptID]).first,
                  updated.text("state") == "completed", updated.long("revision") == revision else {
                throw ExamHistoryError.concurrentAttemptChange
            }
            try enqueue(tx, attemptID: attemptID, revision: revision, type: "completed",
                        payload: jsonString(["attemptId": attemptID, "result": result]), at: completedAt)
            return true
        }
    }

    public static func load(_ db: SqlDatabase, attemptID: String,
                            ownerAccountID: String? = nil) throws -> ExamHistory? {
        let attempt: SqlRow?
        if let ownerAccountID {
            attempt = try db.query("SELECT * FROM exam_attempts WHERE attempt_id = ? AND owner_account_id = ?",
                                   args: [attemptID, ownerAccountID]).first
        } else {
            attempt = try db.query("SELECT * FROM exam_attempts WHERE attempt_id = ? AND owner_account_id IS NULL",
                                   args: [attemptID]).first
        }
        guard let attempt else {
            return nil
        }
        let snapshot = try db.query("SELECT s.* FROM exam_snapshots s JOIN exam_snapshot_ids i " +
            "ON i.content_id = s.content_id WHERE i.exam_id = ?", args: [attempt.text("exam_id")]).first!
        let answers = try db.query("SELECT a.* FROM exam_answers a JOIN " +
            "(SELECT question_id, MAX(revision) AS revision FROM exam_answers WHERE attempt_id = ? GROUP BY question_id) latest " +
            "ON latest.question_id = a.question_id AND latest.revision = a.revision " +
            "WHERE a.attempt_id = ? ORDER BY a.saved_at, a.question_id", args: [attemptID, attemptID])
        let result = try db.query("SELECT * FROM exam_results WHERE attempt_id = ?", args: [attemptID]).first
        return ExamHistory(attempt: attempt, snapshot: snapshot, answers: answers, result: result)
    }

    private static func enqueue(_ db: SqlDatabase, attemptID: String, revision: Int64,
                                type: String, payload: String, at: Double) throws {
        _ = try db.insert("exam_sync_outbox", ["event_id": UUID().uuidString,
            "attempt_id": attemptID, "revision": revision, "event_type": type,
            "payload_json": payload, "delivery_state": "pending", "created_at": at,
            "acknowledged_at": nil])
    }

    private static func object(_ json: String) -> [String: Any]? {
        guard let data = json.data(using: .utf8), let value = try? JSONSerialization.jsonObject(with: data),
              let object = value as? [String: Any] else { return nil }
        return object
    }

    private static func jsonString(_ value: [String: Any]) -> String {
        let data = try! JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed])
        return String(data: data, encoding: .utf8)!
    }
}
