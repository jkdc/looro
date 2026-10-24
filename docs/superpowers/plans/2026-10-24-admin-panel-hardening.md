# Admin Panel Hardening Implementation Plan

> **For agentic workers:** Follow `superpowers:executing-plans` and the project's ODD ledger. Implement each task in order; tests precede production edits.

**Goal:** Implement the confirmed findings in `informe-panel-administracion-2026-10-23.md` without changing business policy or rewriting the static admin application.

**Architecture:** Preserve the server as authority. Fix transactional intent and API compatibility first, add a small explicit view lifecycle, then correct shell/accessibility and generation correlation. Extract only proven responsibilities into local ES modules after behavioral coverage exists.

**Tech Stack:** Node.js 22+, Fastify/TypeScript/PostgreSQL 17, static HTML/CSS/ES modules, existing Tabler/Tabulator/Chart.js, repository browser E2E with Playwright Core.

**Spec:** `openspec/changes/admin-panel-hardening/specs/admin-panel/spec.md`; design: `docs/superpowers/specs/2026-10-24-admin-panel-hardening-design.md`.

## Global Constraints

- Preserve server authorization, audit reasons, validation, transaction semantics, query allowlists, generation locks, and current `202` meaning.
- Keep current route hashes, local vendors, static asset delivery and CSP; do not add a framework, bundler, CDN, or dependency.
- Keep generation optional and do not alert or infer a failure from absent exams.
- Do not change behavior because of extraction alone; do not implement section 9 conditional product ideas without approval.
- Use synthetic fixtures and disposable PostgreSQL only; no production data, live account, real provider, push, or merge.
- Use Superpowers, never Gentle AI; commit on the active branch and do not ask about PR chaining. Do not create worktrees.
- All user-facing interface text stays consistent with existing Spanish UI; code/comments remain English.

## Review Focus

- A campaign apply timeout after server acceptance must not create/apply a second grant.
- Navigation during a pending read must not allow stale DOM, toast, or listener writes.
- A stored generation UUID may not exist at the instant the existing detached launch route returns `202`; do not invent or prematurely expose it.
- Modal close/Escape cancels rather than confirms, and focus returns to the actual invoking control.
- CSP and script ordering must still allow static ES modules and local vendor globals.

---

### Task 0: Baseline and implementation contract

**Files:**
- Create: `openspec/changes/admin-panel-hardening/verify-report.md`
- Update: `openspec/changes/admin-panel-hardening/tasks.md`
- Preserve: `informe-panel-administracion-2026-10-23.md` and its three screenshot annexes.

**Interfaces:** Uses the current isolated API/browser runners documented in `services/api/tests/browser/README.md`. Produces a verified baseline before behavior changes.

- [x] Run `make test-api` before changes; baseline output is 640 tests passed, 0 failed.
- [x] Run documented browser runner `node services/api/tests/browser/admin-e2e.mjs`; baseline is 57 checks passed, runner printed `T11 browser E2E: all checks passed.`
- [x] Record evidence limits: this baseline is Chromium + disposable PostgreSQL/stub provider and does not certify manual assistive technology or Firefox/WebKit.
- [x] Commit the report, synthetic audit annexes, design/spec/plan and this baseline as one documentation-only work unit.
- [ ] For each behavior task, add its failing regression immediately before implementation and observe RED; do not land standalone test-only commits that leave the branch failing.
- [x] Commit: `603f2d4` (`docs(admin): establish panel hardening baseline`).

### Task 1: Campaign snapshot and browse query contracts

**Files:**
- Modify: `services/api/admin-ui/app.js` or the owning extracted module if created in a later task.
- Modify: `services/api/tests/browser/admin-e2e.mjs`.
- Modify: `services/api/tests/admin-browse.test.ts` or the existing browse route test discovered during implementation.

**Interfaces:** Consumes server browse catalog (`operators`, `orderable`, `defaultOrder`) and existing campaign prepare/apply responses; no endpoint contract change.

