import Foundation
import Security
import CryptoKit
import LearningCore

extension ManagedAccountConfiguration {
    /// These keys contain public client IDs, never OAuth secrets or Palique bearer tokens.
    static func load(bundle: Bundle = .main) -> Self? {
        #if APP_SIGN_IN_WITH_APPLE
        let appleCapabilityEnabled = true
        #else
        let appleCapabilityEnabled = false
        #endif
        func enabled(_ key: String) -> Bool {
            let value = bundle.object(forInfoDictionaryKey: key)
            return value as? Bool == true || (value as? String)?.uppercased() == "YES"
        }
        guard enabled("AppManagedAccountsEnabled"),
              let api = bundle.object(forInfoDictionaryKey: "AppManagedAPIURL") as? String,
              let bundleID = bundle.bundleIdentifier else { return nil }
        let google = enabled("AppGoogleSignInEnabled")
            ? bundle.object(forInfoDictionaryKey: "AppGoogleClientID") as? String : nil
        let apple = enabled("AppAppleSignInEnabled") && appleCapabilityEnabled
            ? bundle.object(forInfoDictionaryKey: "AppAppleClientID") as? String : nil
        let schemes = (bundle.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? [])
            .flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] }
        return try? Self(apiURL: api, googleClientID: google, appleClientID: apple,
                         bundleID: bundleID, registeredURLSchemes: schemes, appleCapabilityEnabled: appleCapabilityEnabled)
    }
}

protocol ManagedAccountRequestTransport: Sendable {
    func send(_ request: URLRequest, limit: Int) async throws -> (Data, HTTPURLResponse)
}

/// No redirect can carry an account token, provider code or verifier to another destination.
final class ManagedAccountHTTP: NSObject, URLSessionTaskDelegate, ManagedAccountRequestTransport, @unchecked Sendable {
    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil; configuration.httpShouldSetCookies = false
        configuration.urlCache = nil; configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 20; configuration.timeoutIntervalForResource = 30
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
    func send(_ request: URLRequest, limit: Int = 65_536) async throws -> (Data, HTTPURLResponse) {
        let session = makeSession()
        defer { session.invalidateAndCancel() }
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse,
                  response.url == request.url, response.expectedContentLength <= limit
            else { throw ManagedAccountError.invalidResponse }
            var data = Data()
            for try await byte in bytes {
                guard data.count < limit else { throw ManagedAccountError.invalidResponse }
                data.append(byte)
            }
            return (data, http)
        } catch let error as ManagedAccountError { throw error }
        catch is CancellationError { throw ManagedAccountError.cancelled }
        catch let error as URLError where error.code == .cancelled { throw ManagedAccountError.cancelled }
        catch { throw ManagedAccountError.transport }
    }
}

#if DEBUG && targetEnvironment(simulator)
private struct ManagedAccountFixtureCompletion: Encodable {
    let result: LevelTestResult
    let answers: [String: String]
    let verdicts: [String: Bool]
}

