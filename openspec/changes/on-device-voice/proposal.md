# Proposal: on-device-voice (Phase 6)

## Intent

Give non-OpenAI conversation engines a **voice path**: mic → on-device Whisper
(STT) → the selected conversation engine (LLM) → on-device TTS, replacing the
OpenAI-only WebRTC live session when `mural.conversationEngine != openai`.
This is the last planned piece of the POC port (`local-speaking-poc`) into Mural.

## Problem

- Live voice rides WebRTC to OpenAI Realtime (`LiveTransport` + `LiveSessionProvider`).
- With engines `local`/`deepseek`/`glm` the app currently blocks voice and falls
  back to typed-only conversations: the core "speaking practice" experience is
  unavailable off-OpenAI.

## Approach

**Adapter over the voice-transport seam.** `MuralViewModel` talks to
`LiveTransport` through a narrow surface (callbacks `onEvent` / `onFailure` /
`onLevels`; methods `connect(api, instructions, history, language)`, `send`,
`mute`, `close`, `disconnect`). We:

1. Extract that surface as interface `VoiceTransport`; `LiveTransport` implements it unchanged.
2. Add `LocalVoiceTransport` (app module) that drives an on-device pipeline
   (mic → Whisper STT → `TeachingClient` → TTS) and emits the **same JSON
   events** the ViewModel already consumes (`mural.session.created`,
   `session.started`, `session.input_transcript.delta`,
   `session.output_transcript.delta`, `session.usage.updated`, `session.closed`).
3. `MuralViewModel` picks the transport from `BuildConfig.CONVERSATION_ENGINE`.
   Zero ViewModel/UI logic changes beyond the construction switch.

Turn-taking (v1): continuous listening with energy-based VAD (speech ≥ ~0.3 s,
end-of-turn after ~1.2 s silence, 30 s cap); mic is suppressed while TTS speaks;
mute toggle suspends listening. `send()` appends are honored: instructions /
thinking enrich the next turn's context; commentary is spoken and emitted as
assistant transcript (typed input during a voice session keeps working).

## Scope

- In: whisper.cpp (multilingual base) in `:core:engine-local`, mic recorder,
  TTS engine, `LocalVoiceTransport`, `VoiceTransport` interface, deploy wiring
  for the Whisper model, tests.
- Out: server-side hosted voice, speaker diarization, streaming partial
  transcripts (`correctFragment` refinement), iOS.

## Risks

- VAD quality vs. Realtime server turn detection (documented; tunable constants).
- Whisper model adds ~60 MB push and RAM (~300 MB while loaded).
