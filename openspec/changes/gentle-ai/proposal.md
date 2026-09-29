# Proposal: gentle-ai

## Intent

Ship five learner-facing features from the 2026-09-18 features report
(`informe-features-2026-09-18.md`, not tracked in this repository): K10 tutor
speech pace, K9 listening-comprehension mode, K3 local progress and streaks,
K12 directed writing practice, and D3 partial transcripts + barge-in + an
adaptive energy VAD on the Android on-device voice path.

## Problem

- The tutor's speaking speed is only implied by the challenge level; learners
  who need slower speech (and beginners on the on-device pipeline, where the
  system TTS speaks fast by default) have no control.
- There is no listening-comprehension exercise: every session is a dialogue
  with optional meaning subtitles, so the learner can lean on subtitles and
  never trains plain listening.
- Learning progress is invisible: sessions, voice minutes, word bars and
  capabilities are all stored locally, but the app surfaces them only as a
  browsable word list and passive capability text.
- Due words (`dueAt` is already projected and injected into the tutor) have no
  practice surface; recall evidence only accumulates when a conversation
  happens to revisit them.
- On the on-device voice path (deepseek/glm engines) nothing is shown while
  the user speaks — text appears only after the turn's whisper pass — the
  assistant cannot be interrupted while speaking, and the fixed RMS threshold
  (`0.012`) misclassifies turns on quiet or noisy rooms.

## Approach

Five sequential phases on the same local-only branch, conventional commits,
one commit group per phase, all existing tests stay green:

1. **K10** — `Preferences.slowSpeech` (optional field, old archives decode) +
   `TeachingPolicy.voice` pace line with checker-covered `paceSlow`/
   `paceNormal` prompts (both platforms); system-TTS speech rate on the local
   Android pipeline; Settings toggles on both apps.
2. **K9** — `Preferences.listeningPractice` + optional `SessionRecord.mode`;
   `TeachingPolicy.listeningStory` prompt replaces the greeting in that mode;
   auto-meanings suppressed for listening sessions (word lookup stays);
   Settings toggles on both apps.
3. **K3** — `LearningEngine.stats` projection (pure, testable: totals, minutes,
   words per bar, day streaks, 14-day histogram, capabilities) + a Progress
   screen reached from Settings on both apps. No schema change.
4. **K12** — `WordPractice` core (normalization + acceptance of `form` or
   `lemma`) and a drill over due words on the Words tab of both apps, with the
   example quote masked as a hint. No schema change; attempt persistence is a
   documented follow-up.
5. **D3** — Android local voice only: `AdaptiveVad` (noise-floor tracking +
   hysteresis, pure Kotlin), periodic partial transcription while a turn is
   open (new `session.input_transcript.partial` event, provisional UI state,
   never stored), and AEC-gated barge-in that stops TTS playback.

## Scope

- **In scope:** the five phases above; `openspec/changes/gentle-ai/` as the
  record; the new user-facing strings (Android EN/ES parity) they require;
  `scripts/check_cross_platform.py` CONSTANTS entries only if a new shared
  numeric constant needs parity enforcement (none planned — D3 constants are
  Android-only).
- **Out of scope (documented follow-ups):** persisting K12 practice attempts
  and feeding them into recall evidence; silero/WebRTC VAD models for the
  local path; audio barge-in without hardware AEC; runtime engine selection
  (D1); extending K9 to typed sessions; iOS localization of the new screens.

## Risks

- iOS changes are reasoning-verified only on this Linux machine; macOS
  `swift test` + `xcodebuild` is REQUIRED before merge (same gate as
  `post-analysis-remediation`).
- Prompt wording changes are constrained by `scripts/check_cross_platform.py`;
  both platforms must change together or the checker fails the build.
- Barge-in can mis-trigger on loud speaker output; it is gated on
  `AcousticEchoCanceler.isAvailable()` and a high sustained-energy threshold,
  and frames captured during playback are never added to a turn buffer, so a
  mis-trigger costs an interrupted utterance, never a phantom turn.
- Partial transcription competes with the final whisper pass for the STT
  mutex; partials are bounded (length-capped snapshot, superseded jobs
  cancelled) so the final pass may wait at most one bounded decode.
- K9 changes nothing about consent or data flows (same conversation pipeline);
  no consent version bump.

## Delivery strategy

`single-pr` — direct conventional commits on
`feature/switchable-conversation-engine` (never pushed), one commit group per
phase, Android unit suites + `check_cross_platform.py` green after each phase.
