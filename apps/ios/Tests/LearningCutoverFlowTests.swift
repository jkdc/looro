import XCTest
@testable import LearningCore

/// The phase-E cutover's runtime write-order contract, replayed on real
/// SQLite (the Swift mirror of Android's `LearningCutoverFlowTest`): for each
/// key LearningStore flow, the archive mutation runs through the real engines
/// exactly as the site does, then the SAME LearningDbWriter call sequence the
/// site runs hits the database — and the consistency invariant must hold
/// after every step:
///
///     ArchiveMapper.export(db) == the in-memory archive the engines produced
///
/// LearningStore itself is an App-target type (the macOS gate compiles the
/// LearningCore package only), so each replay spells out the archive-mutation
/// half of its site under a comment naming the site; the DB half is the real
/// LearningDbWriter/DAO code the site calls. The iOS site semantics differ
/// from the Kotlin ones in two pinned places: the ledger appends and
/// truncates without re-sorting (arrival order), and save() replaces a
/// session in place (a continued session keeps its list position instead of
/// moving to the end).
final class LearningCutoverFlowTests: XCTestCase {
    private var db: SqlDatabase!
    private let now = 812_700_000.0

    override func setUpWithError() throws {
        db = try LearningDatabase.openInMemory()
    }

    override func tearDown() {
        db?.close()
        db = nil
    }

    // MARK: helpers

    private func export() throws -> Archive { try ArchiveMapper.export(db) }

    private func assertExportEquals(_ state: Archive,
                                    file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertEqual(try state.encoded(), try export().encoded(),
                       "export(DB) must equal the in-memory archive the engines produced",
                       file: file, line: line)
    }

    /// The startup load, as LearningStore's database mode does it: one-shot
    /// import of the migrated archive; the store's recovery has already run.
    private func startFrom(_ archive: Archive) throws -> Archive {
        try ArchiveMapper.import(archive, db)
        return try export()
    }

    /// Archive has no memberwise init; fixtures configure a fresh struct.
    private func fixture(_ configure: (inout Archive) -> Void) -> Archive {
        var archive = Archive()
        configure(&archive)
        return archive
    }

    /// The site's two halves for removeExpression (engine on the working
    /// copy, then the mirror deleting every row visible to the language).
    private func removeExpression(_ state: inout Archive, _ text: String, _ languageID: String) throws {
        RecallEngine.remove(&state, text: text)
        try LearningDbWriter.expressionRemoved(db, text, languageID)
    }

    // MARK: flow 1 — saveLookupExpression

    func testLookupSaveFlowKeepsExportEqualToTheArchiveAndAdoptsLegacyRows() throws {
        var state = startFrom(fixture {
            $0.preferences.learningLanguageID = "es"
            $0.expressions = [ExpressionState(text: "hola", meaning: "", source: "lesson",
                                              lessonID: "es-foundations-u1-ser",
                                              lastSeen: 5.0, dueAt: 6.0)]
        })

        /// LearningStore.saveLookupExpression's two halves: find the affected
        /// entry first (replace in place or append last), run the engine, then
        /// persist that one post-engine state.
        func runSaveLookup(text: String, meaning: String) throws {
            let key = RecallEngine.normalizeKey(text)
            let index = (state.expressions ?? []).firstIndex {
                RecallEngine.normalizeKey($0.text) == key && ($0.languageID == "es" || $0.languageID == nil)
            }
            XCTAssertTrue(RecallEngine.saveLookup(&state, text: text, meaning: meaning, now: now))
            let list = state.expressions!
            let affected: ExpressionState
            if let index, list.indices.contains(index) { affected = list[index] } else { affected = list.last! }
            try LearningDbWriter.expressionUpserted(db, affected)
        }

        // adoption: the legacy NULL row is retargeted to es, its meaning filled
        // from the lookup, every other field kept from the legacy entry
        try runSaveLookup("Hola", "hello")
        try assertExportEquals(state)
        let adopted = state.expressions!.first!
        XCTAssertEqual("es", adopted.languageID)
        XCTAssertEqual("hello", adopted.meaning)
        XCTAssertEqual("lesson", adopted.source)
        XCTAssertEqual("es-foundations-u1-ser", adopted.lessonID)

        // exact match: the meaning is already set, a second lookup changes nothing
        try runSaveLookup("  HOLA ", "otra cosa")
        try assertExportEquals(state)

        // brand-new key: appended at the end
        try runSaveLookup("¿qué tal?", "how are you")
        try assertExportEquals(state)
        XCTAssertEqual("es", state.expressions!.last!.languageID)
        XCTAssertEqual("lookup", state.expressions!.last!.source)

        // the per-language notebook projection equals the DAO's
        let notebook = (state.expressions ?? [])
            .filter { $0.languageID == nil || $0.languageID == "es" }
            .sorted { $0.lastSeen > $1.lastSeen }
        XCTAssertEqual(notebook, try ExpressionsDao.byLanguage(db, "es").sorted { $0.lastSeen > $1.lastSeen })
        XCTAssertEqual(Int64(2), try db.query("SELECT COUNT(*) AS n FROM expressions").first!.long("n"))
    }

