# Verify report: speech-rate-control

**Date:** 2026-09-22 · **Branch:** `feature/rebranding-looro`

## Delivered (commit `d6e940c`)

- `Preferences.speechRate: Double?` on both cores with
  `effectiveSpeechRate()` derivation (explicit choice → legacy slow flag
  0.8 → 1.0); fixture pins 0.8; checker pins the struct.
- Android: `LocalVoiceTransport.speechRate` (session TTS), lesson card reader
  rate, prompt slow-flag derived from the effective rate; Settings
  "Slower speech" toggle replaced by a four-step "Speech rate" choice.
- iOS: `LessonReader` accepts a rate multiplier over
  `AVSpeechUtteranceDefaultSpeechRate`; Settings toggle replaced by a picker;
  prompt flag derived the same way.

## Gates

| Gate | Result |
|---|---|
| Android `:app` + `:core:engine-local` suites | ✅ 423/423 (new `SpeechRateTest`: derivation matrix, round trip, fixture pin; transport test updated to the new API) |
| `check_cross_platform.py` | ✅ exit 0 |
| Emulator spot-check | ✅ deploy → Settings shows "Speech rate — Normal" → choice "Very slow" persists and displays; evidence `e2e-evidence/bateria-2026-09-22/50` |
| iOS suite | ⏳ macOS gate pending |

## Verdict

Implemented as designed and spot-checked on the emulator. The prompt-pace
lever remains the only control for provider-generated voices, as documented in
the design.
