import Foundation

/// Mirror of Android's core pack commerce (`TokensCommerce.kt`, the pack fields of
/// `MinutePurchases.kt`/`StripeMinutePurchases.kt`): strict parsing of the pack
/// catalog entries that ride `GET /v1/minutes/products`, the pack order status of
/// `GET /v1/minutes/orders/:id`, Stripe checkout-URL validation, and the notice
/// mapping the paywall sheet displays. No arithmetic of our own: kind, price and
/// credit are validated server constants echoed back verbatim (services/api
/// pack-products.ts — the unlock always carries credit `0`, the token pack its
/// fixed nanoUSD credit). Pinned by `TokensCommerceTests` at the macOS gate.
public enum TokenPackKind: String, Sendable, CaseIterable {
    /// One-time purchase that unlocks the hosted model for the account.
    case byokUnlock = "byok_unlock"
    /// A prepaid nanoUSD service-credit pack for the token-billing proxy.
    case tokenPack = "token_pack"

    public static func parse(_ raw: String) -> TokenPackKind? { TokenPackKind(rawValue: raw) }
}

/// Why the last commerce operation ended, as the sheet words it. The sandbox
/// case is the documented server test-mode contract: a `purchased` unlock with
/// `fulfillmentRecorded: false` records state but never unlocks (the server
/// gates the entitlement on live evidence), so it is a notice, never an error.
public enum TokenPackNotice: Equatable, Sendable {
    case added, pending, reversed
    case sandboxRecorded
    case signInRequired, unavailable, verificationFailed

    public static func notice(for status: TokenPackStatus) -> TokenPackNotice {
        if status.state == "created" || status.state == "pending" { return .pending }
        if status.state == "voided" || status.reversedNano > 0 { return .reversed }
        if status.fulfillmentRecorded { return .added }
        if status.kind == .byokUnlock && status.state == "purchased" { return .sandboxRecorded }
        return .verificationFailed
    }
}

/// A pack catalog entry from `/v1/minutes/products` (the server strips merchant
/// and provider before responding). `creditNanoUSD` arrives as a quoted BigInt
/// string; anything but plain digits fails closed, mirroring Android's
/// `nanoUSDString` gate.
public struct TokenPackProduct: Equatable, Sendable {
    public let sku: String
    public let providerProduct: String
    public let currency: String
    public let totalMinor: Int
    public let environment: String
    public let kind: TokenPackKind
    public let creditNanoUSD: Int64

    static let identifierPattern = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$"
    static let nanoUSDPattern = "^[0-9]{1,16}$"

    /// Strict parse of one catalog item; `nil` on any deviation. Items whose
    /// `entitlementKind` is not a pack kind (today: `ai_value`) are not a pack
    /// surface and are skipped by the caller rather than failing the catalog.
    public static func parse(sku: String?, providerProduct: String?, currency: String?,
                             totalMinor: Int?, environment: String?, entitlementKind: String?,
                             creditNanoUSD: String?) -> TokenPackProduct? {
        guard let sku, let providerProduct, let currency, let totalMinor, let environment,
              let entitlementKind, let creditNanoUSD, let kind = TokenPackKind.parse(entitlementKind),
              sku.count <= 128, sku.range(of: Self.identifierPattern, options: .regularExpression) != nil,
              providerProduct.range(of: Self.identifierPattern, options: .regularExpression) != nil,
              currency.range(of: "^[a-z]{3}$", options: .regularExpression) != nil,
              (1...100_000_000).contains(totalMinor),
              environment == "test" || environment == "live",
              creditNanoUSD.range(of: Self.nanoUSDPattern, options: .regularExpression) != nil,
              let nano = Int64(creditNanoUSD)
        else { return nil }
        // Server constants: unlock echoes credit 0, the pack a positive credit.
        guard kind == .tokenPack ? nano > 0 : nano == 0 else { return nil }
        return TokenPackProduct(sku: sku, providerProduct: providerProduct, currency: currency,
                                totalMinor: totalMinor, environment: environment, kind: kind,
                                creditNanoUSD: nano)
    }

    public var isUnlock: Bool { kind == .byokUnlock }
}

