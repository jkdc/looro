import Foundation
import Observation
import LearningCore

private enum ManagedAccountUIFixtureUnavailable {
    private static let argumentPrefixes = ["--exam-recovery-ui-test=", "--exam-recovery-account="]

    static func requested(in arguments: [String]) -> Bool {
        arguments.contains { argument in argumentPrefixes.contains { argument.hasPrefix($0) } }
    }
}

/// Posted (main actor) whenever the local account session appears, changes or
/// disappears: sign-in, sign-out, deletion, forced expiry. Listeners re-read
/// the Keychain — it stays the only source of truth. The token-billing proxy
/// uses this to re-attach its bearer on a fresh session and clear on sign-out
/// or account switch, per the HelperRouter.useHostedProxy contract.
extension Notification.Name {
    static let managedAccountSessionChanged = Notification.Name("com.jkdc.app.managedAccountSessionChanged")
}

@MainActor @Observable
final class ManagedAccountStore {
    let configuration: ManagedAccountConfiguration?
    private(set) var session: ManagedAccountSession?
    private(set) var profile: ManagedAccountProfile?
    private(set) var isBusy = false
    var message: String?
    @ObservationIgnored private let client: ManagedAccountClient?
    @ObservationIgnored private let keychain: ManagedAccountKeychain?
    @ObservationIgnored private let identity = ManagedAccountIdentity()
    @ObservationIgnored private var operation: Task<Void, Never>?
    @ObservationIgnored private var gate = ManagedAccountOperationGate()
    private let isPreview: Bool
    private var allowsFixtureNetworkInPreview = false
    #if DEBUG && targetEnvironment(simulator)
    @ObservationIgnored private var uiTestFixture: ManagedAccountUIFixture?
    var isExamRecoveryUITestFixture: Bool { uiTestFixture != nil }
    #endif

    init(configuration: ManagedAccountConfiguration? = .load()) {
        let arguments = ProcessInfo.processInfo.arguments
        #if DEBUG && targetEnvironment(simulator)
        isPreview = arguments.contains("--preview")
        if ManagedAccountUIFixture.requested(in: arguments) {
            do {
                guard let fixture = try ManagedAccountUIFixture.make(arguments: arguments) else {
                    throw ManagedAccountError.invalidResponse
                }
                self.configuration = fixture.configuration
                client = ManagedAccountClient(configuration: fixture.configuration, transport: fixture.transport)
                keychain = nil
                session = fixture.session
                uiTestFixture = fixture
                allowsFixtureNetworkInPreview = true
            } catch {
                self.configuration = nil
                client = nil
                keychain = nil
                message = "The exam recovery test fixture is invalid."
            }
            return
        }
        #else
        isPreview = false
        if ManagedAccountUIFixtureUnavailable.requested(in: arguments) {
            self.configuration = nil
            client = nil
            keychain = nil
            message = "The exam recovery test fixture is unavailable."
            return
        }
        #endif
        self.configuration = configuration
        client = configuration.map(ManagedAccountClient.init)
        keychain = configuration.map { ManagedAccountKeychain(scope: $0.storageScope) }
        // Screenshot and preview UI tests never read a real account or create a server session.
        if !isPreview {
            do { session = try keychain?.load() }
            catch { message = Self.message(for: error) }
        }
    }
    func signIn(_ provider: ManagedIdentityProvider) {
        guard !isPreview else { message = "Sign-in isn’t available in this preview."; return }
        guard !isBusy, session == nil, let client, let configuration, let keychain,
              configuration.providers.contains(provider) else { return }
        run { [self] token in
            let challenge = try await client.challenge()
            let expiresAt = Date.now.addingTimeInterval(TimeInterval(challenge.expiresInSeconds))
            let credential = try await (provider == .google
                ? identity.google(configuration: configuration, nonce: challenge.nonce, http: client.http)
                : identity.apple(nonce: challenge.nonce))
            guard gate.accepts(token), Date.now < expiresAt else { throw ManagedAccountError.cancelled }
            let exchanged = try await client.exchange(provider: provider, token: credential.idToken, challenge: challenge)
            let newSession = try ManagedAccountSession(exchange: exchanged, provider: provider, scope: configuration.storageScope)
            guard gate.accepts(token), !Task.isCancelled else {
                try? await client.signOut(session: newSession); return
            }
            do { try keychain.save(newSession) }
            catch { try? await client.signOut(session: newSession); throw error }
            session = newSession
            let result = try await client.profile(session: newSession)
            guard gate.accepts(token) else { return }
            profile = result
            announceSessionChange()
        }
    }
    /// Public exams need no bearer token; nil means this build has no API configuration.
    func fetchWeeklyExam(languageID: String, level: String) async throws -> ExamSnapshotFactory.PreparedExam? {
        guard !isPreview, let client else { return nil }
        let data = try await client.weeklyExam(languageID: languageID, level: level)
        guard let json = String(data: data, encoding: .utf8) else { throw ManagedAccountError.invalidResponse }
        do { return try ExamSnapshotFactory.prepare(snapshotJSON: json) }
        catch { throw ManagedAccountError.invalidResponse }
    }

