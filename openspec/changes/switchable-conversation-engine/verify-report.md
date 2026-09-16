# Verify Report: switchable-conversation-engine

## Verdict

**pass** (with documented boundary — see "Not verified by automation")

## Evidence

### Spec compliance

| Spec scenario | Evidence | Result |
|---|---|---|
| Default build unchanged (`openai`) | `./gradlew :app:testDebugUnitTest` — 333 tests, 0 failures, incl. pre-existing regression suites; `assembleDebug` default → `CONVERSATION_ENGINE="openai"` | pass |
| Build-time selection & validation | `app/build.gradle.kts` resolution chain (-P → local.properties → `MURAL_CONVERSATION_ENGINE` → openai); `-Pmural.conversationEngine=local` build succeeded; unknown ids fail configuration (require) | pass |
| Local engine fully on-device | `LocalLlamaEngine` + JNI port (batch-chunked decode, GGUF chat template, schema→GBNF); same native code path proven end-to-end (TTS→STT→LLM) in local-speaking-poc with this exact GGUF; unit tests pin usage mapping, model-missing error with path, schema forwarding | pass |
| Deployment variable (`make deploy ENGINE=…`) | `apps/android/Makefile` + `scripts/push-local-models.sh`; executed `make deploy ENGINE=local` on emulator-5554: build → install → pm grant → model push (2.0 GB) → launch | pass |
| Factory is single construction point | `MuralViewModel.teaching()` final dispatch = `conversationEngine.respond(...)`; only `ConversationEngineFactory` builds adapters | pass |

### Tests executed (real run)

- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL**, 333 tests total (includes 6 new
  `ConversationEngineFactoryTest`, 6 new `OpenAICompatibleEngineTest` against
  MockWebServer: success/usage mapping/JSON mode/missing key/500-retryable/401-not-retryable/malformed payload).
- `:core:engine-local:testDebugUnitTest` → **BUILD SUCCESSFUL**, 6 tests
  (completion+usage, schema forwarding, model-missing, init failure, error mapping, model preference).
- Builds: default (`openai`) and `-Pmural.conversationEngine=local` → both
  `assembleDebug` green; APK contains `libmural_llama.so` + llama.cpp/ggml for arm64-v8a and x86_64.

### Emulator smoke (Android 16, x86_64, 6 GB)

- `make deploy ENGINE=local` completed; `chat.mural.android` launches and survives
  onboarding navigation with zero FATAL log entries; app process alive after model present.
- Model push: size-compare skip/push verified (2.0 GB pushed once; re-runs skip).

## Not verified by automation (honest boundary)

- **In-app typed teaching call with the local model on the emulator**: Mural's
  conversation entry requires onboarding credentials (OpenAI key / hosted
  account) that cannot be legitimately automated here. The engine itself is
  covered by unit tests, and the identical native stack + model completed full
  offline inference in the POC. First manual check on a Pixel: open a typed
  reply with `ENGINE=local` and confirm on-device generation (logcat tag
  `MuralLocalEngine`).
- **Real provider calls** (DeepSeek/GLM) need live keys; contract pinned by
  MockWebServer tests.
- **Pixel hardware**: RAM/latency numbers remain to be measured manually.

## Follow-ups (documented, out of this change)

1. Phase 6: on-device voice (STT→LLM→TTS) replacing WebRTC for non-OpenAI engines.
2. Conditional inclusion of `:core:engine-local` native build before merging to main.
3. Server-side `AI_PROVIDER` factory in `services/api` (seam already documented there).
4. Product decision: `aiConsent` gate scope for on-device-only inference.
