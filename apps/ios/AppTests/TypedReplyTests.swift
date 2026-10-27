import XCTest
@testable import App
import LearningCore

@MainActor private final class TypedReplyFixture {
    enum Failure: LocalizedError { case provider
        var errorDescription: String? { "The reply could not be sent." }
    }

    var requests = 0
    var failuresRemaining = 0
    var suspendFirstRequest = false
    var providerStarted: XCTestExpectation?
    var pendingResponse: CheckedContinuation<APIResult, Error>?

    func adapter() -> ConversationTypedReplyAdapter {
        ConversationTypedReplyAdapter { _, _ in
            self.requests += 1
            if self.suspendFirstRequest && self.requests == 1 {
                self.providerStarted?.fulfill()
                return try await withCheckedThrowingContinuation { continuation in
                    self.pendingResponse = continuation
                }
            }
            if self.failuresRemaining > 0 {
                self.failuresRemaining -= 1
                throw Failure.provider
            }
            return APIResult(text: "Thanks for your reply.", sources: [], usage: APIUsage())
        }
    }
}

@MainActor final class TypedReplyTests: XCTestCase {
    func testFailedSendKeepsDraftFocusAndSheet() {
        let editor = TypedReplyEditorState(draft: "hello")
        let attempt = editor.beginAttempt()!

        editor.finishAttempt(.failed("The reply could not be sent."), id: attempt)

        XCTAssertEqual(editor.draft, "hello")
        XCTAssertEqual(editor.error, "The reply could not be sent.")
        XCTAssertFalse(editor.isDismissed)
    }

    func testTwoManualRetriesKeepOneLocalMessage() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = TypedReplyFixture()
        fixture.failuresRemaining = 2
        let coordinator = ConversationCoordinator(store: store, typedReplyAdapter: fixture.adapter())
        coordinator.prepareTypedReplyTesting()

        let first = await coordinator.sendTyped("hi")
        let second = await coordinator.sendTyped("hi")
        let third = await coordinator.sendTyped("hi")

        XCTAssertEqual(first, .failed("The reply could not be sent."))
        XCTAssertEqual(second, .failed("The reply could not be sent."))
        XCTAssertEqual(third, .sent)
        XCTAssertEqual(fixture.requests, 3)
        XCTAssertEqual(coordinator.session?.fragments.filter { $0.typed && $0.text == "hi" }.count, 1)
    }

    func testConcurrentDuplicateSendUsesOneProviderGeneration() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = TypedReplyFixture()
        fixture.suspendFirstRequest = true
        fixture.providerStarted = expectation(description: "typed reply reached provider boundary")
        let coordinator = ConversationCoordinator(store: store, typedReplyAdapter: fixture.adapter())
        coordinator.prepareTypedReplyTesting()

        let firstSend = Task { await coordinator.sendTyped("hi") }
        await fulfillment(of: [fixture.providerStarted!], timeout: 2)
        let duplicate = await coordinator.sendTyped("hi")
        fixture.pendingResponse?.resume(returning: APIResult(text: "Thanks.", sources: [], usage: APIUsage()))
        let first = await firstSend.value

        XCTAssertEqual(duplicate, .inFlight)
        XCTAssertEqual(first, .sent)
        XCTAssertEqual(fixture.requests, 1)
        XCTAssertEqual(coordinator.session?.fragments.filter { $0.typed && $0.text == "hi" }.count, 1)
    }

    func testDismissalDiscardsSuspendedProviderReply() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = TypedReplyFixture()
        fixture.suspendFirstRequest = true
        fixture.providerStarted = expectation(description: "typed reply reached provider boundary")
        let coordinator = ConversationCoordinator(store: store, typedReplyAdapter: fixture.adapter())
        coordinator.prepareTypedReplyTesting()

        let send = Task { await coordinator.sendTyped("hi") }
        await fulfillment(of: [fixture.providerStarted!], timeout: 2)
        XCTAssertTrue(coordinator.working)
        coordinator.cancelTypedReply()
        XCTAssertFalse(coordinator.working)
        fixture.pendingResponse?.resume(returning: APIResult(text: "Late reply.", sources: [], usage: APIUsage()))

        let result = await send.value
        XCTAssertEqual(result, .cancelled)
        XCTAssertEqual(fixture.requests, 1)
        XCTAssertNil(coordinator.error)
        XCTAssertFalse(coordinator.working)
    }

    func testDoubleSubmitStartsOneGeneration() {
        let editor = TypedReplyEditorState(draft: "hello")

        XCTAssertNotNil(editor.beginAttempt())
        XCTAssertNil(editor.beginAttempt())
    }

    func testLateReplyCannotCloseNewSheet() {
        let oldEditor = TypedReplyEditorState(draft: "old")
        let oldAttempt = oldEditor.beginAttempt()!
        oldEditor.dismiss()
        let newEditor = TypedReplyEditorState(draft: "new")

        oldEditor.finishAttempt(.sent, id: oldAttempt)

        XCTAssertFalse(newEditor.isDismissed)
        XCTAssertEqual(newEditor.draft, "new")
    }
}
