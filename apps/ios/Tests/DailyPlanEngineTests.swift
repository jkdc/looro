import XCTest
@testable import LearningCore

final class DailyPlanEngineTests: XCTestCase {
    func testRecallComesFirstAndNeverExceedsHalfTheBudget() {
        let plan = DailyPlanEngine.plan(budget: 15, dueWords: 20, hasNewLesson: true,
                                        hasUnplayedListening: true, hasUnplayedReading: true)
        XCTAssertEqual("recall", plan.first!.kind)
        XCTAssertLessThanOrEqual(plan.first!.minutes, 8)
        XCTAssertEqual(15, plan.reduce(0) { $0 + $1.minutes })
    }

    func testBiggerBudgetsAddAListeningSmallerOnesPreferAReading() {
        let big = DailyPlanEngine.plan(budget: 20, dueWords: 10, hasNewLesson: true,
                                       hasUnplayedListening: true, hasUnplayedReading: true)
        XCTAssertEqual(["recall", "lesson", "listen"], big.map(\.kind))
        let small = DailyPlanEngine.plan(budget: 5, dueWords: 0, hasNewLesson: true,
                                         hasUnplayedListening: true, hasUnplayedReading: true)
        XCTAssertTrue(small.map(\.kind).contains("lesson") || small.map(\.kind).contains("read"))
    }

    func testNoContentMeansEmptyPlan() {
        let plan = DailyPlanEngine.plan(budget: 10, dueWords: 0, hasNewLesson: false,
                                        hasUnplayedListening: false, hasUnplayedReading: false)
        XCTAssertTrue(plan.isEmpty, "plan should be empty when nothing to do")
    }

    func testZeroBudgetYieldsAnEmptyPlan() {
        XCTAssertTrue(DailyPlanEngine.plan(budget: 0, dueWords: 5, hasNewLesson: true,
                                           hasUnplayedListening: true, hasUnplayedReading: true).isEmpty)
    }
}
