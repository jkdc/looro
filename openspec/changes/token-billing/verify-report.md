# Verify report: token-billing (change closed with honest gates)

**Date:** 2026-10-02 · **Branch:** `feature/learning-platform`

## Delivered

- **Backend (F1+F2, closed 2026-09-22, commits `6745333`, `9f74da3`):** migration
  025 (`entitlements` + `rate_versions`), `POST /v1/proxy/responses` with session
  auth, `unlock_required` 403, metering of provider-reported usage at the pinned
  rate version (bounded single-call grace, 402 on zero balance).
- **Packs (1.2, commit `9a8f69e`):** migration 038 — `byok_unlock`/`token_pack`
  ride the shared purchase-order registry with immutable purchase-time quotes
  (`pack_purchase_quotes`, `rate_version` pinned) and `pack_purchase_transactions`;
  set-once unlock delivery linked to its granting order (`entitlements.unlock_order_id`).
- **Proxy endpoint (2.x, commits `5fdaa59`, `396ac17`, `567a264`):** non-streaming
  v1 (`stream: true` → 400 `streaming_unsupported_v1`), per-account concurrency cap
  (`PROXY_MAX_CONCURRENT`, default 2, 429 `account_busy`), live-environment unlock
  gate, load phase in `loadtest.mjs`.
- **Apps (3.1–3.3, commits `15a9bd3`, `b1b3659`, `80f5b96`, `479a39a`, `2a1f403`,
  `1cfc27d`):** `hosted-proxy` engine in both factories (Kotlin + Swift mirror);
  Settings "Palique (tokens)" row, paywall and pack purchase sheet on Android and
  iOS with EN/ES strings parity.
- **Operator surface (4.1, this task):** admin browse (existing Tablas pattern, no
  new dashboard) now covers the `ledger` token-metering rows (`rate_version`
  filterable), `pack_purchase_transactions` (state/provider/grant/reversal), and
  `entitlements.unlock_order_id` (the order that granted the unlock).

## Gates run (all commands executed 2026-10-02)

| Gate | Command | Result |
|---|---|---|
| Backend typecheck | `npx tsc --noEmit` (services/api) | ✅ clean |
| Backend suite | `TEST_DATABASE_URL=postgresql://app:app@127.0.0.1:55434/app_billing_test npm test` | ✅ 418 tests, 413 pass, 5 fail — the 5 are the known environmental failures (accounts, auth, apple-revocation, service-account OAuth, usage-timeseries), identical before this task; the admin-browse file itself is 8/8 incl. the new token-billing browsing test |
| Android unit tests | `./gradlew :app:testDebugUnitTest` (apps/android) | ✅ 547 tests, 0 failures, 0 skipped (74 suite reports) |
| Android build | `./gradlew :app:assembleDebug` | ✅ BUILD SUCCESSFUL, `app-debug.apk` produced |
| Cross-platform parity | `python3 scripts/check_cross_platform.py` | ✅ exit 0 (prompts, constants, archive-fields agree) |
| Backend docker suite (earlier battery, 2026-09-22) | manual API battery + panel browse | ✅ recorded in the previous revision of this file (401/403/200 metering/grace/402 matrix) |

## Gates pending

| Gate | Blocker |
|---|---|
| macOS compile/test gate, incl. the new `TokensCommerceTests` / `HostedProxyEngineTests` | no macOS host in this round; iOS mirror verified by source parity + cross-platform check only |
| Emulator/device e2e of the purchase flows and engine swap, both platforms | needs Play/Stripe test products and a device run |
| Deployed server check | the proxy path has only run against the local stack and stub provider |
| Play Console products | needs console access (Joaquín) |
| Final price approval | product decision (Joaquín) |
| Android instrumented suites + `lintDebug` | not run this round |

## Known gaps (recorded during this plan, accepted for v1)

- **No GET entitlement route for an unlock badge:** Android/iOS learn the unlock
  only from purchase outcomes; a locked account shows an empty-balance chip
  until a purchase answer arrives.
- **Unlock status shape lacks refund evidence:** a live refunded unlock renders
  the sandbox copy.
- **iOS purchase-plumbing v1 deltas:** no persisted order recovery across
  launches; no StoreKit/Play purchase path (checkout URL only); no
  payment-return deep link; the purchase gate reuses the hosted flag; 402/403
  routing only on typed replies (not streaming UI states).
- **Proxy 429 carries no `Retry-After` header.**
- **`compose.yaml` does not forward `PROXY_MAX_CONCURRENT`** — the cap defaults
  to 2 unless the operator adds the env by hand.
- **Multi-instance deployments need a shared-store cap:** the per-account
  concurrency limiter is in-process, so N instances mean N× the cap.

## Verdict

The full change (entitlement → pack purchase → proxy → metering → exhaustion)
works end to end behind the documented v1 limits, is covered by backend
integration tests and Android JVM tests, and the operator can browse
entitlements, metering and pack transactions in the existing panel. The change
is CLOSED; the pending gates above are store/deployment approvals, not code
work, and the known gaps are the agreed v1 cuts.
