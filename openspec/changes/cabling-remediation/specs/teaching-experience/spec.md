# Delta Spec: teaching-experience

## MODIFIED Requirements

### Requirement: On-device voice feedback (Android local pipeline)

On the deepseek/glm voice path the app SHALL show provisional transcription
while the user speaks, SHALL synthesize the assistant's speech in the session
language when the device provides a system voice for it (degrading to the
previous voice, never failing the session, when it does not), SHALL let the
learner interrupt playback by voice when the device provides acoustic echo
cancellation, and SHALL adapt turn detection to the room via a
noise-floor-tracked energy VAD. Partials SHALL be provisional only — never
stored as fragments — and SHALL be cleared when the turn is discarded without
a final transcription. Without AEC, playback interruption by voice SHALL be
disabled. The manual-turn mode and the adjustable silence window SHALL keep
their semantics, and their settings SHALL only be offered in builds where
they take effect.

#### Scenario: discarded turn clears the caption

- **WHEN** a turn ends as a noise blip or an empty transcription after a
  partial was shown
- **THEN** the provisional caption is cleared and no fragment is stored.

#### Scenario: TTS follows the session language

- **WHEN** a voice session starts in Spanish on a device with a Spanish
  system voice
- **THEN** the assistant's replies are synthesized with that voice; a device
  without the voice logs the failure and continues with the previous one.

#### Scenario: inert controls hidden in the default build

- **WHEN** the app is built with `CONVERSATION_ENGINE=openai` and Settings is
  opened
- **THEN** the manual-turn switch and the silence slider are not rendered.

### Requirement: Listening comprehension mode

The learner MAY enable a listening-practice mode. Sessions started in that
mode SHALL be marked `mode = "listening"`, SHALL open with
`TeachingPolicy.listeningStory` instead of the greeting, and SHALL suppress
automatic meaning subtitles for the whole session. Word lookup SHALL remain
available, and the mode's description SHALL state that the written transcript
remains visible. Assessments SHALL run unchanged. Ending the mode affects
only future sessions.

#### Scenario: copy matches behavior

- **WHEN** the learner reads the listening-practice description in Settings
- **THEN** it promises exactly: no automatic subtitles, transcript and lookup
  still available.

## ADDED Requirements

### Requirement: Failed opening greeting fails the session

On the on-device voice path, a failure while generating the opening greeting
SHALL surface as a session failure with a distinct message, not as a silent
degradation; cancellation SHALL remain unwrapped.

#### Scenario: assistant silent no more

- **WHEN** the engine rejects the greeting request
- **THEN** the learner sees the session failure with the greeting-specific
  message.

### Requirement: Guest link survives member deletion

The guest→member transfer SHALL treat the server outcome `member_deleted` as
terminal: the pending link intent is cleared, no retry is scheduled, and the
learner sees an explanatory notice while keeping their own minutes.

#### Scenario: deleted member no longer wedges the guest

- **WHEN** the transfer endpoint answers `outcome: "member_deleted"`
- **THEN** the linking state ends with the notice instead of retrying
  forever.

### Requirement: Talk orb is labeled for screen readers

The Talk orb SHALL expose a `contentDescription` derived from the session
state (idle vs listening) using the existing strings.

#### Scenario: TalkBack announces the orb

- **WHEN** the Talk screen is focused with a screen reader
- **THEN** the orb announces its state instead of nothing.