actor ManagedAccountUIFixtureTransport: ManagedAccountRequestTransport {
    private let tokens: [String: String]
    private let scenario: String
    private let attempts: [String: [String: Any]]
    private let summaries: [[String: Any]]
    /// examId → served snapshot: a history page may mix bundled and retired
    /// generated (llm) exams, each restored from its own frozen content.
    private let snapshots: [String: [String: Any]]
    private var createdAttempts: [String: [String: Any]] = [:]
    private let host = "exam-recovery-fixture.invalid"

    init(tokens: [String: String], scenario: String,
         attempts: [String: [String: Any]], summaries: [[String: Any]],
         snapshots: [String: [String: Any]]) {
        self.tokens = tokens
        self.scenario = scenario
        self.attempts = attempts
        self.summaries = summaries
        self.snapshots = snapshots
    }

    /// The DAO and store serialize timestamps with fractional seconds; accept
    /// both shapes so the fixture mirrors the real server's ISO-8601 handling.
    func fixtureDate(_ value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: value) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: value)
    }

    func send(_ request: URLRequest, limit: Int) async throws -> (Data, HTTPURLResponse) {
        guard let url = request.url, url.host == host,
              let authorization = request.value(forHTTPHeaderField: "Authorization"),
              authorization.hasPrefix("Bearer "),
              let account = tokens.first(where: {
                  $0.value == String(authorization.dropFirst("Bearer ".count))
              })?.key, account == "A",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw ManagedAccountError.transport
        }
        // Offline loss scenario: creation and progress can never leave the
        // device, so a later owner switch must prove nothing was uploaded.
        if scenario == "sync-unavailable", request.httpMethod == "POST" {
            throw ManagedAccountError.transport
        }
        let responseObject: [String: Any]
        var statusCode = 200
        if scenario.hasPrefix("restore"), request.httpMethod == "GET",
           url.path == "/v1/level-test-attempts" {
            let items = components.queryItems ?? []
            guard items.allSatisfy({ ["limit", "cursor"].contains($0.name) }),
                  Set(items.map(\.name)).count == items.count,
                  Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })["limit"] == "10" else {
                throw ManagedAccountError.invalidResponse
            }
            let cursor = items.first(where: { $0.name == "cursor" })?.value
            switch cursor {
            case nil:
                // restore-race: hold the first page long enough for the UI test
                // to switch accounts while the response is in flight, proving a
                // retained response can never render another owner's rows.
                if scenario == "restore-race" {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                }
                responseObject = ["attempts": [summaries[0], summaries[1]], "nextCursor": "fixture-page-2"]
            case "fixture-page-2":
                responseObject = ["attempts": [summaries[2]], "nextCursor": NSNull()]
            default:
                throw ManagedAccountError.invalidResponse
            }
        } else if scenario.hasPrefix("restore"), request.httpMethod == "GET",
                  url.path.hasPrefix("/v1/level-test-attempts/"),
                  url.path.split(separator: "/").count == 3,
                  components.queryItems?.isEmpty != false,
                  let attemptID = url.path.split(separator: "/").last.map(String.init),
                  let attempt = attempts[attemptID],
                  let examID = attempt["examId"] as? String,
                  let snapshot = snapshots[examID] {
            responseObject = ["attempt": attempt, "snapshot": snapshot]
        } else if scenario.hasPrefix("conflict-"), request.httpMethod == "POST",
                  url.path == "/v1/level-test-attempts/sync",
                  components.queryItems?.isEmpty != false,
                  let body = request.httpBody,
                  let object = try JSONSerialization.jsonObject(with: body) as? [String: Any],
                  let initialAttempts = object["attempts"] as? [[String: Any]], initialAttempts.count == 1,
                  let snapshots = object["snapshots"] as? [[String: Any]], snapshots.count == 1,
                  let attempt = initialAttempts.first,
                  Set(attempt.keys) == Set(["attemptId", "examId", "state", "position", "revision", "startedAt", "updatedAt", "completedAt", "answers", "result"]),
                  let attemptID = attempt["attemptId"] as? String,
                  UUID(uuidString: attemptID) != nil,
                  let examID = attempt["examId"] as? String, !examID.isEmpty,
                  attempt["state"] as? String == "in_progress",
                  let revision = (attempt["revision"] as? NSNumber)?.int64Value, revision > 0,
                  let position = (attempt["position"] as? NSNumber)?.intValue, position >= 0,
                  let startedAt = attempt["startedAt"] as? String,
                  let updatedAt = attempt["updatedAt"] as? String,
                  let startedAtDate = fixtureDate(startedAt),
                  let updatedAtDate = fixtureDate(updatedAt),
                  let answers = attempt["answers"] as? [[String: Any]],
                  snapshots[0]["examId"] as? String == examID,
                  (attempt["completedAt"] == nil || attempt["completedAt"] is NSNull),
                  (attempt["result"] == nil || attempt["result"] is NSNull),
                  startedAtDate <= updatedAtDate,
                  answers.allSatisfy({ answer in
                      guard Set(answer.keys) == Set(["questionId", "revision", "response"]),
                            let questionID = answer["questionId"] as? String, !questionID.isEmpty,
                            let answerRevision = (answer["revision"] as? NSNumber)?.int64Value,
                            answerRevision > 0, answerRevision <= revision else { return false }
                      return true
                  }) {
            // Model an independent account-side write before the submitted local
            // event: the remote revision advances and the account COMPLETED the
            // attempt, grading a first answer that diverges, so an explicit
            // takeover adopts content visibly different from the local branch.
            // The creation payload carries no answers yet (`initialAttempt.answers`
            // is empty), so the marker rides under the first submitted snapshot
            // item's id — the review screen reads it from the frozen result.
            let firstItemID = (snapshots[0]["items"] as? [[String: Any]])?.first?["id"] as? String
            var remoteAttempt = attempt
            remoteAttempt["revision"] = revision + 1
            remoteAttempt["position"] = position
            remoteAttempt["answers"] = firstItemID.map { questionID in
                [["questionId": questionID, "revision": revision + 1,
                  "response": ["answer": "Server-confirmed takeover answer"]]]
            } ?? [[String: Any]]()
            remoteAttempt["state"] = "completed"
            remoteAttempt["completedAt"] = "2027-01-15T08:30:00.000Z"
            remoteAttempt["result"] = [
                "result": [
                    "languageID": snapshots[0]["languageID"] as? String ?? "en",
                    "level": snapshots[0]["level"] as? String ?? "A1",
                    "weekIndex": snapshots[0]["weekId"] as? Int ?? 202641,
                    "overall": 100, "sections": [String: Int](), "passed": true,
                    "weakSections": [String](), "weakUnitIDs": [String](),
                    "at": 1_800_090_000,
                ],
                "answers": firstItemID.map { [$0: "Server-confirmed takeover answer"] } ?? [:],
                "verdicts": firstItemID.map { [$0: false] } ?? [String: Bool](),
            ]
            createdAttempts[attemptID] = remoteAttempt
            responseObject = ["accepted": [["attemptId": attemptID, "revision": revision]]]
        } else if scenario.hasPrefix("conflict-"), request.httpMethod == "POST",
                  url.path == "/v1/level-test-attempts/events",
                  components.queryItems?.isEmpty != false,
                  let body = request.httpBody,
                  let object = try JSONSerialization.jsonObject(with: body) as? [String: Any],
                  let events = object["events"] as? [[String: Any]], events.count == 1,
                  let event = events.first,
                  Set(event.keys) == Set(["eventId", "attemptId", "baseRevision", "revision", "type", "payload"]),
                  let attemptID = event["attemptId"] as? String,
                  let eventID = event["eventId"] as? String, UUID(uuidString: eventID) != nil,
                  event["type"] as? String == "progress",
                  let serverAttempt = createdAttempts[attemptID],
                  let baseRevision = (event["baseRevision"] as? NSNumber)?.int64Value,
                  let eventRevision = (event["revision"] as? NSNumber)?.int64Value,
                  baseRevision > 0, eventRevision == baseRevision + 1,
                  (serverAttempt["revision"] as? NSNumber)?.int64Value != baseRevision,
                  let payload = event["payload"] as? [String: Any],
                  Set(payload.keys) == Set(["questionId", "response", "position", "updatedAt"]),
                  let questionID = payload["questionId"] as? String, !questionID.isEmpty,
                  payload["response"] is [String: Any],
                  let eventPosition = (payload["position"] as? NSNumber)?.intValue, eventPosition >= 0,
                  let updatedAt = payload["updatedAt"] as? String,
                  fixtureDate(updatedAt) != nil {
            statusCode = 409
            responseObject = ["error": "exam_attempt_conflict",
                              "conflict": ["attempt": serverAttempt]]
        } else {
            throw ManagedAccountError.transport
        }

        let data = try JSONSerialization.data(withJSONObject: responseObject, options: [.sortedKeys])
        guard data.count <= limit,
              let response = HTTPURLResponse(url: url, statusCode: statusCode, httpVersion: "HTTP/1.1",
                  headerFields: ["Content-Type": "application/json"]) else {
            throw ManagedAccountError.invalidResponse
        }
        return (data, response)
    }
}

