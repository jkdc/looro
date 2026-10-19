# Swift Exam Engine Requirements

## Requirements

### Requirement: Generated Swift banks stay synchronized with canonical JSON

Placement and weekly level-test Swift data MUST be generated from the canonical
`shared/placement/*.json` and `shared/leveltests/*.json` sources. Each exporter's
`--check` mode MUST reject stale or missing Swift output without modifying files.

#### Scenario: Freshness is checked for all target platforms

- **WHEN** both exporters run with `--check`
- **THEN** Kotlin, Swift, and API generated outputs match their validated inputs
- **AND** a stale Swift output makes the check fail.

### Requirement: Placement behavior matches the deterministic Android contract

Swift placement MUST use the same five CEFR bands, 18-answer cap, three-answer
ladder window, accepted-answer grading, and result categories/confidence semantics.

#### Scenario: Three correct A1 answers move the ladder up

- **WHEN** a learner answers the next three A1 core items correctly
- **THEN** the resulting core band is A2.

### Requirement: Weekly composition matches the cross-platform contract

Swift MUST compose the same items and passage IDs as Android and API for a given
language, level, content version, and ISO week. Reading and listening passages
within one exam MUST be disjoint. Grading MUST enforce overall and section floors.

#### Scenario: Shared golden vector

- **WHEN** English B1 is composed for ISO week 202641
- **THEN** item order and reading/listening passage IDs match
  `shared/leveltests/weekly-goldens.json`.

#### Scenario: ISO week year boundary

- **WHEN** the date is 2021-01-01 UTC
- **THEN** the week identifier is 202053.

### Requirement: Placement preferences are isolated from the learning archive

Placement results MUST be addressable by language, and the instruction-language
choice MUST be limited to `subtitles`, `en`, or `es`. These settings MUST NOT alter
the existing shared archive schema.

#### Scenario: Unsupported instruction language

- **WHEN** a preference is created with an unsupported instruction language
- **THEN** it defaults to `subtitles`.
