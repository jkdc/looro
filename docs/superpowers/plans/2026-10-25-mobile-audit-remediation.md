# Mobile Audit Remediation Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` sequentially: a fresh implementer, fresh task reviewer, then the parent commits the reviewed candidate. No source implementation has started.

**Goal:** Address M01–M16 from the mobile audit plus user-approved account isolation for all local learner data, without data loss, false activity history or unsupported capability promises.
**Architecture:** One negotiated progress contract, always-present member/guest identity, account-partitioned learner storage, owner/generation-bound transactional persistence, bounded continuous voice capture and explicit App-layer error/cancellation states.
**Tech Stack:** Kotlin/Compose/SQLite, Swift/SwiftUI/Speech/GRDB, TypeScript/Fastify/PostgreSQL and existing Python exporters.
**Spec:** `openspec/changes/mobile-audit-remediation/design.md` and `openspec/changes/mobile-audit-remediation/specs/mobile-remediation/spec.md`. The latter contains the normative scenarios; S# are requirements and T# are tasks.
**Status:** Revised after user-requested document review; all tasks pending. The baseline design was approved; this review does not start implementation.

## Global Constraints

- Stay on `feature/learning-platform`; no worktrees, branch switch, push, PR or merge. Use Superpowers, not Gentle AI.
- Parent owns git, shared docs, candidate snapshot and commits. Workers edit only their declared surface, run no installers and spawn no helpers. Reviewer inspects the same bytes that the parent commits.
- Keep generated files generated: schema SQL uses `scripts/export_db_schema.py`; new progress constants use the T2 exporter. Do not edit generated Kotlin/Swift outputs by hand.
- Preserve Android EN/ES strings and Compose test tags; preserve iOS existing localizations. No new dependencies, real provider calls or production configuration changes.
- Preserve pre-existing `.playwright-mcp/` and the source audit report. Do not clean or absorb them into commits.
- V2 stays on `/v1/progress`: GET `version=2`, versioned POST with distinct `firstAt`/`lastAt`, page limits 1000/200, and downgrade only on HTTP 503 plus `progress_sync_version_unavailable`; the design contract table is authoritative.
- Each task consumes the exact contract produced by its prerequisites. No live v2 client activation before T8; local tests explicitly enable the flag.
- Missing platform proof is `implemented / verification pending`, not complete. Do not tick requirements based on pure-helper tests when real persistence, HTTP, App or UI behavior is required.

## Review Focus

1. Account/import replacement during fetch **or POST acknowledgement** must leave replacement data and resume state untouched (T3, T6, T7).
2. First/last timestamps, replayed pages and mutations behind the cursor must converge without firstAt loss, additive attempts or partial cursor commits (T4–T8).
3. Old-server legacy-shaped 200 and unrelated HTTP errors must not silently trigger fallback (T4, T6, T7).
4. Cancellation/overflow/late callbacks must not leak audio, mutate newer attempts or replay muted/echo frames (T9–T13).
5. Legacy guest/local history and existing v1 shapes must survive migrations, rollout and aggregate projection unchanged (T1, T4, T5, T8).

## Interfaces and execution gates

Read task prerequisites in order. T2 locks the shared contract; T4 implements the server; T5 supplies the rollup destination and atomic cursor write before T6/T7 consume them. T8 integrates score readers and upload feedback. T13–T15 are separate independently reviewable iOS behaviors, not one mixed task.

Before dispatch, create the task brief using the Superpowers helper, include these Global Constraints, exact S# scenarios and allowed edit paths. Any necessary file outside those paths is returned to the parent for an explicit surface update, not silently edited. All names below marked Create are planned new files, not claims that they already exist. No task ends with unspecified “related tests” or “existing exporter inputs”.

## Verification commands

Run from repository root except the explicit `cd apps/android`. For each task, first run its named new regression **before** the production fix and observe the correct failure; then run it green and the applicable suite. New tests belong in the listed target, with injected time/network/audio boundaries, never real providers. Formatting normalizers run before freezing the review candidate. Docs-only reconciliation has structural proof, not fabricated RED.

| Check | Command and required evidence |
|---|---|
| Shared | `make check`; `python3 scripts/export_progress_sync.py --check` once T2 exists; `python3 scripts/export_db_schema.py --check`; `python3 scripts/check_db_schema.py`; all exit 0 |
| API integration | `make test-api`; PostgreSQL cases in progress.test.ts/progress-http.test.ts execute, not skip for missing TEST_DATABASE_URL |
| Android unit | `cd apps/android && ./gradlew --offline :app:testDebugUnitTest :core:engine-local:testDebugUnitTest`; inspect failures/skips, not just task status |
| Android lint/build | `cd apps/android && ./gradlew --offline :app:lintDebug :app:assembleDebug :app:assembleUiTestAndroidTest`; record warnings separately |
| Android UI | `cd apps/android && ./gradlew --offline :app:connectedUiTestAndroidTest`; only isolated uiTest app and synthetic data on an available test device |
| Swift Core | `swift test --package-path apps/ios`; compiles Core/Tests only |
| iOS App | `make ios-test` after T3 adds AppTests to scheme App; verify AppTests and AppUITests actually execute; do not call pre-existing UI-only scheme App-unit coverage |
| Physical gates | Explicit Android audio/TalkBack and iOS local-recognition/VoiceOver/device cancellation checks from the audit; pending unless performed |

A failed command is investigated, not hidden by `--offline` or skipped database tests. If the required runner is unavailable, retain the test/code as pending proof and list the exact limitation. At final delivery S# is verified only after its complete scenario chain has evidence; local default-off v2 implementation is not a deployed M04 fix.

---

### Task 1: Isolate exam history

**Requirements:** S1. **Prerequisites:** none. **Status:** implementation/review complete; connected UI proof pending.

**Files:**
- Modify: `apps/android/app/src/main/java/com/jkdc/app/data/LevelTestAttemptStore.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/ui/LearnScreen.kt`
- Modify: `apps/android/app/src/test/java/com/jkdc/app/data/LevelTestAttemptStoreTest.kt`
- Create: `apps/android/app/src/androidTest/java/com/jkdc/app/MobileAuditHistoryTest.kt`

**Interfaces:** Consumes the captured active member/guest identity; produces owner-filtered history with explicit identity equality semantics and UI request identity. NULL is legacy-only, never a wildcard or the active guest. `LevelTestAttemptStore.list` requires caller identity rather than authorizing from each returned row.

### Task 8: Project effective progress into learner reads and upload feedback

**Requirements:** S5,S17. **Prerequisites:** T5,T6,T7,T17. **Status:** implementation committed as `64275bb`; fresh re-review approved; Android focused projection 9/9 and scratch probes passed. Full Android suite had one T10 voice-test failure; isolated rerun passed. Task verification remains pending T16 iOS execution and clean full Android suite.

