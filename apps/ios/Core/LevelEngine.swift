import Foundation

/// Engagement levels projected from the completion ledger — a pure projection,
/// never stored and never a claim about language proficiency (titles live in
/// the UI layer). Inputs only ever grow (attempts, max-merged scores, ledger
/// marks), so tiers and levels never regress.
public struct LessonMastery: Hashable, Sendable {
    public var lessonID: String
    public var tier: Int
}

public struct LevelStats: Hashable, Sendable {
    public var courseID: String
    public var points: Int
    public var level: Int
    public var pointsToNext: Int?
    public var progressInLevel: Double
    public var mastery: [LessonMastery]
}

public enum LevelEngine {
    public static let exerciseGood = 60
    public static let exerciseMastered = 90
    public static let listeningMedal = 80

    /// Points (Σ mastery tiers) required for each level 1..10.
    public static let ladder = [1, 4, 8, 13, 19, 26, 34, 42, 48, 54]

    /// Day boundaries follow the local calendar on both platforms (tests pin UTC).
    public static func lessonTier(lessonID: String, lessons: [LessonState], completions: [CompletionMark],
                                  calendar: Calendar = .current) -> Int {
        guard let attempts = lessons.first(where: { $0.lessonID == lessonID })?.attempts, attempts >= 1 else { return 0 }
        let best = completions.filter { $0.kind == ProgressEngine.exerciseSet && $0.itemID == lessonID }
            .map(\.bestScore).max()
        guard let best, best >= exerciseGood else { return 1 }
        func epochDay(_ seconds: Double) -> Int {
            let epoch = calendar.startOfDay(for: Date(timeIntervalSinceReferenceDate: 0))
            return calendar.dateComponents([.day], from: epoch,
                to: calendar.startOfDay(for: Date(timeIntervalSinceReferenceDate: seconds))).day ?? 0
        }
        let spaced = Set(completions.filter { $0.kind == ProgressEngine.lesson && $0.itemID == lessonID }
            .map { epochDay($0.at) }).count >= 2
        return best >= exerciseMastered || spaced ? 3 : 2
    }

    public static func stats(course: Course, lessons: [LessonState], completions: [CompletionMark],
                             calendar: Calendar = .current) -> LevelStats {
        let mastery = course.units.flatMap(\.lessons).map {
            LessonMastery(lessonID: $0.id, tier: lessonTier(lessonID: $0.id, lessons: lessons, completions: completions, calendar: calendar))
        }
        let points = mastery.reduce(0) { $0 + $1.tier }
        var level = 0
        for threshold in ladder where points >= threshold { level += 1 }
        let pointsToNext = ladder.first { $0 > points }.map { $0 - points }
        let floor = level == 0 ? 0 : ladder[level - 1]
        let ceiling = ladder.first { $0 > points }
        let progress = ceiling.map { Double(points - floor) / Double($0 - floor) } ?? 1.0
        return LevelStats(courseID: course.id, points: points, level: level, pointsToNext: pointsToNext,
                          progressInLevel: progress, mastery: mastery)
    }

    /// 0 none · 1 silver (understood) · 2 gold (perfect).
    public static func medal(bestScore: Int?) -> Int {
        guard let bestScore else { return 0 }
        if bestScore >= 100 { return 2 }
        return bestScore >= listeningMedal ? 1 : 0
    }
}