struct ManagedAccountUIFixture {
    let scenario: String
    let account: String
    let configuration: ManagedAccountConfiguration
    let session: ManagedAccountSession
    let transport: ManagedAccountUIFixtureTransport
    private let sessions: [String: ManagedAccountSession]

    static let argumentPrefixes = ["--exam-recovery-ui-test=", "--exam-recovery-account="]
    static let scenarios: Set<String> = ["restore", "restore-race", "account-isolation", "conflict-fork",
                                         "conflict-takeover", "sync-unavailable"]
    static let accounts: Set<String> = ["A", "B"]

    init(scenario: String, account: String) throws {
        let configuration = try ManagedAccountConfiguration(apiURL: "https://exam-recovery-fixture.invalid",
            bundleID: "com.jkdc.palique", registeredURLSchemes: [], appleCapabilityEnabled: false)
        let tokens = ["A": String(repeating: "A", count: 43), "B": String(repeating: "B", count: 43)]
        var sessions: [String: ManagedAccountSession] = [:]
        for (name, token) in tokens {
            let ids = ["A": "a0000000-0000-4000-8000-000000000001",
                       "B": "b0000000-0000-4000-8000-000000000002"]
            let value: [String: Any] = ["accountID": ids[name]!, "accessToken": token, "expiresInSeconds": 3600]
            let bytes = try JSONSerialization.data(withJSONObject: value)
            let exchange = try JSONDecoder().decode(ManagedAuthExchange.self, from: bytes)
            sessions[name] = try ManagedAccountSession(exchange: exchange, provider: .google,
                                                         scope: configuration.storageScope)
        }
        guard let session = sessions[account] else { throw ManagedAccountError.invalidResponse }
        self.scenario = scenario
        self.account = account
        self.configuration = configuration
        self.session = session
        self.sessions = sessions

        let fixtureDate = Date(timeIntervalSince1970: 1_800_000_000)
        guard let level = LevelTestsRegistry.level(languageID: "en", level: "A1") else {
            throw ManagedAccountError.invalidResponse
        }
        let exam = LevelTestEngine.compose(level: level, weekIndex: 202641)
        let snapshotRecord = try ExamSnapshotFactory.bundled(level: level, exam: exam, now: fixtureDate)
        // A retired generated exam: its week is long gone server-side, but the
        // account still owns attempts frozen on that exact content.
        let retiredExam = LevelTestEngine.compose(level: level, weekIndex: 202639)
        let retiredRecord = try ExamSnapshotFactory.bundled(level: level, exam: retiredExam,
                                                            now: fixtureDate, source: "llm")
        var snapshots: [String: [String: Any]] = [:]
        for record in [snapshotRecord, retiredRecord] {
            guard let object = try? JSONSerialization.jsonObject(with: Data(record.contentJSON.utf8)) as? [String: Any] else {
                throw ManagedAccountError.invalidResponse
            }
            snapshots[record.examID] = object
        }
        let answers = Dictionary(uniqueKeysWithValues: exam.items.map { ($0.id, $0.exercise.answers.first ?? "") })
        let verdicts = Dictionary(uniqueKeysWithValues: exam.items.map { ($0.id, true) })
        let result = LevelTestEngine.grade(level: level, exam: exam, attempts: answers,
                                           at: Int64(fixtureDate.timeIntervalSince1970))
        let completion = ManagedAccountFixtureCompletion(result: result, answers: answers, verdicts: verdicts)
        let resultObject = try JSONSerialization.jsonObject(with: JSONEncoder().encode(completion)) as? [String: Any]
        let timestamp = "2027-01-15T08:00:00.000Z"
        let attemptIDs = ["70000000-0000-4000-8000-000000000001", "70000000-0000-4000-8000-000000000002"]
        var attempts: [String: [String: Any]] = [:]
        var summaries: [[String: Any]] = []
        for attemptID in attemptIDs {
            let attempt: [String: Any] = [
                "attemptId": attemptID, "examId": snapshotRecord.examID, "state": "completed",
                "position": exam.items.count, "revision": 1, "startedAt": timestamp,
                "updatedAt": timestamp, "completedAt": timestamp,
                "answers": exam.items.map { item in
                    ["questionId": item.id, "revision": 1,
                     "response": ["answer": answers[item.id] ?? ""]]
                },
                "result": resultObject ?? [:],
            ]
            attempts[attemptID] = attempt
            summaries.append(["attemptId": attemptID, "state": "completed", "updatedAt": timestamp])
        }
        // Third attempt: frozen on the retired generated exam.
        let retiredID = "70000000-0000-4000-8000-000000000003"
        attempts[retiredID] = [
            "attemptId": retiredID, "examId": retiredRecord.examID, "state": "completed",
            "position": retiredExam.items.count, "revision": 1, "startedAt": timestamp,
            "updatedAt": timestamp, "completedAt": timestamp,
            "answers": retiredExam.items.map { item in
                ["questionId": item.id, "revision": 1,
                 "response": ["answer": retiredExam.items.first?.exercise.answers.first ?? ""]]
            },
            "result": resultObject ?? [:],
        ]
        summaries.insert(["attemptId": retiredID, "state": "completed", "updatedAt": timestamp], at: 1)
        self.transport = ManagedAccountUIFixtureTransport(tokens: tokens, scenario: scenario,
            attempts: attempts, summaries: summaries, snapshots: snapshots)
    }

