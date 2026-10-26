package com.jkdc.app.data

import com.jkdc.app.core.Archive
import com.jkdc.app.core.CompletionMark
import com.jkdc.app.core.ExpressionState
import com.jkdc.app.core.LessonState
import com.jkdc.app.core.Preferences
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.SkillError

/**
 * The DB mirror of every AppViewModel mutation site (the phase-C cutover).
 * Each function persists ONLY what one site mutated, and each is the exact
 * DAO call sequence that keeps the consistency invariant
 *
 *     export(db) == the in-memory archive the engines produced
 *
 * for that flow — pinned per flow by LearningCutoverFlowTest, which replays
 * the archive mutation with the real engines and then calls THESE functions
 * in the same order the site does. AppViewModel enqueues them on its
 * single-consumer write channel (ordered, IO-backed); the functions here are
 * pure data-layer work and never touch view state.
 *
 * Conventions:
 *  - Every function opens its own transaction where it writes more than one
 *    statement (transactions do not nest — pass a tx handle around, never
 *    wrap these calls in another transaction).
 *  - Rowid order must equal archive list order for every content table, so
 *    re-sorting mutations (completions by `at`, skill errors by count) and
 *    session re-ordering (save() moves the updated session to the end of
 *    archive.sessions) rewrite position, not just content.
 *  - The retry ledger's durable form is AssessmentTicketRow —
 *    passageID/revisionKey are runtime-only (schema v1), so a mirrored
 *    ledger change carries only session_id/attempts/lastAttemptAt.
 */
object LearningDbWriter {

    /**
     * save()/updateSession()/newSession(): one session's durable form.
     *
     * The site replaces the session in archive.sessions by removing it and
     * appending a fresh clone, so the archive list position is ALWAYS last
     * after a save. When the session already was the archive's last (the
     * common case: the live conversation), an in-place upsert keeps its rowid
     * position — which is the last — and [moveToEnd] is false. When it came
     * from the middle (a resumed older conversation continued), the rowid
     * must move too: [moveToEnd] deletes the row (fragments follow by
     * ON DELETE CASCADE) and reinserts it at the end, inside the same
     * transaction as the fragment rewrite.
     *
     * [ticketChanged]/[ticket] mirror the site's retry-ledger diff
     * (ConversationProviderPolicy.enqueueRecovery): null with
     * [ticketChanged] = true deletes the session's row, non-null upserts the
     * ledger's durable fields; false skips the ledger entirely.
     */
    fun session(db: SqlDatabase, record: SessionRecord, moveToEnd: Boolean,
                ticketChanged: Boolean = false, ticket: AssessmentTicketRow? = null) {
        db.transaction { tx ->
            if (moveToEnd) SessionsDao.delete(tx, record.id)
            SessionsDao.upsert(tx, record)
            FragmentsDao.replaceInTx(tx, record.id, record.fragments)
            if (ticketChanged) {
                if (ticket == null) AssessmentTicketsDao.delete(tx, record.id)
                else AssessmentTicketsDao.upsert(tx, ticket)
            }
        }
    }

    /** recordCompletion() and the completions side of lesson attempts: the
        archive's whole (re-sorted, truncated) ledger, in list order. */
    fun completions(db: SqlDatabase, marks: List<CompletionMark>) {
        db.transaction { tx -> CompletionsDao.replaceAllInTx(tx, marks,
            TimestampEvidence("android.recordCompletion", 2, "apple_reference_seconds")) }
    }

    /** recordExerciseMistake(): the archive's whole (re-sorted, truncated)
        error memory, in list order. */
    fun skillErrors(db: SqlDatabase, errors: List<SkillError>) {
        db.transaction { tx -> SkillErrorsDao.replaceAllInTx(tx, errors) }
    }

    /** recordLessonAttempt(): the lesson's counters, the ledger mark (passed
        as the whole post-sort list for [completions]' reason), and the
        expressions RecallEngine.seedFromLesson left in the archive — one
        ExpressionsDao.upsert per affected entry, in seeding order, so
        adoption (legacy NULL row retargeted) and appends land identically. */
    fun lessonAttempt(db: SqlDatabase, lesson: LessonState, marks: List<CompletionMark>,
                      seeds: List<ExpressionState>) {
        db.transaction { tx ->
            LessonStateDao.upsert(tx, lesson,
                TimestampEvidence("android.recordLessonAttempt", 2, "apple_reference_seconds"))
            CompletionsDao.replaceAllInTx(tx, marks,
                TimestampEvidence("android.recordLessonAttempt", 2, "apple_reference_seconds"))
            seeds.forEach { ExpressionsDao.upsert(tx, it) }
        }
    }

    /** saveLookupExpression()/gradeRecall(): the post-engine state of the one
        affected expression. adoptLegacy stays on: when the engine adopted a
        legacy NULL row, the incoming state IS that adopted row, so the DAO's
        engine-faithful adoption reproduces it (its fallbacks read the same
        legacy values the engine kept, and its meaning fill falls back to the
        incoming meaning, which is the engine's own post-state). */
    fun expressionUpserted(db: SqlDatabase, state: ExpressionState) {
        ExpressionsDao.upsert(db, state)
    }

