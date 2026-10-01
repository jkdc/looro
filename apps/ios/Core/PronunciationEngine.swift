import Foundation

/// Word-error-rate pronunciation scoring: the target phrase against the
/// speech transcript, both through the ExerciseEngine normalizer.
/// Deterministic — the same target and transcript always produce the same
/// score, and nothing here touches audio, only the recognized words.
public enum PronunciationEngine {
    /// A score at or above this counts as passed and records ledger evidence.
    /// Conservative on purpose: STT mishears accents, so half the words is a
    /// fair bar rather than perfection.
    public static let passThreshold = 50

    public static func score(target: String, transcript: String) -> Int {
        let wanted = ExerciseEngine.normalize(target).split(separator: " ").map(String.init).filter { !$0.isEmpty }
        let heard = ExerciseEngine.normalize(transcript).split(separator: " ").map(String.init).filter { !$0.isEmpty }
        if wanted.isEmpty { return heard.isEmpty ? 100 : 0 }
        let distance = editDistance(wanted, heard)
        return Int((100.0 * (1.0 - Double(distance) / Double(wanted.count))).rounded(.down)).clamped(0, 100)
    }

    public static func passed(_ score: Int) -> Bool { score >= passThreshold }

    /// Levenshtein distance over word lists — insertions, deletions and
    /// substitutions each cost one, so an extra word penalizes exactly as
    /// much as a dropped one.
    static func editDistance(_ a: [String], _ b: [String]) -> Int {
        var previous = Array(0...b.count)
        for i in 1...a.count {
            var current = [Int](repeating: 0, count: b.count + 1)
            current[0] = i
            for j in 1...b.count {
                current[j] = min(previous[j] + 1, current[j - 1] + 1,
                                 previous[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1))
            }
            previous = current
        }
        return previous[b.count]
    }
}

private extension Int {
    func clamped(_ low: Int, _ high: Int) -> Int { Swift.min(Swift.max(self, low), high) }
}
