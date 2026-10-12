# Delta Spec: level-tests-coverage

## ADDED Requirements

### Requirement: Full language and level grid

Weekly level-verification tests SHALL be generable for all 8 supported
languages (en, es, fr, de, it, pt, nb, zh) across all 5 CEFR levels
(A1–C1) = 40 weekly tests. The generation prompt SHALL name the target
language correctly (not default to English for non-Spanish languages).

#### Scenario: French B2 generation

- **WHEN** the weekly generation runs for language=fr, level=B2
- **THEN** the generated items are in French, at B2 difficulty, and pass
  gate 1 (single correct answers, section minimums, disjoint passages).

### Requirement: Extended language heuristic

The gate-1 validator SHALL include target-language stopword markers for
all supported languages so that items written in the wrong language are
rejected (CJK character presence for zh).

#### Scenario: wrong-language rejection

- **WHEN** a generated item's content is predominantly in a language other
  than the target
- **THEN** the language check rejects the item.
