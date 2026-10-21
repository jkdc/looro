# Tasks: Admin exam operations

## 1. Documentation
- [ ] 1.1 openspec change docs (this change)

## 2. Exam listing (admin-exam-management)
- [ ] 2.1 `GET /v1/admin/level-tests/weeks` with filters, sort whitelist, pagination; metadata-only items
- [ ] 2.2 `GET /v1/admin/level-tests/weeks/:id` detail with pool/variants/review; 404
- [ ] 2.3 Panel "Exámenes generados" card: filter bar, status badges, pagination, Ver/Aprobar/Rechazar

## 3. Regeneration (admin-exam-management)
- [ ] 3.1 `POST /v1/admin/level-tests/regenerate`: current-week guard, 409 on approved, async job, audit
- [ ] 3.2 `jobs[]` in `/v1/admin/level-tests/status` (last 10, keeps `latestJob`)
- [ ] 3.3 Panel: Regenerar button (current week, draft/rejected only), reason modal, jobs table with 5 s polling

## 4. Results (admin-exam-results)
- [ ] 4.1 `GET /v1/admin/level-test-attempts` listing with joins, filters, sort, pagination
- [ ] 4.2 `GET /v1/admin/level-test-attempts/:accountId/:attemptId` detail (answers + result)
- [ ] 4.3 `GET /v1/admin/level-test-attempts/summary` KPIs + week/level breakdown
- [ ] 4.4 `GET /v1/admin/level-test-attempts/export` CSV (cap 10 000, formula escaping)
- [ ] 4.5 Panel "Resultados de exámenes" section: filters, detail modal, per-student view, CSV download, accounts browse action "Ver exámenes"

## 5. Verification
- [ ] 5.1 `npx tsc --noEmit` clean
- [ ] 5.2 `make test-api` green (listing filters, regenerate 409/audit/concurrency, results joins/filters, summary, export, security)
- [ ] 5.3 `verify-report.md`
