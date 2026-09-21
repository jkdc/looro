# Tasks: provider-key-selection

## Task 1 — Stores and switcher

- [ ] 1.1 `EngineOverridesStore`: per-engine user key, Keystore AES-GCM
      (CredentialStore scheme), save/remove/get with validation; absent
      entry = bundled key.
- [ ] 1.2 `LocalEngineSelectionStore`: persisted local-engine selection,
      default = build-time engine, validated ids only.
- [ ] 1.3 `LocalEngineSwitcher`: delegating `TeachingClient` resolving the
      selected engine per call, adapters created lazily; `keyEnvVar`
      reflects the active engine; `cancelInFlight` reaches every engine.

## Task 2 — AppViewModel wiring

- [ ] 2.1 Override-aware `engineKeyLookup`: user override → environment →
      bundled file (env-var → engine id derived from provider configs).
- [ ] 2.2 Local builds use the switcher; observable engine statuses
      (selected / key source) for Settings; select/save/remove guarded to
      idle state and applied to new conversations.

## Task 3 — Settings UI

- [ ] 3.1 "Conversation provider" section (non-`openai` gate): per-engine
      rows with key status and selection, custom-key field for the selected
      engine with save / return-to-included.
- [ ] 3.2 EN/ES strings in key parity.

## Task 4 — Tests and gates

- [ ] 4.1 JVM tests: switcher delegation/switching/`keyEnvVar` with injected
      fakes; key validation; env-var → engine mapping.
- [ ] 4.2 Full Android suites + `check_cross_platform.py` green.
