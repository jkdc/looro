import XCTest
@testable import LearningCore

final class ProgressEngineTests: XCTestCase {
    private let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    private let day = 86_400.0
    private let listeningIDs: Set<String> = ["en-listen-coffee"]

    private func course(_ languageID: String, _ lessonIDs: [String]) -> Course {
        Course(id: "\(languageID)-foundations", languageID: languageID, title: "Foundations", subtitle: "s",
               symbol: "g", description: "d",
               units: [CourseUnit(id: "u1", title: "U1", subtitle: "", level: "A1", themes: [],
                                  lessons: lessonIDs.map { id in
                                      Lesson(id: id, title: "T", goal: "G", intro: LessonIntro(explanation: [], examples: []),
                                             expressions: [], situation: "s", practice: LessonPractice(questions: []))
                                  }])
    }

    private func stats(_ lessons: [LessonState], _ completions: [CompletionMark],
                       sessions: [SessionRecord] = [], languageID: String = "en",
                       courses: [Course] = [ProgressEngineTestsMeta.enCourse],
                       now: Double = 20) -> ProgressStats {
        ProgressEngine.stats(sessions: sessions, lessons: lessons, completions: completions,
                             courses: courses, listeningIDs: listeningIDs, languageID: languageID,
                             calendar: calendar, now: Date(timeIntervalSinceReferenceDate: now))
    }

    private func lesson(_ id: String, _ attempts: Int) -> LessonState { LessonState(lessonID: id, attempts: attempts) }

    func testCompletionKeysPassSlugsThroughAndHashEverythingElse() {
        XCTAssertEqual("en-foundations-u1-to-be", ProgressEngine.completionKey("en-foundations-u1-to-be"))
        XCTAssertEqual("restaurant", ProgressEngine.completionKey("restaurant"))
        let hashed = ProgressEngine.completionKey("en|niño|greeting")
        XCTAssertEqual(16, hashed.count)
        XCTAssertTrue(hashed.allSatisfy { "0123456789abcdef".contains($0) })
        XCTAssertEqual(hashed, ProgressEngine.completionKey("en|niño|greeting"))
        XCTAssertNotEqual(hashed, ProgressEngine.completionKey("es|niño|greeting"))
    }

    func testLessonsDoneAndCoursePercentComeFromTheAttemptCounters() {
        let result = stats([lesson("en-l1", 1), lesson("en-l3", 2)], [])
        XCTAssertEqual(2, result.lessonsDone)
        XCTAssertEqual(3, result.lessonsTotal)
        XCTAssertEqual(66, result.courses.first!.percent)
    }

    func testActivityDaysUniteScopedCompletionsAndScopedVoiceSessions() {
        let marks = [100.0, 101.0, 102.0, 104.0].map {
            CompletionMark(kind: ProgressEngine.lesson, itemID: "en-l1", at: $0 * day + 400, languageID: "en")
        }
        let foreign = CompletionMark(kind: ProgressEngine.lesson, itemID: "fr-l1", at: 103 * day + 400, languageID: "fr")
        var session = SessionRecord(languageID: "en")
        session.startedAt = Date(timeIntervalSinceReferenceDate: 103 * day + 400)
        session.voiceSeconds = 60
        var foreignSession = SessionRecord(languageID: "fr")
        foreignSession.startedAt = Date(timeIntervalSinceReferenceDate: 105 * day + 400)
        foreignSession.voiceSeconds = 60
        let result = stats([], marks + [foreign], sessions: [session, foreignSession], now: 105 * day + 400)
        XCTAssertEqual(5, result.activeStreakDays)
        XCTAssertEqual(5, result.longestStreakDays)
        XCTAssertEqual(Array(repeating: 0, count: 24) + [1, 1, 1, 0, 1, 0], result.last30DaysActivities)
    }

    func testAmbiguousCountsRequireTheLanguageStampContentIdsDoNot() {
        let completions = [
            CompletionMark(kind: ProgressEngine.conversationTheme, itemID: "restaurant", at: 10, languageID: "en"),
            CompletionMark(kind: ProgressEngine.conversationTheme, itemID: "restaurant", at: 20, languageID: "fr"),
            CompletionMark(kind: ProgressEngine.conversationTheme, itemID: "cafe", at: 30, languageID: "fr"),
            CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "en-l1", at: 40, bestScore: 80),
            CompletionMark(kind: ProgressEngine.listening, itemID: "en-listen-coffee", at: 50, bestScore: 60),
            CompletionMark(kind: ProgressEngine.recallRound, itemID: "recall", at: 60, languageID: "en"),
            CompletionMark(kind: ProgressEngine.recallRound, itemID: "recall", at: 70, languageID: "fr"),
            CompletionMark(kind: ProgressEngine.wordsPractice, itemID: "hash1", at: 80, languageID: "fr"),
            CompletionMark(kind: ProgressEngine.assessment, itemID: "en-foundations", at: 90, bestScore: 100),
            CompletionMark(kind: ProgressEngine.assessment, itemID: "free", at: 95, bestScore: 100, languageID: "en"),
            CompletionMark(kind: ProgressEngine.assessment, itemID: "free", at: 96, bestScore: 100, languageID: "fr")]
        let result = stats([], completions)
        XCTAssertEqual(1, result.themesPractised)
        XCTAssertEqual(1, result.exercisesCompleted)
        XCTAssertEqual(1, result.listeningCompleted)
        XCTAssertEqual(1, result.practiceRounds)
        XCTAssertEqual(0, result.wordsPracticeItems)
        XCTAssertEqual(2, result.assessmentsPassed)
    }

