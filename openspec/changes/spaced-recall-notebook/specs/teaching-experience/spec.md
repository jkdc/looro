# Delta Spec: teaching-experience

## ADDED Requirements

### Requirement: Spaced recall queue

The app SHALL offer a recall queue that interleaves due expressions and words,
scheduled on the same spaced ladder as word bars, seeded from lesson target
expressions and learner-saved lookups. The queue SHALL be free of mastery
claims: bars estimate spoken recall, not permanent knowledge.

#### Scenario: lesson practice seeds expressions

- **WHEN** a learner completes a lesson practice for the first time
- **THEN** the lesson's target expressions appear in the notebook with bar 0.

#### Scenario: due items resurface

- **WHEN** an expression's due date passes
- **THEN** it enters the recall queue until recalled correctly enough to
  reach the top bar.

#### Scenario: old archives keep decoding

- **WHEN** an archive written before this change is loaded
- **THEN** it decodes with an empty expression notebook and no data loss.

### Requirement: Vocabulary notebook

The learner SHALL be able to save looked-up words and expressions into a
notebook, see their recall bars, and remove entries. Saving SHALL be
idempotent on the same text.

#### Scenario: saving a lookup

- **WHEN** the learner saves a looked-up expression already saved before
- **THEN** the notebook keeps one entry for it.
