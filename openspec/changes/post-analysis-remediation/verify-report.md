# Verify Report: post-analysis-remediation

## Verdict

**pass** (with documented boundaries — iOS not compiled, live-device and macOS
verification runs pending; see "Not verified by automation")

The per-task implementer/reviewer reports (and the sibling `progress.md`
ledger) were controller-session artifacts under
`.zuperpowers/sdd/2026-09-17-post-analysis-remediation/` and are **not
preserved in this repository**. The durable evidence is:

1. the commit list in the table below (each hash verifiable with
   `git log --oneline -1 <hash>`),
2. the runnable suites (Android unit tests green — 353 on the final state of
   this change; `scripts/tests` 54; backend 358/358 with PostgreSQL), and
3. this document.

## Evidence

| Task | Commits (8f87596..HEAD) | Evidence | Result |
|---|---|---|---|
| 1 — key purge | `cfb5ff4`, `b7cf3be` (history rewritten under them) | `git log --all --oneline -- apps/android/api-keys.properties` empty; key prefix absent from every revision; working-tree file local, untracked, and gitignored (contents intentionally not verified in-repo); scanner unittest: **Ran 54 tests … OK** | pass |
| 2 — engine removal | `3aad5d0`, `5829d8b` | `grep -rni llama` (excluding build dirs/docs notes) clean; `./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest` → **341 tests, 0 failures**; whisper CMake builds `libmural_whisper.so` (arm64-v8a, x86_64) | pass |
| 3 — voice fixes | `c8661d7`, `be996b1`, `888fb21` | `:app` **tests=345 failures=0** (LocalVoiceTransportTest 14, MuralViewModelTest 8) + `:core:engine-local` tests=5 (new UtteranceSignalRouterTest); `assembleUiTestAndroidTest` successful | pass |
| 4 — engine parity | `8eb2b79` | `:app` **tests=348 failures=0**; new OpenAICompatibleEngine-style meanings request/parse tests; keyless gate + sourceless-topic degradation covered | pass |
| 5 — CI/tooling | `876b789` | all five YAML files parse; assert: every `android.yml` checkout has `submodules: recursive`; **Ran 54 tests … OK** (`scripts/tests`) | pass |
| 6 — iOS resilience | `2c9459b`, `75db829` | reasoning-verified line-by-line (no Swift toolchain on this Linux machine — **nothing compiled**); review round fixed `@Observable` synthesis of observer tokens; macOS `swift test` + `xcodebuild` REQUIRED before merge | pass (boundary) |
| 7 — backend + record | `cae15d4`, docs commit | `npm test` without DB (Node 20.19.4): **356 tests, 83 pass, 0 fail, 273 skipped** (DB-gated suites skip by design); with `TEST_DATABASE_URL` against disposable docker PostgreSQL 17: **358 tests, 358 pass, 0 fail, 0 skipped** (incl. 2 new hook/limiter/bodyLimit tests) | pass |

## Task 7 detail (this task's own changes)

- `services/api/src/app.ts` — hook early-returns for the Stripe webhook paths (new constants; also used by the raw-bytes content-type parser); `proxyExemptPaths` (`/payment-return`, `/readyz`, `/v1/pricing`, `/v1/minutes/products`) skips only trusted-proxy resolution; both webhook routes declare `bodyLimit: STRIPE_WEBHOOK_BODY_LIMIT` (524_288) with the global 256 KiB kept; `orderStatus` pre-lookup joins `accounts` with `a.deleted_at IS NULL`.
- `services/api/src/payments.ts` / `src/stripe-minute-provider.ts` — path constants; body-limit constant now shared by the routes and the verifier's 512 KiB check.
- `services/api/README.md` — rate-limit contract: per-proxy-IP bucket for client routes; webhook exemption rationale (signed, server-to-server, 429 storms cannot delay payment confirmation); public routes reachable without proxy headers.
- `services/api/tests/http.test.ts` — new tests: "webhooks and public routes answer without proxy headers while client routes stay gated and limited" (includes a 150-request webhook storm that never 429s against a filled client bucket) and "the webhook routes admit payloads up to the provider signature ceiling instead of failing transport first" (524_288 passes, +1 byte → 413).
- PostgreSQL run environment: `docker run postgres:17-alpine` (disposable, removed afterwards), `TEST_DATABASE_URL=postgresql://mural_test:…@127.0.0.1:55434/mural_billing_test`.

## Not verified by automation (honest boundary)

- **iOS:** no Swift/Xcode toolchain on the Linux machine — Task 6 changes are reasoning-verified only. macOS `swift test` + `xcodebuild` must run before merge.
- **Live device:** voice-pipeline behavior (mic echo suppression, TTS `onStop`, turn cap under real VAD) not yet exercised on hardware; CI's 25-minute timeout with the whisper native compile awaits a first real runner run.
- **Whisper tuning:** dotprod/i8mm CPU-flag removal may de-tune STT latency; not yet measured.
- **System Node:** the default `node` on this machine is v18.19.1 (below the required >=22.19); the api suite fails at import there (`crypto is not defined` in `jose`). Runs used nvm Node v20.19.4. Pre-existing environmental issue, not caused by this change.
- **z.ai key rotation** remains an out-of-repo action item from Task 1.
