# Design: provider-key-selection

## Key resolution

```kotlin
// AppViewModel — override-aware, per engine id derived from the env var name
private val engineKeyLookup: (String) -> String? = { envName ->
    overrides.customKey(engineIdForEnv(envName))
        ?: System.getenv(envName)
        ?: ApiKeysStore.get(application, envName)
}
```

`engineIdForEnv` maps through the provider configs (`DEEPSEEK_API_KEY` →
`deepseek`, `ZHIPUAI_API_KEY` → `glm`); an unmapped env var (the OpenAI
credential path) skips the override lookup. `engineKeyReady` keeps working
unchanged: the switcher surfaces the active engine's `keyEnvVar`, and the
lookup is now override-aware.

## Stores

- `EngineOverridesStore` — CredentialStore's scheme (Android Keystore
  AES-GCM key, per-entry ciphertext+IV in SharedPreferences), parameterised
  by engine id instead of a single credential. Entry absent or undecryptable
  = bundled key. Validation: trimmed, non-blank, no whitespace, ≤ 512 chars.
- `LocalEngineSelectionStore` — plain SharedPreferences, stores a validated
  local engine id, default = `BuildConfig.CONVERSATION_ENGINE`.

## Runtime selection

`LocalEngineSwitcher(deps, selected: () -> String)` implements
`TeachingClient` by resolving the active engine **per call** (adapters
created lazily and cached). Because the selection is read per call but the
UI forbids changes while a conversation runs, "applies to the next
conversation" holds without extra state. `keyEnvVar` exposes the active
engine's variable so the existing readiness gate stays correct.
Injected `create` function for JVM tests.

## Settings section

Rendered inside the existing non-`openai` gate. Per engine: radio row
(label + key source: included / your own / missing). Below: custom-key field
for the **selected** engine with save and "use included key again". All
controls disabled while a conversation runs.
