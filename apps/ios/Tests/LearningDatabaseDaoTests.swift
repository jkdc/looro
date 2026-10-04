import XCTest
@testable import LearningCore

/// DAO semantics on a real SQLite (GRDB in-memory): upsert identities, the
/// expressions cap and legacy adoption, the completions dedup policy, the
/// skill-error top-100 trim and the usage-outbox epoch conversion — the Swift
/// mirror of Android's `LearningDbDaoTest`.
final class LearningDatabaseDaoTests: XCTestCase {
    private var db: SqlDatabase!

    override func setUpWithError() throws {
        db = try LearningDatabase.openInMemory()
    }

    override func tearDown() {
        db?.close()
        db = nil
    }

    func testPreferencesRoundTripAllFourteenFieldsNullableOnesIncluded() throws {
        XCTAssertNil(try PreferencesDao.get(db))
        var populated = Preferences()
        populated.learningLanguageID = "es"
        populated.meaningVisible = false
        populated.meaningLanguage = "Spanish"
        populated.sessionMinutes = 25
        populated.hiddenWords = ["es|hola|hello", "fr|chat|cat"]
        populated.interests = "cooking, travel"
        populated.hasOnboarded = true
        populated.aiConsentVersion = 2
        populated.slowSpeech = false
        populated.listeningPractice = true
        populated.speechRate = 0.85
        populated.usageMetricsEnabled = true
        populated.sttEngine = "system"
        populated.pronunciationEnabled = false
        try PreferencesDao.put(db, populated)
        let stored = try PreferencesDao.get(db)
        XCTAssertEqual("es", stored?.learningLanguageID)
        XCTAssertEqual(false, stored?.meaningVisible)
        XCTAssertEqual("Spanish", stored?.meaningLanguage)
        XCTAssertEqual(25, stored?.sessionMinutes)
        XCTAssertEqual(["es|hola|hello", "fr|chat|cat"], stored?.hiddenWords)
        XCTAssertEqual("cooking, travel", stored?.interests)
        XCTAssertEqual(true, stored?.hasOnboarded)
        XCTAssertEqual(2, stored?.aiConsentVersion)
        XCTAssertEqual(false, stored?.slowSpeech)
        XCTAssertEqual(true, stored?.listeningPractice)
        XCTAssertEqual(0.85, stored?.speechRate)
        XCTAssertEqual(true, stored?.usageMetricsEnabled)
        XCTAssertEqual("system", stored?.sttEngine)
        XCTAssertEqual(false, stored?.pronunciationEnabled)
        // every legacy-nullable field NULL, exactly what a v1 fresh archive decodes to
        try PreferencesDao.put(db, Preferences())
        let legacy = try PreferencesDao.get(db)
        XCTAssertEqual(Preferences().learningLanguageID, legacy?.learningLanguageID)
        XCTAssertEqual(Preferences().meaningVisible, legacy?.meaningVisible)
        XCTAssertNil(legacy?.aiConsentVersion)
        XCTAssertNil(legacy?.slowSpeech)
        XCTAssertNil(legacy?.listeningPractice)
        XCTAssertNil(legacy?.speechRate)
        XCTAssertNil(legacy?.usageMetricsEnabled)
        XCTAssertNil(legacy?.sttEngine)
        XCTAssertNil(legacy?.pronunciationEnabled)
        XCTAssertEqual(Int64(1), try count("preferences"))
    }

    func testFragmentsReplaceBySessionRewritesSortIndexesInsideOneTransaction() throws {
        _ = try db.insert("sessions", ["id": "s1", "language_id": "es", "started_at": 0.0, "title": "Hola"])
        var a = Fragment(speaker: .user, text: "hola", startMS: 0, endMS: 10)
        a.id = "a"
        var b = Fragment(speaker: .assistant, text: "¿qué tal?", startMS: 20, endMS: 30)
        b.id = "b"
        b.previousTexts = ["hola"]
        b.revision = 1
        b.typed = true
        try FragmentsDao.replaceBySession(db, "s1", [a, b])
        XCTAssertEqual(["a", "b"], try FragmentsDao.bySession(db, "s1").map(\.id))
        XCTAssertEqual([["hola"], []], try FragmentsDao.bySession(db, "s1").map(\.previousTexts))
        XCTAssertEqual([0, 1], try FragmentsDao.bySession(db, "s1").map(\.revision))
        XCTAssertEqual([false, true], try FragmentsDao.bySession(db, "s1").map(\.typed))
        // rewrite renumbers sort_index from the list position and drops stale rows
        try FragmentsDao.replaceBySession(db, "s1", [b, a])
        XCTAssertEqual(["b", "a"], try FragmentsDao.bySession(db, "s1").map(\.id))
        XCTAssertEqual(Int64(0), try db.query("SELECT COUNT(*) AS n FROM fragments WHERE sort_index = 2")
            .first!.long("n"))
        try FragmentsDao.replaceBySession(db, "s1", [])
        XCTAssertEqual(Int64(0), try count("fragments"))
    }

