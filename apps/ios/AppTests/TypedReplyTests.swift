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

    func testSuccessfulSendPreservesDraftEditedWhileProviderIsPending() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = TypedReplyFixture()
        fixture.suspendFirstRequest = true
        fixture.providerStarted = expectation(description: "typed reply reached provider boundary")
        let coordinator = ConversationCoordinator(store: store, typedReplyAdapter: fixture.adapter())
        coordinator.prepareTypedReplyTesting()
        let editor = TypedReplyEditorState(draft: "first draft")
        let attempt = editor.beginAttempt()!
        let submittedText = editor.draft

        let send = Task { await coordinator.sendTyped(submittedText) }
        await fulfillment(of: [fixture.providerStarted!], timeout: 2)
        editor.draft = "second draft"
        coordinator.updateTypedReplyDraft(editor.draft)
        fixture.pendingResponse?.resume(returning: APIResult(text: "Thanks.", sources: [], usage: APIUsage()))

        let result = await send.value
        editor.finishAttempt(result, id: attempt)

        XCTAssertEqual(result, .sent)
        XCTAssertEqual(editor.draft, "second draft")
        XCTAssertFalse(editor.isDismissed)
        XCTAssertEqual(coordinator.typedReplyDraft, "second draft")
        XCTAssertEqual(coordinator.session?.fragments.filter { $0.typed }.map(\.text), ["first draft"])
    }

    func testFailedSendPreservesDraftEditedWhileProviderIsPending() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = TypedReplyFixture()
        fixture.suspendFirstRequest = true
        fixture.providerStarted = expectation(description: "typed reply reached provider boundary")
        let coordinator = ConversationCoordinator(store: store, typedReplyAdapter: fixture.adapter())
        coordinator.prepareTypedReplyTesting()
        let editor = TypedReplyEditorState(draft: "first draft")
        let attempt = editor.beginAttempt()!

        let send = Task { await coordinator.sendTyped(editor.draft) }
        await fulfillment(of: [fixture.providerStarted!], timeout: 2)
        editor.draft = "second draft"
        coordinator.updateTypedReplyDraft(editor.draft)
        fixture.pendingResponse?.resume(throwing: TypedReplyFixture.Failure.provider)

        let result = await send.value
        editor.finishAttempt(result, id: attempt)

        XCTAssertEqual(result, .failed("The reply could not be sent."))
        XCTAssertEqual(editor.draft, "second draft")
        XCTAssertEqual(editor.error, "The reply could not be sent.")
        XCTAssertEqual(coordinator.typedReplyDraft, "second draft")
        XCTAssertFalse(editor.isDismissed)
    }

    func testInFlightReplyStateRemainsVisibleUntilProviderReturns() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = TypedReplyFixture()
        fixture.suspendFirstRequest = true
        fixture.providerStarted = expectation(description: "typed reply reached provider boundary")
        let coordinator = ConversationCoordinator(store: store, typedReplyAdapter: fixture.adapter())
        coordinator.prepareTypedReplyTesting()

        let send = Task { await coordinator.sendTyped("first draft") }
        await fulfillment(of: [fixture.providerStarted!], timeout: 2)
        XCTAssertTrue(coordinator.typedReplyInFlight)
        coordinator.updateTypedReplyDraft("second draft")
        XCTAssertTrue(coordinator.typedReplyInFlight)
        XCTAssertEqual(coordinator.typedReplyDraft, "second draft")

        fixture.pendingResponse?.resume(returning: APIResult(text: "Late reply.", sources: [], usage: APIUsage()))
        let firstResult = await send.value
        XCTAssertEqual(firstResult, .sent)
        XCTAssertFalse(coordinator.typedReplyInFlight)
        let recovery = await coordinator.sendTyped("second draft")
        XCTAssertEqual(recovery, .sent)
        XCTAssertEqual(fixture.requests, 2)
    }

    func testInFlightSubmitShowsRecoverableMessage() {
        let editor = TypedReplyEditorState(draft: "second draft")
        let attempt = editor.beginAttempt()!

        editor.finishAttempt(.inFlight, id: attempt)

        XCTAssertEqual(editor.error, "A reply is still being sent. Please wait.")
        XCTAssertFalse(editor.isDismissed)
        XCTAssertFalse(editor.isSending)
    }

    func testInvalidatingReplyContinuationClearsDraftAndError() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = TypedReplyFixture()
        fixture.failuresRemaining = 1
        let coordinator = ConversationCoordinator(store: store, typedReplyAdapter: fixture.adapter())
        coordinator.prepareTypedReplyTesting()
        coordinator.updateTypedReplyDraft("first draft")

        let result = await coordinator.sendTyped("first draft")
        XCTAssertEqual(result, .failed("The reply could not be sent."))
        XCTAssertEqual(coordinator.typedReplyError, "The reply could not be sent.")
        coordinator.updateTypedReplyDraft("private draft for previous owner")

        coordinator.cancelTypedReply()

        XCTAssertEqual(coordinator.typedReplyDraft, "")
        XCTAssertNil(coordinator.typedReplyError)
    }

    func testLateReplyCannotCloseNewSheet() {
        let oldEditor = TypedReplyEditorState(draft: "old")
        oldEditor.presentationDidAppear(draft: "old")
        let oldAttempt = oldEditor.beginAttempt()!
        oldEditor.dismiss()
        oldEditor.presentationDidDisappear()
        let newEditor = TypedReplyEditorState(draft: "new")
        newEditor.presentationDidAppear(draft: "new")

        oldEditor.finishAttempt(.sent, id: oldAttempt)

        XCTAssertFalse(oldEditor.isPresented)
        XCTAssertTrue(newEditor.isPresented)
        XCTAssertFalse(newEditor.isDismissed)
        XCTAssertEqual(newEditor.draft, "new")
    }
}
