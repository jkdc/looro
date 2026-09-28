import Foundation

public enum Speaker: String, Codable, Sendable { case user, assistant }
public enum EvidenceKind: String, Codable, Sendable { case exposure, understanding, assisted, independent, lapse }
public enum Outcome: String, Codable, Sendable { case success, partial, breakdown, uncertain }

public struct Fragment: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var revision: Int = 0
    public var previousTexts: [String] = []
    public var speaker: Speaker
    public var text: String
    public var startMS: Int
    public var endMS: Int
    public var receivedAt: Date
    public var meaningVisible: Bool
    public var typed: Bool
    public init(id: String = UUID().uuidString, speaker: Speaker, text: String, startMS: Int, endMS: Int, receivedAt: Date = .now, meaningVisible: Bool = false, typed: Bool = false) {
        self.id = id; self.speaker = speaker; self.text = text; self.startMS = startMS
        self.endMS = endMS; self.receivedAt = receivedAt; self.meaningVisible = meaningVisible; self.typed = typed
    }
}

public struct Passage: Identifiable, Sendable {
    public var id: String
    public var speaker: Speaker
    public var fragments: [Fragment]
    public var text: String { fragments.map(\.text).joined() }
    public var revisionKey: String { fragments.map { "\($0.id):\($0.revision)" }.joined(separator: ",") }
    public var startMS: Int { fragments.first?.startMS ?? 0 }
    public var endMS: Int { fragments.map(\.endMS).max() ?? 0 }
}

public enum Transcript {
    /// Presentation grouping only: neither the gap nor the arrival of another speaker proves a completed turn.
    public static func passages(_ fragments: [Fragment]) -> [Passage] {
        var result: [Passage] = []
        let indexed = fragments.enumerated().sorted {
            $0.element.startMS == $1.element.startMS ? $0.offset < $1.offset : $0.element.startMS < $1.element.startMS
        }
        for (_, fragment) in indexed {
            if let i = result.lastIndex(where: { $0.speaker == fragment.speaker }),
               fragment.startMS - result[i].endMS <= 2200,
               !fragment.typed, !(result[i].fragments.last?.typed ?? false) {
                result[i].fragments.append(fragment)
            } else {
                result.append(Passage(id: fragment.id, speaker: fragment.speaker, fragments: [fragment]))
            }
        }
        return result.sorted { $0.startMS < $1.startMS }
    }
}

public struct WordProposal: Codable, Sendable {
    public var lemma: String
    public var meaning: String
    public var form: String
    public var kind: EvidenceKind
    public var confidence: Double
    public var sourceIDs: [String]
    public var quote: String
    public var language: String
    public init(lemma: String, meaning: String, form: String, kind: EvidenceKind, confidence: Double, sourceIDs: [String], quote: String, language: String = LanguageRegistry.defaultID) {
        self.lemma = lemma; self.meaning = meaning; self.form = form; self.kind = kind
        self.confidence = confidence; self.sourceIDs = sourceIDs; self.quote = quote; self.language = language
    }
    public var key: String { language + "|" + lemma.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() + "|" + meaning.lowercased() }
}

public struct Assessment: Codable, Identifiable, Sendable {
    public var id: String { passageID }
    public var passageID: String
    public var revisionKey: String
    public var outcome: Outcome
    public var suggestedLevel: Int
    public var nextGoal: String
    public var capability: String
    public var words: [WordProposal]
    public var createdAt: Date
    public var context: String
    public init(passageID: String, revisionKey: String, outcome: Outcome, suggestedLevel: Int, nextGoal: String, capability: String, words: [WordProposal], createdAt: Date = .now, context: String = "free") {
        self.passageID = passageID; self.revisionKey = revisionKey; self.outcome = outcome
        self.suggestedLevel = suggestedLevel; self.nextGoal = nextGoal; self.capability = capability
        self.words = words; self.createdAt = createdAt; self.context = context
    }
}

public struct SourceLink: Codable, Identifiable, Hashable, Sendable {
    public var id: String { url }
    public var title: String
    public var url: String
    public init(title: String, url: String) { self.title = title; self.url = url }
    public var safeURL: URL? {
        guard let u = URL(string: url), u.scheme == "https", u.host != nil, u.user == nil else { return nil }
        return u
    }
}

