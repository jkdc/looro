import Foundation

/**
 * Row access helpers for the learning database — the Swift mirror of the
 * Kotlin `LearningDbDao.kt`. Values arrive typed by the backend (see
 * `SqlRow`): INTEGER reads back as Int64, REAL as Double, TEXT as String and
 * NULL as nil, so every column is narrowed at the DAO boundary.
 *
 * Contract for everything below: keyed writes are select-then-update/insert.
 * This layer NEVER uses INSERT OR REPLACE — REPLACE re-inserts the row (a
 * fresh rowid, which would make export order drift) and its internal DELETE
 * fires foreign-key cascades, so REPLACE-ing a session would silently wipe
 * its fragments. Rowids are therefore stable for the life of a row and
 * ORDER BY rowid reads back insertion order forever.
 */

// MARK: JSON columns

func decodeStringList(_ json: String) throws -> [String] {
    return try DatabaseJSON.decode([String].self, json)
}

func encodeStringList(_ value: [String]) throws -> String {
    return try DatabaseJSON.encode(value)
}

// MARK: UPDATE with NULL assignments

/// UPDATE with NULL assignments spelled as literals. `SqlDatabase.update`
/// rejects nil bind arguments, and updates must clear columns too (an ended
/// session going back to ended_at IS NULL), so absent values become
/// `column = NULL` while present ones bind. Column names come only from the
/// fixed per-table row builders in this file, never from user data.
private func updateRow(_ db: SqlDatabase, _ table: String, _ row: [String: Any?],
                       where whereClause: String, whereArgs: [Any?]) throws {
    var sets: [String] = []
    var args: [Any?] = []
    for (column, value) in row {
        if value == nil {
            sets.append("\(column) = NULL")
        } else {
            sets.append("\(column) = ?")
            args.append(value)
        }
    }
    _ = try db.update("UPDATE \(table) SET \(sets.joined(separator: ", ")) WHERE \(whereClause)",
                      args: args + whereArgs)
}

// MARK: PreferencesDao

public enum PreferencesDao {
    public static func put(_ db: SqlDatabase, _ p: Preferences) throws {
        let exists = try !db.query("SELECT 1 AS matched FROM preferences WHERE id = 1").isEmpty
        if exists {
            try updateRow(db, "preferences", row(p), where: "id = 1", whereArgs: [])
        } else {
            _ = try db.insert("preferences", row(p))
        }
    }

    /// nil when the learner has no preferences row yet (fresh database).
    public static func get(_ db: SqlDatabase) throws -> Preferences? {
        guard let row = try db.query("SELECT * FROM preferences WHERE id = 1").first else { return nil }
        var p = Preferences()
        p.learningLanguageID = row.text("learning_language_id")
        p.meaningVisible = row.bool("meaning_visible")
        p.meaningLanguage = row.text("meaning_language")
        p.sessionMinutes = row.int("session_minutes")
        p.hiddenWords = try decodeStringList(row.text("hidden_words"))
        p.interests = row.text("interests")
        p.hasOnboarded = row.bool("has_onboarded")
        p.aiConsentVersion = row.intOrNull("ai_consent_version")
        p.slowSpeech = row.boolOrNull("slow_speech")
        p.listeningPractice = row.boolOrNull("listening_practice")
        p.speechRate = row.realOrNull("speech_rate")
        p.usageMetricsEnabled = row.boolOrNull("usage_metrics_enabled")
        p.sttEngine = row.textOrNull("stt_engine")
        p.pronunciationEnabled = row.boolOrNull("pronunciation_enabled")
        return p
    }

    private static func row(_ p: Preferences) throws -> [String: Any?] {
        return [
            "id": Int64(1),
            "learning_language_id": p.learningLanguageID,
            "meaning_visible": p.meaningVisible,
            "meaning_language": p.meaningLanguage,
            "session_minutes": p.sessionMinutes,
            "hidden_words": try encodeStringList(p.hiddenWords),
            "interests": p.interests,
            "has_onboarded": p.hasOnboarded,
            "ai_consent_version": p.aiConsentVersion,
            "slow_speech": p.slowSpeech,
            "listening_practice": p.listeningPractice,
            "speech_rate": p.speechRate,
            "usage_metrics_enabled": p.usageMetricsEnabled,
            "stt_engine": p.sttEngine,
            "pronunciation_enabled": p.pronunciationEnabled,
        ]
    }
}

// MARK: SessionsDao

/// Sessions persist their scalar columns plus the four write-once JSON
/// payloads (assessments, translations, topics, scenario review) exactly as
/// the archive encodes them; review_created_at mirrors
/// ScenarioReview.createdAt for cheap queries only — reads always decode the
/// JSON column, which stays the source of truth.
public enum SessionsDao {
    /// Select-then-update/insert: never REPLACE, whose internal DELETE would
    /// cascade-destroy the session's fragments (fragments.session_id ON
    /// DELETE CASCADE) on every session-end token/endedAt update.
    public static func upsert(_ db: SqlDatabase, _ session: SessionRecord) throws {
        let exists = try !db.query("SELECT 1 AS matched FROM sessions WHERE id = ?",
                                   args: [session.id.uuidString]).isEmpty
        if exists {
            try updateRow(db, "sessions", row(session), where: "id = ?", whereArgs: [session.id.uuidString])
        } else {
            _ = try db.insert("sessions", row(session))
        }
    }