    func testSessionUpsertUpdatesInPlaceAndNeverTouchesFragments() throws {
        let sessionID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        _ = try db.insert("sessions", ["id": sessionID, "language_id": "es", "started_at": 0.0,
                                       "title": "Hola", "provider_id": "p1", "ended_at": 10.0,
                                       "end_reason": "done"])
        var fragment = Fragment(speaker: .user, text: "hola", startMS: 0, endMS: 10)
        fragment.id = "f1"
        try FragmentsDao.replaceBySession(db, sessionID, [fragment])
        // a session-end style update that also CLEARS nullable columns
        var updated = try SessionsDao.all(db).first!
        updated.providerID = nil
        updated.endedAt = nil
        updated.endReason = nil
        updated.inputTokens = 42
        updated.outputTokens = 7
        updated.searchCalls = 1
        updated.usageFinal = true
        updated.title = "Cambiado"
        try SessionsDao.upsert(db, updated)
        let stored = try SessionsDao.all(db)
        XCTAssertEqual(1, stored.count)
        let s = stored.first!
        XCTAssertEqual(sessionID, s.id.uuidString.lowercased())
        XCTAssertEqual("Cambiado", s.title)
        XCTAssertNil(s.providerID)
        XCTAssertNil(s.endedAt)
        XCTAssertNil(s.endReason)
        XCTAssertEqual(42, s.inputTokens)
        XCTAssertEqual(7, s.outputTokens)
        XCTAssertEqual(1, s.searchCalls)
        XCTAssertTrue(s.usageFinal)
        // INSERT OR REPLACE would have cascade-deleted this row
        XCTAssertEqual(["f1"], s.fragments.map(\.id))
        XCTAssertNil(try db.query("SELECT provider_id FROM sessions WHERE id = ?", args: [sessionID])
            .first!["provider_id"])
    }

    func testExpressionsUpsertNormalizesTheKeyAndReplacesInPlace() throws {
        let expression = ExpressionState(text: "  Hola ", meaning: "hello", source: "lookup", dueAt: 5.0,
                                         languageID: "es")
        XCTAssertTrue(try ExpressionsDao.upsert(db, expression))
        XCTAssertEqual("hola", try db.query("SELECT norm_key FROM expressions").first!.text("norm_key"))
        let rowIdBefore = try db.query("SELECT rowid AS rid FROM expressions").first!.long("rid")
        XCTAssertTrue(try ExpressionsDao.upsert(db, ExpressionState(text: "Hola", meaning: "hello",
                                                                    source: "lookup", bars: 2, dueAt: 5.0,
                                                                    languageID: "es")))
        let rows = try ExpressionsDao.all(db)
        XCTAssertEqual(1, rows.count)
        XCTAssertEqual("Hola", rows.first?.text)
        XCTAssertEqual(2, rows.first?.bars)
        // updated in place, never REPLACE-d: the rowid (export order) is stable
        XCTAssertEqual(rowIdBefore, try db.query("SELECT rowid AS rid FROM expressions").first!.long("rid"))
    }