**Files:** `apps/android/app/src/main/java/com/jkdc/app/core/ProgressEngine.kt`, `ProgressProjection.kt`, `LevelEngine.kt`, `MasteryEngine.kt`, `AppViewModel.kt`, `apps/android/app/src/test/java/com/jkdc/app/core/ProgressProjectionTest.kt`, `apps/ios/Core/ProgressEngine.swift`, `LevelEngine.swift`, `MasteryEngine.swift`, `apps/ios/App/Storage.swift`, `LearningProgressView.swift`, `ListeningView.swift`, `ConversationCoordinator.swift`, `CoursesViews.swift`, `apps/ios/Tests/ProgressProjectionTests.swift`.

**Contract:** Effective reads combine local and remote aggregate rollups without summing duplicated counters; local activity/history/streak events remain local. Upload feedback uses that same aggregate and respects active-owner boundaries. Preserve legacy completions and language scoping. Android RED/GREEN required; do not run Swift/iOS commands before T16. Reviewer correction wires iOS lesson-row attempt labels through effective owner/language-scoped lessons; regression source added but execution remains deferred.

**Verification evidence:** `cd apps/android && ./gradlew --offline --no-parallel --max-workers=1 --rerun-tasks :app:testDebugUnitTest --tests 'com.jkdc.app.core.ProgressProjectionTest'` passed (9 tests). Independent scratch probes passed the pure projection/upload contract. `./gradlew --offline --no-parallel --max-workers=1 :app:testDebugUnitTest :core:engine-local:testDebugUnitTest` reported 792 tests with one failure in the unrelated preserved T10 `LocalVoiceTransportTest.barge-in stops playback and never buffers the interrupted speech as a turn`, at the no-transcript assertion; isolated rerun passed. iOS tests/builds were not run per T16 deferral; test-first RED/GREEN for iOS remains unobserved.

- [x] **Step 1 — RED:** `historyIsScopedToRequestingOwner` and `lateOwnerHistoryCannotRepopulateNewAccount` regressions added; test-first failure observed before implementation (initial API/behavior failure).
- [x] **Step 2 — Observe RED:** Focused test failed before implementing owner-aware history.
- [x] **Step 3 — Implement:** Added owner SQL predicate, guest `IS NULL`, owner-scoped summaries and generation/request-sequence fencing; late results cannot republish after owner changes.
- [x] **Step 4 — GREEN:** Focused and full Android unit suites passed; UI-test sources compiled. Connected UI execution unavailable: no devices attached, `:app:connectedUiTestAndroidTest` reports “No connected devices!”.
- [x] **Step 5 — Review:** Fresh reviewer approved after one correction batch; parent spot-check of the focused unit command passed. Review advisories: no rendered Compose assertion ran, and the test barrier cleanup is not wrapped in `finally`.
- [x] **Step 6 — Commit:** `f4673e2` (`fix(android): isolate exam history by account`) contains the reviewed task files and plan/spec evidence. S1 remains proof-pending until the required connected UI run succeeds.

### Task 2: Lock the progress contract and time primitives

**Requirements:** S2,S4. **Prerequisites:** T1 implementation/review committed as `f4673e2`; S1 device proof pending. **Status:** implementation/review complete; Swift execution unavailable.

**Files:**
- Modify: `openspec/changes/audit-progress-sync/design.md`
- Modify: `openspec/changes/audit-progress-sync/tasks.md`
- Modify: `openspec/changes/audit-progress-sync/specs/progress-sync-protocol/spec.md`
- Modify: `openspec/changes/audit-progress-sync/specs/progress-sync-recovery/spec.md`
- Create: `shared/progress-sync-contract.json`
- Create: `scripts/export_progress_sync.py`
- Create: `scripts/tests/test_export_progress_sync.py`
- Modify: `Makefile`
- Create: `apps/android/app/src/main/java/com/jkdc/app/core/ProgressTime.kt`
- Create: `apps/ios/Core/ProgressTime.swift`
- Create: `apps/android/app/src/test/java/com/jkdc/app/core/ProgressTimeTest.kt`
- Create: `apps/ios/Tests/ProgressTimeTests.swift`
- Generate: `apps/android/app/src/main/java/com/jkdc/app/core/ProgressSyncContractData.kt`
- Generate: `apps/ios/Core/ProgressSyncContractData.swift`

**Interfaces:** Produces one version-2 contract: existing route/version negotiation, Unix-ms/Apple-reference-seconds, epoch offset 978307200, future skew 172800000, limits 200/1000, both aggregate timestamps and structured unavailable error. `ProgressTime` provides checked `fromUnixMilliseconds` and `toUnixMilliseconds` in each language; it does not mutate existing stored records.

- [x] **Step 1 — RED:** Regression tests cover distinct instants, checked conversion bounds, invalid contracts and exporter drift; RED observed for unsafe exporter limits before correction.
- [x] **Step 2 — Observe RED:** `python3 -m unittest discover -s scripts/tests -p test_export_progress_sync.py` demonstrated unsafe exporter limits were accepted before validation was added.
- [x] **Step 3 — Implement:** Added shared contract, generated constants, checked Kotlin/Swift time primitives, exporter, `make check` integration and provenance-based recovery specification; persistence repair remains T5 and clients T6/T7.
- [x] **Step 4 — GREEN:** Exporter tests (5), `make check` (146 tests), focused Android time tests and full Android unit suites passed. One full Android run had a transient unrelated voice assertion; focused rerun passed, and a second full run passed 731 tests with 0 failures. Swift unavailable; `swift test --package-path apps/ios` not run.
- [x] **Step 5 — Review:** Fresh reviewer approved after one correction batch; parent exporter-test spot check passed. Advisory: fractional wire timestamp and invalid injected `now` tests remain desirable; Swift execution is unverified.
- [x] **Step 6 — Commit:** `d58a2c9` (`feat(progress): define checked sync time contract`) contains reviewed T2 surfaces and progress-sync OpenSpec updates. Swift execution remains unverified due unavailable toolchain.

### Task 3: Fence active sync and enable real iOS App tests

**Requirements:** S3. **Prerequisites:** T2 committed as `d58a2c9`; iOS app execution deferred to final verification per user. **Status:** implementation and independent structural re-review complete; iOS app execution pending T16.

**Files:**
- Modify: `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt`
- Create: `apps/android/app/src/main/java/com/jkdc/app/core/ProgressSyncOperation.kt`
- Create: `apps/android/app/src/test/java/com/jkdc/app/core/ProgressSyncOperationTest.kt`
- Modify: `apps/ios/App/ConversationCoordinator.swift`
- Modify: `apps/ios/App/ManagedAccountStore.swift`
- Create: `apps/ios/Core/ProgressSyncOperation.swift`
- Create: `apps/ios/Tests/ProgressSyncOperationTests.swift`
- Modify: `apps/ios/App.xcodeproj/project.pbxproj`
- Modify: `apps/ios/App.xcodeproj/xcshareddata/xcschemes/App.xcscheme`
- Create: `apps/ios/AppTests/ProgressSyncOperationTests.swift`