    func testLegacyNilMarksCountForStreaksButNotForAmbiguousCounts() {
        let completions = [
            CompletionMark(kind: ProgressEngine.conversationTheme, itemID: "restaurant", at: 10),
            CompletionMark(kind: ProgressEngine.lesson, itemID: "en-l1", at: day + 400)]
        let result = stats([lesson("en-l1", 1)], completions, now: 2 * day)
        XCTAssertEqual(0, result.themesPractised)
        XCTAssertEqual(0, result.exercisesCompleted)
        XCTAssertEqual(1, result.lessonsDone)
        XCTAssertEqual(2, result.activeStreakDays)
    }

    func testSyncEntriesRollUpOnlyTheRequestedLanguageAndCarryIt() {
        let marks = [
            CompletionMark(kind: ProgressEngine.lesson, itemID: "en-l1", at: 10),
            CompletionMark(kind: ProgressEngine.lesson, itemID: "fr-l1", at: 15, languageID: "fr"),
            CompletionMark(kind: ProgressEngine.listening, itemID: "en-listen-coffee", at: 30, bestScore: 60)]
        let entries = ProgressSync.entries(lessons: [LessonState(lessonID: "en-l1", attempts: 7, lastSeen: 25)],
                                           completions: marks, courses: [course("en", ["en-l1", "fr-l1"])],
                                           listeningIDs: listeningIDs, languageID: "en")
        XCTAssertEqual(["en", "en"], entries.map(\.language))
        XCTAssertEqual(Set(["en-l1", "en-listen-coffee"]), Set(entries.map(\.itemID)))
        let l1 = entries.first { $0.itemID == "en-l1" }!
        XCTAssertEqual(7, l1.attempts)
        XCTAssertEqual(25, (Double(l1.atMilliseconds) - 978_307_200_000) / 1000, accuracy: 0.001)
    }

    func testDownloadPersistUploadKeepsBothInstants() {
        let firstAtMilliseconds: Int64 = 1_792_929_599_000
        let lastAtMilliseconds: Int64 = 1_792_929_600_000
        let firstAt = Double(firstAtMilliseconds) / 1000 - 978_307_200
        let lastAt = Double(lastAtMilliseconds) / 1000 - 978_307_200
        let entries = ProgressSync.entries(lessons: [], completions: [
            CompletionMark(kind: ProgressEngine.lesson, itemID: "en-foundations-u1-to-be", at: firstAt,
                           languageID: "en"),
            CompletionMark(kind: ProgressEngine.lesson, itemID: "en-foundations-u1-to-be", at: lastAt,
                           languageID: "en")],
            courses: [course("en", ["en-foundations-u1-to-be"])], listeningIDs: [], languageID: "en",
            nowUnixMilliseconds: 1_793_000_000_000)

        XCTAssertEqual(1, entries.count)
        XCTAssertEqual(firstAtMilliseconds, entries[0].firstAtMilliseconds)
        XCTAssertEqual(lastAtMilliseconds, entries[0].lastAtMilliseconds)
        XCTAssertEqual(2, entries[0].attempts)
    }

    func testLocalLessonUsesReferenceEpoch() {
        let unixMilliseconds: Int64 = 1_792_929_600_000
        let appleReferenceSeconds = Double(unixMilliseconds) / 1000 - 978_307_200
        let entries = ProgressSync.entries(
            lessons: [LessonState(lessonID: "en-foundations-u1-to-be", attempts: 2,
                                  lastSeen: appleReferenceSeconds)],
            completions: [], courses: [course("en", ["en-foundations-u1-to-be"])],
            listeningIDs: [], languageID: "en", nowUnixMilliseconds: 1_793_000_000_000)

        XCTAssertEqual(1, entries.count)
        XCTAssertEqual(unixMilliseconds, entries[0].firstAtMilliseconds)
        XCTAssertEqual(unixMilliseconds, entries[0].lastAtMilliseconds)
    }

