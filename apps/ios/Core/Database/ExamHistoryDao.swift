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
    case conflictResolutionFailed
    case completedAttempt
    case missingResult
    case immutableResultConflict
    case concurrentAttemptChange
}

/// Transactional local persistence for frozen exam content and attempt history.
public enum ExamHistoryDao {
    /// Lists only the authenticated owner's durable pending events in revision order.
    public static func pendingOutbox(_ db: SqlDatabase, ownerAccountID: String) throws -> [SqlRow] {
        try db.query("SELECT o.* FROM exam_sync_outbox o JOIN exam_attempts a ON a.attempt_id = o.attempt_id " +
            "WHERE a.owner_account_id = ? AND o.delivery_state = 'pending' " +
            "ORDER BY o.created_at, o.attempt_id, o.revision", args: [ownerAccountID])
    }

    /// Acknowledges exactly one server-confirmed event and preserves newer pending edits.
    @discardableResult
    public static func acknowledgeOutbox(_ db: SqlDatabase, ownerAccountID: String, attemptID: String,
                                         eventID: String, revision: Int64, acknowledgedAt: Double) throws -> Bool {
        try db.transaction { tx in
            guard let event = try tx.query("SELECT o.delivery_state FROM exam_sync_outbox o JOIN exam_attempts a " +
                "ON a.attempt_id = o.attempt_id WHERE o.event_id = ? AND o.attempt_id = ? AND o.revision = ? " +
                "AND a.owner_account_id = ?", args: [eventID, attemptID, revision, ownerAccountID]).first else { return false }
            if event.text("delivery_state") != "acknowledged" {
                let changed = try tx.update("UPDATE exam_sync_outbox SET delivery_state = 'acknowledged', acknowledged_at = ? " +
                    "WHERE event_id = ? AND attempt_id = ? AND revision = ? AND delivery_state IN ('pending','in_flight')",
                    args: [acknowledgedAt, eventID, attemptID, revision])
                guard changed == 1 else { return false }
            }
            _ = try tx.update("UPDATE exam_attempts SET sync_state = CASE WHEN EXISTS (" +
                "SELECT 1 FROM exam_sync_outbox WHERE attempt_id = ? AND delivery_state IN ('pending','in_flight')" +
                ") THEN 'pending' ELSE 'synced' END WHERE attempt_id = ? AND owner_account_id = ? AND sync_state != 'conflict'",
                args: [attemptID, attemptID, ownerAccountID])
            return true
        }
    }

    /// Keeps the local branch pending for explicit conflict resolution.
    @discardableResult
    public static func markConflict(_ db: SqlDatabase, ownerAccountID: String, attemptID: String) throws -> Bool {
        try db.transaction { tx in
            try tx.update("UPDATE exam_attempts SET sync_state = 'conflict' WHERE attempt_id = ? " +
                "AND owner_account_id = ?", args: [attemptID, ownerAccountID]) == 1
        }
    }

