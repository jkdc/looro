# T10 verification report

Date: 2026-10-17

## Environment

- Runner: Docker Compose isolated smoke stack `looro-t10-final`, PostgreSQL 17, Node 22.
- Compose project directory explicitly points to the active `weekly-exam-recovery` worktree.
- Test database used a fresh project volume; no live LLM credentials or provider calls were configured.

## Results

- API image build/typecheck: passed (`npm run build` in Docker).
- T10 focused tests: 13 passed, 0 failed (scheduler, provider cancellation/unknown outcome, PostgreSQL advisory-lock contention and durable unknown-state tests).
- Complete API integration suite: 522 passed, 0 failed, 0 skipped (`node --test --test-concurrency=1 dist/tests/*.test.js`).
- Migration 045 applied successfully to the isolated PostgreSQL database.
- Full suite required Docker test-image fixtures that were absent from the prior image: migrations and operations copied into `dist`, and `shared`/`apps` mounted read-only.

## Limitations

- No real provider calls were made; provider behavior is covered with test transports.
- Swift verification remains deliberately deferred until all implementation phases T01–T14 are complete.
