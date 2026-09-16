# Delta Spec: android-conversation-engine

## ADDED Requirements

### Requirement: Conversation engine selection at build time

The Android app SHALL expose a build-time selection of the conversation engine,
resolved in the following precedence:

1. Gradle property `mural.conversationEngine` (`-P` flag),
2. `mural.conversationEngine` key in `local.properties`,
3. Environment variable `MURAL_CONVERSATION_ENGINE`,
4. Default `openai`.

The resolved value SHALL be exposed to app code as `BuildConfig.CONVERSATION_ENGINE`
and SHALL be validated at configuration time; an unknown value MUST fail the build
with a clear message.

#### Scenario: default build is unchanged

- **WHEN** no property or environment variable is set
- **THEN** `BuildConfig.CONVERSATION_ENGINE == "openai"` and all existing behavior
  (BYOK OpenAI and hosted minutes) is preserved byte-for-byte.

#### Scenario: local deployment

- **WHEN** the app is built with `-Pmural.conversationEngine=local` (or
  `MURAL_CONVERSATION_ENGINE=local`)
- **THEN** teaching/typed AI calls resolve to the on-device engine and no OpenAI
  network call is performed for them.

### Requirement: Engine factory and adapter registry

A `ConversationEngineFactory` SHALL be the only place that constructs engine
implementations. Each engine id maps to exactly one adapter; adding a provider
MUST NOT require changes outside the factory/registry and its adapter file(s).

#### Scenario: unknown engine fails fast

- **WHEN** `CONVERSATION_ENGINE` is not one of the registered ids
- **THEN** app configuration throws with the list of valid ids.

### Requirement: Local engine runs fully on-device

When the selected engine is `local`:

- Teaching calls SHALL be answered by llama.cpp running on-device with a GGUF
  model (Q4 quantized) loaded from the app's external files dir (`models/llm/*.gguf`).
- The engine SHALL run without any network connection.
- `search=true` requests SHALL be answered without web search (documented degradation).
- Usage SHALL be reported honestly (prompt/output tokens from the engine; no
  invented values).

#### Scenario: model missing

- **WHEN** a teaching call is made and no `.gguf` model is present
- **THEN** the call fails with an actionable error (model path shown) and the app
  does not crash.

### Requirement: Deployment script variable

A Makefile target in `apps/android` SHALL accept the engine as a variable
(`make deploy ENGINE=local`) and as an environment variable
(`MURAL_CONVERSATION_ENGINE=local make deploy`), chaining: build with the selected
engine → install → (for `local`) push GGUF models if changed → launch.

#### Scenario: one-command local deployment

- **WHEN** `make deploy ENGINE=local` runs with one device connected
- **THEN** the installed app uses the local engine and required models are on device.