    // MARK: flow 2 — recordLessonAttempt, gradeRecall, removeExpression

    func testLessonAttemptFlowPersistsCountersLedgerAndSeededExpressions() throws {
        var state = startFrom(fixture { $0.preferences.learningLanguageID = "de" })
        let lesson = CourseRegistry.lesson("de-foundations-u1-sein")!
        XCTAssertFalse(lesson.expressions.isEmpty, "the fixture lesson must seed expressions")

        /// LearningStore.recordLessonAttempt's two halves: counters, the
        /// appended (post-cap) ledger mark, the seeding engine, then the
        /// seeded entries as ONE directed write.
        func runLessonAttempt(lessonID: String, at: Double) throws {
            var lessons = state.lessons ?? []
            let updated: LessonState
            if let i = lessons.firstIndex(where: { $0.lessonID == lessonID }) {
                lessons[i].attempts += 1
                lessons[i].lastSeen = at
                updated = lessons[i]
            } else {
                updated = LessonState(lessonID: lessonID, attempts: 1, lastSeen: at)
                lessons.append(updated)
            }
            state.lessons = lessons
            RecallEngine.seedFromLesson(&state, lesson: CourseRegistry.lesson(lessonID)!, now: at)
            var marks = state.completions ?? []
            marks.append(CompletionMark(kind: ProgressEngine.lesson,
                                        itemID: ProgressEngine.completionKey(lessonID),
                                        at: at, bestScore: 0, languageID: "de"))
            if marks.count > Archive.maximumCompletions { marks = Array(marks.suffix(Archive.maximumCompletions)) }
            state.completions = marks
            let seeds = (CourseRegistry.lesson(lessonID)!.expressions).compactMap { expression -> ExpressionState? in
                let key = RecallEngine.normalizeKey(expression.text)
                guard !key.isEmpty else { return nil }
                return (state.expressions ?? []).first {
                    RecallEngine.normalizeKey($0.text) == key && ($0.languageID == "de" || $0.languageID == nil)
                }
            }
            var seen = Set<String>()
            let unique = seeds.filter { seen.insert(RecallEngine.expressionKey($0.text, $0.languageID)).inserted }
            try LearningDbWriter.lessonAttempt(db, lesson: updated, marks: marks, seeds: unique)
        }

        try runLessonAttempt(lesson.id, at: now)
        try assertExportEquals(state)
        XCTAssertEqual(1, state.lessons!.first { $0.lessonID == lesson.id }!.attempts)
        XCTAssertEqual(lesson.expressions.count, state.expressions!.count)

        // a second attempt (later wall clock, like the real engine clock):
        // counters grow, seeding is idempotent, the ledger gains a mark
        try runLessonAttempt(lesson.id, at: now + 60)
        try assertExportEquals(state)
        XCTAssertEqual(2, state.lessons!.first { $0.lessonID == lesson.id }!.attempts)
        XCTAssertEqual(2, state.completions!.count)

        // gradeRecall: grade the first seeded expression, persist its post state
        let target = state.expressions!.first!
        let key = RecallEngine.normalizeKey(target.text)
        let index = (state.expressions ?? []).firstIndex {
            RecallEngine.normalizeKey($0.text) == key && ($0.languageID == "de" || $0.languageID == nil)
        }!
        RecallEngine.grade(&state, text: target.text, correct: true, now: now)
        try LearningDbWriter.expressionUpserted(db, state.expressions![index])
        try assertExportEquals(state)

        // due() over the archive equals the DAO's due projection (same members;
        // both sides ordered by dueAt — Swift's sort is not documented stable
        // on ties, so membership is the pinned half here)
        let engineDue = RecallEngine.due(state, now: now + 60)
        let dbDue = try ExpressionsDao.due(db, "de", now + 60)
        XCTAssertEqual(engineDue.map(\.text).sorted(), dbDue.map(\.text).sorted())
        XCTAssertEqual(engineDue.map(\.dueAt), engineDue.map(\.dueAt).sorted())

        // removeExpression: the language row goes, export follows
        try removeExpression(&state, target.text, "de")
        try assertExportEquals(state)
    }

