# Daily Practice audit fixes

Objective: Correct the residual defects and close the verification gaps reported for `feat/daily-practice`.
Branch: `feat/daily-practice`; delivery: preserve existing work, no push; tests: Docker/Make except native mobile.

## Specs

### S1 — Android hint evidence stays aligned with visible stage
“Android shows hints in later stages without recording their use.” Reset visibility on stage transitions or otherwise ensure visible assistance is recorded for that stage. Regression covers preparation → listening → practice.

### S2 — iOS uploads final speaking fragments before close
“iOS can omit the last speaking fragments received during asynchronous close.” Reacquire current attempt state after awaiting stop; retain attempt fencing. Test transcript arriving during close.

### S3 — Speaking duration is present in completed stage breakdown
“The time of speaking is not preserved in completedStages.” Accumulate segment durations into speaking stage without double counting global total. Regression asserts per-stage and global totals.

### S4 — Load commands execute orchestration in authorized Docker tooling
“make load-api-smoke” and “make load-api-baseline” must work without adding Git/Docker binaries to the production API image. User explicitly authorized a separate Docker tooling container with Docker socket access. Keep scope bounded and cleanup reliable.

### S5 — Admin E2E proves the generated package is the one published
“The admin E2E does not prove generation-to-publication.” Use deterministic stub generation and assert run identity through watch, preview, publish, and public package; add a failure/retry path per existing task acceptance.

### S6 — Verification report counts agree
“The report still has the discrepancy 981 vs 984.” Reconcile from captured test output; do not rerun the suite solely to choose a number.

## Tasks

- T1 S1 Android hint visibility/evidence — delegated — implemented — RED/GREEN; focused hint regression, cached-attempt test and full screen test class pass on emulator (2/2); `make android-build` pass.
- T2 S2 iOS final fragment upload race — delegated — implemented — async regression added; XCTest unavailable on Linux, pending macOS.
- T3 S3 speaking duration accounting — inline — implemented — RED confirmed 0 vs 1000; GREEN isolated; full API suite covers integration.
- T4 S4 load orchestration container — delegated — implemented — runner test 5/5, smoke and baseline workloads pass; cleanup confirmed.
- T5 S5 admin generation E2E — delegated — implemented — actual generation route/worker against deterministic LLM stub; run ID/package ID followed through preview, publish, public read and withdrawal.
- T6 S6 reconcile report count — inline — implemented — `make test-api` 986/986, 0 skipped; report table corrected.

## Log

### L1 — User request
“puedes arreglarlos?”

### L2 — Authorization
User explicitly authorized mounting the local Docker socket into a separate tooling container for the load-test orchestrator.

### L3 — Baseline and constraint
Branch is `feat/daily-practice`, HEAD `3ead501f7eb18f647496d6d62c49d4beab9efe95`; 99 paths already have staged or unstaged changes. Preserve all existing state and do not create commits that could capture unrelated work. Initial `git diff --check` passed; focused API tests passed 12/12 in isolated Docker.

### L4 — Progress and verification
T1 cached-attempt failure was an invalid fixture: decoder required five expressions, three listening questions, three goals and four practice questions. Fixture repaired and emulator test class passes 2/2. T5 upgraded to actual worker and deterministic LLM stub; found/fixed PostgreSQL Date normalization in cost reservation. Fresh `make test-api` now passes 986/986, 0 failures/skips; make check 152 passes; load baseline completes and cleans stack. Only native iOS execution remains unavailable on this Linux host.