    static func requested(in arguments: [String]) -> Bool {
        arguments.contains { argument in argumentPrefixes.contains { argument.hasPrefix($0) } }
    }

    static func make(arguments: [String]) throws -> ManagedAccountUIFixture? {
        guard requested(in: arguments) else { return nil }
        let scenarios = arguments.filter { $0.hasPrefix(argumentPrefixes[0]) }
        let accounts = arguments.filter { $0.hasPrefix(argumentPrefixes[1]) }
        guard scenarios.count == 1, accounts.count == 1 else { throw ManagedAccountError.invalidResponse }
        let scenario = String(scenarios[0].dropFirst(argumentPrefixes[0].count))
        let account = String(accounts[0].dropFirst(argumentPrefixes[1].count))
        guard Self.scenarios.contains(scenario), Self.accounts.contains(account) else { throw ManagedAccountError.invalidResponse }
        return try ManagedAccountUIFixture(scenario: scenario, account: account)
    }

    func session(for account: String) -> ManagedAccountSession? { sessions[account] }
}
#endif

struct ManagedAccountClient {
    let configuration: ManagedAccountConfiguration
    let http: any ManagedAccountRequestTransport
    init(configuration: ManagedAccountConfiguration) {
        self.init(configuration: configuration, transport: ManagedAccountHTTP())
    }
    init(configuration: ManagedAccountConfiguration, transport: any ManagedAccountRequestTransport) {
        self.configuration = configuration
        http = transport
    }

