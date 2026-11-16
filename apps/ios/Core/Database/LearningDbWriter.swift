import Foundation
import CryptoKit

/**
 * The DB mirror of every LearningStore mutation site (the phase-E cutover).
 * Each function persists ONLY what one site mutated, and each is the exact
 * DAO call sequence that keeps the consistency invariant
 *
 *     ArchiveMapper.export(db) == the in-memory archive the engines produced
 *
 * for that flow — pinned per flow by LearningCutoverFlowTests, which replays
 * the archive mutation with the real engines and then calls THESE functions
 * in the same order the site does. The Swift mirror of the Kotlin
 * `LearningDbWriter`; the site mapping table lives in the phase-E task-2
 * report.
 *
 * Conventions:
 *  - Every function opens its own transaction where it writes more than one
 *    statement (transactions do not nest — pass a tx handle around, never
 *    wrap these calls in another transaction).
 *  - Rowid order must equal archive list order for every content table, so
 *    re-sorting mutations (completions by `at`, skill errors by count)
 *    rewrite position, not just content. The iOS store never reorders
 *    `archive.sessions` (save replaces in place), so `session`'s
 *    `moveToEnd` stays false on every real site — the parameter and its
 *    delete-and-reinsert branch are kept for writer parity with the Kotlin
 *    store, whose save appends the updated session at the end.
 *  - The retry ledger's durable form is `AssessmentTicketRow` —
 *    passageID/revisionKey are runtime-only (schema v1). iOS keeps no
 *    runtime retry ledger (its FinalAssessmentQueue is runtime-only), so the
 *    only ledger writers today are the startup recovery and the delete
 *    flows; the parameters stay for parity.
 */
