# Proposal: subscription-monetization

## Intent

Add a configurable subscription business to the app: monthly plan tiers
denominated in conversation minutes, a configurable free drip for unpaid
users, 7-day trials, store billing through the existing Stripe and Google
Play rails, and client-side limit UX (visible remaining minutes, threshold
warnings, graceful exhaustion handling, upsell). All commercial parameters
(monthly minutes, rollover caps, drip, features per tier) are operator-
editable data, versioned and audited — never app code.

## Problem

The app has a working minute economy (welcome grant, gift campaigns,
per-minute purchases on Stripe and Play, reserve→settle hosted voice
metering) but no recurring revenue: nothing to sell a heavy user beyond
top-ups, no trials, no tiers, and no way to change commercial parameters
without a server deploy. The operator decided the commercial shape:
plan tiers whose numbers must be trivially tunable, an optional free
monthly drip, identical behavior on iOS, and client-side limit control
that respects the server as the sole enforcement authority.

## Approach

- **Plans are data.** Migration `027_subscriptions.sql` adds
  `subscription_plans` (singleton-style versioned config: tier definitions
  with `monthly_ms`, `rollover_cap_ms`, free drip parameters, `features`
  jsonb, store product IDs) and `subscriptions` (per-account state: tier,
  status `trialing|active|past_due|canceled`, store, period boundaries,
  cancel-at-period flag). New `minute_entries` kind `subscription`.
- **Grants, not roles.** A renewal/period runner grants the tier's
  `monthly_ms` into the existing minute wallet, idempotent per
  `(subscription, period)`, capped by the tier's rollover cap (existing
  wallet math unchanged). The free drip uses the same path for accounts
  with no active subscription. Consumption enforcement stays exactly as
  today: hosted voice reserve→settle with a zero floor.
- **Billing rails reuse existing providers.** Stripe: Checkout in
  `subscription` mode wired to the existing webhook path with subscription
  lifecycle events. Play: subscription purchase acknowledged and synced via
  the existing Google Play transport (polling sync on app open; RTDN later).
  Both stores feed the same `subscriptions` rows and wallet; no store
  SDK logic in the client beyond launching the purchase.
- **Client contract:** `GET /v1/minutes` response extended with the
  effective tier, remaining milliseconds, drip info and per-tier features;
  `GET /v1/subscriptions/plans` exposes the public plan catalog. 402
  exhaustion keeps its current error code so old clients degrade safely.
- **Client UX (Android):** visible remaining-minutes surface, threshold
  banners (20% / 5 min / last 2 minutes), graceful end-of-session on
  exhaustion with a purchase/upgrade sheet reusing the existing Play
  Billing integration. Features not in the tier are hidden via the
  capabilities payload.
- **Operator panel:** new "Planes" section (edit the versioned plan config
  with conflict detection, reason-required, audited like welcome policy),
  `subscriptions` in the browse whitelist, and subscription KPIs
  (active/trialing counts, granted vs consumed minutes timeseries) on the
  economy dashboard.

## Scope

- Backend: migration, `src/subscriptions.ts`, plan-config admin routes,
  Stripe/Play subscription wiring, `/v1/minutes` + plans endpoints, metrics.
- Panel: plans section, browse table, dashboard additions.
- Android: capabilities consumption, remaining-minutes UX, exhaustion flow,
  subscription purchase launch (Play). JVM tests; device deploy is a
  separate gate as usual.
- iOS: same server contract; no iOS client code in this change (macOS gate
  pending per repo policy) — behavior parity is guaranteed because limits
  and features come from the server.
- Explicitly out of scope: RTDN push for Play (polling first), coupons and
  regional price discrimination, proration beyond Stripe defaults.

## Risks

- Store rejections / price parity across Play and Apple: mitigated by
  keeping store product IDs in plan config and never hardcoding prices.
- Double granting on webhook retries: all grants idempotent by period.
- Cost overrun from a generous free drip: drip values are data, capped by
  an audited lifetime budget like the welcome policy.

## Delivery strategy

Phased conventional commits: F1 backend core (migration, subscriptions
module, endpoints, tests), F1b billing rails, F3 panel, F4 Android.
Full backend suite green per phase; Android via JVM tests; device gates
deferred to the standard deploy checklist.
