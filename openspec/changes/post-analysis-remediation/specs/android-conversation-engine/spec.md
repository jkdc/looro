# Delta Spec: android-conversation-engine

## MODIFIED Requirements

### Requirement: Engine factory and adapter registry

A `ConversationEngineFactory` SHALL be the only place that constructs engine
implementations. The registry SHALL contain exactly the engines `openai`
(default), `deepseek`, and `glm`; the on-device `local` llama.cpp LLM engine is
removed (product decision, 2026-09-17). The on-device voice pipeline it piloted
(whisper STT, system TTS, `MicRecorder`, `WavReader`, `LocalVoiceTransport`)
SHALL be retained because the remote engines run STT + TTS on device with the
LLM remote. Each engine id maps to exactly one adapter; adding a provider MUST
NOT require changes outside the factory/registry and its adapter file(s).

#### Scenario: local engine id no longer resolves

- **WHEN** a build or deployment requests `CONVERSATION_ENGINE=local`
- **THEN** configuration fails with the list of valid ids (`openai`, `deepseek`,
  `glm`), and no llama.cpp code, native target, or submodule remains in the app.

#### Scenario: unknown engine fails fast

- **WHEN** `CONVERSATION_ENGINE` is not one of the registered ids
- **THEN** app configuration throws with the list of valid ids.

## ADDED Requirements

### Requirement: Engine parity for the learning loop

With `deepseek` or `glm` selected, the learning loop SHALL work without an
OpenAI key: auto and on-demand meanings, word lookup, the current-topic request,
and written-session help SHALL be served through the active engine (OpenAI-
compatible chat completion), parsed into the existing models. The capability
gate SHALL be "active engine configured", not "OpenAI key present"; `openai`
behavior is unchanged.

#### Scenario: meanings with a compatible engine

- **WHEN** the user requests meanings while `deepseek` or `glm` is configured
  with its key
- **THEN** the meanings request goes to the active engine and populates the
  meanings panel like the OpenAI path.

#### Scenario: missing engine key fails fast

- **WHEN** lookup, topic, or help runs while the active engine's key is absent
- **THEN** the action fails with a clear key-setup message instead of an OpenAI
  key error.

#### Scenario: topic without sources degrades

- **WHEN** the engine returns a topic without `sources`
- **THEN** the topic renders without the sources UI; an error is reserved for
  genuine engine/transport failures.

### Requirement: Voice pipeline robustness (shared by non-OpenAI engines)

The on-device voice transport used by `deepseek`/`glm` SHALL: enforce the
30-second turn cap by accumulating processed frame time (a turn ends without
manual end-of-turn); suppress TTS echo (the microphone is paused around
`speak()` and stale buffered frames are drained on resume, with a speaking-depth
counter so overlapping windows cannot trample); fail a session loudly when TTS
initialization reports unusable (and shut the underlying `TextToSpeech` down);
complete interrupted utterances promptly via `onStop(utteranceId, interrupted)`;
and handle all transport events serially so session state is never mutated
concurrently. Transcript-bearing logs SHALL be gated to debug builds.

#### Scenario: continuous speech hits the cap

- **WHEN** the user speaks continuously past `MAX_TURN_MS`
- **THEN** the turn is processed without the manual end-of-turn button.

#### Scenario: assistant speech is not re-captured

- **WHEN** TTS finishes speaking
- **THEN** no turn starts from audio buffered during playback.

#### Scenario: unusable TTS fails loudly

- **WHEN** `LocalTtsEngine.initialize()` reports unusable at session start
- **THEN** the session fails with a clear reason instead of silently producing
  text-only turns.

#### Scenario: interrupted utterance does not hang

- **WHEN** a queued utterance is flushed/interrupted mid-speech
- **THEN** the awaiting speaker resumes immediately instead of waiting for the
  timeout.

### Requirement: Remote API keys stay out of the repository

Remote engine keys SHALL live only in the untracked, gitignored
`apps/android/api-keys.properties` (copy from `api-keys.properties.example`;
read by `deploy-local.sh`/the key store). The release scanner SHALL reject
z.ai-style key patterns and `.properties` credential files without printing
values, and git history SHALL not contain the key file.

#### Scenario: key file never returns to the repo

- **WHEN** `apps/android/api-keys.properties` exists on disk and a commit or the
  release check runs
- **THEN** the file stays untracked, and a synthetic key of the same shape in a
  tracked file fails the scanner without the value appearing in output.

## REMOVED Requirements

### Requirement: Local engine runs fully on-device

**Reason:** product decision (2026-09-17): the on-device llama.cpp LLM is
discarded. The whisper/TTS pipeline and its deployment paths remain for the
remote engines; the removed implementation stays recoverable from git history.
