import CryptoKit
import Foundation

/// A short-lived account and local-data identity; credential material is fingerprinted, never stored.
public struct ProgressSyncIdentity: Equatable, Sendable {
    public let accountID: String
    public let credentialFingerprint: String
    public let dataGeneration: UInt64
}

/// Gives one progress pass exclusive ownership and fences writes after identity changes.
@MainActor public final class ProgressSyncOperation {
    public struct Token: Equatable, Sendable {
        fileprivate let identity: ProgressSyncIdentity
        fileprivate let serial: UInt64
    }

    private var generation: UInt64 = 0
    private var serial: UInt64 = 0
    private var active: Token?

    public init() {}

    public func identity(accountID: String, accessToken: String) -> ProgressSyncIdentity {
        let digest = SHA256.hash(data: Data(accessToken.utf8))
        return ProgressSyncIdentity(accountID: accountID,
                                    credentialFingerprint: digest.map { String(format: "%02x", $0) }.joined(),
                                    dataGeneration: generation)
    }

    public func begin(_ identity: ProgressSyncIdentity) -> Token? {
        guard active == nil else { return nil }
        serial &+= 1
        let token = Token(identity: identity, serial: serial)
        active = token
        return token
    }

    public func owns(_ token: Token, liveIdentity: ProgressSyncIdentity) -> Bool {
        active == token && token.identity == liveIdentity
    }

    /// The identity check and storage mutation are one non-suspending MainActor operation.
    @discardableResult
    public func writeIfCurrent<T>(_ token: Token, liveIdentity: ProgressSyncIdentity,
                                  _ write: () throws -> T) rethrows -> T? {
        guard owns(token, liveIdentity: liveIdentity) else { return nil }
        return try write()
    }

    public func invalidate() {
        generation &+= 1
        active = nil
    }

    public func finish(_ token: Token) {
        if active == token { active = nil }
    }
}
