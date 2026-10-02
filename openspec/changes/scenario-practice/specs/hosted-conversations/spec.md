# Delta Spec: hosted-conversations

## ADDED Requirements

### Requirement: Scenario review helper purpose

The hosted helper gateway SHALL accept `scenario_review` as a purpose, with
the same rules as the assessment helper: a bounded client JSON schema is
allowed and web search is forbidden. The scenario review SHALL be admissible
in the post-close window (session closed, within the post-session allowance),
alongside meaning, lookup and assessment. Every purpose row remains one-shot,
budgeted and content-free at rest.

#### Scenario: review rides the post-close window

- **WHEN** a hosted scenario session closes and the client requests
  `scenario_review` with a schema within the post-session window
- **THEN** the gateway admits it under the session's helper budget, and
  rejects conversation purposes (topic, typed_reply, help, delegation) as
  before.

#### Scenario: the purpose check and the catalog stay pinned

- **WHEN** the server migrates an empty database
- **THEN** the `hosted_helper_requests.purpose` CHECK admits exactly the
  helper purposes in `HOSTED_HELPER_PURPOSES`.
