# T10 verification report

Date: 2026-10-17

## Environment

- Runner: Docker Compose isolated smoke stack `looro-t10-final`, PostgreSQL 17, Node 22.
- Compose project directory explicitly points to the active `weekly-exam-recovery` worktree.
- Test database used a fresh project volume; no live LLM credentials or provider calls were configured.

## Results

- API image build/typecheck: passed (`npm run build` in Docker).
- T10 focused tests before independent review: 13 passed, 0 failed. After the bounded correction: 11 affected tests passed, 0 failed (generation job lock/durable unknown state, LLM generation and judge, provider cancellation/body interruption).
- Complete API integration suite after correction: 524 passed, 0 failed, 0 skipped (`node --test --test-concurrency=1 dist/tests/*.test.js`).
- Migration 045 applied successfully to the isolated PostgreSQL database.
- Full suite required Docker test-image fixtures that were absent from the prior image: migrations and operations copied into `dist`, and `shared`/`apps` mounted read-only.

## Limitations

- No real provider calls were made; provider behavior is covered with test transports.
- Swift verification remains deliberately deferred until all implementation phases T01–T14 are complete.


## Independent verification and correction (2026-10-17)

- Independent review found two unmet T10 outcome-safety cases: response.json() failure after successful headers escaped classification; judge transport uncertainty was swallowed (flag) or classified as ordinary failure (strict), allowing later calls/reissue. The underlying transport behavior predated the T10 commit, but durable unknown-outcome acceptance was incomplete.
- Correction: response-body read failure now becomes llm_outcome_unknown without retry. A judge unknown outcome stops both verification modes, returns llm_outcome_unknown, and prevents persistence or subsequent provider calls.
- Regression checks were observed RED before the correction and GREEN afterward.
- Corrected focused Docker tests: 11 passed, 0 failed. Full API Docker suite: 524 passed, 0 failed, 0 skipped.
- Independent verifier bounded recheck: pending; do not close T10 until it returns.
