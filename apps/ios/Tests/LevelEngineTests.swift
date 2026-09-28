import XCTest
@testable import LearningCore

final class LevelEngineTests: XCTestCase {
    private let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    private let day = 86_400.0

    private func course(_ lessonIDs: [String]) -> Course {
        Course(id: "en-foundations", languageID: "en", title: "F", subtitle: "s", symbol: "g", description: "d",
               units: [CourseUnit(id: "u1", title: "U1", subtitle: "", level: "A1", themes: [],
                                  lessons: lessonIDs.map { id in
                                      Lesson(id: id, title: "T", goal: "G", intro: LessonIntro(explanation: [], examples: []),
                                             expressions: [], situation: "s", practice: LessonPractice(questions: []))
                                  }])
    }

    private func tier(_ lessons: [LessonState], _ marks: [CompletionMark]) -> Int {
        LevelEngine.lessonTier(lessonID: "l1", lessons: lessons, completions: marks, calendar: calendar)
    }

    func testTiersGrowWithPracticeAndExerciseScores() {
        let marks = [CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: 10, bestScore: 70)]
        XCTAssertEqual(0, tier([], marks))
        XCTAssertEqual(2, tier([LessonState(lessonID: "l1", attempts: 1)], marks))
        XCTAssertEqual(1, tier([LessonState(lessonID: "l1", attempts: 3)], []))
        XCTAssertEqual(2, tier([LessonState(lessonID: "l1", attempts: 1)], marks))
        XCTAssertEqual(3, tier([LessonState(lessonID: "l1", attempts: 1)],
                               [CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: 10, bestScore: 90)]))
    }

    func testTierThreeAlsoComesFromSpacedOralPracticeAtSixtyPercent() {
        let marks = [
            CompletionMark(kind: ProgressEngine.lesson, itemID: "l1", at: day + 400),
            CompletionMark(kind: ProgressEngine.lesson, itemID: "l1", at: 4 * day + 400),
            CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: 10, bestScore: 60)]
        XCTAssertEqual(3, tier([LessonState(lessonID: "l1", attempts: 2)], marks))
        let sameDay = [marks[0], marks[2]]
        XCTAssertEqual(2, tier([LessonState(lessonID: "l1", attempts: 2)], sameDay))
    }

    func testLevelClimbsTheLadderAndReportsPointsToNext() {
        let lessons = ["l1", "l2", "l3", "l4"].map { LessonState(lessonID: $0, attempts: 1) }
        let marks = [
            CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: 10, bestScore: 100),
            CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l2", at: 10, bestScore: 75),
            CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l3", at: 10, bestScore: 50)]
        let stats = LevelEngine.stats(course: course(["l1", "l2", "l3", "l4", "l5"]), lessons: lessons,
                                      completions: marks, calendar: calendar)
        XCTAssertEqual(7, stats.points)
        XCTAssertEqual(2, stats.level)
        XCTAssertEqual(1, stats.pointsToNext)
        XCTAssertEqual(0.75, stats.progressInLevel, accuracy: 0.0001)
        XCTAssertEqual(3, stats.mastery.first { $0.lessonID == "l1" }!.tier)
        XCTAssertEqual(0, stats.mastery.first { $0.lessonID == "l5" }!.tier)
    }

    func testTopOfTheLadderReportsCompleteAndProgressIsBounded() {
        let lessons = (1...18).map { LessonState(lessonID: "l\($0)", attempts: 1) }
        let marks = (1...18).map { CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l\($0)", at: 10, bestScore: 95) }
        let stats = LevelEngine.stats(course: course(lessons.map(\.lessonID)), lessons: lessons,
                                      completions: marks, calendar: calendar)
        XCTAssertEqual(54, stats.points)
        XCTAssertEqual(10, stats.level)
        XCTAssertNil(stats.pointsToNext)
        XCTAssertEqual(1.0, stats.progressInLevel, accuracy: 0.0001)
    }

    func testListeningMedalsComeFromTheBestScore() {
        XCTAssertEqual(0, LevelEngine.medal(bestScore: nil))
        XCTAssertEqual(0, LevelEngine.medal(bestScore: 79))
        XCTAssertEqual(1, LevelEngine.medal(bestScore: 80))
        XCTAssertEqual(1, LevelEngine.medal(bestScore: 99))
        XCTAssertEqual(2, LevelEngine.medal(bestScore: 100))
    }
}