    func testRemoteMergeIgnoresOtherLanguagesAndStampsRestoredMarks() {
        var archive = Archive()
        archive.lessons = [LessonState(lessonID: "en-l1", attempts: 2, lastSeen: 10)]
        archive.completions = [CompletionMark(kind: ProgressEngine.listening, itemID: "en-listen-known", at: 5, bestScore: 40)]
        let merged = ProgressSync.mergeRemote(archive: archive, rows: [
            RemoteProgress(kind: ProgressEngine.lesson, itemID: "en-l1", firstAt: 1, lastAt: 20, attempts: 5, bestScore: 0, language: "en"),
            RemoteProgress(kind: ProgressEngine.lesson, itemID: "fr-l1", firstAt: 2, lastAt: 30, attempts: 9, bestScore: 0, language: "fr"),
            RemoteProgress(kind: ProgressEngine.exerciseSet, itemID: "en-l1", firstAt: 3, lastAt: 40, attempts: 2, bestScore: 80, language: "en"),
            RemoteProgress(kind: ProgressEngine.listening, itemID: "en-listen-known", firstAt: 4, lastAt: 50, attempts: 3, bestScore: 90, language: "en")],
            languageID: "en")
        XCTAssertEqual(5, merged.lessons!.first { $0.lessonID == "en-l1" }!.attempts)
        XCTAssertNil(merged.lessons!.first { $0.lessonID == "fr-l1" })
        let restored = merged.completions!.first { $0.kind == ProgressEngine.exerciseSet }!
        XCTAssertEqual("en", restored.languageID)
        XCTAssertEqual(1, merged.completions!.filter { $0.kind == ProgressEngine.listening }.count)
    }

    func testArchiveMergeUnionsCompletionMarksWithExactTupleDedup() throws {
        var current = Archive()
        current.completions = [CompletionMark(kind: ProgressEngine.lesson, itemID: "en-l1", at: 10)]
        var incoming = Archive()
        incoming.completions = [CompletionMark(kind: ProgressEngine.lesson, itemID: "en-l1", at: 10),
                                CompletionMark(kind: ProgressEngine.lesson, itemID: "en-l1", at: 20)]
        let merged = try current.merging(incoming)
        XCTAssertEqual(2, merged.completions!.count)
        XCTAssertEqual(1, merged.completions!.filter { $0.at == 10 }.count)
        XCTAssertEqual(1, merged.completions!.filter { $0.at == 20 }.count)
    }

    func testArchiveValidationRejectsUnknownKindsScoresAndDuplicateMarks() {
        func decode(_ marks: [CompletionMark]) throws -> Archive {
            var archive = Archive()
            archive.completions = marks
            return try Archive.decode(try archive.encoded())
        }
        XCTAssertThrowsError(try decode([CompletionMark(kind: "streak", itemID: "x", at: 10)]))
        XCTAssertThrowsError(try decode([CompletionMark(kind: ProgressEngine.lesson, itemID: "x", at: 10, bestScore: 101)]))
        XCTAssertThrowsError(try decode([CompletionMark(kind: ProgressEngine.lesson, itemID: "x", at: 10),
                                         CompletionMark(kind: ProgressEngine.lesson, itemID: "x", at: 10)]))
        // Same instant in two languages is two legitimate marks, not a duplicate.
        let bilingual = try decode([CompletionMark(kind: ProgressEngine.conversationTheme, itemID: "restaurant", at: 10, languageID: "en"),
                                    CompletionMark(kind: ProgressEngine.conversationTheme, itemID: "restaurant", at: 10, languageID: "fr")])
        XCTAssertEqual(2, bilingual.completions!.count)
    }
}

private enum ProgressEngineTestsMeta {
    static let enCourse = Course(id: "en-foundations", languageID: "en", title: "Foundations", subtitle: "s",
        symbol: "g", description: "d",
        units: [CourseUnit(id: "u1", title: "U1", subtitle: "", level: "A1", themes: [],
                           lessons: ["en-l1", "en-l2", "en-l3"].map { id in
                               Lesson(id: id, title: "T", goal: "G", intro: LessonIntro(explanation: [], examples: []),
                                      expressions: [], situation: "s", practice: LessonPractice(questions: []))
                           }])
    )
}
