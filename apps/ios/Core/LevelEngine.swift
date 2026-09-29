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
    public var lessonPoints: Int
    public var listeningPoints: Int
    public init(courseID: String, points: Int, level: Int, pointsToNext: Int?, progressInLevel: Double,
                mastery: [LessonMastery], lessonPoints: Int = 0, listeningPoints: Int = 0) {
        self.courseID = courseID; self.points = points; self.level = level; self.pointsToNext = pointsToNext
        self.progressInLevel = progressInLevel; self.mastery = mastery
        self.lessonPoints = lessonPoints; self.listeningPoints = listeningPoints
    }
}

public enum LevelEngine {
    public static let exerciseGood = 60
    public static let exerciseMastered = 90
    public static let listeningMedal = 80

    /// Points (Σ lesson mastery tiers + Σ listening points) required for each
    /// level 1..10. The top (78) is full en-Foundations: 54 lesson points plus
    /// 12 listenings at two points each.
    public static let ladder = [1, 6, 12, 19, 27, 37, 49, 61, 69, 78]

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
                             listeningTexts: [ListeningText] = [], calendar: Calendar = .current) -> LevelStats {
        let mastery = course.units.flatMap(\.lessons).map {
            LessonMastery(lessonID: $0.id, tier: lessonTier(lessonID: $0.id, lessons: lessons, completions: completions, calendar: calendar))
        }
        let lessonPoints = mastery.reduce(0) { $0 + $1.tier }
        let listeningPoints = listeningTexts.reduce(0) { $0 + listeningTier(bestListeningScore(completions, itemID: $1.id)) }
        let points = lessonPoints + listeningPoints
        var level = 0
        for threshold in ladder where points >= threshold { level += 1 }
        let pointsToNext = ladder.first { $0 > points }.map { $0 - points }
        let floor = level == 0 ? 0 : ladder[level - 1]
        let ceiling = ladder.first { $0 > points }
        let progress = ceiling.map { Double(points - floor) / Double($0 - floor) } ?? 1.0
        return LevelStats(courseID: course.id, points: points, level: level, pointsToNext: pointsToNext,
                          progressInLevel: progress, mastery: mastery, lessonPoints: lessonPoints,
                          listeningPoints: listeningPoints)
    }

    /// 0 none · 1 silver (understood) · 2 gold (perfect). The medal grade doubles
    /// as the listening level tier: silver 1 point, gold 2.
    public static func medal(bestScore: Int?) -> Int {
        guard let bestScore else { return 0 }
        if bestScore >= 100 { return 2 }
        return bestScore >= listeningMedal ? 1 : 0
    }

    public static func listeningTier(bestScore: Int?) -> Int { medal(bestScore: bestScore) }

    private static func bestListeningScore(_ completions: [CompletionMark], itemID: String) -> Int? {
        completions.filter { $0.kind == ProgressEngine.listening && $0.itemID == itemID }.map(\.bestScore).max()
    }
}
