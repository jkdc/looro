# Delta Spec: android-conversation-engine

## ADDED Requirements

### Requirement: Configurable speech rate

The learner SHALL be able to choose the pace of the app's spoken audio from a
set of discrete rates (very slow, slow, normal, fast). The choice SHALL
govern the on-device synthesizer for session TTS and the lesson card reader,
and SHALL inform the tutor's pace instruction through the prompt. Legacy
archives without the choice SHALL behave exactly as before.

#### Scenario: learner picks a slower pace

- **WHEN** the learner selects a rate below normal
- **THEN** on-device spoken audio plays at the selected rate and the tutor
  prompt carries the slow-pace instruction.

#### Scenario: legacy archives keep their behaviour

- **WHEN** an archive without a rate choice is loaded
- **THEN** the effective rate is normal, or slow when the legacy
  "slower speech" flag is set.