    /// Fragments go with it (fragments.session_id ON DELETE CASCADE).
    public static func delete(_ db: SqlDatabase, _ id: String) throws {
        _ = try db.update("DELETE FROM sessions WHERE id = ?", args: [id])
    }

    /// All sessions in insertion order, fragments ordered by sort_index.
    public static func all(_ db: SqlDatabase) throws -> [SessionRecord] {
        return try db.query("SELECT * FROM sessions ORDER BY rowid").map { try session($0, db) }
    }

    /// Sessions of one learning language in insertion order.
    public static func byLanguage(_ db: SqlDatabase, _ languageID: String) throws -> [SessionRecord] {
        return try db.query("SELECT * FROM sessions WHERE language_id = ? ORDER BY rowid", args: [languageID])
            .map { try session($0, db) }
    }

    static func session(_ row: SqlRow, _ db: SqlDatabase) throws -> SessionRecord {
        let id = row.text("id")
        var session = SessionRecord(
            languageID: row.text("language_id"),
            themeID: row.textOrNull("theme_id"),
            title: row.text("title"),
            lessonID: row.textOrNull("lesson_id"),
            scenarioID: row.textOrNull("scenario_id"))
        session.id = UUID(uuidString: id)!
        session.providerID = row.textOrNull("provider_id")
        session.startedAt = Date(timeIntervalSinceReferenceDate: row.real("started_at"))
        session.endedAt = row.realOrNull("ended_at").map(Date.init(timeIntervalSinceReferenceDate:))
        session.fragments = try FragmentsDao.bySession(db, id)
        session.assessments = try DatabaseJSON.decode([Assessment].self, row.text("assessments_json"))
        session.translations = try DatabaseJSON.decode([String: String].self, row.text("translations_json"))
        session.topics = try DatabaseJSON.decode([TopicBrief].self, row.text("topics_json"))
        session.voiceSeconds = row.real("voice_seconds")
        session.usageFinal = row.bool("usage_final")
        session.inputTokens = row.int("input_tokens")
        session.outputTokens = row.int("output_tokens")
        session.searchCalls = row.int("search_calls")
        session.endReason = row.textOrNull("end_reason")
        session.mode = row.textOrNull("mode")
        session.scenarioReview = try row.textOrNull("scenario_review_json")
            .map { try DatabaseJSON.decode(ScenarioReview.self, $0) }
        return session
    }

    private static func row(_ s: SessionRecord) throws -> [String: Any?] {
        return [
            "id": s.id.uuidString,
            "language_id": s.languageID,
            "provider_id": s.providerID,
            "started_at": s.startedAt.timeIntervalSinceReferenceDate,
            "ended_at": s.endedAt?.timeIntervalSinceReferenceDate,
            "theme_id": s.themeID,
            "title": s.title,
            "voice_seconds": s.voiceSeconds,
            "usage_final": s.usageFinal,
            "input_tokens": s.inputTokens,
            "output_tokens": s.outputTokens,
            "search_calls": s.searchCalls,
            "end_reason": s.endReason,
            "mode": s.mode,
            "lesson_id": s.lessonID,
            "scenario_id": s.scenarioID,
            "assessments_json": try DatabaseJSON.encode(s.assessments),
            "translations_json": try DatabaseJSON.encode(s.translations),
            "topics_json": try DatabaseJSON.encode(s.topics),
            "scenario_review_json": s.scenarioReview.flatMap { try DatabaseJSON.encode($0) },
            "review_created_at": s.scenarioReview?.createdAt,
        ]
    }
}

// MARK: FragmentsDao

/// Fragments belong to one session and keep their list position in
/// sort_index; `replaceBySession` rewrites the session's whole transcript
/// (delete + insert) inside one transaction so a crash can never leave half
/// a transcript behind. `replaceInTx` is the same rewrite for callers that
/// already run inside a transaction (ArchiveMapper.import) — transactions
/// do not nest.
public enum FragmentsDao {
    public static func bySession(_ db: SqlDatabase, _ sessionID: String) throws -> [Fragment] {
        return try db.query("SELECT * FROM fragments WHERE session_id = ? ORDER BY sort_index", args: [sessionID])
            .map(fragment)
    }

    public static func replaceBySession(_ db: SqlDatabase, _ sessionID: String, _ fragments: [Fragment]) throws {
        try db.transaction { tx in
            try replaceInTx(tx, sessionID, fragments)
        }
    }

    static func replaceInTx(_ db: SqlDatabase, _ sessionID: String, _ fragments: [Fragment]) throws {
        _ = try db.update("DELETE FROM fragments WHERE session_id = ?", args: [sessionID])
        for (index, f) in fragments.enumerated() {
            _ = try db.insert("fragments", row(sessionID, index, f))
        }
    }

    private static func fragment(_ row: SqlRow) throws -> Fragment {
        var fragment = Fragment(
            speaker: Speaker(rawValue: row.text("speaker"))!,
            text: row.text("text"),
            startMS: row.int("start_ms"),
            endMS: row.int("end_ms"),
            meaningVisible: row.bool("meaning_visible"),
            typed: row.bool("typed"))
        fragment.id = row.text("id")
        fragment.revision = row.int("revision")
        fragment.previousTexts = try decodeStringList(row.text("previous_texts"))
        fragment.receivedAt = Date(timeIntervalSinceReferenceDate: row.real("received_at"))
        return fragment
    }