    /** removeExpression(): RecallEngine.remove drops the language row AND the
        legacy NULL row for the text; the mirror deletes both. */
    fun expressionRemoved(db: SqlDatabase, text: String, languageID: String) {
        ExpressionsDao.removeVisible(db, text, languageID)
    }

    /** updatePreferences()/toggleMeaning()/hideWord(): the whole Preferences
        (one row). */
    fun preferences(db: SqlDatabase, preferences: Preferences) {
        PreferencesDao.put(db, preferences)
    }

    /** deleteSession(): the DELETE (fragments follow by FK cascade) plus the
        session's retry-ledger row when the ledger held one. */
    fun sessionDeleted(db: SqlDatabase, id: String, ticketRemoved: Boolean) {
        db.transaction { tx ->
            SessionsDao.delete(tx, id)
            if (ticketRemoved) AssessmentTicketsDao.delete(tx, id)
        }
    }

    /** deleteLearningData(): the site wipes sessions (FK cascade takes the
        fragments), the retry ledger, and the hidden-words preference —
        lessons, expressions, completions and skill errors are NOT learning
        data the site touches, faithful to the previous full-archive write. */
    fun learningDataDeleted(db: SqlDatabase, preferences: Preferences) {
        db.transaction { tx ->
            tx.update("DELETE FROM exam_attempts WHERE ${ownerClause(tx)}", ownerArguments(tx))
            tx.update("DELETE FROM sessions WHERE ${ownerClause(tx)}", ownerArguments(tx))
            AssessmentTicketsDao.clear(tx)
            PreferencesDao.put(tx, preferences)
        }
    }

    /** finalAssessments.beforeAssessment: the retried ticket's bookkeeping. */
    fun ticketAttempted(db: SqlDatabase, ticket: AssessmentTicketRow) {
        AssessmentTicketsDao.upsert(db, ticket)
    }

    /** syncProgress()'s remote pull: the lessons ProgressSync.mergeRemote
        changed or appended, plus the marks it appended (append-only — no
        re-sort — so plain inserts keep rowid order equal to archive order;
        add() is insert-or-ignore on the ledger's primary key). */
    fun progressMerged(db: SqlDatabase, lessons: List<LessonState>, marks: List<CompletionMark>) {
        db.transaction { tx ->
            lessons.forEach { LessonStateDao.upsert(tx, it) }
            marks.forEach { CompletionsDao.add(tx, it) }
        }
    }

    /** Applies one validated remote page and its resume cursor atomically. */
    fun remoteProgressPageApplied(db: SqlDatabase, ownerAccountID: String,
                                  rollups: List<RemoteProgressRollup>, nextCursor: String?,
                                  passGeneration: Long, updatedAtMilliseconds: Long) {
        require(rollups.all { it.ownerAccountID == ownerAccountID })
        db.transaction { tx ->
            rollups.forEach { RemoteProgressRollupsDao.merge(tx, it) }
            ProgressSyncStateDao.put(tx, ProgressSyncState(ownerAccountID, "download", nextCursor,
                passGeneration, updatedAtMilliseconds))
        }
    }

    /** Upload acknowledgements advance only upload resume state, never rollup data. */
    fun remoteProgressUploadAcknowledged(db: SqlDatabase, ownerAccountID: String, nextCursor: String?,
                                         passGeneration: Long, updatedAtMilliseconds: Long) {
        db.transaction { tx -> ProgressSyncStateDao.put(tx, ProgressSyncState(ownerAccountID, "upload",
            nextCursor, passGeneration, updatedAtMilliseconds)) }
    }

    /** Recovery bookkeeping is versioned and written without rewriting legacy source rows. */
    fun progressRecoveryRecorded(db: SqlDatabase, record: ProgressRecoveryRecord) {
        db.transaction { tx -> ProgressRecoveryDao.record(tx, record) }
    }

    /** Runs timestamp classification transactionally before callers materialize sync state. */
    fun progressRecoveryRun(db: SqlDatabase, nowUnixMilliseconds: Long) {
        ProgressRecoveryDao.recover(db, nowUnixMilliseconds)
    }

    /** importData() (DB restore path): ArchiveMerger.apply IS the write; kept
        here so the site mapping table names one place per flow. */
    fun archiveRestored(db: SqlDatabase, incoming: Archive): Archive =
        ArchiveMerger.apply(db, incoming)

    /** Starts only after the immutable snapshot, attempt, and create outbox
        record have committed; callers must await this before showing a question. */
    fun examStarted(db: SqlDatabase, snapshot: ExamSnapshotRecord, attempt: ExamAttemptRecord): ExamHistory =
        ExamHistoryDao.start(db, snapshot, attempt)

    /** Autosave the response and next visible position as one durable unit. */
    fun examAnswerSaved(db: SqlDatabase, attemptID: String, questionID: String,
                        responseJSON: String, position: Int, savedAt: Double): Long =
        ExamHistoryDao.saveAnswer(db, attemptID, questionID, responseJSON, position, savedAt)

    /** Freeze the original graded result; identical retries are no-ops. */
    fun examCompleted(db: SqlDatabase, attemptID: String, resultJSON: String, completedAt: Double): Boolean =
        ExamHistoryDao.complete(db, attemptID, resultJSON, completedAt)
}
