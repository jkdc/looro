# T05 lifecycle recovery — Implementation Plan

Objective: close four reviewed T05 synchronization defects on feat/weekly-exam-recovery without rewriting history or merging. Delivery: feature-branch-chain; no push or PR is authorized. Test runners: API PostgreSQL integration in Docker; Android JVM suites via make android-test-docker; Swift tests unavailable on Linux.

## Specs

### S1 — Stable creation replay
The service MUST bind the initial creation receipt to the authenticated owner, attempt identity, and canonical initial payload. The receipt MUST preserve the original creation acknowledgement independently from later mutable attempt state.
- Byte-equivalent initial retry after another device advances to revision 2 replays original revision-1 ACK; stale subsequent event gets normal CAS conflict and revision 2 remains unchanged.
- Reusing owner-scoped attempt ID with different canonical initial payload is rejected without changing attempt or receipt.

### S2 — Monotonic effective timestamps
Clients MUST persist a retry-stable effective timestamp that is not earlier than the attempt start or the previously persisted effective timestamp. The service MUST enforce the same ordering when storing a valid event, without changing the original event bytes used for idempotent replay.
- With attempt start at 100 and device clock at 99, persisted event time is at least 100, retry payload is unchanged, and database accepts the transition.

### S3 — Conflict isolation
The Android drain MUST preserve per-attempt event order. An unresolved conflict MUST pause only that attempt; independent attempts for the same owner MUST continue to drain. The client MUST retain all local events until explicit takeover or fork.
- Attempt A conflict leaves A's later events pending and unsent; attempt B still sends in the same drain; later drains do not retry unresolved A.

### S4 — Latest-state fork
Fork resolution MUST atomically copy the latest local attempt state and its immutable snapshot into a new attempt identity. It MUST preserve the source history, supersede only events represented by the committed fork, and reject later mutations to the forked source identity.
- A post-capture local answer is included with latest position/result and original snapshot; source pending events retire only after commit; failed fork leaves source and events unchanged.

Full authority: specs/level-test-attempt-sync/spec.md. Spec approved by user on 2026-10-16.

## Global Constraints
- Keep initial creation receipt separate from mutable event revision and current server attempt state.
- Preserve canonical event bytes/hash for retries; only normalized stored projection may change.
- Keep unresolved conflict events until explicit resolution; never block unrelated attempts.
- Preserve account ownership and immutable exam snapshot behavior.
- Run all project execution commands in Docker. Do not execute Swift on Linux; report it pending.
- Do not rewrite existing commits, push, open PRs, or merge to main.

## Review Focus
- An identical create retry after a later server revision must not overwrite the mutable attempt.
- A changed payload or different owner must not reuse another creation receipt.
- Repeated device-clock rollback must not make event retries drift or violate lifecycle ordering.
- Conflict handling must not send same-attempt later events, starve other attempts, or lose them during a failed fork.
- A fork must include edits made after conflict capture while keeping the original attempt and immutable snapshot recoverable.

## Tasks

### Task 1: Immutable API creation receipt
Route: inline. Status: implemented; focused verification passed; full API suite unresolved. Risk: item 1 (durable server records) and item 3 (consumed API contract). Commit: c8b60fc.
Files:
- Create services/api/migrations/044_level_test_attempt_creation_receipts.sql.
- Modify services/api/src/level-test-attempts.ts.
- Test services/api/tests/level-test-attempts.test.ts.
- Modify services/api/docs/level-test-attempt-sync.md.
Interface: receipt key (account_id, attempt_id); immutable canonical request fingerprint and original creation ACK; atomic with initial attempt persistence; changed fingerprint is a 409 conflict, identical fingerprint returns stored ACK.
- [x] Add regression coverage for exact retry after revision advance and changed initial payload.
- [x] Run the focused Docker PostgreSQL attempt suite; RED reproduced exam_attempt_conflict (409 where the replay must return 200). The full make test-api attempt hit unrelated runner failures before completion; see L5.
- [x] Add additive owner-cascaded receipt migration and transactionally persist/read stable receipt without modifying current attempt on replay.
- [x] Focused Docker PostgreSQL attempt suite: 7/7 passed; image build/TypeScript compile passed. Full make test-api remains unresolved due the failures recorded in L5.
- [x] Commit c8b60fc as fix(level-tests): replay immutable attempt creation acknowledgements.