    // MARK: flow 3 — session lifecycle

    func testSessionLifecycleFlowKeepsExportAndTicketRowsInStep() throws {
        let oldID = "11111111-1111-4111-8111-111111111111"
        let liveID = "22222222-2222-4222-8222-222222222222"
        var oldSession = SessionRecord(languageID: "es", title: "Old")
        oldSession.id = UUID(uuidString: oldID)!
        oldSession.startedAt = Date(timeIntervalSinceReferenceDate: 100)
        oldSession.endedAt = Date(timeIntervalSinceReferenceDate: 200)
        oldSession.endReason = "Ended by you"
        oldSession.usageFinal = true
        oldSession.append(Fragment(id: "old-f1", speaker: .user, text: "hola que tal",
                                   startMS: 0, endMS: 1, receivedAt: Date(timeIntervalSinceReferenceDate: 100)))
        var state = startFrom(fixture {
            $0.preferences.learningLanguageID = "es"
            $0.sessions = [oldSession]
        })

        /// LearningStore.save's two halves: the site replaces the session in
        /// place (or appends), so the row keeps its rowid position — the iOS
        /// mirror of the Kotlin move-to-end save.
        func runSave(_ record: SessionRecord) throws {
            if let i = state.sessions.firstIndex(where: { $0.id == record.id }) {
                state.sessions[i] = record
            } else {
                state.sessions.append(record)
            }
            try LearningDbWriter.session(db, record, moveToEnd: false)
        }

        // newSession: an empty scenario session
        var live = SessionRecord(languageID: "es", title: "Café")
        live.id = UUID(uuidString: liveID)!
        live.startedAt = Date(timeIntervalSinceReferenceDate: 300)
        live.mode = "scenario"
        live.scenarioID = "order-coffee"
        try runSave(live)
        try assertExportEquals(state)

        // turn appends (the coordinator's save): user then assistant, usage counters
        live = state.sessions.first { $0.id == liveID }!
        live.append(Fragment(id: "f1", speaker: .user, text: "un café con leche",
                             startMS: 0, endMS: 1, receivedAt: Date(timeIntervalSinceReferenceDate: 301)))
        live.inputTokens = 12; live.outputTokens = 3
        try runSave(live)
        try assertExportEquals(state)
        live = state.sessions.first { $0.id == liveID }!
        live.append(Fragment(id: "f2", speaker: .assistant, text: "¿algo más?",
                             startMS: 2, endMS: 3, receivedAt: Date(timeIntervalSinceReferenceDate: 302)))
        live.outputTokens = 30
        try runSave(live)
        try assertExportEquals(state)
        XCTAssertEqual(liveID, state.sessions.last!.id.uuidString,
                       "the live session is the archive's last (it arrived last)")

        // end (the coordinator's end): endedAt/usageFinal/endReason
        live = state.sessions.first { $0.id == liveID }!
        live.endedAt = Date(timeIntervalSinceReferenceDate: 400)
        live.usageFinal = false
        live.endReason = "Ended by you"
        try runSave(live)
        try assertExportEquals(state)

        // the startup recovery's ticket form is the schema's only ledger
        // writer on iOS: a recovered session with an unassessed passage
        try AssessmentTicketsDao.upsert(db, AssessmentTicketRow(liveID, attempts: 0, lastAttemptAt: nil))

        // the scenario review lands on the stored session (the review sheet)
        live = state.sessions.first { $0.id == liveID }!
        live.scenarioReview = ScenarioReview(createdAt: 401.0, strengths: ["Natural order."],
            errors: [ReviewedError(quote: "un café", correction: "un café, por favor",
                                   kind: "pragmatic", note: "Softer request")],
            keyPhrases: [], retryFocus: "Polite requests")
        try runSave(live)
        try assertExportEquals(state)
        XCTAssertEqual("Polite requests", try export().sessions.first { $0.id == liveID }!.scenarioReview?.retryFocus)
        XCTAssertEqual([AssessmentTicketRow(liveID, attempts: 0, lastAttemptAt: nil)],
                       try AssessmentTicketsDao.all(db))

        // resume-and-continue (resume + append → save): the OLD session is
        // updated from the middle of the list — the in-place upsert keeps its
        // rowid, so export order stays equal to archive order
        var continued = try export().sessions.first { $0.id == oldID }!
        continued.append(Fragment(id: "old-f2", speaker: .user, text: "otra ronda",
                                  startMS: 4, endMS: 5, receivedAt: Date(timeIntervalSinceReferenceDate: 500)))
        try runSave(continued)
        try assertExportEquals(state)
        XCTAssertEqual(oldID, state.sessions.first!.id.uuidString,
                       "the continued session keeps its archive position (in-place replace)")

        // deleteSession: the row (fragments by cascade) and its recovery
        // ticket go
        state.sessions.removeAll { $0.id == liveID }
        try LearningDbWriter.sessionDeleted(db, liveID, ticketRemoved: true)
        try assertExportEquals(state)
        XCTAssertEqual(0, try AssessmentTicketsDao.all(db).count)
        XCTAssertEqual(Int64(0), try db.query("SELECT COUNT(*) AS n FROM fragments WHERE session_id = ?",
                                              args: [liveID]).first!.long("n"))
    }

