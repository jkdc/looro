# Design: switchable-conversation-engine

## Pattern: Factory + Adapter over the existing `TeachingClient` seam

The repo already defines the right contract (`network/ConversationClient.kt`):
`TeachingClient.respond(instructions, input, schema, search, purpose): APIResult`.
`MuralViewModel.teaching()` funnels every AI helper call through it. We do **not**
invent a new abstraction; we add a factory that produces `TeachingClient`
implementations (adapters) and swap the single construction site.

```
MuralViewModel (composition root, init)
   └── ConversationEngineFactory
         .create(BuildConfig.CONVERSATION_ENGINE, deps: ConversationEngineDeps)
               │
               ├── "openai"   → OpenAIEngineAdapter(api: APIClient)          [existing client, zero change]
               ├── "local"    → LocalLlamaEngine(runtime, modelManager)      [:core:engine-local, llama.cpp JNI]
               ├── "deepseek" → OpenAICompatibleEngine(DEEPSEEK_CONFIG, ...)  [/chat/completions]
               └── "glm"      → OpenAICompatibleEngine(GLM_CONFIG, ...)
```

## File changes

### `:app` (existing module)

| File | Change |
|---|---|
| `app/build.gradle.kts` | Add `mural.conversationEngine` resolution (property → local.properties → env `MURAL_CONVERSATION_ENGINE` → `openai`), validation, `buildConfigField CONVERSATION_ENGINE`, dependency on `:core:engine-local`, `ndk.abiFilters arm64-v8a/x86_64`. |
| `core/engines/ConversationEngineFactory.kt` *(new)* | Registry `Map<String, (Deps) -> TeachingClient>`; `create(id)` throws listing valid ids on unknown. |
| `core/engines/ConversationEngineDeps.kt` *(new)* | Parameter object: `CredentialStore`, `okHttp` base, `appContext`, `json`, `keyLookup: (String) -> String?` (env/system property bridge for remote keys). |
| `core/engines/OpenAIEngineAdapter.kt` *(new)* | Trivial adapter delegating to `APIClient` (keeps factory uniform; single place to evolve). |
| `core/engines/OpenAICompatibleEngine.kt` *(new)* | OpenAI-compatible `/chat/completions` adapter with per-provider `RemoteEngineConfig(id, defaultBaseUrl, defaultModel, keyEnvVar)`; JSON response parsing; error mapping; unit-tested against MockWebServer. |
| `MuralViewModel.kt` | `private val conversationEngine = ConversationEngineFactory.create(BuildConfig.CONVERSATION_ENGINE, …)` in init; `teaching()` final line becomes `return conversationEngine.respond(...)`; voice start gated when engine != `openai` (explicit error string, Phase 6 removes the gate). |

### `:core:engine-local` (new Android library module)

```
apps/android/core/engine-local/
  build.gradle.kts            com.android.library, minSdk 26, externalNativeBuild CMake, abiFilters
  src/main/AndroidManifest.xml
  src/main/java/chat/mural/engines/local/
     LlamaRuntime.kt          interface { init, generate(...) } (testable seam)
     JniLlamaRuntime.kt       System.loadLibrary("mural_llama"), maps to JNI
     LocalLlamaEngine.kt      TeachingClient adapter: prompt build, grammar from schema JsonObject,
                              APIResult mapping, lazy model load, clear errors
     LocalModelManager.kt     models dir scan/preference (port of POC ModelManager)
  src/main/cpp/
     CMakeLists.txt           links third_party/llama.cpp (same flags as the proven POC)
     llama_jni.cpp            port of POC llama_jni.cpp (batch-chunked decode, streaming callback, grammar)
     third_party/llama.cpp    git submodule pinned b10988
```

Design notes:

- **Prompt building**: use the GGUF's own chat template (llama.cpp
  `common_chat_templates_apply`) as in the POC — model-agnostic. System =
  `instructions`, user = `input`. Mural's `schema: JsonObject?` (json_schema
  structured output) is serialized and converted to a GBNF grammar by llama.cpp;
  the POC proved this path (fixed fallback grammar retained).
- **Threading**: generation on `Dispatchers.Default` behind a `Mutex`; one loaded
  context per process (KV cleared per call), mirroring the POC.
- **Errors**: distinct messages for "model not found" (with expected path),
  "prompt exceeds context", "generation failed"; surfaced through the existing
  `APIException`-style flow (`IOException` subclasses) so `MuralViewModel`
  error mapping keeps working.
- **No whisper in this phase** (text only). Phase 6 adds STT/TTS.

## Config resolution (build.gradle.kts)

```
property = providers.gradleProperty("mural.conversationEngine").orNull
        ?: local.properties["mural.conversationEngine"]
        ?: System.getenv("MURAL_CONVERSATION_ENGINE")
        ?: "openai"
valid = {openai, local, deepseek, glm}
```

`BuildConfig.CONVERSATION_ENGINE: String`. (Remote engine keys are NOT build
config; they are read at runtime from the environment/`local.properties` through
`ConversationEngineDeps.keyLookup` — never hardcode keys.)

## Deployment (apps/android/Makefile)

```
make deploy                 → ENGINE=openai default
make deploy ENGINE=local    → gradle -Pmural.conversationEngine=local assembleDebug,
                              adb install, push models (size-compared), launch
MURAL_CONVERSATION_ENGINE=local make deploy   (env form)
```

Model files: `apps/android/local-models/*.gguf` (gitignored) → pushed to
`/sdcard/Android/data/chat.mural.android/files/models/llm/`.

## Testing strategy

- `:app` unit: factory selection/validation; OpenAICompatibleEngine against
  OkHttp MockWebServer (happy path, HTTP error, missing key, schema passthrough).
- `:core:engine-local` unit: LocalLlamaEngine over a fake `LlamaRuntime`
  (usage mapping, empty model list error, schema→JSON string conversion);
  LocalModelManager preference order.
- Existing `:app` suites must stay green with default engine (regression gate).
- Manual/emulator smoke for `ENGINE=local` (real .gguf) — documented; not a unit test.

## Phases

1. **Phase 1 — Seam + factory + OpenAI adapter** (no behavior change; default engine green).
2. **Phase 2 — OpenAI-compatible remote adapters** (DeepSeek/GLM registry rows + adapter + MockWebServer tests).
3. **Phase 3 — Local engine module** (llama.cpp submodule, JNI, LocalLlamaEngine, model manager + tests).
4. **Phase 4 — Deployment** (Makefile ENGINE variable, env var, model push, README).
5. **Phase 5 — Verification** (both builds, full test suite, verify-report.md).

Future (out of this change): Phase 6 on-device voice (STT→LLM→TTS replacing
WebRTC), corrections stream, server-side `AI_PROVIDER` factory in services/api,
conditional native module inclusion.
