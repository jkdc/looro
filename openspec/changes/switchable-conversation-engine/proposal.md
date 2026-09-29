# Proposal: switchable-conversation-engine

## Intent

Allow the Mural Android app's conversation (AI helper/teaching calls) to run against
**OpenAI** (today's behavior) **or a fully local on-device model**, selected at
deployment/build time through a variable, and make adding further providers
(DeepSeek, GLM, or any OpenAI-compatible/free API) a config-only change.

This is the direct productization of the validated POC in
`../local-speaking-poc` (whisper.cpp + llama.cpp, verified end-to-end on emulator),
kept outside the monorepo on purpose.

## Problem

- Conversation logic is hardwired to OpenAI in two places: the app's `APIClient`
  (BYOK, hardcoded `api.openai.com/v1`) and the hosted backend
  (`OpenAILiveProvider`, `OpenAIHostedResponses`).
- `MuralViewModel.teaching()` is the single dispatch choke point but resolves
  directly to `api.respond(...)` with no seam for alternative engines.
- There is no build/deploy-time way to produce an OpenAI-free build.

## Approach (pattern)

**Factory + Adapter (provider registry).** The existing `TeachingClient`
interface stays the contract. A new `ConversationEngineFactory` maps an engine id
(from `mural.conversationEngine` gradle/local.properties property or the
`MURAL_CONVERSATION_ENGINE` environment variable) to an adapter implementation:

| Engine id | Adapter | Backing |
|---|---|---|
| `openai` (default) | wraps existing `APIClient` | OpenAI Responses API (unchanged behavior) |
| `local` | `LocalLlamaEngine` (new `:core:engine-local` module, llama.cpp via JNI — port of the POC) | on-device GGUF Q4 |
| `deepseek` | `OpenAICompatibleEngine` (OpenAI-compatible `/chat/completions` adapter) | DeepSeek API |
| `glm` | `OpenAICompatibleEngine` | Zhipu GLM API |

Adding a future provider = adding one row to the registry + (if not
OpenAI-compatible) one adapter class. No ViewModel/UI changes.

## Scope

- **In scope (this change)**: text/teaching helper path (`TeachingClient.respond`),
  build-config plumbing, factory + adapters, local-engine Gradle module with
  llama.cpp (JNI), deployment Makefile variable, tests.
- **Out of scope (future phases, documented)**: on-device voice pipeline
  (STT→LLM→TTS replacing WebRTC `LiveTransport` — Phase 6), corrections stream
  from the POC, iOS parity, hosted-backend multi-provider (server side keeps
  `AI_PROVIDER` seam documented for later).

## Risks

- First build on `engine-local` compiles llama.cpp (~10–15 min once, then cached).
- The native `.so` ships in every APK of this branch even when engine=`openai`
  (~12 MB/arm64). Accepted for the experiment; conditional module inclusion is the
  documented follow-up for merging to main.
- Existing `aiConsent` gate and voice policy unchanged in this phase (documented).

## Delivery strategy

`single-pr` — one branch `feature/switchable-conversation-engine`, phase-by-phase
commits.
