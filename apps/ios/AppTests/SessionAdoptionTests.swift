import Foundation
import XCTest
@testable import App
import LearningCore

@MainActor private final class MemorySessionPersistence: ManagedAccountSessionPersistence {
    private(set) var session: ManagedAccountSession?
    var failSave = false
    private(set) var saveCount = 0
    private(set) var removeCount = 0

    func load() throws -> ManagedAccountSession? { session }

    func save(_ session: ManagedAccountSession) throws {
        saveCount += 1
        if failSave { throw ManagedAccountError.secureStorage }
        self.session = session
    }

    func remove() throws {
        removeCount += 1
        session = nil
    }
}

private actor SessionAdoptionTransport: ManagedAccountRequestTransport {
    private let accountID = "a0000000-0000-4000-8000-000000000001"
    private let bearer = String(repeating: "A", count: 43)
    private var profileStatuses: [Int]
    private var exchangeContinuation: CheckedContinuation<(Data, HTTPURLResponse), Never>?
    private var exchangeStartedContinuation: CheckedContinuation<Void, Never>?
    private var signOutContinuations: [CheckedContinuation<Void, Never>] = []
    private(set) var signOutCount = 0
    private(set) var profileRequestCount = 0
    var holdExchange = false

    init(profileStatuses: [Int]) { self.profileStatuses = profileStatuses }

    func send(_ request: URLRequest, limit: Int) async throws -> (Data, HTTPURLResponse) {
        let path = request.url?.path ?? ""
        switch path {
        case "/v1/auth/challenge":
            return response(status: 200, json: """
            {"challengeID":"c0000000-0000-4000-8000-000000000001","nonce":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","expiresInSeconds":300}
            """)
        case "/v1/auth/exchange":
            if holdExchange {
                exchangeStartedContinuation?.resume()
                exchangeStartedContinuation = nil
                return await withCheckedContinuation { exchangeContinuation = $0 }
            }
            return exchangeResponse()
        case "/v1/account":
            profileRequestCount += 1
            let status = profileStatuses.isEmpty ? 200 : profileStatuses.removeFirst()
            if status == 401 { return response(status: 401, json: "{}") }
            if status != 200 {
                return response(status: status, json: #"{"error":{"code":"service_unavailable"}}"#)
            }
            return response(status: 200, json: """
            {"accountID":"\(accountID)","email":null,"providers":["google"],"createdAt":"2026-10-27T10:00:00Z"}
            """)
        case "/v1/auth/sign-out":
            signOutCount += 1
            signOutContinuations.forEach { $0.resume() }
            signOutContinuations.removeAll()
            return response(status: 200, json: #"{"signedOut":true}"#)
        default:
            return response(status: 404, json: "{}")
        }
    }

    func releaseExchange() {
        exchangeContinuation?.resume(returning: exchangeResponse())
        exchangeContinuation = nil
    }

    func waitForExchangeStart() async {
        if exchangeContinuation != nil { return }
        await withCheckedContinuation { exchangeStartedContinuation = $0 }
    }

    func waitForSignOut() async {
        if signOutCount > 0 { return }
        await withCheckedContinuation { signOutContinuations.append($0) }
    }

    func setHoldExchange(_ hold: Bool) { holdExchange = hold }
    func observedProfileRequestCount() -> Int { profileRequestCount }

    private func exchangeResponse() -> (Data, HTTPURLResponse) {
        response(status: 200, json: """
        {"accountID":"\(accountID)","accessToken":"\(bearer)","expiresInSeconds":3600}
        """)
    }

    private func response(status: Int, json: String) -> (Data, HTTPURLResponse) {
        let url = URL(string: "https://account-fixture.invalid")!
        return (Data(json.utf8), HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1",
                                                 headerFields: ["Content-Type": "application/json"])!)
    }
}

@MainActor private final class CapturingHostedProxy: HostedProxyResponding {
    private(set) var bearer: String?
    private(set) var calls = 0

    func attach(bearer: String) { self.bearer = bearer }

    func respond(instructions: String, input: String, schema: String?, search: Bool) async throws -> HostedProxyEngine.Result {
        calls += 1
        return HostedProxyEngine.Result(text: "fixture helper result", inputTokens: 1, outputTokens: 1)
    }
}

@MainActor private final class SessionNotificationRecorder {
    struct Snapshot {
        let inMemory: ManagedAccountSession?
        let persisted: ManagedAccountSession?
    }

    let store: ManagedAccountStore
    let persistence: MemorySessionPersistence
    let configuration: ManagedAccountConfiguration
    let helperRouter: HelperRouter
    let proxy: CapturingHostedProxy
    private(set) var snapshots: [Snapshot] = []
    private var observer: NSObjectProtocol?

    init(store: ManagedAccountStore, persistence: MemorySessionPersistence,
         configuration: ManagedAccountConfiguration, helperRouter: HelperRouter,
         proxy: CapturingHostedProxy) {
        self.store = store
        self.persistence = persistence
        self.configuration = configuration
        self.helperRouter = helperRouter
        self.proxy = proxy
    }

    func record() {
        let persisted = try? persistence.load()
        snapshots.append(Snapshot(inMemory: store.session, persisted: persisted))
        if let persisted {
            helperRouter.useHostedProxy(origin: configuration.origin, bearer: persisted.accessToken)
        } else {
            helperRouter.clearHostedProxy()
        }
    }

    func startObserving() {
        observer = NotificationCenter.default.addObserver(
            forName: .managedAccountSessionChanged, object: nil, queue: nil
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.record() }
        }
    }

    func stopObserving() {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
    }
}

