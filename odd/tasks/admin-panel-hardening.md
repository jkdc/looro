# Admin Panel Hardening

Objective: implement the findings in `informe-panel-administracion-2026-10-23.md`.
Branch: `feat/admin-panel-hardening`; delivery: `exception-ok` (user-directed commits on this branch; no PR unless requested); runner: API `make test-api`, isolated browser E2E documented in `services/api/tests/browser/README.md`.
Forecast: exceeds 400 authored changed lines (the audit alone is 531 lines, with additional tests, implementation, and verification docs); generated screenshot binaries excluded. User explicitly prefers current-branch commits and no PR-chain questions.

## Specs

### S1 — Preserve prepared campaign intent
User scope: “implementa los cambios detallados en el documento desde una nueva rama usando sdd de superpowers”. Follow audit H01. Editing the audience/account/amount/cap/reason after prepare invalidates the exact prepared snapshot; the final confirmation identifies its impact; uncertain apply retains identity and never triggers a duplicate grant. Check: browser E2E plus isolated PostgreSQL outcome/idempotency coverage.

### S2 — Match browse controls to API contract
Follow H02. Filters/sorters derive from server `operators`, `orderable`, and default order; no incompatible `contains`, non-orderable sorter, or local re-sorting of a remote page. Check: catalog-driven browser tests and API query integration cases.

### S3 — Own view lifecycle
Follow H03/H04. Mounted views own requests, timers, listeners, Tabulator/Chart instances; stale responses cannot paint, announce, or replace a newer route; every resource is disposed exactly once. Check: controlled response-order browser tests and disposal assertions.

### S4 — Make failures and form outcomes explicit
Follow H05/H08/H10/H13. Invalid forms send no request; network failures and load/refresh errors are accessible and recoverable; a confirmed mutation is never described as failed merely because its subsequent refresh failed; errors never become empty/healthy status. Check: browser/API tests for invalid values, offline, 4xx/5xx and post-mutation refresh failure.

### S5 — Communicate scope and improve operator hierarchy
Follow H12/H14. Global KPIs say they are global; text explains statuses and chart data; existing groups and routes remain; remove only redundancies that obstruct operator tasks. Do not add conditional features in report section 9 absent user authorization. Check: browser assertions and visual screenshots at narrow and desktop widths.

### S6 — Responsive and accessible operation
Follow H06/H07/H09. Navigation/sign-out remain available at narrow viewport; dialogs trap/contain focus, have accessible semantics, Escape cancellation and focus return; controls have programmatic names and row actions do not depend on context-menu gestures. Check: keyboard/role-based E2E; document manual AT/browser limits.

### S7 — Correlate generation to persisted execution
Follow H11. Use existing persisted run UUID and attempt ordinal; exact status lookup/recovery must survive bounded recent-history eviction and distinguish unknown/not registered from terminal. Preserve detached 202 and concurrency locks; no job-table migration unless inspected evidence requires it. Worker remains optional; do not infer failure from absent exams. Check: isolated PostgreSQL concurrency + browser stale-terminal/reload/>10-unrelated-jobs scenarios.

### S8 — Extract only proven frontend boundaries
Follow sections 3 and 6 of the report. Keep static assets, CSP, local vendors, existing hashes/API contracts. Extract only after the target behavior is tested and view ownership is explicit; no framework/bundler. Check: route inventory, module loading under CSP, browser suite.

### S9 — Keep documentation truthful and verify completely
Follow H15 and phases 0–6. Align E2E comments, help/docs, and final verification report. Preserve no exam-absence alerts and report all skipped/unavailable/manual checks. Check: all checks prescribed in the OpenSpec task document and final `verify-report.md`.

## Tasks