    private static func row(_ sessionID: String, _ sortIndex: Int, _ f: Fragment) throws -> [String: Any?] {
        return [
            "id": f.id,
            "session_id": sessionID,
            "sort_index": sortIndex,
            "speaker": f.speaker.rawValue,
            "text": f.text,
            "revision": f.revision,
            "previous_texts": try encodeStringList(f.previousTexts),
            "start_ms": f.startMS,
            "end_ms": f.endMS,
            "received_at": f.receivedAt.timeIntervalSinceReferenceDate,
            "meaning_visible": f.meaningVisible,
            "typed": f.typed,
        ]
    }
}

// MARK: ExpressionLanguageStats

/// One remove-languages dialog row (mirror of the Kotlin data class): the
/// language (nil = the legacy NULL-language bucket), its notebook row count
/// and its approximate UTF-8 payload (text+meaning byte lengths).
public struct ExpressionLanguageStats: Equatable {
    public let languageID: String?
    public let count: Int
    public let approxBytes: Int64

    public init(languageID: String?, count: Int, approxBytes: Int64) {
        self.languageID = languageID
        self.count = count
        self.approxBytes = approxBytes
    }
}

// MARK: ExpressionsDao

/// Saved expressions keyed on (norm_key, language_id) with norm_key =
/// `RecallEngine.normalizeKey`. The schema keeps NULL language rows distinct
/// (legacy entries, visible and adoptable in every language — RecallEngine
/// semantics), which shapes this upsert in three ways:
///
///  - EXACT MATCH: an existing (norm_key, language) row is UPDATEd in place —
///    INSERT OR REPLACE cannot see a NULL-language conflict (NULLs are
///    distinct in the key) and would duplicate every legacy row on re-import,
///    producing an archive whose duplicate expressionKeys the archive
///    decoder rejects.
///  - ADOPTION: when a language row is written, no exact row exists but legacy
///    (norm_key, NULL) row(s) do, the first legacy row (rowid order) is
///    retargeted to the new language — exactly the adopt RecallEngine
///    performs (seedFromLesson, saveLookup): only languageID changes, plus a
///    meaning fill-in when the legacy meaning is blank; text, source,
///    lesson_id, bars, attempts, lastSeen and dueAt stay the legacy entry's,
///    never the incoming state's. If several legacy rows share the norm_key
///    (the PK cannot prevent them), they collapse into that one row with
///    the archive merge's semantics (max bars/attempts/lastSeen/dueAt, first
///    non-blank meaning, first non-null lesson_id).
///  - COEXISTENCE: importers that must preserve an archive verbatim pass
///    adoptLegacy = false, so a legacy row and a language row with the same
///    norm_key stay two rows, exactly the two expressionKeys the archive
///    validation accepts.
///
/// The `RecallEngine.maximumExpressions` notebook cap is enforced here:
/// brand-new keys past the cap are a silent no-op returning false, while
/// updates and adoptions always land. Returns true when a row was written.
public enum ExpressionsDao {
    @discardableResult
    public static func upsert(_ db: SqlDatabase, _ expression: ExpressionState,
                              adoptLegacy: Bool = true) throws -> Bool {
        let normKey = RecallEngine.normalizeKey(expression.text)
        let exactExists: Bool
        if expression.languageID == nil {
            exactExists = try !db.query(
                "SELECT 1 AS matched FROM expressions WHERE norm_key = ? AND language_id IS NULL",
                args: [normKey]).isEmpty
        } else {
            exactExists = try !db.query(
                "SELECT 1 AS matched FROM expressions WHERE norm_key = ? AND language_id = ?",
                args: [normKey, expression.languageID!]).isEmpty
        }
        if exactExists {
            // language_id already matches the key; the row() value for it is
            // rewritten as-is (NULL literal or bound) and lesson_id may clear.
            try updateRow(db, "expressions", row(normKey, expression),
                          where: expression.languageID == nil
                              ? "norm_key = ? AND language_id IS NULL"
                              : "norm_key = ? AND language_id = ?",
                          whereArgs: expression.languageID == nil ? [normKey] : [normKey, expression.languageID!])
            return true
        }
        if expression.languageID != nil, adoptLegacy,
           try adoptLegacyRows(db, normKey, expression, expression.languageID!) {
            return true
        }
        if try count(db) >= Int64(RecallEngine.maximumExpressions) { return false }
        _ = try db.insert("expressions", row(normKey, expression))
        return true
    }

