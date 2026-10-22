# Admin exam operations UX — split config from generation, readable exam review

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (already chosen: native, inline).

**Goal:** Separate the LLM provider configuration from the day-to-day exam generation surface, and replace the raw-JSON exam modal with a full-page review screen an operator can actually read and approve.

**Architecture:** Vanilla JS admin panel (no build step) served by Caddy from `services/api/admin-ui/`. Three views replace the current mixed `#level-tests`: `#exam-weeks` (launch, jobs, listing, moderation), `#ia-config` (provider credentials), and `#exam/<uuid>` (exam review). The review page renders the already-persisted `pool`/`variants`/`review` JSONB — no new content model, only one small read-model enrichment on the detail endpoint.

**Tech Stack:** TypeScript (Fastify) for the API, vanilla ES2022 for the panel, Tabler CSS, Playwright (browser E2E, 26 checks), node:test for API tests.

**Spec:** `openspec/changes/admin-exam-ux/specs/admin-exam-management/spec.md`

## Global Constraints

- No build step in `admin-ui/`: plain `app.js` + `app.css`, loaded with a version query. Bump `app.js?v=7` and `app.css?v=6` in `index.html`.
- All copy for the panel is Spanish; code and comments in English.
- Every admin mutation keeps its mandatory reason (`ask()` dialog) and stays audited.
- Generated files (`*Data.kt`, `*Data.swift`) are never touched by this change.
- Detail endpoint keeps returning only what the operator needs; list endpoints stay metadata-only.
- Text from the LLM must be escaped with `escapeHtml()` before interpolation, no exceptions.

## Review Focus

- An exam with an empty option list or empty `answers` (written items) must render without crashing.
- A variant whose `passages` is missing or empty must still render its items.
- Items whose `passageId` points to a passage absent from the variant (truncated content) must render as standalone items.
- `review` shorter than the pool (judge flagged only part of it) must not break the verdict map.
- Opening `#exam/<unknown-uuid>` must show a clear 404 state, never a blank page or a spinner forever.

---

### Task 1: Enrich the week detail read model

**Files:**
- Modify: `services/api/src/level-tests-admin.ts` (`levelTestWeekDetail`, `WeekDetail`)
- Test: `services/api/tests/level-tests-admin.test.ts`

**Interfaces:**
- Consumes: `weekRow(row)` (already maps `tokensIn`, `tokensOut`, `createdAt`, `approvedAt`, `approvedBy`).
- Produces: `levelTestWeekDetail(db, id)` additionally returns `tokensIn: number`, `tokensOut: number`, `createdAt: string`, `approvedAt: string | null`, `approvedBy: string | null`, `promptVersion: number | null`, `judgeVersion: number | null`.

- [x] **Step 1:** Extend the existing detail test to assert the new fields exist (numbers/ISO strings) on the seeded draft.
- [x] **Step 2:** Run `npx tsx --test tests/level-tests-admin.test.ts` and see it fail.
- [x] **Step 3:** Select `prompt_version`, `judge_version` in the detail query and spread `weekRow(row)` into the result.
- [x] **Step 4:** Re-run the test file until green.
- [x] **Step 5:** Commit.

### Task 2: Sidebar groups + split the mixed view

**Files:**
- Modify: `services/api/admin-ui/app.js` (`NAV`, `boot()`, `VIEWS`, `route()`, `renderLevelTestOperations` → `renderExamWeeksView` + `renderAiConfig`)

**Interfaces:**
- Produces: view ids `exam-weeks` ("Generar y revisar"), `exam-results` (unchanged), `ia-config` ("Configuración de IA"), grouped under a sidebar heading `Exámenes`; `#level-tests` redirects to `#exam-weeks`.
- Produces: `renderAiConfig(container)` renders the provider card (catalog select, model select, base URL, API key, publish/verify modes, variants, save, connection test, dirty state linking to drafts).
- Produces: `renderExamWeeksView(container, view)` renders status card, launch form, jobs monitor and the weeks table (no provider fields).

- [x] **Step 1:** Extend `NAV` items with `group` for the three exam entries and render group headings in `boot()`.
- [x] **Step 2:** Move the provider card markup and handlers into `renderAiConfig`; its dirty-state link points to `#exam-weeks` and counts drafts via `GET /v1/admin/level-tests/weeks?status=draft&limit=1` (`total`).
- [x] **Step 3:** Keep the weeks/generation logic in `renderExamWeeksView`; remove provider fetches from it (`/v1/admin/llm-config` is no longer needed there).
- [x] **Step 4:** `VIEWS` maps the new ids; `route()` redirects `level-tests` → `exam-weeks` with `location.replace`.
- [x] **Step 5:** `node --check app.js`, bump asset versions, manual smoke in the browser stack.
- [x] **Step 6:** Commit.

### Task 3: Full-page exam review

**Files:**
- Modify: `services/api/admin-ui/app.js` (new `renderExamReview`, route `#exam/<id>`, listing "Ver" → navigate)
- Modify: `services/api/admin-ui/app.css` (item/passage/verdict styles)

**Interfaces:**
- Consumes: `GET /v1/admin/level-tests/weeks/:id` (Task 1 fields), `POST .../:id/approve|reject` with `{reason}`.
- Produces: `renderExamReview(container, params, id)`; `#exam/<uuid>` route with `title` = "Revisión de examen".

- [x] **Step 1:** Router: parse `<path>/<id>`, `VIEWS.exam` entry, 404 alert for unknown ids.
- [x] **Step 2:** Header card: language, level, week, status badge, model, judge mode, tokens, created/approved dates, and a `N marcados por el juez` banner when any verdict is `flagged`.
- [x] **Step 3:** Variant tabs (`Variante 1..N`, `Pool`) switching client-side without refetch; sections derived from item order (`recognition`, `writing`, `reading`, `listening`) with Spanish labels.
- [x] **Step 4:** Item cards: prompt, options with the accepted one marked, accepted answers, explanation, unit id, judge verdict + reason. Reading/listening: passage once per `passageId`, then its questions; unknown `passageId` falls back to a standalone item.
- [x] **Step 5:** Sticky footer for drafts: Aprobar / Rechazar with the reason dialog, re-rendering the page on success; listing "Ver" navigates to `#exam/<id>` and the raw-JSON modal is no longer used for exams.
- [x] **Step 6:** Empty/absent `options`, `answers`, `passages`, `review` render safely (Review Focus).
- [x] **Step 7:** Commit.

### Task 4: Browser E2E + docs

**Files:**
- Modify: `services/api/tests/browser/admin-e2e.mjs`
- Modify: `openspec/changes/admin-exam-ux/**`, `openspec/changes/admin-exam-operations/verify-report.md`

- [x] **Step 1:** Update navigation to the new views in the existing checks.
- [x] **Step 2:** New checks: sidebar shows the `Exámenes` group with the three entries; `#ia-config` has the provider form and `#exam-weeks` does not; review page shows sections, the marked correct option, the judge verdict and switches variants; approving from the review page updates the status.
- [x] **Step 3:** Run the full E2E on a fresh browser stack until green, twice.
- [x] **Step 4:** Update tasks/spec/verify report, commit.