public enum LearningDbWriter {
    public static func cacheDailyAttempt(_ db: SqlDatabase, owner: String, draft: DailyPracticeDraft,
                                         now: Double = Date.now.timeIntervalSinceReferenceDate) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let packageData = try encoder.encode(draft.package)
        let stateData = try encoder.encode(draft)
        guard let package = String(data: packageData, encoding: .utf8),
              let state = String(data: stateData, encoding: .utf8) else {
            throw SqlDatabaseError.unsupportedValue("daily_attempt_encoding")
        }
        try db.transaction { tx in
            try persistDailyAttempt(tx, owner: owner, draft: draft, package: package, state: state, now: now)
        }
    }

    public static func acknowledgeDailyEvent(_ db: SqlDatabase, owner: String, eventID: String,
                                             draft: DailyPracticeDraft, expectedRevision: Int,
                                             acceptedResponse: Data,
                                             now: Double = Date.now.timeIntervalSinceReferenceDate) throws {
        guard draft.owner == owner, UUID(uuidString: eventID) != nil,
              let response = String(data: acceptedResponse, encoding: .utf8),
              (try? JSONSerialization.jsonObject(with: acceptedResponse)) != nil else {
            throw SqlDatabaseError.unsupportedValue("daily_event_ack_invalid")
        }
        let acceptedDraft = draft.recordingAcceptedServerResponse(acceptedResponse)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let packageData = try encoder.encode(acceptedDraft.package)
        let stateData = try encoder.encode(acceptedDraft)
        guard let package = String(data: packageData, encoding: .utf8),
              let state = String(data: stateData, encoding: .utf8), !response.isEmpty else {
            throw SqlDatabaseError.unsupportedValue("daily_attempt_encoding")
        }
        try db.transaction { tx in
            guard try LearnerOwnershipDao.activeIdentity(tx) == owner else {
                throw SqlDatabaseError.unsupportedValue("daily_event_owner_changed")
            }
            let changed = try tx.update("UPDATE daily_outbox SET delivery_state = 'acknowledged' WHERE owner_key = ? AND event_id = ? AND attempt_id = ? AND expected_revision = ? AND delivery_state IN ('sending','outcome_unknown')",
                                        [owner, eventID, draft.attemptID, expectedRevision])
            guard changed == 1 else { throw SqlDatabaseError.unsupportedValue("daily_event_ack_missing") }
            try persistDailyAttempt(tx, owner: owner, draft: acceptedDraft, package: package, state: state, now: now)
        }
    }

    private static func persistDailyAttempt(_ tx: SqlDatabase, owner: String, draft: DailyPracticeDraft,
                                            package: String, state: String, now: Double) throws {
        guard try LearnerOwnershipDao.activeIdentity(tx) == owner, draft.owner == owner else {
            throw SqlDatabaseError.unsupportedValue("daily_attempt_owner_changed")
        }
        if let row = try tx.query("SELECT revision FROM daily_attempt_cache WHERE owner_key = ? AND attempt_id = ? LIMIT 1",
                                  [owner, draft.attemptID]).first,
           row.int("revision") > draft.revision {
            throw SqlDatabaseError.unsupportedValue("daily_attempt_revision_regressed")
        }
        _ = try tx.insert("daily_attempt_cache", ["owner_key": owner, "attempt_id": draft.attemptID,
            "package_snapshot": package, "state_json": state, "revision": draft.revision, "updated_at": now],
            strategy: .replace)
    }

    public static func cachedDailyAttempts(_ db: SqlDatabase, owner: String) throws -> [DailyPracticeDraft] {
        guard try LearnerOwnershipDao.activeIdentity(db) == owner else { return [] }
        return try db.query("SELECT state_json FROM daily_attempt_cache WHERE owner_key = ? ORDER BY updated_at DESC",
                            [owner]).compactMap { row in
            guard let data = row.textOrNull("state_json")?.data(using: .utf8) else { return nil }
            return try? JSONDecoder().decode(DailyPracticeDraft.self, from: data)
        }
    }

    public static func adoptDailyAttempt(_ db: SqlDatabase, owner: String, provisional: DailyPracticeDraft,
                                         adopted: DailyPracticeDraft,
                                         now: Double = Date.now.timeIntervalSinceReferenceDate) throws {
        guard provisional.owner == owner, !provisional.remoteConfirmed,
              adopted.owner == owner, adopted.remoteConfirmed,
              provisional.attemptID != adopted.attemptID else {
            throw SqlDatabaseError.unsupportedValue("daily_attempt_adoption_invalid")
        }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        guard let package = String(data: try encoder.encode(adopted.package), encoding: .utf8),
              let state = String(data: try encoder.encode(adopted), encoding: .utf8) else {
            throw SqlDatabaseError.unsupportedValue("daily_attempt_encoding")
        }
        try db.transaction { tx in
            guard try LearnerOwnershipDao.activeIdentity(tx) == owner,
                  (try tx.query("SELECT 1 AS found FROM daily_outbox WHERE owner_key = ? AND attempt_id = ? LIMIT 1",
                                [owner, provisional.attemptID])).isEmpty else {
                throw SqlDatabaseError.unsupportedValue("daily_attempt_adoption_has_events")
            }
            try persistDailyAttempt(tx, owner: owner, draft: adopted, package: package, state: state, now: now)
            _ = try tx.update("DELETE FROM daily_attempt_cache WHERE owner_key = ? AND attempt_id = ?",
                              [owner, provisional.attemptID])
        }
    }

    @discardableResult
    public static func enqueueDailyEvent(_ db: SqlDatabase, owner: String, attemptID: String,
                                         envelope: Data, now: Double = Date.now.timeIntervalSinceReferenceDate) throws -> String {
        guard envelope.count <= 24 * 1024,
              let json = try JSONSerialization.jsonObject(with: envelope) as? [String: Any],
              let eventID = json["eventId"] as? String, UUID(uuidString: eventID) != nil,
              json["schemaVersion"] as? Int == 1,
              let revision = json["expectedRevision"] as? Int, revision >= 0,
              UUID(uuidString: attemptID) != nil else {
            throw SqlDatabaseError.unsupportedValue("daily_event_invalid")
        }
        let intent = String(data: envelope, encoding: .utf8) ?? ""
        guard let stableIntent = stableDailyIntentData(json) else {
            throw SqlDatabaseError.unsupportedValue("daily_event_invalid")
        }
        let hash = SHA256.hash(data: stableIntent).map { String(format: "%02x", $0) }.joined()
        try db.transaction { tx in
            guard try LearnerOwnershipDao.activeIdentity(tx) == owner else {
                throw SqlDatabaseError.unsupportedValue("daily_event_owner_changed")
            }
            if let row = try tx.query("SELECT intent_json,intent_hash FROM daily_outbox WHERE owner_key = ? AND event_id = ? LIMIT 1",
                                      [owner, eventID]).first {
                guard let oldData = row.text("intent_json").data(using: .utf8),
                      let oldJSON = try? JSONSerialization.jsonObject(with: oldData) as? [String: Any],
                      let oldIntent = stableDailyIntentData(oldJSON), oldIntent == stableIntent else {
                    throw SqlDatabaseError.unsupportedValue("daily_event_id_conflict")
                }
                return eventID
            }
            guard let attempt = try tx.query("SELECT revision FROM daily_attempt_cache WHERE owner_key = ? AND attempt_id = ? LIMIT 1",
                                             [owner, attemptID]).first else {
                throw SqlDatabaseError.unsupportedValue("daily_attempt_missing")
            }
            let nextSequence = (try tx.query("SELECT COALESCE(MAX(sequence), -1) + 1 AS next FROM daily_outbox WHERE owner_key = ? AND attempt_id = ?",
                                             [owner, attemptID]).first?.int("next")) ?? 0
            _ = try tx.insert("daily_outbox", ["owner_key": owner, "event_id": eventID, "attempt_id": attemptID,
                "intent_json": intent, "intent_hash": hash, "expected_revision": revision,
                "sequence": nextSequence, "delivery_state": "pending", "created_at": now])
            _ = attempt // The attempt row check binds this outbox event to a locally owned attempt.
            return eventID
        }
    }

    public static func pendingDailyEvents(_ db: SqlDatabase, owner: String, attemptID: String) throws -> [SqlRow] {
        guard try LearnerOwnershipDao.activeIdentity(db) == owner else { return [] }
        return try db.query("SELECT event_id,intent_json,delivery_state,expected_revision,sequence FROM daily_outbox WHERE owner_key = ? AND attempt_id = ? AND delivery_state IN ('pending','sending','outcome_unknown') ORDER BY sequence",
                            [owner, attemptID])
    }

    public static func rebaseDailyEvent(_ db: SqlDatabase, owner: String, eventID: String, attemptID: String,
                                        from expectedRevision: Int, acceptedDraft: DailyPracticeDraft,
                                        now: Double = Date.now.timeIntervalSinceReferenceDate) throws -> Data {
        guard acceptedDraft.owner == owner, acceptedDraft.attemptID == attemptID,
              acceptedDraft.serverRevision > expectedRevision,
              (acceptedDraft.rebasedEventIDs ?? []).contains(eventID), UUID(uuidString: eventID) != nil else {
            throw SqlDatabaseError.unsupportedValue("daily_event_rebase_invalid")
        }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let package = try encoder.encode(acceptedDraft.package)
        let state = try encoder.encode(acceptedDraft)
        guard let packageJSON = String(data: package, encoding: .utf8),
              let stateJSON = String(data: state, encoding: .utf8) else {
            throw SqlDatabaseError.unsupportedValue("daily_attempt_encoding")
        }
        var result = Data()
        try db.transaction { tx in
            guard try LearnerOwnershipDao.activeIdentity(tx) == owner,
                  let row = try tx.query("SELECT intent_json,expected_revision FROM daily_outbox WHERE owner_key = ? AND event_id = ? AND attempt_id = ? AND delivery_state = 'revision_conflict' LIMIT 1",
                                         [owner, eventID, attemptID]).first,
                  row.int("expected_revision") == expectedRevision,
                  let oldData = row.text("intent_json").data(using: .utf8),
                  var json = try JSONSerialization.jsonObject(with: oldData) as? [String: Any],
                  json["eventId"] as? String == eventID else {
                throw SqlDatabaseError.unsupportedValue("daily_event_rebase_missing")
            }
            json["expectedRevision"] = acceptedDraft.serverRevision
            let rebased = try JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])
            guard rebased.count <= 24 * 1024, let stableIntent = stableDailyIntentData(json) else {
                throw SqlDatabaseError.unsupportedValue("daily_event_invalid")
            }
            let hash = SHA256.hash(data: stableIntent).map { String(format: "%02x", $0) }.joined()
            let changed = try tx.update("UPDATE daily_outbox SET intent_json = ?, intent_hash = ?, expected_revision = ?, delivery_state = 'pending' WHERE owner_key = ? AND event_id = ? AND attempt_id = ? AND expected_revision = ? AND delivery_state = 'revision_conflict'",
                                        [String(data: rebased, encoding: .utf8), hash, acceptedDraft.serverRevision,
                                         owner, eventID, attemptID, expectedRevision])
            guard changed == 1 else { throw SqlDatabaseError.unsupportedValue("daily_event_rebase_raced") }
            try persistDailyAttempt(tx, owner: owner, draft: acceptedDraft,
                package: packageJSON, state: stateJSON, now: now)
            result = rebased
        }
        return result
    }

    public static func dailyAttemptHasConflict(_ db: SqlDatabase, owner: String, attemptID: String) throws -> Bool {
        guard try LearnerOwnershipDao.activeIdentity(db) == owner else { return false }
        return !(try db.query("SELECT 1 AS found FROM daily_outbox WHERE owner_key = ? AND attempt_id = ? AND delivery_state = 'revision_conflict' LIMIT 1",
                              [owner, attemptID])).isEmpty
    }

    public static func markDailyEvent(_ db: SqlDatabase, owner: String, eventID: String, state: String) throws {
        guard ["sending", "outcome_unknown", "revision_conflict", "acknowledged"].contains(state) else {
            throw SqlDatabaseError.unsupportedValue("daily_event_state_invalid")
        }
        try db.transaction { tx in
            guard try LearnerOwnershipDao.activeIdentity(tx) == owner else {
                throw SqlDatabaseError.unsupportedValue("daily_event_owner_changed")
            }
            _ = try tx.update("UPDATE daily_outbox SET delivery_state = ? WHERE owner_key = ? AND event_id = ?",
                              [state, owner, eventID])
        }
    }

    private static func stableDailyIntentData(_ json: [String: Any]) -> Data? {
        var stable = json
        stable.removeValue(forKey: "expectedRevision")
        return try? JSONSerialization.data(withJSONObject: stable, options: [.sortedKeys])
    }

    public static func cacheDailyReview(_ db: SqlDatabase, owner: String, draft: DailyPracticeDraft,
                                        review: DailyReviewResult,
                                        now: Double = Date.now.timeIntervalSinceReferenceDate) throws {
        let data = try JSONEncoder().encode(review)
        guard let json = String(data: data, encoding: .utf8), UUID(uuidString: review.reviewId) != nil,
              review.attemptId == draft.attemptID, review.inputHash == draft.inputHash else {
            throw SqlDatabaseError.unsupportedValue("daily_review_invalid")
        }
        try db.transaction { tx in
            guard try LearnerOwnershipDao.activeIdentity(tx) == owner, owner == draft.owner else {
                throw SqlDatabaseError.unsupportedValue("daily_review_owner_changed")
            }
            _ = try tx.insert("daily_review_cache", ["owner_key": owner, "review_id": review.reviewId,
                "attempt_id": draft.attemptID, "result_json": json, "created_at": now], strategy: .replace)
        }
    }

    public static func cachedDailyReview(_ db: SqlDatabase, owner: String, attemptID: String) throws -> DailyReviewResult? {
        guard try LearnerOwnershipDao.activeIdentity(db) == owner else { return nil }
        guard let json = try db.query("SELECT result_json FROM daily_review_cache WHERE owner_key = ? AND attempt_id = ? LIMIT 1",
                                     [owner, attemptID]).first?.textOrNull("result_json"),
              let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(DailyReviewResult.self, from: data)
    }


    /// save()/correctPassage(): one session's durable form — the session row
    /// (scalar columns plus the four write-once JSON payloads) and its whole
    /// transcript rewrite, in one transaction.
    ///
    /// The iOS save site replaces the session in `archive.sessions` IN PLACE
    /// (or appends a new one at the end), so an in-place upsert keeps the
    /// rowid position equal to the archive list position and `moveToEnd` is
    /// always false. When a resumed older conversation were continued from
    /// the middle of the list, `moveToEnd` deletes the row (fragments follow
    /// by ON DELETE CASCADE) and reinserts it at the end, inside the same
    /// transaction as the fragment rewrite — the mirror of the Kotlin site.
    ///
    /// `ticketChanged`/`ticket` mirror the Kotlin site's retry-ledger diff:
    /// null with `ticketChanged` = true deletes the session's row, non-null
    /// upserts the ledger's durable fields; false skips the ledger entirely.
    public static func session(_ db: SqlDatabase, _ record: SessionRecord, moveToEnd: Bool,
                               ticketChanged: Bool = false, ticket: AssessmentTicketRow? = nil) throws {
        try db.transaction { tx in
            if moveToEnd { try SessionsDao.delete(tx, record.id.uuidString) }
            try SessionsDao.upsert(tx, record)
            try FragmentsDao.replaceInTx(tx, record.id.uuidString, record.fragments)
            if ticketChanged {
                if let ticket {
                    try AssessmentTicketsDao.upsert(tx, ticket)
                } else {
                    try AssessmentTicketsDao.delete(tx, record.id.uuidString)
                }
            }
        }
    }

    /// recordCompletion() and the completions side of lesson attempts: the
    /// archive's whole (re-sorted, truncated) ledger, in list order.
    public static func completions(_ db: SqlDatabase, _ marks: [CompletionMark]) throws {
        try db.transaction { tx in
            try CompletionsDao.replaceAllInTx(tx, marks,
                evidence: TimestampEvidence("ios.recordCompletion", 2, "apple_reference_seconds"))
        }
    }

    /// recordExerciseMistake(): the archive's whole (re-sorted, truncated)
    /// error memory, in list order.
    public static func skillErrors(_ db: SqlDatabase, _ errors: [SkillError]) throws {
        try db.transaction { tx in try SkillErrorsDao.replaceAllInTx(tx, errors) }
    }

    /// recordLessonAttempt(): the lesson's counters, the ledger mark (passed
    /// as the whole post-sort list for `completions`' reason), and the
    /// expressions RecallEngine.seedFromLesson left in the archive — one
    /// ExpressionsDao.upsert per affected entry, in seeding order, so
    /// adoption (legacy NULL row retargeted) and appends land identically.
    public static func lessonAttempt(_ db: SqlDatabase, lesson: LessonState, marks: [CompletionMark],
                                     seeds: [ExpressionState]) throws {
        try db.transaction { tx in
            let evidence = TimestampEvidence("ios.recordLessonAttempt", 3, "apple_reference_seconds")
            try LessonStateDao.upsert(tx, lesson, evidence: evidence)
            try CompletionsDao.replaceAllInTx(tx, marks, evidence: evidence)
            for expression in seeds {
                _ = try ExpressionsDao.upsert(tx, expression)
            }
        }
    }

    /// saveLookupExpression()/gradeRecall(): the post-engine state of the one
    /// affected expression. adoptLegacy stays on: when the engine adopted a
    /// legacy NULL row, the incoming state IS that adopted row, so the DAO's
    /// engine-faithful adoption reproduces it (its fallbacks read the same
    /// legacy values the engine kept, and its meaning fill falls back to the
    /// incoming meaning, which is the engine's own post-state).
    public static func expressionUpserted(_ db: SqlDatabase, _ state: ExpressionState) throws {
        _ = try ExpressionsDao.upsert(db, state)
    }

    /// removeExpression(): RecallEngine.remove drops the language row AND the
    /// legacy NULL row for the text; the mirror deletes both.
    public static func expressionRemoved(_ db: SqlDatabase, _ text: String, _ languageID: String) throws {
        try db.transaction { tx in
            let owner = try LearnerOwnershipDao.activeIdentity(tx)
            let key = RecallEngine.normalizeKey(text)
            if let owner, !(try tx.query("SELECT 1 AS found FROM daily_word_imports WHERE owner_key = ? AND language_id = ? AND expression_key = ? LIMIT 1",
                                         [owner, languageID, key])).isEmpty {
                _ = try tx.insert("daily_word_suppressions", ["owner_key": owner, "language_id": languageID,
                    "norm_key": key, "deleted_at": Date.now.timeIntervalSinceReferenceDate], strategy: .ignore)
            }
            _ = try ExpressionsDao.removeVisible(tx, text, languageID)
        }
    }

    public static func dailyWordsImported(_ db: SqlDatabase, owner: String, plans: [DailyWordImportPlan],
                                          clearSuppressions: Set<String> = []) throws -> [String: String] {
        try db.transaction { tx in
            guard try LearnerOwnershipDao.activeIdentity(tx) == owner else {
                throw SqlDatabaseError.unsupportedValue("daily_import_owner_changed")
            }
            for key in clearSuppressions {
                guard let separator = key.firstIndex(of: ":") else { continue }
                let language = String(key[..<separator])
                let normKey = String(key[key.index(after: separator)...])
                _ = try tx.update("DELETE FROM daily_word_suppressions WHERE owner_key = ? AND language_id = ? AND norm_key = ?",
                                  [owner, language, normKey])
            }
            var actions: [String: String] = [:]
            for plan in plans {
                guard plan.owner == owner else { throw SqlDatabaseError.unsupportedValue("daily_import_owner_changed") }
                var action: String
                let suppressed = !(try tx.query("SELECT 1 AS found FROM daily_word_suppressions WHERE owner_key = ? AND language_id = ? AND norm_key = ? LIMIT 1",
                                                [owner, plan.languageID, plan.normKey])).isEmpty
                let receipt = !(try tx.query("SELECT 1 AS found FROM daily_word_imports WHERE owner_key = ? AND origin_key = ? AND action IN ('imported','existing','suppressed') LIMIT 1",
                                             [owner, plan.originKey])).isEmpty
                let expressions = try ExpressionsDao.all(tx)
                let current = expressions.first {
                    RecallEngine.normalizeKey($0.text) == plan.normKey && $0.languageID == plan.languageID
                } ?? expressions.first {
                    RecallEngine.normalizeKey($0.text) == plan.normKey && $0.languageID == nil
                }
                if suppressed {
                    action = "suppressed"
                } else if current != nil || receipt || plan.action == .keep {
                    if let current, current.meaning.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || current.languageID == nil {
                        let updated = ExpressionState(text: current.text,
                            meaning: current.meaning.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                ? plan.meaning : current.meaning,
                            source: current.source, lessonID: current.lessonID, bars: current.bars,
                            attempts: current.attempts, lastSeen: current.lastSeen, dueAt: current.dueAt,
                            languageID: plan.languageID)
                        action = try ExpressionsDao.upsert(tx, updated, adoptLegacy: true) ? "existing" : "pending_capacity"
                    } else {
                        action = "existing"
                    }
                } else if plan.action == .insert {
                    let saved = try ExpressionsDao.upsert(tx, ExpressionState(text: plan.text, meaning: plan.meaning,
                        source: "lookup", dueAt: plan.now, languageID: plan.languageID), adoptLegacy: true)
                    action = saved ? "imported" : "pending_capacity"
                } else {
                    switch plan.action {
                    case .insert: action = "imported"
                    case .keep: action = "existing"
                    case .suppress: action = "suppressed"
                    case .pendingCapacity: action = "pending_capacity"
                    }
                }
                if action != "pending_capacity" {
                    _ = try tx.update("DELETE FROM daily_word_imports WHERE owner_key = ? AND origin_key = ? AND action = 'pending_capacity'",
                                      [owner, plan.originKey])
                }
                let originParts = plan.originKey.split(separator: ":", omittingEmptySubsequences: false)
                let reviewID = originParts.count >= 3 && originParts.first == "review" ? String(originParts[1]) : nil
                _ = try tx.insert("daily_word_imports", ["owner_key": owner, "origin_key": plan.originKey,
                    "language_id": plan.languageID, "expression_key": plan.normKey, "kind": plan.kind,
                    "review_id": reviewID,
                    "action": action, "importer_version": 1, "created_at": plan.now], strategy: .replace)
                actions[plan.originKey] = action
            }
            return actions
        }
    }

    /// selectLanguage()/updatePreferences()/hideWord(): the whole Preferences
    /// (one row).
    public static func preferences(_ db: SqlDatabase, _ preferences: Preferences) throws {
        try PreferencesDao.put(db, preferences)
    }

    /// deleteSession(): the DELETE (fragments follow by FK cascade) plus the
    /// session's retry-ledger row when the ledger held one. The iOS site
    /// keeps no runtime ledger, so it passes `ticketRemoved` = true and the
    /// delete of an absent row is the no-op that cleans a recovered
    /// session's ticket with its session — the same outcome as the Kotlin
    /// site's ledger filter.
    public static func sessionDeleted(_ db: SqlDatabase, _ id: String, ticketRemoved: Bool) throws {
        try db.transaction { tx in
            try SessionsDao.delete(tx, id)
            if ticketRemoved { try AssessmentTicketsDao.delete(tx, id) }
        }
    }

    /// deleteAll(): the site wipes sessions (FK cascade takes the fragments),
    /// the retry ledger, and the hidden-words preference — lessons,
    /// expressions, completions and skill errors are NOT learning data the
    /// site touches, faithful to the previous full-archive write.
    public static func learningDataDeleted(_ db: SqlDatabase, _ preferences: Preferences) throws {
        try db.transaction { tx in
            _ = try tx.update("DELETE FROM exam_attempts WHERE \(ownerClause(tx))", args: ownerArguments(tx))
            _ = try tx.update("DELETE FROM sessions WHERE \(ownerClause(tx))", args: ownerArguments(tx))
            try AssessmentTicketsDao.clear(tx)
            try PreferencesDao.put(tx, preferences)
            try revokeActiveMemberBootstrap(tx)
        }
    }

    /// syncProgress()'s remote pull: the lessons ProgressSync.mergeRemote
    /// changed or appended, plus the marks it appended (append-only — no
    /// re-sort — so plain inserts keep rowid order equal to archive order;
    /// add() is insert-or-ignore on the ledger's primary key).
    public static func progressMerged(_ db: SqlDatabase, lessons: [LessonState], marks: [CompletionMark]) throws {
        try db.transaction { tx in
            for lesson in lessons {
                try LessonStateDao.upsert(tx, lesson)
            }
            for mark in marks {
                _ = try CompletionsDao.add(tx, mark)
            }
        }
    }

    /// Applies one validated remote page and its resume cursor atomically.
    public static func remoteProgressPageApplied(_ db: SqlDatabase, ownerAccountID: String,
                                                 rollups: [RemoteProgressRollup], nextCursor: String?,
                                                 passGeneration: Int64, updatedAtMilliseconds: Int64) throws {
        guard rollups.allSatisfy({ $0.ownerAccountID == ownerAccountID }) else {
            throw SqlDatabaseError.unsupportedValue("remote progress page owner mismatch")
        }
        try db.transaction { tx in
            for rollup in rollups { try RemoteProgressRollupsDao.merge(tx, rollup) }
            try ProgressSyncStateDao.put(tx, ProgressSyncState(ownerAccountID: ownerAccountID,
                direction: "download", cursor: nextCursor, passGeneration: passGeneration,
                updatedAtMilliseconds: updatedAtMilliseconds))
        }
    }

    /// Upload acknowledgements advance only upload resume state, never rollup data.
    public static func remoteProgressUploadAcknowledged(_ db: SqlDatabase, ownerAccountID: String,
                                                        nextCursor: String?, passGeneration: Int64,
                                                        updatedAtMilliseconds: Int64) throws {
        try db.transaction { tx in
            try ProgressSyncStateDao.put(tx, ProgressSyncState(ownerAccountID: ownerAccountID,
                direction: "upload", cursor: nextCursor, passGeneration: passGeneration,
                updatedAtMilliseconds: updatedAtMilliseconds))
        }
    }

    /// Recovery bookkeeping is versioned and written without rewriting legacy source rows.
    public static func progressRecoveryRecorded(_ db: SqlDatabase, _ record: ProgressRecoveryRecord) throws {
        try db.transaction { tx in try ProgressRecoveryDao.record(tx, record) }
    }

    /// Runs timestamp classification transactionally before callers materialize sync state.
    public static func progressRecoveryRun(_ db: SqlDatabase, nowUnixMilliseconds: Int64) throws {
        try ProgressRecoveryDao.recover(db, nowUnixMilliseconds: nowUnixMilliseconds)
    }

    /// importData() (DB restore path): the ArchiveMerger.apply mirror, kept
    /// here so the site mapping table names one place per flow. The merger
    /// merges the incoming archive against the DATABASE's projection (not
    /// the working copy — the invariant makes them equal) inside ONE
    /// transaction that rewrites every content table, then returns the
    /// persisted projection, which becomes the working copy. Restore-only:
    /// never call per-turn at runtime.
    public static func archiveRestored(_ db: SqlDatabase, _ incoming: Archive) throws -> Archive {
        try db.transaction { tx in
            let current = try ArchiveMapper.export(tx)
            let merged = try current.merging(incoming)
            try rewriteInTx(tx, merged)
            try revokeActiveMemberBootstrap(tx)
            return try ArchiveMapper.export(tx)
        }
    }

    private static func revokeActiveMemberBootstrap(_ db: SqlDatabase) throws {
        guard let identity = try LearnerOwnershipDao.activeIdentity(db),
              identity.hasPrefix("member:") else { return }
        try ProgressSyncStateDao.revokeBootstrap(db, ownerAccountID: String(identity.dropFirst("member:".count)))
    }

    /// Persists the immutable snapshot, attempt and create event before the UI
    /// is allowed to reveal the first question.
    @discardableResult
    public static func examStarted(_ db: SqlDatabase, snapshot: ExamSnapshotRecord,
                                   attempt: ExamAttemptRecord) throws -> ExamHistory {
        try ExamHistoryDao.start(db, snapshot: snapshot, attempt: attempt)
    }

    /// Saves answer and next position with a durable outbox event atomically.
    @discardableResult
    public static func examAnswerSaved(_ db: SqlDatabase, attemptID: String, questionID: String,
                                       responseJSON: String, position: Int, savedAt: Double) throws -> Int64 {
        try ExamHistoryDao.saveAnswer(db, attemptID: attemptID, questionID: questionID,
                                      responseJSON: responseJSON, position: position, savedAt: savedAt)
    }

    /// Persists the original grading exactly once; byte-identical retries are no-ops.
    @discardableResult
    public static func examCompleted(_ db: SqlDatabase, attemptID: String,
                                     resultJSON: String, completedAt: Double) throws -> Bool {
        try ExamHistoryDao.complete(db, attemptID: attemptID, resultJSON: resultJSON,
                                    completedAt: completedAt)
    }

    /// The Kotlin `ArchiveMerger.rewriteInTx`: the merged archive is written
    /// verbatim onto wiped tables (adoptLegacy = false on a wiped table — the
    /// coexistence the archive merge just validated), preferences follow the
    /// merge (which keeps the CURRENT preferences), and the platform-local
    /// queues (usage_outbox, assessment_tickets) stay untouched. The iOS
    /// Archive model has no masteryCache (the model gap documented on
    /// `ArchiveMapper.import`), so the rewrite routes nil and the mastery
    /// rows end up empty exactly as a Kotlin rewrite of a cache-less archive.
    private static func rewriteInTx(_ db: SqlDatabase, _ archive: Archive) throws {
        _ = try db.update("DELETE FROM sessions WHERE \(ownerClause(db))", args: ownerArguments(db)) // fragments follow by ON DELETE CASCADE
        for session in archive.sessions {
            try SessionsDao.upsert(db, session)
            try FragmentsDao.replaceInTx(db, session.id.uuidString, session.fragments)
        }
        _ = try db.update("DELETE FROM expressions WHERE \(ownerClause(db))", args: ownerArguments(db))
        for expression in archive.expressions ?? [] {
            _ = try ExpressionsDao.upsert(db, expression, adoptLegacy: false)
        }
        _ = try db.update("DELETE FROM lesson_state WHERE \(ownerClause(db))", args: ownerArguments(db))
        for lesson in archive.lessons ?? [] {
            try LessonStateDao.upsert(db, lesson)
        }
        // add (insert-or-ignore) keeps the FIRST mark of a same-instant
        // cross-language pair — the schema-pinned narrowing documented on
        // CompletionsDao.
        _ = try db.update("DELETE FROM completions WHERE \(ownerClause(db))", args: ownerArguments(db))
        for mark in archive.completions ?? [] {
            _ = try CompletionsDao.add(db, mark)
        }
        _ = try db.update("DELETE FROM skill_errors WHERE \(ownerClause(db))", args: ownerArguments(db))
        for error in archive.errors ?? [] {
            try SkillErrorsDao.upsert(db, error)
        }
        try PreferencesDao.put(db, archive.preferences)
        try MetaDao.replaceMastery(db, [:])
        try MetaDao.ensureSchemaFlags(db)
    }
}