    /// Engine-faithful adoption of the first legacy row, collapsing any
    /// duplicate legacy rows into it. Returns false when none exist.
    private static func adoptLegacyRows(_ db: SqlDatabase, _ normKey: String,
                                        _ incoming: ExpressionState,
                                        _ languageID: String) throws -> Bool {
        let legacy = try db.query(
            "SELECT rowid AS rid, meaning, lesson_id, bars, attempts, last_seen, due_at "
                + "FROM expressions WHERE norm_key = ? AND language_id IS NULL ORDER BY rowid",
            args: [normKey])
        guard let kept = legacy.first else { return false }
        let keptId = kept.long("rid")
        // languageID is the only semantic change (RecallEngine adoption); the
        // meaning fills a blank legacy meaning (saveLookup). The remaining
        // values come from the legacy rows themselves — max across them, which
        // is the row's own value in the single-row case — never the incoming.
        let meaning = legacy.map { $0.text("meaning") }
            .first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            ?? incoming.meaning
        let lessonID = legacy.compactMap { $0.textOrNull("lesson_id") }.first
        _ = try db.update(
            "UPDATE expressions SET language_id = ?, meaning = ?, bars = ?, attempts = ?, "
                + "last_seen = ?, due_at = ? WHERE rowid = ?",
            args: [languageID, meaning,
                   Int64(legacy.map { $0.int("bars") }.max() ?? 0),
                   Int64(legacy.map { $0.int("attempts") }.max() ?? 0),
                   legacy.map { $0.real("last_seen") }.max() ?? 0,
                   legacy.map { $0.real("due_at") }.max() ?? 0,
                   keptId])
        if lessonID == nil {
            try db.exec("UPDATE expressions SET lesson_id = NULL WHERE rowid = \(keptId)")
        } else {
            _ = try db.update("UPDATE expressions SET lesson_id = ? WHERE rowid = ?", args: [lessonID!, keptId])
        }
        _ = try db.update("DELETE FROM expressions WHERE norm_key = ? AND language_id IS NULL AND rowid != ?",
                          args: [normKey, keptId])
        return true
    }

    /// Deletes every row visible to `languageID` for `text`'s norm_key — the
    /// language row AND any legacy NULL-language row — exactly what
    /// RecallEngine.remove removes from the archive (its predicate is
    /// language-or-legacy). Returns the removed row count.
    @discardableResult
    public static func removeVisible(_ db: SqlDatabase, _ text: String, _ languageID: String) throws -> Int64 {
        return try db.update("DELETE FROM expressions WHERE norm_key = ? AND (language_id = ? OR language_id IS NULL)",
                             args: [RecallEngine.normalizeKey(text), languageID])
    }

    /// All expressions in insertion order (legacy NULL rows wherever the
    /// archive listed them).
    public static func all(_ db: SqlDatabase) throws -> [ExpressionState] {
        return try db.query("SELECT * FROM expressions ORDER BY rowid").map(expression)
    }

    /// Expressions visible in `languageID`: its own rows plus the legacy
    /// NULL-language entries RecallEngine keeps reachable everywhere,
    /// insertion order.
    public static func byLanguage(_ db: SqlDatabase, _ languageID: String) throws -> [ExpressionState] {
        return try db.query("SELECT * FROM expressions WHERE language_id = ? OR language_id IS NULL ORDER BY rowid",
                            args: [languageID]).map(expression)
    }

    /// RecallEngine.due over the table: the language's rows plus legacy NULL
    /// entries, bars < 3, dueAt reached, oldest due first. `ORDER BY due_at,
    /// rowid` reproduces the engine's stable sort over rowid order.
    public static func due(_ db: SqlDatabase, _ languageID: String, _ now: Double) throws -> [ExpressionState] {
        return try db.query("SELECT * FROM expressions WHERE (language_id = ? OR language_id IS NULL) "
            + "AND bars < 3 AND due_at <= ? ORDER BY due_at, rowid", args: [languageID, now]).map(expression)
    }

    /// Deletes the notebook rows of ONE language — `language_id = ?` EXACT.
    /// The legacy NULL-language entries are CONSERVED: they are visible (and
    /// adoptable) in every language, so removing one language's rows must not
    /// take the shared legacy bucket along — the same split
    /// `BackupManager.deleteLearningDataForLanguage` draws for the
    /// whole-language wipe, narrowed to expressions. One transaction; returns
    /// the affected row count. Legacy rows go through `deleteExpressionsLegacy`,
    /// which takes a different predicate (IS NULL cannot ride a bind argument).
    @discardableResult
    public static func deleteExpressionsForLanguage(_ db: SqlDatabase, languageID: String) throws -> Int64 {
        return try db.transaction { tx in
            try tx.update("DELETE FROM expressions WHERE language_id = ?", args: [languageID])
        }
    }

    /// The legacy bucket's delete — the selectable "No language (legacy)" row
    /// of the notebook's remove-languages dialog. Same contract as
    /// `deleteExpressionsForLanguage`: one transaction, affected count.
    @discardableResult
    public static func deleteExpressionsLegacy(_ db: SqlDatabase) throws -> Int64 {
        return try db.transaction { tx in
            try tx.update("DELETE FROM expressions WHERE language_id IS NULL", args: [])
        }
    }

    /// Per-language notebook size for the remove-languages dialog: one
    /// `ExpressionLanguageStats` row per language THAT HAS data (an empty
    /// language is nothing to remove), the legacy NULL bucket included as a
    /// `languageID == nil` row when it holds rows. approxBytes is the UTF-8
    /// payload size — `LENGTH(CAST(x AS BLOB))` is byte length in SQLite,
    /// where plain LENGTH on TEXT counts characters — summed over text and
    /// meaning. Ordered by count descending, ties broken deterministically:
    /// named languages alphabetical first, the legacy bucket last.
    public static func expressionStatsByLanguage(_ db: SqlDatabase) throws -> [ExpressionLanguageStats] {
        return try db.query(
            "SELECT language_id, COUNT(*) AS n, "
                + "SUM(LENGTH(CAST(text AS BLOB)) + LENGTH(CAST(meaning AS BLOB))) AS bytes "
                + "FROM expressions GROUP BY language_id "
                + "ORDER BY n DESC, language_id IS NULL, language_id").map { row in
            ExpressionLanguageStats(languageID: row.textOrNull("language_id"),
                                    count: row.int("n"), approxBytes: row.long("bytes"))
        }
    }