    func testExpressionsUpsertAdoptsALegacyNullLanguageRowInPlace() throws {
        let legacy = ExpressionState(text: "hola", meaning: "hello", source: "lookup", bars: 2, attempts: 3,
                                     lastSeen: 10.0, dueAt: 20.0, languageID: nil)
        XCTAssertTrue(try ExpressionsDao.upsert(db, legacy))
        let legacyRowId = try db.query("SELECT rowid AS rid FROM expressions").first!.long("rid")
        // what saveLookup hands over after adopting: the language retargeted and a
        // fresh lookup state — the legacy row's progress must survive untouched
        let incoming = ExpressionState(text: "hola", meaning: "hi", source: "lookup", bars: 0, attempts: 0,
                                       lastSeen: 0.0, dueAt: 500.0, languageID: "es")
        XCTAssertTrue(try ExpressionsDao.upsert(db, incoming))
        let rows = try ExpressionsDao.all(db)
        XCTAssertEqual(1, rows.count)
        let adopted = rows.first!
        XCTAssertEqual("es", adopted.languageID)
        // engine-faithful: only the language (and a blank meaning) change
        XCTAssertEqual(2, adopted.bars)
        XCTAssertEqual(3, adopted.attempts)
        XCTAssertEqual(10.0, adopted.lastSeen)
        XCTAssertEqual(20.0, adopted.dueAt)
        XCTAssertEqual("hello", adopted.meaning)
        // adopted in place, not duplicated and reinserted
        XCTAssertEqual(legacyRowId, try db.query("SELECT rowid AS rid FROM expressions").first!.long("rid"))
    }

    func testExpressionsAdoptionFillsOnlyABlankLegacyMeaning() throws {
        XCTAssertTrue(try ExpressionsDao.upsert(db, ExpressionState(text: "hola", meaning: "", source: "lookup",
                                                                    bars: 1, dueAt: 5.0, languageID: nil)))
        XCTAssertTrue(try ExpressionsDao.upsert(db, ExpressionState(text: "hola", meaning: "hello",
                                                                    source: "lookup", dueAt: 9.0,
                                                                    languageID: "es")))
        let adopted = try ExpressionsDao.all(db).first!
        XCTAssertEqual("hello", adopted.meaning) // blank legacy meaning takes the incoming one
        XCTAssertEqual(1, adopted.bars)          // legacy scheduling still wins
        XCTAssertEqual(5.0, adopted.dueAt)
    }

    func testExpressionsAdoptionCollapsesDuplicateLegacyRowsIntoOne() throws {
        // the PK keeps NULLs distinct and cannot stop duplicate legacy rows
        _ = try db.insert("expressions", [
            "norm_key": "hola", "language_id": nil, "text": "hola", "meaning": "",
            "source": "lookup", "bars": 1, "attempts": 1, "last_seen": 5.0, "due_at": 10.0])
        _ = try db.insert("expressions", [
            "norm_key": "hola", "language_id": nil, "text": "Hola", "meaning": "hello",
            "source": "lookup", "bars": 2, "attempts": 3, "last_seen": 10.0, "due_at": 20.0,
            "lesson_id": "l1"])
        XCTAssertTrue(try ExpressionsDao.upsert(db, ExpressionState(text: "hola", meaning: "hi",
                                                                    source: "lookup", bars: 0, dueAt: 500.0,
                                                                    languageID: "es")))
        let merged = try ExpressionsDao.all(db)
        XCTAssertEqual(1, merged.count)
        // archive merge semantics across the collapsed rows, engine semantics vs the incoming
        XCTAssertEqual("es", merged.first?.languageID)
        XCTAssertEqual("hola", merged.first?.text)     // kept row (rowid order) wins
        XCTAssertEqual(2, merged.first?.bars)
        XCTAssertEqual(3, merged.first?.attempts)
        XCTAssertEqual(10.0, merged.first?.lastSeen)
        XCTAssertEqual(20.0, merged.first?.dueAt)
        XCTAssertEqual("hello", merged.first?.meaning) // first non-blank meaning
        XCTAssertEqual("l1", merged.first?.lessonID)   // first non-null lesson
    }

    func testImportedLegacyAndLanguageExpressionsCoexistWithoutAdoption() throws {
        let legacy = ExpressionState(text: "hola", meaning: "hello", source: "lookup", dueAt: 5.0,
                                     languageID: nil)
        let spanish = ExpressionState(text: "hola", meaning: "hello", source: "lookup", bars: 1, dueAt: 6.0,
                                      languageID: "es")
        XCTAssertTrue(try ExpressionsDao.upsert(db, legacy, adoptLegacy: false))
        XCTAssertTrue(try ExpressionsDao.upsert(db, spanish, adoptLegacy: false))
        let languages = try db.query("SELECT language_id FROM expressions WHERE norm_key = 'hola'")
            .map { $0.textOrNull("language_id") }
        XCTAssertEqual([nil, "es"], languages)
    }

