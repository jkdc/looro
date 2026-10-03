import Foundation
import LearningCore

/// Hosted conversations for signed-in members, mirroring the Android wire
/// shapes exactly (flat create body + Idempotency-Key, status polling,
/// helpers channel, safe error codes). The audio never transits the backend:
/// the phone connects to the provider over WebRTC using the answer SDP.

struct HostedMinuteBalance: Decodable, Equatable {
    let balanceMilliseconds: Int
    let reservedMilliseconds: Int
    let availableMilliseconds: Int

    var canStartConversation: Bool { availableMilliseconds > 0 }

    init(balanceMilliseconds: Int, reservedMilliseconds: Int) {
        self.balanceMilliseconds = balanceMilliseconds
        self.reservedMilliseconds = reservedMilliseconds
        self.availableMilliseconds = balanceMilliseconds - reservedMilliseconds
    }

    static func decode(_ data: Data) throws -> HostedMinuteBalance {
        struct Wire: Decodable { let unit: String; let billingBasis: String
            let balanceMilliseconds: Int; let reservedMilliseconds: Int }
        let wire = try JSONDecoder().decode(Wire.self, from: data)
        guard wire.unit == "milliseconds", wire.billingBasis == "connected-conversation-time",
              wire.balanceMilliseconds >= 0, wire.reservedMilliseconds >= 0,
              wire.reservedMilliseconds <= wire.balanceMilliseconds
        else { throw ManagedAccountError.invalidResponse }
        return HostedMinuteBalance(balanceMilliseconds: wire.balanceMilliseconds,
                                   reservedMilliseconds: wire.reservedMilliseconds)
    }
}

struct HostedCapabilities: Decodable, Equatable {
    let hostedMinutes: Bool
    let experimental: Bool?

    /// The backend promises hosted minutes only with the experimental marker,
    /// mirroring the Android client's interpretation.
    var available: Bool { hostedMinutes && experimental == true }

    static func decode(_ data: Data) throws -> HostedCapabilities {
        try JSONDecoder().decode(HostedCapabilities.self, from: data)
    }
}

struct HostedStatus: Decodable, Equatable {
    let sessionID: String
    let state: String
    var isClosed: Bool { state == "closed" }

    static func decode(_ data: Data) throws -> HostedStatus {
        let status = try JSONDecoder().decode(HostedStatus.self, from: data)
        guard UUID(uuidString: status.sessionID) != nil else { throw ManagedAccountError.invalidResponse }
        return status
    }
}

enum HostedFailure: LocalizedError {
    case unavailable, signInRequired, insufficientMinutes, rateLimited

    var errorDescription: String? {
        switch self {
        case .unavailable: "Hosted conversations aren’t available right now. Please try again later."
        case .signInRequired: "Sign in to your Palique account to use included minutes."
        case .insufficientMinutes: "You’ve used all your included minutes for now."
        case .rateLimited: "Too many requests. Please try again in a moment."
        }
    }

    static func from(code: String) -> HostedFailure {
        switch code {
        case "sign_in_required": .signInRequired
        case "insufficient_minutes": .insufficientMinutes
        case "rate_limit": .rateLimited
        default: .unavailable
        }
    }
}

enum HelperPurpose: String {
    case meaning, assessment, lookup, delegation
    case typedReply = "typed_reply"
    case topic
    case scenarioReview = "scenario_review"
}

/// One route for every learning call: the backend helpers channel while a
/// hosted session is active, the token-billing proxy when the learner selected
/// Palique (tokens), the personal-key client otherwise. Prompts are identical
/// on all three paths.
@MainActor final class HelperRouter {
    private let api: APIClient
    private(set) var hostedSessionID: String?
    private var client: ManagedAccountClient?
    private var session: ManagedAccountSession?
    private var proxy: HostedProxyEngine?
    init(api: APIClient) { self.api = api }

    func useHosted(sessionID: String, client: ManagedAccountClient, session: ManagedAccountSession) {
        self.hostedSessionID = sessionID; self.client = client; self.session = session
    }
    func clearHosted() { hostedSessionID = nil; client = nil; session = nil }

