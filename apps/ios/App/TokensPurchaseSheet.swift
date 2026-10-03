import SwiftUI
import LearningCore

/// Paywall + pack purchase surface for "Palique (tokens)" — the iOS mirror of
/// Android's TokensPurchaseSheet.kt, with every EN string byte-matched to
/// Android's `tokens_*` values (iOS ships EN only; ES is Android-only). Render
/// and copy discipline: prices, SKUs and availability come from the server
/// catalog, the balance chip is the server's display hint (GET /v1/wallet,
/// memory-only, never a cached balance), tokens are prepaid service credit —
/// never a currency, and there is no unlimited option.
///
/// What rides the existing plumbing: session-authed JSON via
/// `ManagedAccountClient` and the checked Stripe checkout URL opened in the
/// external browser (Android's Stripe channel). The iOS build has no Play
/// Billing and no StoreKit surface, so "Sign in to buy tokens" hands back to
/// the Account screen and status is polled with "Check purchases".
struct TokensPurchaseSheet: View {
    @State private var model: TokensCommerceModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    /// True when the sheet was opened from a 402 proxy block: shows the
    /// exhausted banner first, exactly like Android's error-dialog route.
    var exhaustedBanner = false

    init(coordinator: ConversationCoordinator) {
        _model = State(initialValue: TokensCommerceModel(configuration: ManagedAccountConfiguration.load()))
        self.exhaustedBanner = coordinator.tokensExhaustedBanner
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                HStack {
                    Spacer()
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("tokens-purchase-close")
                }
                AppOrb(active: !model.checking).frame(width: 104, height: 104)
                Text("Palique (tokens)")
                    .font(.system(.largeTitle, design: .rounded, weight: .semibold))
                    .multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
                Text("Unlock once, then prepay small token packs. The hosted model answers with the app’s key; your voice never leaves the device.")
                    .font(.subheadline).foregroundStyle(AppColor.secondary).multilineTextAlignment(.center)
                if !model.showSignIn, let wallet = model.wallet, let nano = Int64(wallet.availableNanoUSD) {
                    Text(nano > 0 ? String(format: "%@ of prepaid tokens available", tokenCreditText(nano) ?? "") : "No prepaid tokens left")
                        .font(.caption).weight(.medium).padding(.horizontal, 16).padding(.vertical, 10)
                        .background(AppColor.peach.opacity(0.7), in: Capsule())
                        .accessibilityIdentifier("tokens-balance-chip")
                }
                if exhaustedBanner {
                    Text("Your token balance ran out. Choose a pack below to keep going.")
                        .font(.subheadline).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                        .padding(16).background(AppColor.peach, in: RoundedRectangle(cornerRadius: 22))
                        .accessibilityIdentifier("tokens-exhausted")
                }
                Text("Tokens are prepaid service credit for the hosted model. They are not currency and hold no cash value. Usage is metered per call — there is no unlimited option.")
                    .font(.footnote).foregroundStyle(AppColor.secondary).multilineTextAlignment(.center)
                    .accessibilityIdentifier("tokens-credit-disclosure")
                VStack(spacing: 12) {
                    if model.packs.isEmpty {
                        Text(model.checking ? "Checking token packs…" : "Token packs aren’t available right now. Please check again later.")
                            .font(.subheadline).foregroundStyle(AppColor.secondary).multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity).padding(24)
                            .background(.white.opacity(0.72), in: RoundedRectangle(cornerRadius: 26))
                            .accessibilityIdentifier("tokens-purchase-empty")
                    } else {
                        ForEach(model.packs, id: \.sku) { pack in
                            card(pack)
                        }
                    }
                }
                if model.showSignIn {
                    Text("Your tokens stay with your account. Your conversations stay on this device.")
                        .font(.subheadline).foregroundStyle(AppColor.secondary).multilineTextAlignment(.center)
                    Button {
                        // No in-app sign-in lives in the sheet: hand back to
                        // the Account screen (Settings → Account).
                        dismiss()
                    } label: {
                        Text("Sign in to buy tokens").frame(maxWidth: .infinity, minHeight: 30)
                    }.buttonStyle(.borderedProminent)
                        .disabled(model.checking)
                        .accessibilityIdentifier("tokens-purchase-sign-in")
                }
                if let status = statusText {
                    HStack(spacing: 10) {
                        if model.checking { ProgressView().controlSize(.small) }
                        Text(status).font(.subheadline).foregroundStyle(AppColor.secondary)
                            .multilineTextAlignment(.leading)
                    }
                    .accessibilityIdentifier("tokens-purchase-status")
                }
                if !model.packs.isEmpty {
                    Text("One-time purchase · No subscription")
                        .font(.caption).foregroundStyle(AppColor.secondary)
                }
                Text("Checkout opens securely in Stripe. After paying, return to Palique and check your purchases.")
                    .font(.footnote).foregroundStyle(AppColor.secondary).multilineTextAlignment(.center)
                Button("Check purchases") {
                    Task { await model.checkStatus() }
                }.buttonStyle(.bordered)
                    .disabled(model.checking)
                    .accessibilityIdentifier("tokens-purchase-refresh")
            }.padding(24).frame(maxWidth: 520).frame(maxWidth: .infinity)
        }
        .background(AppColor.cream).foregroundStyle(AppColor.ink)
        .presentationDetents([.medium, .large])
        .task { await model.refresh() }
    }

    /// The one-line outcome, mirroring Android's statusTextResource: token
    /// wording where minute wording would mislead, shared copy for the rest.
    private var statusText: String? {
        if let notice = model.notice {
            switch notice {
            case .added: "Your tokens are ready. Let’s keep talking."
            case .pending: "Your payment is pending. Your tokens will appear once it is complete."
            case .sandboxRecorded: "Purchase recorded in the sandbox store. It stays a test purchase until the live listing is used."
            case .signInRequired: "Sign in to confirm your purchases and see your tokens."
            case .verificationFailed: "We couldn’t confirm a purchase. Check again, or sign in with the Palique account used to buy it."
            case .reversed: "A purchase was refunded or reversed. Your balance has been updated."
            case .unavailable: "Token packs aren’t available right now. Please check again later."
            }
        } else if model.checking {
            "Checking token packs…"
        } else if model.purchaseOpened {
            "Checkout opens securely in Stripe. After paying, return to Palique and check your purchases."
        } else { nil }
    }

    @ViewBuilder private func card(_ pack: TokenPackProduct) -> some View {
        let price = minorUnitsPriceText(pack.currency, pack.totalMinor)
        let title = pack.isUnlock ? "Unlock Palique (tokens)" : "Token pack"
        let detail = pack.isUnlock ? "One-time purchase. Unlocks the hosted model for this account."
                                   : String(format: "%@ of prepaid service credit", tokenCreditText(pack.creditNanoUSD) ?? "")
        if let price {
            Button {
                model.buy(pack) { openURL($0) }
            } label: {
                VStack(alignment: .leading, spacing: 10) {
                    Text(title).font(.system(.title3, design: .rounded, weight: .semibold))
                    Text(detail).font(.footnote).foregroundStyle(AppColor.secondary)
                    HStack(spacing: 12) {
                        Text(price).font(.headline).foregroundStyle(AppColor.ink)
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(AppColor.secondary)
                    }.padding(.horizontal, 16).padding(.vertical, 9)
                        .background(AppColor.peach.opacity(model.canChoose ? 1 : 0.65), in: Capsule())
                }.padding(20).frame(maxWidth: .infinity, alignment: .leading)
                    .background(.white.opacity(model.canChoose ? 0.88 : 0.6), in: RoundedRectangle(cornerRadius: 26))
            }.buttonStyle(.plain)
                .disabled(!model.canChoose)
                .accessibilityIdentifier("tokens-pack-\(pack.sku)")
                .accessibilityLabel(String(format: "Add %@ for %@", title, price))
        }
    }
}
