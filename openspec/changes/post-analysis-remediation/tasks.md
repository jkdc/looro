# Tasks: post-analysis-remediation

## Task 1 — Purge committed API key

- [x] 1.1 `.gitignore` covers `apps/android/api-keys.properties`; `api-keys.properties.example` documents the untracked flow (`ZHIPUAI_API_KEY=your-zhipuai-api-key-here`).
- [x] 1.2 `git rm --cached` + `git filter-branch --index-filter` over `main..HEAD` purged the blob from every commit; `refs/original` cleaned, reflog expired, gc pruned.
- [x] 1.3 Verified: no commit touches the path, key prefix absent from all revisions, working-tree file unchanged (same SHA), shown as ignored.
- [x] 1.4 `scripts/check_android_release.py` detects z.ai-style keys and `*.properties` credential files (with tests; values never printed).
- [x] 1.5 Docs: `conversation-engines.md` key section matches reality; `deploy-local.sh` help text points at the example.

## Task 2 — Remove on-device llama.cpp engine, keep whisper STT + TTS

- [x] 2.1 Registry reduced to `{openai, deepseek, glm}`; `LocalLlamaEngineAdapter`, local engine classes, `llama_jni.cpp`, llama.cpp submodule, `ENGINE=local` deploy/model-push handling deleted; whisper targets and module kept.
- [x] 2.2 `app/build.gradle.kts` keeps `CONVERSATION_ENGINE` (valid ids updated) and the `:core:engine-local` dependency; Makefile/deploy script drop llama `.gguf` handling.
- [x] 2.3 Tests updated (`ConversationEngineFactoryTest`, instrumented suite); `grep -rni llama` shows no code references; docs updated with removal note + re-add path; local-LLM docs superseded.

## Task 3 — Voice pipeline fixes (shared by deepseek/glm voice)

- [x] 3.1 30 s turn cap fires: `turnMs` incremented per processed frame (unit test: loud frames past `MAX_TURN_MS` end a turn without manual stop).
- [x] 3.2 TTS echo suppressed: recorder paused around `speak()` with a bounded stale-buffer drain on resume; speaking-depth counter prevents trample; no turn starts from frames buffered during playback.
- [x] 3.3 TTS init failure fails the session loudly and shuts the underlying `TextToSpeech` down (fake-based test).
- [x] 3.4 `onStop(utteranceId, interrupted)` completes interrupted utterances; utterance counter thread-safe (QUEUE_FLUSH interruption test).
- [x] 3.5 Transcript-bearing logs gated behind `BuildConfig.DEBUG`; `renderHistory` parses the real `ConversationHistory.messages()` parts-array shape.
- [x] 3.6 Transport events serialized through a single consumer coroutine; threading contract documented on `VoiceTransport`. Review round fixed: bounded `drainStale()`, speaking-flag trample, pause/release race.

## Task 4 — Engine parity: meanings, lookup, topics, help

- [x] 4.1 Meanings (auto + button) work on `deepseek`/`glm` via the active engine's chat completion; reply parsed into the existing meanings model.
- [x] 4.2 `lookup()`, `currentTopic()`, `help()` gate on "active engine configured" instead of an OpenAI key; missing key fails fast with a clear message; OpenAI behavior unchanged.
- [x] 4.3 Topic responses without `sources` render without the sources UI; the unsourced error remains only for genuine engine failures.
- [x] 4.4 Unit tests for the meanings request/response shape; `:app` suite green.

## Task 5 — CI and tooling

- [x] 5.1 `.github/workflows/android.yml`: `submodules: recursive` on all three checkouts; `:core:engine-local:testDebugUnitTest` merged into the unit-test step.
- [x] 5.2 `.github/dependabot.yml` directory `/server` → `/services/api`.
- [x] 5.3 `capture_android_play.py` resolves the highest installed build-tools aapt2 instead of hardcoding `36.0.0`.
- [x] 5.4 YAML validation + scripts unittest suite green; `bash -n` on changed scripts.

## Task 6 — iOS resilience (reasoning-verified; no Swift toolchain on Linux)

- [x] 6.1 Size cap enforced on write: oldest conversations pruned (with dependent-evidence teardown) until the payload fits; single un-fittable session fails through the existing `tooLarge` path.
- [x] 6.2 Corrupt-store recovery: confirmed destructive "Erase learning data and start fresh" flow (two explicit taps, never auto-delete) deletes the SwiftData store and retries init.
- [x] 6.3 Import validation bounds: topics text 20 000, session title 200, translations values 10 000 characters (existing caps style); topic-search query clamped at its funnel so local data can never exceed the caps.
- [x] 6.4 `session!` force unwrap replaced with a captured updated session; `voiceSeconds` set on `session.started`, not `session.created`; interruption observers removed on teardown. Review round fix: observer tokens excluded from `@Observable` synthesis.

## Task 7 — Backend webhook/proxy + rate-limit alignment, and this record

- [x] 7.1 Hook excludes the Stripe webhook paths (new `STRIPE_WEBHOOK_PATH` / `STRIPE_MINUTES_WEBHOOK_PATH` constants) and `proxyExemptPaths` (`/payment-return`, `/readyz`, `/v1/pricing`, `/v1/minutes/products`) from `trusted_proxy_required`.
- [x] 7.2 Webhook paths exempt from the in-memory limiter; README documents the per-proxy-IP client bucket and the durable-limiter follow-up.
- [x] 7.3 Per-route `bodyLimit: STRIPE_WEBHOOK_BODY_LIMIT` (524_288) on both webhook routes; global 256 KiB unchanged.
- [x] 7.4 `orderStatus` pre-lookup joins accounts and excludes soft-deleted accounts.
- [x] 7.5 Tests: webhooks + public routes reachable without proxy headers, client routes still gated and limited, webhook storm never 429s, body-limit boundary pinned; full suite green without and with PostgreSQL 17.
- [x] 7.6 This change folder finalized (proposal, design, tasks, spec delta, verify report).

## Future work (parked, out of this change)

- Durable per-client rate limiting generalization in `services/api` (replacing the per-proxy-IP in-memory bucket) — README documents the current contract.
- Meanings keyless-failure UX (now mostly moot: sessions cannot start without the engine key — `MuralViewModel.engineKeyReady`): use `error_missing_engine_key` instead of the generic meanings failure for keyless remote engines.
- Remove or annotate the now-dead `error_topic_unsourced` string. (Done 2026-09-20: the string was removed in `cabling-remediation`.)
- aapt2 version sort: filter build-tools directories with `fullmatch(r'\d+(?:\.\d+)*')` so preview/rc dirs cannot outrank stable.
- Conditional inclusion of the native `:core:engine-local` module before merging to `main`: the whisper `.so` currently ships in every build (unused with `ENGINE=openai`), as documented in `apps/android/docs/conversation-engines.md`.
- iOS: nonisolated transport teardown so `deinit` can disconnect a still-active session; clamp-on-load tolerance for pre-existing over-cap titles; reset `confirmingErase` after erase-and-restart.
- Whisper dotprod/i8mm flag re-tuning: measure STT latency after the llama-flag removal and re-scope flags to whisper if warranted.
- Live-device verification of the voice pipeline (mic echo suppression, TTS `onStop`) and a real CI run confirming the 25-minute timeout with the whisper native compile.
- macOS verification of the iOS changes: `swift test` + `xcodebuild` build before merge (REQUIRED — see verify report).