    func challenge() async throws -> ManagedAuthChallenge {
        let result: ManagedAuthChallenge = try await request("/v1/auth/challenge", method: "POST", body: [:])
        try result.validate(); return result
    }
    func exchange(provider: ManagedIdentityProvider, token: String, challenge: ManagedAuthChallenge) async throws -> ManagedAuthExchange {
        guard !token.isEmpty, token.utf8.count <= 16_384 else { throw ManagedAccountError.invalidResponse }
        let result: ManagedAuthExchange = try await request("/v1/auth/exchange", method: "POST", body: [
            "provider": provider.rawValue, "idToken": token, "challengeID": challenge.challengeID.uuidString
        ])
        try result.validate(); return result
    }
    func wallet(session: ManagedAccountSession) async throws -> ManagedWallet {
        let result: ManagedWallet = try await request("/v1/wallet", method: "GET", session: session)
        try result.validate(); return result
    }

    // MARK: Palique (tokens) pack commerce
    // The Stripe-checkout mirror of Android's MinuteCommerceClient pack routes:
    // the iOS build has no Play Billing and no StoreKit plumbing, so the server
    // checkout URL opens externally and status is polled by hand. Catalog and
    // order parsing is strict and lives in LearningCore (TokensCommerce.swift).

    /// Pack entries riding `GET /v1/minutes/products?provider=stripe`. The parse
    /// lives in `TokenPackProduct.parseCatalog` (LearningCore): non-pack items
    /// (today `ai_value`, which iOS has no surface for) are skipped, while a
    /// malformed pack entry fails the whole catalog closed, like Android.
    func packCatalog(session: ManagedAccountSession) async throws -> [TokenPackProduct] {
        let data = try await rawRequest("/v1/minutes/products", query: ["provider": "stripe"], session: session)
        guard let packs = TokenPackProduct.parseCatalog(data) else { throw ManagedAccountError.invalidResponse }
        return packs
    }

    /// Fetches a public, server-composed weekly exam snapshot; grading remains local.
    func weeklyExam(languageID: String, level: String) async throws -> Data {
        guard languageID.range(of: "^[a-z]{2,3}(?:-[A-Z]{2})?$", options: .regularExpression) != nil,
              level.range(of: "^[A-C][12]$", options: .regularExpression) != nil else {
            throw ManagedAccountError.invalidResponse
        }
        return try await rawRequest("/v1/level-tests/hybrid",
            query: ["language": languageID, "level": level], responseLimit: 96_000)
    }

    /// Uploads one already-persisted exam outbox event using the owning account session.
    func syncExamEvent(session: ManagedAccountSession, eventEnvelope: Data) async throws -> Data {
        guard !eventEnvelope.isEmpty, eventEnvelope.count <= 256_000 else { throw ManagedAccountError.invalidResponse }
        return try await sendData(try configuration.endpoint("/v1/level-test-attempts/events"),
                                  method: "POST", rawBody: eventEnvelope, session: session,
                                  preserveExamConflict: true)
    }

    /// Creates a frozen snapshot and initial attempt before revision events are sent.
    func syncExamAttempt(session: ManagedAccountSession, requestEnvelope: Data) async throws -> Data {
        guard !requestEnvelope.isEmpty, requestEnvelope.count <= 256_000 else { throw ManagedAccountError.invalidResponse }
        return try await sendData(try configuration.endpoint("/v1/level-test-attempts/sync"),
                                  method: "POST", rawBody: requestEnvelope, session: session)
    }

    /// Restores one bounded page of attempts owned by the authenticated account.
    /// The caller must persist and validate the returned records before displaying them.
    func examAttempts(session: ManagedAccountSession, cursor: String? = nil) async throws -> Data {
        var query = ["limit": "10"]
        if let cursor { query["cursor"] = cursor }
        return try await rawRequest("/v1/level-test-attempts", query: query,
                                    responseLimit: 2_750_000, session: session)
    }

    /// Fetches the immutable snapshot associated with one server-confirmed attempt.
    func examAttempt(session: ManagedAccountSession, attemptID: String) async throws -> Data {
        let data = try await sendData(try configuration.examAttemptEndpoint(attemptID),
                                      session: session, responseLimit: 524_288)
        return data
    }

    /// `POST /v1/minutes/orders` for a pack SKU on the Stripe channel: the quoted
    /// order constants are re-validated against the same invariants as the
    /// catalog AND against the catalog entry the learner chose (mirror of
    /// Android's `StripeMinuteOrder.matches`), the echoed payment order must
    /// match, and only a checked `checkout.stripe.com` URL is ever returned.
    func createPackOrder(session: ManagedAccountSession, sku: String, expecting: TokenPackProduct,
                         idempotencyKey: String)
        async throws -> (orderID: String, checkout: URL) {
        struct Wire: Decodable {
            let orderID: String?, sku: String?, providerProduct: String?, currency: String?
            let totalMinor: Int?, environment: String?
            let entitlementKind: String?, creditNanoUSD: String?
            struct Payment: Decodable { let orderID: String?, checkoutURL: String? }
            let payment: Payment?
        }
        guard sku.count <= 128,
              sku.range(of: TokenPackProduct.identifierPattern, options: .regularExpression) != nil,
              idempotencyKey.range(of: "^[A-Za-z0-9._:-]{8,128}$", options: .regularExpression) != nil
        else { throw ManagedAccountError.invalidResponse }
        let wire: Wire = try await request("/v1/minutes/orders", method: "POST",
                                           body: ["provider": "stripe", "sku": sku], session: session,
                                           idempotencyKey: idempotencyKey)
        guard let pack = TokenPackProduct.parse(sku: wire.sku, providerProduct: wire.providerProduct,
                                                currency: wire.currency, totalMinor: wire.totalMinor,
                                                environment: wire.environment,
                                                entitlementKind: wire.entitlementKind,
                                                creditNanoUSD: wire.creditNanoUSD),
              pack == expecting,
              let payment = wire.payment, payment.orderID == wire.orderID,
              let orderID = wire.orderID,
              let checkout = TokenCheckoutURL.checked(payment.checkoutURL ?? "")
        else { throw ManagedAccountError.invalidResponse }
        return (orderID: orderID, checkout: checkout)
    }

