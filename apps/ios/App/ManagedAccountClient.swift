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

/// No redirect can carry an account token, provider code or verifier to another destination.
final class ManagedAccountHTTP: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
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

struct ManagedAccountClient {
    let configuration: ManagedAccountConfiguration
    let http: ManagedAccountHTTP
    init(configuration: ManagedAccountConfiguration) { self.configuration = configuration; http = ManagedAccountHTTP() }

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
                            session: ManagedAccountSession? = nil) async throws -> Data {
        try await sendData(try configuration.endpoint(path, query: query ?? [:]), method: method, session: session)
    }

    private func sendData(_ url: URL, method: String, body: [String: String]? = nil,
                          session: ManagedAccountSession? = nil, idempotencyKey: String? = nil) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = method; request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        if let idempotencyKey { request.setValue(idempotencyKey, forHTTPHeaderField: "Idempotency-Key") }
        if let session {
            guard session.isUsable(scope: configuration.storageScope) else { throw ManagedAccountError.server("sign_in_required") }
            request.setValue("Bearer " + session.accessToken, forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await http.send(request)
        guard (200...299).contains(response.statusCode) else {
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

struct ManagedAccountKeychain {
    private let scope: String
    init(scope: String) { self.scope = scope }
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.jkdc.app.managed-account",
         kSecAttrAccount as String: Data(SHA256.hash(data: Data(scope.utf8))).base64EncodedString(),
         kSecAttrSynchronizable as String: false]
    }
    func load() throws -> ManagedAccountSession? {
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
    }
    func remove() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw ManagedAccountError.secureStorage }
    }
}
