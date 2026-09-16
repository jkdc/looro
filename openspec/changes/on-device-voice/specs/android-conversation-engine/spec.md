# Delta Spec: android-conversation-engine

## ADDED Requirements

### Requirement: On-device voice conversations for non-OpenAI engines

When the selected engine is not `openai`, starting a conversation SHALL open a
voice session powered by an on-device pipeline: microphone capture → Whisper
STT → the selected `TeachingClient` → Android TTS, with no network calls to
OpenAI for the local engine.

#### Scenario: spoken turn produces a coached reply

- **WHEN** the user speaks and the turn ends (silence detected)
- **THEN** the transcription appears as a user fragment, the engine's reply
  appears as an assistant fragment, and the reply is spoken through TTS.

### Requirement: Same transport contract

The local voice transport SHALL expose the same surface as `LiveTransport`
(callbacks `onEvent`/`onFailure`/`onLevels`; `connect`, `send`, `mute`, `close`,
`disconnect`) and emit the event vocabulary the ViewModel already handles
(`mural.session.created`, `session.started`, transcript deltas, usage,
`session.closed`), so the ViewModel and UI require no engine-specific branches.

#### Scenario: typed input during a local voice session

- **WHEN** the user sends a typed message while a local voice session is active
- **THEN** the reply is spoken (TTS) and appended as an assistant transcript
  fragment, mirroring the `commentary.append` behavior of the WebRTC path.

### Requirement: STT language follows the session language

The Whisper model SHALL be multilingual and SHALL be given the session's spoken
language hint; silence that yields no transcription SHALL end the turn
gracefully (no fragments, no LLM call) with listening resumed.

#### Scenario: silence is not transcribed

- **WHEN** no speech is detected or STT returns empty text
- **THEN** no user fragment is appended and listening resumes.

### Requirement: Deployment includes the STT model

`make deploy ENGINE=local` SHALL fetch (if missing) and push the multilingual
Whisper model to `models/whisper/` in the app's external files dir alongside the
LLM model, using the app-owned-directory flow (launch before push).

#### Scenario: voice-ready deployment

- **WHEN** `make deploy ENGINE=local` completes with one device connected
- **THEN** both `models/llm/*.gguf` and `models/whisper/*.bin` are on device and
  a voice session runs offline.