**Interfaces:** Produces a coordinator-owned token bound to account/credential identity/data generation and single active operation. Add an App-hosted XCTest target `AppTests`, included in scheme App, that compiles real App adapters with injected network/storage boundaries; SwiftPM still covers only Core.

- [x] **Step 1 — RED:** Stale-fetch, credential, import-admission and commit-reservation regressions observed failing before fixes; adapter-boundary tests cover the production Android runner and iOS coordinator seams.
- [x] **Step 2 — Observe RED:** Deliberately broken admission/commit probes failed at expected assertions.
- [x] **Step 3 — Implement:** Android runner uses admission leases across suspend boundaries; queued durable write and archive publication share a commit reservation; imports wait for active commit and block new admission. iOS coordinator has injected fetch/send/storage boundaries and identity fences.
- [x] **Step 4 — GREEN:** Fresh scratch Android tests passed (13 focused; 733 app + 11 engine-local). Structural PBX/scheme probe passed after the semicolon correction (109 direct objects, target/config references, testability); `git diff --check` passed. User deferred iOS app execution to T16; no Xcode/Swift run claimed.
- [x] **Step 5 — Review:** Fresh independent scratch re-review strict-parsed 109 PBX objects, verified AppTests references/host/product/dependency, Debug testability and shared-scheme binding; negative control without the terminator failed as expected. iOS app execution is explicitly deferred to T16.
- [x] **Step 6 — Commit:** `6a69351` (`fix(progress): fence sync by live data generation`). iOS execution remains pending T16, not waived.

### Task 4: Implement negotiated API v2 on the existing route

**Requirements:** S4. **Prerequisites:** T2,T3. **Status:** committed as `98ae12d`.

**Files:**
- Modify: `services/api/src/progress.ts`
- Modify: `services/api/src/app.ts`
- Modify: `services/api/.env.example`
- Create: `services/api/migrations/054_progress_allow_historical_first_at.sql`
- Modify: `services/api/tests/progress.test.ts`
- Modify: `services/api/tests/progress-http.test.ts`

**Interfaces:** Consumes the T2 contract. Produces GET version=2 envelope with 1000-row codepoint identity pages, strict bounded version-tagged opaque cursor, and versioned POST with firstAt/lastAt and full-batch {merged:N} ack. Disabled requests authenticate first and return exact HTTP 503 / progress_sync_version_unavailable; only absent GET version selectors use v1, and unversioned v1 behavior stays unchanged. Persisted v2 timestamps follow `0 < firstAt <= lastAt <= injectedNow + 172800000`; migration 054 removes the legacy 2025 lower-bound without editing migration 030.

- [x] **Step 1 — RED:** `versionTwoPreservesDistinctFirstAndLastAt`, `all1001IdentitiesTraverseWithEqualTimestamps`, `disabledV2DoesNotMaskAuthentication`, `invalidCursorDoesNotChangeLedger`, `legacyBodyStillRejectsVersionTwoFields`, `explicitUnsupportedVersionDoesNotFallThroughToLegacy`, and `versionTwoPersistsHistoricalTimestamps` assert HTTP status, full JSON, account-scoped DB state and strict ordering under database collation.
- [x] **Step 2 — Observe RED:** Baseline `make test-api` reported four expected original v2 behavior failures; T4 correction regressions were also observed RED (3 failed) against the pre-correction candidate.
- [x] **Step 3 — Implement:** Use explicit C/codepoint ordering on immutable identity, version-tag the opaque cursor, validate ranges and pair ordering, retain existing body bounds and v1 validation. Add the next additive migration to remove the arbitrary 2025 `first_at` constraint without editing migration 030. Do not add a new /v2 route, credential, cursor-signing secret or database snapshot promise.
- [x] **Step 4 — GREEN:** T4 focused PostgreSQL tests passed 17/17; `make check` passed 146 Python tests and contract/export checks; `cd services/api && npm run check` passed. Fresh full API run passed 756/756. Parent full-suite spot-check had an earlier environment failure (582/757; auth 401 and DB timeouts); parent focused rerun passed 17/17.
- [x] **Step 5 — Review:** Fresh independent review verified S4 after one correction round; all three findings were addressed with no new Critical/Important breakage.
- [x] **Step 6 — Commit:** `98ae12d` (`feat(progress): negotiate bounded version two sync`); commit and evidence recorded in OpenSpec tasks/verification report.

### Task 5: Add durable rollups, recovery and atomic continuation

**Requirements:** S2,S4,S5. **Prerequisites:** T2,T3,T4. **Status:** recovery source/tests committed as `5dd7da4`; Android targeted checks and independent high-risk recheck passed; Swift runtime verification is deferred to T16.

