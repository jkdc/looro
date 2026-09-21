# Delta Spec: hosted-conversations

## ADDED Requirements

### Requirement: Hosted voice conversations on iPhone

When the hosted flag is enabled, a managed account is signed in, the server
allows hosted minutes, and the balance can start a conversation, the iPhone
app SHALL be able to run voice conversations on the hosted path: the session
SHALL be created against the backend (member auth, idempotency key, flat
create body), the audio SHALL connect phone-to-provider directly over
WebRTC, and the hosted session SHALL be closed and confirmed to `closed`
when the conversation ends. An orphaned hosted session SHALL be settled
before sign-out. The provider SHALL be an explicit user preference that
defaults to the personal key; hosted minutes SHALL never be spent without
that choice.

#### Scenario: hosted session runs without a personal key

- **WHEN** a signed-in member with available minutes selects the hosted
  provider and starts a conversation
- **THEN** the session is created through the backend and the conversation
  proceeds without any OpenAI key on the device.

#### Scenario: hosted minutes are never spent silently

- **WHEN** the app starts with the hosted flag enabled and a personal key
  configured
- **THEN** the provider preference defaults to the personal key.

#### Scenario: hosted session is confirmed closed

- **WHEN** a hosted conversation ends
- **THEN** the app requests the backend close and polls until the session
  state is `closed` (or surfaces a non-fatal notice on timeout).

### Requirement: Hosted helpers channel on iPhone

While a hosted session is active, the learning helpers (meaning subtitles,
assessment, delegation, typed replies, word lookup and topic search) SHALL
be served through the backend helpers endpoint for that session instead of
the personal-key client, using the same prompts and the helper purposes
meaning/assessment/delegation/typed_reply/lookup/topic. Without an active
hosted session the behaviour SHALL be unchanged.

#### Scenario: meanings work on hosted minutes

- **WHEN** meaning subtitles are enabled during a hosted conversation
- **THEN** the translation is served by the helpers endpoint of that session
  and stored as today.
