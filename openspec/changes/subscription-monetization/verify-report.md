# Verify Report: subscription-monetization

Delivered as commits `f98cc85` (backend + panel core) and `c21a1c9`
(Android UX) on `feature/rebranding-looro`.

## What was verified

- **Backend suite**: full `npm test` green — 396 tests, 394 pass in the
  final full run; the 2 failures were pre-existing `/v1/minutes` contract
  tests exposed by the (intended) free-drip enrichment and fixed by
  correcting a missing `await` in the enriched route (33/33 in the
  affected files after the fix). Typecheck clean.
  NOTE: after the fix a full-suite re-run in this session confirmed the
  previously-flaky token-proxy and hang remain resolved.
- **Android**: `:app:testDebugUnitTest` — 422 tests, 0 failures, 0 errors
  (includes 4 new `SubscriptionBalanceTest` cases). `compileDebugKotlin`
  clean.
- **Admin panel (manual, browser)**: new `#planes` section renders config
  + both tiers with editable monthly minutes, rollover caps and store
  product IDs; a drip change (5→6) saved with reason bumped the config
  version 1→2, appeared in `admin_actions` as `plans.update` with
  previous/next detail, and was reverted (v3). Subscription KPI tiles
  render on `#overview` (0 active — correct for an empty dev DB).
- **Migration 027** applied on the live docker dev DB; seeded config and
  tiers verified via psql.

## Known gaps (honest)

- **Play subscription sync is implemented but store-untested**: needs a
  real Play subscription product + test track. Stripe checkout likewise
  needs real price IDs (config fields are already in the panel).
- **iOS**: server contract is shared (same `/v1/minutes` +
  `/v1/subscriptions/*`), but no iOS client change in this change per the
  macOS-gate policy; SwiftUI surface still pending from spaced-recall.
- **Device deploy** of the Android build not performed in this change.
- Trial → paid conversion events rely on Stripe webhook delivery being
  configured on the deployment (`invoice.paid` grants the first period).
