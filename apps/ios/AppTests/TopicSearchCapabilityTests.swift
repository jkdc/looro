import XCTest
@testable import App
import LearningCore

@MainActor private final class TopicSearchFixture {
    enum Failure: LocalizedError { case network
        var errorDescription: String? { "The network request failed." }
    }

    let supportsSourceBackedSearch: Bool
    var requests = 0
    var failuresRemaining = 0

    init(supportsSourceBackedSearch: Bool, failuresRemaining: Int = 0) {
        self.supportsSourceBackedSearch = supportsSourceBackedSearch
        self.failuresRemaining = failuresRemaining
    }

    func adapter() -> ConversationTopicSearchAdapter {
        ConversationTopicSearchAdapter(supportsSourceBackedSearch: supportsSourceBackedSearch) { _ in
            self.requests += 1
            if self.failuresRemaining > 0 {
                self.failuresRemaining -= 1
                throw Failure.network
            }
            return APIResult(
                text: "A sourced topic.",
                sources: [SourceLink(title: "Example source", url: "https://example.com/topic")],
                usage: APIUsage(searches: 1)
            )
        }
    }
}

@MainActor final class TopicSearchCapabilityTests: XCTestCase {
    func testTokenProxyCannotProvideSources() {
        let router = HelperRouter(api: APIClient())
        router.useHostedProxy(origin: URL(string: "https://example.com")!, bearer: "fixture")

        XCTAssertFalse(router.supportsSourceBackedSearch)

        router.clearHostedProxy()
        XCTAssertTrue(router.supportsSourceBackedSearch)
    }

    func testUnsupportedEngineMakesZeroSearchCalls() async throws {
        let store = try LearningStore(inMemory: true)
        let fixture = TopicSearchFixture(supportsSourceBackedSearch: false)
        let coordinator = ConversationCoordinator(store: store, topicSearchAdapter: fixture.adapter())

        do {
            _ = try await coordinator.currentTopic("Aurora borealis")
            XCTFail("Unsupported engines must not start a source-backed request.")
        } catch let error as ConversationCoordinator.TopicError {
            XCTAssertEqual(error.localizedDescription, ConversationCoordinator.topicSearchLimitation)
        }

        XCTAssertEqual(fixture.requests, 0)
        XCTAssertEqual(coordinator.topicSearchLimitation, "Source-backed topic search isn’t available with the selected conversation engine. You can still use saved topics.")
    }

    func testUnsupportedEngineKeepsCachedTopics() async throws {
        let store = try LearningStore(inMemory: true)
        let cached = TopicBrief(languageID: store.language.id, query: "Aurora borealis", text: "Saved explanation.", sources: [])
        var session = SessionRecord(languageID: store.language.id, title: "Saved topics")
        session.endedAt = .now
        session.topics = [cached]
        store.save(session)
        let fixture = TopicSearchFixture(supportsSourceBackedSearch: false)
        let coordinator = ConversationCoordinator(store: store, topicSearchAdapter: fixture.adapter())

        let result = try await coordinator.currentTopic("aurora borealis")

        XCTAssertEqual(result.id, cached.id)
        XCTAssertEqual(result.text, "Saved explanation.")
        XCTAssertEqual(fixture.requests, 0)
    }

    func testCapableEngineKeepsRealRetryAndSourcedResults() async throws {
        let store = try LearningStore(inMemory: true)
        store.updatePreferences { $0.aiConsentVersion = AIProcessingConsent.version }
        let fixture = TopicSearchFixture(supportsSourceBackedSearch: true, failuresRemaining: 1)
        let coordinator = ConversationCoordinator(store: store, topicSearchAdapter: fixture.adapter())

        do {
            _ = try await coordinator.currentTopic("Aurora borealis")
            XCTFail("The first genuine network failure should remain visible.")
        } catch let error as TopicSearchFixture.Failure {
            XCTAssertEqual(error.localizedDescription, "The network request failed.")
        }

        let result = try await coordinator.currentTopic("Aurora borealis")

        XCTAssertEqual(fixture.requests, 2)
        XCTAssertEqual(result.text, "A sourced topic.")
        XCTAssertEqual(result.sources.map(\.url), ["https://example.com/topic"])
    }
}
