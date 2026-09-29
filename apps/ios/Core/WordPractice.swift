import Foundation

/// Typed writing drill over due words. Deliberately strict on purpose:
/// spelling practice means diacritics are significant in all eight languages.
public enum WordPractice {
    /// Accepts the observed form or its lemma, ignoring case and surrounding punctuation.
    public static func isCorrect(answer: String, form: String, lemma: String) -> Bool {
        let normalized = normalize(answer)
        guard !normalized.isEmpty else { return false }
        return normalized == normalize(form) || normalized == normalize(lemma)
    }

    public static func normalize(_ value: String) -> String {
        var text = value.precomposedStringWithCanonicalMapping.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        while let first = text.first, first.isPunctuation { text.removeFirst() }
        while let last = text.last, last.isPunctuation { text.removeLast() }
        return text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    /// The example sentence with every occurrence of the target form blanked out.
    public static func maskedExample(_ example: String, form: String) -> String {
        guard !form.isEmpty else { return example }
        let masked = example.replacingOccurrences(of: NSRegularExpression.escapedPattern(from: form),
                                                  with: "___", options: [.regularExpression, .caseInsensitive])
        return masked == example ? "" : masked
    }
}
