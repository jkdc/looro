# Delta Spec: android-conversation-engine

## ADDED Requirements

### Requirement: Configurable end-of-turn silence (advanced users)

The on-device voice pipeline SHALL expose the silence window that ends a
spoken turn, configurable between 600 and 3000 ms (default 1200 ms), persisted
across sessions and editable from the Settings screen. The value applies to the
on-device voice pipeline (local STT); it does not alter the OpenAI WebRTC path.

#### Scenario: patient beginner uses manual mode

- **WHEN** beginner (manual end-of-turn) mode is enabled
- **THEN** the silence window is irrelevant: the turn closes only with the
  "done speaking" button (or the 30 s safety cap).

#### Scenario: advanced user tunes the window

- **WHEN** the user sets the silence window to 2000 ms
- **THEN** in auto mode a turn closes only after ~2 s of trailing silence.

### Requirement: Themed opening utterance in local voice

When a voice session starts on the on-device pipeline, the assistant SHALL
open the conversation: it generates a short themed opening (using the session
instructions plus the greeting instruction appended at session start), shows
it as an assistant transcript fragment, and speaks it through TTS.

#### Scenario: theme comes alive

- **WHEN** the user picks a theme and starts a voice session
- **THEN** the assistant speaks a themed opening in the learning language and
  the transcript shows it.

#### Scenario: failure degrades quietly

- **WHEN** the opening generation fails or returns empty
- **THEN** the session continues listening (no crash, no error dialog), and
  the failure is logged.
