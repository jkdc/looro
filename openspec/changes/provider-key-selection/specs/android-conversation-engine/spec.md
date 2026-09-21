# Delta Spec: android-conversation-engine

## ADDED Requirements

### Requirement: Selectable local provider with per-provider keys

In non-`openai` builds, the app SHALL let the learner select among the
bundled local conversation engines and SHALL apply the selection to new
conversations (never mid-conversation). For each local engine the learner
MAY store a personal provider key, encrypted at rest; key resolution SHALL
be user override first, then the deployed bundled key. The default SHALL
remain the bundled key and the build-time engine. The `openai` build SHALL
be unaffected.

#### Scenario: bundled key remains the default

- **WHEN** a local-voice build starts with no user keys stored
- **THEN** conversations use the deployed bundled key of the build-time
  engine.

#### Scenario: custom key overrides the bundled one

- **WHEN** the learner saves a personal key for a provider
- **THEN** that provider's calls use the personal key until the learner
  returns to the included key, and the key is stored encrypted at rest.

#### Scenario: switching applies to new conversations only

- **WHEN** the learner selects another local engine while a conversation is
  running
- **THEN** the selection is rejected until the conversation ends, and the
  running conversation keeps its engine.

#### Scenario: missing key fails before connecting

- **WHEN** the selected engine has neither a user key nor a bundled key
- **THEN** starting a conversation fails with the existing missing-engine-key
  notice instead of a network error.
