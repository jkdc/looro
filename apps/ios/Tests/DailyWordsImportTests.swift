import XCTest
@testable import Core

final class DailyWordsImportTests: XCTestCase {
    func testDailyImportKeepsExistingMeaningAndReplayReceipt() {
        let review = DailyWordsReview(reviewID: "review-1", languageID: "en", candidates: [
            DailyWordCandidate(candidateID: "candidate-1", kind: "word", text: "Useful", meaning: "useful", confidence: "supported")
        ])
        let existing = [DailyWordExisting(text: "useful", languageID: "en", bars: 2, attempts: 3, meaning: "my meaning")]
        let first = planDailyWordsImport(result: review, owner: "owner-a", language: "en", existing: existing,
                                         receipts: [], suppressions: [], now: 10)
        XCTAssertEqual(first.map(\.action), [.keep])
        XCTAssertEqual(first.first?.meaning, "my meaning")
        XCTAssertEqual(first.first?.originKey, "review:review-1:candidate-1")
        let replay = planDailyWordsImport(result: review, owner: "owner-a", language: "en", existing: [],
                                          receipts: ["review:review-1:candidate-1"], suppressions: [], now: 11)
        XCTAssertEqual(replay.map(\.action), [.keep])
    }

    func testDeletedCandidateStaysSuppressedAndCapacityDoesNotEvict() {
        let review = DailyWordsReview(reviewID: "review-2", languageID: "en", candidates: [
            DailyWordCandidate(candidateID: "candidate-2", kind: "expression", text: "on the other hand", meaning: "contrast phrase", confidence: "supported")
        ])
        let suppressed = planDailyWordsImport(result: review, owner: "owner-a", language: "en", existing: [],
                                              receipts: [], suppressions: ["en:on the other hand"], now: 10)
        XCTAssertEqual(suppressed.map(\.action), [.suppress])
        let full = (1...2000).map { DailyWordExisting(text: "word\($0)", languageID: "en", bars: 0, attempts: 0, meaning: "") }
        let pending = planDailyWordsImport(result: review, owner: "owner-a", language: "en", existing: full,
                                           receipts: [], suppressions: [], now: 10)
        XCTAssertEqual(pending.map(\.action), [.pendingCapacity])
    }

    func testCapacityIsReservedSequentiallyWithinCandidateBatch() {
        let review = DailyWordsReview(reviewID: "review-3", languageID: "en", candidates: [
            DailyWordCandidate(candidateID: "candidate-3", kind: "word", text: "first", meaning: "first", confidence: "supported"),
            DailyWordCandidate(candidateID: "candidate-4", kind: "word", text: "second", meaning: "second", confidence: "supported")
        ])
        let existing = (1...1999).map { DailyWordExisting(text: "word\($0)", languageID: "en", bars: 0, attempts: 0, meaning: "") }
        let plans = planDailyWordsImport(result: review, owner: "owner-a", language: "en", existing: existing,
                                         receipts: [], suppressions: [], now: 10)
        XCTAssertEqual(plans.map(\.action), [.insert, .pendingCapacity])
    }

    func testPendingCapacityCandidateCanBeRetriedAfterSpaceIsFreed() {
        let review = DailyWordsReview(reviewID: "review-capacity-retry", languageID: "en", candidates: [
            DailyWordCandidate(candidateID: "candidate-retry", kind: "word", text: "seedling", meaning: "young plant", confidence: "supported")
        ])
        let full = (1...2000).map { DailyWordExisting(text: "word\($0)", languageID: "en", bars: 0, attempts: 0, meaning: "") }
        let origin = "review:review-capacity-retry:candidate-retry"
        let pending = planDailyWordsImport(result: review, owner: "owner-a", language: "en", existing: full,
            receipts: [], suppressions: [], now: 10)
        XCTAssertEqual(pending.map(\.action), [.pendingCapacity])
        let afterSpaceFreed = Array(full.dropLast())
        let retried = planDailyWordsImport(result: review, owner: "owner-a", language: "en", existing: afterSpaceFreed,
            receipts: [], suppressions: [], now: 11)
        XCTAssertEqual(retried.map(\.action), [.insert])
        XCTAssertEqual(retried.first?.originKey, origin)
    }
}
