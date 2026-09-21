# Verify Report: provider-key-selection

Date: 2026-09-22 · Branch: `feature/rebranding-looro` (local, unpushed)

## What was delivered

- `EngineOverridesStore`: optional per-engine user key, encrypted at rest
  with the same Keystore AES-GCM scheme as `CredentialStore`; validation
  (trim, non-blank, no whitespace, ≤512) exposed as a pure function for JVM
  tests; absent/undecryptable entry = bundled key.
- `LocalEngineSelectionStore` + `LocalEngineSwitcher`: the active local
  engine resolves per call (adapters lazily created and cached); the
  build-time engine is the default; `keyEnvVar`/`cancelInFlight` behave
  correctly through the delegate.
- Key resolution order per engine: **user override → environment → bundled
  `api_keys.properties`**; the env-var → engine-id mapping is derived from
  the provider configs.
- AppViewModel: override-aware lookup, switcher wiring for local builds
  (`openai` builds untouched), observable engine statuses, and guarded
  select/save/remove (rejected while a conversation runs).
- Settings "Conversation provider" section (non-`openai` gate): per-engine
  rows with key source (included / your own / missing), selection radio, and
  a custom-key field with save / return-to-included. Strings EN/ES 262/262.

## Verified on this machine

- Android: 406/406 `:app` + 5/5 `:core:engine-local` unit tests (new:
  switcher delegation/switching/`keyEnvVar`/unknown-id, key validation,
  env-var → engine mapping), `:app:assembleDebug` green,
  `check_cross_platform.py` green.

## HEAD repair included

`brand-constants` T2 left `styles.xml` pointing at a non-existent
`app_launch_background` drawable, breaking every resource-linking task on a
clean tree (unit tests also depend on resource linking). Fixed by pointing
the launch theme at the renamed `launch_background` (commit `1050e09`).

## Gates pending (not run here)

- Keystore crypto path: verified only by the established device deploy flow
  (`make deploy ENGINE=glm`) — save a custom key, confirm the provider call
  uses it, then "use included key again".
- Device listen-check of a provider switch between two conversations.
- Adding OpenRouter/Groq configs and the billing work remain parked
  follow-ups, as scoped.