public struct TopicBrief: Codable, Identifiable, Sendable {
    public var id = UUID()
    public let languageID: String
    public var query: String
    public var text: String
    public var sources: [SourceLink]
    public var retrievedAt = Date()
    public var isFresh: Bool { Date().timeIntervalSince(retrievedAt) < 6 * 3600 }
    public init(languageID: String, query: String, text: String, sources: [SourceLink]) { self.languageID = languageID; self.query = query; self.text = text; self.sources = sources }
}

public struct SessionRecord: Codable, Identifiable, Sendable {
    public var id = UUID()
    public let languageID: String
    public var providerID: String?
    public var startedAt = Date()
    public var endedAt: Date?
    public var themeID: String?
    public var title: String
    public var fragments: [Fragment] = []
    public var assessments: [Assessment] = []
    public var translations: [String: String] = [:]
    public var topics: [TopicBrief] = []
    public var voiceSeconds: Double = 0
    public var usageFinal = false
    public var inputTokens = 0
    public var outputTokens = 0
    public var searchCalls = 0
    public var endReason: String?
    public var mode: String?
    public var lessonID: String?
    public init(languageID: String = LanguageRegistry.defaultID, themeID: String? = nil, title: String? = nil, lessonID: String? = nil) {
        self.languageID = languageID; self.themeID = themeID
        self.title = title ?? LanguageRegistry.module(for: languageID)?.defaultTitle ?? "A conversation"
        self.lessonID = lessonID
    }
    public var isListening: Bool { mode == "listening" }
    public var isLesson: Bool { mode == "lesson" }
    public var passages: [Passage] { Transcript.passages(fragments) }
    public mutating func append(_ fragment: Fragment) {
        guard !fragments.contains(where: { $0.id == fragment.id }) else { return }
        fragments.append(fragment)
        invalidateChangedAssessments()
    }
    public mutating func invalidateChangedAssessments() {
        let current = Dictionary(uniqueKeysWithValues: passages.map { ($0.id, $0.revisionKey) })
        assessments.removeAll { current[$0.passageID] != $0.revisionKey }
    }
    public mutating func correctFragment(id: String, text: String) {
        guard let index = fragments.firstIndex(where: { $0.id == id }) else { return }
        fragments[index].previousTexts.append(fragments[index].text)
        fragments[index].text = text; fragments[index].revision += 1
        translations.removeAll(); invalidateChangedAssessments()
    }
}

public struct Preferences: Codable, Sendable {
    public var learningLanguageID = LanguageRegistry.defaultID
    public var meaningVisible = true
    public var meaningLanguage = "English"
    public var sessionMinutes = 15
    public var hiddenWords: [String] = []
    public var interests = ""
    public var hasOnboarded = false
    public var aiConsentVersion: Int?
    public var slowSpeech: Bool?
    public var speechRate: Double?
    public var listeningPractice: Bool?
    public var usageMetricsEnabled: Bool?
    public var sttCloud: Bool?
    public init() {}
}

/// Per-lesson practice progress. v1 records attempts only; bars/dueAt stay at zero
/// until the evidence-based progress change claims them.
public struct LessonState: Codable, Hashable, Sendable {
    public var lessonID: String
    public var bars: Int
    public var attempts: Int
    public var lastSeen: Double
    public var dueAt: Double
    public init(lessonID: String, bars: Int = 0, attempts: Int = 0, lastSeen: Double = 0, dueAt: Double = 0) {
        self.lessonID = lessonID; self.bars = bars; self.attempts = attempts
        self.lastSeen = lastSeen; self.dueAt = dueAt
    }
}