    /// Drains only the current account's durable outbox. Initial creation replays its
    /// frozen payload; every later event waits for the exact prior acknowledgement.
    func syncExamHistory(using learningStore: LearningStore, onComplete: @escaping () -> Void = {}) {
        guard (!isPreview || allowsFixtureNetworkInPreview), !isBusy, let client,
              let capturedSession = session else { return }
        let ownerID = capturedSession.accountID
        run { [self] token in
            let events = try learningStore.pendingExamOutbox(ownerAccountID: ownerID)
            for event in events {
                guard gate.accepts(token), session?.accountID == ownerID, !Task.isCancelled else {
                    throw ManagedAccountError.cancelled
                }
                let eventID = event.text("event_id")
                let attemptID = event.text("attempt_id")
                let revision = event.long("revision")
                guard revision > 0,
                      let payloadData = event.text("payload_json").data(using: .utf8),
                      let payload = try? JSONSerialization.jsonObject(with: payloadData) as? [String: Any]
                else { throw ManagedAccountError.invalidResponse }

                let response: Data
                if event.text("event_type") == "created" {
                    guard let snapshot = payload["snapshot"] as? [String: Any],
                          let initialAttempt = payload["initialAttempt"] as? [String: Any],
                          initialAttempt["attemptId"] as? String == attemptID,
                          (initialAttempt["revision"] as? NSNumber)?.int64Value == revision else {
                        throw ManagedAccountError.invalidResponse
                    }
                    response = try await client.syncExamAttempt(session: capturedSession,
                        requestEnvelope: JSONSerialization.data(withJSONObject: [
                            "snapshots": [snapshot], "attempts": [initialAttempt]
                        ], options: [.sortedKeys]))
                    guard gate.accepts(token), session?.accountID == ownerID else {
                        throw ManagedAccountError.cancelled
                    }
                    guard let object = try JSONSerialization.jsonObject(with: response) as? [String: Any],
                          let accepted = object["accepted"] as? [[String: Any]],
                          accepted.contains(where: {
                              $0["attemptId"] as? String == attemptID &&
                              ($0["revision"] as? NSNumber)?.int64Value == revision
                          }) else { throw ManagedAccountError.invalidResponse }
                } else {
                    let type = event.text("event_type")
                    guard ["progress", "completed", "takeover"].contains(type) else {
                        throw ManagedAccountError.invalidResponse
                    }
                    var eventPayload = payload
                    eventPayload.removeValue(forKey: "attemptId")
                    if type == "progress" || type == "takeover" {
                        eventPayload["updatedAt"] = Self.isoTimestamp(event.real("created_at"))
                    } else if type == "completed" {
                        eventPayload["completedAt"] = Self.isoTimestamp(event.real("created_at"))
                    }
                    let envelope: [String: Any] = ["events": [[
                        "eventId": eventID, "attemptId": attemptID, "baseRevision": revision - 1,
                        "revision": revision, "type": type, "payload": eventPayload
                    ]]]
                    response = try await client.syncExamEvent(session: capturedSession,
                        eventEnvelope: JSONSerialization.data(withJSONObject: envelope, options: [.sortedKeys]))
                    guard gate.accepts(token), session?.accountID == ownerID else {
                        throw ManagedAccountError.cancelled
                    }
                    let object = try JSONSerialization.jsonObject(with: response) as? [String: Any]
                    if let conflict = object?["conflict"] as? [String: Any],
                       let attempt = conflict["attempt"],
                       JSONSerialization.isValidJSONObject(attempt) {
                        let bytes = try JSONSerialization.data(withJSONObject: attempt, options: [.sortedKeys])
                        guard let json = String(data: bytes, encoding: .utf8),
                              try learningStore.persistExamConflict(ownerAccountID: ownerID, attemptID: attemptID,
                                  serverAttemptJSON: json, receivedAt: Date.now.timeIntervalSince1970) else {
                            throw ManagedAccountError.invalidResponse
                        }
                        break
                    }
                    guard let acks = object?["acks"] as? [[String: Any]],
                          acks.contains(where: {
                              $0["eventId"] as? String == eventID &&
                              $0["attemptId"] as? String == attemptID &&
                              ($0["revision"] as? NSNumber)?.int64Value == revision &&
                              $0["status"] as? String == "accepted"
                          }) else { throw ManagedAccountError.invalidResponse }
                }
                guard gate.accepts(token), session?.accountID == ownerID,
                      try learningStore.acknowledgeExamOutbox(ownerAccountID: ownerID, attemptID: attemptID,
                          eventID: eventID, revision: revision, acknowledgedAt: Date.now.timeIntervalSince1970)
                else { throw ManagedAccountError.cancelled }
            }
            guard gate.accepts(token), session?.accountID == ownerID else { throw ManagedAccountError.cancelled }
            onComplete()
        }
    }

