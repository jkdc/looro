import XCTest
@testable import App
import LearningCore

@MainActor private final class ProgressSyncFixture {
    var session: ProgressSyncSession? = .init(accountID: "account-A", accessToken: "credential-A")
    var pendingFetch: CheckedContinuation<[RemoteProgress]?, Never>?
    var suspendFetch = false
    var fetchCount = 0
    var sent = 0
    var fetchStarted: XCTestExpectation?
    var sendStarted: XCTestExpectation?
    var finished: XCTestExpectation?
    var suspendSend = false
    var pendingSend: CheckedContinuation<Bool, Never>?

    func adapter(store: LearningStore, sendExpectation: XCTestExpectation? = nil) -> ConversationProgressSyncAdapter {
        ConversationProgressSyncAdapter(
            loadSession: { self.session },
            fetch: { _ in
                self.fetchCount += 1
                if self.suspendFetch && self.fetchCount == 1 {
                    return await withCheckedContinuation { continuation in
                        self.pendingFetch = continuation
                        self.fetchStarted?.fulfill()
                    }
                }
                return [RemoteProgress(kind: ProgressEngine.lesson, itemID: "lesson-fixture", firstAt: 1,
                                       lastAt: 2, attempts: 3, bestScore: 80, language: "en")]
            },
            archiveSnapshot: { try store.exportData() },
            applyRemote: { rows in store.applyRemoteProgress(rows) },
            send: { _, _ in
                self.sent += 1
                sendExpectation?.fulfill()
                if self.suspendSend && self.sent == 1 {
                    return await withCheckedContinuation { continuation in
                        self.pendingSend = continuation
                        self.sendStarted?.fulfill()
                    }
                }
                return true
            },
            onFinish: { self.finished?.fulfill() })
    }
}

@MainActor final class ProgressSyncOperationAppTests: XCTestCase {
    private func identity(_ operation: ProgressSyncOperation, account: String = "account-A", credential: String = "credential-A") -> ProgressSyncIdentity {
        operation.identity(accountID: account, accessToken: credential)
    }

    func testRecordLessonAttemptStoresAppleReferenceTimestamp() throws {
        let store = try LearningStore(inMemory: true)
        let before = Date.now.timeIntervalSinceReferenceDate

        store.recordLessonAttempt("lesson-fixture")

        let lastSeen = try XCTUnwrap(store.archive.lessons?.first { $0.lessonID == "lesson-fixture" }?.lastSeen)
        let after = Date.now.timeIntervalSinceReferenceDate
        XCTAssertGreaterThanOrEqual(lastSeen, before)
        XCTAssertLessThanOrEqual(lastSeen, after)
    }

    func testStaleFetchAfterImportPreservesReplacementMemoryAndRows() {
        let operation = ProgressSyncOperation()
        let token = operation.begin(identity(operation))!
        var memory = ["imported-memory"]
        var rows = ["imported-row"]
        operation.invalidate()

        let applied = operation.writeIfCurrent(token, liveIdentity: identity(operation)) {
            memory.append("stale-fetch")
            rows.append("stale-fetch")
        }

        XCTAssertNil(applied)
        XCTAssertEqual(memory, ["imported-memory"])
        XCTAssertEqual(rows, ["imported-row"])
    }

    func testAccountReplacementCannotPostCapturedSnapshot() {
        let operation = ProgressSyncOperation()
        let token = operation.begin(identity(operation))!
        let replacement = identity(operation, account: "account-B", credential: "credential-B")
        var requests: [String] = []
        if operation.owns(token, liveIdentity: replacement) { requests.append("snapshot-B") }
        if operation.owns(token, liveIdentity: identity(operation, credential: "rotated-credential")) {
            requests.append("snapshot-with-rotated-credential")
        }
        XCTAssertTrue(requests.isEmpty)
    }

    func testLateAcknowledgementCannotMutateNewGeneration() {
        let operation = ProgressSyncOperation()
        let old = operation.begin(identity(operation))!
        operation.invalidate()
        let newIdentity = identity(operation)
        let current = operation.begin(newIdentity)!
        var cursor = "new-generation-cursor"

        let acknowledgement = operation.writeIfCurrent(old, liveIdentity: newIdentity) { cursor = "old-ack" }
        XCTAssertNil(acknowledgement)
        XCTAssertEqual(cursor, "new-generation-cursor")
        XCTAssertTrue(operation.owns(current, liveIdentity: newIdentity))
    }