    // MARK: flow 4 — language switch + notebook isolation

    func testLanguageSwitchIsolatesTheNotebookExceptLegacyRows() throws {
        var state = startFrom(fixture {
            $0.preferences.learningLanguageID = "es"
            $0.expressions = [
                ExpressionState(text: "perro", meaning: "dog", source: "lookup", lastSeen: 1.0, dueAt: 2.0, languageID: "es"),
                ExpressionState(text: "chien", meaning: "chien", source: "lookup", lastSeen: 2.0, dueAt: 3.0, languageID: "fr"),
                ExpressionState(text: "hola", meaning: "", source: "lesson", lessonID: "es-foundations-u1-ser",
                                lastSeen: 3.0, dueAt: 4.0),
            ]
        })

        func notebook(_ target: Archive, _ languageID: String) -> [ExpressionState] {
            (target.expressions ?? []).filter { $0.languageID == nil || $0.languageID == languageID }
                .sorted { $0.lastSeen > $1.lastSeen }
        }

        // es: own row + the legacy row; fr rows invisible
        XCTAssertEqual(notebook(state, "es"), try ExpressionsDao.byLanguage(db, "es").sorted { $0.lastSeen > $1.lastSeen })
        XCTAssertFalse(notebook(state, "es").contains { $0.text == "chien" })
        XCTAssertTrue(notebook(state, "es").contains { $0.text == "hola" })

        // switch (selectLanguage/updatePreferences' mirror)
        state.preferences.learningLanguageID = "fr"
        try LearningDbWriter.preferences(db, state.preferences)
        try assertExportEquals(state)

        // fr: own row + the legacy row; es rows invisible; due counts scoped
        XCTAssertEqual(notebook(state, "fr"), try ExpressionsDao.byLanguage(db, "fr").sorted { $0.lastSeen > $1.lastSeen })
        XCTAssertFalse(notebook(state, "fr").contains { $0.text == "perro" })
        XCTAssertTrue(notebook(state, "fr").contains { $0.text == "hola" })
        XCTAssertEqual(RecallEngine.due(state, now: now).map(\.text).sorted(),
                       try ExpressionsDao.due(db, "fr", now).map(\.text).sorted())

        // removing in fr takes the fr row AND the shared legacy row, per engine
        try removeExpression(&state, "hola", "fr")
        try assertExportEquals(state)
        XCTAssertFalse(notebook(state, "fr").contains { $0.text == "hola" })
        XCTAssertEqual(Int64(0), try db.query("SELECT COUNT(*) AS n FROM expressions WHERE norm_key = 'hola'")
            .first!.long("n"))
    }

