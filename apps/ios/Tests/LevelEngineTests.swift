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

    private func listening(_ ids: [String]) -> [ListeningText] {
        ids.map { ListeningText(id: $0, languageID: "en", title: "T", level: "A1", topic: "t", text: "x", questions: []) }
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
        // l1 = 3, l2 = 2, l3 = 1, l4 = 1, l5 = 0 → 7 lesson points → level 2 (threshold 6), 5 points to level 3 (12).
        XCTAssertEqual(7, stats.points)
        XCTAssertEqual(7, stats.lessonPoints)
        XCTAssertEqual(0, stats.listeningPoints)
        XCTAssertEqual(2, stats.level)
        XCTAssertEqual(5, stats.pointsToNext)
        XCTAssertEqual(1.0 / 6.0, stats.progressInLevel, accuracy: 0.0001)
        XCTAssertEqual(3, stats.mastery.first { $0.lessonID == "l1" }!.tier)
        XCTAssertEqual(0, stats.mastery.first { $0.lessonID == "l5" }!.tier)
    }

    func testListeningPointsDoubleTheMedalGradeFromTheBestScore() {
        let texts = listening(["t1", "t2", "t3", "t4"])
        func statsOf(_ marks: [CompletionMark]) -> LevelStats {
            LevelEngine.stats(course: course(["l1"]), lessons: [], completions: marks,
                              listeningTexts: texts, calendar: calendar)
        }
        // Nothing below 80 counts; best-of-marks wins; unknown items never count.
        XCTAssertEqual(0, statsOf([]).listeningPoints)
        XCTAssertEqual(1, statsOf([CompletionMark(kind: ProgressEngine.listening, itemID: "t1", at: 10, bestScore: 80)]).listeningPoints)
        XCTAssertEqual(1, statsOf([CompletionMark(kind: ProgressEngine.listening, itemID: "t1", at: 10, bestScore: 79),
                                   CompletionMark(kind: ProgressEngine.listening, itemID: "t1", at: 11, bestScore: 85)]).listeningPoints)
        XCTAssertEqual(4, statsOf([CompletionMark(kind: ProgressEngine.listening, itemID: "t1", at: 10, bestScore: 100),
                                   CompletionMark(kind: ProgressEngine.listening, itemID: "t2", at: 10, bestScore: 100),
                                   CompletionMark(kind: ProgressEngine.listening, itemID: "t3", at: 10, bestScore: 70),
                                   CompletionMark(kind: ProgressEngine.listening, itemID: "unknown", at: 10, bestScore: 100)]).listeningPoints)
        XCTAssertEqual(LevelEngine.medal(bestScore: 80), LevelEngine.listeningTier(bestScore: 80))
    }

    func testLessonOnlyMasteryNoLongerReachesTheTopOfTheLadder() {
        let lessons = (1...18).map { LessonState(lessonID: "l\($0)", attempts: 1) }
        let marks = (1...18).map { CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l\($0)", at: 10, bestScore: 95) }
        let stats = LevelEngine.stats(course: course(lessons.map(\.lessonID)), lessons: lessons,
                                      completions: marks, calendar: calendar)
        // 54 lesson points: level 7 now — the last rungs need listening too.
        XCTAssertEqual(54, stats.points)
        XCTAssertEqual(7, stats.level)
        XCTAssertEqual(7, stats.pointsToNext)
    }

    func testTopOfTheLadderReportsCompleteAndProgressIsBounded() {
        let lessons = (1...18).map { LessonState(lessonID: "l\($0)", attempts: 1) }
        let texts = listening((1...12).map { "t\($0)" })
        let marks = (1...18).map { CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l\($0)", at: 10, bestScore: 95) } +
            texts.map { CompletionMark(kind: ProgressEngine.listening, itemID: $0.id, at: 10, bestScore: 100) }
        let stats = LevelEngine.stats(course: course(lessons.map(\.lessonID)), lessons: lessons,
                                      completions: marks, listeningTexts: texts, calendar: calendar)
        // 54 lesson + 24 listening = 78 = full Foundations.
        XCTAssertEqual(78, stats.points)
        XCTAssertEqual(54, stats.lessonPoints)
        XCTAssertEqual(24, stats.listeningPoints)
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
