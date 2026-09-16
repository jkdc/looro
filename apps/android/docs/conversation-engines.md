# Conversation Engines (OpenAI / local / DeepSeek / GLM)

The app's AI teaching calls (typed replies, meanings, assessments, topics) run
through a single contract, `chat.mural.network.TeachingClient`, and a single
construction point, `chat.mural.core.engines.ConversationEngineFactory`.
**Which engine is used is a build/deploy-time decision**, not a code change.

```
MuralViewModel.teaching()
        └── ConversationEngineFactory  ← the only construction point
              ├── "openai"   (default) OpenAIEngineAdapter → existing APIClient
              ├── "local"             LocalLlamaEngineAdapter → :core:engine-local (llama.cpp, offline)
              ├── "deepseek"          OpenAICompatibleEngine → api.deepseek.com
              └── "glm"               OpenAICompatibleEngine → open.bigmodel.cn
```

Adding a provider in the future = one registry row in `ConversationEngineFactory`
(+ one adapter class only if the API is not OpenAI-compatible). No ViewModel, UI,
or prompt changes.

## Selecting the engine

Precedence (highest first):

1. Gradle property: `./gradlew :app:assembleDebug -Pmural.conversationEngine=local`
2. `local.properties` key `mural.conversationEngine`
3. Environment variable `MURAL_CONVERSATION_ENGINE`
4. Default: `openai` (byte-for-byte today's behavior)

Invalid values fail the build immediately.

The value lands in `BuildConfig.CONVERSATION_ENGINE`. Use `make deploy` (below)
so the whole pipeline follows the variable.

## Deploy

```bash
cd apps/android
make deploy                    # ENGINE=openai by default (unchanged behavior)
make deploy ENGINE=local       # on-device llama.cpp
make deploy ENGINE=deepseek    # remote, needs DEEPSEEK_API_KEY in the environment at runtime
make deploy ENGINE=glm         # remote, needs ZHIPUAI_API_KEY at runtime
MURAL_CONVERSATION_ENGINE=local make deploy ENGINE=   # env-var form
make deploy-fast ENGINE=local  # reuse the last APK (no Gradle)
```

`make deploy` chains: build with the selected engine → `adb install` → grant mic →
(for `local`) push models → restart app. With several devices: `SERIAL=<serial>`.

## Local engine (`ENGINE=local`)

- Module: `core/engine-local` (Android library) wrapping llama.cpp @ b10988 via a
  small JNI bridge (`mural_llama` .so). Port of the validated
  `local-speaking-poc` (kept out of the monorepo on purpose).
- Prompting uses the GGUF's own chat template; Mural's JSON schemas become
  llama.cpp GBNF grammars (structured output) when provided.
- Models: put one `.gguf` in `apps/android/local-models/` before deploying, e.g.

  ```bash
  curl -L -o apps/android/local-models/qwen2.5-3b-instruct-q4_k_m.gguf \
    https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf
  ```

  Files land on device under
  `/sdcard/Android/data/chat.mural.android/files/models/{llm,whisper}/` and are
  only re-pushed when their size changes. The LLM manager prefers known
  conversation models by name; the Whisper manager prefers multilingual base
  over English-only (see `LocalModelManager`/`LocalWhisperModelManager`).
  LLM inference is CPU-only, ~2 GB RAM; Whisper adds ~300 MB while loaded.
  Note: the model dirs must be created by the app (it does so on launch);
  `make deploy` handles the launch-before-push ordering automatically.
- First build compiles llama.cpp with the NDK (10–20 min once, then cached).

### Runtime requirements (local)

~2.5 GB free RAM for the process (device total ≥ 6 GB, 8 GB comfortable),
~2.5 GB storage, arm64-v8a. Works in airplane mode.

### Known limitations (this phase)

- **Voice on-device** (Phase 6): with `ENGINE=local`, starting a conversation
  now opens an on-device voice session: mic → Whisper STT (multilingual
  `ggml-base-q5_1.bin`, language hint from the session) → local LLM → Android
  TTS, driven by `LocalVoiceTransport` behind the same `VoiceTransport`
  contract as the WebRTC path. Turn-taking uses an energy VAD (speech ≥ 0.3 s,
  end-of-turn after 1.2 s silence, 30 s cap); the mic is suppressed while TTS
  speaks; typed input during a voice session is spoken and shown as assistant
  transcript. Voice for `deepseek`/`glm` also uses the local STT/TTS with the
  remote LLM.
- `search=true` requests are answered without web search by non-OpenAI engines
  (degradation, not an error).
- The `aiConsent` gate still applies before any engine call (product decision,
  unchanged here).
- The native `.so` ships in every build of this branch even with
  `ENGINE=openai` (~12 MB/arm64, unused). Conditional module inclusion is the
  documented follow-up before merging to `main`.
- Remote engines never receive key material at build time: keys are read from
  the environment at runtime (`DEEPSEEK_API_KEY`, `ZHIPUAI_API_KEY`) on the
 *device* that runs the app — for real deployments a thin proxy (the existing
  `services/api` hosted path) remains the recommended way to keep keys off
  devices; these adapters target experimentation.
- Structured output on `deepseek`/`glm` uses JSON mode + schema-in-prompt
  (no cross-provider `json_schema` guarantee); OpenAI keeps native
  `json_schema`.

## Tests

```bash
make test   # :app unit tests + :core:engine-local tests
```

Covers: factory validation/selection, OpenAI-compatible adapter against
MockWebServer (success/HTTP errors/missing key/schema mode), local engine over a
fake runtime (usage mapping, model-missing error, schema forwarding), model
preference order, and the pre-existing regression suites (which pin the default
`openai` behavior).