    /// Routes learning calls through the token-billing proxy until cleared.
    ///
    /// Bearer lifecycle contract (the wiring Task 5 must honor — the eager-bearer
    /// form was chosen over Kotlin's per-request session read, so re-attach and
    /// clear are mandatory, not optional):
    /// 1. ATTACH here when the learner selects Palique (tokens), with the
    ///    `ManagedAccountConfiguration.origin` and a token read from
    ///    `ManagedAccountKeychain(scope: configuration.storageScope)`.
    /// 2. RE-ATTACH whenever the app session is (re)issued (sign-in, renewal) —
    ///    the captured bearer goes stale at `session.expiresAt`.
    /// 3. CLEAR via `clearHostedProxy()` on sign-out or account switch; otherwise
    ///    the engine keeps sending a dead token and every call fails with
    ///    `Failure.signInRequired` (fail-closed, never a wrong account's wallet).
    func useHostedProxy(origin: URL, bearer: String) {
        proxy = HostedProxyEngine(origin: origin, bearer: bearer)
    }
    func clearHostedProxy() { proxy = nil }

    func respond(purpose: HelperPurpose, instructions: String, input: String,
                 schema: [String: Any]? = nil, search: Bool = false) async throws -> APIResult {
        if let hostedSessionID, let client, let session {
            return try await client.hostedHelper(sessionID: hostedSessionID, purpose: purpose,
                                                 instructions: instructions, input: input,
                                                 schema: schema, search: search, session: session)
        }
        if let proxy {
            // The proxy takes an OpenAI-compatible chat-completions body; the
            // schema travels as its serialized JSON (json_object mode + embedded
            // in the system prompt, like the Android compatible engines). The
            // proxy path is text-only, so sources are always empty.
            let schemaJSON = schema.flatMap { try? JSONSerialization.data(withJSONObject: $0) }
                .flatMap { String(data: $0, encoding: .utf8) }
            let result = try await proxy.respond(instructions: instructions, input: input,
                                                 schema: schemaJSON, search: search)
            return APIResult(text: result.text, sources: [],
                             usage: APIUsage(input: result.inputTokens, output: result.outputTokens))
        }
        return try await api.respond(instructions: instructions, input: input, schema: schema, search: search)
    }
}

extension ManagedAccountClient {
    /// Presence of the authorization header is what the capabilities endpoint checks.
    func hostedCapabilities(session: ManagedAccountSession?) async throws -> HostedCapabilities {
        let data = try await hostedData("/v1/live/capabilities", method: "GET", jsonBody: nil, session: session)
        return try HostedCapabilities.decode(data)
    }

    func minutesBalance(session: ManagedAccountSession) async throws -> HostedMinuteBalance {
        let data = try await hostedData("/v1/minutes", method: "GET", jsonBody: nil, session: session)
        return try HostedMinuteBalance.decode(data)
    }

    /// Flat create body + Idempotency-Key, per the backend contract; the
    /// answer SDP is top-level and the audio connects phone-to-provider directly.
    func hostedCreate(sdp: String, language: String, instructions: String, history: [[String: Any]],
                      requestedMilliseconds: Int?, session: ManagedAccountSession)
        async throws -> (sessionID: String, answerSDP: String) {
        var body: [String: Any] = ["sdp": sdp, "language": language, "instructions": instructions, "history": history]
        if let requestedMilliseconds { body["requestedMilliseconds"] = requestedMilliseconds }
        let data = try await hostedData("/v1/live/sessions", method: "POST", jsonBody: body, session: session,
                                        idempotencyKey: UUID().uuidString, sizeLimit: 1_048_576)
        return try Self.parseCreate(data)
    }

