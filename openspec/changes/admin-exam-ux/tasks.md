# Tasks: admin-exam-ux

## 1. Read model
- [x] 1.1 `levelTestWeekDetail` returns tokens, dates and prompt/judge versions
- [x] 1.2 Detail test asserts the enriched shape

## 2. Navigation split
- [x] 2.1 Sidebar group `Exámenes` with three entries and heading rendering
- [x] 2.2 `renderAiConfig` owns the provider card (catalog, model, key, modes, test, dirty state)
- [x] 2.3 `renderExamWeeksView` keeps launcher, jobs and listing; no provider calls
- [x] 2.4 `#level-tests` redirects to `#exam-weeks`

## 3. Exam review page
- [x] 3.1 Route `#exam/<uuid>` with 404 state
- [x] 3.2 Header (model, judge mode, tokens, dates) and flagged banner
- [x] 3.3 Variant tabs and pool view
- [x] 3.4 Sections in exam order; item cards with marked answer, explanation, unit, verdict
- [x] 3.5 Passages rendered once per `passageId`, unknown ids degrade
- [x] 3.6 Sticky approve/reject from the page; listing "Ver" navigates

## 4. Verification
- [x] 4.1 Browser E2E: group, view isolation, review page, approval from the page
- [x] 4.2 Two consecutive green E2E runs (35/35 each, fresh stack)
- [x] 4.3 verify-report updated
