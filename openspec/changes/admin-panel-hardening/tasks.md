# Implementation Tasks

- [x] **T0 — Baseline and implementation contract** (`S1–S9`; inline): record current runner results and preserve the approved audit/spec before implementation. `make test-api`: 640/640; browser E2E: 57 checks passed. Commit: `603f2d4`.
- [x] **T1 — Campaign and query integrity** (`S1–S2`; inline): immutable preparation/invalidations; catalog-driven browse controls; uncertain apply keeps the same ID and blocks creating another campaign. Browser E2E 63 checks, `make test-api` 640/640. Commit: `386b9a3`.
- [x] **T2 — Validation and operator error states** (`S4–S5`; inline): client-side form validation prevents invalid requests; bearer login network errors are retryable without leaking token; confirmed updates remain distinct from refresh failures; global result summary is labeled; accessible persistent retry and API connectivity state. Chromium browser E2E 65 checks passed. Commit: `6f42b2c`.
- [ ] **T3 — View lifecycle and resource disposal** (`S3–S4`; inline): view context, stale-render guards, Tabulator/Chart/listener/timer disposal, loading/error/retry and truthful API status. Commit: pending.
- [ ] **T4 — Responsive shell and functional accessibility** (`S5–S6`; inline): mobile navigation/sign-out, modal semantics/focus, labels/landmarks/visible actions, status announcements and chart equivalents. Commit: pending.
- [ ] **T5 — Generation run correlation** (`S7`; inline): reproduce races with isolated PostgreSQL; expose/query exact persisted identity additively; retain `202`, locks, audit and optional worker. Commit: pending.
- [ ] **T6 — Incremental organization, operational hierarchy, docs** (`S8–S9`; inline): extract stabilized responsibilities only; improve information hierarchy and update help/tests/docs. Commit: pending.
- [ ] **T7 — Full verification and final report** (`S1–S9`; inline): full relevant tests, independent high-risk review, record all limitations and results in `verify-report.md`. Commit: pending.

Order is intentional: tests first, correctness before lifecycle, lifecycle before extraction, and run identity only after confirming the existing persistence model. Each code task closes with its applicable check and a Conventional Commit; no push or merge.
