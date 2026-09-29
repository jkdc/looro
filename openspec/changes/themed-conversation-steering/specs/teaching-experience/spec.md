# Delta Spec: teaching-experience

## ADDED Requirements

### Requirement: Themed conversation steering

When a conversation theme is selected, the session instructions SHALL present
the theme as the conversation's anchor: the tutor SHALL be instructed to keep
the conversation revolving around that theme, to explore the different
concrete situations the theme suggests, and to gently steer back when the
chat drifts too far, using that variety to deepen the learner's command of
the language. The opening-greeting instruction SHALL ask its first question
from inside the theme situation, and a mid-session theme change SHALL
re-anchor the live conversation the same way. Without a theme the
instructions SHALL keep the free-conversation default. The wording SHALL be
identical on both platforms.

#### Scenario: theme reaches the session instructions

- **WHEN** a theme is chosen and a voice session starts
- **THEN** the session instructions contain the theme anchor block and the
  steering sentence, and do not contain the free-conversation line, on both
  platforms.

#### Scenario: first question opens the theme

- **WHEN** a themed session opens
- **THEN** the greeting instruction asks for a question that steps into the
  theme situation; without a theme the greeting keeps its generic question.

#### Scenario: mid-session switch re-anchors

- **WHEN** the learner picks a different theme while the session is active
- **THEN** the appended instructions command anchors the conversation in the
  new situation with the same steering.