    private static func count(_ db: SqlDatabase) throws -> Int64 {
        return try db.query("SELECT COUNT(*) AS n FROM expressions").first!.long("n")
    }

    private static func expression(_ row: SqlRow) -> ExpressionState {
        return ExpressionState(
            text: row.text("text"),
            meaning: row.text("meaning"),
            source: row.text("source"),
            lessonID: row.textOrNull("lesson_id"),
            bars: row.int("bars"),
            attempts: row.int("attempts"),
            lastSeen: row.real("last_seen"),
            dueAt: row.real("due_at"),
            languageID: row.textOrNull("language_id"))
    }

    private static func row(_ normKey: String, _ e: ExpressionState) -> [String: Any?] {
        return [
            "norm_key": normKey,
            "language_id": e.languageID,
            "text": e.text,
            "meaning": e.meaning,
            "source": e.source,
            "bars": e.bars,
            "attempts": e.attempts,
            "last_seen": e.lastSeen,
            "due_at": e.dueAt,
            "lesson_id": e.lessonID,
        ]
    }
}

// MARK: LessonStateDao

/// Per-lesson practice progress. Schema v1 persists attempts and last_seen
/// only — LessonState.bars and dueAt are intentionally not stored yet and
/// read back as zero (shared/db/schema.sql, lesson_state).
public enum LessonStateDao {
    public static func upsert(_ db: SqlDatabase, _ state: LessonState) throws {
        let exists = try !db.query("SELECT 1 AS matched FROM lesson_state WHERE lesson_id = ?",
                                   args: [state.lessonID]).isEmpty
        let row: [String: Any?] = [
            "lesson_id": state.lessonID,
            "attempts": state.attempts,
            "last_seen": state.lastSeen,
        ]
        if exists {
            try updateRow(db, "lesson_state", row, where: "lesson_id = ?", whereArgs: [state.lessonID])
        } else {
            _ = try db.insert("lesson_state", row)
        }
    }

    public static func all(_ db: SqlDatabase) throws -> [LessonState] {
        return try db.query("SELECT * FROM lesson_state ORDER BY rowid").map { row in
            LessonState(
                lessonID: row.text("lesson_id"),
                bars: 0,
                attempts: row.int("attempts"),
                lastSeen: row.real("last_seen"),
                dueAt: 0.0)
        }
    }
}

// MARK: CompletionsDao

/// Completion marks (the append-only progress ledger), insert-or-ignore on the
/// (kind, item_id, at) primary key.
///
/// DEDUP POLICY — schema-pinned, and honestly narrower than the archive: the
/// archive's completionKey is kind|itemID|at|languageID, so merge and
/// validation keep TWO marks that differ only by language at the same
/// instant. This table's primary key (kind, item_id, at) cannot — the first
/// mark stored wins and the other is dropped, whichever order that happens to
/// be. Writers append in merge order (at-descending), so for merge-produced
/// archives the survivor is the first in merge order (stable position for
/// equal at), but that is an artifact of write order, not an equivalent
/// semantic. A genuinely different `at` is always its own row.
/// Returns true when the mark landed.
public enum CompletionsDao {
    @discardableResult
    public static func add(_ db: SqlDatabase, _ mark: CompletionMark) throws -> Bool {
        return try db.insert("completions", [
            "kind": mark.kind,
            "item_id": mark.itemID,
            "at": mark.at,
            "best_score": mark.bestScore,
            "language_id": mark.languageID,
        ], strategy: .ignore) != -1
    }

    public static func all(_ db: SqlDatabase) throws -> [CompletionMark] {
        return try db.query("SELECT * FROM completions ORDER BY rowid").map { row in
            CompletionMark(
                kind: row.text("kind"),
                itemID: row.text("item_id"),
                at: row.real("at"),
                bestScore: row.int("best_score"),
                languageID: row.textOrNull("language_id"))
        }
    }

    /// Rewrites the table to exactly `marks`, in list order (callers inside a
    /// transaction use this directly — transactions do not nest). The runtime
    /// ledger re-sorts by `at` descending and truncates on every append
    /// (recordCompletion), so an insert-only write would drift from the
    /// archive's order and cap; the archive's ledger IS the table's content,
    /// and rowid order must equal list order for export to rebuild it.
    public static func replaceAllInTx(_ db: SqlDatabase, _ marks: [CompletionMark]) throws {
        try db.exec("DELETE FROM completions")
        for mark in marks {
            _ = try add(db, mark)
        }
    }
}

// MARK: SkillErrorsDao

/// Repeated-error patterns keyed by skill, kept to the newest
/// `Archive.maximumErrors` rows by count — the same top-100 cap the archive
/// merge applies, with rowid breaking count ties the same stable way the
/// merge's insertion-ordered sort does. Updates are in-place (no REPLACE),
/// so rows keep their insertion-order rowid.
public enum SkillErrorsDao {
    public static func upsert(_ db: SqlDatabase, _ error: SkillError) throws {
        let exists = try !db.query("SELECT 1 AS matched FROM skill_errors WHERE skill_id = ?",
                                   args: [error.skillID]).isEmpty
        let row: [String: Any?] = [
            "skill_id": error.skillID,
            "count": error.count,
            "last_at": error.lastAt,
            "last_prompt": error.lastPrompt,
        ]
        if exists {
            try updateRow(db, "skill_errors", row, where: "skill_id = ?", whereArgs: [error.skillID])
        } else {
            _ = try db.insert("skill_errors", row)
        }
        try db.exec("DELETE FROM skill_errors WHERE skill_id NOT IN "
            + "(SELECT skill_id FROM skill_errors ORDER BY count DESC, rowid LIMIT \(Archive.maximumErrors))")
    }