    func testConcurrentPassCannotAcquireWriter() {
        let operation = ProgressSyncOperation()
        let currentIdentity = identity(operation)
        let owner = operation.begin(currentIdentity)!
        XCTAssertNil(operation.begin(currentIdentity))
        var writes: [String] = []
        _ = operation.writeIfCurrent(owner, liveIdentity: currentIdentity) { writes.append("current") }
        XCTAssertEqual(writes, ["current"])
    }

    func testCoordinatorUsesInjectedNetworkAndStorageEdges() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = ProgressSyncFixture()
        let finished = expectation(description: "coordinator sync completed")
        fixture.finished = finished
        let sent = expectation(description: "coordinator sends after applying fetched progress")
        let coordinator = ConversationCoordinator(store: store, progressSyncAdapter: fixture.adapter(store: store, sendExpectation: sent))

        coordinator.syncProgress()
        await fulfillment(of: [sent], timeout: 2)
        await fulfillment(of: [finished], timeout: 2)

        XCTAssertEqual(fixture.sent, 1)
        XCTAssertEqual(store.archive.lessons?.first(where: { $0.lessonID == "lesson-fixture" })?.attempts, 3)
    }

    func testCoordinatorDiscardsSuspendedFetchAfterCredentialReplacement() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = ProgressSyncFixture()
        fixture.suspendFetch = true
        let fetchStarted = expectation(description: "fetch suspended at injected network boundary")
        fixture.fetchStarted = fetchStarted
        let finished = expectation(description: "both replaced and replacement operations finished")
        finished.expectedFulfillmentCount = 2
        fixture.finished = finished
        let coordinator = ConversationCoordinator(store: store, progressSyncAdapter: fixture.adapter(store: store))

        coordinator.syncProgress()
        await fulfillment(of: [fetchStarted], timeout: 2)

        fixture.session = .init(accountID: "account-A", accessToken: "credential-rotated")
        fixture.suspendFetch = false
        NotificationCenter.default.post(name: .managedAccountSessionChanged, object: nil)
        fixture.pendingFetch?.resume(returning: [RemoteProgress(kind: ProgressEngine.lesson, itemID: "stale",
                                                                  firstAt: 1, lastAt: 2, attempts: 1,
                                                                  bestScore: 1, language: "en")])
        await fulfillment(of: [finished], timeout: 2)

        XCTAssertEqual(store.archive.lessons?.first(where: { $0.lessonID == "lesson-fixture" })?.attempts, 3)
        XCTAssertNil(store.archive.lessons?.first(where: { $0.lessonID == "stale" }))
        XCTAssertEqual(fixture.sent, 1)
    }

    func testCoordinatorAwaitsSuspendedSendBoundaryBeforeFinishingOperation() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = ProgressSyncFixture()
        fixture.suspendSend = true
        let sendStarted = expectation(description: "network send suspended at acknowledgement boundary")
        fixture.sendStarted = sendStarted
        let finished = expectation(description: "sync finishes only after send returns")
        fixture.finished = finished
        let coordinator = ConversationCoordinator(store: store, progressSyncAdapter: fixture.adapter(store: store))

        coordinator.syncProgress()
        await fulfillment(of: [sendStarted], timeout: 2)
        XCTAssertEqual(store.archive.lessons?.first(where: { $0.lessonID == "lesson-fixture" })?.attempts, 3)

        fixture.pendingSend?.resume(returning: true)
        await fulfillment(of: [finished], timeout: 2)

        XCTAssertEqual(fixture.sent, 1)
        XCTAssertEqual(store.archive.lessons?.first(where: { $0.lessonID == "lesson-fixture" })?.attempts, 3)
    }

    func testCoordinatorDoesNotPublishSuspendedAcknowledgementAfterCredentialReplacement() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = ProgressSyncFixture()
        fixture.suspendSend = true
        let sendStarted = expectation(description: "first network acknowledgement is suspended")
        fixture.sendStarted = sendStarted
        let finished = expectation(description: "both credential generations finish")
        finished.expectedFulfillmentCount = 2
        fixture.finished = finished
        let coordinator = ConversationCoordinator(store: store, progressSyncAdapter: fixture.adapter(store: store))

        coordinator.syncProgress()
        await fulfillment(of: [sendStarted], timeout: 2)
        fixture.session = .init(accountID: "account-A", accessToken: "credential-rotated")
        NotificationCenter.default.post(name: .managedAccountSessionChanged, object: nil)
        fixture.pendingSend?.resume(returning: true)
        await fulfillment(of: [finished], timeout: 2)

        XCTAssertEqual(fixture.sent, 2)
        XCTAssertEqual(store.archive.lessons?.filter { $0.lessonID == "lesson-fixture" }.count, 1)
        XCTAssertEqual(store.archive.lessons?.first(where: { $0.lessonID == "lesson-fixture" })?.attempts, 3)
    }
}