    /// Pack order status; `TokenPackStatus.validate()` enforces the BigInt-string
    /// money shape and Android's four status cross-checks.
    func packStatus(session: ManagedAccountSession, orderID: String) async throws -> TokenPackStatus {
        let data = try await sendData(try configuration.orderEndpoint(orderID), method: "GET", session: session)
        do {
            let status = try JSONDecoder().decode(TokenPackStatus.self, from: data)
            try status.validate()
            guard status.orderID.lowercased() == orderID.lowercased() else { throw ManagedAccountError.invalidResponse }
            return status
        } catch let error as ManagedAccountError {
            throw error
        } catch {
            throw ManagedAccountError.invalidResponse
        }
    }

    func profile(session: ManagedAccountSession) async throws -> ManagedAccountProfile {
        let result: ManagedAccountProfile = try await request("/v1/account", method: "GET", session: session)
        try result.validate(session: session); return result
    }
    func signOut(session: ManagedAccountSession) async throws {
        struct Response: Decodable { let signedOut: Bool }
        let result: Response = try await request("/v1/auth/sign-out", method: "POST", body: [:], session: session)
        guard result.signedOut else { throw ManagedAccountError.invalidResponse }
    }
    func delete(session: ManagedAccountSession, appleCode: String?) async throws {
        struct Response: Decodable { let deleted: Bool }
        let result: Response = try await request("/v1/account", method: "DELETE",
                                               body: appleCode.map { ["appleAuthorizationCode": $0] } ?? [:], session: session)
        guard result.deleted else { throw ManagedAccountError.invalidResponse }
    }
    private func request<T: Decodable>(_ path: String, method: String, body: [String: String]? = nil,
                                        session: ManagedAccountSession? = nil,
                                        query: [String: String]? = nil, idempotencyKey: String? = nil) async throws -> T {
        let data = try await sendData(try configuration.endpoint(path, query: query ?? [:]), method: method, body: body,
                                      session: session, idempotencyKey: idempotencyKey)
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw ManagedAccountError.invalidResponse }
    }

    /// Raw variant for routes parsed in LearningCore (`TokenPackProduct.parseCatalog`).
    private func rawRequest(_ path: String, method: String = "GET", query: [String: String]? = nil,
                            responseLimit: Int = 65_536,
                            session: ManagedAccountSession? = nil) async throws -> Data {
        try await sendData(try configuration.endpoint(path, query: query ?? [:]), method: method,
                           session: session, responseLimit: responseLimit)
    }

    private func sendData(_ url: URL, method: String, body: [String: String]? = nil, rawBody: Data? = nil,
                          session: ManagedAccountSession? = nil, idempotencyKey: String? = nil,
                          preserveExamConflict: Bool = false, responseLimit: Int = 65_536) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = method; request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let rawBody {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = rawBody
        }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        if let idempotencyKey { request.setValue(idempotencyKey, forHTTPHeaderField: "Idempotency-Key") }
        if let session {
            guard session.isUsable(scope: configuration.storageScope) else { throw ManagedAccountError.server("sign_in_required") }
            request.setValue("Bearer " + session.accessToken, forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await http.send(request, limit: responseLimit)
        guard (200...299).contains(response.statusCode) else {
            if preserveExamConflict, response.statusCode == 409,
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               json["error"] as? String == "exam_attempt_conflict",
               let conflict = json["conflict"] as? [String: Any], conflict["attempt"] is [String: Any] {
                return data
            }
            // A 401 means the bearer died (expiry, revocation, remote sign-out):
            // surface sign_in_required directly — the safe-code allowlist does
            // not carry it, and stale-session sweeps depend on the exact code.
            if response.statusCode == 401 { throw ManagedAccountError.server("sign_in_required") }
            struct Failure: Decodable { struct Detail: Decodable { let code: String }; let error: Detail }
            let code = (try? JSONDecoder().decode(Failure.self, from: data))?.error.code ?? "service_unavailable"
            // Only a small allowlist is displayed. Never surface raw provider/server response text.
            let safe = ["unresolved_billing", "rate_limit", "apple_sign_in_not_ready", "apple_revocation_not_configured", "invalid_challenge", "identity_provider_not_configured"]
            throw ManagedAccountError.server(safe.contains(code) ? code : "service_unavailable")
        }
        return data
    }
}