- T0 / S1–S9 / inline / complete / commit `603f2d4` — baseline and specification artifacts; baseline API 640/640, browser 57 checks.
- T1 / S1–S2 / inline / complete / commit `386b9a3` — campaign snapshot invalidation, explicit same-ID retry after uncertain apply, and server-catalog browse filters/sorters. RED: E2E reproduced enabled stale apply, sortable `is_guest`, and rejected server-filter operators; lock-specific RED reproduced the ability to prepare a second campaign during uncertainty. GREEN: browser E2E 63/63; `make test-api` 640/640.
- T2 / S4–S5 / inline / complete / commit `6f42b2c` — client validation, bearer network recovery, API connectivity, persistent read retry, confirmed mutation/failed refresh distinction, and global results scope.
- T3 / S3–S4 / inline / complete / commit `adca0e1` — lifecycle/disposal and latest-read ordering.
- T4 / S5–S6 / inline / complete / commits `9efe1c0`, `41590a6` — responsive accessibility; browser E2E passed all 11 groups and screenshots were inspected at 390px/1280px.
- T5 / S7 / inline / complete / commit `8c76dbb` — persisted UUID/attempt ordinal lookup separate from the ten-job feed; correlation token in `202`, exact terminal status and reload recovery; unregistered requests stay pending/unknown. API integration 26/26; browser E2E 74 checks; `make test-api` 644/644.
- T6 / S5,S8,S9 / inline / complete / commit `60c5113` — tested dashboard-chart module extraction, grouped KPIs, and corrected operator wording.
- T7 / S1–S9 / inline / complete / commits `2057d25` + documentation closeout — fixed five Important review findings, completed focused independent recheck, full E2E and API verification; one Minor remains explicitly deferred.

## Log

### L1 — Original request
“implementa los cambios detallados en el documento desde una nueva rama usando sdd de superpowers”

### L2 — Scope decisions
- The audit is the approved source of requirements; implement its phased recommendations, not the conditional feature ideas in section 9.
- Keep the static UI and current server authority. Do not use worktrees, remote services, live credentials, production data, push, or merge.
- Preserve optional generation and the explicit choice not to alert on missing exam output.
- Code changes are high risk for campaign effects and generation concurrency; independent verification is required before closeout.

### L3 — Progress
- Created `feat/admin-panel-hardening` from `feature/learning-platform` at `30990b0`.
- OpenSpec and Superpowers design artifacts created before production-code edits. T0 baseline and T1 campaign/browse work are complete; continue with T2.
- The report, plans, screenshots, and pre-existing untracked files remain preserved. User overrode the default PR-chain prompt: continue with work-unit commits on the active branch, and do not create a PR without a later explicit request.
- Baseline observed before implementation: `make test-api` reported 640 passed / 0 failed; documented browser E2E reported 57 passing checks. Both ran against disposable local services.
- The browser runner's default evidence path overwrites tracked screenshots; those generated files were restored because the initial worktree had no tracked changes. Future E2E runs must set `E2E_EVIDENCE_DIR` to the ignored SDD workspace.

### L4 — User workflow requirement
“no me preguntes más esto, siempre haces commits sobre la rama donde estamos trabajando y no quiero que uses gentle-ai quiero que uses superpowers añadelo como requisito de desarrollo al proyecto”
- Use Superpowers only; commit work units on the currently active branch; do not use worktrees or ask about PR chaining; push/PR/merge remain unauthorized unless separately requested.

### L5 — T1 campaign and browse integrity
- RED: the browser suite reproduced stale campaign apply after editing audience/account/minutes/cap/reason and exposed sorting for non-orderable `is_guest`. Additional RED checks showed that uncertain apply left the form available to prepare a second campaign.
- GREEN: prepared identity/confirmation/reason remain bound to the snapshot; the impact summary includes recipients and total minutes. Editing invalidates apply. An uncertain response retains the same campaign ID for explicit retry and locks intent fields/new preparation until success. Browse filter operator options come directly from API `operators`; unsupported controls/operators are omitted, sortable columns use `orderable`, and remote sort mode keeps pagination semantics aligned.
- Verification: browser E2E passed 63/63 checks in Chromium against disposable PostgreSQL and the stub provider; full `make test-api` passed 640/640. One earlier browser run exposed a flaky pre-existing generation timer check; a fresh complete run passed it. Browser evidence is under `.superpowers/sdd/2026-10-24-admin-panel-hardening/evidence-t1-final3/`.
- T1 work-unit commit: `386b9a3` (`fix(admin): preserve campaign and browse intent`). Next: continue T2.

