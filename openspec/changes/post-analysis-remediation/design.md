# Design: post-analysis-remediation

## Decisions that shaped the work

1. **On-device LLM is discarded; on-device voice is not.** The `local` engine row, `LocalLlamaEngineAdapter`, `core/engine-local/src/main/java/.../local/{LocalLlamaEngine,LlamaRuntime,LocalModelManager}.kt`, `llama_jni.cpp`, the llama.cpp submodule and the `ENGINE=local` deploy path are deleted. `MicRecorder`, `WavReader`, `WhisperStt`, `WhisperRuntime`, `LocalTtsEngine`, `LocalVoiceTransport` stay: `deepseek`/`glm` voice runs STT + TTS on device with the LLM remote. Re-add path: git history holds the implementation.
2. **The key file stays on disk, leaves the repo.** `api-keys.properties` is gitignored and purged from history with `git filter-branch --index-filter` (the blob only ever existed on this branch); an `.example` file documents the flow; `deploy-local.sh` keeps working untracked. The key value is never printed anywhere.
3. **Voice fixes stay inside the existing transport surface.** `LocalVoiceTransport` keeps the `LiveTransport`-shaped callback contract; fixes are: `turnMs += frameMs` so the 30 s cap fires; recorder stopped around TTS plus a bounded stale-buffer drain on resume; a speaking-depth counter so echo-suppression windows cannot trample; loud `onFailure` + `TextToSpeech.shutdown()` when TTS init fails; `onStop(utteranceId, interrupted)` completes the awaiting `CompletableDeferred`; transcript-bearing logs gated behind `BuildConfig.DEBUG`; `renderHistory` reads the parts-array JSON shape `ConversationHistory.messages()` emits; all transport callbacks funnel through a single consumer coroutine so `MuralViewModel.handle()` is effectively serial.
4. **Parity through the active engine, not a second brain.** Meanings/lookup/topics/help route through the selected `TeachingClient` (OpenAI-compatible chat for `deepseek`/`glm`). The gate becomes "active engine configured" instead of "OpenAI key present"; OpenAI behavior is byte-identical. A topic whose engine response carries no `sources` renders without the sources UI instead of erroring.
5. **Backend fail-open only for signed/public routes.** In `services/api/src/app.ts` the `onRequest` hook gains an early return for the two Stripe webhook paths (new constants `STRIPE_WEBHOOK_PATH` in `payments.ts`, `STRIPE_MINUTES_WEBHOOK_PATH` in `stripe-minute-provider.ts`): Stripe calls server-to-server without trusted-proxy headers, and the webhook paths also skip the shared in-memory limiter so a client 429 storm cannot delay payment confirmation. A module-level `proxyExemptPaths` set (`/payment-return`, `/readyz`, `/v1/pricing`, `/v1/minutes/products`) skips only the trusted-proxy resolution; those routes still pass the limiter under the direct peer address. All other client routes keep failing closed with `trusted_proxy_required`.
6. **bodyLimit: per-route raise, global kept.** Both webhook routes declare `bodyLimit: STRIPE_WEBHOOK_BODY_LIMIT` (524_288, the same constant the Stripe verifier checks against), so oversized payloads are judged by the signed-bytes verifier, not by a transport 413. The global 262_144 (256 KiB) stays for every other route — no broad attack-surface increase.
7. **`orderStatus` mirrors its downstream queries.** The entitlement pre-lookup joins `accounts` and filters `a.deleted_at IS NULL`, exactly like `MinutePurchases.status()`/`AIValuePurchases.status()`, so a soft-deleted account's AI-value order can no longer route to `aiPurchases` (where a missing `aiPurchases` would 503 instead of the correct `purchase_not_found`). HTTP-level observation is unreachable (authentication already rejects soft-deleted accounts), so the change is defense-in-depth consistency.
8. **The in-memory limiter is not generalized now.** The README documents that behind Caddy the base-deployment limiter applies per proxy IP to client routes; a durable per-client limiter is recorded as future work.

## File map (backend, this change)

| File | Change |
|---|---|
| `services/api/src/payments.ts` | `STRIPE_WEBHOOK_PATH` constant. |
| `services/api/src/stripe-minute-provider.ts` | `STRIPE_MINUTES_WEBHOOK_PATH`, `STRIPE_WEBHOOK_BODY_LIMIT` (524_288); verifier uses the constant. |
| `services/api/src/app.ts` | Hook: webhook early return (proxy gate + limiter), `proxyExemptPaths` gate, webhook route constants in the content-type parser, per-route `bodyLimit` on both webhook routes, `orderStatus` deleted-account join. |
| `services/api/README.md` | Rate-limit contract: per-proxy-IP bucket for client routes, webhook exemption rationale, public routes reachable without proxy headers. |
| `services/api/tests/http.test.ts` | Non-DB tests: webhooks + public routes answer without proxy headers while client routes stay gated and limited (120/min bucket, webhook storm never 429s); webhook body-limit boundary at 524_288. |

## Verification strategy

- Backend: `npm test` twice — without `TEST_DATABASE_URL` (boundary tests, DB-gated suites skip) and against PostgreSQL 17 in a disposable docker container with `TEST_DATABASE_URL` (full suite).
- Android/iOS/CI: per-task suites (`./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest`, `python3 -m unittest discover -s scripts/tests -t .`); iOS by reasoning only (no Swift toolchain on Linux) with macOS verification required before merge — see `verify-report.md`.
