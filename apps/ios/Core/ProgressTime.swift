import Foundation

/// Checked conversion between progress wire milliseconds and Apple-reference seconds.
enum ProgressTime {
    private static let maxSafeInteger = 9_007_199_254_740_991.0

    static func fromUnixMilliseconds(
        _ unixMilliseconds: Double,
        nowUnixMilliseconds: Double,
        earliestUnixMilliseconds: Double = ProgressSyncContractData.earliestUnixMilliseconds
    ) -> Double? {
        let latestUnixMilliseconds = nowUnixMilliseconds + ProgressSyncContractData.futureSkewMilliseconds
        guard isSafeInteger(nowUnixMilliseconds), isSafeInteger(earliestUnixMilliseconds),
              isSafeInteger(latestUnixMilliseconds),
              earliestUnixMilliseconds <= latestUnixMilliseconds,
              isSafeInteger(unixMilliseconds),
              unixMilliseconds >= earliestUnixMilliseconds,
              unixMilliseconds <= latestUnixMilliseconds else { return nil }

        return unixMilliseconds / 1000 - ProgressSyncContractData.appleEpochOffsetSeconds
    }

    static func toUnixMilliseconds(
        _ appleReferenceSeconds: Double,
        nowUnixMilliseconds: Double,
        earliestUnixMilliseconds: Double = ProgressSyncContractData.earliestUnixMilliseconds
    ) -> Int64? {
        guard appleReferenceSeconds.isFinite else { return nil }
        let rawUnixMilliseconds =
            (appleReferenceSeconds + ProgressSyncContractData.appleEpochOffsetSeconds) * 1000
        guard rawUnixMilliseconds.isFinite, abs(rawUnixMilliseconds) <= maxSafeInteger else { return nil }

        let roundedUnixMilliseconds = rawUnixMilliseconds.rounded()
        guard fromUnixMilliseconds(
            roundedUnixMilliseconds,
            nowUnixMilliseconds: nowUnixMilliseconds,
            earliestUnixMilliseconds: earliestUnixMilliseconds
        ) != nil else { return nil }
        return Int64(roundedUnixMilliseconds)
    }

    private static func isSafeInteger(_ value: Double) -> Bool {
        value.isFinite && abs(value) <= maxSafeInteger && value.rounded(.towardZero) == value
    }
}
