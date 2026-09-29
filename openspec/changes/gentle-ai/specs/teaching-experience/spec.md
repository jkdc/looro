# Delta Spec: teaching-experience

## ADDED Requirements

### Requirement: Tutor speech pace preference

The learner MAY set a slower speech pace. When enabled, new voice sessions
SHALL instruct the tutor to speak noticeably more slowly
(`TeachingPolicy.paceSlow`), and the Android on-device pipeline SHALL
additionally lower the system-TTS speech rate. Live sessions SHALL NOT change
pace mid-flight; the preference applies from the next session. Old archives
without the preference SHALL decode as natural pace.

#### Scenario: slow pace reaches the prompt

- **WHEN** a voice session starts with `slowSpeech` enabled
- **THEN** the session instructions contain the slow-pace line and not the
  natural-pace line, on both platforms.

#### Scenario: old archive decodes

- **WHEN** an archive exported before this change is imported (or loaded)
- **THEN** `slowSpeech` is absent and behaves as natural pace; import still
  succeeds on both platforms.

### Requirement: Listening comprehension mode

The learner MAY enable a listening-practice mode. Sessions started in that
mode SHALL be marked `mode = "listening"`, SHALL open with
`TeachingPolicy.listeningStory` instead of the greeting, and SHALL suppress
automatic meaning subtitles for the whole session. Word lookup SHALL remain
available. Assessments SHALL run unchanged. Ending the mode affects only
future sessions.

#### Scenario: listening session hides subtitles but keeps lookup

- **WHEN** the tutor speaks during a listening session with meaning subtitles
  globally enabled
- **THEN** no meaning is scheduled or rendered for the session, and tapping a
  caption word still opens the lookup sheet.

#### Scenario: mode is recorded

- **WHEN** a listening session is saved
- **THEN** the session record carries `mode = "listening"` on both platforms.

### Requirement: Local progress projection

The app SHALL present a read-only Progress view computed from the local
archive: total sessions and voice minutes, words per recall-bar level, active
and longest day streaks (local calendar days, anchored on today or
yesterday), voice minutes for the last 14 days, and the capabilities count
with the latest descriptor. No new stored data SHALL be required.

#### Scenario: streak across a gap

- **WHEN** sessions exist on three consecutive days followed by a day with no
  session, then today has a session
- **THEN** the active streak counts only today and the longest streak counts
  the earlier three-day run.

### Requirement: Directed writing practice

The Words experience SHALL offer a writing drill over due words (hidden words
excluded, at most 10, earliest due first). For each item it SHALL show the
English sense and the example quote with the target form masked, accept a
typed answer matching the normalized `form` or `lemma`, reveal the correct
spelling after a miss, and summarize the run. Attempts SHALL NOT be persisted
in this change.

#### Scenario: diacritics are significant

- **WHEN** the target form is "frågor" and the answer is "fragor"
- **THEN** the attempt is rejected and the correct spelling is revealed.

#### Scenario: form and lemma both accepted

- **WHEN** the target form is "vorrei" with lemma "volere"
- **THEN** typing either word, in any case or surrounding punctuation, is
  accepted.

### Requirement: On-device voice feedback (Android local pipeline)

On the deepseek/glm voice path the app SHALL show provisional transcription
while the user speaks, SHALL let the learner interrupt playback by voice when
the device provides acoustic echo cancellation, and SHALL adapt turn detection
to the room via a noise-floor-tracked energy VAD. Partials SHALL be
provisional only — never stored as fragments. Without AEC, playback
interruption by voice SHALL be disabled. The manual-turn mode and the
adjustable silence window SHALL keep their semantics.

#### Scenario: partial appears then is replaced

- **WHEN** the learner speaks long enough for a partial pass and then pauses
- **THEN** the interim line shows the provisional text, and the final stored
  fragment comes from the turn-final transcription.

#### Scenario: barge-in stops playback

- **WHEN** the learner speaks at sustained barge energy while the assistant
  plays on an AEC-capable device
- **THEN** playback stops through the depth-accounted path and the mic
  returns to normal turn capture.