enum DailyPracticeClientError: Error, Equatable {
    case invalidResponse
    case unavailable
    case outcomeUnknown
    case http(status: Int, code: String?)
}

private struct DailyCurrentPackageEnvelope: Decodable {
    let schemaVersion: Int
    let package: DailyPracticePackage
}

/// Owner-bound daily-practice transport. Writes are never retried by this client;
/// callers persist the same event/idempotency identity before invoking it again.
struct DailyPracticeClient {
    private let configuration: ManagedAccountConfiguration
    private let http: any ManagedAccountRequestTransport

    init(configuration: ManagedAccountConfiguration) {
        self.init(configuration: configuration, transport: ManagedAccountHTTP())
    }

    init(configuration: ManagedAccountConfiguration, transport: any ManagedAccountRequestTransport) {
        self.configuration = configuration
        http = transport
    }

    func currentPackage(language: String) async throws -> DailyPracticePackage {
        guard language.range(of: "^[a-z]{2}(?:-[A-Z]{2})?$", options: .regularExpression) != nil else {
            throw DailyPracticeClientError.invalidResponse
        }
        let (data, _) = try await send(path: "/v1/daily-practice/current", method: "GET", query: ["language": language])
        do {
            let envelope = try JSONDecoder().decode(DailyCurrentPackageEnvelope.self, from: data)
            guard envelope.schemaVersion == 1 else { throw DailyPracticeClientError.invalidResponse }
            return envelope.package
        } catch let error as DailyPracticeClientError { throw error }
        catch { throw DailyPracticeClientError.invalidResponse }
    }

    func publishedPackage(packageVersionID: String) async throws -> Data {
        guard Self.isUUID(packageVersionID) else { throw DailyPracticeClientError.invalidResponse }
        return try await send(path: "/v1/daily-practice/packages/\(packageVersionID.lowercased())", method: "GET").data
    }

    func createAttempt(session: ManagedAccountSession, attemptID: String, packageVersionID: String,
                       explanationLanguage: String, dailyConsentVersion: Int, idempotencyKey: String) async throws -> Data {
        guard Self.isUUID(attemptID), Self.isUUID(packageVersionID), Self.isUUID(idempotencyKey),
              ["en", "es"].contains(explanationLanguage), dailyConsentVersion == 1 else {
            throw DailyPracticeClientError.invalidResponse
        }
        let body = try JSONSerialization.data(withJSONObject: [
            "schemaVersion": 1, "attemptId": attemptID.lowercased(), "packageVersionId": packageVersionID.lowercased(),
            "explanationLanguage": explanationLanguage, "dailyConsentVersion": dailyConsentVersion
        ], options: [.sortedKeys])
        let result = try await send(path: "/v1/daily-practice/attempts", method: "POST", body: body,
                                    session: session, idempotencyKey: idempotencyKey.lowercased())
        try Self.validateAttempt(result.data)
        return result.data
    }

    func attempts(session: ManagedAccountSession, language: String = "en", limit: Int = 20,
                  cursor: String? = nil) async throws -> Data {
        guard language.range(of: "^[a-z]{2}(?:-[A-Z]{2})?$", options: .regularExpression) != nil,
              (1...50).contains(limit), cursor.map({ $0.utf8.count <= 2_048 }) ?? true else {
            throw DailyPracticeClientError.invalidResponse
        }
        var query = ["language": language, "limit": String(limit)]
        if let cursor { query["cursor"] = cursor }
        return try await send(path: "/v1/daily-practice/attempts", method: "GET", query: query,
                              session: session, responseLimit: 2 * 1024 * 1024).data
    }

    func attempt(session: ManagedAccountSession, attemptID: String) async throws -> Data {
        guard Self.isUUID(attemptID) else { throw DailyPracticeClientError.invalidResponse }
        let result = try await send(path: "/v1/daily-practice/attempts/\(attemptID.lowercased())", method: "GET", session: session)
        try Self.validateAttempt(result.data)
        return result.data
    }

    func attemptPackage(session: ManagedAccountSession, attemptID: String) async throws -> Data {
        guard Self.isUUID(attemptID) else { throw DailyPracticeClientError.invalidResponse }
        return try await send(path: "/v1/daily-practice/attempts/\(attemptID.lowercased())/package",
                              method: "GET", session: session, responseLimit: 96_000).data
    }

