# Proposal: Retire an approved level test

## Intent

Let an operator withdraw a week that is already serving students — including one
approved automatically by the pipeline — without deleting its content or the LLM
judge's verdicts, and keep the motive on the week itself.

## Problem

`reject` only accepts drafts, and the database trigger only allows
`approved → retired`. The only path to retire an approved week was a force
regeneration, which replaces the content in the same operation: there was no way
to invalidate an approval while keeping the evidence.

## Approach

- `retireGeneratedWeek` and `POST /v1/admin/level-tests/:generationId/retire`
  with an audited reason (3–500 characters).
- Migration `050` adds `retired_at`, `retired_by` and `retired_reason` to
  `level_test_weeks`. The motive is read by the review page; the
  `level_test.retire` audit entry keeps the same reason.
- The listing and the review page expose «Retirar» for approved rows. A retired
  week explains its motive and offers «Regenerar».
- Regeneration stays explicit: the scheduler and the launcher skip any existing
  row, so a retired week is never reissued without an operator.

## Out of scope

Deleting content, editing judge verdicts, automatic replacement after a
retirement.
