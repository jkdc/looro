import XCTest
@testable import LearningCore

/// Mirrors the JVM `RecallEngineTest`; runs under the macOS gate.
final class RecallEngineTests: XCTestCase {
    private var archive = Archive()
    private let lesson = CourseRegistry.lesson(id: "en-foundations-u1-to-be")!

    func testLessonPracticeSeedsExpressionsIdempotently() {
        let now = 1_000.0
        RecallEngine.seedFromLesson(&archive, lesson: lesson, now: now)
        XCTAssertEqual(archive.expressions?.count, lesson.expressions.count)
        XCTAssertTrue(archive.expressions!.allSatisfy { $0.source == "lesson" && $0.lessonID == lesson.id && $0.bars == 0 && $0.dueAt == now })
        RecallEngine.seedFromLesson(&archive, lesson: lesson, now: now + 5)
        XCTAssertEqual(archive.expressions?.count, lesson.expressions.count)
    }

    func testLookupSaveUpsertsOnNormalizedText() {
        XCTAssertTrue(RecallEngine.saveLookup(&archive, text: "  Grandmother ", meaning: "the mother of your parent", now: 100))
        XCTAssertTrue(RecallEngine.saveLookup(&archive, text: "grandmother", meaning: "the mother of your parent", now: 200))
        XCTAssertEqual(archive.expressions?.count, 1)
        XCTAssertEqual(archive.expressions?.first?.source, "lookup")
        XCTAssertNil(archive.expressions?.first?.lessonID)
    }

    func testGradingPromotesOnTheLadderAndLapsesDropToBarOne() {
        RecallEngine.saveLookup(&archive, text: "soup", meaning: "a hot liquid food", now: 0)
        RecallEngine.grade(&archive, text: " SOUP ", correct: true, now: 100)
        var state = archive.expressions!.first!
        XCTAssertEqual(state.bars, 1)
        XCTAssertEqual(state.dueAt, 100 + 86_400, accuracy: 0.001)
        RecallEngine.grade(&archive, text: "soup", correct: true, now: 200)
        state = archive.expressions!.first!
        XCTAssertEqual(state.bars, 2)
        XCTAssertEqual(state.dueAt, 200 + 4 * 86_400, accuracy: 0.001)
        RecallEngine.grade(&archive, text: "soup", correct: false, now: 300)
        state = archive.expressions!.first!
        XCTAssertEqual(state.bars, 1)
        XCTAssertEqual(state.dueAt, 300 + 86_400, accuracy: 0.001)
        XCTAssertEqual(state.attempts, 3)
    }

    func testDueQueueOnlyHoldsDueExpressionsOldestFirst() {
        RecallEngine.saveLookup(&archive, text: "soup", meaning: "a hot liquid food", now: 100)
        RecallEngine.saveLookup(&archive, text: "grandmother", meaning: "the mother of your parent", now: 200)
        RecallEngine.grade(&archive, text: "soup", correct: true, now: 100)
        RecallEngine.grade(&archive, text: "grandmother", correct: true, now: 200)
        XCTAssertEqual(RecallEngine.dueCount(archive, now: 150), 0)
        let dueNow = RecallEngine.due(archive, now: 100 + 3 * 86_400)
        XCTAssertEqual(dueNow.count, 2)
        XCTAssertEqual(dueNow.first?.text, "soup")
        XCTAssertTrue(zip(dueNow, dueNow.dropFirst()).allSatisfy { $0.dueAt <= $1.dueAt })
    }

    func testArchivesWithoutExpressionsStillDecode() throws {
        let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../shared/fixtures/cross-platform")
        let data = try Data(contentsOf: directory.appendingPathComponent("archive.json"))
        let archive = try Archive.decode(data)
        XCTAssertEqual(archive.expressions?.count, 2)
        var legacy = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        legacy.removeValue(forKey: "expressions")
        let decoded = try Archive.decode(try JSONSerialization.data(withJSONObject: legacy))
        XCTAssertNil(decoded.expressions)
    }
}