    /// Persists the confirmed server branch beside an immutable local projection before resolution.
    @discardableResult
    public static func persistConflict(_ db: SqlDatabase, ownerAccountID: String, attemptID: String,
                                       serverAttemptJSON: String, receivedAt: Double) throws -> Bool {
        guard let server = object(serverAttemptJSON), server["attemptId"] as? String == attemptID,
              let revision = server["revision"] as? Int, revision > 0 else { return false }
        return try db.transaction { tx in
            guard let attempt = try tx.query("SELECT * FROM exam_attempts WHERE attempt_id = ? AND owner_account_id = ?",
                                             args: [attemptID, ownerAccountID]).first else { return false }
            let answers = try tx.query("SELECT question_id, revision, response_json FROM exam_answers " +
                "WHERE attempt_id = ? ORDER BY revision, question_id", args: [attemptID]).map { row in
                    ["questionId": row.text("question_id"), "revision": row.long("revision"),
                     "response": (try? JSONSerialization.jsonObject(with: Data(row.text("response_json").utf8))) ?? NSNull()] as [String: Any]
                }
            let result = try tx.query("SELECT result_json FROM exam_results WHERE attempt_id = ?", args: [attemptID]).first
            var localAttempt: [String: Any] = ["attemptId": attemptID, "examId": attempt.text("exam_id"),
                "state": attempt.text("state"), "position": attempt.long("position"),
                "revision": attempt.long("revision"), "startedAt": attempt.real("started_at"),
                "updatedAt": attempt.real("updated_at")]
            if let completedAt = attempt.realOrNull("completed_at") { localAttempt["completedAt"] = completedAt }
            var local: [String: Any] = ["attempt": localAttempt, "answers": answers]
            if let result, let value = object(result.text("result_json")) { local["result"] = value }
            let localJSON = jsonString(local)
            let existing = try tx.query("SELECT 1 AS found FROM exam_sync_conflicts WHERE attempt_id = ? AND owner_account_id = ?",
                                        args: [attemptID, ownerAccountID]).first != nil
            if existing {
                _ = try tx.update("UPDATE exam_sync_conflicts SET server_revision = ?, server_attempt_json = ?, " +
                    "local_attempt_json = ?, received_at = ?, resolution = NULL, resolved_at = NULL " +
                    "WHERE attempt_id = ? AND owner_account_id = ?",
                    args: [revision, serverAttemptJSON, localJSON, receivedAt, attemptID, ownerAccountID])
            } else {
                _ = try tx.insert("exam_sync_conflicts", ["attempt_id": attemptID, "owner_account_id": ownerAccountID,
                    "server_revision": revision, "server_attempt_json": serverAttemptJSON,
                    "local_attempt_json": localJSON, "received_at": receivedAt])
            }
            return try tx.update("UPDATE exam_attempts SET sync_state = 'conflict' WHERE attempt_id = ? " +
                "AND owner_account_id = ?", args: [attemptID, ownerAccountID]) == 1
        }
    }

    public static func conflict(_ db: SqlDatabase, ownerAccountID: String, attemptID: String) throws -> SqlRow? {
        try db.query("SELECT * FROM exam_sync_conflicts WHERE attempt_id = ? AND owner_account_id = ?",
                     args: [attemptID, ownerAccountID]).first
    }

