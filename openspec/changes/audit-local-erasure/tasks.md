# Tasks: Complete and Durable Local Learning-Data Erasure

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 1,100–1,500 authored lines across Android/iOS storage, coordinators, localization, and tests |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1: Android storage and tests → PR 2: iOS storage and tests → PR 3: cross-platform coordination, stale-work invalidation, localization, and final integration checks |
| Delivery strategy | auto-chain |
| Chain strategy | pending parent decision: stacked-to-main or feature-branch-chain |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: pending
400-line budget risk: High

The synchronized-rollup storage introduced by `audit-progress-sync` is part of this erasure contract. The implementation MUST include `progress_rollups` and `progress_sync_state` in the durable local erase boundary (for every locally retained account partition, without deleting account credentials/state); it MUST NOT leave rollups or resume cursors that can resurrect hidden local progress. This change consumes those schema-v3 tables and does not add another schema migration.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 | Implement Android database/artifact erasure, including synchronized rollups and resume state, with storage tests | PR 1 | `./gradlew :app:testDebugUnitTest --tests 'com.jkdc.app.data.LearningCutoverFlowTest' --tests 'com.jkdc.app.data.LearningDbStoreTest'` | JVM SQLite plus fake `LearningDbStore.Files`; no device required | Revert Android `LearningDbWriter.kt` and `LearningDbStore.kt` changes plus their tests; no schema rollback |
| 2 | Implement iOS database/artifact erasure, including synchronized rollups and resume state, with store tests | PR 2 | `swift test --package-path apps/ios --filter LearningDbStoreTests` | In-memory database and injected filesystem/SwiftData seams; Xcode app runtime not required for package tests | Revert iOS `LearningDbWriter.swift` and `Storage.swift` changes plus their tests; no schema rollback |
| 3 | Wire both coordinators, generation gates, notices/localization, and stale-work integration checks | PR 3 | `./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest` and `swift test --package-path apps/ios` | Android JVM and Swift package harnesses; application/device/Xcode checks remain environment-dependent | Revert coordinator/generation/localization changes while retaining storage transaction work |

## Phase 1: RED Contract and Recovery Tests

- [ ] 1.1 Update `apps/android/app/src/test/java/com/jkdc/app/data/LearningCutoverFlowTest.kt` to seed every covered category (sessions/fragments, lesson state, expressions, completions, skill errors, assessment tickets, hidden words, mastery metadata) plus `progress_rollups` and `progress_sync_state`; assert erasure removes all covered rows while preserving credentials/account state, preferences, schema/migration metadata, and `usage_outbox`.
- [ ] 1.2 Extend `apps/android/app/src/test/java/com/jkdc/app/data/LearningDbStoreTest.kt` with the complete historical-artifact inventory (`learning.json`, backups, migrated copies, preimport/install/backup-verify files, and stranded verification copies), fail-safe sanitization, restart/no-resurrection, migration-marker preservation, partial cleanup failure, and retry scenarios.
- [ ] 1.3 Add the stale-generation RED cases to `apps/android/app/src/test/java/com/jkdc/app/core/ProgressEngineTest.kt`: a G0 progress response/deferred write must be rejected after erasure advances to G1, while a new G1 synchronization remains accepted.
- [ ] 1.4 Update `apps/ios/Tests/LearningCutoverFlowTests.swift` with the same covered/excluded seed matrix, explicitly asserting synchronized-rollup and sync-resume rows are removed without deleting account credentials/state, preferences, migration markers, or `usage_outbox`.
- [ ] 1.5 Extend `apps/ios/Tests/LearningDbStoreTests.swift` with payload/backup/staging inventory, fail-safe empty-payload preservation, restart/no-resurrection, marker preservation, partial cleanup failure, idempotent retry, and database-rollback cases.
- [ ] 1.6 Add equivalent stale-token RED cases to `apps/ios/Tests/ProgressEngineTests.swift`, proving pre-boundary results cannot mutate the archive or database and post-boundary synchronization can proceed.

## Phase 2: Android Durable Storage

