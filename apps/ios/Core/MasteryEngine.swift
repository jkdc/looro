import Foundation

/// Skill mastery projected from the completion ledger — a pure projection, never
/// stored and never a CEFR claim (percentages per category live in the UI layer).
/// Raw mastery is what the evidence shows; effective mastery applies temporal
/// decay at read time (60-day half-life). Pre-built index avoids re-iterating
/// content on every computation.
public struct SkillCatalogIndex: Sendable {
    public let skillsByActivityID: [String: Set<String>]
    public let activitiesBySkillID: [String: Set<String>]
    public let skillCategory: [String: String]

    public init(course: Course, listenings: [ListeningText], readings: [ReadingText],
                includePronunciation: Bool = true) {
        var byActivity: [String: Set<String>] = [:]
        var bySkill: [String: Set<String>] = [:]
        var cats: [String: String] = [:]

        func index(_ activityID: String, _ skills: [String]) {
            for skill in skills {
                if let cat = SkillsData.categories[skill], includePronunciation || !skill.hasPrefix("pron:") {
                    byActivity[activityID, default: []].insert(skill)
                    bySkill[skill, default: []].insert(activityID)
                    cats[skill] = cat
                }
            }
        }
        for lesson in course.units.flatMap(\.lessons) { index(lesson.id, lesson.skills) }
        for text in listenings { index(text.id, text.skills) }
        for text in readings { index(text.id, text.skills) }

        skillsByActivityID = byActivity
        activitiesBySkillID = bySkill
        skillCategory = cats
    }

    public var taughtSkills: Set<String> { Set(skillCategory.keys) }
}

public struct SkillMastery: Hashable, Sendable {
    public var skillID: String
    public var category: String
    public var rawMastery: Int
    public var effectiveMastery: Int
    public var evidenceCount: Int
    public var lastPracticedAt: Double?
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
    public var ledgerRevision: Int64
    public var masteryVersion: Int
}

public enum MasteryEngine {
    public static let checkpointWeight = 1.0
    public static let oralWeight = 0.9
    public static let exerciseWeight = 0.6
    public static let listeningWeight = 0.5
    public static let readingWeight = 0.5
    /// Self-scored WER evidence is worth less than any graded activity.
    public static let pronunciationWeight = 0.8
    public static let oralBaseline = 75
    public static let halfLifeDays = 60.0

    /// Bump this when the algorithm, weights, or skill mappings change.
    public static let masteryVersion = 2

    static let categoryOrder = ["grammar", "vocab", "function", "speaking", "listening", "reading", "writing", "pron"]

    public static func buildIndex(course: Course, listenings: [ListeningText], readings: [ReadingText],
                                  includePronunciation: Bool = true) -> SkillCatalogIndex {
        SkillCatalogIndex(course: course, listenings: listenings, readings: readings,
                          includePronunciation: includePronunciation)
    }

