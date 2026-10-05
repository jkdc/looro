package com.jkdc.app.data

import com.jkdc.app.core.Archive
import com.jkdc.app.core.ArchiveCodec
import com.jkdc.app.core.CompletionMark
import com.jkdc.app.core.ConversationProviderPolicy
import com.jkdc.app.core.CourseRegistry
import com.jkdc.app.core.ExpressionState
import com.jkdc.app.core.FinalAssessmentRecovery
import com.jkdc.app.core.FinalAssessmentTicket
import com.jkdc.app.core.Fragment
import com.jkdc.app.core.LessonState
import com.jkdc.app.core.Preferences
import com.jkdc.app.core.ProgressEngine
import com.jkdc.app.core.ProgressSync
import com.jkdc.app.core.RecallEngine
import com.jkdc.app.core.RemoteProgress
import com.jkdc.app.core.ReviewedError
import com.jkdc.app.core.ScenarioReview
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.SkillError
import com.jkdc.app.core.Speaker
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phase-C cutover's runtime write-order contract, replayed on real SQLite
 * (sqlite-jdbc): for each key AppViewModel flow, the archive mutation runs
 * through the real engines exactly as the site does, then the SAME
 * LearningDbWriter call sequence the site enqueues runs against the
 * database — and the consistency invariant must hold after every step:
 *
 *     ArchiveMapper.export(db) == the in-memory archive the engines produced
 *
 * AppViewModel itself needs an Android Application and cannot run on the JVM
 * (the brief pins this), so each replay spells out the archive-mutation half
 * of its site under a comment naming the site; the DB half is the real
 * LearningDbWriter/DAO code the site calls. The per-site mapping lives in
 * the phase-C task-2 report.
 */
