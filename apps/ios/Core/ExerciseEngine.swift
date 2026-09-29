import Foundation

/// Deterministic grading for authored temario exercises. No AI, no network:
/// attempts are normalized (NFC, case, punctuation, spacing) and compared
/// against the accepted answers; a `<…>` span in an expected answer accepts
/// any non-empty text. Shared verbatim with the Listening questions.
public enum ExerciseEngine {
    public static let choice = "choice"
    public static let gap = "gap"
    public static let order = "order"
    public static let written = "written"

    /// Swift compares strings by canonical equivalence; Kotlin needs the same NFC form.
    public static func normalize(_ text: String) -> String {
        let punctuation = CharacterSet(charactersIn: ".,!?;:¿¡\"“”«»…()")
        let lowered = text.precomposedStringWithCanonicalMapping.lowercased()
        let stripped = lowered.unicodeScalars.filter { !punctuation.contains($0) }
        return String(String.UnicodeScalarView(stripped))
            .split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    /// True when the attempt matches an accepted answer. `choice` compares the
    /// picked option exactly; `order` receives the chosen tokens joined with
    /// spaces; `gap`/`written` compare with placeholders tolerated.
    public static func isCorrect(exercise: Exercise, attempt: String) -> Bool {
        switch exercise.kind {
        case choice: return exercise.answers.contains { $0 == attempt.trimmingCharacters(in: .whitespaces) }
        case order: return exercise.answers.contains { normalize($0) == normalize(attempt) }
        default: return exercise.answers.contains { matches(expected: $0, attempt: attempt) }
        }
    }

    private static func matches(expected: String, attempt: String) -> Bool {
        let wanted = normalize(expected)
        guard wanted.contains("<") else { return normalize(attempt) == wanted }
        let placeholder = try! NSRegularExpression(pattern: "<[^<>]+>")
        let range = NSRange(wanted.startIndex..., in: wanted)
        var parts: [String] = []
        var cursor = wanted.startIndex
        placeholder.enumerateMatches(in: wanted, range: range) { match, _, _ in
            guard let match, let matchRange = Range(match.range, in: wanted) else { return }
            parts.append(NSRegularExpression.escapedPattern(for: String(wanted[cursor..<matchRange])))
            cursor = matchRange.upperBound
        }
        parts.append(NSRegularExpression.escapedPattern(for: String(wanted[cursor...])))
        let candidate = normalize(attempt)
        let regex = try! NSRegularExpression(pattern: "\\A(?:\(parts.joined(separator: ".+")))\\z")
        return regex.firstMatch(in: candidate, range: NSRange(candidate.startIndex..., in: candidate)) != nil
    }
}
