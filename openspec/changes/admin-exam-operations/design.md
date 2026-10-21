# Design: Admin exam operations

## Key decisions

### Regeneration is restricted to the current, unpublished week
Only `weekIndex(now)` keys regenerate. If an `approved` row exists for the key the endpoint answers `409 level_test_week_not_regenerable`: approved exams can already have student attempts linked to their snapshot ids, and the content trigger makes week content immutable anyway. `draft`/`rejected` rows or a missing row proceed with `force: true`, which retires the live row and inserts a fresh one (existing `persistWeek` semantics). Validated server-side, not merely hidden in the UI.

### Regeneration runs async in-process
The route validates, audits, then starts `runLevelTestGenerationJob(db, env, {languageID, level, weekIndex, force: true})` without awaiting and answers `202 {started: true}`. The job module already serializes CLI, scheduler and admin runs behind a per-key advisory lock and persists `level_test_generation_jobs` state (`running|finished|failed|unknown`), so a crash lands as `failed`, never an unhandled rejection (`void job.catch(() => {})`). Progress is observed through the jobs monitor, not the request.

### Zero schema changes
All reads reuse existing tables and indexes. `level_test_generation_jobs` primary key `(language_id, level, week_index)` is the monitor's natural identity.

### Listings exclude heavy JSONB
`pool`, `variants`, `review`, `answers`, `result` (with `questionReviews`) appear only in detail endpoints. Listings select explicit columns — never `SELECT *` — so payloads stay small and predictable.

## Endpoints

| Route | Type | Notes |
|---|---|---|
| `GET /v1/admin/level-tests/weeks` | read | `status`, `language`, `level` (CSV), `week_from`/`week_to` (int), `created_from`/`created_to` (ISO date), `sort` ∈ {created_at, week_index, language_id, level, status, approved_at}, `dir` ∈ {asc, desc}, `limit` 1-100 (default 50), `offset` ≤ 100 000. → `{items, total, limit, offset}` metadata only. |
| `GET /v1/admin/level-tests/weeks/:id` | read | uuid; 404 `level_test_week_not_found`. Full pool/variants/review. |
| `POST /v1/admin/level-tests/regenerate` | mutation | `{languageID, level, weekIndex?, reason}`; reason 3-500; audited `level_test.regenerate`; 202 `{started: true}`. |
| `GET /v1/admin/level-tests/status` | read | unchanged shape + `jobs[]` (last 10 by updated_at). |
| `GET /v1/admin/level-test-attempts` | read | join `level_test_attempts` + `level_test_attempt_snapshot_ids` + `accounts`; filters `account`, `email` (contains), `language`, `level`, `state`, `passed`, `week_from`/`week_to`, `from`/`to` (started_at); sort ∈ {started_at, updated_at, state, overall}; pagination. |
| `GET /v1/admin/level-test-attempts/summary` | read | KPIs over completed attempts + `group=week|level` breakdown. |
| `GET /v1/admin/level-test-attempts/export` | read | CSV of the filtered selection, cap 10 000 rows (413 `export_too_large`), formula-prefix escaping (`=+-@` → `'`), `text/csv` attachment. |
| `GET /v1/admin/level-test-attempts/:accountId/:attemptId` | read | full answers + result; 404 `level_test_attempt_not_found`; account-scoped (no cross-account leak). |

## Regeneration sequence

```
Panel → POST /regenerate {languageID, level, reason}
  → validate weekIndex == weekIndex(now)          [else 400 invalid_request]
  → SELECT status FROM level_test_weeks WHERE key  [approved → 409]
  → INSERT admin_actions 'level_test.regenerate'    (reason 3-500)
  → 202 {started:true}                              (returns immediately)
  → runLevelTestGenerationJob(force:true)           (advisory lock; jobs row running→finished/failed)
Panel → GET /status {jobs[]} every 5s while running
```

## Privacy and audit

Reads are un-audited (consistent with browse) but gated by `adminOnly` + page origin + `no-store`; mutating routes carry CSRF + mandatory reason + `recordAdminAction`. No request bodies, tokens or student answers are logged (existing server rule). Guests never sync attempts, so no extra filtering is needed.