    func testExpressionsCapSkipsNewKeysAtMaximumButStillUpdatesExisting() throws {
        for i in 0..<2_000 {
            _ = try db.insert("expressions", [
                "norm_key": "k\(i)", "language_id": "es", "text": "k\(i)", "meaning": "m",
                "source": "lesson", "lesson_id": "l", "due_at": 0.0])
        }
        XCTAssertTrue(try ExpressionsDao.upsert(db, ExpressionState(text: "k7", meaning: "m", source: "lesson",
                                                                    lessonID: "l", dueAt: 1.0,
                                                                    languageID: "es")))
        XCTAssertFalse(try ExpressionsDao.upsert(db, ExpressionState(text: "new", meaning: "m",
                                                                     source: "lookup", dueAt: 1.0,
                                                                     languageID: "es")))
        XCTAssertEqual(Int64(2_000), try count("expressions"))
        XCTAssertNil(try db.query("SELECT norm_key FROM expressions WHERE norm_key = 'new'").first)
    }

    func testDueOrdersOldestDueFirstAndKeepsLegacyRowsVisible() throws {
        _ = try db.insert("expressions", [
            "norm_key": "late", "language_id": "es", "text": "late", "meaning": "m", "source": "lookup",
            "bars": 1, "due_at": 100.0])
        _ = try db.insert("expressions", [
            "norm_key": "early", "language_id": "es", "text": "early", "meaning": "m", "source": "lookup",
            "bars": 2, "due_at": 50.0])
        _ = try db.insert("expressions", [
            "norm_key": "legacy", "language_id": nil, "text": "legacy", "meaning": "m", "source": "lookup",
            "bars": 0, "due_at": 10.0])
        _ = try db.insert("expressions", [
            "norm_key": "graduated", "language_id": "es", "text": "graduated", "meaning": "m",
            "source": "lesson", "lesson_id": "l", "bars": 3, "due_at": 1.0])
        _ = try db.insert("expressions", [
            "norm_key": "future", "language_id": "fr", "text": "future", "meaning": "m", "source": "lookup",
            "due_at": 1.0])
        XCTAssertEqual(["legacy", "early", "late"],
                       try ExpressionsDao.due(db, "es", 200.0).map(\.text))
        XCTAssertEqual(["legacy"], try ExpressionsDao.due(db, "fr", 200.0).map(\.text))
    }

    func testCompletionsIgnoreDuplicateInstantsAndLanguageStaysOutOfTheKey() throws {
        let mark = CompletionMark(kind: "lesson", itemID: "es-foundations-u1-ser", at: 100.0, bestScore: 90,
                                  languageID: "es")
        XCTAssertTrue(try CompletionsDao.add(db, mark))
        // same instant, different language: distinct to the archive's completionKey,
        // one row here — the first stored (newest in merge order) wins
        XCTAssertFalse(try CompletionsDao.add(db, CompletionMark(kind: "lesson",
                                                                 itemID: "es-foundations-u1-ser", at: 100.0,
                                                                 bestScore: 50, languageID: "fr")))
        let stored = try CompletionsDao.all(db)
        XCTAssertEqual(1, stored.count)
        XCTAssertEqual("es", stored.first?.languageID)
        XCTAssertEqual(90, stored.first?.bestScore)
        // a genuinely different instant is its own row
        XCTAssertTrue(try CompletionsDao.add(db, CompletionMark(kind: "lesson",
                                                                itemID: "es-foundations-u1-ser", at: 200.0,
                                                                bestScore: 90, languageID: "es")))
        XCTAssertEqual(Int64(2), try count("completions"))
    }