    /// Forks the retained local conflict branch into a new immutable attempt identity.
    @discardableResult
    public static func resolveConflictFork(_ db: SqlDatabase, ownerAccountID: String, attemptID: String,
                                           forkAttemptID: String, forkedAt: Double) throws -> Bool {
        try db.transaction { tx in
        guard !forkAttemptID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              forkAttemptID != attemptID,
              let conflict = try tx.query("SELECT local_attempt_json FROM exam_sync_conflicts WHERE attempt_id = ? " +
                "AND owner_account_id = ? AND resolution IS NULL", args: [attemptID, ownerAccountID]).first,
              let local = object(conflict.text("local_attempt_json")),
              let old = local["attempt"] as? [String: Any],
              let source = try tx.query("SELECT a.*, i.content_id FROM exam_attempts a JOIN exam_snapshot_ids i " +
                "ON i.exam_id = a.exam_id WHERE a.attempt_id = ? AND a.owner_account_id = ?",
                args: [attemptID, ownerAccountID]).first,
              let snapshot = try tx.query("SELECT * FROM exam_snapshots WHERE content_id = ?",
                args: [source.text("content_id")]).first,
              let state = old["state"] as? String, ["in_progress", "completed"].contains(state),
              let position = (old["position"] as? NSNumber)?.intValue else { return false }
        _ = try tx.update("UPDATE exam_sync_outbox SET delivery_state = 'superseded' WHERE attempt_id = ? " +
            "AND delivery_state IN ('pending','in_flight')", args: [attemptID])
        let completedAt = (old["completedAt"] as? NSNumber)?.doubleValue
        let result = local["result"] as? [String: Any]
        if state == "completed" && (completedAt == nil || result == nil) { return false }
        let answers = local["answers"] as? [[String: Any]] ?? []
        var latest: [String: [String: Any]] = [:]
        for answer in answers {
            guard let question = answer["questionId"] as? String, answer["response"] != nil else { return false }
            let revision = (answer["revision"] as? NSNumber)?.int64Value ?? 0
            if revision > ((latest[question]?["revision"] as? NSNumber)?.int64Value ?? -1) { latest[question] = answer }
        }
        let prepared = latest.compactMap { question, answer -> (String, String)? in
            guard let response = answer["response"] as? [String: Any] else { return nil }
            return (question, jsonString(response))
        }
        guard prepared.count == latest.count, let content = object(snapshot.text("content_json")) else { return false }
        let inserted = try tx.insert("exam_attempts", ["attempt_id": forkAttemptID,
            "exam_id": source.text("exam_id"), "owner_account_id": ownerAccountID, "state": state,
            "sync_state": "pending", "position": position, "revision": 1,
            "started_at": source.real("started_at"), "updated_at": forkedAt, "completed_at": completedAt])
        guard inserted != -1 else { return false }
        for (question, responseJSON) in prepared {
            _ = try tx.insert("exam_answers", ["attempt_id": forkAttemptID, "question_id": question,
                "revision": 1, "response_json": responseJSON,
                "saved_at": forkedAt])
        }
        if let result {
            _ = try tx.insert("exam_results", ["attempt_id": forkAttemptID, "result_json": jsonString(result),
                "recorded_at": completedAt ?? forkedAt])
        }
        let fullSnapshot = content.merging(["examId": source.text("exam_id"), "contentId": snapshot.text("content_id"),
            "contentHash": snapshot.text("content_hash"), "schemaVersion": snapshot.int("schema_version"),
            "contentRevision": snapshot.int("content_revision"), "gradingVersion": snapshot.int("grading_version"),
            "source": snapshot.text("source"), "languageID": snapshot.text("language_id"),
            "level": snapshot.text("level"), "weekId": snapshot.long("week_id")]) { _, new in new }
        try Self.enqueue(tx, attemptID: forkAttemptID, revision: 1, type: "created",
            payload: jsonString(["attemptId": forkAttemptID, "examId": source.text("exam_id"), "snapshot": fullSnapshot]), at: forkedAt)
        guard try tx.update("UPDATE exam_sync_conflicts SET resolution = 'fork', resolved_at = ? WHERE attempt_id = ? " +
            "AND owner_account_id = ? AND resolution IS NULL", args: [forkedAt, attemptID, ownerAccountID]) == 1 else {
            throw ExamHistoryError.conflictResolutionFailed
        }
        return true
        }
    }

