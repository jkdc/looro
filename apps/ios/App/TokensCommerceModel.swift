import Foundation
import Observation
import LearningCore

/// Memory-only state for the "Palique (tokens)" row and paywall sheet — the iOS
/// mirror of Android's token-wallet hint (`MinutePurchaseViewModel.tokenWallet`)
/// and Stripe pack controller, restricted to the existing iOS plumbing
/// (session-authed JSON via ManagedAccountClient + an external checkout URL).
/// Nothing here is persisted: no offline balance caching, no attempt store, no
/// checkout URLs on disk — every value dies with this object, and a slow read
/// can never surface a previous account's wallet (stale-guarded per operation).
@MainActor @Observable final class TokensCommerceModel {
    @ObservationIgnored private let client: ManagedAccountClient?
    @ObservationIgnored private let configuration: ManagedAccountConfiguration?
    @ObservationIgnored private var lastOrderID: String?

    /// Server display hint from GET /v1/wallet. Display only; admission is
    /// re-checked server-side under the account lock on every proxied call.
    private(set) var wallet: ManagedWallet?
    private(set) var packs: [TokenPackProduct] = []
    private(set) var busy = false
    private(set) var buying = false
    private(set) var checkingStatus = false
    private(set) var purchaseOpened = false
    private(set) var purchasePending = false
    private(set) var signedIn = false
    private(set) var notice: TokenPackNotice?
    /// True when the catalog route answered with no pack items (or failed).
    private(set) var unavailable = false

    init(configuration: ManagedAccountConfiguration?) {
        self.configuration = configuration
        client = configuration.map(ManagedAccountClient.init)
    }

    var showSignIn: Bool { !signedIn || notice == .signInRequired }
    var canChoose: Bool { !showSignIn && !packs.isEmpty && !unavailable && !busy && !buying && !checkingStatus }
    var checking: Bool { busy || buying || checkingStatus }

    private func currentSession() -> ManagedAccountSession? {
        guard let configuration else { return nil }
        return try? ManagedAccountKeychain(scope: configuration.storageScope).load()
    }

    /// A completion may only be applied while the same account session is still
    /// in the Keychain (mirror of Android's requireCurrent / stale-wallet guard).
    private func stillCurrent(_ session: ManagedAccountSession) -> Bool {
        currentSession()?.accountID == session.accountID
    }

    private func authLoss(_ error: Error) -> Bool {
        error as? ManagedAccountError == .server("sign_in_required")
    }

    /// Wallet hint + catalog, refetched on every appear/refresh of the surface.
    /// A failed read leaves the wallet nil (fail closed) — never a stale hint.
    func refresh() async {
        guard let client, let session = currentSession() else {
            signedIn = false; wallet = nil; return
        }
        signedIn = true; busy = true; defer { busy = false }
        wallet = try? await client.wallet(session: session)
        guard stillCurrent(session) else { return }
        do {
            let catalog = try await client.packCatalog(session: session)
            guard stillCurrent(session) else { return }
            packs = catalog
            unavailable = catalog.isEmpty
        } catch {
            guard stillCurrent(session) else { return }
            if authLoss(error) { signedIn = false; wallet = nil }
            packs = []; unavailable = true
        }
    }

    /// Creates the pack order and hands the checked Stripe checkout URL to the
    /// caller (the sheet opens it in the external browser, as on Android's
    /// Stripe channel). The idempotency key and order id stay memory-only.
    func buy(_ product: TokenPackProduct, open: @escaping (URL) -> Void) {
        guard !buying, canChoose, let client, let session = currentSession() else { return }
        buying = true; notice = nil
        Task { [weak self] in
            guard let self else { return }
            defer { self.buying = false }
            do {
                let order = try await client.createPackOrder(session: session, sku: product.sku,
                                                             expecting: product,
                                                             idempotencyKey: UUID().uuidString)
                guard stillCurrent(session) else { return }
                lastOrderID = order.orderID
                purchaseOpened = true
                open(order.checkout)
            } catch {
                guard stillCurrent(session) else { return }
                if authLoss(error) { signedIn = false }
                notice = authLoss(error) ? .signInRequired : .unavailable
            }
        }
    }

    /// Polls the last order this surface created (memory-only, so never across
    /// launches): pending payments report PENDING, a credited pack or live
    /// unlock ADDED, a reversal REVERSED, and the documented sandbox contract —
    /// `purchased` + `fulfillmentRecorded:false` on the unlock — is a notice,
    /// never an error. ADDED refreshes the wallet hint.
    func checkStatus() async {
        guard let client, let orderID = lastOrderID, let session = currentSession() else { return }
        checkingStatus = true; defer { checkingStatus = false }
        do {
            let status = try await client.packStatus(session: session, orderID: orderID)
            guard stillCurrent(session) else { return }
            let outcome = TokenPackNotice.notice(for: status)
            purchasePending = status.isPending
            notice = outcome
            if outcome == .added { wallet = try? await client.wallet(session: session) }
        } catch {
            guard stillCurrent(session) else { return }
            if authLoss(error) { signedIn = false }
            notice = authLoss(error) ? .signInRequired : .unavailable
        }
    }
}