    func testSkillErrorsReplacePerSkillAndTrimToTheTopHundredByCount() throws {
        try SkillErrorsDao.upsert(db, SkillError(skillID: "s1", count: 2, lastAt: 1.0, lastPrompt: "a"))
        try SkillErrorsDao.upsert(db, SkillError(skillID: "s1", count: 5, lastAt: 2.0, lastPrompt: "b"))
        var only = try SkillErrorsDao.all(db)
        XCTAssertEqual(1, only.count)
        XCTAssertEqual("b", only.first?.lastPrompt)
        XCTAssertEqual(5, only.first?.count)
        // 106 distinct skills: s1 (count 5, stored first) plus counts 0..104
        for i in 0..<105 {
            try SkillErrorsDao.upsert(db, SkillError(skillID: "skill\(i)", count: i, lastAt: 3.0,
                                                     lastPrompt: ""))
        }
        let errors = try SkillErrorsDao.all(db)
        XCTAssertEqual(100, errors.count)
        XCTAssertEqual("s1", errors.min(by: { $0.count < $1.count })?.skillID) // rowid breaks the count-5 tie
        XCTAssertFalse(errors.contains { $0.skillID == "skill5" })
        XCTAssertTrue(errors.contains { $0.skillID == "skill104" })
    }

    func testUsageOutboxConvertsUnixMillisToAppleSecondsAndBack() throws {
        let props: [String: UsageProp] = ["bucket": .int(3)]
        let first = try UsageOutboxDao.enqueue(db, "session_started", 1_727_859_600_000)
        let second = try UsageOutboxDao.enqueue(db, "app_open", 1_727_859_600_123, props)
        let stored = try db.query("SELECT at FROM usage_outbox WHERE rowid = ?", args: [second]).first!
        XCTAssertEqual(1_727_859_600.123 - UsageOutboxDao.appleEpochUnixSeconds, stored.real("at"))
        let pending = try UsageOutboxDao.pending(db)
        XCTAssertEqual([first, second], pending.map(\.rowId)) // oldest first
        XCTAssertEqual(Int64(1_727_859_600_000), pending.first?.event.at)
        XCTAssertEqual(Int64(1_727_859_600_123), pending.last?.event.at)
        XCTAssertEqual("app_open", pending.last?.event.name)
        XCTAssertEqual(3, intProp(pending.last?.event.props, "bucket"))
        XCTAssertEqual("00000000-0000-0000-0000-000000000001",
                       pending.first?.event.id.uuidString) // the rowid becomes the event id
        try UsageOutboxDao.markDispatched(db, [first])
        XCTAssertEqual([second], try UsageOutboxDao.pending(db).map(\.rowId))
        XCTAssertEqual(Int64(2), try count("usage_outbox"))
    }

    func testAssessmentTicketsUpsertPerSessionGetAndClear() throws {
        try AssessmentTicketsDao.upsert(db, AssessmentTicketRow("s1", attempts: 0, lastAttemptAt: nil))
        try AssessmentTicketsDao.upsert(db, AssessmentTicketRow("s1", attempts: 1, lastAttemptAt: 10.0))
        try AssessmentTicketsDao.upsert(db, AssessmentTicketRow("s2", attempts: 0, lastAttemptAt: nil))
        let all = try AssessmentTicketsDao.all(db)
        XCTAssertEqual(2, all.count)
        let s1 = all.first { $0.sessionID == "s1" }
        XCTAssertEqual(1, s1?.attempts)
        XCTAssertEqual(10.0, s1?.lastAttemptAt)
        try AssessmentTicketsDao.clear(db)
        XCTAssertEqual(0, try AssessmentTicketsDao.all(db).count)
    }

    func testMetaPutGetAndMasteryRows() throws {
        XCTAssertNil(try MetaDao.get(db, "k"))
        try MetaDao.put(db, "k", "v1")
        try MetaDao.put(db, "k", "v2")
        XCTAssertEqual("v2", try MetaDao.get(db, "k"))
        try MetaDao.replaceMastery(db, ["grammar:articles": 2, "function:describe-people": 1])
        XCTAssertEqual(["grammar:articles": 2, "function:describe-people": 1], try MetaDao.mastery(db))
        // replace semantics: a re-import with fewer skills drops the stale rows
        try MetaDao.replaceMastery(db, ["grammar:articles": 3])
        XCTAssertEqual(["grammar:articles": 3], try MetaDao.mastery(db))
    }

    // MARK: helpers

    private func count(_ table: String) throws -> Int64 {
        return try db.query("SELECT COUNT(*) AS n FROM \(table)").first!.long("n")
    }

    private func intProp(_ props: [String: UsageProp]?, _ key: String) -> Int? {
        guard case .int(let value)? = props?[key] else { return nil }
        return value
    }
}