### Task 2: Retry-stable monotonic timestamps
Route: inline. Status: implemented; focused checks green; full API integration check unresolved; Swift unverified on Linux. Risk: item 1 (persisted state) and item 3 (wire behavior). Commit: ce37ae0.
Files:
- Modify services/api/src/level-test-attempts.ts and services/api/tests/level-test-attempts.test.ts.
- Modify apps/android/app/src/main/java/com/jkdc/app/data/ExamHistoryDao.kt and apps/android/app/src/test/java/com/jkdc/app/data/ExamHistorySyncTest.kt.
- Modify apps/ios/Core/Database/ExamHistoryDao.swift and apps/ios/Tests/ExamHistoryDaoTests.swift.
Interface: freeze effective time with each outbox event; enforce max(startedAt, prior effective time, received event time) server-side while preserving event hash over original wire event.
- [x] Add failing API and Android DAO regression tests; observed RED before implementation (API 500 instead of 200; Android assertion failure on clock rollback).
- [x] Add Swift mirror regression test; not executable on this Linux host.
- [x] Record RED, then implement monotonic Android/iOS local timestamps and API normalization while retaining raw event hash inputs.
- [x] Focused API sync tests passed 8/8; full Android Docker JVM suites passed. Full API suite failed on unrelated missing SQL files in dist and voice_worker_already_running, then stalled after test 212 and was interrupted; Swift remains unverified.
- [x] Commit ce37ae0 as fix(level-tests): clamp attempt event timestamps monotonically.

### Task 3: Per-attempt conflict isolation in Android drain
Route: inline. Status: implemented; focused regression and full Android JVM suites passed. Risk: item 4 (async ordering and starvation). Commit: dc9fc19.
Files:
- Modify apps/android/app/src/main/java/com/jkdc/app/network/ExamAttemptOutboxSync.kt and apps/android/app/src/main/java/com/jkdc/app/data/ExamHistoryDao.kt.
- Extend apps/android/app/src/test/java/com/jkdc/app/data/LearningDbTest.kt using the existing SQLite and outbox-sync fixtures.
Interface: pending event selection excludes attempts with unresolved conflict; a confirmed conflict skips remaining events for that attempt only; transport/unknown failures retain existing safe stop/retry behavior.
- [x] Add regression with conflicting A followed by later A event and valid B event; assert A later event remains pending and B is sent.
- [x] Add regression that a later drain does not resend unresolved A.
- [x] Observe RED on the current early-return behavior, then implement per-attempt pause and continue behavior.
- [x] Focused T3 regression passed; full Android Docker JVM suites passed (43 tasks, BUILD SUCCESSFUL).
- [x] Commit dc9fc19 as fix(android): isolate conflicted exam attempts during sync.

### Task 4: Atomic fork from latest local branch
Route: inline. Status: implemented; focused and full Android JVM suites passed; Swift unverified on Linux. Risk: item 1 (durable local data) and item 4 (transaction/concurrency). Commit: 6bc1696 (`fix(exams): fork the latest local conflict state atomically`).
Files:
- Modify apps/android/app/src/main/java/com/jkdc/app/data/ExamHistoryDao.kt and apps/android/app/src/test/java/com/jkdc/app/data/ExamHistorySyncTest.kt.
- Modify iOS ExamHistoryDao.swift and ExamHistoryDaoTests.swift.
- Update services/api/docs/level-test-attempt-sync.md if lifecycle guidance requires it.
Interface: transaction reads current source, latest answers, position, completion/result and original snapshot; inserts new fork identity plus immutable creation event; only after all inserts succeed does it retire represented source events and mark source forked; writes to forked source fail.
- [x] Add Android regression for answer/position/result edited after conflict capture, fork failure rollback on conflicting identity, and source write rejection; initial tests reproduced stale conflict snapshot behavior, lost conflict status, source mutation, and non-atomic failure effects.
- [x] Add equivalent Swift DAO tests; execution remains unavailable on Linux.
- [x] Implement fork from live source rows and latest answer revisions; freeze creation event with snapshot/current state, preserve unresolved conflict status, and reject writes to forked source identity.
- [x] Focused T4 Android tests passed 3/3; full Android JVM suites passed (43 actionable tasks, BUILD SUCCESSFUL); Swift tests unexecuted on Linux.
- [x] Commit as fix(exams): fork the latest local conflict state atomically (6bc1696).

