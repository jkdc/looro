# Tasks: English Generated Exam Quality and Length

- [x] T1 — Capture English blueprint/form-shape and validator regressions; route: inline; committed after RED→GREEN verification.
- [x] T2 — Connect typed English blueprint to generation and section validation; route: inline; prompts/judge now use can-do, skill, format, and level-specific constraints.
- [ ] T3 — Implement one bounded, validated repair cycle per section; route: inline.
- [ ] T4 — Expand English pool and served form separately; enforce diversity/capacity; route: inline.
- [ ] T5 — Strengthen active judge and flagged publication policy with real rejection coverage; route: inline.
- [ ] T6 — Aggregate per-run token usage and generation provenance without schema/history breakage; route: inline.
- [ ] T7 — Improve editorial-skill guidance only where it supports English exam quality; do not imply runtime loading; route: inline.
- [ ] T8 — Run acceptance checks and record teacher-pilot limitations; route: inline.

## Verification

- `cd services/api && env -u TEST_DATABASE_URL node_modules/.bin/tsx --test tests/level-tests-llm.test.ts`
- `cd services/api && ./node_modules/.bin/tsc --noEmit`
- `python3 scripts/export_leveltests.py --check` when shared model JSON changes.
- `make test-api` if the Docker/ephemeral PostgreSQL environment is available; never use a personal or production database.
- Additional focused policy/snapshot/attempt tests for any affected boundary.
