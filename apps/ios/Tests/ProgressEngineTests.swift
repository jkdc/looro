import XCTest
@testable import LearningCore

final class ProgressEngineTests: XCTestCase {
    private let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    private func lesson(_ id: String) -> Lesson {
        Lesson(id: id, title: "T", goal: "G", intro: LessonIntro(explanation: [], examples: []),
               expressions: [], situation: "s", practice: LessonPractice(questions: []))
    }
    private let course = Course(id: "en-foundations", languageID: "en", title: "Foundations", subtitle: "s",
        symbol: "graduationcap", description: "d", units: [
        CourseUnit(id: "u1", title: "U1", subtitle: "", level: "A1", themes: [], lessons: [Lesson(id: "l1", title: "T", goal: "G", intro: LessonIntro(explanation: [], examples: []), expressions: [], situation: "s", practice: LessonPractice(questions: [])),
            Lesson(id: "l2", title: "T", goal: "G", intro: LessonIntro(explanation: [], examples: []), expressions: [], situation: "s", practice: LessonPractice(questions: []))]),
        CourseUnit(id: "u2", title: "U2", subtitle: "", level: "A1", themes: [], lessons: [Lesson(id: "l3", title: "T", goal: "G", intro: LessonIntro(explanation: [], examples: []), expressions: [], situation: "s", practice: LessonPractice(questions: []))])])

    private func stats(_ lessons: [LessonState] = [], _ completions: [CompletionMark] = [],
                       sessions: [SessionRecord] = [], now: Double = 20) -> ProgressStats {
        ProgressEngine.stats(sessions: sessions, lessons: lessons, completions: completions, courses: [course],
                             calendar: calendar, now: Date(timeIntervalSinceReferenceDate: now))
    }

    func testCompletionKeysPassSlugsThroughAndHashEverythingElse() {
        XCTAssertEqual("en-foundations-u1-to-be", ProgressEngine.completionKey("en-foundations-u1-to-be"))
        XCTAssertEqual("restaurant", ProgressEngine.completionKey("restaurant"))
        let hashed = ProgressEngine.completionKey("en|niño|greeting")
        XCTAssertEqual(16, hashed.count)
        XCTAssertTrue(hashed.allSatisfy { "0123456789abcdef".contains($0) })
        XCTAssertEqual(hashed, ProgressEngine.completionKey("en|niño|greeting"))
    }

    func testLessonsDoneAndCoursePercentComeFromTheAttemptCounters() {
        let result = stats([LessonState(lessonID: "l1", attempts: 1), LessonState(lessonID: "l3", attempts: 2)])
        XCTAssertEqual(2, result.lessonsDone)
        XCTAssertEqual(3, result.lessonsTotal)
        XCTAssertEqual(66, result.courses.first!.percent)
    }

    func testActivityDaysUniteCompletionsAndVoiceSessionsForStreaks() {
        let day = 86_400.0
        let marks = [100.0, 101.0, 102.0, 104.0].map { CompletionMark(kind: ProgressEngine.lesson, itemID: "l1", at: $0 * day + 400) }
        var session = SessionRecord()
        session.startedAt = Date(timeIntervalSinceReferenceDate: 103 * day + 400)
        session.voiceSeconds = 60
        let result = stats([], marks, sessions: [session], now: 105 * day + 400)
        XCTAssertEqual(5, result.activeStreakDays)
        XCTAssertEqual(5, result.longestStreakDays)
        XCTAssertEqual(Array(repeating: 0, count: 24) + [1, 1, 1, 0, 1, 0], result.last30DaysActivities)
    }