**Files:**
- Modify: `shared/db/schema.sql`
- Modify: `scripts/export_db_schema.py`
- Modify: `scripts/check_db_schema.py`
- Modify: `scripts/tests/test_export_db_schema.py`
- Modify: `scripts/tests/test_check_db_schema.py`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/data/LearningDb.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbDao.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbWriter.kt`
- Modify: `apps/android/app/src/test/java/com/jkdc/app/data/LearningDbTest.kt`
- Modify: `apps/ios/Core/Database/LearningDatabase.swift`
- Modify: `apps/ios/Core/Database/LearningDatabaseDao.swift`
- Modify: `apps/ios/Core/Database/LearningDbWriter.swift`
- Modify: `apps/ios/Tests/LearningDatabaseTests.swift`
- Create: `apps/android/app/src/main/java/com/jkdc/app/core/ProgressProjection.kt`
- Create: `apps/ios/Core/ProgressProjection.swift`
- Create: `apps/android/app/src/test/java/com/jkdc/app/core/ProgressProjectionTest.kt`
- Create: `apps/ios/Tests/ProgressProjectionTests.swift`
- Generate: `apps/android/app/src/main/java/com/jkdc/app/data/SchemaData.kt`
- Generate: `apps/ios/Core/Database/SchemaData.swift`

**Interfaces:** Produces account-scoped remote rollup and direction/cursor persistence, per-record timestamp source/version and unit evidence, evidence-backed recovery, atomic rollup-page+cursor apply and ack-only upload advance. `ProgressProjection` merges one aggregate identity using min(firstAt), max(lastAt,attempts,bestScore). T6/T7 must use these stores; T8 wires visible readers. Pre-v6 completion rows without provenance remain unchanged; new writes that may participate in recovery persist explicit evidence.

- [ ] **Step 1 — RED:** `pageAndCursorRollbackTogether`, `uncertainAckReplaysWithoutStarvation`, `upgradeFromV5PreservesExamOutboxAndConflicts`, `preV6CompletionWithoutProvenanceIsPreserved`, `evidenceBackedRecoveryRepairsOnlyProvenRecord`, `ambiguousTimestampIsRetainedAndValidRowsStillSync`, `recoveryRollbackAndSecondRunAreIdempotent`, and `projectionDoesNotAppendLocalEvents` assert persisted rows after close/reopen, rollback, owner partition, provenance decisions and full effective aggregate.
- [ ] **Step 2 — Observe RED:** Run `python3 scripts/export_db_schema.py --check && python3 scripts/check_db_schema.py` after adding the regression. Expected: the new behavior assertion fails for the original defect, not a test setup/compile typo. For a genuinely new contract/target, prove the intended unsupported behavior/configuration is the failure.
- [ ] **Step 3 — Implement:** Append schema version 6 only if still next; never edit old applied blocks. Update actual native openers, pinned inventory/versions and generate SQL outputs with export_db_schema.py. Test fresh install and every supported prior version. Persist timestamp source/version and unit evidence per eligible new row. Run repair before materialization only when that exact row has explicit, supported provenance; pre-v6 completion rows without provenance remain unchanged and unresolved. Keep bounded reasons and retry-safe markers. Do not infer origin or reclassify legacy completion history.
- [ ] **Step 4 — GREEN:** Repeat the focused command; expected: regression passes. Run the **Shared + Android unit + Swift Core** checks from the command table and record actual status/counts or exact unavailability.
- [ ] **Step 5 — Review:** Parent freezes the task candidate; fresh reviewer checks S2,S4,S5 scenarios, interfaces and task quality. Do not mark complete with missing required proof.
- [x] **Step 6 — Commit:** Recovery correction committed as `5dd7da4` (`fix(progress): repair explicitly evidenced legacy timestamps`). The earlier rollup checkpoint is `07685b5`; both remain separate work units. Platform-level completion remains open for T16 Swift verification.

**T5 recovery result:** Recovery recognizes only complete persisted writer/version/unit tuples, converts the explicitly tagged `ios.recordLessonAttempt` v1 Unix-seconds lesson timestamp to Apple-reference seconds, then reads back the exact owner-scoped row inside the transaction before persisting its repaired marker. Untagged pre-v6 rows are never tagged or changed. The tuple-bearing test fixture is synthetic and does not assert that existing v5 data contains provenance. Android focused tests and an independent fresh-scratch recheck passed; Swift execution remains deferred to T16. Risk: item 1 (persisted timestamp repair).

**Review finding and approved scope revision (2026-10-26):** The fresh reviewer found persisted recovery is not implemented by marker writes alone. Historical Android and iOS remote merges stored downloaded aggregates as ordinary `CompletionMark` rows, and the v5 schema persists only `(kind,item_id,at,best_score,language_id)` with no per-record source/unit evidence. The local completion writer uses `nowSeconds()`. The user approved revising S2/T5: pre-v6 rows without evidence remain byte-for-byte unchanged; new eligible writes persist explicit source/version and unit evidence; recovery repairs only supported evidence-bearing records and never infers provenance. T5 is reopened; the partial candidate remains uncommitted pending the correction and review.

**Verification note:** Original rollup/schema checks are recorded above. For the resumed recovery behavior, Android's exact recovery tests passed after a RED run; an independent verifier reproduced and closed the production adapter UPDATE-count mismatch, then passed the focused regression and rollback probe. The full Android suite passed before that final narrow adapter correction; it was not rerun afterward. SwiftPM/Xcode were not run per the user-requested T16 deferral.

### Task 17: Enforce active-account ownership across local learner data

**Requirements:** S1, S17 and account-bound portions of S3–S5. **Prerequisites:** Identity/startup map, T3 and the local persistence contracts from T5; must be completed before T6–T8. **Status:** newly authorized, planned.

**Files:** Android and iOS account identity/session stores, startup/root wiring, learning database schema/openers/DAOs/writers, archive export/import and reset/delete paths, progress sync admission, and their unit/integration tests. Exact paths are confirmed by the T17 implementation brief before code edits.

**Interfaces:** Every learner-data access receives a resolved namespaced active key: `member:<accountID>` or `guest:<installationUUID>`. Persist it on each mutable learner-owned row in the shared SQLite database; resolve it before migration, then backfill ownerless development rows once. Existing ownerless development rows are assigned exactly once to the active identity during an additive migration. Sign-out transitions to guest; guest→member never merges or exposes guest rows. Guest progress is local-only and member rows are the only progress eligible for backend sync; backend bootstrap is one-time and only for a member without local data on this device. Device-global cache/install metadata remains unowned.

- [ ] **Step 1 — RED:** Add public-flow regressions for A→B isolation across learner stores, migration-time backfill, guest→member isolation, owner-filtered archive/import/reset and member-only sync. Assert output and persisted data after close/reopen.
- [ ] **Step 2 — Observe RED:** Run focused Android and Swift/Core or App-hosted tests available for changed boundaries; confirm failure is missing ownership behavior, not compile/setup.
- [ ] **Step 3 — Implement:** Resolve identity before opening/migrating the learning database; scope every learner-data query and mutation; atomically switch active identity and invalidate stale work; backfill only currently ownerless development rows once; partition archives and destructive/reset operations by owner; prevent guest progress from backend requests. Do not merge guest data into a member or allow a later account to inherit the backfilled rows.
- [ ] **Step 4 — GREEN:** Run focused regressions, Android unit suite, schema/export checks and available Swift tests; record unavailable Xcode/device proof honestly.
- [ ] **Step 5 — Review:** Fresh reviewer validates every S17 scenario and migration/data-isolation boundary before parent commit.
- [ ] **Step 6 — Commit:** Parent commits reviewed bytes conventionally; record identity and evidence in SDD artifacts.

**Risk:** item 1 (persisted data migration and ownership boundaries).

### Task 6: Wire iOS time-safe complete transport

**Requirements:** S2,S3,S4,S5. **Prerequisites:** T3,T4,T5. **Status:** source and regressions committed as `c3719a0`; independent static/high-risk review passed after one correction. All Swift/iOS execution remains deferred to T16, so runtime/platform completion is still pending. RED/GREEN was not observed because of the T16 deferral and some App tests were added after implementation.

**Files:**
- Modify: `apps/ios/App/ProgressClient.swift`
- Modify: `apps/ios/App/ConversationCoordinator.swift`
- Modify: `apps/ios/App/Storage.swift`
- Modify: `apps/ios/Core/ProgressEngine.swift`
- Modify: `apps/ios/Tests/ProgressEngineTests.swift`
- Create: `apps/ios/AppTests/ProgressClientTests.swift`
- Modify: `apps/ios/App.xcodeproj/project.pbxproj` (register the AppTests source explicitly)

**Interfaces:** Consumes T2 time types, T3 fences and T5 atomic rollup/cursor APIs. Produces typed success, protocol-unavailable, cancellation and failure transport outcomes, not Boolean/nil conflation. V1 fallback uses corrected conversion and rollup writes but does not advance v2 cursors.

- [ ] **Step 1 — RED:** `all201UploadsAnd1001DownloadsSurviveRestart`, `downloadPersistUploadKeepsBothInstants`, `localLessonUsesReferenceEpoch`, `onlyExactUnavailableResponseDowngrades`, and `malformedPageLeavesCursorUnchanged` cover status/code and complete identity sets with no synthetic events.
- [ ] **Step 2 — Observe RED:** Run `swift test --package-path apps/ios --filter ProgressEngineTests` after adding the regression. Expected: the new behavior assertion fails for the original defect, not a test setup/compile typo. For a genuinely new contract/target, prove the intended unsupported behavior/configuration is the failure.
- [x] **Step 3 — Implement:** Correct the actual local lesson writer, decode once in HTTP adapter, validate complete/cursor consistency and page progress, traverse bounded passes and persist via T5. Enable the v2 bootstrap/upload transport in T6, but persist received rows only to T5 remote rollups/cursors; do not connect the visible progress projection until T8.
- [ ] **Step 4 — GREEN:** Repeat the focused command; expected: regression passes. Run the **Swift Core + iOS App** checks from the command table and record actual status/counts or exact unavailability.
- [x] **Step 5 — Review:** Fresh high-risk reviewer checked S2,S3,S4,S5 and found one blocking ProgressEngine property/unit mismatch; the one-line correction was independently re-reviewed as addressed. Static source review only; runtime compile/tests remain deferred to T16.
- [x] **Step 6 — Commit:** `c3719a0` (`fix(ios): wire checked resumable progress transport`); source-level review correction and static re-review are recorded. Swift compile/test evidence remains pending T16.

**T6 ruling:** S4 and the user's established rule require downloading only for a newly authenticated account with no local progress on that device. Therefore T6 wires v2 bootstrap/upload now and stores fetched rows in T5 remote rollups only; T8 remains responsible for exposing the effective projection to app readers. A dormant-only client that leaves production on recurring v1 downloads would contradict S4 and the user's rule.

**Fallback-state ruling:** On the exact unavailable response, a successful full v1 compatibility bootstrap may persist rollups and increment `passGeneration` as its completion marker while preserving the current opaque v2 cursor unchanged. This is not v2 cursor advancement; it prevents the v1 bootstrap from repeating before T8 projects remote rollups. Upload fallback must not mutate download state.

### Task 7: Wire Android time-safe complete transport

**Requirements:** S2,S3,S4,S5. **Prerequisites:** T3,T4,T5,T6. **Status:** Android implementation committed as `8b1b153`; Android proof and scoped review passed; shared iOS/runtime proof remains deferred to T16. Android focused RED observed for the missing v2 GET version selector. Keep unrelated pre-existing user modifications in `AppViewModel.kt` untouched.

**Files:**
- Modify: `apps/android/app/src/main/java/com/jkdc/app/network/ProgressClient.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/core/ProgressEngine.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/core/ProgressSyncOperation.kt` (extend the existing owner/generation-fenced runner for bounded page and upload-ack continuations)
- Modify: `apps/android/app/src/test/java/com/jkdc/app/core/ProgressEngineTest.kt`
- Modify: `apps/android/app/src/test/java/com/jkdc/app/core/ProgressSyncOperationTest.kt`
- Create: `apps/android/app/src/test/java/com/jkdc/app/network/ProgressClientTest.kt`
- Modify: `apps/android/app/src/test/java/com/jkdc/app/data/LearningDbTest.kt` (1001-row restart and malformed later-page persistence coverage)
- Modify: `apps/android/app/src/test/java/com/jkdc/app/data/JvmSqlDatabase.kt` (preserve 64-bit SQLite INTEGER values in the JVM test adapter)

**Interfaces:** Consumes the same T2/T3/T5 interfaces as iOS. Replaces current Boolean/null transport conflation with typed outcomes and converts wire timestamps only in the transport adapter.

- [ ] **Step 1 — RED:** `all201UploadsAnd1001DownloadsSurviveRestart`, `downloadPersistUploadKeepsBothInstants`, `onlyExactUnavailableResponseDowngrades`, `malformedPageLeavesCursorUnchanged` and stale-generation variants assert final persisted identity sets, emitted payloads, cursor and event counts.
- [ ] **Step 2 — Observe RED:** Run `cd apps/android && ./gradlew --offline :app:testDebugUnitTest --tests 'com.jkdc.app.network.ProgressClientTest' --tests 'com.jkdc.app.core.ProgressEngineTest'` after adding the regression. Expected: the new behavior assertion fails for the original defect, not a test setup/compile typo. For a genuinely new contract/target, prove the intended unsupported behavior/configuration is the failure.
- [x] **Step 3 — Implement:** Wire ProgressClient.kt as well as AppViewModel/engine. Use exact version negotiation, bounded deterministic suffix traversal, ack validation and T5 rollup write path, including corrected v1 compatibility. No app-facing v2 activation before T8.
- [x] **Step 4 — GREEN:** Focused T7 regressions and full Android unit/core suites passed: 786 app tests + 11 engine-local tests, 0 failures/errors/skips. Exact command: `./gradlew --offline --no-parallel --max-workers=1 :app:testDebugUnitTest :core:engine-local:testDebugUnitTest`.
- [x] **Step 5 — Review:** Fresh scoped review confirmed both Important transport findings fixed and found no blockers in the new database-backed regressions/JVM adapter update. Independent scratch verification passed all four targeted timestamp, fallback-cursor, 1001-row restart, and malformed-later-page tests.
- [x] **Step 6 — Commit:** Parent committed reviewed T7 files as `8b1b153` (`fix(android): wire checked resumable progress transport`).

### Task 8: Integrate effective progress reads and upload feedback

**Requirements:** S5. **Prerequisites:** T5,T6,T7. **Status:** implementation in progress; Android RED/GREEN and review required; Swift/iOS execution deferred to T16.

**Files:**
- Modify: `apps/android/app/src/main/java/com/jkdc/app/core/ProgressEngine.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/core/LevelEngine.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/core/MasteryEngine.kt`
- Modify: `apps/android/app/src/test/java/com/jkdc/app/core/ProgressProjectionTest.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt` (authorized by the user to connect Android read/upload entry points)
- Modify: `apps/ios/Core/ProgressEngine.swift`
- Modify: `apps/ios/Core/LevelEngine.swift`
- Modify: `apps/ios/Core/MasteryEngine.swift`
- Modify: `apps/ios/App/Storage.swift`
- Modify: `apps/ios/App/LearningProgressView.swift`
- Modify: `apps/ios/App/ListeningView.swift`
- Modify: `apps/ios/Tests/ProgressProjectionTests.swift`

**Interfaces:** Consumes T5 pure projection and T6/T7 stored rollups; produces visible score/mastery/completion evidence and future uploads from the same effective aggregate. Activity/streak/history readers continue to read local events only.

- [ ] **Step 1 — RED:** `remote90ImprovesLocal40InEitherOrder`, `projectionReplayDoesNotInventActivity`, `otherOwnerOrLanguageDoesNotContribute`, and `nextUploadRetainsRemoteBestAndFirstAt` cover all 10 supported kinds, legacy language, account switch and event/session counts.
- [ ] **Step 2 — Observe RED:** Run `cd apps/android && ./gradlew --offline :app:testDebugUnitTest --tests 'com.jkdc.app.core.ProgressProjectionTest'` after adding the regression. Expected: the new behavior assertion fails for the original defect, not a test setup/compile typo. For a genuinely new contract/target, prove the intended unsupported behavior/configuration is the failure.
- [ ] **Step 3 — Implement:** Wire score consumers rather than appending CompletionMark/SessionRecord. Preserve legacy rows unchanged and do not sum device attempts. Exercise v1 compatibility and local v2-on paths before clearing the integration gate; production stays untouched.
- [ ] **Step 4 — GREEN:** Repeat the focused Android command; expected: regression passes. Run Android unit tests. Do not run SwiftPM/Xcode/iOS build or test commands; the user explicitly deferred all Swift/iOS execution to T16. Record those platform checks as deferred, not passed.
- [ ] **Step 5 — Review:** Parent freezes the task candidate; fresh reviewer checks S5 scenarios, interfaces and task quality. Do not mark complete with missing required proof.
- [ ] **Step 6 — Commit:** Parent commits only reviewed task-owned files using `fix(progress): integrate aggregate projection without activity inflation`; record commit and evidence in OpenSpec tasks/verification report.

### Task 9: Make Android topic selection transactional

**Requirements:** S6. **Prerequisites:** T3. **Status:** complete; code-level review and focused tests passed. Android connected UI execution was unavailable (no device; APK assembly passed). Commit: `442c585` (independent of T5/T6–T8; checkpoint commits `07685b5`, `3173fec` recorded first).

**Files:**
- Modify: `apps/android/app/src/main/java/com/jkdc/app/ui/LibraryScreens.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/ui/AppRoot.kt`
- Create: `apps/android/app/src/test/java/com/jkdc/app/ThemeTransitionTest.kt`
- Create: `apps/android/app/src/androidTest/java/com/jkdc/app/ThemeTransitionUiTest.kt`

**Interfaces:** Produces pending-topic intent separate from the applied session, a single awaitable close/settlement/start transition, and invalidation on newer intent/account/navigation.

- [ ] **Step 1 — RED:** `cancelTopicSelectionLeavesSessionUnchanged`, `delayedCloseStartsExactlyOnce`, `timeoutDoesNotStartNewSession`, and `staleIntentCannotRestart` assert theme/title/context, sent instructions and session start counts; include double confirm.
- [ ] **Step 2 — Observe RED:** Run `cd apps/android && ./gradlew --offline :app:testDebugUnitTest --tests 'com.jkdc.app.ThemeTransitionTest'` after adding the regression. Expected: the new behavior assertion fails for the original defect, not a test setup/compile typo. For a genuinely new contract/target, prove the intended unsupported behavior/configuration is the failure.
- [ ] **Step 3 — Implement:** Apply no active mutation before confirmation. Await real terminal close and settlement, not a fixed delay. Surface close failure/timeout and discard only the pending selection on cancel.
- [x] **Step 4 — GREEN:** Focused `ThemeTransitionTest` passed (7/7) in parent and independent scratch. Android UI test APK assembled successfully; connected UI execution unavailable because `adb devices -l` reported no device.
- [x] **Step 5 — Review:** Independent bounded reviewer passed the typed/resumed terminal-close blocker and structurally checked pending-only selection, successful terminal close/settlement gating, stale/account/navigation checks, and cancel invalidation. Mounted AppViewModel state wiring was inspected but is not directly exercised by tests.
- [x] **Step 6 — Commit:** `442c585` (`fix(android): await confirmed topic transitions`); includes task-owned code/tests and SDD status. `git diff --check` passed before commit.

### Task 10: Bound voice capture and unify live turn context

**Requirements:** S7,S8,S9. **Prerequisites:** T9. **Status:** Needs decision after two correction rounds; S8 durable no-phantom-turn coverage still has a scheduling race because the fake source latch fires before producer classification. Independent scratch verification passed, but task review did not accept that as deterministic regression proof. No T10 commit; device audio/AEC proof also remains pending. T9 is complete; T5 remains independently partial. Baseline: `4d8942c`.

**Files:**
- Modify: `apps/android/app/src/main/java/com/jkdc/app/core/voice/LocalVoiceTransport.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt`
- Modify: `apps/android/app/src/test/java/com/jkdc/app/LocalVoiceTransportTest.kt`

**Interfaces:** Produces one bounded frame producer/VAD-barrier independent of a serial turn consumer, plus one role-aware bounded opening/typed/voice context. Record chosen preroll, active/manual limits, queue capacity and overflow handling in the task brief before code; derive from the actual sample/frame rate.

- [ ] **Step 1 — RED:** `initialSilenceRetainsBoundedSamples`, `speechAfterSilenceRetainsFirstSyllable`, `voiceReplyCanBeInterruptedWhileTtsBlocked`, `openingAppearsExactlyOnceInNextRequest`, and cross-mode/cancel/no-AEC/overflow regressions assert sample arrays, stop timing and exact request roles/order.
- [ ] **Step 2 — Observe RED:** Run `cd apps/android && ./gradlew --offline :app:testDebugUnitTest --tests 'com.jkdc.app.LocalVoiceTransportTest'` after adding the regression. Expected: the new behavior assertion fails for the original defect, not a test setup/compile typo. For a genuinely new contract/target, prove the intended unsupported behavior/configuration is the failure.
- [ ] **Step 3 — Implement:** Keep capture live during supported playback, own/cancel all child jobs, bound every state and discard echo/interruption frames. Preserve safe no-AEC behavior and existing typed-originated interruption regression.
- [ ] **Step 4 — GREEN:** Repeat the focused command; expected: regression passes. Run the **Android unit + Android audio device gate** checks from the command table and record actual status/counts or exact unavailability.
- [ ] **Step 5 — Review:** Parent freezes the task candidate; fresh reviewer checks S7,S8,S9 scenarios, interfaces and task quality. Do not mark complete with missing required proof.
- [ ] **Step 6 — Commit:** Parent commits only reviewed task-owned files using `fix(android): bound voice and unify conversation context`; record commit and evidence in OpenSpec tasks/verification report.

### Task 11: Expose accessible manual microphone mute

**Requirements:** S10. **Prerequisites:** T10. **Status:** implementation committed as `f57e3ee`; review approved. Verification remains incomplete: connected UI proof unavailable and no valid RED observed.

**Files:**
- Modify: `apps/android/app/src/main/java/com/jkdc/app/ui/TalkScreen.kt`
- Modify: `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt`
- Modify: `apps/android/app/src/main/res/values/strings.xml`
- Modify: `apps/android/app/src/main/res/values-es/strings.xml`
- Modify: `apps/android/app/src/test/java/com/jkdc/app/LocalVoiceTransportTest.kt`
- Create: `apps/android/app/src/androidTest/java/com/jkdc/app/ManualMuteUiTest.kt`

**Interfaces:** Consumes T10 voice input state. Produces a separate observable microphone mute with EN/ES text, preserved test tags/state description and >=48 dp target. It is not session pause or tutor-output stop.

**Risk:** none. T11 adds a manual-mode UI action and reuses the existing input-only mute operation; it does not change shared transport/API contracts.

- [ ] **Step 1 — RED:** `manualMuteIsVisibleAndAccessible`, `mutedSamplesNeverReachNextTurn` and `muteDoesNotStopTutorOutput` assert semantics/state, focus, touch target, STT payloads and unchanged active session. Tests authored; no valid RED observed because the UI runner had no device and transport mute behavior was already present.
- [ ] **Step 2 — Observe RED:** `LocalVoiceTransportTest` did not demonstrate the new separate UI control's absence; the test-first gate remains pending device-backed test execution or a deterministic unit seam.
- [x] **Step 3 — Implement:** Added separate manual mute UI, localized visible/action/state copy, and tests; existing input-only mute path preserves tutor playback and session.
- [ ] **Step 4 — GREEN:** Writer reported focused voice 45/45 and full Android + engine-local 804/804; parent's focused rerun hit one T10 `manual mode never auto-processes...` failure (`endTurn` expected 1, got 0); T11-only test passed 1/1. `:app:lintDebug :app:assembleDebug :app:assembleUiTestAndroidTest` passed (138 tasks, 13 executed). `:app:connectedUiTestAndroidTest` failed with `DeviceException: No connected devices!`; `emulator` CLI missing. Runtime UI proof remains pending.
- [x] **Step 5 — Review:** Fresh reviewer approved; advisory: runtime Compose accessibility/localization unverified without device.
- [x] **Step 6 — Commit:** `f57e3ee` (`feat(android): expose accessible manual microphone mute`) contains T11-owned files; unrelated T10 hunks remain unstaged. Implementation commit does not close the task while RED/runtime proof is missing.

### Task 12: Make iOS pronunciation private and recoverable

**Requirements:** S11,S12,S13. **Prerequisites:** T3. **Status:** implemented and reviewed; commit `c8c2d48`; iOS/Swift execution is deferred to T16 by user instruction.

**Files:**
- Modify: `apps/ios/App/Info.plist`
- Modify: `apps/ios/App/PronunciationCheckController.swift`
- Modify: `apps/ios/App/CoursesViews.swift`
- Modify: `apps/ios/App.xcodeproj/project.pbxproj`
- Create: `apps/ios/App/en.lproj/InfoPlist.strings`
- Create: `apps/ios/App/es.lproj/InfoPlist.strings`
- Create: `apps/ios/AppTests/PronunciationCheckControllerTests.swift`
- Modify: `apps/ios/UITests/AppUITests.swift`
- Modify: `openspec/changes/pronunciation-module/proposal.md`

**Interfaces:** Consumes T3 actual App test target. Produces request-scoped states and one terminal cleanup/cancel path with injected recognizer/recorder/time boundaries. Every adapter request requires on-device recognition; unsupported returns non-scored practice.

- [ ] **Step 1 — RED:** `deniedPermissionNeverRecords`, `recognizerErrorSettlesBusy`, `timeoutAndCancelCleanExactlyOnce`, `lateCallbackCannotScoreNextAttempt`, `unsupportedLocalModelMakesNoRequest`, and built-bundle permission tests cover unique file removal, owned audio release, navigation/background and successive attempts.
- [ ] **Step 2 — RED evidence deferred:** Author the regression tests first, but do not run SwiftPM, Xcode, or `make ios-test` during T12; the user explicitly deferred iOS app testing to T16. Record RED/GREEN as pending, not passed.
- [ ] **Step 3 — Implement:** Add accurate localized Speech/mic copy and resource membership without overwriting existing localizations. Retain recognition task, use unique attempt files, enforce supportsOnDeviceRecognition/requiresOnDeviceRecognition and preserve shared TTS audio ownership.
- [ ] **Step 4 — GREEN evidence deferred:** Do not execute SwiftPM, Xcode, or `make ios-test` during T12. Run the **Swift Core + iOS App + iOS UI/audio device gate** checks in T16 and record actual status/counts or exact unavailability; until then T12 remains verification-pending.
- [x] **Step 5 — Review:** Scoped reviewer and independent verifier accepted the `PronunciationUITestProbe: ObservableObject` correction with no new breakage. App compilation and runtime proof remain deferred to T16; this is not full platform verification.
- [x] **Step 6 — Commit:** Parent committed only reviewed task-owned files as `c8c2d48` (`fix(ios): enforce local pronunciation lifecycle`). iOS/Swift runtime proof remains deferred to T16.

### Task 13: Preserve typed replies across failure and retry

**Requirements:** S14. **Prerequisites:** T3. **Status:** implementation/review committed as `185b152`; Swift/iOS execution deferred to T16 by user instruction.

**Files:**
- Modify: `apps/ios/App/RootView.swift`
- Modify: `apps/ios/App/ConversationCoordinator.swift`
- Create: `apps/ios/AppTests/TypedReplyTests.swift`
- Modify: `apps/ios/UITests/AppUITests.swift`
- Modify: `apps/ios/App.xcodeproj/project.pbxproj` (register the new AppTests source in the explicit target source phase)

**Interfaces:** Produces a typed send result and stable pending message identity bound to conversation/account. Only success closes the matching editor; manual retries reuse the local entry and one in-flight operation.

- [ ] **Step 1 — RED:** `failedSendKeepsDraftFocusAndSheet`, `twoManualRetriesKeepOneLocalMessage`, `doubleSubmitStartsOneGeneration`, and `lateReplyCannotCloseNewSheet` assert UI/error persistence, transcript identity and request count. Preserve auth/balance behavior.
- [ ] **Step 2 — Observe RED:** Run `make ios-test` after adding the regression. Expected: the new behavior assertion fails for the original defect, not a test setup/compile typo. For a genuinely new contract/target, prove the intended unsupported behavior/configuration is the failure.
- [x] **Step 3 — Implement:** Distinguish success/cancel/failure, persist visible errors and do not auto-replay uncertain provider operations. Explicit dismissal invalidates local continuation; external provider costs are not exactly-once guaranteed.
- [ ] **Step 4 — GREEN:** Repeat the focused command; expected: regression passes. Run the **Swift Core + iOS App + iOS UI** checks from the command table and record actual status/counts or exact unavailability.
- [x] **Step 5 — Review:** Parent freezes the task candidate; fresh reviewer checks S14 scenarios, interfaces and task quality. Do not mark complete with missing required proof. Task review and two scoped correction re-reviews accepted; independent static verification found no blocker. Runtime proof remains deferred to T16.
- [x] **Step 6 — Commit:** Parent committed reviewed task-owned files as `185b152` (`fix(ios): preserve typed replies across retries`); Swift/iOS runtime proof remains pending T16.

### Task 14: Gate topic search by real engine capability

**Requirements:** S15. **Prerequisites:** T13 committed as `185b152`; iOS/Swift execution deferred to T16 by user instruction. **Status:** implementation/review committed as `e4a5375`.

**Files:**
- Modify: `apps/ios/App/LibraryViews.swift`
- Modify: `apps/ios/App/HostedSessions.swift`
- Modify: `apps/ios/App/ConversationCoordinator.swift`
- Create: `apps/ios/AppTests/TopicSearchCapabilityTests.swift`
- Modify: `apps/ios/UITests/AppUITests.swift`

**Interfaces:** Produces explicit source-backed-search capability for the selected engine, used before a fresh network/model request, with cached topic access unchanged.

- [ ] **Step 1 — RED:** `unsupportedEngineMakesZeroSearchCalls`, `unsupportedEngineKeepsCachedTopics`, and `capableEngineKeepsRealRetry` assert UI explanation, cache selection, successful source-backed results and distinct genuine network errors.
- [ ] **Step 2 — Observe RED:** Run `make ios-test` after adding the regression. Expected: the new behavior assertion fails for the original defect, not a test setup/compile typo. For a genuinely new contract/target, prove the intended unsupported behavior/configuration is the failure.
- [x] **Step 3 — Implement:** Gate impossible operations without provider switch, new search backend, proxy, key or billing changes.
- [ ] **Step 4 — GREEN:** Repeat the focused command; expected: regression passes. Run the **Swift Core + iOS App + iOS UI** checks from the command table and record actual status/counts or exact unavailability.
- [x] **Step 5 — Review:** Parent freezes the task candidate; fresh reviewer checks S15 scenarios, interfaces and task quality. Do not mark complete with missing required proof. Scoped review and fix re-review accepted; iOS runtime proof remains pending T16.
- [x] **Step 6 — Commit:** Parent committed reviewed task-owned files as `e4a5375` (`fix(ios): gate unsupported topic search`); Swift/iOS proof remains deferred to T16.

### Task 15: Notify session adoption before profile loading

**Requirements:** S16. **Prerequisites:** T3; T14 committed as `e4a5375`; iOS/Swift execution deferred to T16 by user instruction. **Status:** implementation in progress.

**Files:**
- Modify: `apps/ios/App/ManagedAccountStore.swift`
- Modify: `apps/ios/App/ConversationCoordinator.swift`
- Modify: `apps/ios/App/HostedSessions.swift`
- Create: `apps/ios/AppTests/SessionAdoptionTests.swift`

**Interfaces:** Produces one adoption event only after secure persistence/in-memory commit, before profile await. Keeps existing credential-invalidated transition symmetric and independent from recoverable profile errors.

- [ ] **Step 1 — RED:** `profile500StillPublishesAdoptedBearer`, `profileRefreshDoesNotRepublishAdoption`, `actualCredentialRejectionInvalidates` and persistence-failure/stale-exchange variants assert Keychain session, notification order/count and helper bearer before starting conversation.
- [ ] **Step 2 — Observe RED:** Run `make ios-test` after adding the regression. Expected: the new behavior assertion fails for the original defect, not a test setup/compile typo. For a genuinely new contract/target, prove the intended unsupported behavior/configuration is the failure.
- [ ] **Step 3 — Implement:** Separate session adoption from profile hydration. A profile 500 is not logout; actual rejection follows existing policy. Preserve T3 checks against live owner/generation rather than relying only on notifications.
- [ ] **Step 4 — GREEN:** Repeat the focused command; expected: regression passes. Run the **Swift Core + iOS App** checks from the command table and record actual status/counts or exact unavailability.
- [ ] **Step 5 — Review:** Parent freezes the task candidate; fresh reviewer checks S16 scenarios, interfaces and task quality. Do not mark complete with missing required proof.
- [ ] **Step 6 — Commit:** Parent commits only reviewed task-owned files using `fix(ios): notify session adoption independently of profile`; record commit and evidence in OpenSpec tasks/verification report.

### Task 16: Verify the complete remediation and report gaps

**Requirements:** S1–S16. **Prerequisites:** T1–T15. **Status:** pending.

**Files:**
- Modify: `openspec/changes/mobile-audit-remediation/verify-report.md`
- Modify: `openspec/changes/mobile-audit-remediation/tasks.md`

**Interfaces:** Consumes per-task test evidence and reviewer decisions. Produces one requirement-to-proof matrix with exact commands, failure/skip counts, device/bundle evidence and honest incomplete gates.

- [ ] **Step 1 — Evidence reconciliation:** Compare each scenario against a real executed test. Parser-only or Swift Core green never substitutes for database or actual App tests. Verify old v1, v2-on/off, earliest/latest, schema upgrades, owner boundaries and no invented activity together.
- [ ] **Step 2 — Baseline checks:** Run `make check` and read every output. Evidence documentation itself does not require an artificial failing behavior test.
- [ ] **Step 3 — Implement:** Run shared/API/Android/Swift/App/UI checks where available, fresh whole-feature review, and one bounded correction batch with RED→GREEN for actual blockers. Keep production activation and physical audio validation separate; unexecuted proof stays pending.
- [ ] **Step 4 — Final proof:** Run the final applicable command set; expected: every executed required check exits zero, with no hidden skipped integration cases. Run the **Shared + API integration + Android unit + Android lint/build + Swift Core + iOS App + Android UI + device gates** checks from the command table and record actual status/counts or exact unavailability.
- [ ] **Step 5 — Review:** Parent freezes the task candidate; fresh reviewer checks S1–S16 scenarios, interfaces and task quality. Do not mark complete with missing required proof.
- [ ] **Step 6 — Commit:** Parent commits only reviewed task-owned files using `docs(mobile): record remediation verification`; record commit and evidence in OpenSpec tasks/verification report.


## Task 15 implementation record

Commit `e545c79` implements session adoption notification before profile loading, with deterministic production-flow tests. Static source checks and independent review passed; Swift/iOS runtime validation remains deferred to T16 by user instruction.
