# Admin Exam Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Panel de administración de exámenes: listado completo de exámenes con filtros, regeneración de la semana actual no publicada, y resultados de exámenes por alumno.

**Architecture:** Nuevos módulos server (`level-tests-admin.ts`, `level-tests-attempts-admin.ts`) con la lógica de query/parsing; rutas thin en `app.ts` siguiendo el patrón existente (`adminOnly` + `assertAdminPageOrigin` + `no-store` en lecturas, `assertAdminCsrf` + auditoría en mutaciones). La UI SPA (`admin-ui/app.js`) reutiliza sus helpers (`api()`, `detail()`, `ask()`, Tabulator/Chart.js). Cero migraciones.

**Tech Stack:** Node 22 + TypeScript + Fastify + PostgreSQL 17; panel vanilla JS + Tabulator.

**Spec:** `informe-admin-exam-operations-2026-10-21.md` (propuesta validada) + `openspec/changes/admin-exam-operations/` (se escribe en Task 1; las specs SHALL son la autoridad de los requisitos).

## Global Constraints

- Código y comentarios en inglés; docs de producto en español.
- NO new migrations: reuse `level_test_weeks`, `level_test_generation_jobs`, `level_test_attempts`, `level_test_attempt_snapshot_ids`, `accounts`.
- Parameterized SQL only (`$1…$n`); never interpolate filter values.
- List endpoints return metadata only — heavy JSONB (`pool`, `variants`, `review`, `answers`, `result`, `questionReviews`) ONLY in detail endpoints.
- Reads: `assertAdminPageOrigin(request)` + `await adminOnly(request)` + `reply.header('cache-control', 'no-store')`. Mutations: `assertAdminCsrf(request)` + admin + `reason` string 3-500 audited via `recordAdminAction`.
- Drafts endpoint `/v1/admin/level-tests/drafts` and `latestJob` in `/status` stay unchanged.
- `weekIndex(now)` semantics: `services/api/src/level-tests.ts` exports `weekIndex`; the current-week key is `(languageID, level, weekIndex(now))`.
- Test harness: real PG via `TEST_DATABASE_URL` (pattern of `tests/admin-usage.test.ts`); `make test-api` runs the whole suite.
- UI: no build step; `node --check services/api/admin-ui/app.js` must pass; CSP forbids inline handlers — attach listeners via JS only.

## Review Focus

1. Malformed query values (bad CSV, non-integer week_from, offset out of range) → 400 `invalid_request`, never 500.
2. Filter values reaching SQL unparameterized (injection) → every dynamic fragment is whitelisted enums (status/sort/columns), values always `$n`.
3. Regenerate on an approved key or past week → 409 `level_test_week_not_regenerable`; audit still written only on accepted mutations.
4. Un-awaited regeneration job crashing the process → `.catch(() => {})` with job state `failed` persisted by the job runner, never an unhandled rejection.
5. CSV export formula/CSV injection (cell starting with `=`, `+`, `-`, `@`) and oversize export → prefix-guard cells and cap at 10 000 rows.

---

### Task 1: openspec change documentation

**Files:**
- Create: `openspec/changes/admin-exam-operations/proposal.md`
- Create: `openspec/changes/admin-exam-operations/design.md`
- Create: `openspec/changes/admin-exam-operations/tasks.md`
- Create: `openspec/changes/admin-exam-operations/specs/admin-exam-management/spec.md`
- Create: `openspec/changes/admin-exam-operations/specs/admin-exam-results/spec.md`

**Interfaces:**
- Produces: the SHALL specs the implementation tasks argue from (listing, regeneration 409s, jobs monitor, results/summary/export, per-student view, privacy).

- [ ] **Step 1: Write the five documents** following the format of `openspec/changes/t11-admin-operations/` (proposal: Intent/Problem/Approach/Out of scope in English; specs with `Requirement:`/`Scenario:` SHALL/WHEN-THEN blocks). Content comes verbatim-in-spirit from `informe-admin-exam-operations-2026-10-21.md` sections 3-5, 7, 8.
- [ ] **Step 2: Verify structure** — `ls openspec/changes/admin-exam-operations/` shows the five files; each spec has ≥1 Requirement block.
- [ ] **Step 3: Commit** — `docs(admin-exam-operations): openspec change spec`

