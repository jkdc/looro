# Proposal: speech-rate-control

## Intent

Let the learner configure how fast the app speaks to them. Today the only
control is the binary "Slower speech" toggle, which (a) asks the model to slow
its pace through the prompt and (b) on Android's on-device voice, switches the
system TTS between two fixed rates (1.0 / 0.8). This change replaces the
binary with a four-step speech-rate choice and applies it consistently to the
local synthesizer (session TTS and lesson card reader).

Terminology note: this governs TTS (what speaks). STT/whisper transcription
speed is not a user-facing control.

## Problem

One speed does not fit: beginners need slower tutor audio; advanced learners
find 1.0× sluggish. The prompt-only toggle cannot help the local synthesizer
beyond the hard-coded 0.8, iOS's card reader ignores pace entirely, and there
is no way to go faster than normal.

## Approach

- **`Preferences.speechRate: Double?`** (optional per repo convention; null =
  legacy behaviour, i.e. 0.8 when `slowSpeech` is set, otherwise 1.0). Pinned
  by `check_cross_platform.py` and the shared fixture.
- **Four-step choice in Settings** — Very slow 0.6× / Slow 0.8× / Normal 1.0× /
  Fast 1.2× (Android dropdown row; iOS picker). Legacy `slowSpeech = true`
  keeps working as "Slow" when `speechRate` is unset.
- **Application:** Android `LocalVoiceTransport` (session TTS) and the lesson
  card reader take the effective rate; iOS `LessonReader` multiplies
  `AVSpeechUtteranceDefaultSpeechRate`. The model-pace prompt flag derives
  from the rate (< 1.0 → slow prompt) on both platforms.
- Documented limit: provider-generated voices (iOS realtime/hosted) are not
  locally rate-controlled — the prompt pace line remains their lever.

## Out of scope

Pitch control, per-language rates, whisper transcription speed, server-side
voice speed for hosted sessions.
