# admin-exam-management Specification

## ADDED Requirements

### Requirement: Complete exam week listing

The system SHALL expose `GET /v1/admin/level-tests/weeks` returning every `level_test_weeks` row (any of `draft|approved|rejected|retired`) as metadata items `{id, languageID, level, weekId, status, judgeMode, generator, model, variantCount, tokensIn, tokensOut, createdAt, approvedAt, approvedBy}` with `{items, total, limit, offset}`, without pool, variants or review.

#### Scenario: Filter by status CSV
- **WHEN** the endpoint is called with `status=approved,draft`
- **THEN** only rows whose status is in that set are returned.

#### Scenario: Filter by language, level and week range
- **WHEN** the endpoint is called with `language=en,es`, `level=A1`, `week_from=202640`, `week_to=202650`
- **THEN** only rows for those languages and levels with `week_index` between `week_from` and `week_to` (inclusive) are returned.

#### Scenario: Filter by creation date range
- **WHEN** the endpoint is called with `created_from=2026-10-01` and `created_to=2026-10-21`
- **THEN** only rows with `created_at` inside the inclusive date range are returned.

#### Scenario: Sort whitelist
- **WHEN** the endpoint is called with `sort=week_index&dir=asc`
- **THEN** items are ordered by `week_index` ascending.
- **WHEN** `sort` names a column outside the whitelist
- **THEN** the response is `400 invalid_request`.

#### Scenario: Pagination
- **WHEN** the endpoint is called with `limit=1&offset=1`
- **THEN** `items` holds the second newest row and `total` counts every matching row regardless of the window.

#### Scenario: Malformed query values
- **WHEN** any of `limit` (0, 101, non-integer), `offset` (negative, > 100000), `week_from`/`week_to` (non-integer), `status`/`language`/`level` (unknown value) is provided
- **THEN** the response is `400 invalid_request`.

#### Scenario: Admin-only and uncacheable
- **WHEN** the endpoint is called without an admin session or with a foreign Origin
- **THEN** the response is rejected (401/403) and every success response carries `cache-control: no-store`.

### Requirement: Exam week detail

The system SHALL expose `GET /v1/admin/level-tests/weeks/:id` returning the full row (metadata plus `pool`, `variants`, `review`) for a valid uuid, with `404 level_test_week_not_found` when the row does not exist.

#### Scenario: Detail returns full content
- **WHEN** the detail endpoint is called with the id of a seeded week
- **THEN** the response includes the same metadata as the listing plus `pool`, `variants` and `review`.

#### Scenario: Unknown week
- **WHEN** the id does not exist
- **THEN** the response is `404 level_test_week_not_found`.

### Requirement: Restricted regeneration

The system SHALL expose `POST /v1/admin/level-tests/regenerate` accepting `{languageID, level, weekIndex?, reason}` (reason 3-500 chars), refusing with `409 level_test_week_not_regenerable` any key with an `approved` row or a `weekIndex` other than `weekIndex(now)`, refusing malformed bodies with `400 invalid_request`, auditing accepted requests as `level_test.regenerate`, and answering `202 {started: true}` while `runLevelTestGenerationJob` runs with `force: true` behind its advisory lock without being awaited by the request.

#### Scenario: Regenerate current draft week
- **WHEN** the current week key has a `draft` row and the request is well-formed
- **THEN** the response is `202 {started: true}`, an audit entry `level_test.regenerate` stores the reason, and a generation job runs for that key.

#### Scenario: Regenerate an approved week is refused
- **WHEN** the key has an `approved` row
- **THEN** the response is `409 level_test_week_not_regenerable` and no audit entry is written.

#### Scenario: Regenerate a past week is refused
- **WHEN** `weekIndex` differs from `weekIndex(now)`
- **THEN** the response is `400 invalid_request`.

#### Scenario: Reason is mandatory and audited
- **WHEN** `reason` is shorter than 3 or longer than 500 chars
- **THEN** the response is `400 invalid_request` and no job starts.

### Requirement: Generation jobs monitor

The system SHALL extend `GET /v1/admin/level-tests/status` with `jobs[]`, the last 10 `level_test_generation_jobs` rows ordered by `updated_at DESC` (same item shape as `latestJob`), while keeping `latestJob` unchanged for compatibility.

#### Scenario: Jobs list reflects recent runs
- **WHEN** several generation jobs have been recorded
- **THEN** `jobs[]` contains the last 10 rows newest-first and `latestJob` still names the most recent one.

### Requirement: Drafts endpoint compatibility

The system SHALL keep `GET /v1/admin/level-tests/drafts` unchanged in behavior.

#### Scenario: Drafts still list draft and rejected rows
- **WHEN** the drafts endpoint is called
- **THEN** it returns the same shape and filtering as before this change.
