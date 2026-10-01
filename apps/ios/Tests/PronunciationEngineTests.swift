import XCTest
@testable import LearningCore

final class PronunciationEngineTests: XCTestCase {
    func testExactMatchScoresOneHundredRegardlessOfCaseAndPunctuation() {
        XCTAssertEqual(100, PronunciationEngine.score(target: "This is my brother.", transcript: "this is my brother"))
        XCTAssertEqual(100, PronunciationEngine.score(target: "Three thin thieves", transcript: "  Three   THIN thieves!! "))
    }

    func testOneWrongWordInFiveScoresEighty() {
        XCTAssertEqual(80, PronunciationEngine.score(target: "I would like the fish", transcript: "I would like the dish"))
    }

    func testADroppedWordAndAnExtraWordCostTheSame() {
        XCTAssertEqual(80, PronunciationEngine.score(target: "I would like the fish", transcript: "I would like fish"))
        XCTAssertEqual(80, PronunciationEngine.score(target: "I would like the fish", transcript: "I would really like the fish"))
    }

    func testEmptyTranscriptScoresZeroAndNothingScoresNothing() {
        XCTAssertEqual(0, PronunciationEngine.score(target: "Thank you", transcript: ""))
        XCTAssertEqual(100, PronunciationEngine.score(target: "", transcript: ""))
        XCTAssertEqual(0, PronunciationEngine.score(target: "", transcript: "hello"))
    }

    func testThresholdPassesAtFiftyAndFailsAtFortyNine() {
        XCTAssertEqual(50, PronunciationEngine.score(target: "thank you", transcript: "thank"))
        XCTAssertTrue(PronunciationEngine.passed(50))
        XCTAssertFalse(PronunciationEngine.passed(49))
    }
}
