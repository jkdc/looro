import Foundation

/// Spaced-recall scheduling for saved expressions. Mirrors the JVM
/// `RecallEngine`: the word-bar ladder (`1, 1, 4, 14` days), typed-recall v1 —
/// correct promotes one bar, a lapse drops to bar 1.
public enum RecallEngine {
    public static let maximumExpressions = 2_000
    private static let ladder: [Double] = [1, 1, 4, 14]

    /// Case/whitespace-insensitive upsert key for an expression.
    public static func normalizeKey(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    /// Seed a lesson's target expressions. Idempotent on the normalized text;
    /// silently skips when the notebook is at capacity.
    public static func seedFromLesson(_ archive: inout Archive, lesson: Lesson, now: Double) {
        var known = Dictionary(uniqueKeysWithValues: (archive.expressions ?? []).map { (normalizeKey($0.text), $0) })
        for expression in lesson.expressions {
            let key = normalizeKey(expression.text)
            guard !key.isEmpty, known[key] == nil else { continue }
            guard (archive.expressions ?? []).count < maximumExpressions else { return }
            let state = ExpressionState(text: expression.text, meaning: expression.meaning,
                                        source: "lesson", lessonID: lesson.id, dueAt: now)
            archive.expressions = (archive.expressions ?? []) + [state]
            known[key] = state
        }
    }

    /// Save a lookup; upserts on the normalized text. Returns false when a new
    /// entry cannot fit.
    @discardableResult
    public static func saveLookup(_ archive: inout Archive, text: String, meaning: String, now: Double) -> Bool {
        let key = normalizeKey(text)
        guard !key.isEmpty else { return false }
        var list = archive.expressions ?? []
        if let index = list.firstIndex(where: { normalizeKey($0.text) == key }) {
            if list[index].meaning.isEmpty { list[index].meaning = meaning }
            archive.expressions = list
            return true
        }
        guard list.count < maximumExpressions else { return false }
        list.append(ExpressionState(text: text.trimmingCharacters(in: .whitespacesAndNewlines),
                                    meaning: meaning, source: "lookup", dueAt: now))
        archive.expressions = list
        return true
    }

    public static func remove(_ archive: inout Archive, text: String) {
        let key = normalizeKey(text)
        archive.expressions = (archive.expressions ?? []).filter { normalizeKey($0.text) != key }
    }

    /// Due expressions (bars < 3, dueAt reached), oldest due first.
    public static func due(_ archive: Archive, now: Double) -> [ExpressionState] {
        (archive.expressions ?? [])
            .filter { $0.bars < 3 && $0.dueAt <= now }
            .sorted { $0.dueAt < $1.dueAt }
    }

    public static func dueCount(_ archive: Archive, now: Double) -> Int {
        due(archive, now: now).count
    }

    /// Grade a recall attempt on the expression with the given text.
    public static func grade(_ archive: inout Archive, text: String, correct: Bool, now: Double) {
        let key = normalizeKey(text)
        guard var list = archive.expressions,
              let index = list.firstIndex(where: { normalizeKey($0.text) == key }) else { return }
        list[index].attempts += 1
        list[index].lastSeen = now
        list[index].bars = correct ? min(list[index].bars + 1, 3) : 1
        list[index].dueAt = now + ladder[list[index].bars] * 86_400
        archive.expressions = list
    }
}
