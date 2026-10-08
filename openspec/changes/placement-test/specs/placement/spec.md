# Delta Spec: placement

## ADDED Requirements

### Requirement: Free, repeatable level test

The system SHALL offer a CEFR placement test that any learner can take at any
time, in any order relative to other content, without gating or being gated
by any content. Re-taking the test SHALL always be possible.

#### Scenario: optional entry

- **WHEN** a learner opens the Learn tab for a language with a placement bank
- **THEN** a non-blocking entry point offers the test, with or without a
  previous result.

#### Scenario: retake

- **WHEN** a learner with an existing result starts the test again
- **THEN** the session restarts from scratch and the previous result stays in
  effect until a new one is produced.

### Requirement: Adaptive band ladder

The core test SHALL adapt across CEFR bands: two correct of the last three
answers in the current band move up, two wrong of the last three move down,
with A1 as floor, C1 as cap, and a hard stop of 18 core answers. Item
selection SHALL balance skill categories and never repeat an item within a
session.

#### Scenario: converges upward

- **WHEN** a learner answers consistently correctly above the starting band
- **THEN** the ladder climbs band by band and the global band is the band at
  stop, with high confidence when converged before the cap.

### Requirement: Skill coverage blocks

The test SHALL include a listening block (spoken stimulus, comprehension
answer) and a short-form writing block (exact-match production) administered
at the resulting band. Each block SHALL adjust only its own skill breakdown
by at most one band and SHALL NOT change the global band.

#### Scenario: listening breakdown drop

- **WHEN** the learner fails most of the listening block
- **THEN** the listening breakdown reports one band below the global band
  while the global band and other categories are unchanged.

### Requirement: Always-available self-assessment

After the test — completed or abandoned — the system SHALL present the CEFR
can-do self-assessment and SHALL treat it as the only level signal when the
test produced none.

#### Scenario: abandoned test

- **WHEN** the learner exits the test mid-way and reaches the can-do step
- **THEN** the produced suggestion is marked as self-assessed (source
  `cando`), distinct from a test result.

### Requirement: Suggestion, never fixation

A placement result SHALL be a suggestion: the CEFR bar default, overridable
at any time by the learner. A new test result SHALL NOT silently overwrite a
level the learner chose manually.

#### Scenario: manual choice wins

- **WHEN** the learner picks a level different from the suggestion
- **THEN** the bar keeps the picked level and the suggestion remains visible
  as information only.

### Requirement: Honest result framing

The result screen SHALL present the outcome as an estimate for choosing where
to start — with the per-category breakdown, a confidence sentence, and the
listening/writing splits — and SHALL NOT present it as a certificate.

#### Scenario: result wording

- **WHEN** a placement result is shown
- **THEN** it is labelled as an estimate and no certificate claim is made.
