# Verify Report: gentle-ai

**Date:** 2026-09-18 · **Branch:** `feature/switchable-conversation-engine`
**Commits:** one per phase (K10, K9, K3, K12, D3), plus this record.

## What shipped

- **K10 — Slower speech:** `Preferences.slowSpeech` (optional; old archives
  decode), `TeachingPolicy.paceSlow`/`paceNormal` prompt line wired into
  `voice()` on both platforms (checker-enforced wording), system-TTS rate on
  the Android local pipeline (`TTS_SLOW_RATE = 0.8`), Settings toggles on both
  apps. Applies to new sessions only.
- **K9 — Listening practice:** `Preferences.listeningPractice`,
  `SessionRecord.mode = "listening"` (optional field; survives a backup round
  trip), `TeachingPolicy.listeningStory` replaces the greeting on
  `session.started` on both platforms (checker-enforced), `scheduleTranslation`
  suppressed for listening sessions while word lookup stays available,
  Settings toggles with caption (Android EN/ES).
- **K3 — Progress:** `LearningEngine.stats` on both platforms (totals, words
  per bar, active/longest local-day streaks, 14-day voice-minutes histogram,
  capabilities) with UTC-pinned tests on both; Progress screens from Settings
  on both apps. Nothing new is stored.
- **K12 — Writing practice:** `WordPractice` core on both platforms
  (NFC + casefold + surrounding-punctuation normalization, accepts form or
  lemma, diacritics significant; masked example), drill UI on the Words tab of
  both apps (up to 10 due words, miss feedback, summary; Android EN/ES
  strings). Attempts are not persisted (documented follow-up).
- **D3 — On-device voice feedback (Android local path):** `AdaptiveVad`
  (noise-floor tracking + start/continue hysteresis) replaces the fixed RMS
  threshold in auto mode; partial transcription every 2 s of spoken audio
  (1 s minimum, ≤ 8 s snapshot, superseded/cancelled jobs, dedup) emitting
  `session.input_transcript.partial`, mapped by the ViewModel to provisional
  UI state only and cleared on the final delta/end; AEC-gated barge-in that
  keeps the mic live during playback, drops those frames from turn buffering,
  and stops TTS on 0.06 RMS sustained 250 ms.

## Verification performed

- `:app:testDebugUnitTest`: **385 tests, 0 failures, 0 skipped** (includes
  `:core:engine-local`), covering new `TeachingPolicyTest`, `LearningStatsTest`,
  `WordPracticeTest`, `AdaptiveVadTest`, and new `LocalVoiceTransportTest`
  cases (TTS rate, partials, barge-in, no-barge pause path unchanged).
- `scripts/check_cross_platform.py`: green after every phase (new prompts
  `paceSlow`, `paceNormal`, `listeningStory` exist with identical normalized
  wording; new archive fields are optional and mirrored).
- Android instrumented sources compile (`compileUiTestAndroidTestSources`).
- Android string parity: `values/` and `values-es/` both at 250 keys.
- `Mural.xcodeproj` regenerated via `scripts/generate_project.py` for the two
  new Swift files (`LearningProgressView.swift`, `WritingPracticeView.swift`).

## Adjustments made during implementation

- Barge-in: `MicRecorder.pause()` stops capture (that is the echo fix from
  `post-analysis-remediation`), so pausing and barge detection are mutually
  exclusive. With barge-in armed the mic is **not** paused; the listen loop
  drops playback-period frames (never buffering them) and watches for the
  barge energy. Documented in `design.md`.
- The manual-turn test's "no transcript events" assertion was narrowed to the
  final `session.input_transcript.delta`: provisional partials may now stream
  while the beginner's turn is open (they never process the turn).

## REQUIRED before merge (unchanged policy)

1. **macOS gate:** `swift test --package-path apps/ios` and the `xcodebuild`
   simulator build — all iOS changes in this change (Models, TeachingPolicy,
   ConversationCoordinator, LibraryViews, LearningEngine, LearningProgressView,
   WritingPracticeView, LearningStatsTests) are reasoning-verified only; this
   Linux machine has no Swift toolchain.
2. **Live device run of the on-device voice path:** adaptive-VAD thresholds,
   partial latency on low-end hardware, and barge-in false-trigger behavior
   with real loudspeaker volume ( MicRecorder uses `VOICE_RECOGNITION`, so
   hardware AEC may not be active even where `isAvailable()` reports true).
3. The branch-level human gates recorded in `informe-verificacion-2026-09-18.md`
   (a local working-analysis document, not tracked in this repository):
   z.ai key rotation, first CI run, push/PR decision — still apply.

## Parked follow-ups (documented, out of this change)

- Persist K12 practice attempts and feed them into recall evidence (archive
  schema v3).
- Silero/WebRTC VAD for the on-device path; adaptive VAD is the interim.
- Extending K9 listening mode to typed sessions; per-word pronunciation
  tracking (see the features report).
- iOS localization of the new screens (iOS UI remains English-only).
