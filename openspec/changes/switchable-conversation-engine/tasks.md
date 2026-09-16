# Tasks: switchable-conversation-engine

## Phase 1 — Seam + factory + OpenAI adapter (no behavior change)

- [ ] 1.1 Add `mural.conversationEngine` resolution + validation + `BuildConfig.CONVERSATION_ENGINE` in `app/build.gradle.kts` (property → local.properties → `MURAL_CONVERSATION_ENGINE` env → `openai`).
- [ ] 1.2 Create `core/engines/ConversationEngineDeps.kt` and `core/engines/ConversationEngineFactory.kt` with registry `{openai, local, deepseek, glm}` (local/deepseek/glm rows wired in later phases; unknown id → throw listing valid ids).
- [ ] 1.3 Create `core/engines/OpenAIEngineAdapter.kt` delegating to `APIClient`.
- [ ] 1.4 Wire `MuralViewModel`: construct engine via factory in init; `teaching()` dispatches through it; gate voice start with a clear message when engine != `openai`.
- [ ] 1.5 Tests: `ConversationEngineFactoryTest` (default, selection, unknown id) — default-build regression suite green.

## Phase 2 — OpenAI-compatible remote adapters

- [ ] 2.1 Create `core/engines/OpenAICompatibleEngine.kt` (`RemoteEngineConfig` table for deepseek/glm; `/chat/completions` request/response; usage mapping to `APIUsage`; key via `keyLookup(envVar)`; IOException-based errors).
- [ ] 2.2 Register `deepseek` and `glm` rows in the factory.
- [ ] 2.3 Tests: `OpenAICompatibleEngineTest` with MockWebServer (success, HTTP 4xx/5xx, missing key, system+user message shape, temperature/model body).

## Phase 3 — Local engine module `:core:engine-local`

- [ ] 3.1 Add llama.cpp submodule (b10988) under `apps/android/core/engine-local/src/main/cpp/third_party/`.
- [ ] 3.2 Module skeleton: `settings.gradle.kts` include, `build.gradle.kts` (library, NDK/CMake, abiFilters arm64-v8a + x86_64), manifest.
- [ ] 3.3 Port `llama_jni.cpp` + `CMakeLists.txt` from the POC (batch-chunked decode, streaming callback, schema→grammar via chat templates, fallback grammar).
- [ ] 3.4 Kotlin: `LlamaRuntime` (seam), `JniLlamaRuntime` (JNI object), `LocalModelManager` (dir scan/preference), `LocalLlamaEngine : TeachingClient` (prompt/grammar/usage mapping, lazy load, IOException errors).
- [ ] 3.5 `:app` depends on `:core:engine-local`; factory `local` row builds `LocalLlamaEngine`.
- [ ] 3.6 Unit tests in the module: engine-over-fake-runtime (usage, errors, schema JSON string), model manager preference.

## Phase 4 — Deployment

- [ ] 4.1 `apps/android/Makefile`: `deploy` with `ENGINE` variable (default `openai`), env-var form, `deploy-fast`, `models-push`, `logs`.
- [ ] 4.2 `scripts/push-local-models.sh` (size-compare push, same pattern as the POC).
- [ ] 4.3 Docs: `apps/android/docs/conversation-engines.md` (fases, uso, cómo añadir un proveedor nuevo, limitaciones).

## Phase 5 — Verification

- [ ] 5.1 Full unit test suite green on default build (`testDebugUnitTest`).
- [ ] 5.2 `assembleDebug` succeeds for `ENGINE=openai` and `ENGINE=local`.
- [ ] 5.3 Emulator smoke (documented in verify report): `local` build loads GGUF and answers a typed teaching call without network; `openai` build unchanged flows.
- [ ] 5.4 Write `verify-report.md`; persist SDD artifacts (hybrid: files + Engram); commit per phase.
