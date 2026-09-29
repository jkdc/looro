# Verify report: token-billing (F1+F2 backend delivered; F3/F4 pending)

**Date:** 2026-09-22 · **Branch:** `feature/rebranding-looro`

## Delivered (commits `6745333`, `9f74da3`)

- **F1:** migration 025 (`entitlements` + `rate_versions` seeded with the
  MiniMax rate 300,000 / 1,200,000 nano per million tokens); ledger rows carry
  the applied rate version.
- **F2:** `POST /v1/proxy/responses` — session auth, `unlock_required` 403,
  OpenAI-compatible relay through the server key, metering of
  provider-reported usage at the active rate version via `appendEntry`
  (bounded single-call grace: a call costing more than the balance settles at
  the balance, never negative; zero balance refuses before the provider).
  compose passes `PROXY_PROVIDER_URL/KEY/MAX_TOKENS` (disabled by default).
  `entitlements`/`rate_versions` join the admin browse whitelist.

## Gates

| Gate | Result |
|---|---|
| Full backend docker suite | ✅ exit 0, 0 failures (includes 3 new token-proxy integration tests: config validation, metering/settle math with a stub provider, grace floor, 402, estimate refusal) |
| Manual API battery (local stack + stub provider) | ✅ 401 without session · 403 `unlock_required` without entitlement · 200 with `metering {rateVersion, nanoUSDSettled=90000}` and wallet 50,000,000 → 49,910,000 · grace settle at balance floor · 402 `token_balance_exhausted` |
| Panel (browser) | ✅ `entitlements` browsable in Tablas with the battery's unlock row |
| Play/Stripe real product delivery (task 1.2) | ⏳ needs Play Console products + final prices (Joaquín) |
| Streaming (2.3), Apps F3, dashboards F4 | ⏳ pending, per tasks.md |

## Verdict

Backend path (entitlement → proxy → metering → exhaustion) works end to end
and is covered by integration tests plus a manual API/browser battery. The
change stays OPEN for F1.2 delivery adapters, F3 apps and F4 dashboards.
