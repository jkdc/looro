# Proposal: provider-key-selection

## Intent

Let the user choose among the bundled local conversation engines (DeepSeek,
Zhipu GLM) at runtime and override each provider's API key with their own,
while keeping the deployed bundled key as the default — the "bring your own
key" foundation for the planned paid unlock.

## Problem

The local voice builds (`deepseek`/`glm`) fix the LLM provider at build time
(`BuildConfig.CONVERSATION_ENGINE`) and resolve keys only from the
developer-deployed `api_keys.properties` (or environment). A user cannot
switch provider without a new build, nor use their own provider key: the app
reads the bundled key but offers no way to inspect, replace or reset it.
The OpenAI personal-key flow (`CredentialStore`) already proves the
encrypted-at-rest pattern this change generalises.

## Approach

- **Per-provider key overrides** (`EngineOverridesStore`): user keys
  encrypted at rest with the same Android Keystore AES-GCM scheme as
  `CredentialStore`, one entry per engine id, absent entry = bundled key.
- **Key resolution order** (per engine): user override → environment →
  bundled `api_keys.properties`. Mapping from an engine's `keyEnvVar` to its
  engine id is derived from the provider configs.
- **Runtime selection among local engines** (`LocalEngineSwitcher`): a
  delegating `TeachingClient` that resolves the selected engine per call and
  creates adapters lazily; selection persisted locally, defaults to the
  build-time engine. Only in non-`openai` builds; the `openai` build keeps
  today's behaviour untouched.
- **Settings UI**: a "Conversation provider" section (non-`openai` builds
  only, same gate as the local-voice controls): one row per local engine
  with key status (included / your own / missing), selection radio, and a
  custom-key field for the selected engine (save / return to included key).
  Changes apply to the next conversation; controls are disabled while a
  conversation runs.
- **Adding future providers** (OpenRouter, Groq, …) becomes one registry row
  each, as the factory's design already intends — out of scope here.

## Scope

- **In scope:** the two stores, the switcher, key-resolution order,
  AppViewModel status/selection API, the Settings section (EN/ES strings),
  JVM tests, this record.
- **Out of scope:** new providers (OpenRouter/Groq/…), editable base URLs
  or models, runtime switching between `openai` and local engines (the
  transport differs), iOS (no local engines; its personal-key UI already
  exists — noted for parity), and any billing work.

## Risks

- Keystore-cipher code is untestable on the JVM: the store mirrors the
  proven `CredentialStore` scheme, and its pure validation logic gets JVM
  tests; the crypto path is verified by the existing device deploy flow.
- A missing key for the selected engine must fail with the existing
  `error_missing_engine_key` notice before a conversation starts
  (`engineKeyReady` already works through the switcher's active engine).
- Selection stored per device (not in the Archive): switching phones
  re-defaults to the build-time engine — deliberate, mirroring the hosted
  provider preference on iOS.

## Delivery strategy

`single-pr` — direct conventional commits on the current local branch:
record → stores + switcher → ViewModel wiring → Settings UI + strings →
verified close. Full Android suites + `check_cross_platform.py` green
before the close.
