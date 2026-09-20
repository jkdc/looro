# Verify Report: cabling-remediation

**Date:** 2026-09-20 · **Branch:** `feature/switchable-conversation-engine`
**Source findings:** `informe-cableado-2026-09-20.md` (local working-analysis
document, not tracked in this repository).

## What shipped

- **T1** `SpeechSink.setLanguage(languageTag)` + `LocalTtsEngine` mapping via
  `Locale.forLanguageTag` with the initialize-time usability rule (previous
  voice kept on failure); `LocalVoiceTransport.connect` feeds the session
  language.
- **T2** Manual-turn switch and silence slider render only when
  `BuildConfig.CONVERSATION_ENGINE != "openai"`.
- **T3** `clearPartial()` emits an empty `session.input_transcript.partial`
  from both discard paths; the ViewModel clears the provisional caption on
  the empty delta. (One iteration: the loop-side `lastPartialText` reset had
  to move into `processTurn` so the retraction could know a partial existed —
  caught by the new test.)
- **T4** Failed opening greeting now calls `onFailure` with a distinct
  message; cancellation still propagates.
- **T5** `GuestLinkResult` accepts `member_deleted`; `linkTo` treats it
  terminally (intent cleared, guest session and minutes kept, no retry) with
  a dedicated `MEMBER_DELETED` status, sheet title/detail and EN/ES strings.
- **T6** `MuralOrb` gained `contentDescription`, wired in `TalkScreen` from
  the previously unused `talk_orb_*_desc` strings; 15 orphan resources purged
  (13 strings + `hosted_not_ready` + `account_settings_detail`) across both
  locales by a script that greps every key first; dead constants
  `VAD_SPEECH_RMS`/`SILENCE_END_MS` removed; `LiveTransport.started/isMuted`
  removed (no callers), `LocalVoiceTransport.started` kept (test-covered).
- **T7/T8** Progress screens state their scope (totals all languages; words
  and milestones current language); the listening-practice copy states that
  the written transcript stays visible.
- **T9** iOS: `--preview` checks DEBUG-gated (`MuralApp`, `RootView`) so a
  release build can never run on an in-memory store; backup footer matches
  `Archive.merging` semantics; stats-test warning fixed;
  `Tests/WordPracticeTests.swift` (7 cases, mirrors the JVM suite).
  **9.1 retracted:** `Signing.xcconfig` already includes
  `ManagedAccounts.xcconfig` and all four configurations reference it — the
  analysis finding was wrong; verified, nothing changed.
- **T10** Docs: root README "What works today" gained the six shipped
  features and now qualifies the sourced-search claim by engine; the
  13-September record is marked historical with current counts appended;
  Android README feature list refreshed; `conversation-minutes.md` no longer
  claims minute reservations are unimplemented; `add-language.md` uses
  post-restructure paths; `conversation-engines.md` documents that voice
  delegation is OpenAI-server-only; `post-analysis-remediation` Future-work
  annotated (keyless-UX moot, dead string removed). The analysis report
  itself was corrected for its three false findings (xcconfig wiring,
  `hosted_sign_in`, `mural_cream`).

## Verification performed

- Android: `:app:testDebugUnitTest` + `:core:engine-local:testDebugUnitTest`
  — **390 tests, 0 failures, 0 skips**, including new tests for T1 (language
  feed + degradation), T3 (retraction), T4 (greeting failure), T5
  (`member_deleted` terminal state).
- Backend re-run for the docs claims: **358/358 pass, 0 skipped** on Node 22
  (official image) + PostgreSQL 17 in Docker against an isolated
  `mural_billing_test` database. Two environment notes: on the host's Node 18
  the same suite fails 4 files with `crypto is not defined` (global WebCrypto
  needs Node ≥ 20 — the Dockerfile already pins 22), and one test reads
  `Languages.kt` via a repo-relative path, so the container must mount the
  repository root, not only `services/api`.
- `scripts/check_cross_platform.py`: green (no prompt changes in this change).
- iOS: reasoning-verified only — **macOS `swift test` + `xcodebuild` REQUIRED
  before merge** (unchanged policy).

## Still open (parked, unchanged)

- Adaptive-VAD tuning, partial latency and barge-in thresholds on physical
  devices; instrumented tests for the new UI (device required).
- Delegation for non-OpenAI engines (documented as a known degradation), the
  legacy `nanoUSD` hosted mode, per-code error mapping for the degraded
  server codes, orphaned backend endpoints, and the iOS dormant account code.
- The stats language-scoping product decision (copy fix shipped; scoping the
  totals per language would need a product call).
- Branch-level human gates from `informe-verificacion-2026-09-18.md` (local
  working-analysis document): z.ai key rotation, first CI run, push/PR
  decision.
