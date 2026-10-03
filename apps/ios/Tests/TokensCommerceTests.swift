import XCTest
@testable import LearningCore

/// Pins the Swift pack-commerce core against the shipped backend surface
/// (services/api pack-products.ts) and Android's parsing invariants: strict
/// BigInt-string money, the sandbox unlock notice (never an error), and the
/// checked Stripe checkout URL. Runs under the macOS gate.
final class TokensCommerceTests: XCTestCase {
    private let unlock = ["sku": "palique-unlock", "providerProduct": "unlock_stripe",
                          "currency": "usd", "totalMinor": 699, "environment": "live",
                          "entitlementKind": "byok_unlock", "creditNanoUSD": "0"]
    private let pack = ["sku": "palique-pack-small", "providerProduct": "pack_stripe",
                        "currency": "usd", "totalMinor": 499, "environment": "test",
                        "entitlementKind": "token_pack", "creditNanoUSD": "2160000000"]

    private func product(_ fields: [String: Any]) -> TokenPackProduct? {
        TokenPackProduct.parse(sku: fields["sku"] as? String, providerProduct: fields["providerProduct"] as? String,
                               currency: fields["currency"] as? String, totalMinor: fields["totalMinor"] as? Int,
                               environment: fields["environment"] as? String,
                               entitlementKind: fields["entitlementKind"] as? String,
                               creditNanoUSD: fields["creditNanoUSD"] as? String)
    }

    private func status(_ fields: [String: Any]) throws -> TokenPackStatus {
        try JSONDecoder().decode(TokenPackStatus.self, from: JSONSerialization.data(withJSONObject: fields))
    }

    private func serverStatus(orderID: String, kind: String, state: String,
                              granted: String = "0", reversed: String = "0",
                              recorded: Bool, unlockedAt: String? = nil,
                              outstanding: String = "0", environment: String = "test") -> [String: Any] {
        ["orderID": orderID, "entitlementKind": kind, "environment": environment, "state": state, "grantedNanoUSD": granted,
         "reversedNanoUSD": reversed, "reversalOutstandingNanoUSD": outstanding,
         "fulfillmentRecorded": recorded, "unlockedAt": unlockedAt ?? NSNull()]
    }

    func testCatalogParsesServerConstantUnlockAndPackEntries() throws {
        let parsedUnlock = try XCTUnwrap(product(unlock))
        XCTAssertEqual(parsedUnlock.kind, .byokUnlock)
        XCTAssertTrue(parsedUnlock.isUnlock)
        XCTAssertEqual(parsedUnlock.creditNanoUSD, 0)
        let parsedPack = try XCTUnwrap(product(pack))
        XCTAssertEqual(parsedPack.kind, .tokenPack)
        XCTAssertFalse(parsedPack.isUnlock)
        XCTAssertEqual(parsedPack.creditNanoUSD, 2_160_000_000)
    }

    /// The unlock always echoes credit 0 and the pack a positive credit — any
    /// other combination is a tampered catalog and fails closed (Android's
    /// validPackCredit).
    func testCatalogRejectsDeviatingConstantsAndShapes() {
        var fields = unlock; fields["creditNanoUSD"] = "1"
        XCTAssertNil(product(fields), "unlock with credit must fail closed")
        fields = pack; fields["creditNanoUSD"] = "0"
        XCTAssertNil(product(fields), "pack without credit must fail closed")
        for mutate in [{ (f: inout [String: Any]) in f["entitlementKind"] = "ai_value" },
                       { (f: inout [String: Any]) in f["entitlementKind"] = "unlock" },
                       { (f: inout [String: Any]) in f["sku"] = String(repeating: "a", count: 129) },
                       { (f: inout [String: Any]) in f["sku"] = "-leading-dash" },
                       { (f: inout [String: Any]) in f["currency"] = "USD" },
                       { (f: inout [String: Any]) in f["totalMinor"] = 0 },
                       { (f: inout [String: Any]) in f["totalMinor"] = 100_000_001 },
                       { (f: inout [String: Any]) in f["environment"] = "sandbox" },
                       { (f: inout [String: Any]) in f["creditNanoUSD"] = "2160000000.0" },
                       { (f: inout [String: Any]) in f["creditNanoUSD"] = "10000000000000000" },
                       { (f: inout [String: Any]) in f["creditNanoUSD"] = "-1" }] {
            var candidate = unlock; mutate(&candidate)
            XCTAssertNil(product(candidate))
        }
    }

