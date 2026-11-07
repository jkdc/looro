# Delta Spec: talk-session

## ADDED Requirements

### Requirement: Leaving the Talk screen pauses the voice session

When the user navigates away from the Talk screen while a voice conversation
is running, the app SHALL stop all conversation audio and microphone capture
before the destination screen becomes interactive, and SHALL keep the
conversation available for continuation.

#### Scenario: tab switch silences playback

- **WHEN** a voice conversation is active and the user switches to another
  tab
- **THEN** no assistant audio plays and the microphone no longer captures,
  on any screen.

#### Scenario: the session survives the pause

- **WHEN** the pause completes
- **THEN** the conversation is not ended, is not auto-reset after the ended
  grace period, and its transcript remains available on the Talk screen.

### Requirement: Resume and restart affordances

With a paused conversation, the Talk screen SHALL offer to resume it where
it stopped and to restart the conversation from scratch.

#### Scenario: resume continues the same conversation

- **WHEN** the user resumes a paused conversation
- **THEN** the voice connection reopens feeding the same conversation
  history, no new conversation record is created, and new turns append to
  the existing transcript.

#### Scenario: restart starts over

- **WHEN** the user restarts a paused conversation
- **THEN** the paused conversation is closed and a new voice conversation
  begins from zero.

### Requirement: Typed replies continue the conversation

Typing from the Talk screen SHALL append to the current conversation —
including while it is paused — instead of starting a new one.

#### Scenario: typed message during pause

- **WHEN** the user sends typed text while the conversation is paused
- **THEN** the message and the assistant's reply are appended to the same
  conversation, the paused state is preserved, and no new conversation
  record is created.

### Requirement: Chosen app language persists

The UI language selected in Settings SHALL persist across app restarts on
both platforms.

#### Scenario: selection survives recreation and relaunch

- **WHEN** the user selects a concrete app language, the activity is
  recreated (or the app relaunched)
- **THEN** the interface renders in that language without asking again.

### Requirement: One-command lab deploy

`make deploy` SHALL bring up the local lab API, build the debug app against
it with the configured test engine keys, install it on the connected device
(disambiguating by serial when several devices are attached), and launch it.

#### Scenario: deploy with phone and emulator attached

- **WHEN** `make deploy` runs with both a phone and an emulator connected
- **THEN** the app installs on the selected serial (first USB device by
  default) instead of failing on "more than one device".