/// Saved expression for the recall queue. `source` is "lesson" or "lookup";
/// scheduled by RecallEngine on the same ladder as word bars. `languageID` is
/// nil only on legacy entries, which stay visible in every language.
public struct ExpressionState: Codable, Hashable, Sendable {
    public var text: String
    public var meaning: String
    public var source: String
    public var lessonID: String?
    public var bars: Int
    public var attempts: Int
    public var lastSeen: Double
    public var dueAt: Double
    public var languageID: String?
    public init(text: String, meaning: String, source: String, lessonID: String? = nil,
                bars: Int = 0, attempts: Int = 0, lastSeen: Double = 0, dueAt: Double = 0,
                languageID: String? = nil) {
        self.text = text; self.meaning = meaning; self.source = source; self.lessonID = lessonID
        self.bars = bars; self.attempts = attempts; self.lastSeen = lastSeen; self.dueAt = dueAt
        self.languageID = languageID
    }
}

/// One completed learning event in the append-only progress ledger. `kind` is the
/// closed catalog shared with the server ledger (`user_progress.kind`); itemID is
/// a content slug, or its hashed key when the raw item (a word lemma) is not one.
/// `languageID` is nil only on legacy marks, which count as global activity.
/// Optional fields so pre-ledger archives and backups keep decoding untouched.
public struct CompletionMark: Codable, Hashable, Sendable {
    public var kind: String
    public var itemID: String
    public var at: Double
    public var bestScore: Int
    public var languageID: String?
    public init(kind: String, itemID: String, at: Double, bestScore: Int = 0, languageID: String? = nil) {
        self.kind = kind; self.itemID = itemID; self.at = at; self.bestScore = bestScore
        self.languageID = languageID
    }
}