    /// The documented server test-mode contract: a `purchased` unlock with
    /// `fulfillmentRecorded: false` records state but never unlocks — that is a
    /// sandbox notice, NOT a verification failure, and only for test-environment
    /// orders. Live evidence and credited packs are ADDED; money back (a
    /// refunded live unlock's reversal evidence) is REVERSED; nothing else is
    /// pending.
    func testStatusNoticeMappingMirrorsAndroidIncludingSandboxRule() throws {
        let orderID = "123e4567-e89b-12d3-a456-426614174000"
        func notice(_ fields: [String: Any]) throws -> TokenPackNotice {
            let parsed = try status(fields); try parsed.validate(); return .notice(for: parsed)
        }
        XCTAssertEqual(try notice(serverStatus(orderID: orderID, kind: "token_pack", state: "created", recorded: false)), .pending)
        XCTAssertEqual(try notice(serverStatus(orderID: orderID, kind: "token_pack", state: "pending", recorded: false)), .pending)
        XCTAssertEqual(try notice(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                               granted: "2160000000", recorded: true, environment: "live")), .added)
        XCTAssertEqual(try notice(serverStatus(orderID: orderID, kind: "byok_unlock", state: "purchased",
                                               recorded: true, unlockedAt: "2026-10-02T10:00:00.000Z")), .added)
        // The sandbox rule: a test-environment unlock purchase only.
        XCTAssertEqual(try notice(serverStatus(orderID: orderID, kind: "byok_unlock", state: "purchased",
                                               recorded: false)), .sandboxRecorded)
        // A live unlock purchased with no fulfillment and no refund evidence is
        // NOT the sandbox case; it failed verification (or was bought while
        // already unlocked — either way it is not "recorded for sandbox").
        XCTAssertEqual(try notice(serverStatus(orderID: orderID, kind: "byok_unlock", state: "purchased",
                                               recorded: false, environment: "live")), .verificationFailed)
        // A live unlocked-then-refunded order carries money-returned evidence
        // (699 minor → 6_990_000_000 nano) and words REVERSED, never sandbox.
        let refunded = try status(serverStatus(orderID: orderID, kind: "byok_unlock", state: "purchased",
                                               recorded: false, environment: "live", outstanding: "6990000000"))
        try refunded.validate()
        XCTAssertEqual(refunded.reversalOutstandingNano, 6_990_000_000)
        XCTAssertEqual(TokenPackNotice.notice(for: refunded), .reversed)
        // A pack that never credited is not a sandbox case; it failed verification.
        XCTAssertEqual(try notice(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                               recorded: false, environment: "live")), .verificationFailed)
        // Reversal wins over fulfillment, mirroring Android's applyStatus order.
        XCTAssertEqual(try notice(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                               granted: "2160000000", reversed: "10", recorded: true, environment: "live")), .reversed)
        // A voided unlock refund clears the entitlement server-side (unlockedAt
        // is nulled), so the reversal is visible through state alone.
        XCTAssertEqual(try notice(serverStatus(orderID: orderID, kind: "byok_unlock", state: "voided",
                                               recorded: false, environment: "live")), .reversed)
    }

    /// Regression for the ai_value merge: the server ALWAYS merges non-pack
    /// products into /v1/minutes/products (services/api app.ts), so the catalog
    /// must skip them — and still fail closed on a tampered pack entry.
    func testCatalogParseSkipsNonPackItemsAndFailsClosedOnTamperedPacks() throws {
        let aiItem: [String: Any] = ["sku": "palique-ai-small", "providerProduct": "ai_stripe",
                                     "currency": "usd", "totalMinor": 499, "environment": "live",
                                     "entitlementKind": "ai_value", "estimate": true,
                                     "aiValueNanoUSD": "1000000"]
        let kindless: [String: Any] = ["sku": "legacy-minutes", "minutes": 60]
        let mixed = try JSONSerialization.data(withJSONObject: [
            "available": true, "billingBasis": "actual-ai-usage", "products": [unlock, aiItem, pack, kindless]])
        let packs = try XCTUnwrap(TokenPackProduct.parseCatalog(mixed))
        XCTAssertEqual(packs.map(\.sku), [unlock["sku"] as! String, pack["sku"] as! String])
        // A pack-kind item deviating from the server constants kills the catalog.
        var tampered = pack; tampered["creditNanoUSD"] = "0"
        let tamperedPayload = try JSONSerialization.data(withJSONObject: ["products": [unlock, aiItem, tampered]])
        XCTAssertNil(TokenPackProduct.parseCatalog(tamperedPayload))
        // Structural deviations fail closed as well.
        XCTAssertNil(TokenPackProduct.parseCatalog(Data("{\"unexpected\":true}".utf8)))
        XCTAssertNil(TokenPackProduct.parseCatalog(Data("{\"products\":{\"sku\":\"x\"}}".utf8)))
        let many = try JSONSerialization.data(withJSONObject: ["products": Array(repeating: pack, count: 101)])
        XCTAssertNil(TokenPackProduct.parseCatalog(many))
        let duplicate = try JSONSerialization.data(withJSONObject: ["products": [pack, pack]])
        XCTAssertNil(TokenPackProduct.parseCatalog(duplicate))
    }

    /// Android's four pack-status cross-checks (MinutePurchases.kt): a tampered
    /// status must throw, never reach the notice mapping as a plausible "added".
    func testStatusCrossChecksFailClosedOnTamperedPayloads() throws {
        let orderID = "123e4567-e89b-12d3-a456-426614174000"
        func validated(_ fields: [String: Any]) throws -> TokenPackStatus {
            let parsed = try status(fields); try parsed.validate(); return parsed
        }
        // (a) fulfillmentRecorded must equal (unlock ? unlockedAt != nil : granted > 0):
        // the reviewer's tampered "added" example.
        XCTAssertThrowsError(try validated(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                                        granted: "0", recorded: true)))
        XCTAssertThrowsError(try validated(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                                        granted: "2160000000", recorded: false)))
        XCTAssertThrowsError(try validated(serverStatus(orderID: orderID, kind: "byok_unlock", state: "purchased",
                                                        recorded: true))) // no unlockedAt ⇒ never "recorded"
        // (b) 0 <= reversed <= granted.
        XCTAssertThrowsError(try validated(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                                        granted: "10", reversed: "11", recorded: true, environment: "live")))
        // (c) created/pending carry no credit, no unlock timestamp and no refund evidence.
        XCTAssertThrowsError(try validated(serverStatus(orderID: orderID, kind: "token_pack", state: "pending",
                                                        granted: "5", recorded: false, environment: "live")))
        // Precise (c) violation: otherwise-consistent pending unlock with a timestamp.
        XCTAssertThrowsError(try validated(serverStatus(orderID: orderID, kind: "byok_unlock", state: "pending",
                                                        granted: "0", recorded: true,
                                                        unlockedAt: "2026-10-02T10:00:00.000Z")))
        // Precise (c) violation, refund flavor: a pending unlock already claiming money back.
        XCTAssertThrowsError(try validated(serverStatus(orderID: orderID, kind: "byok_unlock", state: "pending",
                                                        granted: "0", recorded: false, outstanding: "6990000000")))
        // (d) unlockedAt only ever rides the unlock, bounded at 40 characters.
        XCTAssertThrowsError(try validated(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                                        granted: "2160000000", recorded: true, environment: "live",
                                                        unlockedAt: "2026-10-02T10:00:00.000Z")))
        XCTAssertThrowsError(try validated(serverStatus(orderID: orderID, kind: "byok_unlock", state: "purchased",
                                                        recorded: true, unlockedAt: String(repeating: "a", count: 41))))
        // (e) only the unlock carries refund evidence: a pack outstanding stays "0".
        XCTAssertThrowsError(try validated(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                                        granted: "2160000000", recorded: true, environment: "live",
                                                        outstanding: "10")))
        // (f) the echoed environment gates the sandbox notice, so it must be real.
        XCTAssertThrowsError(try validated(serverStatus(orderID: orderID, kind: "byok_unlock", state: "purchased",
                                                        recorded: false, environment: "sandbox")))
        // The clean pending and credited shapes still validate.
        let pending = try validated(serverStatus(orderID: orderID, kind: "token_pack", state: "pending", recorded: false, environment: "live"))
        XCTAssertTrue(pending.isPending)
        let credited = try validated(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                                  granted: "2160000000", recorded: true, environment: "live"))
        XCTAssertEqual(credited.grantedNano, 2_160_000_000)
    }

    func testStatusParsingFailsClosedOnUnexpectedShapes() throws {
        let orderID = "123e4567-e89b-12d3-a456-426614174000"
        // A pack (immediate reversal) never carries an outstanding reversal, and
        // a missing or unknown environment never decodes/validates.
        XCTAssertThrowsError(try status(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                                     granted: "1", recorded: true, environment: "live", outstanding: "1")).validate())
        var noEnvironment = serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                         granted: "1", recorded: true, environment: "live")
        noEnvironment.removeValue(forKey: "environment")
        XCTAssertThrowsError(try status(noEnvironment))
        XCTAssertThrowsError(try status(serverStatus(orderID: orderID, kind: "ai_value", state: "purchased",
                                                     recorded: true, environment: "live")).validate())
        XCTAssertThrowsError(try status(serverStatus(orderID: orderID, kind: "token_pack", state: "refunded",
                                                     recorded: true, environment: "live")).validate())
        XCTAssertThrowsError(try status(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                                     granted: "-1", recorded: true, environment: "live")).validate())
        XCTAssertThrowsError(try status(serverStatus(orderID: orderID, kind: "token_pack", state: "purchased",
                                                     granted: "10000000000000000", recorded: true, environment: "live")).validate())
        XCTAssertThrowsError(try status(serverStatus(orderID: "not-a-uuid", kind: "token_pack", state: "purchased",
                                                     recorded: true, environment: "live")).validate())
        XCTAssertThrowsError(try status(serverStatus(orderID: orderID, kind: "byok_unlock", state: "purchased",
                                                     granted: "0", recorded: false, unlockedAt: "")).validate())
        // The unlock's refund evidence is a legitimate part of the shape now.
        let refunded = try status(serverStatus(orderID: orderID, kind: "byok_unlock", state: "purchased",
                                               granted: "0", recorded: false, environment: "live", outstanding: "6990000000"))
        try refunded.validate()
        XCTAssertEqual(refunded.reversalOutstandingNano, 6_990_000_000)
        let parsed = try status(serverStatus(orderID: orderID.uppercased(), kind: "token_pack", state: "pending", recorded: false, environment: "live"))
        try parsed.validate()
        XCTAssertTrue(parsed.isPending)
        XCTAssertEqual(parsed.kind, .tokenPack)
    }

    func testCheckoutURLAcceptsOnlyStripeHostedCheckoutPaths() {
        XCTAssertEqual(TokenCheckoutURL.checked("https://checkout.stripe.com/c/pay/cs_test_a0B9xY"),
                       URL(string: "https://checkout.stripe.com/c/pay/cs_test_a0B9xY"))
        XCTAssertNotNil(TokenCheckoutURL.checked("https://checkout.stripe.com/c/pay/cs_live_Zz9"))
        // Stripe's own resumption fragments ride the URL verbatim (Android's
        // pinned test accepts one); only a query is rejected.
        let fragment = "https://checkout.stripe.com/c/pay/cs_test_a#fidkdWxOYHwnPyd1blppbHNgWjA0"
        XCTAssertEqual(TokenCheckoutURL.checked(fragment), URL(string: fragment))
        for candidate in [
            "http://checkout.stripe.com/c/pay/cs_test_a", "https://evil.example/c/pay/cs_test_a",
            "https://checkout.stripe.com:443/c/pay/cs_test_a", "https://user:pass@checkout.stripe.com/c/pay/cs_test_a",
            "https://checkout.stripe.com/c/pay/cs_test_a?guided=true", "https://checkout.stripe.com/other/cs_test_a",
            "https://checkout.stripe.com/c/pay/cs_sandbox_a", "checkout.stripe.com/c/pay/cs_test_a",
            "https://checkout.stripe.com/c/pay/cs_test_a b",
            String(repeating: "https://checkout.stripe.com/c/pay/cs_test_", count: 300)
        ] { XCTAssertNil(TokenCheckoutURL.checked(candidate), candidate) }
    }

    func testMoneyFormattingMirrorsAndroidFormatters() {
        XCTAssertEqual(tokenCreditText(2_160_000_000, locale: Locale(identifier: "en_US")), "$2.16")
        XCTAssertEqual(tokenCreditText(0, locale: Locale(identifier: "en_US")), "$0.00")
        XCTAssertNil(tokenCreditText(-1, locale: Locale(identifier: "en_US")))
        XCTAssertEqual(minorUnitsPriceText("usd", 699, locale: Locale(identifier: "en_US")), "$6.99")
        XCTAssertEqual(minorUnitsPriceText("USD", 0, locale: Locale(identifier: "en_US")), "$0.00")
        // Packs are pinned to USD by the server's PACK_CURRENCY constant.
        XCTAssertNil(minorUnitsPriceText("eur", 699, locale: Locale(identifier: "en_US")))
    }

    func testCommerceEndpointsJoinTheAllowlistWithValidatedOrderPaths() throws {
        let configuration = try ManagedAccountConfiguration(apiURL: "https://accounts.example.test",
            googleClientID: "123-example.apps.googleusercontent.com", bundleID: "com.jkdc.app.test",
            registeredURLSchemes: ["com.googleusercontent.apps.123-example"], appleCapabilityEnabled: false)
        let orderID = "123E4567-E89B-12D3-A456-426614174000"
        XCTAssertEqual(try configuration.orderEndpoint(orderID).absoluteString,
                       "https://accounts.example.test/v1/minutes/orders/123e4567-e89b-12d3-a456-426614174000")
        XCTAssertThrowsError(try configuration.orderEndpoint("../wallet"))
        XCTAssertThrowsError(try configuration.orderEndpoint("123e4567e89b12d3a456426614174000"))
        let products = try configuration.endpoint("/v1/minutes/products", query: ["provider": "stripe"])
        XCTAssertEqual(products.absoluteString, "https://accounts.example.test/v1/minutes/products?provider=stripe")
        XCTAssertNotNil(try configuration.endpoint("/v1/minutes/orders"))
        XCTAssertThrowsError(try configuration.endpoint("/v1/proxy/responses"))
    }
}