- [x] Test campaign prepare then edit each intent-affecting field and assert apply is unavailable until re-prepare; assert uncertain apply never produces a second prepare/apply request. After uncertainty, form controls lock while the same campaign can be explicitly retried.
- [x] Test audit `action`, ledger `kind`, and non-orderable `is_guest` against the real catalog fixtures.
- [x] Implement snapshot identity/impact display and catalog-derived filter/sort controls; disable unsupported sorting and use remote sort mode.
- [x] Run focused browser E2E (63 checks passed) and `make test-api` (640/640).
- [x] Commit: `386b9a3` (`fix(admin): preserve campaign and browse intent`).

### Task 2: Validation, login and operator error states

**Files:**
- Modify: `services/api/admin-ui/app.js`, `login.js`, `login.html`, `index.html` as needed.
- Modify: `services/api/tests/browser/admin-e2e.mjs`.

**Interfaces:** Uses existing API error contract and form controls. No API business validation change.

- [x] Test that invalid numeric/reason forms issue zero requests and identify the invalid field.
- [x] Test bearer network rejection, recoverable retry, and absence of token in error text/URL.
- [x] Test a successful mutation followed by failed refresh remains a confirmed mutation with a distinct retryable stale/read state.
- [x] Label global result KPIs explicitly without changing the result API or pretending they are selection-scoped.
- [x] Add persistent accessible load/empty/error/retry feedback and truthful API connectivity status.
- [x] Run focused Chromium browser E2E: 65 checks passed. Existing API endpoints for policy/plans were exercised through the real API in this suite; no backend contract code changed.
- [x] Commit: `6f42b2c` (`fix(admin): make validation and errors recoverable`).

### Task 3: View lifecycle and resource ownership

**Files:**
- Create: `services/api/admin-ui/view-context.js` (loaded as a same-origin classic script to preserve the existing CSP and script model).
- Modify: `services/api/admin-ui/app.js` and affected browser tests.

**Interfaces:** A view context exposes current/disposed state, read cancellation, resource/listener/timer registration and idempotent `dispose()`. Existing router remains the route authority.

- [x] Test resource registration/disposal exactly once and late `isCurrent()` rejection.
- [x] Test browse reads resolving after navigation, and exam-weeks/browse requests resolving after newer same-route requests.
- [x] Test timer/listener cleanup and no rendering after disposal.
- [x] Implement the minimal context and use it in dashboard, exam-weeks and browse; cancellation applies only to reads, never server mutations.
- [x] Run JavaScript syntax checks and the isolated Chromium panel suite (69/69 checks passed).
- [x] Commit: `adca0e1` (`fix(admin): bind async work to view lifecycle`).

### Task 4: Responsive shell and accessibility

**Files:**
- Modify: `services/api/admin-ui/index.html`, `app.css`, `app.js`, `login.html`, `login.js` as relevant.
- Modify: `services/api/tests/browser/admin-e2e.mjs`.

**Interfaces:** Retain the existing navigation group IDs, route hashes, local assets and server behavior.

- [x] Test mobile menu/sign-out at 390px, route selection and accessible button names.
- [x] Test reason/detail dialog accessible name/role, focus containment, Escape cancellation, and focus return.
- [x] Test form labels/errors, `<main>` landmark, `aria-current`, visible row actions by keyboard/touch, and non-color state copy.
- [x] Test toast/status announcements and text/table summaries for rendered charts.
- [x] Implement CSS/layout using existing Tabler patterns; no canvas upload or new design framework.
- [x] Run browser suite and manually inspect screenshots at narrow and desktop widths; do not claim full WCAG certification. E2E passed all 11 groups; visual inspection covered 390px mobile navigation and 1280px dashboard.
- [x] Commit: `feat(admin): improve responsive accessible operations` (`9efe1c0`) plus focus-cancellation correction (`41590a6`).

### Task 5: Durable generation-run correlation

**Files:**
- Modify only as needed: `services/api/src/http/routes/admin-level-tests.ts`, `src/level-tests-admin.ts`, generation status query/model, admin UI/types, migrations only if the existing UUID cannot serve.
- Modify: relevant API PostgreSQL tests and browser E2E fixtures.

