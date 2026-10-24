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
- T2 / S4–S5 / inline / pending / commit pending — form validation and errors.
- T3 / S3–S4 / inline / pending / commit pending — lifecycle/disposal.
- T4 / S5–S6 / inline / pending / commit pending — responsive accessibility.
- T5 / S7 / inline / pending / commit pending — run correlation.
- T6 / S5,S8,S9 / inline / pending / commit pending — extraction and polish.
- T7 / S1–S9 / inline / pending / commit pending — independent review and closeout.

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
