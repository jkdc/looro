import Foundation

/// One owner/language-scoped cumulative progress identity. Times are Unix milliseconds.
public struct ProgressAggregate: Equatable, Sendable {
    public var ownerAccountID: String
    public var languageID: String?
    public var kind: String
    public var itemID: String
    public var firstAt: Int64
    public var lastAt: Int64
    public var attempts: Int
    public var bestScore: Int

    public init(ownerAccountID: String, languageID: String?, kind: String, itemID: String,
                firstAt: Int64, lastAt: Int64, attempts: Int, bestScore: Int) {
        self.ownerAccountID = ownerAccountID
        self.languageID = languageID
        self.kind = kind
        self.itemID = itemID
        self.firstAt = firstAt
        self.lastAt = lastAt
        self.attempts = attempts
        self.bestScore = bestScore
    }
}

/// Pure projection of local and remote rollups; it never writes activity history.
public enum ProgressProjection {
    public static func merge(ownerAccountID: String, languageID: String,
                             local: [ProgressAggregate], remote: [ProgressAggregate]) -> [ProgressAggregate] {
        struct Identity: Hashable {
            let owner: String
            let language: String?
            let kind: String
            let item: String
        }
        var merged: [Identity: ProgressAggregate] = [:]
        for entry in (local + remote) where entry.ownerAccountID == ownerAccountID && entry.languageID == languageID {
            let identity = Identity(owner: entry.ownerAccountID, language: entry.languageID,
                                    kind: entry.kind, item: entry.itemID)
            guard let previous = merged[identity] else {
                merged[identity] = entry
                continue
            }
            merged[identity] = ProgressAggregate(
                ownerAccountID: entry.ownerAccountID,
                languageID: entry.languageID,
                kind: entry.kind,
                itemID: entry.itemID,
                firstAt: min(previous.firstAt, entry.firstAt),
                lastAt: max(previous.lastAt, entry.lastAt),
                attempts: max(previous.attempts, entry.attempts),
                bestScore: max(previous.bestScore, entry.bestScore))
        }
        return merged.values.sorted {
            let lhs = [$0.ownerAccountID, $0.languageID ?? "", $0.kind, $0.itemID]
            let rhs = [$1.ownerAccountID, $1.languageID ?? "", $1.kind, $1.itemID]
            return lhs.lexicographicallyPrecedes(rhs)
        }
    }
}