    public static func all(_ db: SqlDatabase) throws -> [SkillError] {
        return try db.query("SELECT * FROM skill_errors ORDER BY rowid").map { row in
            SkillError(skillID: row.text("skill_id"), count: row.int("count"),
                       lastAt: row.real("last_at"), lastPrompt: row.text("last_prompt"))
        }
    }

    /// Rewrites the table to exactly `errors`, in list order (callers inside a
    /// transaction use this directly — transactions do not nest). The runtime
    /// error memory re-sorts by count descending and truncates on every hit
    /// (recordExerciseMistake), so the archive's list IS the table's content
    /// and rowid order must equal list order for export to rebuild it.
    public static func replaceAllInTx(_ db: SqlDatabase, _ errors: [SkillError]) throws {
        try db.exec("DELETE FROM skill_errors")
        for error in errors {
            try upsert(db, error)
        }
    }
}

// MARK: UsageOutboxDao

/// One queued usage event plus its durable row id. `PendingUsageEvent.id` is
/// a client-generated UUID the schema does not store; the rowid takes over as
/// identity and its `durableUsageEventID` form — folded with this install's
/// random 'install_id' — becomes the id used on the wire, stable per
/// (install, row) so client retries dedupe server-side.
public struct QueuedUsageEvent {
    public let rowId: Int64
    public let event: PendingUsageEvent

    /// The deterministic UUID form of a rowid (see `durableUsageEventID`).
    static func eventID(forRowId rowId: Int64, installID: String) -> UUID {
        UUID(uuidString: durableUsageEventID(installID: installID, rowId: rowId))!
    }
}

/// Durable usage-outbox queue. `PendingUsageEvent.at` is unix milliseconds;
/// usage_outbox.at is Apple-epoch seconds REAL (the schema's one conversion
/// rule), applied here on write and reversed on read.
public enum UsageOutboxDao {
    /// Unix seconds behind Apple-epoch seconds (Date.timeIntervalSinceReferenceDate);
    /// the schema's APPLE_EPOCH_UNIX_SECONDS on the Kotlin side.
    static let appleEpochUnixSeconds = 978_307_200.0

    /// The per-install random component of every durable wire id, persisted in
    /// meta on first use (the server's client-id unique index spans ALL
    /// installs, so rowid-only ids from fresh installs would collide).
    public static let installIDKey = "install_id"

    /// Reads the persisted install id, generating a random UUID on first use;
    /// re-reads after the put so a concurrent first drain derives from exactly
    /// what is persisted (last write wins).
    public static func installID(_ db: SqlDatabase) throws -> String {
        if let stored = try MetaDao.get(db, installIDKey) { return stored }
        try MetaDao.put(db, installIDKey, UUID().uuidString)
        return (try MetaDao.get(db, installIDKey)) ?? ""
    }

    @discardableResult
    public static func enqueue(_ db: SqlDatabase, _ name: String, _ atUnixMillis: Int64,
                               _ props: [String: UsageProp] = [:]) throws -> Int64 {
        return try db.insert("usage_outbox", [
            "name": name,
            "at": Double(atUnixMillis) / 1000.0 - appleEpochUnixSeconds,
            "props_json": try propsJSON(props),
        ])
    }

    /// Undispatched events, oldest first.
    public static func pending(_ db: SqlDatabase) throws -> [QueuedUsageEvent] {
        let installID = try Self.installID(db)
        return try db.query("SELECT rowid AS row_id, name, at, props_json FROM usage_outbox "
            + "WHERE dispatched = 0 ORDER BY at, row_id").map { row in
            let rowId = row.long("row_id")
            return QueuedUsageEvent(
                rowId: rowId,
                event: PendingUsageEvent(
                    id: QueuedUsageEvent.eventID(forRowId: rowId, installID: installID),
                    name: row.text("name"),
                    at: Int64(((row.real("at") + appleEpochUnixSeconds) * 1000.0).rounded()),
                    props: try props(row.text("props_json"))))
        }
    }

    public static func markDispatched(_ db: SqlDatabase, _ rowIds: some Collection<Int64>) throws {
        if rowIds.isEmpty { return }
        let placeholders = rowIds.map { _ in "?" }.joined(separator: ", ")
        _ = try db.update("UPDATE usage_outbox SET dispatched = 1 WHERE rowid IN (\(placeholders))",
                          args: Array(rowIds))
    }

    // The event props ride the column as a JSON object, encoded like every
    // other JSON column (pretty-printed, sorted keys).

