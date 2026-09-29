# Delta Spec: teaching-experience

## MODIFIED Requirements

### Requirement: Course library with authored lessons

The app SHALL offer structured courses for a learning language, organized as
ordered units of authored lessons (a grammar point or expression set with a
communicative goal). Course content SHALL be embedded data shared across
platforms from a single canonical source with a drift check. Lessons SHALL be
freely navigable (no unlocking). Only authored lessons SHALL be offered.
Every unit SHALL carry an internal level label in the canonical source,
validated by the exporter and carried by both platforms; the label SHALL NOT
be presented to learners until the content passes competent-speaker review.

#### Scenario: course appears for its language

- **WHEN** the learning language has a course and the learner opens the
  Learn tab
- **THEN** the course, its units, and its authored lessons are listed with
  the learner's attempt state.

#### Scenario: content cannot drift between platforms

- **WHEN** the generated course data on one platform differs from the
  canonical source
- **THEN** the export check fails.

#### Scenario: units carry a level label

- **WHEN** a unit in the canonical source lacks a non-empty `level`
- **THEN** the exporter refuses to generate content.

#### Scenario: the Foundations temario is complete

- **WHEN** the English Foundations course is loaded
- **THEN** it exposes six units of three authored lessons each, following
  the syllabus fixed in `informe-cursos-2026-09-21.md` §7.