    /// Adopts the confirmed server branch only after an explicit learner choice.
    @discardableResult
    public static func resolveConflictTakeover(_ db: SqlDatabase, ownerAccountID: String,
                                               attemptID: String, resolvedAt: Double) throws -> Bool {
        try db.transaction { tx in
            guard let conflict = try tx.query("SELECT server_attempt_json FROM exam_sync_conflicts " +
                "WHERE attempt_id = ? AND owner_account_id = ? AND resolution IS NULL",
                args: [attemptID, ownerAccountID]).first,
                  let server = object(conflict.text("server_attempt_json")),
                  server["attemptId"] as? String == attemptID,
                  let examID = server["examId"] as? String,
                  let revision = (server["revision"] as? NSNumber)?.int64Value, revision > 0,
                  let position = (server["position"] as? NSNumber)?.intValue, position >= 0,
                  let state = server["state"] as? String, ["in_progress", "completed"].contains(state),
                  let startedAt = parseISO(server["startedAt"] as? String),
                  let updatedAt = parseISO(server["updatedAt"] as? String),
                  let answers = server["answers"] as? [[String: Any]] else { return false }
            guard try tx.query("SELECT exam_id FROM exam_attempts WHERE attempt_id = ? AND owner_account_id = ?",
                               args: [attemptID, ownerAccountID]).first?.text("exam_id") == examID else { return false }
            let prepared = answers.compactMap { answer -> (String, Int64, String)? in
                guard let question = answer["questionId"] as? String,
                      let answerRevision = (answer["revision"] as? NSNumber)?.int64Value,
                      let response = answer["response"],
                      let data = try? JSONSerialization.data(withJSONObject: response, options: [.sortedKeys, .fragmentsAllowed]),
                      let responseJSON = String(data: data, encoding: .utf8) else { return nil }
                return (question, answerRevision, responseJSON)
            }
            guard prepared.count == answers.count else { return false }
            let completedAt = parseISO(server["completedAt"] as? String)
            if state == "completed" && (completedAt == nil || !(server["result"] is [String: Any])) { return false }
            _ = try tx.update("UPDATE exam_sync_outbox SET delivery_state = 'superseded' WHERE attempt_id = ? " +
                "AND delivery_state IN ('pending','in_flight')", args: [attemptID])
            _ = try tx.update("DELETE FROM exam_answers WHERE attempt_id = ?", args: [attemptID])
            _ = try tx.update("DELETE FROM exam_results WHERE attempt_id = ?", args: [attemptID])
            for answer in prepared {
                _ = try tx.insert("exam_answers", ["attempt_id": attemptID, "question_id": answer.0,
                    "revision": answer.1, "response_json": answer.2, "saved_at": updatedAt])
            }
            if let result = server["result"] as? [String: Any] {
                _ = try tx.insert("exam_results", ["attempt_id": attemptID, "result_json": jsonString(result),
                    "recorded_at": completedAt ?? updatedAt])
            }
            let syncState = state == "completed" ? "synced" : "pending"
            let localRevision = state == "completed" ? revision : revision + 1
            let attemptUpdate = completedAt == nil
                ? "UPDATE exam_attempts SET state = ?, sync_state = ?, position = ?, revision = ?, " +
                    "started_at = ?, updated_at = ?, completed_at = NULL WHERE attempt_id = ? AND owner_account_id = ?"
                : "UPDATE exam_attempts SET state = ?, sync_state = ?, position = ?, revision = ?, " +
                    "started_at = ?, updated_at = ?, completed_at = ? WHERE attempt_id = ? AND owner_account_id = ?"
            let attemptArgs: [Any] = completedAt.map {
                [state, syncState, position, localRevision, startedAt, updatedAt, $0, attemptID, ownerAccountID]
            } ?? [state, syncState, position, localRevision, startedAt, updatedAt, attemptID, ownerAccountID]
            guard try tx.update(attemptUpdate, args: attemptArgs) == 1 else {
                return false
            }
            if state == "in_progress" {
                let eventAt = max(resolvedAt, updatedAt)
                try enqueue(tx, attemptID: attemptID, revision: revision + 1, type: "takeover",
                            payload: jsonString(["updatedAt": isoTimestamp(eventAt)]), at: eventAt)
            }
            return try tx.update("UPDATE exam_sync_conflicts SET resolution = 'takeover', resolved_at = ? " +
                "WHERE attempt_id = ? AND owner_account_id = ? AND resolution IS NULL",
                args: [resolvedAt, attemptID, ownerAccountID]) == 1
        }
    }

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
            _ = try tx.update("UPDATE exam_attempts SET position = ?, revision = ?, updated_at = ?, " +
                "sync_state = CASE WHEN owner_account_id IS NULL THEN 'local_only' ELSE 'pending' END " +
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
                "completed_at = ?, sync_state = CASE WHEN owner_account_id IS NULL THEN 'local_only' ELSE 'pending' END " +
                "WHERE attempt_id = ? AND state = 'in_progress' AND revision = ?",
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

    private static func parseISO(_ value: String?) -> Double? {
        guard let value else { return nil }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: value)?.timeIntervalSince1970
    }

    private static func isoTimestamp(_ seconds: Double) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date(timeIntervalSince1970: seconds))
    }
}
