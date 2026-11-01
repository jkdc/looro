import Foundation

/// Mirror of Android's `api` engine (change `proxy-api-provider`): the
/// production first-party path. Every call rides the backend proxy
/// (`POST /v1/proxy/responses`) with the app session bearer, carries its
/// `x-palique-purpose` header, and uses the **server-chosen** model resolved
/// from the conversation policy (`GET /v1/conversation-policy`) — never a
/// client-pinned model and never provider key material. The purpose is
/// routing advice for the backend's per-purpose model decision; auth, quota
/// and metering stay account-scoped server-side. Pinned at the macOS gate
/// (mirror of Android's `OpenAICompatibleEngineTest` api cases).
public struct ApiProxyEngine: Sendable {
    /// Wire values shared with the backend's purpose enum (the Kotlin
    /// `HelperPurpose` wire values); a nil purpose maps to `typed_reply`,
    /// the dominant conversation case.
    public enum Purpose: String, Sendable {
        case typedReply = "typed_reply"
        case meaning, assessment, lookup, topic, help
        case scenarioReview = "scenario_review"
        case delegation
    }

    public struct Result: Equatable, Sendable {
        public var text: String
        public var inputTokens: Int
        public var outputTokens: Int
        public init(text: String, inputTokens: Int, outputTokens: Int) {
            self.text = text
            self.inputTokens = inputTokens
            self.outputTokens = outputTokens
        }
    }

    public enum Failure: Error, Equatable, LocalizedError, Sendable {
        /// No valid app session was available to authenticate with.
        case signInRequired
        /// The conversation policy did not resolve a model for this purpose:
        /// the engine refuses to guess what the server should decide.
        case missingModel
        /// 402 `token_balance_exhausted` (balance or daily quota exhausted).
        case balanceExhausted
        /// 403 `unlock_required`.
        case unlockRequired
        /// 429 `account_busy` (per-account in-flight cap).
        case accountBusy
        /// Any other proxy refusal; the detail mirrors the Kotlin raw-status fallback.
        case http(String)
        case invalidResponse, transport

        public var errorDescription: String? {
            switch self {
            case .signInRequired: "Sign in to your Palique account to use Palique API."
            case .missingModel: "Palique API could not resolve the model for this call. Sign in again and retry."
            case .balanceExhausted: "Your Palique token balance is used up. Buy more tokens to keep practising."
            case .unlockRequired: "Palique API needs a one-time unlock purchase before this account can use it."
            case .accountBusy: "Palique API is still handling a previous request. Try again in a moment."
            case .http(let detail): detail
            case .invalidResponse, .transport: "The Palique service couldn’t be reached. Please try again."
            }
        }
    }

    /// v1 contract: `POST /v1/proxy/responses`, always `stream: false`.
    static let path = "/v1/proxy/responses"

    public let origin: URL
    public let bearer: String
    /// Resolves the server-chosen model per purpose (the conversation policy
    /// snapshot). Returning nil fails the call: a client-guessed model would
    /// defeat the server-owned routing.
    public let modelFor: @Sendable (Purpose?) -> String?
    private let session: URLSession

    public init(origin: URL, bearer: String, modelFor: @escaping @Sendable (Purpose?) -> String?) {
        self.origin = origin
        self.bearer = bearer
        self.modelFor = modelFor
        // LLM turns can take minutes on slow providers: the request budget
        // matches the Kotlin engine's 120 s read window.
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 120
        configuration.timeoutIntervalForResource = 180
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        session = URLSession(configuration: configuration)
    }

    public func respond(instructions: String, input: String, schema: String? = nil,
                        search: Bool = false, purpose: Purpose? = nil) async throws -> Result {
        guard !bearer.isEmpty, let url = Self.url(origin: origin) else { throw Failure.signInRequired }
        guard let model = modelFor(purpose), !model.isEmpty else { throw Failure.missingModel }
        let request = Self.request(url: url, bearer: bearer, model: model,
                                   instructions: instructions, input: input, schema: schema, purpose: purpose)
        let data: Data, response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw Failure.transport
        }
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else { throw Failure.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw Self.failure(status: http.statusCode, data: data) }
        return try Self.parse(data)
    }

    /// origin + `/v1/proxy/responses`, keeping the configured scheme and host.
    static func url(origin: URL) -> URL? {
        var components = URLComponents(url: origin, resolvingAgainstBaseURL: false)
        components?.path = path
        return components?.url
    }

    /// The non-streaming chat-completions request: `stream` is always false, a
    /// schema rides as json_object mode with the schema text embedded in the
    /// system prompt, and the purpose header is always present — exactly like
    /// the Kotlin engine.
    static func request(url: URL, bearer: String, model: String, instructions: String,
                        input: String, schema: String?, purpose: Purpose?) -> URLRequest {
        let system: String
        if let schema {
            system = "\(instructions)\n\nRespond with exactly one JSON object conforming to this JSON schema:\n\(schema)"
        } else {
            system = instructions
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer " + bearer, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(purpose?.rawValue ?? Purpose.typedReply.rawValue, forHTTPHeaderField: "x-palique-purpose")
        let messages: [[String: String]] = [["role": "system", "content": system],
                                            ["role": "user", "content": input]]
        var json: [String: Any] = ["model": model, "messages": messages, "stream": false]
        if schema != nil { json["response_format"] = ["type": "json_object"] }
        request.httpBody = try? JSONSerialization.data(withJSONObject: json)
        return request
    }

    static func failure(status: Int, data: Data) -> Failure {
        switch status {
        // A 401 means the captured bearer expired or was revoked; the surface is
        // the same fail-closed sign-in message as an empty bearer.
        case 401: return .signInRequired
        case 402: return .balanceExhausted
        case 403: return .unlockRequired
        case 429: return .accountBusy
        default: break
        }
        let payload = String(data: data, encoding: .utf8) ?? ""
        return .http("api engine HTTP \(status): \(String(payload.prefix(200)))")
    }

    static func parse(_ data: Data) throws -> Result {
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let choices = json["choices"] as? [[String: Any]],
              let message = choices.first?["message"] as? [String: Any],
              let text = message["content"] as? String, !text.isEmpty
        else { throw Failure.invalidResponse }
        // `metering` (rateVersion / nanoUSDSettled) is server bookkeeping; usage
        // mirrors the provider's chat-completions token counts.
        let usage = json["usage"] as? [String: Any] ?? [:]
        return Result(text: text,
                      inputTokens: usage["prompt_tokens"] as? Int ?? 0,
                      outputTokens: usage["completion_tokens"] as? Int ?? 0)
    }
}
