import XCTest
@testable import MuralCore

/// Day boundaries are pinned to UTC on both platforms (see the Kotlin LearningStatsTest).
final class LearningStatsTests: XCTestCase {
    private var calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    /// `unixDay` is a `LocalDate.toEpochDay()` equivalent; sessions carry reference dates.
    private func session(unixDay: Int, hour: Int = 10, minutes: Double = 5) -> SessionRecord {
        var s = SessionRecord(languageID: "en")
        s.startedAt = Date(timeIntervalSince1970: Double(unixDay * 86400 + hour * 3600))
        s.voiceSeconds = minutes * 60
        return s
    }

    private func nowAt(unixDay: Int) -> Date {
        Date(timeIntervalSince1970: Double(unixDay * 86400))
    }

    private func stats(_ sessions: [SessionRecord], today: Int,
                       learner: LearnerState = LearnerState(challenge: 0, observationCount: 0, nextGoal: "", capabilities: [], words: [])) -> LearningStats {
        LearningEngine.stats(sessions: sessions, learner: learner, calendar: calendar, now: nowAt(unixDay: today))
    }

    func testEmptyArchiveProjectsToZeros() {
        let result = stats([], today: 20_000)
        XCTAssertEqual(result.totalSessions, 0)
        XCTAssertEqual(result.totalVoiceMinutes, 0)
        XCTAssertEqual(result.activeStreakDays, 0)
        XCTAssertEqual(result.longestStreakDays, 0)
        XCTAssertEqual(result.wordsByBars, [0, 0, 0, 0])
        XCTAssertEqual(result.last14DaysMinutes.count, 14)
    }

    func testConsecutiveDaysBuildAStreakAcrossAMonthBoundary() {
        let jan30 = Int(calendar.date(from: DateComponents(year: 2024, month: 1, day: 30))!.timeIntervalSince1970 / 86400)
        let sessions = [session(unixDay: jan30), session(unixDay: jan30 + 1), session(unixDay: jan30 + 2), session(unixDay: jan30 + 3)]
        let result = stats(sessions, today: jan30 + 3)
        XCTAssertEqual(result.activeStreakDays, 4)
        XCTAssertEqual(result.longestStreakDays, 4)
        XCTAssertEqual(result.totalVoiceMinutes, 20)
    }

    func testAGapDayKeepsTheLongestStreakAndRestartsTheActiveOne() {
        let result = stats([session(unixDay: 100), session(unixDay: 101), session(unixDay: 102), session(unixDay: 104)], today: 104)
        XCTAssertEqual(result.activeStreakDays, 1)
        XCTAssertEqual(result.longestStreakDays, 3)
    }

    func testYesterdayAnchorsTheActiveStreakWhenTodayHasNoSession() {
        let result = stats([session(unixDay: 100), session(unixDay: 101)], today: 102)
        XCTAssertEqual(result.activeStreakDays, 2)
    }

    func testSeveralSessionsOnOneDayCountOnceForTheStreak() {
        let result = stats([session(unixDay: 100, hour: 9), session(unixDay: 100, hour: 20)], today: 100)
        XCTAssertEqual(result.activeStreakDays, 1)
        XCTAssertEqual(result.totalVoiceMinutes, 10)
    }

    func testHistogramCoversTheLastFourteenDaysOldestFirst() {
        let result = stats([session(unixDay: 987, minutes: 7.5), session(unixDay: 1_000, minutes: 2)], today: 1_000)
        XCTAssertEqual(result.last14DaysMinutes.count, 14)
        XCTAssertEqual(result.last14DaysMinutes.first ?? -1, 7.5, accuracy: 0.0001)
        XCTAssertEqual(result.last14DaysMinutes.last ?? -1, 2, accuracy: 0.0001)
        XCTAssertEqual(result.last14DaysMinutes[1] ?? -1, 0, accuracy: 0.0001)
    }

    func testWordBarsAndCapabilitiesComeFromTheLearnerProjection() {
        func word(bars: Int) -> WordState {
            WordState(id: "w\(bars)", lemma: "lemma\(bars)", meaning: "sense", form: "form", example: "example",
                      bars: bars, understandingCount: 0, independentCount: 0, lastSeen: .now, dueAt: .now)
        }
        let learner = LearnerState(challenge: 2, observationCount: 4, nextGoal: "goal",
                                   capabilities: ["Can order food", "Can greet people"],
                                   words: [word(bars: 0), word(bars: 2), word(bars: 3), word(bars: 3)])
        let result = stats([], today: 100, learner: learner)
        XCTAssertEqual(result.wordsByBars, [1, 0, 1, 2])
        XCTAssertEqual(result.capabilityCount, 2)
        XCTAssertEqual(result.latestCapability, "Can greet people")
    }
}
