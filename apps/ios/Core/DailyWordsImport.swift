import Foundation

public enum DailyWordImportAction: Equatable { case insert, keep, suppress, pendingCapacity }

public struct DailyWordCandidate {
    public let candidateID: String
    public let kind: String
    public let text: String
    public let meaning: String
    public let confidence: String
    public let origin: String
    public init(candidateID: String, kind: String, text: String, meaning: String, confidence: String, origin: String = "review") {
        self.candidateID = candidateID; self.kind = kind; self.text = text; self.meaning = meaning
        self.confidence = confidence; self.origin = origin
    }
}

public struct DailyWordsReview {
    public let reviewID: String
    public let languageID: String
    public let candidates: [DailyWordCandidate]
    public init(reviewID: String, languageID: String, candidates: [DailyWordCandidate]) {
        self.reviewID = reviewID; self.languageID = languageID; self.candidates = candidates
    }
}

public struct DailyWordExisting {
    public let text: String
    public let languageID: String
    public let bars: Int
    public let attempts: Int
    public let meaning: String
    public init(text: String, languageID: String, bars: Int, attempts: Int, meaning: String) {
        self.text = text; self.languageID = languageID; self.bars = bars; self.attempts = attempts; self.meaning = meaning
    }
}

public struct DailyWordImportPlan {
    public let owner: String
    public let languageID: String
    public let originKey: String
    public let normKey: String
    public let text: String
    public let meaning: String
    public let kind: String
    public let action: DailyWordImportAction
    public let now: Double
    public init(owner: String, languageID: String, originKey: String, normKey: String, text: String,
                meaning: String, kind: String, action: DailyWordImportAction, now: Double) {
        self.owner = owner; self.languageID = languageID; self.originKey = originKey; self.normKey = normKey
        self.text = text; self.meaning = meaning; self.kind = kind; self.action = action; self.now = now
    }
}

public func planDailyWordsImport(result: DailyWordsReview, owner: String, language: String,
                          existing: [DailyWordExisting], receipts: Set<String>, suppressions: Set<String>,
                          now: Double) -> [DailyWordImportPlan] {
    precondition(!owner.isEmpty && language.range(of: "^[a-z]{2}(?:-[A-Z]{2})?$", options: .regularExpression) != nil)
    precondition(result.languageID == language && !result.reviewID.isEmpty && now.isFinite)
    let relevant = existing.filter { $0.languageID == language }
    var known = Set(relevant.map { dailyNormalizeKey($0.text) })
    var expressionCount = existing.count
    var emitted = Set<String>()
    return result.candidates.compactMap { candidate in
        guard candidate.confidence == "supported", ["word", "expression"].contains(candidate.kind) else { return nil }
        let text = candidate.text.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = dailyNormalizeKey(text)
        guard !key.isEmpty, emitted.insert(key).inserted else { return nil }
        let originKey = candidate.origin == "learner_mark"
            ? "mark:\(candidate.candidateID)" : "review:\(result.reviewID):\(candidate.candidateID)"
        let action: DailyWordImportAction
        if receipts.contains(originKey) { action = .keep }
        else if suppressions.contains("\(language):\(key)") { action = .suppress }
        else if known.contains(key) { action = .keep }
        else if expressionCount >= 2_000 { action = .pendingCapacity }
        else { action = .insert }
        let saved = relevant.first { dailyNormalizeKey($0.text) == key }
        if action == .insert { known.insert(key); expressionCount += 1 }
        return DailyWordImportPlan(owner: owner, languageID: language, originKey: originKey, normKey: key,
                                   text: text, meaning: saved?.meaning.isEmpty == false ? saved!.meaning : candidate.meaning,
                                   kind: candidate.kind, action: action, now: now)
    }
}

private func dailyNormalizeKey(_ text: String) -> String {
    text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(with: Locale(identifier: "en_US_POSIX"))
}
