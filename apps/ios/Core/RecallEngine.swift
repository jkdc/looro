import Foundation

/// Spaced-recall scheduling for saved expressions. Mirrors the JVM
/// `RecallEngine`: the word-bar ladder (`1, 1, 4, 14` days), typed-recall v1 —
/// correct promotes one bar, a lapse drops to bar 1. Entries are scoped per
/// learning language: the upsert key is (languageID, text); legacy
/// null-language entries stay reachable everywhere so old backups keep
/// working, and get adopted by the current language the next time the same
/// text is seeded or looked up.
public enum RecallEngine {
    public static let maximumExpressions = 2_000
    private static let ladder: [Double] = [1, 1, 4, 14]

    /// Case/whitespace-insensitive upsert key for an expression.
    public static func normalizeKey(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    public static func expressionKey(_ text: String, _ languageID: String?) -> String {
        (languageID ?? "") + "|" + normalizeKey(text)
    }

    private static func languageID(_ archive: Archive) -> String { archive.preferences.learningLanguageID }

    private static func firstMatching(_ archive: Archive, _ text: String) -> ExpressionState? {
        let language = languageID(archive)
        let key = normalizeKey(text)
        return (archive.expressions ?? []).first { normalizeKey($0.text) == key && ($0.languageID == nil || $0.languageID == language) }
    }

    /// Seed a lesson's target expressions. Idempotent on the (language, text)
    /// key; silently skips when the notebook is at capacity.
    public static func seedFromLesson(_ archive: inout Archive, lesson: Lesson, now: Double) {
        let language = languageID(archive)
        var list = archive.expressions ?? []
        for expression in lesson.expressions {
            let key = normalizeKey(expression.text)
            guard !key.isEmpty else { continue }
            if let index = list.firstIndex(where: {
                normalizeKey($0.text) == key && ($0.languageID == language || $0.languageID == nil)
            }) {
                if list[index].languageID == nil { list[index].languageID = language }
                continue
            }
            guard list.count < maximumExpressions else { return }
            list.append(ExpressionState(text: expression.text, meaning: expression.meaning,
                                        source: "lesson", lessonID: lesson.id, dueAt: now, languageID: language))
        }
        archive.expressions = list
    }

    /// Save a lookup; upserts on the (language, text) key. Returns false when a
    /// new entry cannot fit.
    @discardableResult
    public static func saveLookup(_ archive: inout Archive, text: String, meaning: String, now: Double) -> Bool {
        let language = languageID(archive)
        let key = normalizeKey(text)
        guard !key.isEmpty else { return false }
        var list = archive.expressions ?? []
        if let index = list.firstIndex(where: {
            normalizeKey($0.text) == key && ($0.languageID == language || $0.languageID == nil)
        }) {
            if list[index].meaning.isEmpty { list[index].meaning = meaning }
            list[index].languageID = language
            archive.expressions = list
            return true
        }
        guard list.count < maximumExpressions else { return false }
        list.append(ExpressionState(text: text.trimmingCharacters(in: .whitespacesAndNewlines),
                                    meaning: meaning, source: "lookup", dueAt: now, languageID: language))
        archive.expressions = list
        return true
    }

    public static func remove(_ archive: inout Archive, text: String) {
        let language = languageID(archive)
        let key = normalizeKey(text)
        archive.expressions = (archive.expressions ?? [])
            .filter { !(normalizeKey($0.text) == key && ($0.languageID == nil || $0.languageID == language)) }
    }

    /// Due expressions for the learning language (bars < 3, dueAt reached),
    /// oldest due first. Legacy null-language entries stay due everywhere.
    public static func due(_ archive: Archive, now: Double, language languageOverride: String? = nil) -> [ExpressionState] {
        let language = languageOverride ?? languageID(archive)
        return (archive.expressions ?? [])
            .filter { ($0.languageID == nil || $0.languageID == language) && $0.bars < 3 && $0.dueAt <= now }
            .sorted { $0.dueAt < $1.dueAt }
    }

    public static func dueCount(_ archive: Archive, now: Double, language languageOverride: String? = nil) -> Int {
        due(archive, now: now, language: languageOverride).count
    }

    /// Grade a recall attempt on the expression with the given text.
    public static func grade(_ archive: inout Archive, text: String, correct: Bool, now: Double) {
        let key = normalizeKey(text)
        guard var list = archive.expressions,
              let index = list.firstIndex(where: { normalizeKey($0.text) == key && ($0.languageID == nil || $0.languageID == languageID(archive)) })
        else { return }
        list[index].attempts += 1
        list[index].lastSeen = now
        list[index].bars = correct ? min(list[index].bars + 1, 3) : 1
        list[index].dueAt = now + ladder[list[index].bars] * 86_400
        archive.expressions = list
    }
}
