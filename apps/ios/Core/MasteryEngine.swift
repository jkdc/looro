import Foundation

/// Skill mastery projected from the completion ledger — a pure projection, never
/// stored and never a CEFR claim (percentages per category live in the UI layer).
/// Mastery per skill is the best weighted evidence, decayed with a 60-day
/// half-life: dominating something two months ago is not dominating it today, and
/// fresh practice (recall, exercises, listening) brings the number back.
public struct SkillMastery: Hashable, Sendable {
    public var skillID: String
    public var category: String
    public var mastery: Int
    public var lastPracticed: Double?
}

public struct CategoryMastery: Hashable, Sendable {
    public var category: String
    public var mastery: Int
    public var skillCount: Int
}

public struct MasteryStats: Hashable, Sendable {
    public var overall: Int
    public var categories: [CategoryMastery]
    public var skills: [SkillMastery]
}

public enum MasteryEngine {
    public static let checkpointWeight = 1.0
    public static let oralWeight = 0.9
    public static let exerciseWeight = 0.6
    public static let listeningWeight = 0.5

    /// Guided oral practice records attempts, not marks; showing up and speaking
    /// is strong evidence even without a score.
    public static let oralBaseline = 75
    public static let halfLifeDays = 60.0

    static let categoryOrder = ["grammar", "vocab", "function", "speaking", "listening", "reading", "writing", "pron"]

    /// Taught skills of a language, from its content — exposureSkills are
    /// deliberately excluded: nobody is penalised for a structure they have
    /// only heard.
    public static func taggedSkills(course: Course, listenings: [ListeningText]) -> [String: String] {
        var tagged: [String: String] = [:]
        for lesson in course.units.flatMap(\.lessons) {
            for skill in lesson.skills where tagged[skill] == nil {
                if let category = SkillsData.categories[skill] { tagged[skill] = category }
            }
        }
        for text in listenings {
            for skill in text.skills where tagged[skill] == nil {
                if let category = SkillsData.categories[skill] { tagged[skill] = category }
            }
        }
        return tagged
    }

    public static func stats(course: Course, listenings: [ListeningText], lessons: [LessonState],
                             completions: [CompletionMark], nowSeconds: Double) -> MasteryStats {
        let tagged = taggedSkills(course: course, listenings: listenings)
        var lessonsBySkill: [String: [String]] = [:]
        for lesson in course.units.flatMap(\.lessons) {
            for skill in lesson.skills { lessonsBySkill[skill, default: []].append(lesson.id) }
        }
        var listeningsBySkill: [String: [String]] = [:]
        for text in listenings {
            for skill in text.skills { listeningsBySkill[skill, default: []].append(text.id) }
        }
        let mastery = tagged.map { skillID, category -> SkillMastery in
            var best = 0.0
            var bestAt = 0.0
            func offer(weight: Double, score: Int, at: Double) {
                let value = weight * (Double(score) / 100.0) * decay(atSeconds: at, nowSeconds: nowSeconds)
                if value > best { best = value; bestAt = at }
            }
            for lessonID in lessonsBySkill[skillID] ?? [] {
                if let mark = completions.filter({ $0.kind == ProgressEngine.exerciseSet && $0.itemID == lessonID })
                    .max(by: { $0.bestScore < $1.bestScore }) {
                    offer(weight: exerciseWeight, score: mark.bestScore, at: mark.at)
                }
                if let state = lessons.first(where: { $0.lessonID == lessonID }), state.attempts > 0, state.lastSeen > 0 {
                    offer(weight: oralWeight, score: oralBaseline, at: state.lastSeen)
                }
            }
            for textID in listeningsBySkill[skillID] ?? [] {
                if let mark = completions.filter({ $0.kind == ProgressEngine.listening && $0.itemID == textID })
                    .max(by: { $0.bestScore < $1.bestScore }) {
                    offer(weight: listeningWeight, score: mark.bestScore, at: mark.at)
                }
            }
            return SkillMastery(skillID: skillID, category: category, mastery: min(100, max(0, Int((best * 100).rounded()))),
                                lastPracticed: bestAt > 0 ? bestAt : nil)
        }
        let grouped = Dictionary(grouping: mastery, by: \.category)
        let categories = grouped.map { category, skills in
            CategoryMastery(category: category,
                            mastery: Int((Double(skills.map(\.mastery).reduce(0, +)) / Double(skills.count)).rounded()),
                            skillCount: skills.count)
        }.sorted { categoryOrder.firstIndex(of: $0.category) ?? 0 < categoryOrder.firstIndex(of: $1.category) ?? 0 }
        let overall = categories.isEmpty ? 0
            : Int((Double(categories.map(\.mastery).reduce(0, +)) / Double(categories.count)).rounded())
        let ranked = mastery.sorted {
            let lhs = categoryOrder.firstIndex(of: $0.category) ?? 0
            let rhs = categoryOrder.firstIndex(of: $1.category) ?? 0
            return lhs != rhs ? lhs < rhs : $0.skillID < $1.skillID
        }
        return MasteryStats(overall: overall, categories: categories, skills: ranked)
    }

    /// Exponential forgetting: half of the evidence value is gone after
    /// halfLifeDays without practice.
    public static func decay(atSeconds: Double, nowSeconds: Double) -> Double {
        let days = max(0, (nowSeconds - atSeconds) / 86_400.0)
        return pow(0.5, days / halfLifeDays)
    }
}
