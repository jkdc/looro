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

### Requirement: Live generation progress without polling in the dark

While a generation started from the panel, or one already `running` when the
view mounts, is in flight, the panel SHALL poll
`GET /v1/admin/level-tests/status` every 5 seconds. A failed poll SHALL be
retried on the next tick, never aborting the watch. The watch SHALL stop on the
first terminal state of every watched key (`finished`, `failed`, `unknown`),
refresh the weeks listing once, and issue no further polls. Leaving the
generation view SHALL park the watch (no timer, no requests); returning SHALL
resume it and resolve the pending keys.

#### Scenario: Cadence is one poll every 5 seconds
- **WHEN** a watched run is in flight
- **THEN** consecutive status requests are ~5 seconds apart, not the 2 seconds of the previous fixed timer.

#### Scenario: One failed poll does not end the watch
- **WHEN** a status request fails with a server error
- **THEN** the next request still happens one cadence later and the run keeps being tracked.

#### Scenario: Polling stops at the terminal state
- **WHEN** the last watched key reports `finished`
- **THEN** the operator sees the completion line and no further status requests are made.

#### Scenario: Leaving the view stops background requests
- **WHEN** the operator navigates away while a run is in flight
- **THEN** at most the already-scheduled tick runs, and no timer keeps polling afterwards.

#### Scenario: Returning resumes the watch
- **WHEN** the operator comes back to the generation view with a parked watch
- **THEN** polling resumes, the pending keys resolve, and the watch stops again.

## MODIFIED Requirements

### Requirement: On-demand generation launcher

The system SHALL expose `POST /v1/admin/level-tests/generate` accepting
`{languageID, levels?, weekIndex?, reason}` (levels defaulting to all CEFR
levels; reason 3-500 chars), validating language, levels and week against
whitelists, answering `202 {started: true, keys[]}`, auditing accepted requests
as `level_test.generate`, and running the started keys sequentially in the
background through the job runner. Each key SHALL be `started`,
`skipped_approved` (an approved week is never regenerated here) or
`skipped_existing` (any other week already exists for that language, level and
week: draft, rejected or retired). Skipped keys SHALL NOT create a generation
job, so the operator never sees a finished job for content that was not
generated; replacing a draft or a rejected week goes through the explicit
regeneration operation, which is force and refuses approved keys.

#### Scenario: An approved week is skipped
- **WHEN** the launcher is called with `levels: 'all'` for a language with one approved level
- **THEN** the response marks that key `skipped_approved`, the other four keys `started`, and exactly the started keys get a generation job.

#### Scenario: An existing draft is skipped instead of "finishing" without content
- **WHEN** the launcher is called for a level whose week already exists as `draft` or `rejected`
- **THEN** the response marks it `skipped_existing`, no job row is created for it, no provider call is made, and the panel reports it as already existing with a pointer to «Regenerar».

#### Scenario: A missing week still launches
- **WHEN** the launcher is called for a level with no week for the current week
- **THEN** the key is `started` and its generation job runs through the runner as before.
