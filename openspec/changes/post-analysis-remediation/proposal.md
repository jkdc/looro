# Proposal: post-analysis-remediation

## Intent

Remediate the concrete defects found by the 2026-09-17 deep analysis of
`feature/switchable-conversation-engine` (`informe-analisis-2026-09-17.md`),
across Android, iOS, CI, and the backend, and record one product decision: the
on-device llama.cpp conversation engine is discarded while the whisper STT +
TTS voice pipeline is retained for the remote engines.

## Problem

- A real z.ai API key was committed to git history (`apps/android/api-keys.properties`) and the release scanner missed both the key pattern and `.properties` credential files.
- The on-device `local` engine (llama.cpp) is superseded; keeping it costs APK size, build time, and a git submodule for a path the product will not ship.
- The voice pipeline used by `deepseek`/`glm` has confirmed functional bugs: the 30 s turn cap never fires (`turnMs` never incremented), TTS echo is re-captured as a phantom turn, TTS init failure is silently ignored, interrupted utterances hang up to 60 s, transcripts reach release logs, history rendering mismatches the real history JSON shape, and transport events mutate session state from three dispatchers unsynchronized.
- The learning loop (meanings, word lookup, topics, written-session help) early-returns or errors on non-OpenAI engines even when the selected engine is fully capable.
- CI checks out `:core:engine-local` without `submodules: recursive`, omits the module's unit tests, Dependabot points at a non-existent `/server` directory, and `capture_android_play.py` hardcodes one build-tools version.
- iOS persists without enforcing the archive size cap, has no recovery path for a corrupt store, renders unbounded imported Markdown, force-unwraps `session!`, logs phantom voice seconds for aborted connects, and leaks interruption observers.
- Backend: Stripe delivers webhooks without trusted-proxy headers, so with accounts enabled every webhook was 503 `trusted_proxy_required`; webhook routes shared the client in-memory limiter; the 256 KiB global body limit could 413 payloads the Stripe verifier (512 KiB) is defined to judge; and `orderStatus`'s pre-lookup lacked the soft-deleted-account filter its downstream status queries apply.

## Approach

Seven sequential tasks on the same local-only branch, one implementer + one
reviewer each, conventional commits, all existing tests stay green:

1. Purge the committed key (gitignore + history rewrite + scanner hardening).
2. Remove the on-device llama.cpp engine; keep whisper STT, system TTS, `MicRecorder`, `WavReader`, `WhisperStt`, `LocalVoiceTransport`.
3. Fix the voice pipeline bugs in `LocalVoiceTransport`/`MicRecorder`/`LocalTtsEngine` and serialize transport event handling.
4. Restore meanings/lookup/topics/help parity for `deepseek`/`glm` through the active engine.
5. Fix CI and tooling (submodules, engine-local unit tests, Dependabot path, aapt2 resolution).
6. iOS resilience: cap-on-write, corrupt-store recovery, import bounds, teardown hygiene (reasoning-verified; no Swift toolchain on the Linux machine).
7. Backend webhook/proxy + rate-limit alignment, then this record.

## Scope

- **In scope:** the seven tasks above; `openspec/changes/post-analysis-remediation/` as the single record; docs touched only where claims changed.
- **Out of scope (documented follow-ups):** durable per-client rate limiting, meanings keyless-failure UX polish, `error_topic_unsourced` cleanup, aapt2 preview-version sorting, iOS nonisolated transport teardown, iOS clamp-on-load for pre-existing over-cap titles, whisper re-tuning measurements, live-device and macOS verification runs (see `tasks.md` Future work).

## Risks

- The history rewrite invalidates clones made before 2026-09-17; the branch is local-only and never pushed (authorized), and the exposed key must still be rotated at z.ai.
- iOS changes compile-verified only by reading; macOS `swift test` + `xcodebuild` is REQUIRED before merge.
- Exempting webhooks from the shared in-memory limiter moves abuse control for those paths entirely to Stripe signature verification (accepted: server-to-server, signed, retry-safe).

## Delivery strategy

`single-pr` — direct conventional commits on `feature/switchable-conversation-engine` (never pushed), one commit group per task.
