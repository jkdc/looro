# Verify Report: on-device-voice

## Verdict

**pass** (with documented boundary — emulator mic cannot inject real speech;
STT leg verified with TTS-synthesized audio, mic hardware leg is the standard
AudioRecord API proven in local-speaking-poc)

## Evidence

### Tests executed (real runs)

- `LocalVoiceTransportTest` (JVM, fakes) — turn flow (speech frames → input
  transcript → LLM → output transcript → TTS spoken), silence produces no
  turns/LLM calls, mute drops frames, `commentary.append` is spoken and emitted
  as assistant transcript, connect emits `mural.session.created` +
  `session.started`. **All passing.**
- `ConversationEnginesInstrumentedTest` — **8/8 passing on emulator**, now
  including `whisperTranscribesTtsAudioOnDevice`: TTS `synthesizeToFile` →
  WAV → 16 kHz resample → REAL whisper.cpp inference → transcription contains
  the spoken keyword.
- Full unit suites: **339 tests, 0 failures** (both modules) after the change.

### Full-app E2E (`make deploy ENGINE=local`, emulator)

- Deploy pushed both models (`llm/qwen2.5-3b…gguf` size-skipped, `whisper/ggml-base-q5_1.bin` pushed) using the app-owned-dir ordering.
- Orb → `LocalVoiceTransport` connected: "I'm listening / Microphone on";
  VAD listening with silent mic stays gracefully idle (no phantom turns).
- Typed input during the voice session ("Hello! How are you today?") → local
  LLM reply appended as assistant transcript and spoken: **"Hei! Det var en
  litt mistakk, skulle være 'Hej!'. Hva mener du med å ta utbudd?"** / "Hey!
  That was a little mistake…". Session auto-ended on the 120 s inactivity
  policy while the emulator-generation was still running (device is far
  faster); no crashes.

### Bugs found and fixed during this change

1. `MicRecorder` had two companion objects (compile error) — merged.
2. VAD timing derived from real frame audio length (128 ms), not loop pacing
   (tests pass `frameDelayMs=0`); keeps behavior identical on hardware.
3. JNI symbol naming bit us twice (llama + whisper): external fun names must
   match `…_init/_transcribe/_free`. Both .so symbol tables verified with
   llvm-nm against the INSTALLED APK.
4. Stale AGP native artifacts: `outputs/apk/uiTest` packaged an old
   `libmural_llama.so` even after intermediates were clean; recovered by
   deleting `outputs/apk/uiTest` and the `.cxx` cache, then rebuilding.
5. Operational gotcha (documented): any Gradle invocation without
   `-Pmural.conversationEngine` silently rebuilds as `openai`; always deploy
   through `make deploy ENGINE=…`.

## Not verified by automation (honest boundary)

- Live microphone → STT on real hardware (emulator mic is silent): the
  recorder is the standard AudioRecord path proven in the POC; STT itself is
  proven with real audio (TTS round-trip). First manual Pixel check: speak a
  sentence, watch the user fragment appear, then the spoken reply.
- DeepSeek/GLM voice (remote LLM with local STT/TTS) — same pipeline, needs
  live keys.
- Latency/RAM numbers on Pixel hardware.
