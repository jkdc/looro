# Tasks: on-device-voice

## Phase 1 — STT stack in `:core:engine-local`

- [x] 1.1 whisper.cpp submodule @ v1.9.4 under `src/main/cpp/third_party/whisper.cpp`; CMake adds whisper + JNI symbols for `JniWhisperRuntime`.
- [x] 1.2 Kotlin: `WhisperRuntime` seam + `JniWhisperRuntime` + `WhisperStt` (load/transcribe with language hint).
- [x] 1.3 Port `MicRecorder`, `LocalTtsEngine`, `WavReader` from the POC.
- [x] 1.4 Module unit tests: WhisperStt over fake runtime (params, empty result), recorder trim logic.

## Phase 2 — Transport seam + LocalVoiceTransport

- [x] 2.1 Extract `VoiceTransport` interface; `LiveTransport : VoiceTransport` (no behavior change).
- [x] 2.2 `LocalVoiceTransport` pipeline (VAD turns, event vocabulary, appends, TTS suppression, usage events).
- [x] 2.3 `MuralViewModel` constructs transport by engine id; typed var becomes `VoiceTransport`.
- [x] 2.4 JVM tests `LocalVoiceTransportTest` (turn flow, silence, mute, commentary append, usage).

## Phase 3 — Deployment + on-device verification

- [x] 3.1 `push-local-models.sh`/Makefile: fetch+push multilingual Whisper model (`models/whisper/`).
- [x] 3.2 Instrumented `WhisperSttInstrumentedTest`: TTS→WAV→Whisper round trip with real model.
- [x] 3.3 Emulator E2E: `ENGINE=local` voice session via typed-input (spoken reply), graceful silence handling.

## Phase 4 — Verification

- [x] 4.1 Full unit suites green (both modules).
- [x] 4.2 `verify-report.md` + commits + Engram persistence.
