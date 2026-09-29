# Proposal: token-billing

## Intent

Turn the already-chosen billing model into infrastructure: a one-time BYOK
(bring-your-own-key) unlock plus monthly token tiers sold through our own
proxy, so learners can pay with money instead of managing provider API keys,
while the on-device path (own keys, no server cost) remains the default.
Builds on the existing minute economy (wallet, ledger, Play Billing, Stripe)
and the hosted-voice nanoUSD metering. Roadmap item B2 of
`informe-mejoras-2026-09-22.md` (Fase 2), as chosen by Joaquín.

## Problem

The app has a full commerce stack (Play Billing, Stripe, wallets, webhooks)
but only sells hosted minutes, and hosted voice is gated off commercially.
Learners without provider keys have no way to pay; the provider-key UX
(`provider-key-selection`) requires creating accounts at OpenAI/DeepSeek/Zhipu
— a hard funnel wall. The billing decision is made; what is missing is the
metering-to-entitlement path and its price basis (MiniMax cost analysis below).

## Cost basis (MiniMax, researched 2026-09-22)

- MiniMax M-series text: **$0.30 / M input tokens, $1.20 / M output** (standard
  tier); $0.60 / $2.40 on the high-speed tier or >512K contexts; ~$0.39 blended
  via third-party hosts (Novita/Bedrock).
- Token plans: $22 / $55 / $132 per month shared quota across text+speech —
  relevant only for operations, not per-user resale.
- Session estimate (10-minute conversation, tutor reply + assessment JSON):
  ~8K input / ~4K output tokens ≈ **$0.0072 per session (~$0.43/hour)** at
  standard rates, before any volume discount.

Proposed resale (numbers APPROVED by Joaquín before implementation): one-time
BYOK unlock ≈ $6.99; token packs priced so a $4.99 pack covers ≈ 300 sessions
(~4.3× markup ≈ healthy with refunds/fees); monthly tiers later, after pack
sales data. Rate versioning (`rateVersion`) pins the nanoUSD-per-token rate at
purchase time so balance meaning never shifts under a price change.

## Approach

- **F1 — Entitlements (backend):** migrations for `entitlements` (unlock flag,
  source receipt) and reuse of the existing `wallets`/`ledger` nanoUSD balance
  for token packs ("1 token pack = X nanoUSD of proxy credit"). Products
  declared for Play (one-time + consumables) and Stripe checkout, delivered by
  the existing webhook paths (`minute-purchases` pattern).
- **F2 — Proxy (backend):** `/v1/proxy/responses` — an OpenAI-compatible relay
  to the configured provider using the server key, auth via app session,
  admission via the existing trusted-proxy caps, metering nanoUSD per call
  against the wallet with the pinned `rateVersion`, hard stop at zero balance.
  Server keys never reach clients; the endpoint is gated to unlocked accounts.
- **F3 — Apps (both platforms):** Settings gains a "Use Palique tokens"
  option next to provider keys, the unlock paywall, and the pack purchase
  sheet (reuses `MinutePurchaseSheet` patterns). Conversation engine factory
  gains a "hosted-proxy" engine that talks to `/v1/proxy/responses`.
- **F4 — Operator surface:** admin panel shows unlock/balance metrics
  (browse tables already expose the data).

## Risks

- Provider rate limits are shared under the server key: per-account
  concurrency caps in the proxy (same admission machinery).
- Refund/chargeback abuse: unlock binds to the Play/Stripe receipt paths
  already reconciliation-tested.
- Claim discipline: pricing copy must state the balance is a prepaid service
  credit, not a currency; no "unlimited" claims.

## Delivery strategy

F1+F2 backend-only (docker suite + new tests), F3 apps behind the usual
parity gates, F4 last. Requires Joaquín's approval of final prices and a Play
Console listing for the real products before store rollout; local dev uses
test products.