## Log

### L1 — Original approval
“confirmo” — explicit approval of the written spec specs/level-test-attempt-sync/spec.md.

### L2 — User direction
“completa todos los pasos sin mi aprobación, quiero que actues de manera automática” — proceed through implementation and review without repeated routine approval prompts.

### L3 — Evidence and execution constraints
Baseline is 75fb86a on feat/weekly-exam-recovery; the worktree is clean except the already-created OpenSpec design files. The project has Docker-backed API integration (make test-api) and Android JVM (make android-test-docker) runners. Swift cannot be executed on this Linux host. All project commands continue inside Docker.

### L4 — Plan review
Self-review: S1-S4 each map to one task; task interfaces agree; five review-focus failure classes are assigned to regression tests; Android DAO JVM test path is explicit.

### L5 — Task 1 evidence and ruling
Focused Docker PostgreSQL test reproduced the expected RED (initial retry returned 409, expected 200); after the implementation, the focused attempt-sync file passed 7/7 and the Docker image TypeScript build passed. The full make test-api run reported unrelated missing runtime SQL files under /app/dist and voice_worker_already_running in hosted-paid tests, then remained active without progress; it was interrupted after more than two minutes.

Ruling: use the focused Docker attempt-sync suite for per-task TDD evidence and keep the full API integration suite as an unresolved final check — the full runner's unrelated failures obscure the T05 tests, while the focused suite directly reaches the changed route. Cost if wrong: a cross-suite integration interaction could remain undetected.

### L6 — Task 2 progress
Focused API sync tests passed 8/8 and full Android Docker JVM suites passed; Swift mirror remains unexecuted on Linux. The full API suite again reported unrelated missing SQL files under `/app/dist` and `voice_worker_already_running`, then stalled at test 212 and was interrupted. T2 is implemented and committed as ce37ae0, but the required full API suite remains unresolved.

### L7 — Task 3 evidence
The T3 regression first failed because the current drain returned after attempt A conflicted, preventing B from being sent. After adding conflict-scoped selection and continuing other attempts, the focused test and full Android JVM suites passed. Commit dc9fc19.

### L8 — Task 4 implementation and verification
Android RED tests reproduced stale conflict snapshot data, conflict-state loss after offline edits, and writes still accepted on a forked source. The rollback test was refined to simulate a real fork-ID collision; the source events and conflict resolution remain unchanged after rejection. Fork now snapshots current attempt/answer/result rows and frozen snapshot in one transaction, records a retry-stable creation event, retires source events only after new identity/event creation, and prevents subsequent source writes. Focused T4 tests passed 3/3; full Android JVM suite passed (43 actionable tasks, BUILD SUCCESSFUL). Equivalent Swift changes/tests are present but unexecuted because this host is Linux.

### L9 — Independent whole-feature verification
A fresh-context verifier inspected committed candidate a05fd77 against baseline 75fb86a using a read-only Docker scratch copy. Verdicts: S1-S4 passed on the executable platform(s); Android fork probes 3/3 and full Android JVM suites (702 tests, zero failures) passed; API focused suite 8/8 passed and baseline focused suite 6/6 passed. Scratch-only checks confirmed snapshot equality, unchanged exam-table hashes after fork-ID collision, and rollback after injected outbox insertion failure. Swift remains unexecuted on Linux.

Native RDD remains unavailable: assessment reports high risk/review_due but cannot build its temporary index because the shared worktree metadata is read-only; selectorless STATUS failed safely before review start with mutation_outcome=not_started. No native receipt or approval is claimed. Full API suite remains unresolved per L5/L6.