/// Pack order status from `/v1/minutes/orders/:id` (PackPurchaseStatus). Money
/// fields are quoted BigInt strings; a non-zero outstanding reversal is never
/// sent today and fails closed. Same strict shape as `ManagedWallet`.
public struct TokenPackStatus: Decodable, Equatable, Sendable {
    public let orderID: String
    public let entitlementKind: String
    public let state: String
    public let grantedNanoUSD: String
    public let reversedNanoUSD: String
    public let reversalOutstandingNanoUSD: String
    public let fulfillmentRecorded: Bool
    public let unlockedAt: String?

    public init(orderID: String, entitlementKind: String, state: String, grantedNanoUSD: String,
                reversedNanoUSD: String, reversalOutstandingNanoUSD: String,
                fulfillmentRecorded: Bool, unlockedAt: String?) {
        self.orderID = orderID; self.entitlementKind = entitlementKind; self.state = state
        self.grantedNanoUSD = grantedNanoUSD; self.reversedNanoUSD = reversedNanoUSD
        self.reversalOutstandingNanoUSD = reversalOutstandingNanoUSD
        self.fulfillmentRecorded = fulfillmentRecorded; self.unlockedAt = unlockedAt
    }

    public func validate() throws {
        guard let kind = TokenPackKind.parse(entitlementKind),
              orderID.range(of: "^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$",
                            options: .regularExpression) != nil,
              ["created", "pending", "purchased", "voided"].contains(state),
              grantedNanoUSD.range(of: TokenPackProduct.nanoUSDPattern, options: .regularExpression) != nil,
              reversedNanoUSD.range(of: TokenPackProduct.nanoUSDPattern, options: .regularExpression) != nil,
              reversalOutstandingNanoUSD == "0",
              unlockedAt == nil || !unlockedAt!.isEmpty
        else { throw ManagedAccountError.invalidResponse }
        // The unlock is a binary entitlement: it never carries granted credit.
        if kind == .byokUnlock, let nano = Int64(grantedNanoUSD), nano != 0 {
            throw ManagedAccountError.invalidResponse
        }
    }

    public var kind: TokenPackKind? { TokenPackKind.parse(entitlementKind) }
    public var grantedNano: Int64? { Int64(grantedNanoUSD) }
    public var reversedNano: Int64 { Int64(reversedNanoUSD) ?? 0 }
    public var isPending: Bool { state == "created" || state == "pending" }
}

/// A validated Stripe checkout URL, mirror of Android's `StripeCheckoutURL.checked`:
/// only `https://checkout.stripe.com/c/pay/cs_(test|live)_…`, no port, no user
/// info, no query — a tampered or relayed URL can never open.
public enum TokenCheckoutURL {
    public static func checked(_ value: String) -> URL? {
        // Mirror of Android's checks: bounded length, no whitespace or control
        // characters anywhere, and a bare https URL on the checkout host.
        func clean(_ character: Character) -> Bool {
            character.isWhitespace || character.isNewline ||
            character.unicodeScalars.contains { $0.value < 32 || $0.value == 127 }
        }
        guard (1...8192).contains(value.count), !value.contains(where: clean),
              let components = URLComponents(string: value),
              components.scheme == "https", components.host == "checkout.stripe.com",
              components.port == nil, components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.path.range(of: "^/c/pay/cs_(test|live)_[A-Za-z0-9]+$", options: .regularExpression) != nil
        else { return nil }
        return URL(string: value)
    }
}

/// Human text for a nanoUSD credit amount, e.g. `$2.16` — formatting only,
/// mirror of Android's `tokenCreditText`. `nil` for negative amounts.
public func tokenCreditText(_ nanoUSD: Int64, locale: Locale = .current) -> String? {
    guard nanoUSD >= 0 else { return nil }
    let formatter = NumberFormatter()
    formatter.numberStyle = .currency
    formatter.locale = locale
    return formatter.string(from: NSDecimalNumber(decimal: Decimal(nanoUSD) / 1_000_000_000))
}

/// Store-formatted price for a pack card from minor units, mirror of Android's
/// `minorUnitsPriceText`. The server pins pack currency to `usd` (`PACK_CURRENCY`)
/// with exponent 2; any other currency fails closed instead of guessing.
public func minorUnitsPriceText(_ currency: String, _ totalMinor: Int, locale: Locale = .current) -> String? {
    guard totalMinor >= 0, currency.lowercased() == "usd" else { return nil }
    let formatter = NumberFormatter()
    formatter.numberStyle = .currency
    formatter.locale = locale
    formatter.currencyCode = "USD"
    return formatter.string(from: NSDecimalNumber(decimal: Decimal(totalMinor) / 100))
}