@MainActor final class SessionAdoptionTests: XCTestCase {
    func testProfile500StillPublishesAdoptedBearer() async throws {
        let fixture = try makeFixture(profileStatuses: [500])
        let recorder = fixture.observeSessionChanges()
        defer { recorder.stopObserving() }

        fixture.store.signIn(.google)
        await fixture.store.waitForCurrentOperation()

        XCTAssertEqual(recorder.snapshots.count, 1)
        XCTAssertEqual(recorder.snapshots[0].inMemory, fixture.persistence.session)
        XCTAssertEqual(recorder.snapshots[0].persisted, fixture.persistence.session)
        XCTAssertEqual(fixture.store.session, fixture.persistence.session)
        XCTAssertEqual(fixture.store.session?.accessToken, String(repeating: "A", count: 43))
        XCTAssertEqual(fixture.store.message, "Palique couldn’t complete that request. Check your connection and try again.")

        let profileRequests = await fixture.transport.observedProfileRequestCount()
        XCTAssertEqual(profileRequests, 1)
        let result = try await fixture.helperRouter.respond(purpose: .meaning, instructions: "Explain", input: "hello")
        XCTAssertEqual(result.text, "fixture helper result")
        XCTAssertEqual(fixture.proxy.bearer, fixture.store.session?.accessToken)
        XCTAssertEqual(fixture.proxy.calls, 1)
    }

    func testProfileRefreshDoesNotRepublishAdoption() async throws {
        let fixture = try makeFixture(profileStatuses: [500, 200])
        let recorder = fixture.observeSessionChanges()
        defer { recorder.stopObserving() }

        fixture.store.signIn(.google)
        await fixture.store.waitForCurrentOperation()
        XCTAssertEqual(recorder.snapshots.count, 1)

        fixture.store.refresh()
        await fixture.store.waitForCurrentOperation()

        XCTAssertEqual(fixture.store.profile?.accountID, fixture.store.session?.accountID)
        XCTAssertEqual(recorder.snapshots.count, 1)
        XCTAssertEqual(fixture.persistence.session, fixture.store.session)
    }

