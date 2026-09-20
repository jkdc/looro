# Proposal: cabling-remediation

## Intent

Remediate the actionable findings of the 2026-09-20 wiring/completeness
analysis (`informe-cableado-2026-09-20.md`, a local working-analysis document
not tracked in this repository): features that are incomplete, mis-wired, or
dead across both apps and the user-facing documentation.

## Problem

- The on-device TTS speaks every language with a hard-coded `Locale.US`
  voice (`LocalTtsEngine.setLanguage` is never fed the session language).
- The manual-turn switch and silence slider render in the default `openai`
  build where they are permanently inert (and "He terminado" can never appear).
- A discarded local-voice turn (noise blip / empty STT) never clears the
  provisional partial transcript, which then lingers for the whole session.
- A failed opening greeting is swallowed ("degrades quietly"), leaving an
  "active" session with a silent assistant and no user feedback.
- The guest→member transfer outcome `member_deleted` (server-side, durable)
  is not in the Android client's accepted set, wedging that guest in
  "LINKING" forever.
- The Talk orb has no `contentDescription` although the accessibility strings
  exist; 17 other string keys, two constants, and two transport properties
  have zero callers.
- iOS cannot ever enable managed accounts: `ManagedAccounts.xcconfig` is not
  referenced by the generated Xcode project, so the on/off mechanism itself
  is broken. Two `--preview` checks are not DEBUG-gated (a release build
  launched with the flag runs on an in-memory store), the backup footer
  mis-describes import behavior, and the new `WordPractice` core has no iOS
  tests.
- The documentation layer is stale: the README "What works today" and the
  Android README predate six shipped features; four test-count claims are
  mutually inconsistent and all false; `conversation-minutes.md` describes
  minute reservations as future work that shipped; `add-language.md` gives
  pre-restructure commands; three docs disagree on the hosted/accounts
  posture; and the root README promises sourced web search unconditionally.
- The Progress screens mix all-language totals with current-language word
  stats under copy that promises per-language progress; the listening-mode
  caption does not say the transcript stays visible.

## Approach

Ten sequential tasks on the same local-only branch, conventional commits,
Android changes verified by the existing JVM suites plus new tests:

1. TTS language wiring: `SpeechSink.setLanguage` + session-language feed.
2. Gate the manual-turn/silence controls to non-`openai` builds.
3. Clear the provisional partial when a turn is discarded.
4. Surface a failed opening greeting as a session failure.
5. Handle `member_deleted` in the guest link flow (terminal, with notice).
6. TalkBack label for the orb from the existing strings; purge dead
   strings/constants/properties (verified by grep before each removal).
7. Scope-caption the Progress screens (totals vs current-language words).
8. Make the listening-practice copy accurate (subtitles hidden, transcript
   visible).
9. iOS (reasoning-verified; macOS gate REQUIRED): wire
   `ManagedAccounts.xcconfig` into the generated project, DEBUG-gate the
   `--preview` checks, fix the backup footer, fix the stats-test warning,
   add `WordPracticeTests`.
10. Documentation pass: feature lists, test counts (recounted statically,
    backend suite re-run if the Docker Postgres is available),
    `conversation-minutes.md`, `add-language.md`, hosted/accounts posture,
    engine-conditional search caveat, and the stale Future-work list.

## Scope

- **In scope:** the ten tasks above and this record.
- **Out of scope (parked, documented):** audio barge-in thresholds and
  adaptive-VAD tuning without a physical device; instrumented tests for the
  new UI (device required); implementing delegation for non-OpenAI engines
  (documented as a known degradation instead); hosted `nanoUSD` legacy mode
  removal; per-code error mapping for the degraded server codes; orphaned
  backend endpoints; the stats language-scoping product decision (copy fix
  only); removing iOS dormant account code.

## Risks

- iOS changes compile-verified only by reading; macOS `swift test` +
  `xcodebuild` is REQUIRED before merge (unchanged policy).
- Removing dead strings touches both locales; every removal is grep-verified
  and the EN/ES key sets must stay identical.
- `TtsEngine.setLanguage` failure degrades to the previous voice (logged)
  rather than failing the session: a missing Norwegian system voice must not
  block conversation, but this is only verifiable on a device.

## Delivery strategy

`single-pr` — direct conventional commits on
`feature/switchable-conversation-engine` (never pushed), one commit group per
task or task cluster, full Android suites + `check_cross_platform.py` green
before the record.