    public static func stats(course: Course, listenings: [ListeningText], readings: [ReadingText],
                             lessons: [LessonState], completions: [CompletionMark],
                             nowSeconds: Double, index: SkillCatalogIndex,
                             ledgerRevision: Int64, ownerAccountID: String? = nil,
                             remote: [ProgressAggregate] = []) -> MasteryStats {
        let tagged = index.taughtSkills
        let lessonIDs = Set(course.units.flatMap { $0.lessons.map(\.id) })
        let scopedIDs = lessonIDs.union(listenings.map(\.id)).union(readings.map(\.id)).union([course.id])
        let readLessons = ownerAccountID.map { ProgressProjection.effectiveLessons(ownerAccountID: $0, languageID: course.languageID, local: lessons, remote: remote, allowedIDs: lessonIDs) } ?? lessons
        let readCompletions = ownerAccountID.map { owner in
            completions.filter { $0.languageID == course.languageID || ($0.languageID == nil && scopedIDs.contains($0.itemID)) } +
                ProgressProjection.effectiveCompletions(ownerAccountID: owner, languageID: course.languageID, local: [], remote: remote)
        } ?? completions
        let mastery = tagged.map { skillID -> SkillMastery in
            let category = index.skillCategory[skillID] ?? String(skillID.split(separator: ":").first ?? Substring(skillID))
            let activityIDs = index.activitiesBySkillID[skillID] ?? []
            var best = 0.0
            var bestAt = 0.0
            var evidenceCount = 0

            func offer(weight: Double, score: Int, at: Double) {
                evidenceCount += 1
                let value = weight * (Double(score) / 100.0) * decay(atSeconds: at, nowSeconds: nowSeconds)
                if value > best { best = value; bestAt = at }
            }

            // Self-scored pronunciation evidence lands only on pron skills —
            // the mark's itemID is the lesson, so without this guard every
            // co-tagged grammar skill would inherit it.
            let pronSkill = skillID.hasPrefix("pron:")
            for activityID in activityIDs {
                if pronSkill, let mark = readCompletions.filter({ $0.kind == ProgressEngine.pronunciation && $0.itemID == activityID })
                    .max(by: { $0.bestScore < $1.bestScore }) {
                    offer(weight: pronunciationWeight, score: mark.bestScore, at: mark.at)
                }
                if let mark = readCompletions.filter({ $0.kind == ProgressEngine.exerciseSet && $0.itemID == activityID })
                    .max(by: { $0.bestScore < $1.bestScore }) {
                    offer(weight: exerciseWeight, score: mark.bestScore, at: mark.at)
                }
                if let state = readLessons.first(where: { $0.lessonID == activityID }), state.attempts > 0, state.lastSeen > 0 {
                    offer(weight: oralWeight, score: oralBaseline, at: state.lastSeen)
                }
                for textID in [] { /* listening placeholder */ }
                if let mark = readCompletions.filter({ $0.kind == ProgressEngine.listening && $0.itemID == activityID })
                    .max(by: { $0.bestScore < $1.bestScore }) {
                    offer(weight: listeningWeight, score: mark.bestScore, at: mark.at)
                }
                if let mark = readCompletions.filter({ $0.kind == ProgressEngine.reading && $0.itemID == activityID })
                    .max(by: { $0.bestScore < $1.bestScore }) {
                    offer(weight: readingWeight, score: mark.bestScore, at: mark.at)
                }
            }

            let raw = min(100, max(0, Int((best * 100).rounded())))
            return SkillMastery(skillID: skillID, category: category,
                                rawMastery: raw, effectiveMastery: raw,
                                evidenceCount: evidenceCount,
                                lastPracticedAt: bestAt > 0 ? bestAt : nil)
        }

        let grouped = Dictionary(grouping: mastery, by: \.category)
        let categories = grouped.map { category, skills in
            CategoryMastery(category: category,
                            mastery: Int((Double(skills.map(\.effectiveMastery).reduce(0, +)) / Double(skills.count)).rounded()),
                            skillCount: skills.count)
        }.sorted { categoryOrder.firstIndex(of: $0.category) ?? 0 < categoryOrder.firstIndex(of: $1.category) ?? 0 }
        let overall = categories.isEmpty ? 0
            : Int((Double(categories.map(\.mastery).reduce(0, +)) / Double(categories.count)).rounded())
        let ranked = mastery.sorted {
            let lhs = categoryOrder.firstIndex(of: $0.category) ?? 0
            let rhs = categoryOrder.firstIndex(of: $1.category) ?? 0
            return lhs != rhs ? lhs < rhs : $0.skillID < $1.skillID
        }
        return MasteryStats(overall: overall, categories: categories, skills: ranked,
                            ledgerRevision: ledgerRevision, masteryVersion: masteryVersion)
    }

    public static func decay(atSeconds: Double, nowSeconds: Double) -> Double {
        let days = max(0, (nowSeconds - atSeconds) / 86_400.0)
        return pow(0.5, days / halfLifeDays)
    }
}