    func testKindCountersCountDistinctItemsPerFamily() {
        let marks = [
            CompletionMark(kind: ProgressEngine.conversationTheme, itemID: "restaurant", at: 10),
            CompletionMark(kind: ProgressEngine.conversationTheme, itemID: "restaurant", at: 20),
            CompletionMark(kind: ProgressEngine.conversationTheme, itemID: "cafe", at: 30),
            CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "l1", at: 40, bestScore: 80),
            CompletionMark(kind: ProgressEngine.listening, itemID: "cafe-morning", at: 50, bestScore: 60),
            CompletionMark(kind: ProgressEngine.recallRound, itemID: "recall", at: 60),
            CompletionMark(kind: ProgressEngine.recallRound, itemID: "recall", at: 70),
            CompletionMark(kind: ProgressEngine.wordsPractice, itemID: "abc123", at: 80),
            CompletionMark(kind: ProgressEngine.assessment, itemID: "en-foundations", at: 90, bestScore: 100),
            CompletionMark(kind: ProgressEngine.assessment, itemID: "free", at: 95)]
        let result = stats([], marks)
        XCTAssertEqual(2, result.themesPractised)
        XCTAssertEqual(1, result.exercisesCompleted)
        XCTAssertEqual(1, result.listeningCompleted)
        XCTAssertEqual(2, result.practiceRounds)
        XCTAssertEqual(1, result.wordsPracticeItems)
        XCTAssertEqual(1, result.assessmentsPassed)
    }

    func testSyncEntriesRollUpMarksAndLessonCountersOutrankTheCappedLedger() {
        let marks = [
            CompletionMark(kind: ProgressEngine.lesson, itemID: "l1", at: 10),
            CompletionMark(kind: ProgressEngine.lesson, itemID: "l1", at: 20),
            CompletionMark(kind: ProgressEngine.listening, itemID: "cafe", at: 30, bestScore: 60)]
        let entries = ProgressSync.entries(lessons: [LessonState(lessonID: "l1", attempts: 7, lastSeen: 25)], completions: marks)
        let l1 = entries.first { $0.itemID == "l1" }!
        XCTAssertEqual(ProgressEngine.lesson, l1.kind)
        XCTAssertEqual(7, l1.attempts)
        XCTAssertEqual(25, (Double(l1.atMilliseconds) - 978_307_200_000) / 1000, accuracy: 0.001)
        let cafe = entries.first { $0.itemID == "cafe" }!
        XCTAssertEqual(1, cafe.attempts)
        XCTAssertEqual(60, cafe.bestScore)
        XCTAssertGreaterThanOrEqual(entries.first!.atMilliseconds, entries.last!.atMilliseconds)
    }

    func testRemoteMergeRestoresLessonsCounterWiseAndSynthesizesUnseenCompletionMarks() throws {
        var archive = Archive()
        archive.lessons = [LessonState(lessonID: "l1", attempts: 2, lastSeen: 10)]
        archive.completions = [CompletionMark(kind: ProgressEngine.listening, itemID: "known", at: 5, bestScore: 40)]
        let merged = ProgressSync.mergeRemote(archive: archive, rows: [
            RemoteProgress(kind: ProgressEngine.lesson, itemID: "l1", firstAt: 1, lastAt: 20, attempts: 5, bestScore: 0),
            RemoteProgress(kind: ProgressEngine.lesson, itemID: "l2", firstAt: 2, lastAt: 30, attempts: 1, bestScore: 0),
            RemoteProgress(kind: ProgressEngine.exerciseSet, itemID: "l1", firstAt: 3, lastAt: 40, attempts: 2, bestScore: 80),
            RemoteProgress(kind: ProgressEngine.listening, itemID: "known", firstAt: 4, lastAt: 50, attempts: 3, bestScore: 90)])
        XCTAssertEqual(5, merged.lessons!.first { $0.lessonID == "l1" }!.attempts)
        XCTAssertEqual(20, merged.lessons!.first { $0.lessonID == "l1" }!.lastSeen, accuracy: 0.0001)
        XCTAssertEqual(1, merged.lessons!.first { $0.lessonID == "l2" }!.attempts)
        XCTAssertTrue(merged.completions!.contains { $0.kind == ProgressEngine.exerciseSet && $0.itemID == "l1" && $0.bestScore == 80 })
        XCTAssertEqual(1, merged.completions!.filter { $0.kind == ProgressEngine.listening }.count)
    }

    func testArchiveMergeUnionsCompletionMarksWithExactTupleDedup() throws {
        var current = Archive()
        current.completions = [CompletionMark(kind: ProgressEngine.lesson, itemID: "l1", at: 10)]
        var incoming = Archive()
        incoming.completions = [CompletionMark(kind: ProgressEngine.lesson, itemID: "l1", at: 10),
                                CompletionMark(kind: ProgressEngine.lesson, itemID: "l1", at: 20)]
        let merged = try current.merging(incoming)
        XCTAssertEqual(2, merged.completions!.count)
        XCTAssertEqual(1, merged.completions!.filter { $0.at == 10 }.count)
        XCTAssertEqual(1, merged.completions!.filter { $0.at == 20 }.count)
    }

    func testArchiveValidationRejectsUnknownKindsScoresAndDuplicateMarks() throws {
        func data(_ marks: [CompletionMark]) throws -> Data {
            var archive = Archive()
            archive.completions = marks
            return try archive.encoded()
        }
        func decode(_ marks: [CompletionMark]) throws -> Archive {
            // Encode through one archive, decode through Archive.decode: validation is the subject.
            var archive = Archive()
            archive.completions = marks
            return try Archive.decode(try archive.encoded())
        }
        _ = try data([])
        XCTAssertThrowsError(try decode([CompletionMark(kind: "streak", itemID: "x", at: 10)]))
        XCTAssertThrowsError(try decode([CompletionMark(kind: ProgressEngine.lesson, itemID: "x", at: 10, bestScore: 101)]))
        XCTAssertThrowsError(try decode([CompletionMark(kind: ProgressEngine.lesson, itemID: "x", at: 10),
                                         CompletionMark(kind: ProgressEngine.lesson, itemID: "x", at: 10)]))
    }
}
