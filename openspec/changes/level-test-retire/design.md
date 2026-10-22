# Design: Retire an approved level test

## The row keeps the motive

`reject` only accepts drafts, and `protect_level_test_weeks()` already allows
`approved → retired`. Retiring is therefore a publication transition, not a
content edit: the pool, variants, review and judge mode stay untouched.

The motive goes on the week (`retired_reason`) as well as in `admin_actions`.
The audit ledger is the canonical trace; the row is what the review page can
read without joining audit by entity id, and the operator should see *why* a
week disappeared from the student surface next to the week itself.

`retired` has two causes:

- a manual retirement, which fills `retired_at`, `retired_by` and
  `retired_reason`;
- a force regeneration superseding the row (`persistWeek`), which leaves the
  three columns null because the reason is the regeneration, recorded as
  `level_test.regenerate`.

The review page distinguishes them: a manual retirement shows the motive and the
date; a superseded row says it was replaced by a regeneration.

## Idempotency and conflicts

- Only `approved` can be retired; anything else is `level_test_not_retirable`
  (409).
- Retiring an already `retired` week answers `alreadyRetired: true` without a
  second audit entry, mirroring `reject`/`approve` replay.
- An unknown id is `level_test_not_found` (404); a short or control-character
  motive is `admin_reason_required` (400).

## No automatic regeneration

`exists` in the runner matches any week row, so a retired week is not reissued
by the scheduler or «Lanzar faltantes». That is deliberate: retirement removes
content from students, and an operator decides when to replace it. The listing
offers «Regenerar» for retired rows of the current week, which is the existing
force path that retires the old row (already retired) and persists a fresh one.
