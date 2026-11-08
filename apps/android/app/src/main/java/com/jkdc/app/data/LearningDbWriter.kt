package com.jkdc.app.data

import com.jkdc.app.core.Archive
import com.jkdc.app.core.DailyAttemptReducer
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

    private fun markActiveOwnerHasLocalData(db: SqlDatabase) {
        LearnerOwnershipDao.activeIdentity(db)?.let { OwnerBootstrapEligibilityDao.markLocalData(db, it) }
    }

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
            markActiveOwnerHasLocalData(tx)
        }
    }

    /** recordCompletion() and the completions side of lesson attempts: the
        archive's whole (re-sorted, truncated) ledger, in list order. */
    fun completions(db: SqlDatabase, marks: List<CompletionMark>) {
        db.transaction { tx ->
            CompletionsDao.replaceAllInTx(tx, marks,
                TimestampEvidence("android.recordCompletion", 2, "apple_reference_seconds"))
            markActiveOwnerHasLocalData(tx)
        }
    }

    /** recordExerciseMistake(): the archive's whole (re-sorted, truncated)
        error memory, in list order. */
    fun skillErrors(db: SqlDatabase, errors: List<SkillError>) {
        db.transaction { tx ->
            SkillErrorsDao.replaceAllInTx(tx, errors)
            markActiveOwnerHasLocalData(tx)
        }
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
            markActiveOwnerHasLocalData(tx)
        }
    }

    /** saveLookupExpression()/gradeRecall(): the post-engine state of the one
        affected expression. adoptLegacy stays on: when the engine adopted a
        legacy NULL row, the incoming state IS that adopted row, so the DAO's
        engine-faithful adoption reproduces it (its fallbacks read the same
        legacy values the engine kept, and its meaning fill falls back to the
        incoming meaning, which is the engine's own post-state). */
    fun expressionUpserted(db: SqlDatabase, state: ExpressionState) {
        db.transaction { tx ->
            ExpressionsDao.upsert(tx, state)
            markActiveOwnerHasLocalData(tx)
        }
    }

    /** removeExpression(): RecallEngine.remove drops the language row AND the
        legacy NULL row for the text; the mirror deletes both. */
    fun expressionRemoved(db: SqlDatabase, text: String, languageID: String) {
        db.transaction { tx ->
            val owner = LearnerOwnershipDao.activeIdentity(tx)
            val key = com.jkdc.app.core.RecallEngine.normalizeKey(text)
            if (owner != null && tx.query("SELECT 1 AS found FROM daily_word_imports WHERE owner_key = ? AND language_id = ? AND expression_key = ? LIMIT 1",
                    listOf(owner, languageID, key)).isNotEmpty()) {
                tx.insert("daily_word_suppressions", mapOf("owner_key" to owner, "language_id" to languageID,
                    "norm_key" to key, "deleted_at" to System.currentTimeMillis() / 1000.0), ConflictStrategy.IGNORE)
            }
            ExpressionsDao.removeVisible(tx, text, languageID)
        }
    }

    /** Persist the immutable package snapshot and latest local attempt state before rendering it. */
    fun cacheDailyAttempt(db: SqlDatabase, owner: String, attemptID: String, packageSnapshot: String,
        stateJSON: String, revision: Long, updatedAt: Double) {
        require(owner.isNotBlank() && attemptID.isNotBlank() && revision >= 0 && updatedAt.isFinite())
        require(packageSnapshot.toByteArray(Charsets.UTF_8).size <= 65_536 && stateJSON.toByteArray(Charsets.UTF_8).size <= 65_536)
        db.transaction { tx ->
            check(LearnerOwnershipDao.activeIdentity(tx) == owner) { "daily_attempt_owner_changed" }
            val existing = tx.query("SELECT package_snapshot,revision FROM daily_attempt_cache WHERE owner_key=? AND attempt_id=?",
                listOf(owner, attemptID)).firstOrNull()
            check(existing == null || existing["package_snapshot"] == packageSnapshot) { "daily_attempt_package_changed" }
            val priorRevision = existing?.get("revision") as? Long
            check(priorRevision == null || revision >= priorRevision) { "daily_attempt_stale_revision" }
            tx.insert("daily_attempt_cache", mapOf("owner_key" to owner, "attempt_id" to attemptID,
                "package_snapshot" to packageSnapshot, "state_json" to stateJSON, "revision" to revision,
                "updated_at" to updatedAt), ConflictStrategy.REPLACE)
        }
    }

    /** Adopt a server-canonical attempt ID before any event can be queued. */
    fun remapDailyAttemptId(db: SqlDatabase, owner: String, provisionalID: String, canonicalID: String,
        stateJSON: String, revision: Long, updatedAt: Double) {
        require(owner.isNotBlank() && provisionalID.isNotBlank() && canonicalID.isNotBlank() &&
            revision >= 0 && updatedAt.isFinite())
        val remappedState = DailyAttemptReducer.decode(stateJSON).copy(attemptId = canonicalID, revision = revision)
        db.transaction { tx ->
            check(LearnerOwnershipDao.activeIdentity(tx) == owner) { "daily_attempt_owner_changed" }
            val existing = tx.query("SELECT package_snapshot FROM daily_attempt_cache WHERE owner_key=? AND attempt_id=?",
                listOf(owner, provisionalID)).singleOrNull() ?: error("daily_attempt_not_cached")
            check(tx.query("SELECT 1 AS found FROM daily_outbox WHERE owner_key=? AND attempt_id=? LIMIT 1",
                listOf(owner, provisionalID)).isEmpty()) { "daily_attempt_already_has_events" }
            check(tx.query("SELECT 1 AS found FROM daily_attempt_cache WHERE owner_key=? AND attempt_id=? LIMIT 1",
                listOf(owner, canonicalID)).isEmpty()) { "daily_attempt_canonical_id_conflict" }
            val changed = tx.update("UPDATE daily_attempt_cache SET attempt_id=?,state_json=?,revision=?,updated_at=? WHERE owner_key=? AND attempt_id=?",
                listOf(canonicalID, DailyAttemptReducer.encode(remappedState), revision, updatedAt, owner, provisionalID))
            check(changed == 1L && existing["package_snapshot"] != null) { "daily_attempt_remap_failed" }
        }
    }

    /** Atomically advances the local reducer and records its network event before dispatch. */
    fun enqueueDailyEvent(db: SqlDatabase, owner: String, eventID: String, attemptID: String, intentJSON: String,
        intentHash: String, expectedRevision: Long, sequence: Long, nextStateJSON: String, createdAt: Double): Boolean {
        require(owner.isNotBlank() && eventID.isNotBlank() && attemptID.isNotBlank() && intentHash.isNotBlank())
        require(expectedRevision >= 0 && sequence >= 0 && createdAt.isFinite())
        require(intentJSON.toByteArray(Charsets.UTF_8).size <= 24_576 && nextStateJSON.toByteArray(Charsets.UTF_8).size <= 65_536)
        return db.transaction { tx ->
            check(LearnerOwnershipDao.activeIdentity(tx) == owner) { "daily_attempt_owner_changed" }
            val previous = tx.query("SELECT revision FROM daily_attempt_cache WHERE owner_key=? AND attempt_id=?",
                listOf(owner, attemptID)).firstOrNull() ?: error("daily_attempt_not_cached")
            val replay = tx.query("SELECT attempt_id,intent_json,intent_hash,expected_revision,sequence FROM daily_outbox WHERE owner_key=? AND event_id=?",
                listOf(owner, eventID)).firstOrNull()
            if (replay != null) {
                check(replay["attempt_id"] == attemptID && replay["intent_json"] == intentJSON &&
                    replay["intent_hash"] == intentHash && replay["expected_revision"] == expectedRevision &&
                    replay["sequence"] == sequence) { "daily_event_id_conflict" }
                return@transaction false
            }
            check((previous["revision"] as Long) == expectedRevision) { "daily_attempt_revision_conflict" }
            val nextSequence = (tx.query("SELECT COALESCE(MAX(sequence),-1)+1 AS next_sequence FROM daily_outbox WHERE owner_key=? AND attempt_id=?",
                listOf(owner, attemptID)).single()["next_sequence"] as Long)
            check(sequence == nextSequence) { "daily_event_sequence_conflict" }
            tx.insert("daily_outbox", mapOf("owner_key" to owner, "event_id" to eventID, "attempt_id" to attemptID,
                "intent_json" to intentJSON, "intent_hash" to intentHash, "expected_revision" to expectedRevision,
                "sequence" to sequence, "delivery_state" to "pending", "created_at" to createdAt))
            val changed = tx.update("UPDATE daily_attempt_cache SET state_json=?,revision=?,updated_at=? WHERE owner_key=? AND attempt_id=? AND revision=?",
                listOf(nextStateJSON, expectedRevision + 1, createdAt, owner, attemptID, expectedRevision))
            check(changed == 1L) { "daily_attempt_revision_conflict" }
            true
        }
    }

    /** Records the result class for a previously persisted immutable outbox event. */
    fun markDailyEventState(db: SqlDatabase, owner: String, eventID: String, state: String) {
        require(state in setOf("pending", "sending", "outcome_unknown", "revision_conflict", "rejected", "acknowledged"))
        db.transaction { tx ->
            check(LearnerOwnershipDao.activeIdentity(tx) == owner) { "daily_attempt_owner_changed" }
            val changed = tx.update("UPDATE daily_outbox SET delivery_state=? WHERE owner_key=? AND event_id=?",
                listOf(state, owner, eventID))
            check(changed == 1L) { "daily_event_not_found" }
        }
    }

    /** Caches only the server-admitted review, immutable for this owner/review ID. */
    fun cacheDailyReview(db: SqlDatabase, owner: String, reviewID: String, attemptID: String,
        resultJSON: String, createdAt: Double) {
        require(owner.isNotBlank() && reviewID.isNotBlank() && attemptID.isNotBlank() && createdAt.isFinite())
        require(resultJSON.toByteArray(Charsets.UTF_8).size <= 32_768)
        db.transaction { tx ->
            check(LearnerOwnershipDao.activeIdentity(tx) == owner) { "daily_attempt_owner_changed" }
            check(tx.query("SELECT 1 AS found FROM daily_attempt_cache WHERE owner_key=? AND attempt_id=?",
                listOf(owner, attemptID)).isNotEmpty()) { "daily_attempt_not_cached" }
            val existing = tx.query("SELECT attempt_id,result_json FROM daily_review_cache WHERE owner_key=? AND review_id=?",
                listOf(owner, reviewID)).firstOrNull()
            check(existing == null || (existing["attempt_id"] == attemptID && existing["result_json"] == resultJSON)) {
                "daily_review_conflict"
            }
            if (existing == null) tx.insert("daily_review_cache", mapOf("owner_key" to owner, "review_id" to reviewID,
                "attempt_id" to attemptID, "result_json" to resultJSON, "created_at" to createdAt))
        }
    }

    /** Applies a planned batch only while the database still belongs to its captured learner. */
    fun dailyWordsImported(db: SqlDatabase, owner: String, plans: List<com.jkdc.app.core.DailyWordImportPlan>,
        clearSuppressions: Set<Pair<String, String>> = emptySet()): com.jkdc.app.core.DailyWordsImportSummary =
        db.transaction { tx ->
            check(LearnerOwnershipDao.activeIdentity(tx) == owner) { "daily_import_owner_changed" }
            val plannedKeys = plans.map { it.languageID to it.normKey }.toSet()
            require(clearSuppressions.all { it in plannedKeys }) { "daily_import_suppression_not_planned" }
            clearSuppressions.forEach { (language, normKey) ->
                tx.update("DELETE FROM daily_word_suppressions WHERE owner_key=? AND language_id=? AND norm_key=?",
                    listOf(owner, language, normKey))
            }
            var inserted = 0; var existing = 0; var suppressed = 0; var pendingCapacity = 0
            for (plan in plans) {
                require(plan.owner == owner)
                var action = when (plan.action) {
                    com.jkdc.app.core.DailyWordImportAction.INSERT -> "imported"
                    com.jkdc.app.core.DailyWordImportAction.KEEP -> "existing"
                    com.jkdc.app.core.DailyWordImportAction.SUPPRESS -> "suppressed"
                    com.jkdc.app.core.DailyWordImportAction.PENDING_CAPACITY -> "pending_capacity"
                }
                if (plan.action == com.jkdc.app.core.DailyWordImportAction.INSERT) {
                    val saved = ExpressionsDao.upsert(tx, ExpressionState(text = plan.text, meaning = plan.meaning,
                        source = "lookup", dueAt = plan.now, languageID = plan.languageID), adoptLegacy = false)
                    if (!saved) action = "pending_capacity"
                }
                when (action) {
                    "imported" -> inserted++
                    "existing" -> existing++
                    "suppressed" -> suppressed++
                    "pending_capacity" -> pendingCapacity++
                }
                if (action != "pending_capacity") tx.update("DELETE FROM daily_word_imports WHERE owner_key = ? AND origin_key = ? AND action = 'pending_capacity'",
                    listOf(owner, plan.originKey))
                val originParts = plan.originKey.split(':')
                val reviewID = originParts.getOrNull(1)?.takeIf { originParts.firstOrNull() == "review" && originParts.size >= 3 }
                tx.insert("daily_word_imports", mapOf("owner_key" to owner, "origin_key" to plan.originKey,
                    "language_id" to plan.languageID, "expression_key" to plan.normKey, "kind" to plan.kind,
                    "review_id" to reviewID, "action" to action,
                    "importer_version" to 1, "created_at" to plan.now), ConflictStrategy.REPLACE)
            }
            com.jkdc.app.core.DailyWordsImportSummary(inserted, existing, suppressed, pendingCapacity)
        }

    /** updatePreferences()/toggleMeaning()/hideWord(): the whole Preferences
        (one row). */
    fun preferences(db: SqlDatabase, preferences: Preferences) {
        db.transaction { tx ->
            PreferencesDao.put(tx, preferences)
            markActiveOwnerHasLocalData(tx)
        }
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
            LearnerOwnershipDao.activeIdentity(tx)?.let {
                OwnerBootstrapEligibilityDao.clearBootstrapIntentInTransaction(tx, it)
            }
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
    fun archiveRestored(db: SqlDatabase, incoming: Archive): Archive = ArchiveMerger.apply(db, incoming)

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
