# Delta Spec: android-conversation-engine

## ADDED Requirements

### Requirement: Scenario session mode

The conversation engine SHALL support a `mode="scenario"` session that reuses
the existing transport, consent, provider selection, per-turn assessments and
typed-reply machinery. Typed replies inside a scenario session SHALL stay
anchored to that situation. Ending a scenario session SHALL follow the same
closing path as other voice sessions (final assessment, telemetry, translation
flush).

#### Scenario: scenario session behaves like a first-class mode

- **WHEN** a scenario session starts on any engine (openai, deepseek, glm)
- **THEN** the instructions are the scenario practice prompt, the opening
  command starts Phase 1 without waiting, and per-turn assessments still run.

### Requirement: Prompt-injection hygiene for learner-sourced situations

The learner's free-text situation SHALL be sanitized (single line, control and
format characters removed, hard length cap) before it is interpolated into any
prompt, and SHALL always be framed as data, never instructions.

#### Scenario: control characters cannot reshape the prompt

- **WHEN** the learner's situation description contains newlines, tabs or
  control characters
- **THEN** the sanitized goal contains none of them and is capped at 120
  characters.