    func appendEvent(session: ManagedAccountSession, attemptID: String, envelope: Data) async throws -> Data {
        guard Self.isUUID(attemptID), !envelope.isEmpty, envelope.count <= 24 * 1024 else {
            throw DailyPracticeClientError.invalidResponse
        }
        let result = try await send(path: "/v1/daily-practice/attempts/\(attemptID.lowercased())/events",
                                    method: "POST", body: envelope, session: session)
        try Self.validateAttempt(result.data)
        return result.data
    }

    func review(session: ManagedAccountSession, attemptID: String) async throws -> (data: Data, status: Int) {
        guard Self.isUUID(attemptID) else { throw DailyPracticeClientError.invalidResponse }
        return try await send(path: "/v1/daily-practice/attempts/\(attemptID.lowercased())/review",
                              method: "GET", session: session, responseLimit: 96_000)
    }

    private func send(path: String, method: String, query: [String: String] = [:], body: Data? = nil,
                      session: ManagedAccountSession? = nil, idempotencyKey: String? = nil,
                      responseLimit: Int = 128 * 1024) async throws -> (data: Data, status: Int) {
        guard var components = URLComponents(url: configuration.origin, resolvingAgainstBaseURL: false) else {
            throw DailyPracticeClientError.unavailable
        }
        components.path = path
        components.queryItems = query.keys.sorted().map { URLQueryItem(name: $0, value: query[$0]) }
        guard let url = components.url else { throw DailyPracticeClientError.invalidResponse }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if let idempotencyKey { request.setValue(idempotencyKey, forHTTPHeaderField: "Idempotency-Key") }
        if let session {
            guard session.isUsable(scope: configuration.storageScope) else { throw DailyPracticeClientError.http(status: 401, code: "sign_in_required") }
            request.setValue("Bearer " + session.accessToken, forHTTPHeaderField: "Authorization")
        }
        let data: Data
        let response: HTTPURLResponse
        do { (data, response) = try await http.send(request, limit: responseLimit) }
        catch is CancellationError { throw CancellationError() }
        catch ManagedAccountError.cancelled { throw ManagedAccountError.cancelled }
        catch { throw method == "GET" ? DailyPracticeClientError.unavailable : DailyPracticeClientError.outcomeUnknown }
        guard (200...299).contains(response.statusCode) else {
            let code = Self.safeErrorCode(data)
            throw DailyPracticeClientError.http(status: response.statusCode, code: code)
        }
        return (data, response.statusCode)
    }

    private static func isUUID(_ value: String) -> Bool {
        value.range(of: "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
                    options: .regularExpression) != nil
    }

    private static func validateAttempt(_ data: Data) throws {
        guard let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              value["schemaVersion"] as? Int == 1, let attemptID = value["attemptId"] as? String,
              isUUID(attemptID), let revision = value["revision"] as? Int, revision >= 0,
              let state = value["state"] as? String, ["in_progress", "completed", "withdrawn"].contains(state)
        else { throw DailyPracticeClientError.invalidResponse }
    }

    private static func safeErrorCode(_ data: Data) -> String? {
        guard let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let error = value["error"] as? [String: Any]
        let code = error?["code"] as? String ?? value["error"] as? String
        guard let code, code.count <= 80,
              code.range(of: "^[a-z_]+$", options: .regularExpression) != nil else { return nil }
        return code
    }
}

struct ManagedAccountKeychain {
    private let scope: String
    init(scope: String) { self.scope = scope }
    private var rejectionMarkerKey: String {
        let digest = SHA256.hash(data: Data(scope.utf8))
        return "com.jkdc.app.managed-account.rejected.\(Data(digest).base64EncodedString())"
    }
    var hasRejectedCredential: Bool {
        UserDefaults.standard.bool(forKey: rejectionMarkerKey)
    }
    func markCredentialRejected() {
        UserDefaults.standard.set(true, forKey: rejectionMarkerKey)
    }
    func clearCredentialRejection() {
        UserDefaults.standard.removeObject(forKey: rejectionMarkerKey)
    }
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.jkdc.app.managed-account",
         kSecAttrAccount as String: Data(SHA256.hash(data: Data(scope.utf8))).base64EncodedString(),
         kSecAttrSynchronizable as String: false]
    }
    func load() throws -> ManagedAccountSession? {
        guard !hasRejectedCredential else {
            try? remove()
            return nil
        }
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw ManagedAccountError.secureStorage }
        guard let value = try? JSONDecoder().decode(ManagedAccountSession.self, from: data), value.isUsable(scope: scope)
        else { try remove(); return nil }
        return value
    }
    func save(_ value: ManagedAccountSession) throws {
        guard value.isUsable(scope: scope) else { throw ManagedAccountError.invalidResponse }
        let data = try JSONEncoder().encode(value)
        let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            guard SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) == errSecSuccess
            else { throw ManagedAccountError.secureStorage }
        } else if status != errSecSuccess { throw ManagedAccountError.secureStorage }
        clearCredentialRejection()
    }
    func remove() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw ManagedAccountError.secureStorage }
    }
}
