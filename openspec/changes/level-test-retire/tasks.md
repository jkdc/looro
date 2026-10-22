# Tasks: Retire an approved level test

## 1. Persistence

- [x] 1.1 Migration `050`: `retired_at`, `retired_by`, `retired_reason` on `level_test_weeks`
- [x] 1.2 Length constraint (3–500) when a motive is present
- [x] 1.3 Detail endpoint returns `retiredAt`, `retiredBy`, `retiredReason`

## 2. Server action

- [x] 2.1 `retireGeneratedWeek`: approved only, idempotent replay, audited `level_test.retire`
- [x] 2.2 Audit detail keeps `approvedBy` and `origin` of the invalidated week
- [x] 2.3 `POST /v1/admin/level-tests/:generationId/retire` with CSRF and admin gates

## 3. Panel

- [x] 3.1 «Retirar» action for approved rows in the listing
- [x] 3.2 «Retirar» action in the review page, shared reason dialog
- [x] 3.3 Retired week shows the motive and date; superseded rows say so
- [x] 3.4 «Regenerar» available for retired rows of the current week

## 4. Verification

- [x] 4.1 API tests: motive persisted, detail exposes it, single audit, replay, 409/404/400
- [x] 4.2 API tests: CSRF and admin-only
- [x] 4.3 E2E: retire the approved B1, public endpoint 404, motive visible in review
- [x] 4.4 Full API suite and type-check