    /// Fetches every account-owned page and imports details insert-only; existing
    /// local or anonymous attempt identities are deliberately left untouched.
    func restoreExamHistory(using learningStore: LearningStore, onComplete: @escaping () -> Void = {}) {
        guard (!isPreview || allowsFixtureNetworkInPreview), !isBusy, let client,
              let capturedSession = session else { return }
        let ownerID = capturedSession.accountID
        run { [self] token in
            var cursor: String?
            var seenCursors = Set<String>()
            repeat {
                guard gate.accepts(token), session?.accountID == ownerID, !Task.isCancelled else {
                    throw ManagedAccountError.cancelled
                }
                let pageData = try await client.examAttempts(session: capturedSession, cursor: cursor)
                guard gate.accepts(token), session?.accountID == ownerID,
                      let page = try JSONSerialization.jsonObject(with: pageData) as? [String: Any],
                      let attempts = page["attempts"] as? [[String: Any]], attempts.count <= 10 else {
                    throw ManagedAccountError.invalidResponse
                }
                var pageRecords: [ExamRestoreRecord] = []
                for listed in attempts {
                    guard gate.accepts(token), session?.accountID == ownerID,
                          let listedID = listed["attemptId"] as? String,
                          UUID(uuidString: listedID) != nil else { throw ManagedAccountError.invalidResponse }
                    let detailData = try await client.examAttempt(session: capturedSession, attemptID: listedID)
                    guard gate.accepts(token), session?.accountID == ownerID,
                          let detail = try JSONSerialization.jsonObject(with: detailData) as? [String: Any],
                          let remote = detail["attempt"] as? [String: Any],
                          remote["attemptId"] as? String == listedID,
                          let remoteBytes = try? JSONSerialization.data(withJSONObject: remote, options: [.sortedKeys]),
                          let remoteJSON = String(data: remoteBytes, encoding: .utf8),
                          let value = detail["snapshot"] as? [String: Any] else {
                         throw ManagedAccountError.invalidResponse
                     }
                     let snapshotBytes = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
                     guard let snapshotJSON = String(data: snapshotBytes, encoding: .utf8) else {
                         throw ManagedAccountError.invalidResponse
                     }
                     let verifiedSnapshot: ExamSnapshotRecord
                     do { verifiedSnapshot = try ExamSnapshotFactory.prepare(snapshotJSON: snapshotJSON).snapshot }
                     catch { throw ManagedAccountError.invalidResponse }
                     pageRecords.append(ExamRestoreRecord(snapshot: verifiedSnapshot, attemptJSON: remoteJSON))
                }
                guard let cursorValue = page["nextCursor"], cursorValue is NSNull || cursorValue is String else {
                    throw ManagedAccountError.invalidResponse
                }
                let next = cursorValue as? String
                if let next {
                    guard next != cursor, seenCursors.insert(next).inserted else {
                        throw ManagedAccountError.invalidResponse
                    }
                }
                guard gate.accepts(token), session?.accountID == ownerID else { throw ManagedAccountError.cancelled }
                if !pageRecords.isEmpty {
                    _ = try learningStore.restoreExamAttempts(records: pageRecords, ownerAccountID: ownerID)
                }
                cursor = next
            } while cursor != nil
            guard gate.accepts(token), session?.accountID == ownerID else { throw ManagedAccountError.cancelled }
            onComplete()
        }
    }

