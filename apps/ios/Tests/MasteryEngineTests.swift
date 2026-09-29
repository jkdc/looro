import XCTest
@testable import LearningCore

final class MasteryEngineTests: XCTestCase {
    private let now = 100 * 86_400.0
    private let day = 86_400.0

    private func course(_ lessons: [(String, [String])]) -> Course {
        Course(id: "en-foundations", languageID: "en", title: "F", subtitle: "s", symbol: "g", description: "d",
               units: [CourseUnit(id: "u1", title: "U1", subtitle: "", level: "A1", themes: [],
                                  lessons: lessons.map { id, skills in
                                      Lesson(id: id, title: "T", goal: "G", intro: LessonIntro(explanation: [], examples: []),
                                             expressions: [], situation: "s", practice: LessonPractice(questions: []),
                                             skills: skills)
                                  }])
    }

    private func listening(_ id: String, _ skills: [String]) -> [ListeningText] {
        [ListeningText(id: id, languageID: "en", title: "T", level: "A1", topic: "t", text: "x",
                       questions: [], skills: skills)]
    }

    private func stats(_ course: Course, listenings: [ListeningText] = [], lessons: [LessonState] = [],
                       marks: [CompletionMark] = []) -> MasteryStats {
        MasteryEngine.stats(course: course, listenings: listenings, lessons: lessons,
                            completions: marks, nowSeconds: now)
    }

    func testEvidenceIsWeightedAndDecayedWithASixtyDayHalfLife() {
        let course = course([("l1", ["grammar:verb-be"])])
        let fresh = stats(course, marks: [CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: now, bestScore: 100)])
        XCTAssertEqual(60, fresh.skills.first!.mastery)
        let aged = stats(course, marks: [CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: now - 60 * day, bestScore: 100)])
        XCTAssertEqual(30, aged.skills.first!.mastery)
        XCTAssertEqual(0.5, MasteryEngine.decay(atSeconds: now - 60 * day, nowSeconds: now), accuracy: 0.0001)
        XCTAssertEqual(1.0, MasteryEngine.decay(atSeconds: now, nowSeconds: now), accuracy: 0.0001)
    }

    func testOralPracticeIsStrongEvidenceEvenWithoutAMark() {
        let course = course([("l1", ["grammar:verb-be"])])
        let result = stats(course, lessons: [LessonState(lessonID: "l1", attempts: 2, lastSeen: now)])
        // 0.9 × 75 baseline = 67.5 → 68; exercises alone at 100 would only be 60.
        XCTAssertEqual(68, result.skills.first!.mastery)
        XCTAssertEqual(now, result.skills.first!.lastPracticed)
    }

    func testTheBestEvidenceWinsAcrossKinds() {
        let course = course([("l1", ["grammar:verb-be"])])
        let result = stats(course, listenings: listening("t1", ["grammar:verb-be"]),
                           marks: [CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: now, bestScore: 80),
                                   CompletionMark(kind: ProgressEngine.listening, itemID: "t1", at: now, bestScore: 100)])
        // exercise 0.6 × 80 = 48 · listening 0.5 × 100 = 50 → 50 wins.
        XCTAssertEqual(50, result.skills.first!.mastery)
    }

    func testExposureSkillsAreNeverMeasured() {
        let lesson = Lesson(id: "l1", title: "T", goal: "G", intro: LessonIntro(explanation: [], examples: []),
                            expressions: [], situation: "s", practice: LessonPractice(questions: []),
                            skills: ["grammar:verb-be"], exposureSkills: ["grammar:past-continuous"])
        let course = Course(id: "en-foundations", languageID: "en", title: "F", subtitle: "s", symbol: "g", description: "d",
                            units: [CourseUnit(id: "u1", title: "U1", subtitle: "", level: "A1", themes: [], lessons: [lesson])])
        let result = stats(course)
        XCTAssertEqual(["grammar:verb-be"], result.skills.map(\.skillID))
    }

    func testCategoriesAndOverallAreMeansOfTheirSkills() {
        let course = course([("l1", ["grammar:verb-be"]), ("l2", ["grammar:plurals", "vocab:family"])])
        let result = stats(course, marks: [CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: now, bestScore: 100),
                                           CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l2", at: now, bestScore: 50)])
        // grammar = mean(60, 30) = 45 · vocab = 30 · overall = mean(45, 30) = 38.
        XCTAssertEqual(45, result.categories.first { $0.category == "grammar" }!.mastery)
        XCTAssertEqual(30, result.categories.first { $0.category == "vocab" }!.mastery)
        XCTAssertEqual(38, result.overall)
        XCTAssertEqual(2, result.categories.first { $0.category == "grammar" }!.skillCount)
    }

    func testUntaggedContentProducesEmptyMastery() {
        let result = stats(course([("l1", [])]))
        XCTAssertEqual(0, result.overall)
        XCTAssertTrue(result.categories.isEmpty)
        XCTAssertTrue(result.skills.isEmpty)
    }
}
