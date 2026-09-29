# Verify Report: voice-turn-settings-themes

## Verdict

**pass** (functional verification on emulator + unit suites; on-phone silence
feel pending manual use)

## Evidence

### Tests executed (real runs)

- Unit suites (both modules): **BUILD SUCCESSFUL — 339+ tests, 0 failures**,
  including new:
  - `LocalVoiceTransportTest#custom silence window gates the auto turn`:
    with `silenceEndMs = 3000`, a turn does not close at 1,3 s of silence and
    closes once exceeded.
  - `LocalVoiceTransportTest#manual mode holds the turn until endTurn is
    requested`: manual mode never auto-closes; `endTurn()` commits the turn
    (STT → LLM → TTS) with the buffered audio.
  - (Beginner mode regression tests from the previous change remain green.)

### Functional verification on emulator (ENGINE=glm, Pixel-config parity)

- Voice session starts with `language='nb'` (the persisted learning language).
- **Themed opening utterance works end-to-end**: GLM-4.5-Flash generated the
  opening in the learning language — *"Hei! Hvordan har du det i dag?"* —
  emitted as assistant transcript and spoken via TTS (log: `opening spoken
  (30 chars)`).
- Settings shows both new controls: beginner-mode switch and the silence
  slider (0,6–3,0 s) with its hint; values persist via
  `VoiceManualTurnStore`.

### Deploy

- `make deploy-fast ENGINE=glm SERIAL=…` installs the APK + pushes
  `api_keys.properties` automatically (verified: `ZHIPUAI_API_KEY` entry).
- `make deploy ENGINE=glm` builds with the right engine flag — remember every
  Gradle invocation without `-Pmural.conversationEngine` falls back to
  `openai`.

## Not verified by automation (honest boundary)

- The *feel* of the silence window on real hardware (0,6–3,0 s range) —
  tune per user; the value applies live to the listening loop.
- GLM on-device typed/voice round trip from the PHONE: verified from the
  emulator with the same key + endpoint (1,2–2,0 s replies); phone-side
  depends on the user's network.
- OpenAI WebRTC manual commit (the button is hidden there; server VAD rules).
