# Delta Spec: ai-feedback

## ADDED Requirements

### Requirement: AI report submission on iPhone

The iPhone app SHALL let the learner report an assistant passage when the
backend advertises AI reports: a report carries a fresh `reportID`, the
learning language, one of the reasons `offensive | incorrect |
wrong_language | other`, an editable excerpt of at most 2000 characters
prefilled with the passage text, and the consent version
`ai-report-v1`. Submission SHALL be credential-free and SHALL treat HTTP
202 with the echoed `reportID` as success, offering retry on transient
failures.

#### Scenario: learner reports an assistant answer

- **WHEN** the learner flags an assistant passage, picks a reason, edits the
  excerpt, accepts the consent notice and submits
- **THEN** the app posts the report without credentials and confirms the
  submission with the echoed report identifier.