class LearningCutoverFlowTest {
    private val now = 812_700_000.0
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)

    @After fun closeConnection() { connection.close() }

    private fun export(): Archive = ArchiveMapper.export(db)

    /** The startup load, as AppViewModel.loadLearningState does it: one-shot
        export; the store's recovery has already run by the time this is used. */
    private fun startFrom(archive: Archive): Archive {
        ArchiveMapper.import(archive, db)
        return export()
    }

    /** AppViewModel.removeExpression's two halves (engine on a copy, then the
        mirror deleting every row visible to the language). */
    private fun removeExpression(state: Archive, text: String, languageID: String): Archive {
        val next = state.copy(expressions = state.expressions.toMutableList())
        RecallEngine.remove(next, text)
        LearningDbWriter.expressionRemoved(db, text, languageID)
        return next
    }

    // ------------------------------------------------------------------
    // Flow 1 — saveLookupExpression: legacy adoption, exact-match update,
    // brand-new append.
    // ------------------------------------------------------------------
    @Test fun lookupSaveFlowKeepsExportEqualToTheArchiveAndAdoptsLegacyRows() {
        var state = startFrom(Archive(preferences = Preferences(learningLanguageID = "es"),
            expressions = mutableListOf(
                ExpressionState(text = "hola", meaning = "", source = "lesson",
                    lastSeen = 5.0, dueAt = 6.0, lessonID = "es-foundations-u1-ser"))))

        /** AppViewModel.saveLookupExpression's two halves. */
        fun runSaveLookup(text: String, meaning: String) {
            val key = RecallEngine.normalizeKey(text)
            val index = state.expressions.indexOfFirst {
                RecallEngine.normalizeKey(it.text) == key &&
                    (it.languageID == "es" || it.languageID == null)
            }
            check(RecallEngine.saveLookup(state, text, meaning, now))
            val affected = if (index >= 0) state.expressions[index] else state.expressions.last()
            LearningDbWriter.expressionUpserted(db, affected)
        }

        // adoption: the legacy NULL row is retargeted to es, its meaning filled
        // from the lookup, every other field kept from the legacy entry
        runSaveLookup("Hola", "hello")
        assertEquals(state, export())
        val adopted = state.expressions.single()
        assertEquals("es", adopted.languageID)
        assertEquals("hello", adopted.meaning)
        assertEquals("lesson", adopted.source)
        assertEquals("es-foundations-u1-ser", adopted.lessonID)

        // exact match: the meaning is already set, a second lookup changes nothing
        runSaveLookup("  HOLA ", "otra cosa")
        assertEquals(state, export())

        // brand-new key: appended at the end
        runSaveLookup("¿qué tal?", "how are you")
        assertEquals(state, export())
        assertEquals("es", state.expressions.last().languageID)
        assertEquals("lookup", state.expressions.last().source)

        // the per-language notebook projection equals the reader's
        assertEquals(
            state.expressions.filter { it.languageID == null || it.languageID == "es" }
                .sortedByDescending { it.lastSeen },
            LearningDbReader.expressionsFor(db, "es").sortedByDescending { it.lastSeen })
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM expressions").single().long("n"))
    }

    // ------------------------------------------------------------------
    // Flow 2 — recordLessonAttempt: counters, ledger mark, seeding; then
    // gradeRecall and removeExpression on the seeded notebook.
    // ------------------------------------------------------------------
    @Test fun lessonAttemptFlowPersistsCountersLedgerAndSeededExpressions() {
        var state = startFrom(Archive(preferences = Preferences(learningLanguageID = "de")))
        val lesson = CourseRegistry.lesson("de-foundations-u1-sein")!!
        assertTrue("the fixture lesson must seed expressions", lesson.expressions.isNotEmpty())

        /** AppViewModel.recordLessonAttempt's two halves. */
        fun runLessonAttempt(lessonID: String, at: Double) {
            val lessons = state.lessons.toMutableList()
            val i = lessons.indexOfFirst { it.lessonID == lessonID }
            val updated = if (i >= 0) lessons[i].copy(attempts = lessons[i].attempts + 1, lastSeen = at)
            else LessonState(lessonID = lessonID, attempts = 1, lastSeen = at)
            if (i >= 0) lessons[i] = updated else lessons += updated
            val marks = ((state.completions ?: mutableListOf()) +
                CompletionMark(ProgressEngine.LESSON, lessonID, at, 0, "de"))
                .sortedByDescending { it.at }.take(ArchiveCodec.MAXIMUM_COMPLETIONS).toMutableList()
            state = state.copy(lessons = lessons, completions = marks)
            RecallEngine.seedFromLesson(state, CourseRegistry.lesson(lessonID)!!, at)
            val seeded = CourseRegistry.lesson(lessonID)!!.expressions.mapNotNull { expression ->
                val key = RecallEngine.normalizeKey(expression.text)
                if (key.isEmpty()) null
                else state.expressions.firstOrNull {
                    RecallEngine.normalizeKey(it.text) == key && (it.languageID == "de" || it.languageID == null)
                }
            }.distinctBy { RecallEngine.expressionKey(it.text, it.languageID) }
            LearningDbWriter.lessonAttempt(db, updated, marks, seeded)
        }

        runLessonAttempt(lesson.id, now)
        assertEquals(state, export())
        assertEquals(1, state.lessons.single { it.lessonID == lesson.id }.attempts)
        assertEquals(lesson.expressions.size, state.expressions.size)

        // a second attempt (later wall clock, like the real engine clock):
        // counters grow, seeding is idempotent, the ledger gains a mark
        runLessonAttempt(lesson.id, now + 60)
        assertEquals(state, export())
        assertEquals(2, state.lessons.single { it.lessonID == lesson.id }.attempts)
        assertEquals(2, state.completions.orEmpty().size)

        // gradeRecall: grade the first seeded expression, persist its post state
        val target = state.expressions.first()
        val key = RecallEngine.normalizeKey(target.text)
        val index = state.expressions.indexOfFirst {
            RecallEngine.normalizeKey(it.text) == key && (it.languageID == "de" || it.languageID == null)
        }
        RecallEngine.grade(state, target.text, correct = true, now)
        LearningDbWriter.expressionUpserted(db, state.expressions[index])
        assertEquals(state, export())

        // due() over the archive equals the reader's due projection
        assertEquals(RecallEngine.due(state, now + 60), LearningDbReader.dueExpressions(db, "de", now + 60))

        // removeExpression: the language row goes, export follows
        state = removeExpression(state, target.text, "de")
        assertEquals(state, export())
    }

    // ------------------------------------------------------------------
    // Flow 3 — session lifecycle: newSession, turns, end (retry ticket),
    // scenario review, resume-and-continue (list position moves),
    // deleteSession (cascade + ticket), and the startup ledger rebuild.
    // ------------------------------------------------------------------
    @Test fun sessionLifecycleFlowKeepsExportTicketsAndLedgerInStep() {
        val oldID = "11111111-1111-4111-8111-111111111111"
        val liveID = "22222222-2222-4222-8222-222222222222"
        var state = startFrom(Archive(preferences = Preferences(learningLanguageID = "es"),
            sessions = mutableListOf(SessionRecord(id = oldID, languageID = "es",
                startedAt = 100.0, endedAt = 200.0, endReason = "Ended by you", usageFinal = true).apply {
                append(Fragment(id = "old-f1", speaker = Speaker.user, text = "hola que tal", startMS = 0, endMS = 1))
            })))
        var ledger = emptyList<FinalAssessmentTicket>()

        /** AppViewModel.save's two halves (the write channel inlined). */
        fun runSave(record: SessionRecord) {
            val before = ledger.firstOrNull { it.sessionID == record.id }
            ledger = ConversationProviderPolicy.enqueueRecovery(record, ledger, emptySet())
            val after = ledger.firstOrNull { it.sessionID == record.id }
            val ticketChanged = when {
                after == null -> before != null
                before == null -> true
                else -> before.attempts != after.attempts || before.lastAttemptAt != after.lastAttemptAt
            }
            val existingIndex = state.sessions.indexOfFirst { it.id == record.id }
            val moveToEnd = existingIndex >= 0 && existingIndex != state.sessions.size - 1
            state = state.copy(sessions = (state.sessions.filterNot { it.id == record.id } + record).toMutableList())
            LearningDbWriter.session(db, state.sessions.last(), moveToEnd, ticketChanged,
                after?.let { AssessmentTicketRow(it.sessionID, it.attempts, it.lastAttemptAt) })
        }

        // newSession: an empty scenario session
        runSave(SessionRecord(id = liveID, languageID = "es", startedAt = 300.0, title = "Café",
            mode = "scenario", scenarioID = "order-coffee"))
        assertEquals(state, export())

        // turn appends (updateSession → save): user then assistant, usage counters
        val live1 = state.sessions.single { it.id == liveID }.apply {
            append(Fragment(id = "f1", speaker = Speaker.user, text = "un café con leche", startMS = 0, endMS = 1))
            inputTokens = 12; outputTokens = 3
        }
        runSave(live1)
        assertEquals(state, export())
        val live2 = state.sessions.single { it.id == liveID }.apply {
            append(Fragment(id = "f2", speaker = Speaker.assistant, text = "¿algo más?", startMS = 2, endMS = 3))
            outputTokens = 30
        }
        runSave(live2)
        assertEquals(state, export())
        assertEquals("the live session must be the archive's last", liveID, state.sessions.last().id)

        // end (finish → updateSession endedAt/usageFinal → save): the retry
        // ledger enqueues a ticket for the unassessed user passage
        val ended = state.sessions.single { it.id == liveID }.copy(
            endedAt = 400.0, usageFinal = false, endReason = "Ended by you")
        runSave(ended)
        assertEquals(state, export())
        val ticket = ledger.single { it.sessionID == liveID }
        assertEquals(liveID, ticket.sessionID)
        assertEquals(0, ticket.attempts)
        assertEquals(AssessmentTicketRow(liveID, 0, null), AssessmentTicketsDao.all(db).single())

        // the scenario review lands on the stored session (maybeGenerateScenarioReview)
        val reviewed = state.sessions.single { it.id == liveID }.copy(
            scenarioReview = ScenarioReview(createdAt = 401.0, strengths = listOf("Natural order."),
                errors = listOf(ReviewedError("un café", "un café, por favor", "pragmatic", "Softer request")),
                keyPhrases = listOf(), retryFocus = "Polite requests"))
        runSave(reviewed)
        assertEquals(state, export())
        assertEquals("Polite requests", export().sessions.single { it.id == liveID }.scenarioReview?.retryFocus)
        assertEquals("the ticket survives the review save", AssessmentTicketRow(liveID, 0, null),
            AssessmentTicketsDao.all(db).single())

        // resume-and-continue (resumeSession + append → save): the OLD session
        // is updated from the middle of the list — its row must move to the
        // end so export order stays equal to archive order
        val continued = export().sessions.single { it.id == oldID }.apply {
            append(Fragment(id = "old-f2", speaker = Speaker.user, text = "otra ronda", startMS = 4, endMS = 5))
        }
        runSave(continued)
        assertEquals(state, export())
        assertEquals("the continued session now sorts last, like the archive",
            oldID, export().sessions.last().id)

        // a restart's ledger rebuild (AppViewModel.loadLearningState): the
        // matching half is re-derived from each session's recoverable passage,
        // attempts/lastAttemptAt come off the row
        val rows = AssessmentTicketsDao.all(db).associateBy { it.sessionID }
        val reloaded = export()
        val rebuilt = reloaded.sessions.mapNotNull { session ->
            val passage = FinalAssessmentRecovery.passage(session) ?: return@mapNotNull null
            val row = rows[session.id] ?: return@mapNotNull null
            FinalAssessmentTicket(session.id, passage.id, passage.revisionKey, row.attempts, row.lastAttemptAt)
        }
        assertEquals("the rebuilt ledger equals the in-memory one", ledger, rebuilt)

        // deleteSession: the row (fragments by cascade) and its ticket go; the
        // continued old session keeps its own recovery ticket (it ended with an
        // unassessed passage, so its save enqueued one — engine semantics)
        val hadTicket = ledger.any { it.sessionID == liveID }
        ledger = ledger.filterNot { it.sessionID == liveID }
        state = state.copy(sessions = state.sessions.filterNot { it.id == liveID }.toMutableList())
        LearningDbWriter.sessionDeleted(db, liveID, hadTicket)
        assertEquals(state, export())
        assertEquals(listOf(AssessmentTicketRow(oldID, 0, null)), AssessmentTicketsDao.all(db))
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM fragments WHERE session_id = '$liveID'").single().long("n"))
    }

    // ------------------------------------------------------------------
    // Flow 4 — language switch + notebook isolation (per-language notebook,
    // legacy NULL rows reachable everywhere).
    // ------------------------------------------------------------------
    @Test fun languageSwitchIsolatesTheNotebookExceptLegacyRows() {
        var state = startFrom(Archive(preferences = Preferences(learningLanguageID = "es"),
            expressions = mutableListOf(
                ExpressionState(text = "perro", meaning = "dog", source = "lookup", lastSeen = 1.0, dueAt = 2.0, languageID = "es"),
                ExpressionState(text = "chien", meaning = "chien", source = "lookup", lastSeen = 2.0, dueAt = 3.0, languageID = "fr"),
                ExpressionState(text = "hola", meaning = "", source = "lesson", lastSeen = 3.0, dueAt = 4.0))))

        fun notebook(target: Archive, languageID: String) =
            target.expressions.filter { it.languageID == null || it.languageID == languageID }
                .sortedByDescending { it.lastSeen }

        // es: own row + the legacy row; fr rows invisible
        assertEquals(notebook(state, "es"), LearningDbReader.expressionsFor(db, "es").sortedByDescending { it.lastSeen })
        assertFalse(notebook(state, "es").any { it.text == "chien" })
        assertTrue(notebook(state, "es").any { it.text == "hola" })

        // switch (updatePreferences' mirror)
        state = state.copy(preferences = state.preferences.copy(learningLanguageID = "fr"))
        LearningDbWriter.preferences(db, state.preferences)
        assertEquals(state, export())

        // fr: own row + the legacy row; es rows invisible; due counts scoped
        assertEquals(notebook(state, "fr"), LearningDbReader.expressionsFor(db, "fr").sortedByDescending { it.lastSeen })
        assertFalse(notebook(state, "fr").any { it.text == "perro" })
        assertTrue(notebook(state, "fr").any { it.text == "hola" })
        assertEquals(RecallEngine.due(state, now).map { it.text },
            LearningDbReader.dueExpressions(db, "fr", now).map { it.text })

        // removing in fr takes the fr row AND the shared legacy row, per engine
        state = removeExpression(state, "hola", "fr")
        assertEquals(state, export())
        assertFalse(notebook(state, "fr").any { it.text == "hola" })
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM expressions WHERE norm_key = 'hola'").single().long("n"))
    }

    // ------------------------------------------------------------------
    // Flow 5 — recordExerciseMistake, syncProgress' pull, deleteLearningData,
    // and the restore path (importData).
    // ------------------------------------------------------------------
    @Test fun mistakeMemoryProgressPullAndWipeStayConsistent() {
        var state = startFrom(Archive(preferences = Preferences(learningLanguageID = "es")))
        val lesson = CourseRegistry.lesson("es-foundations-u1-ser")!!

        // recordExerciseMistake: counts re-sort the whole memory (rewrite, not upsert)
        fun runMistake(prompt: String) {
            val skills = lesson.skills.filter { it in SkillsData.categories.keys }
            check(skills.isNotEmpty())
            val errors = (state.errors ?: mutableListOf()).associateBy { it.skillID }.toMutableMap()
            skills.forEach { skill ->
                val current = errors[skill]
                errors[skill] = SkillError(skill, (current?.count ?: 0) + 1, now, prompt.take(120))
            }
            val memory = errors.values.sortedByDescending { it.count }
                .take(ArchiveCodec.MAXIMUM_ERRORS).toMutableList()
            state = state.copy(errors = memory)
            LearningDbWriter.skillErrors(db, memory)
        }
        runMistake("dijo X")
        runMistake("dijo Y otra vez")
        assertEquals(state, export())

        // syncProgress' pull (ProgressSync.mergeRemote): lessons update/append,
        // unseen marks append — mirrored by upserts + insert-or-ignore adds
        val lessonsBefore = state.lessons.toList()
        val markCountBefore = state.completions.orEmpty().size
        state = ProgressSync.mergeRemote(state, listOf(
            RemoteProgress(ProgressEngine.LESSON, lesson.id, now - 10, now, 3, 0, "es"),
            RemoteProgress(ProgressEngine.LISTENING, "es-listening-1", now - 5, now, 1, 80, "es"),
        ), "es")
        val changedLessons = state.lessons.filterIndexed { i, candidate -> i >= lessonsBefore.size || candidate != lessonsBefore[i] }
        val appendedMarks = state.completions.orEmpty().drop(markCountBefore)
        LearningDbWriter.progressMerged(db, changedLessons, appendedMarks)
        assertEquals(state, export())
        assertEquals(3, state.lessons.single { it.lessonID == lesson.id }.attempts)

        // deleteLearningData: sessions + ledger + hidden words; the rest stays
        val stored = state.preferences.copy(hiddenWords = emptyList())
        state = state.copy(sessions = mutableListOf(), preferences = stored)
        LearningDbWriter.learningDataDeleted(db, stored)
        assertEquals(state, export())
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM sessions").single().long("n"))
        assertEquals("lessons are not this site's learning data", 1, state.lessons.size)

        // importData (restore path): the merger's projection becomes the new
        // working copy and the invariant survives the swap
        val incoming = Archive(preferences = Preferences(learningLanguageID = "it"),
            sessions = mutableListOf(SessionRecord(id = "33333333-3333-4333-8333-333333333333",
                languageID = "it", startedAt = 10.0, endedAt = 20.0).apply {
                append(Fragment(id = "it-f1", speaker = Speaker.user, text = "ciao", startMS = 0, endMS = 1))
            }))
        val restored = LearningDbWriter.archiveRestored(db, incoming)
        state = restored
        assertEquals(state, export())
        // ArchiveCodec.merge keeps the CURRENT preferences (the old
        // importData behaved identically) while the incoming session lands
        assertEquals("es", export().preferences.learningLanguageID)
        assertEquals("the merged archive keeps both worlds' sessions", 1, export().sessions.size)
    }

    // ------------------------------------------------------------------
    // Flow 6 — recordCompletion ordering: the ledger is rewritten in the
    // archive's at-descending order, not appended, so export matches.
    // ------------------------------------------------------------------
    @Test fun completionLedgerRewriteKeepsTheArchivedSortOrder() {
        var state = startFrom(Archive(preferences = Preferences(learningLanguageID = "es"),
            completions = mutableListOf(CompletionMark("listening", "es-a", 100.0, 50, "es"))))

        /** AppViewModel.recordCompletion's two halves. */
        fun runCompletion(kind: String, itemID: String, at: Double, score: Int = 0) {
            val mark = CompletionMark(kind, ProgressEngine.completionKey(itemID), at, score, "es")
            val marks = ((state.completions ?: mutableListOf()) + mark)
                .sortedByDescending { it.at }.take(ArchiveCodec.MAXIMUM_COMPLETIONS)
            state = state.copy(completions = marks.toMutableList())
            LearningDbWriter.completions(db, marks)
        }

        runCompletion("listening", "es-b", 200.0)  // newest first after the sort
        assertEquals(state, export())
        assertEquals(200.0, export().completions!!.first().at, 0.0)
        runCompletion("reading", "es-c", 150.0)    // lands between the two
        runCompletion("lesson", "es-d", 300.0)     // and on top again
        assertEquals(state, export())
        assertEquals(listOf(300.0, 200.0, 150.0, 100.0), export().completions!!.map { it.at })
        assertEquals(state, export())
    }

    // ------------------------------------------------------------------
    // Flow 7 — removeExpressionLanguages: the notebook's multi-language
    // removal dialog. The dialog reads the working copy, so the SAME stats
    // projection must come out of both sides, and the deletes must keep
    // export(DB) == archive while conserving every non-selected bucket.
    // ------------------------------------------------------------------
    @Test fun notebookLanguageRemovalKeepsExportEqualToTheArchiveAndLegacySelectable() {
        var state = startFrom(Archive(preferences = Preferences(learningLanguageID = "es"),
            expressions = mutableListOf(
                ExpressionState(text = "perro", meaning = "dog", source = "lookup", lastSeen = 1.0, dueAt = 2.0, languageID = "es"),
                ExpressionState(text = "canción", meaning = "song", source = "lesson", lastSeen = 2.0, dueAt = 3.0, languageID = "es"),
                ExpressionState(text = "chien", meaning = "dog", source = "lookup", lastSeen = 3.0, dueAt = 4.0, languageID = "fr"),
                ExpressionState(text = "hola", meaning = "", source = "lookup", lastSeen = 4.0, dueAt = 5.0, languageID = null),
                ExpressionState(text = "ciao", meaning = "hi", source = "lookup", lastSeen = 5.0, dueAt = 6.0, languageID = null))))

        /** AppViewModel.expressionLanguageStats — the dialog's rows. */
        fun stats(target: Archive): List<ExpressionLanguageStats> = target.expressions
            .groupBy { it.languageID }
            .map { (languageID, rows) -> ExpressionLanguageStats(
                languageID, rows.size,
                rows.sumOf { (it.text.toByteArray(Charsets.UTF_8).size +
                    it.meaning.toByteArray(Charsets.UTF_8).size).toLong() }) }
            .sortedWith(compareByDescending<ExpressionLanguageStats> { it.count }
                .thenBy { it.languageID == null }
                .thenBy { it.languageID ?: "" })

        /** AppViewModel.removeExpressionLanguages' two halves: the SAME
            predicate purges the working copy; the per-language DAO
            transactions (legacy via its own delete) run against the DB. */
        fun runRemoval(languageIDs: Collection<String?>) {
            state = state.copy(expressions = state.expressions.filterNot {
                (it.languageID != null && it.languageID in languageIDs) ||
                    (null in languageIDs && it.languageID == null)
            }.toMutableList())
            languageIDs.filterNotNull().forEach { ExpressionsDao.deleteExpressionsForLanguage(db, it) }
            if (null in languageIDs) ExpressionsDao.deleteExpressionsLegacy(db)
        }

        // the dialog's rows equal the durable projection (count desc, named
        // languages before the legacy bucket within a tie — UTF-8 byte sizes:
        // es 8+12=20, legacy 4+0+4+2=10, fr 5+3=8)
        assertEquals(stats(state), ExpressionsDao.expressionStatsByLanguage(db))
        assertEquals(
            listOf(
                ExpressionLanguageStats("es", 2, 20L),
                ExpressionLanguageStats(null, 2, 10L),
                ExpressionLanguageStats("fr", 1, 8L),
            ),
            ExpressionsDao.expressionStatsByLanguage(db))

        // delete the current language: es goes EXACTLY (both rows), the fr
        // rows and the shared legacy bucket stay on both sides
        runRemoval(listOf<String?>("es"))
        assertEquals(state, export())
        assertEquals(listOf("chien", "hola", "ciao"), export().expressions.map { it.text })
        assertTrue(state.expressions.none { it.languageID == "es" })

        // the legacy bucket is selectable on its own and a multi-select takes
        // several buckets in one dialog pass — same predicate both sides
        runRemoval(listOf<String?>("fr", null))
        assertEquals(state, export())
        assertTrue(export().expressions.isEmpty())
        assertTrue(ExpressionsDao.expressionStatsByLanguage(db).isEmpty())
    }
}