public struct Archive: Codable, Sendable {
    public static let maximumEncodedBytes = 30_000_000
    private static let maximumSessions = 10_000
    private static let maximumLessons = 1_000
    private static let maximumExpressions = 2_000
    /// Write sites keep the ledger to the newest marks with this cap so validation never rejects a save.
    public static let maximumCompletions = 2_000
    static let completionKinds: Set<String> = ["lesson", "conversation_theme", "exercise_set", "listening",
                                               "recall_round", "words_practice", "assessment"]
    // Generous text caps, far above a real conversation, so a crafted backup cannot stall Markdown rendering.
    // Write sites clamp to these too, so a long model reply can never make a saved session fail validation here.
    public static let maximumTitleCharacters = 200
    public static let maximumTopicTextCharacters = 20_000
    public static let maximumTranslationCharacters = 10_000
    public var schemaVersion = 2
    public var sessions: [SessionRecord] = []
    public var preferences = Preferences()
    public var lessons: [LessonState]?
    public var expressions: [ExpressionState]?
    public var completions: [CompletionMark]?
    public init() {}
    public static func decode(_ data: Data) throws -> Archive {
        guard data.count <= maximumEncodedBytes else { throw ArchiveError.tooLarge }
        let migrated = try migrate(data)
        let archive = try JSONDecoder().decode(Archive.self, from: migrated)
        try archive.validate()
        return archive
    }
    /// Bound the selected file before loading it. The read limit also covers growth after the size check.
    public static func readImportData(from url: URL) throws -> Data {
        guard url.isFileURL, try url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true
        else { throw ArchiveError.invalid }
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        guard try file.seekToEnd() <= maximumEncodedBytes else { throw ArchiveError.tooLarge }
        try file.seek(toOffset: 0)
        var data = Data()
        while let chunk = try file.read(upToCount: min(65_536, maximumEncodedBytes - data.count + 1)), !chunk.isEmpty {
            guard chunk.count <= maximumEncodedBytes - data.count else { throw ArchiveError.tooLarge }
            data.append(chunk)
        }
        return data
    }
    /// Reject the complete candidate before changing local history, so it remains readable on relaunch.
    public func merging(_ incoming: Archive) throws -> Archive {
        try incoming.validate()
        let known = Set(sessions.map(\.id))
        let additions = incoming.sessions.filter { !known.contains($0.id) }
        guard additions.count <= Self.maximumSessions - sessions.count else { throw ArchiveError.tooLarge }
        var candidate = self
        for var session in additions {
            session.invalidateChangedAssessments()
            session.assessments = session.assessments.compactMap { LearningEngine.validate($0, session: session) }
            candidate.sessions.append(session)
        }
        var lessonsByID: [String: LessonState] = Dictionary(uniqueKeysWithValues: (candidate.lessons ?? []).map { ($0.lessonID, $0) })
        for lesson in incoming.lessons ?? [] {
            let current = lessonsByID[lesson.lessonID]
            lessonsByID[lesson.lessonID] = LessonState(
                lessonID: lesson.lessonID,
                bars: max(current?.bars ?? 0, lesson.bars),
                attempts: max(current?.attempts ?? 0, lesson.attempts),
                lastSeen: max(current?.lastSeen ?? 0, lesson.lastSeen),
                dueAt: max(current?.dueAt ?? 0, lesson.dueAt))
        }
        candidate.lessons = lessonsByID.isEmpty ? nil : lessonsByID.values.sorted { $0.lessonID < $1.lessonID }
        var expressionsByKey: [String: ExpressionState] = Dictionary(uniqueKeysWithValues: (candidate.expressions ?? []).map { (RecallEngine.expressionKey($0.text, $0.languageID), $0) })
        for expression in incoming.expressions ?? [] {
            let key = RecallEngine.expressionKey(expression.text, expression.languageID)
            let current = expressionsByKey[key]
            expressionsByKey[key] = ExpressionState(
                text: current?.text ?? expression.text,
                meaning: (current?.meaning.isEmpty == false) ? current!.meaning : expression.meaning,
                source: current?.source ?? expression.source,
                lessonID: current?.lessonID ?? expression.lessonID,
                bars: max(current?.bars ?? 0, expression.bars),
                attempts: max(current?.attempts ?? 0, expression.attempts),
                lastSeen: max(current?.lastSeen ?? 0, expression.lastSeen),
                dueAt: max(current?.dueAt ?? 0, expression.dueAt))
        }
        candidate.expressions = expressionsByKey.isEmpty ? nil : expressionsByKey.values.sorted { $0.text < $1.text }
        var marks = candidate.completions ?? []
        var seenMarks = Set(marks.map(Self.markKey))
        for mark in incoming.completions ?? [] where seenMarks.insert(Self.markKey(mark)).inserted {
            marks.append(mark)
        }
        marks.sort { $0.at > $1.at }
        candidate.completions = marks.isEmpty ? nil : Array(marks.prefix(Self.maximumCompletions))
        try candidate.validate()
        guard try candidate.encoded().count <= Self.maximumEncodedBytes else { throw ArchiveError.tooLarge }
        return candidate
    }
    private static func markKey(_ mark: CompletionMark) -> String { "\(mark.kind)|\(mark.itemID)|\(mark.at)|\(mark.languageID ?? "")" }
    private func validate() throws {
        guard LanguageRegistry.module(for: preferences.learningLanguageID) != nil else { throw ArchiveError.unsupportedLanguage }
        guard Set(sessions.map(\.id)).count == sessions.count,
              sessions.count <= Self.maximumSessions,
              preferences.sessionMinutes >= 1, preferences.sessionMinutes <= 60 else { throw ArchiveError.invalid }
        func validDate(_ date: Date) -> Bool { date >= .distantPast && date <= .distantFuture }
        for s in sessions {
            guard LanguageRegistry.module(for: s.languageID) != nil else { throw ArchiveError.unsupportedLanguage }
            // These generous limits exceed a normal session while keeping UI conversions and totals safe.
            guard s.voiceSeconds.isFinite, (0...31_536_000).contains(s.voiceSeconds),
                  [s.inputTokens, s.outputTokens, s.searchCalls].allSatisfy({ (0...1_000_000_000).contains($0) }),
                  validDate(s.startedAt), s.endedAt.map(validDate) ?? true,
                  s.assessments.allSatisfy({ validDate($0.createdAt) }),
                  s.title.count <= Self.maximumTitleCharacters,
                  s.translations.values.allSatisfy({ $0.count <= Self.maximumTranslationCharacters }) else { throw ArchiveError.invalid }
            guard Set(s.fragments.map(\.id)).count == s.fragments.count,
                  s.fragments.allSatisfy({ $0.startMS >= 0 && $0.endMS >= $0.startMS && $0.text.count <= 50_000 &&
                      (0...1_000_000).contains($0.revision) && validDate($0.receivedAt) }),
                  s.topics.allSatisfy({ $0.languageID == s.languageID && $0.text.count <= Self.maximumTopicTextCharacters && validDate($0.retrievedAt) }) else { throw ArchiveError.invalid }
        }
        let lessons = lessons ?? []
        guard lessons.count <= Self.maximumLessons, Set(lessons.map(\.lessonID)).count == lessons.count,
              lessons.allSatisfy({ !$0.lessonID.isEmpty && $0.lessonID.count <= 200 && (0...3).contains($0.bars) &&
                  (0...1_000_000).contains($0.attempts) && $0.lastSeen.isFinite && $0.dueAt.isFinite })
        else { throw ArchiveError.invalid }
        let expressions = expressions ?? []
        guard expressions.count <= Self.maximumExpressions,
              Set(expressions.map { RecallEngine.expressionKey($0.text, $0.languageID) }).count == expressions.count,
              expressions.allSatisfy({ !$0.text.isEmpty && $0.text.count <= 300 && !$0.meaning.isEmpty && $0.meaning.count <= 300 &&
                  ["lesson", "lookup"].contains($0.source) &&
                  ($0.source != "lesson" || !($0.lessonID ?? "").isEmpty) &&
                  (0...3).contains($0.bars) && (0...1_000_000).contains($0.attempts) &&
                  $0.lastSeen.isFinite && $0.dueAt.isFinite })
        else { throw ArchiveError.invalid }
        let completions = completions ?? []
        guard completions.count <= Self.maximumCompletions,
              Set(completions.map(Self.markKey)).count == completions.count,
              completions.allSatisfy({ Self.completionKinds.contains($0.kind) && !$0.itemID.isEmpty && $0.itemID.count <= 200 &&
                  ($0.languageID ?? "").count <= 16 &&
                  $0.at.isFinite && (0...100).contains($0.bestScore) })
        else { throw ArchiveError.invalid }
    }
    /// Version 1 was Norwegian-only. Migration assigns that provenance once;
    /// version 2 records must explicitly declare their language.
    private static func migrate(_ data: Data) throws -> Data {
        guard var root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = root["schemaVersion"] as? Int else { throw ArchiveError.invalid }
        guard version == 1 || version == 2 else { throw ArchiveError.unsupportedVersion }
        if version == 2 { return data }
        guard var preferences = root["preferences"] as? [String: Any],
              let sessions = root["sessions"] as? [[String: Any]] else { throw ArchiveError.invalid }
        preferences["learningLanguageID"] = LanguageRegistry.defaultID
        if let hidden = preferences["hiddenWords"] as? [String] {
            preferences["hiddenWords"] = hidden.map { LanguageRegistry.defaultID + "|" + $0 }
        }
        root["preferences"] = preferences
        root["sessions"] = sessions.map { original in
            var session = original; session["languageID"] = LanguageRegistry.defaultID
            if let topics = session["topics"] as? [[String: Any]] {
                session["topics"] = topics.map { original in
                    var topic = original; topic["languageID"] = LanguageRegistry.defaultID; return topic
                }
            }
            return session
        }
        root["schemaVersion"] = 2
        return try JSONSerialization.data(withJSONObject: root)
    }
    public func encoded() throws -> Data {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(self)
    }
}
public enum ArchiveError: Error, LocalizedError {
    case tooLarge, unsupportedVersion, unsupportedLanguage, invalid
    public var errorDescription: String? {
        switch self {
        case .tooLarge: "This backup is too large to import."
        case .unsupportedVersion: "This backup needs a newer version of Palique."
        case .unsupportedLanguage: "This backup contains a language module that this version of Palique does not support."
        case .invalid: "This backup has invalid or duplicate records."
        }
    }
}

/// Effective TTS multiplier: the explicit rate choice wins; legacy archives fall
/// back to 0.8 when the old "slower speech" flag is set, otherwise normal.
public extension Preferences {
    func effectiveSpeechRate() -> Double {
        speechRate ?? (slowSpeech == true ? 0.8 : 1.0)
    }
}