    private static func propsJSON(_ props: [String: UsageProp]) throws -> String {
        if props.isEmpty { return "{}" }
        let object = props.mapValues { jsonValue($0) }
        let data = try JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys])
        return String(data: data, encoding: .utf8)!
    }

    private static func jsonValue(_ prop: UsageProp) -> Any {
        switch prop {
        case .string(let value): return value
        case .int(let value): return NSNumber(value: value)
        case .bool(let value): return NSNumber(value: value)
        }
    }

    private static func props(_ json: String) throws -> [String: UsageProp] {
        if json == "{}" { return [:] }
        guard let data = json.data(using: .utf8) else { return [:] }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return object.mapValues { usageProp($0) }
    }

    /// JSONSerialization hands Bools over as CFBoolean NSNumbers, so the
    /// boolean case is recognized by its CoreFoundation type before the int.
    private static func usageProp(_ value: Any) -> UsageProp {
        switch value {
        case let string as String:
            return .string(string)
        case let number as NSNumber where CFGetTypeID(number) == CFBooleanGetTypeID():
            return .bool(number.boolValue)
        case let number as NSNumber:
            return .int(number.intValue)
        default:
            return .string(String(describing: value))
        }
    }
}

// MARK: AssessmentTicketsDao

/// Final-assessment retry metadata, one row per session (the newest ticket
/// wins on upsert, matching FinalAssessmentRecovery). passageID/revisionKey
/// are not stored (schema v1): ticket matching degrades accordingly.
public struct AssessmentTicketRow: Equatable {
    public let sessionID: String
    public let attempts: Int
    public let lastAttemptAt: Double?

    public init(_ sessionID: String, attempts: Int, lastAttemptAt: Double?) {
        self.sessionID = sessionID
        self.attempts = attempts
        self.lastAttemptAt = lastAttemptAt
    }
}

public enum AssessmentTicketsDao {
    public static func upsert(_ db: SqlDatabase, _ ticket: AssessmentTicketRow) throws {
        let exists = try !db.query("SELECT 1 AS matched FROM assessment_tickets WHERE session_id = ?",
                                   args: [ticket.sessionID]).isEmpty
        let row: [String: Any?] = [
            "session_id": ticket.sessionID,
            "attempts": ticket.attempts,
            "last_attempt_at": ticket.lastAttemptAt,
        ]
        if exists {
            try updateRow(db, "assessment_tickets", row, where: "session_id = ?", whereArgs: [ticket.sessionID])
        } else {
            _ = try db.insert("assessment_tickets", row)
        }
    }

    public static func all(_ db: SqlDatabase) throws -> [AssessmentTicketRow] {
        return try db.query("SELECT * FROM assessment_tickets ORDER BY rowid").map { row in
            AssessmentTicketRow(row.text("session_id"), attempts: row.int("attempts"),
                                lastAttemptAt: row.realOrNull("last_attempt_at"))
        }
    }

    /// Removes one session's ticket — the mirror of FinalAssessmentRecovery
    /// enqueue's removal branch and of the ledger filters in deleteSession.
    public static func delete(_ db: SqlDatabase, _ sessionID: String) throws {
        _ = try db.update("DELETE FROM assessment_tickets WHERE session_id = ?", args: [sessionID])
    }

    public static func clear(_ db: SqlDatabase) throws {
        try db.exec("DELETE FROM assessment_tickets")
    }
}

// MARK: MetaDao

/// Free-form meta rows: 'schema_flags' (JSON object) and one 'mastery:<key>'
/// row per archive masteryCache entry, the Int encoded as TEXT.
public enum MetaDao {
    public static let schemaFlagsKey = "schema_flags"
    public static let masteryPrefix = "mastery:"

    public static func put(_ db: SqlDatabase, _ key: String, _ value: String) throws {
        let exists = try !db.query("SELECT 1 AS matched FROM meta WHERE key = ?", args: [key]).isEmpty
        if exists {
            try updateRow(db, "meta", ["key": key, "value": value], where: "key = ?", whereArgs: [key])
        } else {
            _ = try db.insert("meta", ["key": key, "value": value])
        }
    }

    public static func get(_ db: SqlDatabase, _ key: String) throws -> String? {
        return try db.query("SELECT value FROM meta WHERE key = ?", args: [key]).first?.text("value")
    }

    /// The archive's mastery cache IS the table's mastery content: existing
    /// mastery rows are removed first so a re-import never leaves stale
    /// skills behind (export = the archive, not a union). Callers inside a
    /// transaction (ArchiveMapper.import) make that atomic.
    public static func replaceMastery(_ db: SqlDatabase, _ mastery: [String: Int]) throws {
        try db.exec("DELETE FROM meta WHERE key LIKE '\(masteryPrefix)%'")
        for (skill, level) in mastery {
            try put(db, "\(masteryPrefix)\(skill)", String(level))
        }
    }

    /// Every mastery row, keyed like the archive's mastery cache.
    public static func mastery(_ db: SqlDatabase) throws -> [String: Int] {
        return try db.query("SELECT key, value FROM meta WHERE key LIKE '\(masteryPrefix)%' ORDER BY key")
            .reduce(into: [:]) { result, row in
                let key = row.text("key")
                result[String(key.dropFirst(masteryPrefix.count))] = Int(row.text("value"))!
            }
    }

    /// Writes the schema-flags row once; v1 has no flags.
    public static func ensureSchemaFlags(_ db: SqlDatabase) throws {
        if try get(db, schemaFlagsKey) == nil {
            try put(db, schemaFlagsKey, "{}")
        }
    }
}

// MARK: SearchDao

