# Conversation Engines (OpenAI / DeepSeek / GLM)

The app's AI teaching calls (typed replies, meanings, assessments, topics) run
through a single contract, `chat.mural.network.TeachingClient`, and a single
construction point, `chat.mural.core.engines.ConversationEngineFactory`.
**Which engine is used is a build/deploy-time decision**, not a code change.

```
MuralViewModel.teaching()
        └── ConversationEngineFactory  ← the only construction point
              ├── "openai"   (default) OpenAIEngineAdapter → existing APIClient
              ├── "deepseek"          OpenAICompatibleEngine → api.deepseek.com
              └── "glm"               OpenAICompatibleEngine → open.bigmodel.cn
```

Adding a provider in the future = one registry row in `ConversationEngineFactory`
(+ one adapter class only if the API is not OpenAI-compatible). No ViewModel, UI,
or prompt changes.

## Local engine (removed 2026-09-17)

The former `"local"` engine (on-device llama.cpp LLM) was removed by product
decision: on-device LLM inference was too slow for voice turns (45-110 s per
turn on a Pixel 9, even with tuned GGML kernels). Two things were kept:

- The **whisper.cpp STT + system TTS pipeline** in `:core:engine-local`
  (`WhisperStt`, `WhisperRuntime`, `LocalTtsEngine`, `LocalVoiceTransport`):
  `deepseek`/`glm` voice sessions still run speech-to-text and text-to-speech
  on device, with the LLM call going to the remote API.
- The engine-selection architecture (registry + `BuildConfig.CONVERSATION_ENGINE`).

To re-add an on-device LLM: the implementation (llama.cpp submodule, JNI,
`LocalLlamaEngine`/`LlamaRuntime`/`LocalModelManager`, adapter, tests,
`ENGINE=local` tooling) is preserved in git history before the 2026-09-17
rewrite — see the commit that removed it. Benchmarks that motivated the
decision: `docs/benchmarks-local.md`.

## Selecting the engine

Precedence (highest first):

1. Gradle property: `./gradlew :app:assembleDebug -Pmural.conversationEngine=deepseek`
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
make deploy ENGINE=deepseek    # remote, needs DEEPSEEK_API_KEY in the environment at runtime
make deploy ENGINE=glm         # remote, needs ZHIPUAI_API_KEY at runtime
make deploy-fast ENGINE=glm    # reuse the last APK (no Gradle)
```

Las claves de los motores remotos se leen de un fichero de propiedades
pushable por adb (las apps no pueden leer variables de entorno en runtime).
`api-keys.properties` está **fuera del control de versiones** (gitignored,
nunca se commitea). Créalo copiando el ejemplo que vive en el repo:

```bash
cd apps/android
cp api-keys.properties.example api-keys.properties
# edita api-keys.properties con tus claves reales:
#   ZHIPUAI_API_KEY=<tu clave de z.ai / bigmodel>
#   DEEPSEEK_API_KEY=<tu clave>

adb push api-keys.properties \
  /sdcard/Android/data/chat.mural.android/files/api_keys.properties
```

**Rotación de claves**: edita tu `api-keys.properties` local, vuelve a hacer
el push y revoca la clave antigua en el portal del proveedor. El fichero solo
existe en tu disco y en el dispositivo; si una clave se filtra, revócala
primero en el portal y genera una nueva.

`ApiKeysStore` resuelve `keyEnvVar → entrada del fichero`. GLM verificado en vivo contra api.z.ai con glm-4.5-flash (thinking desactivado por provider config): TTFT 1,2-2,0 s y respuesta completa en 1,2-2,0 s.

`make deploy` chains: build with the selected engine → `adb install` → grant mic →
(for `deepseek`/`glm`) push the whisper model → restart app. With several
devices: `SERIAL=<serial>`.

## Voice pipeline (deepseek / glm)

With a remote engine, starting a conversation opens a voice session:
mic → whisper STT on device (multilingual `ggml-base-q5_1.bin`, language hint
from the session) → remote LLM → Android TTS, driven by `LocalVoiceTransport`
behind the same `VoiceTransport` contract as the WebRTC path. Turn-taking uses
an energy VAD (speech ≥ 0.3 s, end-of-turn after 1.2 s silence, 30 s cap); the
mic is suppressed while TTS speaks; typed input during a voice session is
spoken and shown as assistant transcript.

The whisper model is pushed to
`/sdcard/Android/data/chat.mural.android/files/models/whisper/` by
`make deploy` (or `make models-push`) and is only re-pushed when its size
changes. Note: the model dir must be created by the app (it does so on
launch); the deploy script handles the launch-before-push ordering
automatically. Whisper adds ~300 MB while loaded.

### Known limitations

- `search=true` requests are answered without web search by non-OpenAI engines
  (degradation, not an error).
- The `aiConsent` gate still applies before any engine call (product decision,
  unchanged here).
- The native `.so` (whisper-only since the local LLM removal) ships in every
  build of this branch even with `ENGINE=openai` (unused there). Conditional
  module inclusion is the documented follow-up before merging to `main`.
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
MockWebServer (success/HTTP errors/missing key/schema mode), and the
pre-existing regression suites (which pin the default `openai` behavior). The
whisper STT leg is exercised on device by
`ConversationEnginesInstrumentedTest` (needs `:app:assembleDebugAndroidTest`
plus a device/emulator).