### Task 2: Weeks listing endpoint (Feature A backend)

**Files:**
- Create: `services/api/src/level-tests-admin.ts`
- Modify: `services/api/src/app.ts` (route registration near the level-tests admin routes ~line 478)
- Create: `services/api/tests/level-tests-admin.test.ts`

**Interfaces:**
- Consumes: `Database` from `./db.js`, `ServiceError` from `./errors.js`.
- Produces: `parseWeekListQuery(query: Record<string, unknown>): WeekListQuery` (throws `ServiceError('invalid_request')` on bad values); `listLevelTestWeeks(db: Database, q: WeekListQuery): Promise<{ items: WeekMeta[]; total: number; limit: number; offset: number }>` where `WeekMeta = { id, languageID, level, weekId, status, judgeMode, generator, model, variantCount, tokensIn, tokensOut, createdAt, approvedAt, approvedBy }`.

- [ ] **Step 1: Write failing integration tests** in `tests/level-tests-admin.test.ts` (harness per `admin-usage.test.ts`): seed `level_test_weeks` rows in all 4 states; assert:
  - default listing returns only metadata fields and orders by `created_at DESC`;
  - `status=approved,draft` CSV filter; `language=en,es`; `level=A1`; `week_from`/`week_to` inclusive; `created_from`/`created_to` (ISO date) inclusive on `created_at`;
  - sort whitelist `sort=week_index&dir=asc` works; unknown sort column → 400;
  - `limit=1&offset=1` pagination with `total` stable;
  - `offset=-1`, `limit=0`, `limit=101`, non-integer `week_from`, unknown `status` value → 400 `invalid_request`;
  - route requires admin: no cookie → 401; wrong Origin → 403; response has `no-store`.
- [ ] **Step 2: Run tests — Expected: FAIL** (`node --test --test-name-pattern` against compiled dist is not available pre-build; run via `npx tsx --test services/api/tests/level-tests-admin.test.ts` with `TEST_DATABASE_URL` set to the smoke database or local `_test` db). Expected: import/list failures.
- [ ] **Step 3: Implement `parseWeekListQuery` + `listLevelTestWeeks`** — dynamic WHERE built only from whitelisted segments; ORDER BY column from whitelist map; SELECT excludes `pool`, `variants`, `review`.
- [ ] **Step 4: Run tests — Expected: PASS.**
- [ ] **Step 5: Commit** — `feat(level-tests): admin weeks listing endpoint with filters`

### Task 3: Week detail endpoint

**Files:**
- Modify: `services/api/src/level-tests-admin.ts`
- Modify: `services/api/src/app.ts`
- Modify: `services/api/tests/level-tests-admin.test.ts`

**Interfaces:**
- Produces: `levelTestWeekDetail(db: Database, id: string): Promise<WeekDetail | undefined>` — `WeekDetail = WeekMeta & { pool, variants, review }`; route `GET /v1/admin/level-tests/weeks/:id` (uuid validated, 404 `level_test_week_not_found` when missing).

- [ ] **Step 1: Failing test** — detail of a seeded week returns pool/variants/review; unknown uuid → 404; malformed uuid → 400.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement** (module + route).
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Commit** — `feat(level-tests): admin week detail endpoint`

### Task 4: Feature A UI — "Exámenes generados" card

**Files:**
- Modify: `services/api/admin-ui/app.js` (`renderLevelTestOperations`)

**Interfaces:**
- Consumes: `GET /v1/admin/level-tests/weeks` (Task 2), `GET .../weeks/:id` (Task 3), existing drafts approve/reject endpoints, `detail()`/`ask()` helpers.
- Produces: card id `exam-weeks-card` with filter bar (status multi-select, language, level, week range, created range), Tabulator-free simple table with status badges (existing BADGE_COLORS), pagination (prev/next + total), row actions.

- [ ] **Step 1: Implement** — replace the drafts card with the weeks listing; keep "Ver" (detail modal), "Aprobar"/"Rechazar" for `draft` rows (existing endpoints + `ask()`), per-row rendering from `items[]`.
- [ ] **Step 2: Verify** — `node --check services/api/admin-ui/app.js` → OK.
- [ ] **Step 3: Commit** — `feat(admin-ui): generated exams listing with filters`

