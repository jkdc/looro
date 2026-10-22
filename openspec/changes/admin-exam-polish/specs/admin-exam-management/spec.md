# Admin exam operations polish (delta)

## ADDED Requirements

### Requirement: Severity-coded exam states

The panel SHALL colour exam week and generation job states by severity instead
of a single neutral badge. `approved` and `finished` SHALL render green,
`rejected` and `failed` red, `draft` and `unknown` yellow, `running` blue and
`retired` neutral. Only state badges carry these fills; identifiers such as an
error code SHALL use an outline.

#### Scenario: A failed job is visually distinct from a finished one
- **WHEN** the job monitor contains a `failed` and a `finished` job
- **THEN** the failed row's state badge is red and the finished row's is green.

#### Scenario: Week moderation is visible in the listing
- **WHEN** a week is approved or rejected
- **THEN** its state badge in the weeks listing is green or red respectively.

### Requirement: Explainable generation failures

The API SHALL persist the reasons of the last generation attempt in
`level_test_generation_jobs.error_details` and expose them as `errors: string[]`
on `GET /v1/admin/level-tests/status`, keeping `errorCode` as the coarse code.
The stored entries SHALL be limited to messages the pipeline writes itself:
lowercase, at most 160 characters, at most 10 entries, with provider bodies,
stack traces and user content rejected. The panel SHALL make the error code a
button that opens a dialog with the code, the reasons, the run context
(language, level, week, attempts, timestamps) and the next step.

#### Scenario: Section-level reason survives the write
- **WHEN** generation fails because a section produced too little content
- **THEN** the status endpoint returns `errorCode: "generation_failed"` and `errors` containing that section's message, and the dialog shows it verbatim.

#### Scenario: Provider payloads never reach the database
- **WHEN** an error string looks like a provider body, a stack trace or user text
- **THEN** the writer drops it and falls back to the coarse code.

#### Scenario: Every red row can be opened
- **WHEN** the operator clicks an error code in the job monitor
- **THEN** a dialog explains the failure and offers the next step.

### Requirement: Unambiguous sidebar sections

Every sidebar entry SHALL belong to a titled section and each heading SHALL be a
non-interactive separator. The sections SHALL be `Operación`, `Exámenes`
(`#exam-weeks`, `#exam-results`, `#ia-config`), `Comercial` (`#plans`,
`#campaigns`) and `Sistema` (`#browse`, `#policy`, `#audit`).

#### Scenario: Exam entries are visually contained
- **WHEN** the operator scans the sidebar
- **THEN** the three exam entries sit between the `Exámenes` heading and the `Comercial` heading, and no non-exam entry is inside that range.

#### Scenario: Headings are not links
- **WHEN** the operator clicks a section heading
- **THEN** nothing navigates and no entry becomes active.