### L6 — T2 validation and operator error states
- RED: browser tests reproduced bearer network failures without accessible feedback, ambiguous static API status, missing global-result scope, and invalid forms sending requests to the server rather than identifying fields locally.
- GREEN: login bearer handling catches network failures, re-enables retry, and never echoes credentials; shared validation identifies invalid fields and blocks requests; API badge reflects reachable/unreachable fetches; route and table failures expose persistent accessible retry states without turning stale rows into healthy/empty results; confirmed policy updates remain explicitly confirmed when refresh fails; result KPIs state their global scope.
- Verification: browser E2E passed 65/65 checks on the disposable stack, including failure/retry paths. JavaScript syntax checks and `git diff --check` passed. Existing API contracts were exercised in E2E; no backend code changed. Browser evidence is under `.superpowers/sdd/2026-10-24-admin-panel-hardening/evidence-t2-green2/`.
- T2 work-unit commit: `6f42b2c` (`fix(admin): make validation and errors recoverable`). Next: continue T3.

### L7 — T3 view lifecycle and resource disposal
- RED: browser E2E reproduced (1) missing lifecycle ownership/disposal, (2) a pending browse route that delayed navigation, (3) older exam-week results repainting over a newer filter, and (4) the parked generation watcher failing to resume because a cleared timer ID remained globally truthy.
- GREEN: added `AdminViewContext` with abortable route reads, idempotent cleanup, listener/timer/resource registration and current-view checks. Dashboard, exam-weeks and browse reads now avoid writes after disposal; exam-weeks and browse discard superseded responses. Browse now registers Tabulator instances in the router-owned collection, so navigation destroys them. Generation mutations remain non-abortable; outcomes are only announced while the initiating view remains current, and watcher state can resume on return.
- Verification: isolated Chromium browser E2E passed 69/69 checks; `node --check` passed for `app.js`, `view-context.js`, and `admin-e2e.mjs`; `git diff --check` passed. Evidence is under `.superpowers/sdd/2026-10-24-admin-panel-hardening/evidence-t3-final/` and the complete run is `.superpowers/sdd/2026-10-24-admin-panel-hardening/task-3-tests.log`.
- T3 work-unit commit: `adca0e1` (`fix(admin): bind async work to view lifecycle`). The context stays a same-origin classic script next to `app.js` because the panel deliberately uses classic scripts and CSP disallows inline/module changes; no bundler or import rewrite is needed. Next: continue T4.

### L8 — T4 responsive accessibility
- RED: the browser E2E reproduced that cancelling the reason dialog failed to restore focus. The moderation action disabled its trigger before opening the modal, which blurred the invoker before the dialog saved its return-focus target.
- GREEN: defer disabling the moderation trigger until a valid reason is confirmed; cancellation leaves the trigger focusable and restores focus correctly. Existing T4 browser coverage also exercises mobile navigation/sign-out, dialog semantics/focus trap/Escape, field labels/errors, main landmark, route state, row actions, non-color state text, live announcements and chart data tables.
- Verification: isolated Chromium E2E passed all 11 groups (`T11 browser E2E: all checks passed`); `node --check services/api/admin-ui/app.js` and `git diff --check` passed. Inspected ignored screenshots `evidence-t4-green/mobile-menu-open-390.png` (390px) and `15-overview.png` (1280px); no full WCAG certification claimed. Evidence and full log are under `.superpowers/sdd/2026-10-24-admin-panel-hardening/evidence-t4-green/` and `task-4-tests.log`.
- T4 commits: `9efe1c0` (`feat(admin): improve responsive accessible operations`) and `41590a6` (`fix(admin): preserve focus when cancelling moderation`).
- Next: continue T5 durable generation-run correlation.

