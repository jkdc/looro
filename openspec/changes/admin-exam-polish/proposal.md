# Proposal: Admin exam polish — readable states, error dialog, grouped menu

## Intent

Make the generation monitor readable at a glance: severity colours for states,
and a real explanation when a job fails. Make every sidebar entry visibly belong
to a section.

## Problem

1. `BADGE_COLORS` had no entry for `draft`, `approved`, `rejected`, `retired`,
   `running`, `finished`, `failed` or `unknown`, so every exam state rendered in
   the same grey. A red week and a green one looked identical.
2. A failed job showed only `generation_failed`. That code is the coarse
   contract (`^[a-z0-9_]{1,80}$`), so the useful message the pipeline produced —
   `reading: only 2 passages (needed 4)` — was discarded on write. The operator
   had no way to know which section failed, and no way to act.
3. The `Exámenes` heading was a styled `span` inside the same list as the links.
   Everything below it (Planes, Campañas, Tablas, Política, Auditoría) looked
   like part of the exam group.

## Approach

- Severity tones for both status vocabularies (weeks: `draft|approved|rejected|
  retired`; jobs: `running|finished|failed|unknown`). Green means usable, red
  means the operator must look, yellow means waiting or unknown.
- Persist the real reasons: `level_test_generation_jobs.error_details` (jsonb,
  migration `047`), written on every state transition by a sanitizer that keeps
  only our own lowercase messages and drops provider bodies, stack traces and
  user content. The status endpoint returns `errors: string[]` next to the
  unchanged `errorCode`.
- The error cell is a button: a dialog shows the coarse code, the sanitized
  per-section reasons, language/level/week, attempts, timestamps and what to do.
- Four titled sections in the sidebar (`Operación`, `Exámenes`, `Comercial`,
  `Sistema`); headings are separators, never links.

## Scope added after the first pass

- The launcher no longer enqueues a level whose week already exists: it reports
  `skipped_existing` and creates no run (the runner would answer `exists` and
  the old upsert recorded it as `finished`, a green completion for content that
  never changed).
- `level_test_generation_jobs` becomes a run log: one row per attempt, so a
  retry keeps the previous failure. The automatic cap (two attempts) still binds
  the scheduler and CLI; an operator launch may retry beyond it.
- Weeks record `origin` (`user_operator` | `automatic_worker` | `unknown`), so
  the listing answers who triggered each generation. `approved_by` stays as is
  (it already distinguishes `system:auto` from an operator id).

## Out of scope

New endpoints, grading changes, exam content, Android/iOS, provider behaviour,
and the retire/invalidate path for an approval (it needs its own audited
action: `reject` only accepts drafts and the trigger only allows
`approved → retired`).
