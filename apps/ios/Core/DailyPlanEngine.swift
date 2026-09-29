import Foundation

/// Daily Learning Plan: composes a short session from what the ledger says the
/// user needs — recall first, then new content, then one comprehension activity.
public struct PlanStep: Hashable, Sendable {
    public var kind: String
    public var minutes: Int
    public init(kind: String, minutes: Int) { self.kind = kind; self.minutes = minutes }
}

public enum DailyPlanEngine {
    public static let budgets = [5, 10, 15, 20]

    /// Composes a plan within the budget: recall first (never more than half),
    /// one new lesson, one comprehension step (listening for bigger budgets,
    /// reading for smaller ones). A zero budget yields an empty plan.
    public static func plan(budget: Int, dueWords: Int, hasNewLesson: Bool,
                            hasUnplayedListening: Bool, hasUnplayedReading: Bool) -> [PlanStep] {
        guard budget > 0 else { return [] }
        var steps: [PlanStep] = []
        var left = budget
        if dueWords > 0 {
            let minutes = min(left, max(3, budget / 3))
            steps.append(PlanStep(kind: "recall", minutes: minutes)); left -= minutes
        }
        if left >= 5 && hasNewLesson {
            let minutes = min(left, max(5, budget / 3))
            steps.append(PlanStep(kind: "lesson", minutes: minutes)); left -= minutes
        }
        if left >= 3 {
            let minutes = left
            if budget >= 10 && hasUnplayedListening {
                steps.append(PlanStep(kind: "listen", minutes: minutes))
            } else if hasUnplayedReading {
                steps.append(PlanStep(kind: "read", minutes: minutes))
            } else if hasUnplayedListening {
                steps.append(PlanStep(kind: "listen", minutes: minutes))

        }
        return steps
    }
}
