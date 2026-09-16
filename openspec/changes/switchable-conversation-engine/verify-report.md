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

- **Real provider calls** (DeepSeek/GLM) need live keys; contract pinned by
  MockWebServer unit tests and on-device construction tests. On-device plaintext
  HTTP to localhost is blocked by network security policy (by design), so the
  instrumented HTTP variant was replaced by construction + key-handling tests.
- **Pixel hardware**: RAM/latency numbers remain to be measured manually.

## Round 2 — on-device tests requested after review (emulator, real model)

New instrumented suite `ConversationEnginesInstrumentedTest` (7 tests, **all
passing** via `am instrument`):

- `localEngineGeneratesReplyWithRealModel` — REAL llama.cpp inference with the
  real Qwen2.5-3B Q4_K_M GGUF through `ConversationEngineFactory.create("local")`.
- `localEngineHonorsJsonSchema` — Mural-style schema constrained to valid JSON
  via GBNF grammar; reply parses and contains the required field.
- `openaiAdapterIsConstructedAndDelegates`, `compatibleEnginesAreConstructedOnDevice`,
  `missingRemoteKeyFailsWithProviderMessage`, `localEngineWithEmptyModelDirFailsGracefully`,
  `setupLocalModelDir` (must run before the adb push: the model dir must be
  created by the app — see FUSE note).

**Full-app E2E through the real UI (`ENGINE=local` deploy)**: onboarding → AI
consent → fake key saved → orb starts a typed session → typed
"Yesterday I go to the beach. It was amazing!" → assistant reply generated
on-device in the learning language (Norwegian):
**"Ja, hva fant du å gjøre i går?"** / "Yes, what did you do yesterday?" —
grammatically correct target-language coaching; the meaning/lookup helper flow
also ran through the local engine. Zero crashes; process alive across rounds.

Bugs found and fixed during this round:
1. JNI symbol mismatch after renaming the Kotlin bridge (`LlamaNative` →
   `JniLlamaRuntime`) — native functions renamed accordingly.
2. Qwen renders `<|im_start|>assistant` as text (added-token, not control):
   `LocalLlamaEngine` now sanitizes leading template-header echoes
   (`sanitizeCompletion`, unit-tested).
3. FUSE/permission: model dirs created by `adb shell` are NOT readable by the
   app — the model dir must be created by the app itself. Fixed by
   `ensureDirs()` at engine construction + deploy order in the Makefile
   (launch app once → push models → relaunch). Push scripts unchanged.
4. Voice gating replaced by typed-session fallback (spec scenario updated).

Unit suite after fixes: **334 tests, 0 failures** (both modules).

## Follow-ups (documented, out of this change)

1. Phase 6: on-device voice (STT→LLM→TTS) replacing WebRTC for non-OpenAI engines.
2. Conditional inclusion of `:core:engine-local` native build before merging to main.
3. Server-side `AI_PROVIDER` factory in `services/api` (seam already documented there).
4. Product decision: `aiConsent` gate scope for on-device-only inference.