    func testActualCredentialRejectionClearsSessionAndNotifiesInvalidation() async throws {
        let fixture = try makeFixture(profileStatuses: [500, 401])
        let recorder = fixture.observeSessionChanges()
        defer { recorder.stopObserving() }

        fixture.store.signIn(.google)
        await fixture.store.waitForCurrentOperation()
        XCTAssertEqual(recorder.snapshots.count, 1)
        XCTAssertNotNil(fixture.persistence.session)

        fixture.store.refresh()
        await fixture.store.waitForCurrentOperation()

        XCTAssertEqual(recorder.snapshots.count, 2)
        XCTAssertNotNil(recorder.snapshots[0].inMemory)
        XCTAssertNotNil(recorder.snapshots[0].persisted)
        XCTAssertNil(recorder.snapshots[1].inMemory)
        XCTAssertNil(recorder.snapshots[1].persisted)
        XCTAssertNil(fixture.store.session)
        XCTAssertNil(fixture.persistence.session)
        XCTAssertEqual(fixture.persistence.removeCount, 1)
        XCTAssertTrue(fixture.helperRouter.supportsSourceBackedSearch)
    }

    func testPersistenceFailureDoesNotAdoptOrNotify() async throws {
        let fixture = try makeFixture(profileStatuses: [])
        fixture.persistence.failSave = true
        let recorder = fixture.observeSessionChanges()
        defer { recorder.stopObserving() }

        fixture.store.signIn(.google)
        await fixture.store.waitForCurrentOperation()

        XCTAssertNil(fixture.store.session)
        XCTAssertNil(fixture.persistence.session)
        XCTAssertEqual(fixture.persistence.saveCount, 1)
        XCTAssertTrue(recorder.snapshots.isEmpty)
        await fixture.transport.waitForSignOut()
    }

    func testStaleExchangeIsRejectedBeforeAdoption() async throws {
        let fixture = try makeFixture(profileStatuses: [])
        await fixture.transport.setHoldExchange(true)
        let recorder = fixture.observeSessionChanges()
        defer { recorder.stopObserving() }

        fixture.store.signIn(.google)
        await fixture.transport.waitForExchangeStart()
        fixture.store.cancelSignIn()
        await fixture.transport.releaseExchange()
        await fixture.transport.waitForSignOut()

        XCTAssertNil(fixture.store.session)
        XCTAssertNil(fixture.persistence.session)
        XCTAssertEqual(fixture.persistence.saveCount, 0)
        XCTAssertTrue(recorder.snapshots.isEmpty)
    }

    private struct Fixture {
        let store: ManagedAccountStore
        let transport: SessionAdoptionTransport
        let persistence: MemorySessionPersistence
        let configuration: ManagedAccountConfiguration
        let helperRouter: HelperRouter
        let proxy: CapturingHostedProxy

        func observeSessionChanges() -> SessionNotificationRecorder {
            let recorder = SessionNotificationRecorder(store: store, persistence: persistence,
                configuration: configuration, helperRouter: helperRouter, proxy: proxy)
            recorder.startObserving()
            return recorder
        }
    }

    private func makeFixture(profileStatuses: [Int]) throws -> Fixture {
        let scheme = "com.googleusercontent.apps.fixture"
        let configuration = try ManagedAccountConfiguration(
            apiURL: "https://account-fixture.invalid",
            googleClientID: "fixture.apps.googleusercontent.com",
            bundleID: "com.example.app",
            registeredURLSchemes: [scheme],
            appleCapabilityEnabled: false
        )
        let transport = SessionAdoptionTransport(profileStatuses: profileStatuses)
        let persistence = MemorySessionPersistence()
        let provider: ManagedAccountStore.CredentialProvider = { _, _, _, _ in
            ManagedAccountIdentity.Credential(idToken: "provider-id-token", authorizationCode: nil)
        }
        let store = ManagedAccountStore(configuration: configuration, requestTransport: transport,
                                        sessionPersistence: persistence, credentialProvider: provider)
        let proxy = CapturingHostedProxy()
        let helperRouter = HelperRouter(api: APIClient(), hostedProxyFactory: { _, bearer in
            proxy.attach(bearer: bearer)
            return proxy
        })
        return Fixture(store: store, transport: transport, persistence: persistence,
                       configuration: configuration, helperRouter: helperRouter, proxy: proxy)
    }
}
