# Proposal: Admin exam operations

## Intent

Give operators a complete exam operations surface in the admin panel: a full, filterable listing of every generated exam week (all statuses), regeneration of the current week's unpublished exams, and per-student exam results with summary and CSV export.

## Problem

The panel only lists `draft`/`rejected` weeks through `/v1/admin/level-tests/drafts`. Approved and retired generations are invisible, there is no regeneration path outside the CLI, and no admin endpoint exposes `level_test_attempts`, so operators cannot review what students took or how they scored.

## Approach

- New read endpoints `GET /v1/admin/level-tests/weeks` (filters, sort whitelist, pagination) and `GET /v1/admin/level-tests/weeks/:id` (full content).
- New mutation `POST /v1/admin/level-tests/regenerate`: current week only, blocked with 409 when the key is already approved; runs `runLevelTestGenerationJob(force: true)` un-awaited with the existing advisory lock; audited with a mandatory reason.
- `GET /v1/admin/level-tests/status` gains `jobs[]` (last 10) while keeping `latestJob`.
- New results surface: list, detail, summary and CSV export over `level_test_attempts` joined with `level_test_attempt_snapshot_ids` (authoritative language/level/week) and `accounts`; per-student view reachable from the accounts browse table.
- UI: replace the private drafts card with a "Exámenes generados" card (filters, badges, actions) and add a "Resultados de exámenes" section.

## Out of scope

Editing exam content; per-section regeneration; regenerating approved or past weeks; blue-green parallel drafts; Android/iOS changes; new migrations; new public endpoints; business-level analytics.