### Task 5: Regeneration endpoint + jobs monitor (Feature B backend)

**Files:**
- Modify: `services/api/src/level-tests-admin.ts`
- Modify: `services/api/src/app.ts`
- Modify: `services/api/tests/level-tests-admin.test.ts`

**Interfaces:**
- Produces: `validateRegenerateRequest(db: Database, languageID: string, level: string, weekIndex: number, now: Date): Promise<void>` (throws `ServiceError('level_test_week_not_regenerable', 409)` if an `approved` row exists for the key or `weekIndex !== weekIndex(now)`; passes when `draft`/`rejected` row or none). Route `POST /v1/admin/level-tests/regenerate` body `{languageID, level, weekIndex?, reason}` → validation, audit `level_test.regenerate`, then `void runLevelTestGenerationJob(db, process.env, {languageID, level, weekIndex, force: true}).catch(() => {})` → `202 {started: true}`. `GET /status` gains `jobs[]` (last 10 by updated_at, same shape as `latestJob`, keeps `latestJob`).
- Consumes: `runLevelTestGenerationJob` from `./level-tests-generation-jobs.js`.

- [ ] **Step 1: Failing tests** — with current week seeded `draft`: POST → 202 `{started:true}`, audit row exists; with `approved` row → 409 `level_test_week_not_regenerable` and NO audit; `weekIndex` different from current → 400/409 per spec (400 `invalid_request`); reason <3 chars → 400; body with extra keys → 400 `invalid_request`; unauthenticated → 401; missing CSRF origin → 403.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement** — note: the async job call must not be awaited inside the request handler; in tests assert the jobs table row transitions via the real job module only for the draft-ok case (transport stubbed via a fake `resolveLlmConfig` failure is acceptable: job ends `failed` — assert state exists, not success).
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Commit** — `feat(level-tests): regenerate current unpublished week + jobs monitor`

### Task 6: Feature B UI — regenerate button + jobs polling

**Files:**
- Modify: `services/api/admin-ui/app.js`

**Interfaces:**
- Consumes: `POST /v1/admin/level-tests/regenerate`, `status.jobs[]` (Task 5), `ask()` helper, current `weekId` from `weeks` listing.

- [ ] **Step 1: Implement** — "Regenerar" button on rows whose `weekId === currentWeek` and status `draft|rejected`; modal `ask('Regenerar examen', row.id)` with mandatory reason; after 202, poll `status.jobs[]` every 5 s while any job is `running`, re-render jobs table; stop polling on completion.
- [ ] **Step 2: Verify** — `node --check` → OK.
- [ ] **Step 3: Commit** — `feat(admin-ui): regenerate current week with job monitor`

### Task 7: Attempts listing endpoint (Feature C backend)

**Files:**
- Create: `services/api/src/level-tests-attempts-admin.ts`
- Modify: `services/api/src/app.ts`
- Create: `services/api/tests/level-tests-attempts.test.ts`

**Interfaces:**
- Produces: `parseAttemptListQuery(query): AttemptListQuery`; `listLevelTestAttempts(db, q): Promise<{ items: AttemptMeta[]; total; limit; offset }>` where `AttemptMeta = { accountId, attemptId, email, examId, source, languageID, level, weekId, state, startedAt, updatedAt, completedAt, overall, passed, sections }`. SQL: `level_test_attempts a JOIN level_test_attempt_snapshot_ids s ON s.account_id=a.account_id AND s.exam_id=a.exam_id JOIN accounts acc ON acc.id=a.account_id`; language/level/week from `s.metadata_json`; `source` from `s.metadata_json->>'source'`. Filters: `account` (uuid), `email` (contains), `language`, `level`, `state`, `passed`, `week_from`/`week_to`, `from`/`to` on started_at; sort whitelist (started_at, updated_at, state, overall) default `started_at DESC`.
- Excludes `answers`/`result` questionReviews.

- [ ] **Step 1: Failing tests** — seed accounts + snapshot_ids + attempts (completed with `result` jsonb and in_progress); assert: listing metadata only, email contains filter, language/level/state/passed filters, week/date ranges, sort, pagination, 400 on bad inputs, auth/origin/no-store.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Commit** — `feat(level-tests): admin exam results listing`