    // MARK: flow 5 — mistakes, remote pull, wipe, restore

    func testMistakeMemoryProgressPullAndWipeStayConsistent() throws {
        var state = startFrom(fixture { $0.preferences.learningLanguageID = "es" })
        let lesson = CourseRegistry.lesson("es-foundations-u1-ser")!

        /// recordExerciseMistake's two halves: counts re-sort the whole memory
        /// (rewrite, not upsert).
        func runMistake(_ prompt: String) throws {
            let skills = lesson.skills.filter { SkillsData.categories.keys.contains($0) }
            XCTAssertFalse(skills.isEmpty)
            var errorsByKey: [String: SkillError] =
                Dictionary(uniqueKeysWithValues: (state.errors ?? []).map { ($0.skillID, $0) })
            for skill in skills {
                let current = errorsByKey[skill]
                errorsByKey[skill] = SkillError(skillID: skill, count: (current?.count ?? 0) + 1,
                                                lastAt: now, lastPrompt: String(prompt.prefix(120)))
            }
            let merged = errorsByKey.values.sorted { $0.count > $1.count }
            let memory = merged.isEmpty ? [] : Array(merged.prefix(Archive.maximumErrors))
            state.errors = memory.isEmpty ? nil : memory
            try LearningDbWriter.skillErrors(db, memory)
        }
        try runMistake("dijo X")
        try runMistake("dijo Y otra vez")
        try assertExportEquals(state)

        // syncProgress' pull (ProgressSync.mergeRemote): lessons update/append,
        // unseen marks append — mirrored by upserts + insert-or-ignore adds,
        // computed as the site's diff (changed/appended only)
        let lessonsBefore = state.lessons ?? []
        let markCountBefore = state.completions?.count ?? 0
        state = ProgressSync.mergeRemote(archive: state, rows: [
            RemoteProgress(kind: ProgressEngine.lesson, itemID: lesson.id, firstAt: now - 10, lastAt: now,
                           attempts: 3, bestScore: 0, language: "es"),
            RemoteProgress(kind: ProgressEngine.listening, itemID: "es-listening-1", firstAt: now - 5, lastAt: now,
                           attempts: 1, bestScore: 80, language: "es"),
        ], languageID: "es")
        let changedLessons = (state.lessons ?? []).enumerated()
            .filter { $0.offset >= lessonsBefore.count || $0.element != lessonsBefore[$0.offset] }
            .map { $0.element }
        let appendedMarks = Array((state.completions ?? []).dropFirst(markCountBefore))
        try LearningDbWriter.progressMerged(db, lessons: changedLessons, marks: appendedMarks)
        try assertExportEquals(state)
        XCTAssertEqual(3, state.lessons!.first { $0.lessonID == lesson.id }!.attempts)

        // deleteAll: sessions + ledger + hidden words; the rest stays
        var stored = state.preferences
        stored.hiddenWords = []
        state.sessions = []
        state.preferences = stored
        try LearningDbWriter.learningDataDeleted(db, stored)
        try assertExportEquals(state)
        XCTAssertEqual(Int64(0), try db.query("SELECT COUNT(*) AS n FROM sessions").first!.long("n"))
        XCTAssertEqual(1, state.lessons!.count, "lessons are not this site's learning data")

        // importData (restore path): the merger's projection becomes the new
        // working copy and the invariant survives the swap
        var incoming = Archive()
        incoming.preferences.learningLanguageID = "it"
        var imported = SessionRecord(languageID: "it", title: "Ciao")
        imported.id = UUID(uuidString: "33333333-3333-4333-8333-333333333333")!
        imported.startedAt = Date(timeIntervalSinceReferenceDate: 10)
        imported.endedAt = Date(timeIntervalSinceReferenceDate: 20)
        imported.append(Fragment(id: "it-f1", speaker: .user, text: "ciao",
                                 startMS: 0, endMS: 1, receivedAt: Date(timeIntervalSinceReferenceDate: 10)))
        incoming.sessions = [imported]
        let restored = try LearningDbWriter.archiveRestored(db, incoming)
        state = restored
        try assertExportEquals(state)
        // the merge keeps the CURRENT preferences (the pre-cutover iOS
        // importData behaved identically) while the incoming session lands
        XCTAssertEqual("es", try export().preferences.learningLanguageID)
        XCTAssertEqual(1, try export().sessions.count, "the merged archive keeps both worlds' sessions")
    }

