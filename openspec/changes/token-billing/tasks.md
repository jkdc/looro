# Tasks: token-billing

## Task 1 — Entitlements + rate versions (backend)

- [x] 1.1 Migration: `entitlements`, `rate_versions`; ledger `rate_version`
      column; indexes.
- [x] 1.2 Play + Stripe pack products wired into existing purchase delivery
      paths with reconciliation tests.
- [x] 1.3 Docker suite green.

## Task 2 — Proxy endpoint (backend)

- [x] 2.1 `/v1/proxy/responses` relay: session auth, `unlock_required` 403,
      admission caps, per-account concurrency cap (`PROXY_MAX_CONCURRENT`,
      default 2, `429 account_busy`).
- [x] 2.2 Metering: settle with provider usage × pinned rateVersion;
      402 on zero balance with bounded single-call grace; metering tests.
- [x] 2.3 Streaming pass-through or documented non-streaming v1. (Sanctioned
      alternative shipped: non-streaming v1; `stream: true` → 400
      `streaming_unsupported_v1`; contract in docs/token-billing.md.)
- [x] 2.4 Docker suite green; load phase added to `loadtest.mjs`
      (`proxy-completions` against a stub provider only).

## Task 3 — Apps (both platforms)

- [x] 3.1 `hosted-proxy` engine in the factory (Kotlin + Swift mirror).
- [ ] 3.2 Settings "Palique (tokens)" row, paywall, pack purchase sheet; EN/ES
      strings parity.
- [ ] 3.3 JVM tests + `check_cross_platform.py` + assembleDebug; iOS mirrored
      (macOS gate).

## Task 4 — Operator + record close

- [ ] 4.1 Admin browse exposes entitlements/metering (no new dashboard).
- [x] 4.2 verify-report.md with honest gates (Play products need console
      access; prices need final approval).