    static func parseCreate(_ data: Data) throws -> (sessionID: String, answerSDP: String) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let sessionID = json["sessionID"] as? String, UUID(uuidString: sessionID) != nil,
              let answerSDP = json["sdp"] as? String, answerSDP.hasPrefix("v=0")
        else { throw ManagedAccountError.invalidResponse }
        return (sessionID, answerSDP)
    }

    func hostedStatus(sessionID: String, session: ManagedAccountSession) async throws -> HostedStatus {
        let data = try await hostedData("/v1/live/sessions/\(sessionID)", method: "GET", jsonBody: nil, session: session)
        return try HostedStatus.decode(data)
    }

    func hostedClose(sessionID: String, session: ManagedAccountSession) async throws -> HostedStatus {
        let data = try await hostedData("/v1/live/sessions/\(sessionID)/close", method: "POST",
                                        jsonBody: [String: Any](), session: session)
        return try HostedStatus.decode(data)
    }

    func hostedCurrent(session: ManagedAccountSession) async throws -> HostedStatus? {
        struct Current: Decodable { let session: HostedStatus? }
        let data = try await hostedData("/v1/live/sessions/current", method: "GET", jsonBody: nil, session: session)
        return try JSONDecoder().decode(Current.self, from: data).session
    }

    /// The learning-helper channel for an active hosted session; answers in the
    /// same shape as the personal-key client so call sites stay unchanged.
    func hostedHelper(sessionID: String, purpose: HelperPurpose, instructions: String, input: String,
                      schema: [String: Any]? = nil, search: Bool = false, session: ManagedAccountSession)
        async throws -> APIResult {
        var body: [String: Any] = ["requestID": UUID().uuidString, "purpose": purpose.rawValue,
                                   "instructions": instructions, "input": input, "search": search]
        if let schema { body["schema"] = schema }
        let data = try await hostedData("/v1/live/sessions/\(sessionID)/helpers", method: "POST",
                                        jsonBody: body, session: session, sizeLimit: 262_144)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = json["text"] as? String, !text.isEmpty else { throw ManagedAccountError.invalidResponse }
        var sources: [SourceLink] = []
        for source in json["sources"] as? [[String: Any]] ?? [] {
            guard let title = source["title"] as? String, let url = source["url"] as? String else { continue }
            let link = SourceLink(title: title, url: url)
            if link.safeURL != nil && !sources.contains(where: { $0.url == url }) { sources.append(link) }
        }
        let usage = json["usage"] as? [String: Any] ?? [:]
        return APIResult(text: text, sources: sources,
                         usage: APIUsage(input: usage["inputTokens"] as? Int ?? 0,
                                         output: usage["outputTokens"] as? Int ?? 0,
                                         searches: usage["searchCalls"] as? Int ?? 0))
    }

    private func hostedData(_ path: String, method: String, jsonBody: [String: Any]?, session: ManagedAccountSession?,
                            idempotencyKey: String? = nil, sizeLimit: Int = 65_536) async throws -> Data {
        guard session.isUsable(scope: configuration.storageScope) else { throw HostedFailure.signInRequired }
        var request = URLRequest(url: try configuration.endpoint(path))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("Bearer " + session.accessToken, forHTTPHeaderField: "Authorization")
        if let idempotencyKey { request.setValue(idempotencyKey, forHTTPHeaderField: "Idempotency-Key") }
        if let jsonBody { request.httpBody = try JSONSerialization.data(withJSONObject: jsonBody) }
        let (data, response) = try await http.send(request, limit: sizeLimit)
        guard (200...299).contains(response.statusCode) else {
            struct Failure: Decodable { struct Detail: Decodable { let code: String }; let error: Detail }
            let code = (try? JSONDecoder().decode(Failure.self, from: data))?.error.code ?? ""
            throw HostedFailure.from(code: code)
        }
        return data
    }
}

/// Local, per-device provider preference (mirrors Android's provider store).
/// Deliberately not an Archive field: switching phones re-defaults to the
/// personal key, and hosted minutes are never spent without an explicit choice.
struct HostedPreferenceStore {
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    private static let key = "hostedMinutesPreferred"
    private static let proxyKey = "hostedProxyPreferred"
    var prefersHostedMinutes: Bool {
        get { defaults.bool(forKey: Self.key) }
        set { defaults.set(newValue, forKey: Self.key) }
    }
    /// The "Palique (tokens)" engine selection, mirroring Android's local engine
    /// selection store; the choice applies to the next conversation. Set from
    /// Settings (`ConversationCoordinator.selectTokensEngine`), which also owns
    /// the ATTACH / RE-ATTACH / CLEAR lifecycle documented on HelperRouter.
    var prefersHostedProxy: Bool {
        get { defaults.bool(forKey: Self.proxyKey) }
        set { defaults.set(newValue, forKey: Self.proxyKey) }
    }
    static var hostedFlagEnabled: Bool {
        let value = Bundle.main.object(forInfoDictionaryKey: "AppHostedMinutesEnabled")
        return value as? Bool == true || (value as? String)?.uppercased() == "YES"
    }
}