### Task 8: Attempt detail endpoint

**Files:**
- Modify: `services/api/src/level-tests-attempts-admin.ts`, `services/api/src/app.ts`, `services/api/tests/level-tests-attempts.test.ts`

**Interfaces:**
- Produces: `levelTestAttemptDetail(db, accountId, attemptId)` → full row (`answers` + `result` complete) or 404 `level_test_attempt_not_found`.

- [ ] **Step 1: Failing test** — detail returns answers + result with questionReviews; unknown attempt → 404; another account's id pair → 404 (no cross-account leak).
- [ ] **Step 2: Run — FAIL.** — [ ] **Step 3: Implement.** — [ ] **Step 4: PASS.** — [ ] **Step 5: Commit** — `feat(level-tests): admin attempt detail`

### Task 9: Summary endpoint (KPIs + weekly evolution)

**Files:**
- Modify: `services/api/src/level-tests-attempts-admin.ts`, `services/api/src/app.ts`, `services/api/tests/level-tests-attempts.test.ts`

**Interfaces:**
- Produces: `levelTestAttemptSummary(db, query: { language?: string; level?: string; group?: 'week'|'level' })` → `{ completed: number; total: number; passed: number; avgOverall: number | null; groups: { key: string; total: number; completed: number; passed: number; avgOverall: number | null }[] }` — overall/passed from `result` jsonb of completed attempts only.

- [ ] **Step 1: Failing test** — seeded mixed data: counts, average, grouped by week then by level.
- [ ] **Step 2: FAIL → Step 3: implement → Step 4: PASS → Step 5: Commit** — `feat(level-tests): exam results summary and evolution`

### Task 10: CSV export endpoint

**Files:**
- Modify: `services/api/src/level-tests-attempts-admin.ts`, `services/api/src/app.ts`, `services/api/tests/level-tests-attempts.test.ts`

**Interfaces:**
- Produces: `attemptCsv(db, q: AttemptListQuery): Promise<string>` — same filters as Task 7, hard cap 10 000 rows (413 `export_too_large` beyond); header row `attempt_id,account_id,email,exam_id,source,language,level,week,state,started_at,completed_at,overall,passed`; cells prefixed with `'` when starting with `=+-@`; response `Content-Disposition: attachment; filename=...` and `text/csv`.

- [ ] **Step 1: Failing test** — CSV matches header + rows, formula-prefix escaping, cap returns 413.
- [ ] **Step 2: FAIL → Step 3: implement → Step 4: PASS → Step 5: Commit** — `feat(level-tests): exam results CSV export`

### Task 11: Feature C UI — "Resultados de exámenes" section

**Files:**
- Modify: `services/api/admin-ui/app.js` (new `renderAttemptResults`, NAV entry, VIEWS entry)
- Modify: `services/api/admin-ui/index.html` if section chrome requires it

**Interfaces:**
- Consumes: Tasks 7-10 endpoints. Produces: hash route `#exam-results` with filter bar (email, language, level, state, passed, week/date ranges), table with badges, pagination; "Ver" → detail modal with section score rows and questionReviews table; per-student mode when `#exam-results?account=<uuid>`: header with student email; summary card (KPIs from Task 9); "Exportar CSV" button (fetch + blob download); browse accounts context action "Ver exámenes" → `location.hash = '#exam-results?account=' + id`.

- [ ] **Step 1: Implement.** — [ ] **Step 2: `node --check` → OK.** — [ ] **Step 3: Commit** — `feat(admin-ui): exam results section with student view and CSV export`

### Task 12: Full verification + verify-report

**Files:**
- Create: `openspec/changes/admin-exam-operations/verify-report.md`

- [ ] **Step 1:** `npx tsc --noEmit` (services/api) → clean.
- [ ] **Step 2:** `make test-api` → full suite green; record counts.
- [ ] **Step 3:** `node --check services/api/admin-ui/app.js` → OK.
- [ ] **Step 4:** Write `verify-report.md` with what was verified and what could not be (UI manual flows).
- [ ] **Step 5: Commit** — `docs(admin-exam-operations): verify report`
