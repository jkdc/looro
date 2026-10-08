# Delta Spec: level-tests

## ADDED Requirements

### Requirement: One weekly verification test per level

For each language and CEFR level with a valid pool, the system SHALL offer a
level-verification test whose items are recomposed every ISO week from a
deterministic seed over the level's pool. Two consecutive weeks SHALL not
present substantially the same test, and the composition SHALL be identical
on every device for a given week.

#### Scenario: weekly rotation

- **WHEN** the ISO week changes
- **THEN** the composed test for that level differs from the previous week's
  beyond a bounded item overlap, without any network access.

### Requirement: Full-spectrum exam with fixed section counts

The test SHALL cover vocabulary, grammar, reading, listening (spoken
stimuli) and writing in fixed section counts of roughly 25 items, taking
10–15 minutes, without feedback during the exam and with a final review
showing every item's verdict and explanation.

#### Scenario: exam flow

- **WHEN** a learner completes all sections
- **THEN** the review lists each item with its verdict and explanation
  before the result is shown.

### Requirement: Directional result

Grading SHALL combine an overall threshold with per-section floors. Passing
SHALL suggest readiness for the next CEFR band with a level-jump action that
never fixes the learner's choice. Failing SHALL suggest the weak sections
and the specific units of wrong unit-tagged items to reinforce.

#### Scenario: passed

- **WHEN** the overall percent and every section floor are met
- **THEN** the result suggests readiness for the next band and offers the
  jump without changing the level by itself.

#### Scenario: failed

- **WHEN** any floor is missed
- **THEN** the result lists the weak sections and the units to reinforce.


### Requirement: Weekly endpoint serves the same composition

The backend SHALL expose `GET /v1/level-tests/current?language=&level=`
composing the weekly exam with the same deterministic rotation as the apps
(locked by a shared golden vector asserted on both sides), answering with the
full item content, thresholds, `weekId` and `expiresAt` (next Monday UTC),
ETag/304 revalidation, and proxy-exempt availability like other static
content. Unknown languages or levels SHALL answer 404; missing parameters
400. Grading SHALL stay on device.

#### Scenario: cached client revalidation

- **WHEN** a client repeats the request within the same ISO week presenting
  the returned ETag
- **THEN** the server answers 304 without a body until the week rolls over.
### Requirement: Always-visible results

The last result per level SHALL remain visible on the level's entry card
(date, overall percent, pass state), and every attempt SHALL be recorded as
an `assessment` ledger completion (slug-safe id) so results sync without any
database schema change.

#### Scenario: card persistence

- **WHEN** the learner returns to the Learn tab after an attempt
- **THEN** the level card shows the last attempt's date, percent and pass
  state.
