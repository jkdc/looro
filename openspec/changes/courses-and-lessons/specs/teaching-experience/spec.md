# Delta Spec: teaching-experience

## ADDED Requirements

### Requirement: Course library with authored lessons

The app SHALL offer structured courses for a learning language, organized as
ordered units of authored lessons (a grammar point or expression set with a
communicative goal). Course content SHALL be embedded data shared across
platforms from a single canonical source with a drift check. Lessons SHALL be
freely navigable (no unlocking). Only authored lessons SHALL be offered.

#### Scenario: course appears for its language

- **WHEN** the learning language has a course and the learner opens the
  Learn tab
- **THEN** the course, its units, and its authored lessons are listed with
  the learner's attempt state.

#### Scenario: content cannot drift between platforms

- **WHEN** the generated course data on one platform differs from the
  canonical source
- **THEN** the export check fails.

### Requirement: Lesson study card with optional read-aloud

Each lesson SHALL present a pre-loaded study card: a short explanation, work
examples with meaning glosses, target expressions, and the practice situation.
The card SHALL be readable silently, and SHALL offer on-device read-aloud in
the course language with an audio switch that silences it. The read-aloud
SHALL use local synthesis, SHALL degrade to silent reading when no suitable
voice exists, and SHALL NOT invoke the conversation model.

#### Scenario: audio off reads silently

- **WHEN** the learner disables the audio switch and opens a lesson
- **THEN** the card is shown without any synthesized speech.

#### Scenario: reading works without the conversation engine

- **WHEN** the learner taps read-aloud on the study card
- **THEN** the card text is spoken with the course-language system voice (or
  silently skipped when unavailable), with no model call.

### Requirement: Guided lesson practice conversation

Starting a lesson's practice SHALL create a voice session marked
`mode = "lesson"` carrying the lesson id, whose instructions are a
lesson-specific rigorous prompt: the tutor SHALL ask the pre-set practice
questions in order, one at a time; after each learner answer SHALL say
explicitly whether it was correct; SHALL correct errors with a recast plus a
one-line rule without advancing until the target form appears (assisted
production acceptable); and SHALL close with a short review of the target
expressions. Typed replies during the session SHALL stay lesson-aware.
Existing session affordances (subtitles, lookup, help, assessments, ending)
SHALL behave unchanged. Running a practice session SHALL record the attempt
(attempts, lastSeen) for that lesson without asserting mastery.

#### Scenario: practice validates answers

- **WHEN** the learner answers a practice question incorrectly
- **THEN** the tutor recasts the answer, names the one-line rule, and asks
  again before moving to the next question.

#### Scenario: attempt recorded without mastery claims

- **WHEN** a practice session for a lesson ends
- **THEN** the lesson's attempt count and last-seen date advance, and no
  mastery/bars claim is made from this change.

#### Scenario: session is labelled by its lesson

- **WHEN** a practice session appears in history
- **THEN** it is titled with the lesson and marked with the lesson id.
