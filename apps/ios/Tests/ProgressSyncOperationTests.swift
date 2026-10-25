import XCTest
@testable import LearningCore

@MainActor final class ProgressSyncOperationTests: XCTestCase {
    private func identity(_ operation: ProgressSyncOperation, account: String = "A", credential: String = "bearer-A") -> ProgressSyncIdentity {
        operation.identity(accountID: account, accessToken: credential)
    }

    func testStaleFetchAfterImportDoesNotWrite() {
        let operation = ProgressSyncOperation()
        let token = operation.begin(identity(operation))!
        var memory = ["replacement-memory"]
        var persisted = ["replacement-row"]
        operation.invalidate()

        let result = operation.writeIfCurrent(token, liveIdentity: identity(operation)) {
            memory.append("stale-remote-row")
            persisted.append("stale-remote-row")
        }

        XCTAssertNil(result)
        XCTAssertEqual(memory, ["replacement-memory"])
        XCTAssertEqual(persisted, ["replacement-row"])
    }

    func testStaleAccountResponseCannotPostReplacementSnapshot() {
        let operation = ProgressSyncOperation()
        let token = operation.begin(identity(operation))!
        let replacement = identity(operation, account: "B", credential: "bearer-B")
        var outbound: [String] = []
        if operation.owns(token, liveIdentity: replacement) { outbound.append("snapshot-B") }
        if operation.owns(token, liveIdentity: identity(operation, credential: "rotated-bearer")) {
            outbound.append("snapshot-with-rotated-credential")
        }
        XCTAssertTrue(outbound.isEmpty)
    }

    func testLateAckCannotAdvanceReplacementGeneration() {
        let operation = ProgressSyncOperation()
        let token = operation.begin(identity(operation))!
        var cursor = "replacement-cursor"
        operation.invalidate()
        let replacement = identity(operation)
        _ = operation.begin(replacement)

        let result = operation.writeIfCurrent(token, liveIdentity: replacement) { cursor = "stale-ack" }

        XCTAssertNil(result)
        XCTAssertEqual(cursor, "replacement-cursor")
    }

    func testOnlyOnePassOwnsWriter() {
        let operation = ProgressSyncOperation()
        let identity = identity(operation)
        let owner = operation.begin(identity)!
        let second = operation.begin(identity)
        var writes: [String] = []
        _ = operation.writeIfCurrent(owner, liveIdentity: identity) { writes.append("one") }
        if let second { _ = operation.writeIfCurrent(second, liveIdentity: identity) { writes.append("two") } }
        XCTAssertNil(second)
        XCTAssertEqual(writes, ["one"])
    }
}
