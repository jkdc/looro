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
                       marks: [CompletionMark] = [], readings: [ReadingText] = []) -> MasteryStats {
        let idx = MasteryEngine.buildIndex(course: course, listenings: listenings, readings: readings)
        return MasteryEngine.stats(course: course, listenings: listenings, readings: readings,
                                   lessons: lessons, completions: marks,
                                   nowSeconds: now, index: idx, ledgerRevision: 1)
    }

    func testEvidenceIsWeightedAndDecayedWithASixtyDayHalfLife() {
        let course = course([("l1", ["grammar:verb-be"])])
        let fresh = stats(course, marks: [CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: now, bestScore: 100)])
        XCTAssertEqual(60, fresh.skills.first!.effectiveMastery)
        let aged = stats(course, marks: [CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: now - 60 * day, bestScore: 100)])
        XCTAssertEqual(30, aged.skills.first!.effectiveMastery)
        XCTAssertEqual(0.5, MasteryEngine.decay(atSeconds: now - 60 * day, nowSeconds: now), accuracy: 0.0001)
        XCTAssertEqual(1.0, MasteryEngine.decay(atSeconds: now, nowSeconds: now), accuracy: 0.0001)
    }

    func testOralPracticeIsStrongEvidenceEvenWithoutAMark() {
        let course = course([("l1", ["grammar:verb-be"])])
        let result = stats(course, lessons: [LessonState(lessonID: "l1", attempts: 2, lastSeen: now)])
        XCTAssertEqual(68, result.skills.first!.effectiveMastery)
        XCTAssertEqual(now, result.skills.first!.lastPracticedAt)
    }

    func testTheBestEvidenceWinsAcrossKinds() {
        let course = course([("l1", ["grammar:verb-be"])])
        let result = stats(course, listenings: listening("t1", ["grammar:verb-be"]),
                           marks: [CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: now, bestScore: 80),
                                   CompletionMark(kind: ProgressEngine.listening, itemID: "t1", at: now, bestScore: 100)])
        XCTAssertEqual(50, result.skills.first!.effectiveMastery)
    }

    func testReadingEvidenceCountsAtItsOwnWeight() {
        let course = course([("l1", ["reading:detail"])])
        let readings = [ReadingText(id: "r1", languageID: "en", title: "R", level: "A1",
                                    topic: "t", text: "x", questions: [], skills: ["reading:detail"])]
        let result = stats(course, readings: readings,
                           marks: [CompletionMark(kind: ProgressEngine.reading, itemID: "r1", at: now, bestScore: 100)])
        XCTAssertEqual(50, result.skills.first!.effectiveMastery)
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
        XCTAssertEqual(45, result.categories.first { $0.category == "grammar" }!.mastery)
        XCTAssertEqual(30, result.categories.first { $0.category == "vocab" }!.mastery)
        XCTAssertEqual(38, result.overall)
    }

    func testUntaggedContentProducesEmptyMastery() {
        let result = stats(course([("l1", [])]))
        XCTAssertEqual(0, result.overall)
        XCTAssertTrue(result.categories.isEmpty)
        XCTAssertTrue(result.skills.isEmpty)
    }

    func testSkillCatalogIndexResolvesActivityToSkills() {
        let course = course([("l1", ["grammar:verb-be", "vocab:family"])])
        let idx = MasteryEngine.buildIndex(course: course, listenings: listening("t1", ["grammar:verb-be"]), readings: [])
        XCTAssertEqual(Set(["grammar:verb-be", "vocab:family"]), idx.skillsByActivityID["l1"])
        XCTAssertEqual(Set(["grammar:verb-be"]), idx.skillsByActivityID["t1"])
        XCTAssertEqual(Set(["l1", "t1"]), idx.activitiesBySkillID["grammar:verb-be"])
    }

    func testMasteryVersionAndRevisionAreInStats() {
        let result = stats(course([("l1", ["grammar:verb-be"])]))
        XCTAssertEqual(MasteryEngine.masteryVersion, result.masteryVersion)
        XCTAssertEqual(1, result.ledgerRevision)
    }
}
