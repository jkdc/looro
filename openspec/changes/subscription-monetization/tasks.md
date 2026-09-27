# Tasks: subscription-monetization

## Task 1 — Migration and subscriptions core (backend)

- [ ] 1.1 `migrations/027_subscriptions.sql`: `subscription_config`,
      `subscription_plans` (seeded standard/pro + defaults), `subscriptions`,
      `subscription_grants`; `minute_entries` kind + `'subscription'`;
      grants for `app_runtime`.
- [ ] 1.2 `src/subscriptions.ts`: config read/update (versioned, FOR UPDATE),
      plan catalog, effective tier, `grantPeriod` (idempotent),
      `runDripForAccount`, `upsertFromStore`, cancel/sync helpers.
- [ ] 1.3 Routes: `GET /v1/subscriptions/plans`, `GET /v1/subscriptions/mine`;
      `/v1/minutes` response extension (tier, remaining, drip, features);
      drip run on balance read.
- [ ] 1.4 Tests: config versioning/conflict, grant idempotency per period,
      rollover cap math, drip once per month, canceled accounts get no drip
      while an active sub exists, minutes response shape.

## Task 2 — Billing rails

- [ ] 2.1 Stripe: subscription-mode checkout (existing provider), webhook
      handling for checkout/invoice/subscription events → upsert + grants.
- [ ] 2.2 Play: subscription purchase verification + ack via existing
      transport; poll-sync on `GET /v1/subscriptions/mine`.
- [ ] 2.3 Tests: webhook battery (retry idempotency, failed payment →
      past_due, cancel), Play sync with mocked transport.

## Task 3 — Operator panel

- [ ] 3.1 Admin routes: `GET/PUT /v1/admin/plans` (version conflict 409,
      reason required, audited `plans.update`), plans + config in metrics
      snapshot/timeseries (active, trialing, granted vs consumed ms).
- [ ] 3.2 `subscriptions` in the browse whitelist.
- [ ] 3.3 Panel UI: `#planes` section (config + tiers form, version guard),
      economy dashboard tiles + granted/consumed series, nav entry.
- [ ] 3.4 Tests: admin config update contract, metrics queries, browse
      whitelist.

## Task 4 — Android client UX

- [ ] 4.1 Parse the extended `/v1/minutes` payload (tier, remaining,
      drip, features) in the minutes client + view models.
- [ ] 4.2 Remaining-minutes surface on the conversation screens with
      threshold banners (20% / 5 min / last 2 min) from config-driven
      values.
- [ ] 4.3 Exhaustion flow: 402 → graceful end-of-turn message, resumable
      session state preserved, upgrade sheet listing plans
      (`/v1/subscriptions/plans`) launching the existing Play Billing flow.
- [ ] 4.4 Feature flags from capabilities hide tier-gated features with a
      plan CTA.
- [ ] 4.5 JVM tests for parsing/threshold logic; string parity en/es.

## Task 5 — Delivery

- [ ] 5.1 Phase commits (F1 core, F1b rails, F3 panel, F4 Android), full
      backend suite green per phase.
- [ ] 5.2 `verify-report.md` with honest verdicts; device gates listed.
