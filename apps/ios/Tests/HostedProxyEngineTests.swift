import XCTest
import Foundation
@testable import LearningCore

/// Mirrors the JVM `HostedProxyEngineTest` cases (URL construction, bearer auth,
/// non-streaming body, 402/403/429 wording, response parsing); runs under the
/// macOS gate. No network: the request/parse/failure helpers are pure.
final class HostedProxyEngineTests: XCTestCase {
    private let origin = URL(string: "https://api.example.com/")!
    private let bearer = String(repeating: "a", count: 43)

    private func body(_ request: URLRequest) throws -> [String: Any] {
        let data = try XCTUnwrap(request.httpBody)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testRequestTargetsProxyPathWithBearerAndNonStreamingBody() throws {
        let url = try XCTUnwrap(HostedProxyEngine.url(origin: origin))
        XCTAssertEqual(url.absoluteString, "https://api.example.com/v1/proxy/responses")
        let request = HostedProxyEngine.request(url: url, bearer: bearer,
                                                instructions: "You are a coach", input: "Say hi", schema: nil)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer " + bearer)
        let json = try body(request)
        XCTAssertEqual(json["model"] as? String, HostedProxyEngine.model)
        XCTAssertEqual(json["stream"] as? Bool, false, "v1 is non-streaming by contract")
        let messages = try XCTUnwrap(json["messages"] as? [[String: String]])
        XCTAssertEqual(messages.count, 2)
        XCTAssertEqual(messages[0]["role"], "system")
        XCTAssertEqual(messages[0]["content"], "You are a coach")
        XCTAssertEqual(messages[1]["role"], "user")
        XCTAssertEqual(messages[1]["content"], "Say hi")
        XCTAssertNil(json["response_format"])
    }

    func testSchemaRequestEmbedsJSONModeAndSchemaTextLikeTheKotlinEngine() throws {
        let request = HostedProxyEngine.request(url: try XCTUnwrap(HostedProxyEngine.url(origin: origin)),
                                                bearer: bearer, instructions: "Coach",
                                                input: "input", schema: "{\"type\":\"object\"}")
        let json = try body(request)
        XCTAssertEqual((json["response_format"] as? [String: String])?["type"], "json_object")
        let messages = try XCTUnwrap(json["messages"] as? [[String: String]])
        let system = try XCTUnwrap(messages[0]["content"])
        XCTAssertTrue(system.contains("JSON schema"))
        XCTAssertTrue(system.contains("\"type\":\"object\""))
    }

    func testOriginWithoutTrailingSlashStillResolvesTheProxyPath() {
        let origin = URL(string: "https://api.example.com")!
        XCTAssertEqual(HostedProxyEngine.url(origin: origin)?.absoluteString,
                       "https://api.example.com/v1/proxy/responses")
    }

    func testParseMapsChoicesAndUsageAndToleratesMetering() throws {
        let payload = """
        {"choices":[{"message":{"role":"assistant","content":"¡Hola!"}}],
         "usage":{"prompt_tokens":30,"completion_tokens":6},
         "metering":{"rateVersion":"2026-09-minimax-m","nanoUSDSettled":"90000"}}
        """
        let result = try HostedProxyEngine.parse(Data(payload.utf8))
        XCTAssertEqual(result.text, "¡Hola!")
        XCTAssertEqual(result.inputTokens, 30)
        XCTAssertEqual(result.outputTokens, 6)
    }

    func testParseRejectsUnexpectedPayloads() {
        XCTAssertThrowsError(try HostedProxyEngine.parse(Data("{\"unexpected\":true}".utf8)))
        XCTAssertThrowsError(try HostedProxyEngine.parse(Data("not json".utf8)))
    }

    /// The failure wording must match Android's `OpenAICompatibleEngine.proxyFailure`
    /// and `PROXY_SIGN_IN_MESSAGE` character for character.
    func testProxyErrorMappingIsReadableAndMirrorsAndroid() throws {
        // 401 (expired/revoked bearer) fails closed with the same sign-in surface.
        XCTAssertEqual(HostedProxyEngine.failure(status: 401, data: Data("{}".utf8)), .signInRequired)
        XCTAssertEqual(HostedProxyEngine.failure(status: 402, data: Data("{}".utf8)), .balanceExhausted)
        XCTAssertEqual(try XCTUnwrap(HostedProxyEngine.Failure.balanceExhausted.errorDescription),
                       "Your Palique token balance is used up. Buy more tokens to keep practising.")
        XCTAssertEqual(HostedProxyEngine.failure(status: 403, data: Data("{}".utf8)), .unlockRequired)
        XCTAssertEqual(try XCTUnwrap(HostedProxyEngine.Failure.unlockRequired.errorDescription),
                       "Palique (tokens) needs a one-time unlock purchase before this account can use it.")
        XCTAssertEqual(HostedProxyEngine.failure(status: 429, data: Data("{}".utf8)), .accountBusy)
        XCTAssertEqual(try XCTUnwrap(HostedProxyEngine.Failure.accountBusy.errorDescription),
                       "Palique (tokens) is still handling a previous request. Try again in a moment.")
        XCTAssertEqual(HostedProxyEngine.Failure.signInRequired.errorDescription,
                       "Sign in to your Palique account to use Palique (tokens).")
    }

    func testUnexpectedProxyStatusKeepsTheRawStatusSurface() {
        guard case .http(let detail) = HostedProxyEngine.failure(status: 503, data: Data("{\"error\":{\"code\":\"proxy_unavailable\"}}".utf8)) else {
            return XCTFail("expected .http")
        }
        XCTAssertTrue(detail.contains("503"))
    }

    func testEmptyBearerFailsBeforeAnyNetwork() async {
        let engine = HostedProxyEngine(origin: origin, bearer: "")
        do {
            _ = try await engine.respond(instructions: "i", input: "in")
            XCTFail("expected signInRequired")
        } catch let failure as HostedProxyEngine.Failure {
            XCTAssertEqual(failure, .signInRequired)
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }
}
