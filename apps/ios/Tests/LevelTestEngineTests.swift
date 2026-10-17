import XCTest
@testable import LearningCore

final class LevelTestEngineTests: XCTestCase {
    func testWeekIndexUsesIsoWeekYearAcrossCalendarYearBoundary() {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = calendar.date(from: DateComponents(year: 2021, month: 1, day: 1))!
        XCTAssertEqual(202053, LevelTestEngine.weekIndex(date: date))
    }

    func testB1Week202641MatchesTheSharedCompositionGolden() throws {
        let level = try XCTUnwrap(LevelTestsRegistry.level(languageID: "en", level: "B1"))
        let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../shared/leveltests/weekly-goldens.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: fixture)) as! [String: Any]
        let vector = (json["vectors"] as! [[String: Any]])[0]
        let week = vector["weekIndex"] as! Int
        let exam = LevelTestEngine.compose(level: level, weekIndex: week)
        XCTAssertEqual(vector["items"] as? [String], exam.items.map(\.id))
        XCTAssertEqual(vector["readingPassageIDs"] as? [String], exam.readingPassageIDs)
        XCTAssertEqual(vector["listeningPassageIDs"] as? [String], exam.listeningPassageIDs)
        XCTAssertEqual(12, exam.items.filter { $0.section == .recognition }.count)
        XCTAssertEqual(3, exam.items.filter { $0.section == .writing }.count)
        XCTAssertEqual(2, exam.readingPassageIDs.count)
        XCTAssertEqual(2, exam.listeningPassageIDs.count)
        XCTAssertTrue(Set(exam.readingPassageIDs).isDisjoint(with: exam.listeningPassageIDs))
        XCTAssertEqual(vector["languageID"] as? String, level.languageID)
        XCTAssertEqual(vector["level"] as? String, exam.level)
    }

    func testGradingUsesTheOverallAndPerSectionThresholds() throws {
        let level = try XCTUnwrap(LevelTestsRegistry.level(languageID: "en", level: "B1"))
        let exam = LevelTestEngine.compose(level: level, weekIndex: 202641)
        let attempts = Dictionary(uniqueKeysWithValues: exam.items.map { ($0.id, $0.exercise.answers[0]) })
        let result = LevelTestEngine.grade(level: level, exam: exam, attempts: attempts)
        XCTAssertEqual(100, result.overall)
        XCTAssertTrue(result.passed)
        XCTAssertEqual("B2", result.nextBand)

        let recognitionOnly = exam.items.filter { $0.section == .recognition }
        var failedAttempts = attempts
        recognitionOnly.forEach { failedAttempts[$0.id] = "not an accepted answer" }
        let failed = LevelTestEngine.grade(level: level, exam: exam, attempts: failedAttempts)
        XCTAssertFalse(failed.passed)
        XCTAssertNil(failed.nextBand)
        XCTAssertTrue(failed.weakSections.contains("recognition"))
    }
}
