import XCTest
@testable import LearningCore

final class ProgressTimeTests: XCTestCase {
    private let nowUnixMilliseconds = 1_792_929_600_000.0

    func testDistinctProgressInstantsRoundTrip() {
        let firstAt = 1_792_929_599_000.0
        let lastAt = 1_792_929_600_000.0

        guard let firstLocal = ProgressTime.fromUnixMilliseconds(firstAt, nowUnixMilliseconds: nowUnixMilliseconds),
              let lastLocal = ProgressTime.fromUnixMilliseconds(lastAt, nowUnixMilliseconds: nowUnixMilliseconds),
              let firstWire = ProgressTime.toUnixMilliseconds(firstLocal, nowUnixMilliseconds: nowUnixMilliseconds),
              let lastWire = ProgressTime.toUnixMilliseconds(lastLocal, nowUnixMilliseconds: nowUnixMilliseconds) else {
            XCTFail("Expected supported progress instants to convert")
            return
        }

        XCTAssertEqual(Int64(1_792_929_599_000), firstWire)
        XCTAssertEqual(Int64(1_792_929_600_000), lastWire)
        XCTAssertEqual(1, lastLocal - firstLocal, accuracy: 0)
    }

    func testFirstSupportedMillisecondIsIncludedButDatabaseCutoffIsExcluded() {
        let earliestSupported = ProgressSyncContractData.earliestUnixMilliseconds
        guard let local = ProgressTime.fromUnixMilliseconds(earliestSupported, nowUnixMilliseconds: nowUnixMilliseconds),
              let wire = ProgressTime.toUnixMilliseconds(local, nowUnixMilliseconds: nowUnixMilliseconds) else {
            XCTFail("Expected the first supported progress millisecond to convert")
            return
        }

        XCTAssertEqual(Int64(earliestSupported), wire)
        XCTAssertNil(ProgressTime.fromUnixMilliseconds(earliestSupported - 1, nowUnixMilliseconds: nowUnixMilliseconds))
    }

    func testUnixEpochConversionIsDistinctFromUnsupportedProgressRecordZero() {
        let local = ProgressTime.fromUnixMilliseconds(
            0,
            nowUnixMilliseconds: nowUnixMilliseconds,
            earliestUnixMilliseconds: 0
        )

        guard let local = local,
              let wireEpochZero = ProgressTime.toUnixMilliseconds(
                local,
                nowUnixMilliseconds: nowUnixMilliseconds,
                earliestUnixMilliseconds: 0
              ) else {
            XCTFail("Expected Unix epoch zero to round-trip through the pure conversion")
            return
        }

        XCTAssertEqual(-978_307_200.0, local)
        XCTAssertEqual(Int64(0), wireEpochZero)
        XCTAssertNil(ProgressTime.fromUnixMilliseconds(0, nowUnixMilliseconds: nowUnixMilliseconds))
    }

    func testEncodingRoundsToTheNearestMillisecond() {
        guard let converted = ProgressTime.fromUnixMilliseconds(1_792_929_599_000, nowUnixMilliseconds: nowUnixMilliseconds),
              let wire = ProgressTime.toUnixMilliseconds(
                converted + 0.0006,
                nowUnixMilliseconds: nowUnixMilliseconds
              ) else {
            XCTFail("Expected a valid progress instant to encode")
            return
        }

        XCTAssertEqual(Int64(1_792_929_599_001), wire)
    }

    func testInvalidTimeIsNotClamped() {
        XCTAssertNil(ProgressTime.fromUnixMilliseconds(.nan, nowUnixMilliseconds: nowUnixMilliseconds))
        XCTAssertNil(ProgressTime.fromUnixMilliseconds(.infinity, nowUnixMilliseconds: nowUnixMilliseconds))
        XCTAssertNil(ProgressTime.fromUnixMilliseconds(9_007_199_254_740_992, nowUnixMilliseconds: nowUnixMilliseconds))
        XCTAssertNil(ProgressTime.fromUnixMilliseconds(1_735_689_600_000, nowUnixMilliseconds: nowUnixMilliseconds))
        XCTAssertNil(ProgressTime.fromUnixMilliseconds(1_735_689_600_000, nowUnixMilliseconds: 1_735_516_799_999))
        XCTAssertNil(ProgressTime.fromUnixMilliseconds(nowUnixMilliseconds + 172_800_001, nowUnixMilliseconds: nowUnixMilliseconds))
        XCTAssertNil(ProgressTime.toUnixMilliseconds(.nan, nowUnixMilliseconds: nowUnixMilliseconds))
        XCTAssertNil(ProgressTime.toUnixMilliseconds(.infinity, nowUnixMilliseconds: nowUnixMilliseconds))
        XCTAssertNil(ProgressTime.toUnixMilliseconds(.greatestFiniteMagnitude, nowUnixMilliseconds: nowUnixMilliseconds))
    }
}