/// One conversation-search result: the session to open plus a short context
/// window around the best match (the same snippet the Kotlin mirror produces
/// — markers '«'/'»' wrap the matched token, '…' marks elided text).
public struct SearchHit {
    public let session: SessionRecord
    public let snippet: String
}

/// Full-text conversation search over the fragments FTS index (schema v2,
/// the FTS4 external-content table) — the Swift mirror of the Kotlin
/// `SearchDao`. MATCH resolves docids in fragments_fts (an external-content
/// FTS4 table's rowid IS the content row's rowid), the JOIN walks back to the
/// fragment's session, the language filter rides the session (a fragment has
/// no language of its own), and results order newest-conversation-first with
/// `limit` capping SESSIONS, not fragment matches: rows come back one per
/// matching fragment (snippet() must ride the plain MATCH query — SQLite
/// refuses it under a GROUP BY) and this DAO collapses each session to its
/// first matching fragment in transcript order. snippet() is FTS4's
/// six-argument form (table, start, end, ellipsis, column, tokens); it reads
/// the original text through the content option. The query is a plain user
/// string, sanitized into bare prefix terms (see `matchQuery`) so FTS4
/// syntax characters can neither throw nor widen the match.
public enum SearchDao {
    public static let snippetStart = "«"
    public static let snippetEnd = "»"
    public static let snippetEllipsis = "…"
    public static let defaultLimit = 20

    public static func search(_ db: SqlDatabase, _ query: String, languageID: String,
                              limit: Int = defaultLimit) throws -> [SearchHit] {
        let match = matchQuery(query)
        if match.isEmpty || limit <= 0 { return [] }
        var hits: [SearchHit] = []
        var seen = Set<String>()
        for row in try db.query(
            "SELECT s.*, snippet(fragments_fts, ?, ?, ?, 0, 16) AS fts_snippet "
                + "FROM fragments_fts "
                + "JOIN fragments ON fragments.rowid = fragments_fts.rowid "
                + "JOIN sessions s ON s.id = fragments.session_id "
                + "WHERE fragments_fts MATCH ? AND s.language_id = ? "
                + "ORDER BY s.started_at DESC, fragments.rowid",
            args: [snippetStart, snippetEnd, snippetEllipsis, match, languageID]) {
            // one hit per session: the first (newest conversation, earliest
            // fragment) match decides the snippet
            if seen.insert(row.text("id")).inserted {
                hits.append(SearchHit(session: try SessionsDao.session(row, db),
                                      snippet: row.text("fts_snippet")))
                if hits.count == limit { break }
            }
        }
        return hits
    }

    /// "qué tal HO?" → "qué* tal* HO*" — one BARE prefix term per token:
    /// FTS4 has no escaped quoting (a quoted phrase matches whole tokens
    /// only — the prefix star is ignored inside quotes), and a term that
    /// carries the trailing star can never parse as the NEAR/OR/AND/NOT
    /// keywords, while the operator symbols ( ) : ^ - are stripped outright.
    /// A blank (or only-blank) query searches nothing.
    static func matchQuery(_ query: String) -> String {
        return query.split(whereSeparator: \.isWhitespace)
            .compactMap { token -> String? in
                let cleaned = String(token).filter { $0.isLetter || $0.isNumber }
                return cleaned.isEmpty ? nil : cleaned
            }
            .map { "\($0)*" }
            .joined(separator: " ")
    }
}

// MARK: ArchiveHealthDao

/// The archive_health usage event: one coarse, content-free heartbeat per
/// install per day (the returned props are `UsageBuckets.dbBytes`/`.sessions`
/// bucket strings — never exact sizes or counts). claimIfDue only claims the
/// 24h slot (meta 'last_health_at') and returns the bucketed props; the
/// CALLER writes the durable usage_outbox row (`UsageOutboxDao.enqueue`) —
/// the queue UsageReporter.flush drains, so the heartbeat survives an app
/// close between claim and upload. The 24h cadence keys off the meta row
/// (unix milliseconds as TEXT); the metrics-enabled choice stays the
/// caller's.
public enum ArchiveHealthDao {
    /// Claims the daily slot: when the last marker is missing or older than
    /// the cadence, stamps the marker and returns the bucketed props for the
    /// caller to enqueue as the durable usage_outbox row; otherwise returns
    /// nil. TWO loss windows are accepted for a heartbeat, both simply
    /// delaying the next heartbeat to the next due window: a crash after the
    /// claim, and a FAILED OUTBOX ENQUEUE after it (the marker is already
    /// stamped, so that heartbeat is skipped until the next window). The ONLY
    /// durable write here is that single meta row (a put), atomic on its own
    /// — no transaction required.
    public static func claimIfDue(_ db: SqlDatabase, nowUnixMilliseconds: Int64,
                                  dbBytes: Int64, sessionCount: Int) throws -> [String: UsageProp]? {
        let last = try MetaDao.get(db, ArchiveHealth.lastHealthAtKey).flatMap { Int64($0) }
        if !ArchiveHealth.isDue(lastAtMilliseconds: last, nowMilliseconds: nowUnixMilliseconds) {
            return nil
        }
        try MetaDao.put(db, ArchiveHealth.lastHealthAtKey, String(nowUnixMilliseconds))
        return [
            "dbBytesBucket": .string(UsageBuckets.dbBytes(dbBytes)),
            "sessionsBucket": .string(UsageBuckets.sessions(sessionCount)),
        ]
    }
}
