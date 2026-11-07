import XCTest
@testable import App
import LearningCore

/// Pause/resume/restart on the conversation coordinator. macOS-only (App
/// target); on Linux these are reviewed but not executed.
@MainActor final class TalkPauseResumeTests: XCTestCase {
    private func makeCoordinator() throws -> ConversationCoordinator {
        let store = try LearningStore(inMemory: true)
        return ConversationCoordinator(store: store)
    }

    func testPauseKeepsTheSessionWithoutEndingIt() throws {
        let coordinator = try makeCoordinator()
        coordinator.prepareTypedReplyTesting()

        coordinator.pauseConversation()
        coordinator.settlePauseForTesting()

        XCTAssertEqual(coordinator.state, .paused)
        XCTAssertNotNil(coordinator.session)
        XCTAssertNil(coordinator.session?.endedAt, "a pause is not an end: no endedAt")
        XCTAssertFalse(coordinator.isRunning)
    }

    func testResumeReconnectsTheSameConversation() throws {
        let coordinator = try makeCoordinator()
        coordinator.prepareTypedReplyTesting()
        let originalID = coordinator.session?.id

        coordinator.pauseConversation()
        coordinator.settlePauseForTesting()
        coordinator.resumePausedConversation()

        XCTAssertEqual(coordinator.state, .connecting)
        XCTAssertEqual(coordinator.session?.id, originalID, "resume reattaches the kept conversation")
    }

    func testResumeWithoutAPausedConversationDoesNothing() throws {
        let coordinator = try makeCoordinator()
        coordinator.prepareTypedReplyTesting()
        let originalID = coordinator.session?.id

        coordinator.resumePausedConversation()

        XCTAssertEqual(coordinator.state, .active)
        XCTAssertEqual(coordinator.session?.id, originalID)
    }

    func testRestartClosesThePausedConversationAndStartsOver() throws {
        let coordinator = try makeCoordinator()
        coordinator.prepareTypedReplyTesting()
        let originalID = coordinator.session?.id

        coordinator.pauseConversation()
        coordinator.settlePauseForTesting()
        coordinator.restartConversation()

        XCTAssertNotEqual(coordinator.session?.id, originalID, "restart begins a new conversation")
        XCTAssertNotEqual(coordinator.state, .paused)
    }

    func testTypedReplyInPauseAppendsToTheSameConversation() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = TypedReplyFixture()
        let coordinator = ConversationCoordinator(store: store, typedReplyAdapter: fixture.adapter())
        coordinator.prepareTypedReplyTesting()
        let conversationID = coordinator.session?.id
        coordinator.pauseConversation()
        coordinator.settlePauseForTesting()

        let result = await coordinator.sendTyped("hola")

        XCTAssertEqual(result, .sent)
        XCTAssertEqual(coordinator.state, .paused, "a typed reply does not resume the voice session by itself")
        XCTAssertEqual(coordinator.session?.id, conversationID, "the typed turn appends to the paused conversation")
        let passages = coordinator.session?.passages ?? []
        XCTAssertEqual(passages.last { $0.speaker == .user }?.text, "hola")
        XCTAssertEqual(passages.last { $0.speaker == .assistant }?.text, "Thanks for your reply.")
    }
}

@MainActor private final class TypedReplyFixture {
    func adapter() -> ConversationTypedReplyAdapter {
        ConversationTypedReplyAdapter { _, _ in
            APIResult(text: "Thanks for your reply.", sources: [], usage: APIUsage())
        }
    }
}
