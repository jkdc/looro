# Design: cabling-remediation

## T1 — TTS language wiring (Android)

`SpeechSink.setLanguage(languageTag: String): Boolean` (default `false` — a
sink without language control reports so). `LocalTtsEngine` maps the two-letter
session code with `Locale.forLanguageTag` and applies the same usability rule
as `initialize` (`LANG_MISSING_DATA`/`LANG_NOT_SUPPORTED` → `false`, engine
left on its previous language). `LocalVoiceTransport.connect` applies the
session language right after the rate; a `false` result is logged and ignored:
a device without, say, a Norwegian system voice must still hold a
conversation. Session codes are the module ids (`en`, `es`, `nb`, `fr`, `de`,
`it`, `pt`, `zh`), all valid BCP-47 primary tags.

## T2 — Gate local-voice controls (Android)

`SettingsScreen` renders the manual-turn switch and the silence slider only
when `BuildConfig.CONVERSATION_ENGINE != "openai"` — same condition
`MuralViewModel.voiceManualTurn` already uses, so the UI and the getter can
never disagree again. "He terminado" stays gated by `voiceManualTurn` (already
correct). Wiring these controls into WebRTC remains the
`voice-turn-settings-themes` proposal's job, not this remediation's.

## T3 — Clear the partial on discarded turns (Android)

The transport owns the truth about turn discard (two sites in `processTurn`:
sub-250 ms trim and empty STT). Both call a new `clearPartial()` that emits
`session.input_transcript.partial` with `delta: ""`. `MuralViewModel` treats
an empty delta as a clear (replacing the blank-rejection guard; the 50 000-char
cap stays). Round trip: partial shown → turn discarded → caption gone.

## T4 — Surface a failed opening greeting (Android)

The opening-greeting catch in `connect` calls `onFailure` with a dedicated
message prefix instead of logging quietly, matching `processTurn`'s behavior.
Cancellation still propagates untouched.

## T5 — `member_deleted` guest link outcome (Android)

`GuestLinkResult` accepts the server's `member_deleted` outcome; the controller
treats it as terminal: the pending link intent is cleared, no retry is
scheduled, and a new notice string tells the learner the account they were
linking no longer exists. The guest keeps their own minutes (server behavior).
Fake-based test pins the parse and the terminal state.

## T6 — Orb accessibility + dead-code purge (Android)

`MuralOrb` gains a `contentDescription: String?` parameter wired from
`TalkScreen` using the existing-but-unused `talk_orb_idle_desc` /
`talk_orb_listening_desc` strings. The remaining 17 orphan keys are deleted
from both locales — each key is grep-verified (zero `.kt`/`.java` references)
by the removal script, which fails loudly if EN and ES sets drift. Dead
constants `VAD_SPEECH_RMS`/`SILENCE_END_MS` and the caller-less `started`/
`isMuted` transport properties are removed only after grep confirms zero
references in main, test, and instrumented sources.

## T7/T8 — Copy accuracy (both apps)

Progress screens gain a footer: totals (conversations, streaks, minutes) span
all languages; words and capabilities are the current language's. The
listening-practice hint states plainly that automatic subtitles are hidden but
the written transcript stays visible (word lookup included). The underlying
"strict listening" variant (hiding the story transcript) stays parked as a
product decision.

## T9 — iOS (reasoning-verified)

- `Config/Signing.xcconfig` gains `#include "ManagedAccounts.xcconfig"`:
  every project configuration already uses `Signing.xcconfig` as its
  `baseConfigurationReference`, so this makes the documented on/off mechanism
  real without touching the generator or the pbxproj. Defaults are unchanged
  (accounts off); verified statically by regenerating nothing and confirming
  the include resolves under `apps/ios/Config/`.
- `#if DEBUG` around the two `--preview` checks (`MuralApp.swift`,
  `RootView.swift`).
- Backup footer copy corrected to describe the real merge semantics
  (idempotent, keeps original IDs, skips known ones).
- `LearningStatsTests`: drop the non-optional `??` (warning).
- New `Tests/WordPracticeTests.swift` mirroring the JVM cases (runs under the
  macOS gate).

## T10 — Documentation pass

- Root README "What works today": add slower speech, listening practice,
  progress screen, writing drill, and the Android on-device voice feedback
  (partials/adaptive VAD/barge-in) with the AEC caveat; qualify the sourced-
  search sentence as OpenAI-engine-specific; align the accounts sentence with
  `docs/android-accounts.md` (implemented, production verification pending).
- Test counts: backend re-run if `docker` + a disposable Postgres are
  available, else static recount clearly labeled; iOS core count refreshed;
  `verification/android-validation.md` marked historical in the README
  sentence that links it.
- `docs/conversation-minutes.md`: hosted voice already reserves and settles
  minutes — reword the stale sentence.
- `docs/add-language.md`: fix the pre-restructure paths to `apps/ios`.
- `apps/android/docs/conversation-engines.md`: note that voice delegation is
  an OpenAI-server capability (documenting the known degradation).
- `openspec/changes/post-analysis-remediation/tasks.md`: annotate the
  Future-work entries that this change resolves (`error_topic_unsourced`) or
  that became moot (meanings keyless UX).

## Verification

Android: `:app:testDebugUnitTest` + `:core:engine-local:testDebugUnitTest`
green, new tests for T1/T3/T4/T5, string-key parity via the purge script,
`check_cross_platform.py` green (no prompt changes). iOS: reasoning-verified
only + regenerated project inspected; macOS gate REQUIRED before merge.
