import Foundation

/// Parses and verifies the structured scenario review produced by the review
/// helper. Validation mirrors `LearningEngine.validate`: every error quote must
/// be an exact contiguous substring of the transcript fragments it cites, so a
/// hallucinated correction never reaches the learner. Invalid items are dropped
/// individually; the rest of the review survives.
/// Mirror of `apps/android/.../core/ScenarioReviewing.kt`; pinned by
/// `ScenarioReviewingTests` at the macOS gate.
public enum ScenarioReviewing {
    public static let maximumErrors = 6
    public static let maximumStrengths = 3
    public static let maximumKeyPhrases = 6
    public static let maximumSourceIDs = 8
    public static let kinds: Set<String> = ["grammar", "vocabulary", "register", "phrasing", "omission"]

    /// Optionals so a reply may omit any group (Kotlin's decoder defaults); unknown
    /// keys are ignored by JSONDecoder, and a non-JSON reply fails the whole decode.
    private struct Response: Codable, Sendable {
        var strengths: [String]?
        var errors: [ReviewedError]?
        var keyPhrases: [ReviewedPhrase]?
        var retryFocus: String?
    }

    /// Returns nil when the reply is not the expected JSON at all.
    public static func parse(_ text: String) -> ScenarioReview? {
        guard let decoded = try? JSONDecoder().decode(Response.self, from: Data(text.utf8)) else { return nil }
        return ScenarioReview(createdAt: Date.now.timeIntervalSinceReferenceDate,
                              strengths: decoded.strengths ?? [], errors: decoded.errors ?? [],
                              keyPhrases: decoded.keyPhrases ?? [], retryFocus: decoded.retryFocus ?? "")
    }

    public static func validate(_ review: ScenarioReview, session: SessionRecord) -> ScenarioReview {
        // Kotlin associateBy: on a duplicate id the last fragment wins.
        let byID = Dictionary(session.fragments.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        return validate(review: review, fragmentsByID: byID)
    }

    public static func validate(review: ScenarioReview, fragmentsByID: [String: Fragment]) -> ScenarioReview {
        let errors = review.errors.prefix(maximumErrors).compactMap { error -> ReviewedError? in
            let quote = String(error.quote.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200))
            let correction = String(error.correction.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200))
            if quote.isEmpty || correction.isEmpty { return nil }
            if !kinds.contains(error.kind) { return nil }
            let sourceIDs = Array(error.sourceIDs.prefix(maximumSourceIDs))
            let evidence = sourceIDs.compactMap { fragmentsByID[$0]?.text }.joined()
            if !evidence.contains(quote) { return nil }
            return ReviewedError(quote: quote, correction: correction, kind: error.kind,
                                 note: String(error.note.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200)),
                                 sourceIDs: sourceIDs)
        }
        let strengths = review.strengths.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }.prefix(maximumStrengths).map { String($0.prefix(200)) }
        let keyPhrases = review.keyPhrases.prefix(maximumKeyPhrases).compactMap { phrase -> ReviewedPhrase? in
            let text = String(phrase.phrase.trimmingCharacters(in: .whitespacesAndNewlines).prefix(120))
            if text.isEmpty { return nil }
            return ReviewedPhrase(phrase: text,
                                  meaning: String(phrase.meaning.trimmingCharacters(in: .whitespacesAndNewlines).prefix(160)))
        }
        return ScenarioReview(createdAt: review.createdAt, strengths: strengths, errors: errors,
                              keyPhrases: keyPhrases,
                              retryFocus: String(review.retryFocus.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200)))
    }

    /// "you said → corrected" pairs for the next attempt's prompt, newest session first.
    public static func previousErrors(_ review: ScenarioReview) -> String {
        String(review.errors.map { "\($0.quote) → \($0.correction)" }.joined(separator: "; ").prefix(400))
    }
}