### L9 — T5 durable generation-run correlation
- RED: isolated PostgreSQL tests showed the `202` response had no request-specific identity and exact lookup data was absent; browser E2E reproduced missing reload recovery for an accepted generation. The test suite also caught a fixture parameter-type error before the intended exact-window failure; the fixture was corrected and the regression test then failed on the missing `trackedRuns` behavior as expected.
- GREEN: each accepted manual launch gets a UUID correlation token. It is passed to the existing runner and only becomes the reported `runId` after the `level_test_generation_jobs` row is persisted. Optional `/status?runIds=...` performs exact UUID lookup independently of the recent ten, returns `pending` when no row is registered, and adds persisted `attemptOrdinal`; old status consumers retain their shape when `runIds` is absent. The panel tracks exact identities in session storage across reload, never adopts a prior terminal job by language/level/week, and surfaces long-unregistered requests as unknown instead of false terminal results.
- No migration/table was needed: migration 049 already has per-run UUIDs and attempt ordinals. Existing advisory locks, detached `202`, sequential multi-key launch, automatic/manual policies and audit event remain in place.
- Verification: API integration tests 26/26; isolated browser E2E 74 checks including stale prior attempt, delayed registration, exact run after reload, and poll-resilience cases; `node --check` for panel/E2E JS; `npm run check` passed; full `make test-api` passed 644/644. Browser evidence is `.superpowers/sdd/2026-10-24-admin-panel-hardening/evidence-t5-green2/`; task logs are in the ignored SDD workspace.
- T5 commit: `8c76dbb` (`fix(admin): correlate generation status to persisted runs`). Next: continue T6 incremental organization and operational design.

### L10 — T6 incremental organization and operational dashboard
- RED: new browser assertions failed on the baseline because the overview had no operational grouping and the footer claimed every action was audited. The first extraction run exposed a module initialization error (`Cannot access 'escapeHtml' before initialization`); chart renderer construction referenced a later-initialized formatter. Moved module construction after formatter initialization; direct Chromium readback then showed API connected and all three groups rendered.
- GREEN: extracted chart-card rendering, Chart.js creation/resize/disposal into `services/api/admin-ui/features/dashboard-charts.js`, imported it through the same-origin ES-module entry, and retained existing chart markup/data tables/lifecycle. Reorganized only existing snapshot KPIs into Users/Activity, Service/Consumption, and Commercial/Support sections with current/period context; no new metrics or queries. Footer now scopes the audit claim to mutations. Updated operator docs to describe exact run UUID/ordinal resolution, pending registration, and tab-reload tracking; corrected browser-run comments.
- Verification: isolated browser E2E passed 77/77; checks verified module and CSS HTTP 200, existing `script-src 'self'` CSP, routes, query results and behavior. `node --check` passed for app, extracted module and browser tests. Screenshot `evidence-t6-green3/02-dashboard.png` was visually inspected at 1280px; T4 had already covered 390px responsive navigation. The first E2E attempt was against a previously mutated disposable database and was not used as green evidence; the stack was recreated and the clean-seed run passed.

### L11 — T7 independent review corrections and closeout
- Fixed five Important findings from the independent review: stale campaign preparation now compares intent versions and serializes prepares; uncertain campaign identity and retry reason are saved before apply and recovered across route remount/reload; custom Tabulator filters disable vendor live filtering and omit empty values; policy/plans refresh guards stale DOM work without aborting post-write reads; generation polling batches IDs sequentially at 25.
- Added browser regressions for delayed prepare/edit, same-ID campaign recovery after remount and reload, actual filter typing/clear, late failed refresh after navigation, and 26 run IDs (25+1). The current clean-seed browser E2E passed all checks: `E2E_EVIDENCE_DIR=.superpowers/sdd/2026-10-24-admin-panel-hardening/evidence-t7-final node services/api/tests/browser/admin-e2e.mjs`.
- Verification: `make test-api` 644/644; `cd services/api && npm run check`; `node --check` on app.js and browser E2E; `git diff --check`. Focused independent reviewer recheck on `60c5113..2057d25` passed all five blockers and reproduced them on baseline; it was not a new whole-branch review.
- Intermediate browser attempts failed only because the first stack had prior test mutations and later regression harness setup was incomplete. The stack was reset and harness corrected; the complete final run passed. Firefox/WebKit, manual screen-reader testing, and real provider were not exercised.
- Minor deferred: exam-review reason-dialog cancellation may lose focus because the trigger is disabled before modal invocation. It was deliberately excluded from the bounded blocker correction; see verification report.
- T6 work-unit commit: `60c5113` (`refactor(admin): extract tested panel responsibilities`). T7 correction commit: `2057d25` (`fix(admin): resolve hardening review blockers`). Documentation closeout commit pending.
- Risk: item 4 (async state ordering and mutation/refresh lifecycle). Next: commit the report/tracker closeout; no merge, push, PR, or worktree.