- [ ] 2.1 Expand `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbWriter.kt` with one transaction that deletes every covered learning table, mastery metadata, all local `progress_rollups`, and all local `progress_sync_state` rows while preserving `usage_outbox`, non-mastery metadata, migration/schema markers, credentials, account state, and preferences; return a typed database-failure outcome without partial success.
- [ ] 2.2 Extend `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbStore.kt` with an injectable artifact seam and mode-aware idempotent scrub: database mode removes all legacy payloads/backups/staging copies, fail-safe JSON mode replaces only the active payload with an empty preferences-preserving archive, missing artifacts are successful, and non-missing failures produce an incomplete-cleanup result without deleting migration gates.
- [ ] 2.3 Implement the Android result/retry boundary in `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbStore.kt`: run database deletion before artifact cleanup, reload the durable empty projection after post-commit cleanup failure, and expose enough typed state for the coordinator to avoid claiming success.
- [ ] 2.4 Add the local-device success and incomplete-erasure messages to `apps/android/app/src/main/res/values/strings.xml` and the corresponding neutral existing-project Spanish translations to `apps/android/app/src/main/res/values-es/strings.xml`; messages MUST not imply account or remote deletion.

## Phase 3: iOS Durable Storage

- [ ] 3.1 Expand `apps/ios/Core/Database/LearningDbWriter.swift` with the matching single transaction for all covered learning rows, mastery metadata, synchronized `progress_rollups`, and `progress_sync_state`, preserving `usage_outbox`, non-mastery metadata, migration markers, credentials, account state, and preferences.
- [ ] 3.2 Implement `eraseLocalLearningData()` and typed database/cleanup outcomes in `apps/ios/App/Storage.swift`, ordering the database commit before artifact cleanup and reloading the active empty projection on post-commit failure.
- [ ] 3.3 Add injectable filesystem/SwiftData seams in `apps/ios/App/Storage.swift` for deleting database-mode payloads and historical artifacts, or sanitizing the fail-safe active payload while preserving allowed preferences and `app-v1.migrated`; treat missing artifacts as already clean and make retries idempotent.

## Phase 4: Quiescence, Generation, and Publication Ordering

- [ ] 4.1 Update `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt` to cancel owned conversation/assessment/review/progress jobs, advance `storageGeneration`, reject stale queued `WriteRequest`s, await the ordered erasure request, and publish the empty archive/derived-state reset/success notice only after database and artifact cleanup succeed.
- [ ] 4.2 Update `apps/android/app/src/main/java/com/jkdc/app/core/ProgressEngine.kt` and its tests to carry the generation through progress fetch, archive merge, enqueue, and send boundaries; stale G0 work MUST be side-effect free while a later G1 sync remains allowed to restore server-held progress.
- [ ] 4.3 Update `apps/ios/Core/ProgressEngine.swift` to expose the generation/token gate used by the coordinator and ensure stale results cannot be projected into local state or written to the database.
- [ ] 4.4 Update `apps/ios/App/ConversationCoordinator.swift` to retain the active-conversation refusal, cancel deferred work and progress sync, invalidate final assessments, advance `dataGeneration`, await `Storage.eraseLocalLearningData()`, and publish success/empty state only for a fully successful result; database and cleanup failures MUST retain/reload durable state and show no success notice.
- [ ] 4.5 Complete the integration assertions in `apps/android/app/src/test/java/com/jkdc/app/data/LearningCutoverFlowTest.kt` and `apps/ios/Tests/LearningCutoverFlowTests.swift` for publication ordering: controlled database and artifact barriers must show that observable state changes only after durable success, while failures never present a falsely empty completed-erasure state.

## Phase 5: Cross-Platform Verification and Delivery Slices

- [ ] 5.1 Run `./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest` from `apps/android`, record passing/failing/unavailable results, and do not claim device, instrumentation, lint, or full application-build coverage when the environment cannot provide it.
- [ ] 5.2 Run `swift test --package-path apps/ios`, record package results, and separately report Xcode application/UI checks as unavailable when macOS/Xcode or a simulator is absent.
- [ ] 5.3 Run the applicable root contract checks (`make check`) after both platform implementations are integrated; preserve the audit report's limitation that no single workspace command proves native, database, remote, forensic, or account-deletion behavior.
- [ ] 5.4 Split implementation commits according to the selected chain strategy: PR 1 contains only Android storage/tests, PR 2 only iOS storage/tests, and PR 3 only cross-platform coordination/generation/localization/integration checks; each slice keeps its focused tests and rollback boundary together and remains at or below the 400-line review budget where practicable.

