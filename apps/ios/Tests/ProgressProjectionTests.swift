import XCTest
@testable import LearningCore

final class ProgressProjectionTests: XCTestCase {
    func testRemote90ImprovesLocal40InEitherOrder() {
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

    func testRemoteOnlyLessonAttemptsAreAvailableToCourseRows() {
        let remote = [ProgressAggregate(ownerAccountID: "account-a", languageID: "es", kind: "lesson",
            itemID: "lesson-1", firstAt: 1_700_000_000_000, lastAt: 1_700_000_001_000,
            attempts: 3, bestScore: 0)]

        let lessons = ProgressProjection.effectiveLessons(ownerAccountID: "account-a", languageID: "es",
            local: [], remote: remote, allowedIDs: ["lesson-1"])

        XCTAssertEqual(3, lessons.first?.attempts)
    }

    func testNextUploadRetainsRemoteBestAndFirstAtForAllKinds() {
        let owner = "account-a"
        let rows = ProgressEngine.kinds.map { kind in
            ProgressAggregate(ownerAccountID: owner, languageID: "en", kind: kind, itemID: "item-\(kind)",
                firstAt: 1_700_000_000_000, lastAt: 1_700_000_000_500, attempts: 4, bestScore: 90)
        }
        let local = [CompletionMark(kind: ProgressEngine.exerciseSet, itemID: "item-\(ProgressEngine.exerciseSet)",
            at: 1_699_999_999.5 - 978_307_200, bestScore: 40, languageID: "en")]
        let entries = ProgressSync.entries(lessons: [], completions: local, courses: [], listeningIDs: [],
            languageID: "en", ownerAccountID: owner, remote: rows)

        XCTAssertEqual(Set(ProgressEngine.kinds), Set(entries.map(\.kind)))
        XCTAssertEqual(1_700_000_000_000, entries.first?.firstAtMilliseconds)
        XCTAssertEqual(90, entries.first?.bestScore)
        XCTAssertEqual(4, entries.first(where: { $0.kind == ProgressEngine.exerciseSet })?.attempts)
    }
}
