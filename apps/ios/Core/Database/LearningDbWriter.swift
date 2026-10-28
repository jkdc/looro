import Foundation

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
        _ = try ExpressionsDao.removeVisible(db, text, languageID)
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
