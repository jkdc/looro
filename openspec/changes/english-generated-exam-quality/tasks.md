# Tasks: English Generated Exam Quality and Length

- [x] T1 — Capture English blueprint/form-shape and validator regressions; route: inline; committed after RED→GREEN verification.
- [x] T2 — Connect typed English blueprint to generation and section validation; route: inline; prompts/judge now use can-do, skill, format, and level-specific constraints.
- [x] T3 — Implement one bounded, validated repair cycle per section; route: inline; validation codes are sanitized, each affected section is regenerated once, and uncertain outcomes stop.
- [x] T4 — Expand English pool and served form separately; enforce diversity/capacity; route: inline; newly generated English forms use report pilot totals 34/39/44/54/58 and three variants select distinct deterministic item/stimulus sets or fail closed if judge filtering leaves insufficient diversity.
- [x] T5 — Strengthen active judge and flagged publication policy with real rejection coverage; route: inline; English flagged/rejected items are removed before composition, affected comprehension passages are excluded as indivisible stimuli, duplicate final English variants fail closed, and publication rechecks reject/flagged state; non-English composer and passage-duplicate behavior remain unchanged.
- [x] T6 — Aggregate per-run token usage and generation provenance without schema/history breakage; route: inline; all generation/judge/repair calls aggregate reported usage once, unknown usage persists NULL and remains null in admin responses, prompt/judge versions advanced to 2.
- [x] T7 — Improve editorial-skill guidance only where it supports English exam quality; do not imply runtime loading; route: inline; documented that the content skill is editorial and runtime LLM behavior is implemented/tested in the API.
- [x] T8 — Run acceptance checks and record teacher-pilot limitations; route: inline; full isolated API suite passed 669/669; form totals remain unvalidated pilot targets requiring teacher review.

## Verification

- `make test-api`: 669 passed, 0 failed on final implementation behavior.
- `cd services/api && env -u TEST_DATABASE_URL node_modules/.bin/tsx --test tests/level-tests-llm.test.ts`
- `cd services/api && ./node_modules/.bin/tsc --noEmit`
- `python3 scripts/export_leveltests.py --check` when shared model JSON changes.
- `make test-api` if the Docker/ephemeral PostgreSQL environment is available; never use a personal or production database.
- Additional focused policy/snapshot/attempt tests for any affected boundary.