    // MARK: flow 6 — recordCompletion ordering

    func testCompletionLedgerRewriteKeepsTheArchivedArrivalOrder() throws {
        var state = startFrom(fixture {
            $0.preferences.learningLanguageID = "es"
            $0.completions = [CompletionMark(kind: ProgressEngine.listening, itemID: "es-a",
                                             at: 100.0, bestScore: 50, languageID: "es")]
        })

        /// recordCompletion's two halves: the iOS ledger appends and truncates
        /// (arrival order — unlike the Kotlin site, which re-sorts by `at`;
        /// each platform's flow test pins its own site).
        func runCompletion(kind: String, itemID: String, at: Double, score: Int = 0) throws {
            let mark = CompletionMark(kind: kind, itemID: ProgressEngine.completionKey(itemID),
                                      at: at, bestScore: score, languageID: "es")
            var marks = state.completions ?? []
            marks.append(mark)
            if marks.count > Archive.maximumCompletions { marks = Array(marks.suffix(Archive.maximumCompletions)) }
            state.completions = marks
            try LearningDbWriter.completions(db, marks)
        }

        try runCompletion(kind: ProgressEngine.listening, itemID: "es-b", at: 200.0)
        try assertExportEquals(state)
        XCTAssertEqual(200.0, try export().completions!.last!.at, "the newest mark arrives last on iOS")
        try runCompletion(kind: ProgressEngine.reading, itemID: "es-c", at: 150.0)
        try runCompletion(kind: ProgressEngine.lesson, itemID: "es-d", at: 300.0)
        try assertExportEquals(state)
        XCTAssertEqual([100.0, 200.0, 150.0, 300.0], try export().completions!.map(\.at))
    }
}
