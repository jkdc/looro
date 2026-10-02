import Foundation

/// Mirror of Android's `hosted-proxy` engine (the `OpenAICompatibleEngine` aimed
/// at the backend): a non-streaming OpenAI-compatible chat completion relayed by
/// the Palique backend (`POST /v1/proxy/responses`) with the app session bearer,
/// paid from the account's token wallet server-side. Voice STT/TTS stays on
/// device; only the LLM call is proxied. The user-facing failure wording matches
/// the Kotlin engine exactly (`OpenAICompatibleEngine.proxyFailure` /
/// `PROXY_SIGN_IN_MESSAGE`). Pinned by `HostedProxyEngineTests` at the macOS gate.
public struct HostedProxyEngine: Sendable {
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
        /// 402 `token_balance_exhausted`.
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
            case .signInRequired: "Sign in to your Palique account to use Palique (tokens)."
            case .balanceExhausted: "Your Palique token balance is used up. Buy more tokens to keep practising."
            case .unlockRequired: "Palique (tokens) needs a one-time unlock purchase before this account can use it."
            case .accountBusy: "Palique (tokens) is still handling a previous request. Try again in a moment."
            case .http(let detail): detail
            case .invalidResponse, .transport: "The Palique token service couldn’t be reached. Please try again."
            }
        }
    }

    /// Must match the provider behind the operator's `PROXY_PROVIDER_URL`; the
    /// seeded rate version `2026-09-minimax-m` pins MiniMax M as the launch
    /// provider. Same constant as Kotlin's `RemoteEngineConfig.HOSTED_PROXY`.
    public static let model = "MiniMax-M2"
    /// v1 contract: `POST /v1/proxy/responses`, always `stream: false`.
    static let path = "/v1/proxy/responses"

    public let origin: URL
    public let bearer: String
    private let session: URLSession

    /// - Parameters:
    ///   - origin: the managed API origin (the same `ManagedAccountConfiguration.origin`
    ///     every account route uses — one session store, one origin).
    ///   - bearer: the app session access token; callers re-read it from the
    ///     account session store when it is (re)issued, never a second token store.
    public init(origin: URL, bearer: String) {
        self.origin = origin
        self.bearer = bearer
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
                        search: Bool = false) async throws -> Result {
        // An empty bearer means no usable app session: fail before any network
        // traffic, mirroring the Kotlin engine's credential check.
        guard !bearer.isEmpty, let url = Self.url(origin: origin) else { throw Failure.signInRequired }
        let request = Self.request(url: url, bearer: bearer, instructions: instructions, input: input, schema: schema)
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

    /// The non-streaming chat-completions request: `stream` is always false, and
    /// a schema rides as json_object mode with the schema text embedded in the
    /// system prompt — exactly like the Kotlin engine.
    static func request(url: URL, bearer: String, instructions: String, input: String, schema: String?) -> URLRequest {
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
        let messages: [[String: String]] = [["role": "system", "content": system],
                                            ["role": "user", "content": input]]
        var json: [String: Any] = ["model": Self.model, "messages": messages, "stream": false]
        if schema != nil { json["response_format"] = ["type": "json_object"] }
        request.httpBody = try? JSONSerialization.data(withJSONObject: json)
        return request
    }

    static func failure(status: Int, data: Data) -> Failure {
        switch status {
        case 402: return .balanceExhausted
        case 403: return .unlockRequired
        case 429: return .accountBusy
        default: break
        }
        let payload = String(data: data, encoding: .utf8) ?? ""
        return .http("hosted-proxy engine HTTP \(status): \(String(payload.prefix(200)))")
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