    private static func isoTimestamp(_ seconds: Double) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter.string(from: Date(timeIntervalSince1970: seconds))
    }

    func refresh() {
        guard !isBusy, let client, let session else { return }
        run { [self] token in
            let result = try await client.profile(session: session)
            guard gate.accepts(token) else { return }
            profile = result
        }
    }
    #if DEBUG && targetEnvironment(simulator)
    func switchExamRecoveryUITestAccount() {
        guard let fixture = uiTestFixture,
              let accountA = fixture.session(for: "A") else { return }
        let next = session?.accountID == accountA.accountID ? "B" : "A"
        guard let replacement = fixture.session(for: next) else { return }
        session = replacement
    }
    #endif

    func signOut() {
        guard !isBusy, let client, let session, let keychain else { return }
        run { [self] token in
            var remoteFailed = false
            do { try await client.signOut(session: session) }
            catch let error as ManagedAccountError where error == .server("sign_in_required") { /* Already expired or revoked. */ }
            catch { remoteFailed = true }
            guard gate.accepts(token) else { return }
            try keychain.remove()
            self.session = nil; profile = nil
            announceSessionChange()
            if remoteFailed { message = "Signed out on this iPhone. We couldn’t reach Palique to revoke other sessions; they expire within 24 hours." }
        }
    }
    func deleteAccount() {
        guard !isBusy, let client, let session, let keychain else { return }
        run { [self] token in
            var code: String?
            if session.provider == .apple {
                // A fresh code lets the server verify this Apple identity and revoke its authorization.
                code = try await identity.apple(nonce: ManagedAccountIdentity.random()).authorizationCode
            }
            guard gate.accepts(token) else { return }
            try await client.delete(session: session, appleCode: code)
            guard gate.accepts(token) else { return }
            self.session = nil; profile = nil
            announceSessionChange()
            do { try keychain.remove() }
            catch {
                message = "Account deleted. Palique couldn’t clear its local secure sign-in record. Unlock this iPhone and reopen Account to clear it."
                return
            }
            message = "Account deleted. Your learning history remains on this iPhone."
        }
    }
    /// Close pending sign-in on dismissal. Account mutations finish so their result is not ambiguous.
    func cancelSignIn() {
        guard session == nil else { return }
        gate.cancel(); operation?.cancel(); identity.cancel(); operation = nil; isBusy = false
    }
    /// Announces the Keychain truth so listeners (the token proxy engine) can
    /// re-attach or clear without polling.
    private func announceSessionChange() {
        NotificationCenter.default.post(name: .managedAccountSessionChanged, object: nil)
    }
    private func run(_ body: @escaping @MainActor (UInt64) async throws -> Void) {
        let token = gate.begin(); isBusy = true; message = nil
        operation = Task { [self] in
            defer { if gate.accepts(token) { isBusy = false; operation = nil } }
            do { try await body(token) }
            catch {
                guard gate.accepts(token) else { return }
                if error as? ManagedAccountError == .server("sign_in_required") {
                    session = nil; profile = nil
                    do { try keychain?.remove() } catch { message = Self.message(for: error); return }
                    announceSessionChange()
                }
                if error as? ManagedAccountError != .cancelled, !(error is CancellationError) {
                    message = Self.message(for: error)
                }
            }
        }
    }
    private static func message(for error: Error) -> String {
        switch error as? ManagedAccountError {
            // TODO(rebrand): support@app.example is a placeholder; replace it with the
            // publisher's real support address before any store submission.
        case .secureStorage: "Palique couldn’t update this iPhone’s secure account storage. Unlock the iPhone and try again."
        case .server("unresolved_billing"): "Your account has a balance, pending payment or active usage. Contact support@app.example to resolve it before deleting your account."
        case .server("sign_in_required"): "Please sign in again. Your learning history is still on this iPhone."
        case .server("rate_limit"): "Please try again later."
        case .server("apple_sign_in_not_ready"), .server("apple_revocation_not_configured"):
            "Sign in with Apple is temporarily unavailable. Please try again later."
        case .server("identity_provider_not_configured"), .unavailable:
            "This sign-in option is unavailable. Please try again later."
        case .invalidCallback, .invalidResponse, .server("invalid_challenge"):
            "Sign-in couldn’t be verified. Please start again."
        default: "Palique couldn’t complete that request. Check your connection and try again."
        }
    }
}
