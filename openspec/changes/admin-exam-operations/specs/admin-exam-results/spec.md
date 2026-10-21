# admin-exam-results Specification

## ADDED Requirements

### Requirement: Exam results listing

The system SHALL expose `GET /v1/admin/level-test-attempts` joining `level_test_attempts` with `level_test_attempt_snapshot_ids` (authoritative `languageID`, `level`, `weekId`, `source` from its metadata) and `accounts` (email), returning items `{accountId, attemptId, email, examId, source, languageID, level, weekId, state, startedAt, updatedAt, completedAt, overall, passed, sections}` — without `answers` or the per-question `questionReviews` — with filters `account` (uuid), `email` (contains), `language`, `level`, `state` ∈ {in_progress, completed}, `passed` (bool), `week_from`/`week_to`, `from`/`to` (over `started_at`), a sort whitelist {started_at, updated_at, state, overall} defaulting to `started_at DESC`, and `limit`/`offset` pagination with `total`.

#### Scenario: Listing shows student attempts with content metadata
- **WHEN** an account has completed an attempt whose snapshot id metadata names a language, level and week
- **THEN** the listing item carries the account email, the attempt ids and that content metadata.

#### Scenario: Filter by student and email substring
- **WHEN** the endpoint is called with `account=<uuid>` or `email=<substring>`
- **THEN** only that account's attempts (or attempts whose email contains the substring) are returned.

#### Scenario: Filter by language, level, state and passed
- **WHEN** the endpoint is called with `language=en&level=A1&state=completed&passed=true`
- **THEN** only matching attempts are returned.

#### Scenario: Filter by week and date ranges
- **WHEN** `week_from`/`week_to` or `from`/`to` are provided
- **THEN** the ranges apply inclusively over the content week and `started_at`.

#### Scenario: Malformed query values
- **WHEN** `state` is not in_progress|completed, `passed` is not a boolean, `account` is not a uuid, `overall` is not sortable, or pagination is out of range
- **THEN** the response is `400 invalid_request`.

### Requirement: Attempt detail

The system SHALL expose `GET /v1/admin/level-test-attempts/:accountId/:attemptId` returning the full attempt including `answers` and `result` (with `questionReviews`), with `404 level_test_attempt_not_found` when the pair does not exist or belongs to a different account.

#### Scenario: Detail includes per-question review
- **WHEN** the detail endpoint is called for a completed attempt
- **THEN** the response includes `answers` and `result.questionReviews`.

#### Scenario: Cross-account access is not possible
- **WHEN** the detail endpoint is called with an accountId and an attemptId of a different account
- **THEN** the response is `404 level_test_attempt_not_found`.

### Requirement: Results summary and evolution

The system SHALL expose `GET /v1/admin/level-test-attempts/summary` computing, over completed attempts, `{total, completed, passed, avgOverall}` plus a breakdown `{key, total, completed, passed, avgOverall}` grouped by content week (`group=week`) or level (`group=level`), honouring the same language/level filters.

#### Scenario: Weekly evolution breakdown
- **WHEN** `group=week` is requested
- **THEN** groups contain one entry per content week with its counts and average overall.

### Requirement: CSV export

The system SHALL expose `GET /v1/admin/level-test-attempts/export` producing the filtered listing as CSV (`text/csv`, attachment) with header `attempt_id,account_id,email,exam_id,source,language,level,week,state,started_at,completed_at,overall,passed`, at most 10 000 rows (`413 export_too_large` beyond), and every cell starting with `=`, `+`, `-` or `@` escaped with a leading `'`.

#### Scenario: Export escapes formula prefixes
- **WHEN** a cell value starts with `=` (or `+`, `-`, `@`)
- **THEN** the CSV cell is prefixed with `'`.

#### Scenario: Export cap
- **WHEN** the filtered selection exceeds 10 000 rows
- **THEN** the response is `413 export_too_large`.

### Requirement: Per-student view

The panel SHALL provide a per-student exam view — the same listing filtered by `account` (or `email`) with the student identified in the header — and the accounts browse table SHALL offer a "Ver exámenes" context action navigating to that view with the filter preloaded.

#### Scenario: Ver exámenes from accounts browse
- **WHEN** an operator picks "Ver exámenes" on an accounts row
- **THEN** the panel navigates to the results section filtered by that account.

### Requirement: Results privacy

Results endpoints SHALL be admin-only (`adminOnly` + page Origin check + `no-store`), SHALL NOT log request bodies or student answers, and SHALL NOT audit reads; mutations remain audited.

#### Scenario: Unauthenticated access is refused
- **WHEN** a results endpoint is called without an admin session or with a foreign Origin
- **THEN** the response is 401/403 and no data leaks.
