import XCTest
@testable import LearningCore

/// DAO semantics on a real SQLite (GRDB in-memory): upsert identities, the
/// expressions cap and legacy adoption, the completions dedup policy, the
/// skill-error top-100 trim, the usage-outbox epoch conversion and its
/// install-scoped wire ids — the Swift
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

    func testExpressionsDeleteForLanguageIsExactAndLegacyStaysSelectable() throws {
        func seed(_ text: String, _ languageID: String?) throws {
            XCTAssertTrue(try ExpressionsDao.upsert(db, ExpressionState(text: text, meaning: "m",
                                                                        source: "lookup", dueAt: 1.0,
                                                                        languageID: languageID),
                                                    adoptLegacy: false))
        }
        try seed("perro", "es"); try seed("hola", "es"); try seed("chien", "fr"); try seed("bonjour", "fr")
        try seed("ciao", nil)
        // The language delete is EXACT (language_id = ?): es goes, fr and the
        // legacy NULL bucket stay — deleting one language never takes the
        // shared legacy entries along.
        XCTAssertEqual(Int64(2), try ExpressionsDao.deleteExpressionsForLanguage(db, languageID: "es"))
        XCTAssertEqual(["fr", "fr", nil],
                       try ExpressionsDao.all(db).map(\.languageID).sorted { ($0 ?? "~") < ($1 ?? "~") })
        // An unknown language is a no-op (0 rows affected).
        XCTAssertEqual(Int64(0), try ExpressionsDao.deleteExpressionsForLanguage(db, languageID: "de"))
        // The legacy bucket is its own delete (the dialog's selectable
        // "No language (legacy)" row) and never rode the language delete.
        XCTAssertEqual(Int64(1), try ExpressionsDao.deleteExpressionsLegacy(db))
        XCTAssertEqual(["fr", "fr"], try ExpressionsDao.all(db).map(\.languageID))
        XCTAssertEqual(Int64(0), try ExpressionsDao.deleteExpressionsLegacy(db))
    }

    func testExpressionStatsByLanguageListsOnlyLanguagesWithDataCountDescWithUtf8Bytes() throws {
        // Empty database: no languages to remove, no rows at all.
        XCTAssertTrue(try ExpressionsDao.expressionStatsByLanguage(db).isEmpty)
        func seed(_ text: String, _ meaning: String, _ languageID: String?) throws {
            XCTAssertTrue(try ExpressionsDao.upsert(db, ExpressionState(text: text, meaning: meaning,
                                                                        source: "lookup", dueAt: 1.0,
                                                                        languageID: languageID),
                                                    adoptLegacy: false))
        }
        // es: 2 rows, 8 + 12 bytes ("canción" carries the two-byte ó — UTF-8
        // BYTE lengths, not character counts); fr: 3 rows, 25 bytes; legacy:
        // 1 row, 6 bytes.
        try seed("perro", "dog", "es")
        try seed("canción", "song", "es")
        try seed("chien", "dog", "fr")
        try seed("chat", "cat", "fr")
        try seed("oiseau", "bird", "fr")
        try seed("ciao", "hi", nil)
        XCTAssertEqual(
            [
                ExpressionLanguageStats(languageID: "fr", count: 3, approxBytes: 25),
                ExpressionLanguageStats(languageID: "es", count: 2, approxBytes: 20),
                ExpressionLanguageStats(languageID: nil, count: 1, approxBytes: 6),
            ],
            try ExpressionsDao.expressionStatsByLanguage(db))
        // A language with no rows never appears ("it" was never seeded).
        XCTAssertEqual(Set(["fr", "es", nil]),
                       Set(try ExpressionsDao.expressionStatsByLanguage(db).map(\.languageID)))
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
        // the rowid rides the 12-hex tail of a GLOBALLY unique id: the free
        // groups carry the persisted meta 'install_id' (the server's
        // client-id unique index spans installs), dash-spliced 8+4; the
        // version/variant markers stay 4/8
        let uuidV4 = try! NSRegularExpression(
            pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        func matchesV4(_ id: String) -> Bool {
            let range = NSRange(id.startIndex..., in: id)
            return uuidV4.firstMatch(in: id, range: range) != nil
        }
        let id = pending.first!.event.id.uuidString.lowercased()
        XCTAssertTrue(matchesV4(id))
        let stored = try MetaDao.get(db, UsageOutboxDao.installIDKey) ?? ""
        let install = String(stored.filter(\.isHexDigit).prefix(12).lowercased())
        XCTAssertEqual("\(install.prefix(8))-\(install.suffix(4))-4000-8000-000000000001", id)
        // stable per (install, row): client retries replay the same id
        XCTAssertEqual(pending.map(\.event.id),
                       try UsageOutboxDao.pending(db).map(\.event.id))
        XCTAssertTrue(pending.allSatisfy { matchesV4($0.event.id.uuidString.lowercased()) })
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

    // MARK: conversation search (schema v2)

    /// The shared case-basic fixture's shape, seeded directly: two es
    /// sessions ("En el restaurante" older, "The verb ser" newer) and one fr
    /// session ("Commander au café"). Session ids are UUID strings — the DAO
    /// re-parses them.
    private func seedSearchFixtures() throws {
        func session(_ id: String, _ languageID: String, _ title: String,
                     _ startedAt: Double) throws {
            var record = SessionRecord(languageID: languageID, themeID: nil, title: title,
                                       lessonID: nil, scenarioID: nil)
            record.id = UUID(uuidString: id)!
            record.startedAt = Date(timeIntervalSinceReferenceDate: startedAt)
            try SessionsDao.upsert(db, record)
        }
        func fragment(_ id: String, _ sessionID: String, _ text: String) throws {
            var fragment = Fragment(speaker: .user, text: text, startMS: 0, endMS: 10)
            fragment.id = id
            try FragmentsDao.replaceBySession(db, sessionID, [fragment])
        }
        try session("00000000-0000-0000-0000-000000000001", "es", "En el restaurante", 812_573_760)
        try fragment("f1", "00000000-0000-0000-0000-000000000001",
                     "Hola de nuevo. Quisiera reservar una mesa para dos personas")
        try fragment("f2", "00000000-0000-0000-0000-000000000001",
                     "Quisiera reservar una mesa, por favor")
        try session("00000000-0000-0000-0000-000000000002", "es", "The verb ser", 812_620_000)
        try fragment("f3", "00000000-0000-0000-0000-000000000002", "Yo soy de Argentina")
        try session("00000000-0000-0000-0000-000000000003", "fr", "Commander au café", 812_490_000)
        try fragment("f4", "00000000-0000-0000-0000-000000000003",
                     "Je veux un café, s'il vous plaît")
    }

    func testSearchMatchesFragmentsFiltersByLanguageAndOrdersNewestFirst() throws {
        try seedSearchFixtures()
        // a word matching TWO fragments of ONE session: exactly one hit
        let reservar = try SearchDao.search(db, "reservar", languageID: "es")
        XCTAssertEqual(["00000000-0000-0000-0000-000000000001"],
                       reservar.map { $0.session.id.uuidString })
        XCTAssertTrue(reservar.first!.snippet.contains("«reservar"),
                      "the snippet wraps the match, got \(reservar.first!.snippet)")
        // a word from the fr conversation is invisible to es, found in fr
        // (accent-folded by the tokenizer)
        XCTAssertEqual(0, try SearchDao.search(db, "café", languageID: "es").count)
        XCTAssertEqual(["00000000-0000-0000-0000-000000000003"],
                       try SearchDao.search(db, "café", languageID: "fr")
                           .map { $0.session.id.uuidString })
        // a token present in both es sessions: newest conversation first
        XCTAssertEqual(["00000000-0000-0000-0000-000000000002",
                        "00000000-0000-0000-0000-000000000001"],
                       try SearchDao.search(db, "de", languageID: "es")
                           .map { $0.session.id.uuidString })
    }

    func testSearchLimitsPrefixMatchesAndNeverThrowsOnFtsSyntax() throws {
        try seedSearchFixtures()
        // LIMIT caps sessions, not fragment matches
        XCTAssertEqual(1, try SearchDao.search(db, "de", languageID: "es", limit: 1).count)
        XCTAssertEqual(0, try SearchDao.search(db, "de", languageID: "es", limit: 0).count)
        // prefix search
        XCTAssertEqual(1, try SearchDao.search(db, "reser", languageID: "es").count)
        // FTS4 operator characters cannot throw or widen the match: the
        // sanitizer strips them, and a trailing star defuses the keywords
        XCTAssertEqual(0, try SearchDao.search(db, "\"", languageID: "es").count)
        XCTAssertEqual(0, try SearchDao.search(db, "  ", languageID: "es").count)
        XCTAssertEqual(0, try SearchDao.search(db, "NEAR", languageID: "es").count)
        XCTAssertEqual(1, try SearchDao.search(db, "\"(reservar)\"", languageID: "es").count)
        // the OR keyword is defused (terms AND together; no fragment holds both)
        XCTAssertEqual(0, try SearchDao.search(db, "mesa OR fibra", languageID: "es").count)
        // the sanitizer's shape
        XCTAssertEqual("qué* tal* HO*", SearchDao.matchQuery("qué tal \"HO?\""))
        XCTAssertEqual("", SearchDao.matchQuery("  \"\"  "))
    }

    // MARK: archive_health heartbeat

    func testArchiveHealthClaimsTheDailySlotOncePerWindow() throws {
        let start: Int64 = 1_700_000_000_000
        // first launch: no meta row yet -> the claim returns the bucketed props
        // the caller enqueues as the durable usage_outbox row
        let props = try ArchiveHealthDao.claimIfDue(db, nowUnixMilliseconds: start,
                                                    dbBytes: 5 << 20, sessionCount: 7)!
        XCTAssertEqual("1-10mb", stringProp(props, "dbBytesBucket"))
        XCTAssertEqual("1-10", stringProp(props, "sessionsBucket"))
        XCTAssertEqual(String(start), try MetaDao.get(db, ArchiveHealth.lastHealthAtKey))
        // the claim itself never writes the outbox row — that is the caller's
        // single durable write
        XCTAssertEqual(Int64(0), try count("usage_outbox"))
        // inside the 24h cadence: no claim, no new marker
        XCTAssertNil(try ArchiveHealthDao.claimIfDue(
            db, nowUnixMilliseconds: start + ArchiveHealth.cadenceMilliseconds - 1,
            dbBytes: 5 << 20, sessionCount: 7))
        XCTAssertEqual(String(start), try MetaDao.get(db, ArchiveHealth.lastHealthAtKey))
        // past the cadence: claims again and moves the marker
        let next = start + ArchiveHealth.cadenceMilliseconds
        let second = try ArchiveHealthDao.claimIfDue(db, nowUnixMilliseconds: next,
                                                     dbBytes: 250 << 20, sessionCount: 500)!
        XCTAssertEqual(">=100mb", stringProp(second, "dbBytesBucket"))
        XCTAssertEqual(">200", stringProp(second, "sessionsBucket"))
        XCTAssertEqual(String(next), try MetaDao.get(db, ArchiveHealth.lastHealthAtKey))
        XCTAssertEqual(Int64(0), try count("usage_outbox"))
        // the event is in the closed catalog, and the cadence boundaries hold
        XCTAssertTrue(UsageEvents.all.contains(UsageEvents.archiveHealth))
        XCTAssertTrue(ArchiveHealth.isDue(lastAtMilliseconds: nil, nowMilliseconds: start))
        XCTAssertFalse(ArchiveHealth.isDue(lastAtMilliseconds: start,
                                           nowMilliseconds: start + ArchiveHealth.cadenceMilliseconds - 1))
        XCTAssertTrue(ArchiveHealth.isDue(lastAtMilliseconds: start,
                                          nowMilliseconds: start + ArchiveHealth.cadenceMilliseconds))
    }

    func testArchiveHealthHeartbeatLandsAsExactlyOneDurableRowPerWindow() throws {
        // the ConversationCoordinator site, replayed: claim the 24h slot, then
        // write the durable row — the ONLY record of the heartbeat (no
        // in-memory double-record; UsageReporter.flush drains it, tested in
        // UsageMetricsTests)
        let start: Int64 = 1_700_000_000_000
        let props = try ArchiveHealthDao.claimIfDue(db, nowUnixMilliseconds: start,
                                                    dbBytes: 5 << 20, sessionCount: 7)!
        _ = try UsageOutboxDao.enqueue(db, UsageEvents.archiveHealth, start, props)
        var pending = try UsageOutboxDao.pending(db)
        XCTAssertEqual(1, pending.count)
        XCTAssertEqual("archive_health", pending.first?.event.name)
        XCTAssertEqual(start, pending.first?.event.at)
        XCTAssertEqual("1-10mb", stringProp(pending.first?.event.props, "dbBytesBucket"))
        XCTAssertEqual("1-10", stringProp(pending.first?.event.props, "sessionsBucket"))
        // a close before the next flush loses nothing: the row is still
        // pending in a later run and drains then
        // inside the window: no claim -> no second row
        XCTAssertNil(try ArchiveHealthDao.claimIfDue(
            db, nowUnixMilliseconds: start + ArchiveHealth.cadenceMilliseconds - 1,
            dbBytes: 5 << 20, sessionCount: 7))
        XCTAssertEqual(Int64(1), try count("usage_outbox"))
        // next window: exactly one more row, both still undispatched (the
        // drain marks them only after a successful upload)
        let next = start + ArchiveHealth.cadenceMilliseconds
        let secondProps = try ArchiveHealthDao.claimIfDue(db, nowUnixMilliseconds: next,
                                                          dbBytes: 250 << 20, sessionCount: 500)!
        _ = try UsageOutboxDao.enqueue(db, UsageEvents.archiveHealth, next, secondProps)
        pending = try UsageOutboxDao.pending(db)
        XCTAssertEqual(2, pending.count)
        XCTAssertEqual(Int64(2), try count("usage_outbox"))
    }

    // MARK: helpers

    private func count(_ table: String) throws -> Int64 {
        return try db.query("SELECT COUNT(*) AS n FROM \(table)").first!.long("n")
    }

    private func stringProp(_ props: [String: UsageProp]?, _ key: String) -> String {
        guard case .string(let value)? = props?[key] else { return "" }
        return value
    }

    private func intProp(_ props: [String: UsageProp]?, _ key: String) -> Int? {
        guard case .int(let value)? = props?[key] else { return nil }
        return value
    }
}
