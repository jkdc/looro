import XCTest
@testable import App
import LearningCore

@MainActor private final class ProgressSyncFixture {
    var session: ProgressSyncSession? = .init(accountID: "account-A", accessToken: "credential-A")
    var pendingFetch: CheckedContinuation<[RemoteProgress]?, Never>?
    var suspendFetch = false
    var failedFetches = 0
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
                if self.failedFetches > 0 { self.failedFetches -= 1; return nil }
                if self.suspendFetch && self.fetchCount == 1 {
                    return await withCheckedContinuation { continuation in
                        self.pendingFetch = continuation
                        self.fetchStarted?.fulfill()
                    }
                }
                return [RemoteProgress(kind: ProgressEngine.lesson, itemID: "lesson-fixture", firstAt: 1,
                                       lastAt: 2, attempts: 3, bestScore: 80, language: "en")]
            },
            generationSnapshot: { store.dataGeneration },
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
    func testProgressSyncStatusIsOwnerScopedAndDropsStaleCompletion() {
        let pending = ProgressSyncStatus(ownerAccountID: "account-A", phase: .pending)

        XCTAssertEqual(ProgressSyncStatus.forOwner(pending, ownerAccountID: "account-A"), pending)
        XCTAssertNil(ProgressSyncStatus.forOwner(pending, ownerAccountID: "account-B"))
        XCTAssertNil(ProgressSyncStatus.finished(capturedOwner: "account-A", currentOwner: "account-B", succeeded: false))
        XCTAssertEqual(ProgressSyncStatus.finished(capturedOwner: "account-A", currentOwner: "account-A", succeeded: false),
                       ProgressSyncStatus(ownerAccountID: "account-A", phase: .failed))
        XCTAssertEqual(ProgressSyncStatus.finished(capturedOwner: "account-A", currentOwner: "account-A", succeeded: true),
                       ProgressSyncStatus(ownerAccountID: "account-A", phase: .synced))
    }

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

    func testLocalProgressDuringSuspendedBootstrapDoesNotDiscardRemotePage() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = ProgressSyncFixture()
        fixture.suspendFetch = true
        let fetchStarted = expectation(description: "bootstrap request is suspended")
        fixture.fetchStarted = fetchStarted
        let sent = expectation(description: "continued bootstrap reaches upload")
        let finished = expectation(description: "continued bootstrap finishes")
        fixture.finished = finished
        let coordinator = ConversationCoordinator(store: store,
            progressSyncAdapter: fixture.adapter(store: store, sendExpectation: sent))

        coordinator.syncProgress()
        await fulfillment(of: [fetchStarted], timeout: 2)
        store.recordLessonAttempt("local-during-bootstrap")
        fixture.pendingFetch?.resume(returning: [RemoteProgress(kind: ProgressEngine.lesson,
            itemID: "remote-during-bootstrap", firstAt: 1, lastAt: 2, attempts: 1,
            bestScore: 20, language: "en")])

        await fulfillment(of: [sent, finished], timeout: 2)
        XCTAssertNotNil(store.archive.lessons?.first { $0.lessonID == "local-during-bootstrap" })
        XCTAssertNotNil(store.archive.lessons?.first { $0.lessonID == "remote-during-bootstrap" },
                        "ordinary local progress must not invalidate the owner's bootstrap")
    }

    func testImportGenerationDiscardsSuspendedFetchEvenWhenArchiveBytesAreUnchanged() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = ProgressSyncFixture()
        fixture.suspendFetch = true
        let fetchStarted = expectation(description: "bootstrap request is suspended")
        fixture.fetchStarted = fetchStarted
        let finished = expectation(description: "stale bootstrap is discarded")
        fixture.finished = finished
        let coordinator = ConversationCoordinator(store: store, progressSyncAdapter: fixture.adapter(store: store))

        coordinator.syncProgress()
        await fulfillment(of: [fetchStarted], timeout: 2)
        try store.importData(store.exportData())
        fixture.pendingFetch?.resume(returning: [RemoteProgress(kind: ProgressEngine.lesson,
            itemID: "stale-after-import", firstAt: 1, lastAt: 2, attempts: 1,
            bestScore: 20, language: "en")])

        await fulfillment(of: [finished], timeout: 2)
        XCTAssertNil(store.archive.lessons?.first { $0.lessonID == "stale-after-import" },
                     "data-generation changes must fence late results even when archive bytes match")
        XCTAssertEqual(0, fixture.sent)
    }

    func testExplicitResetRevokesPartialBootstrapCursor() throws {
        let store = try LearningStore(inMemory: true)
        let owner = "00000000-0000-0000-0000-0000000000A1"
        store.activateLearnerIdentity(owner)
        try store.applyRemoteProgressPage([], ownerAccountID: owner, nextCursor: "after-page-1",
                                          passGeneration: 0, updatedAtMilliseconds: 100)

        store.deleteAll()

        let state = try XCTUnwrap(store.remoteProgressSyncState(direction: "download", ownerAccountID: owner))
        XCTAssertNil(state.cursor, "An explicit reset must not leave a resumable remote cursor")
        XCTAssertEqual(1, state.passGeneration)
        XCTAssertFalse(ProgressClient.shouldBootstrap(localProgressExists: false, state: state))
    }

    func testArchiveImportRevokesPartialBootstrapCursor() throws {
        let store = try LearningStore(inMemory: true)
        let owner = "00000000-0000-0000-0000-0000000000A2"
        store.activateLearnerIdentity(owner)
        try store.applyRemoteProgressPage([], ownerAccountID: owner, nextCursor: "after-page-1",
                                          passGeneration: 0, updatedAtMilliseconds: 100)

        try store.importData(store.exportData())

        let state = try XCTUnwrap(store.remoteProgressSyncState(direction: "download", ownerAccountID: owner))
        XCTAssertNil(state.cursor, "An explicit import must not leave a resumable remote cursor")
        XCTAssertEqual(1, state.passGeneration)
        XCTAssertFalse(ProgressClient.shouldBootstrap(localProgressExists: false, state: state))
    }

    func testCoordinatorExposesFailureAndExplicitRetryCanRecover() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = ProgressSyncFixture()
        fixture.failedFetches = 1
        let failed = expectation(description: "failed sync becomes observable")
        fixture.finished = failed
        let coordinator = ConversationCoordinator(store: store, progressSyncAdapter: fixture.adapter(store: store))

        coordinator.syncProgress()
        XCTAssertEqual(coordinator.progressSyncStatus,
                       ProgressSyncStatus(ownerAccountID: "account-A", phase: .pending))
        await fulfillment(of: [failed], timeout: 2)
        XCTAssertEqual(coordinator.progressSyncStatus,
                       ProgressSyncStatus(ownerAccountID: "account-A", phase: .failed))

        let recovered = expectation(description: "retry completes successfully")
        fixture.finished = recovered
        coordinator.retryProgressSync()
        await fulfillment(of: [recovered], timeout: 2)
        XCTAssertEqual(coordinator.progressSyncStatus,
                       ProgressSyncStatus(ownerAccountID: "account-A", phase: .synced))
        XCTAssertEqual(fixture.sent, 1)
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
        XCTAssertEqual(coordinator.progressSyncStatus,
                       ProgressSyncStatus(ownerAccountID: "account-A", phase: .pending))

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
        XCTAssertNil(coordinator.progressSyncStatus)
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