**Interfaces:** Existing generation run UUID and attempt ordinal in migration 049 are the starting identity. Preserve launcher `202`, job locks, audit and current manual/automatic rules. Prefer additive exact-ID status lookup to a new job table.

- [x] Reproduce prior-terminal, delayed job registration, >10 unrelated job eviction, reload, and concurrent same-key runs against isolated PostgreSQL.
- [x] Specify how the UI obtains an exact run ID only after persistence confirms it; represent accepted-but-unregistered identity as pending/unknown without claiming terminal state. `runCorrelationId` is only a lookup token; the persisted run ID and ordinal appear only when the exact query returns a database row.
- [x] Add exact-ID lookup separate from the recent jobs array; ensure old client shape remains valid. Optional `runIds` adds `trackedRuns`; requests without it retain the original status response shape.
- [x] Correlate UI terminal state and reload recovery to exact UUID/ordinal; persist active correlation tokens in tab session storage and clear each only after its persisted run reaches terminal state.
- [x] Verify concurrency and backward compatibility with API integration tests (26/26), browser E2E (74 checks), typecheck and `make test-api` (644/644).
- [x] Commit: `fix(admin): correlate generation status to persisted runs` (`8c76dbb`). No migration was needed; migration 049 already provides a UUID and attempt ordinal per run.

### Task 6: Incremental organization and operational design

**Files:**
- Create only tested modules under `services/api/admin-ui/core/`, `ui/`, or `features/`.
- Modify: `index.html`, `app.css`, admin docs, E2E comments/expectations, and screenshots/evidence.

**Interfaces:** ES modules preserve current globals only where required by vendors; entrypoint/hash/routes and CSP stay stable.

- [x] Identify code with a proven independent responsibility and move it without changing behavior; do not create empty folders or move the whole 1,300-line file mechanically. Extracted chart-card rendering, chart lifecycle and resize handling into `features/dashboard-charts.js`.
- [x] Improve dashboard hierarchy using existing operator tasks and label units/periods/statuses; no unapproved KPI, alert, RBAC or global search. Grouped existing KPIs into three operational areas and clarified current-vs-period context; no KPI/query changes.
- [x] Align stale browser-test comments, operator help, footer audit claim, and admin documentation with observed behavior. Clarified exact generation run identity/pending semantics; replaced the overbroad footer audit statement with mutations-only wording.
- [x] Verify module loading, static asset paths, CSP, route inventory, query results and existing behavior. Browser E2E: 77 checks passed; same-origin module and CSS returned HTTP 200 under `script-src 'self'`; existing routes/queries and syntax checks passed. Inspected 1280px dashboard screenshot; prior T4 screenshot evidence covers responsive navigation at 390px.
- [x] Commit: `refactor(admin): extract tested panel responsibilities` (`PENDING`).

### Task 7: Closeout

**Files:**
- Create: `openspec/changes/admin-panel-hardening/verify-report.md`.
- Modify: `openspec/changes/admin-panel-hardening/tasks.md`, `odd/tasks/admin-panel-hardening.md`.

- [ ] Run focused checks per task, full documented browser E2E, affected PostgreSQL suites and `make test-api` for API contract changes.
- [ ] Run TypeScript check if server code changed and JavaScript syntax/module/CSP smoke checks.
- [ ] Obtain independent high-risk verification of the campaign and generation units; fix blockers only within the authorized scope and re-run their tests.
- [ ] Record exact outputs, failed/unavailable/skipped/manual checks, screenshots, reviewed commit boundary, and known limitations.
- [ ] Commit: `docs(admin): record hardening verification`.

## Commit boundaries

Each task is one reversible work unit, with its tests and documentation. Keep all work-unit commits on `feat/admin-panel-hardening`; the user explicitly rejected PR-chain questions. The branch may exceed 400 lines without slicing; never push, create a PR, or merge without a separate user request.
