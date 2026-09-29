# Design: on-device-voice

## Architecture

```
MuralViewModel (unchanged logic)
   └── VoiceTransport (new interface = LiveTransport's public surface)
         ├── LiveTransport          (engine=openai: WebRTC, untouched)
         └── LocalVoiceTransport    (engine!=openai: on-device pipeline)
               ├── MicRecorder          (:core:engine-local, AudioRecord 16k mono)
               ├── VadTurnDetector      (energy VAD: speech>0.3s, silence 1.2s, cap 30s)
               ├── WhisperStt           (whisper.cpp JNI, language hint from session)
               ├── TeachingClient       (the selected conversation engine)
               └── LocalTtsEngine       (Android TTS; mic suppressed while speaking)
```

## Files

### `:core:engine-local`

| File | Purpose |
|---|---|
| `cpp/third_party/whisper.cpp` | submodule @ v1.9.4 |
| `cpp/whisper_jni.cpp` | port of POC whisper JNI + `language` and `translate` params; symbols `Java_chat_mural_engines_local_JniWhisperRuntime_*` |
| `cpp/CMakeLists.txt` | add `whisper` target + link into `mural_llama` |
| `WhisperRuntime.kt` | seam + `JniWhisperRuntime` (loadLibrary mural_llama) |
| `WhisperStt.kt` | model load, `transcribe(samples, language): String`, honest processing ms |
| `MicRecorder.kt` | port of POC recorder (`start`/`stop`) |
| `LocalTtsEngine.kt` | port of POC Android TTS engine (+ `synthesizeToFile` for tests) |
| `WavReader.kt` | port (used by STT instrumented test) |

### `:app`

| File | Purpose |
|---|---|
| `core/voice/VoiceTransport.kt` | interface extracted from LiveTransport's used surface |
| `core/voice/LocalVoiceTransport.kt` | pipeline → ViewModel event vocabulary |
| `network/LiveTransport.kt` | add `: VoiceTransport` (no logic change) |
| `MuralViewModel.kt` | transport construction: `if (CONVERSATION_ENGINE == "openai") LiveTransport else LocalVoiceTransport`; typed variable becomes `VoiceTransport` |

`LocalVoiceTransport` constructor: `(appContext, scope, stt: WhisperStt, engine: TeachingClient, tts: LocalTtsEngine, recorderFactory: () -> MicRecorder)`.

## Event mapping (same JSON vocabulary as WebRTC)

| Pipeline state | Emitted event |
|---|---|
| connect() | `mural.session.created` `{session:{id}}` then `session.started` |
| turn transcribed | `session.input_transcript.delta` `{delta, start_ms, end_ms, event_id}` |
| LLM reply complete | `session.output_transcript.delta` `{delta, start_ms, end_ms, event_id}` |
| every ~15 s | `session.usage.updated` `{usage:{seconds}}` |
| close() | `session.closed` |
| levels | `onLevels(rms, 0.0)` |

`send()`: `session.instructions.append` / `session.thinking.append` → stored,
prepended to the next turn's system context; `session.commentary.append` →
spoken via TTS + emitted as assistant transcript (typed-during-voice parity).
`connect()` ignores the `LiveSessionProvider` argument (no WebRTC).

Turn loop (single coroutine on `Dispatchers.Default`): recorder → RMS VAD
(speech ≥ 300 ms then 1200 ms silence, cap 30 s) → whisper(language hint) →
empty result ⇒ resume listening (no fragments, no LLM call) → engine.respond →
output transcript → TTS speak (mic suppressed while speaking).

Whisper model: multilingual `ggml-base-q5_1.bin` (~60 MB), language hint from
`module.locale`-style code passed via `connect(language)`; test uses `en`.

## Testing

- JVM: `LocalVoiceTransportTest` with fake recorder samples / fake STT / fake
  LLM / fake TTS: turn → user fragment event → assistant event → TTS spoken;
  silence → no events; mute → no turns; commentary append → spoken event;
  usage event after time.
- Instrumented: `WhisperSttInstrumentedTest` — TTS `synthesizeToFile` →
  WavReader → 16 kHz resample → real Whisper transcription (non-empty, keyword);
  plus real-model LLM sanity (already covered by previous suite).
- UI E2E (manual/automated where possible): `ENGINE=local` deploy → orb → speak
  (or typed-during-voice) → spoken reply.

## Phases

1. STT stack in module (submodule, JNI, Kotlin) + TTS/recorder ports + unit tests.
2. `VoiceTransport` extraction + `LocalVoiceTransport` + ViewModel switch + JVM tests.
3. Deploy wiring (whisper model fetch/push) + instrumented STT test + UI E2E.
4. Verify report, commits, Engram persistence.
