import XCTest
@testable import LearningCore

final class ProgressProjectionTests: XCTestCase {
    func testRemoteBestScoreWinsAndRollupIsIdempotentInEitherOrder() {
        let local = ProgressAggregate(ownerAccountID: "account-a", languageID: "es", kind: "exercise",
                                      itemID: "unit-1", firstAt: 10, lastAt: 20, attempts: 2, bestScore: 40)
        let remote = ProgressAggregate(ownerAccountID: "account-a", languageID: "es", kind: "exercise",
                                       itemID: "unit-1", firstAt: 5, lastAt: 30, attempts: 4, bestScore: 90)

        let localThenRemote = ProgressProjection.merge(ownerAccountID: "account-a", languageID: "es",
                                                       local: [local], remote: [remote]).first!
        let remoteThenLocal = ProgressProjection.merge(ownerAccountID: "account-a", languageID: "es",
                                                       local: [remote], remote: [local]).first!
        let replayed = ProgressProjection.merge(ownerAccountID: "account-a", languageID: "es",
                                                local: [localThenRemote], remote: [remote]).first!

        XCTAssertEqual(ProgressAggregate(ownerAccountID: "account-a", languageID: "es", kind: "exercise",
                                         itemID: "unit-1", firstAt: 5, lastAt: 30, attempts: 4, bestScore: 90),
                       localThenRemote)
        XCTAssertEqual(localThenRemote, remoteThenLocal)
        XCTAssertEqual(localThenRemote, replayed)
    }

    func testOtherOwnerAndUnresolvedLanguageDoNotContribute() {
        let local = ProgressAggregate(ownerAccountID: "account-a", languageID: "es", kind: "lesson",
                                      itemID: "unit-1", firstAt: 10, lastAt: 20, attempts: 1, bestScore: 0)
        let remote = [
            ProgressAggregate(ownerAccountID: "account-b", languageID: "es", kind: "lesson",
                              itemID: "unit-1", firstAt: 1, lastAt: 90, attempts: 9, bestScore: 90),
            ProgressAggregate(ownerAccountID: "account-a", languageID: "fr", kind: "lesson",
                              itemID: "unit-1", firstAt: 2, lastAt: 80, attempts: 8, bestScore: 80),
            ProgressAggregate(ownerAccountID: "account-a", languageID: nil, kind: "lesson",
                              itemID: "unit-1", firstAt: 3, lastAt: 70, attempts: 7, bestScore: 70),
        ]

        XCTAssertEqual([local], ProgressProjection.merge(ownerAccountID: "account-a", languageID: "es",
                                                         local: [local], remote: remote))
    }

    func testProjectionDoesNotAppendLocalEvents() {
        let input = [ProgressAggregate(ownerAccountID: "account-a", languageID: "es", kind: "reading",
                                       itemID: "r1", firstAt: 1, lastAt: 2, attempts: 1, bestScore: 0)]

        XCTAssertEqual(input, ProgressProjection.merge(ownerAccountID: "account-a", languageID: "es",
                                                       local: input, remote: []))
        XCTAssertEqual(1, input.count)
    }
}
