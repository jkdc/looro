# Proposal: Make Local Learning-Data Erasure Complete and Durable

## Intent

Align the Android and iOS local-device deletion behavior with the existing user-facing promise. When a user confirms deletion, all covered local conversations, vocabulary, and progress must be durably removed from active stores and internal historical artifacts without deleting credentials, account state, preferences, schema flags, or migration commit markers.

This change addresses audit finding F02. It is strictly a local-device erasure contract: it does not delete server-held data or the user's account, and a later legitimate synchronization may restore remote progress.

## Scope

### In Scope

- Define the cross-platform local learning-data set as sessions/fragments, lesson state, expressions, completions, skill errors, assessment tickets, hidden words, and mastery cache.
- Clear all covered SQLite rows in one transaction on each platform while preserving preferences, secure credentials/account state, schema flags, and migration commit markers.
- Quiesce deferred writers and invalidate in-flight progress fetches before erasure; publish an empty observable archive only after durable deletion succeeds.
- Idempotently remove or sanitize legacy migration payloads, backups, and restore/verification staging artifacts without re-arming migration.
- Surface partial database/artifact cleanup as failure rather than claiming complete erasure.
- Add Android and iOS tests that seed every covered store and artifact, erase, restart, and prove that learning data is neither retained nor re-imported.

### Out of Scope

- Remote progress deletion, account deletion, remote tombstones, or permanent suppression of future synchronization.
- Erasure of `usage_outbox`, which is outside the archive-projected learning-data contract.
- Forensic byte sanitization of SQLite pages, storage media, operating-system backups, or cloud backups.
- Resetting the entire local database or reconstructing preserved settings from scratch.
- Rewording the UI to narrow the existing deletion promise instead of implementing it.

## Capabilities

### New Capabilities

- `local-learning-data-erasure`: Defines the covered local learning data, preservation boundary, durable deletion ordering, historical-artifact cleanup, stale-work invalidation, failure semantics, and restart guarantees for Android and iOS.

### Modified Capabilities

None. No existing OpenSpec capabilities are present for this behavior.

## Approach

Introduce a dedicated erasure operation on each platform. The operation first quiesces active conversation and deferred persistence work and advances a generation boundary so stale progress-fetch results cannot commit. It then deletes all covered archive-projected rows within the platform's existing SQLite transaction.

After the database commit, an idempotent artifact scrub removes or sanitizes legacy JSON/SwiftData payloads, migration backups, and restore staging copies while retaining migration commit markers. In the iOS fail-safe store, the active legacy payload is replaced with a preferences-preserving empty archive; after database migration, the historical payload is removed while its committed marker remains. The coordinator replaces observable and derived state only after the durable operation succeeds. Any incomplete cleanup returns a failure and remains safe to retry.

No schema migration or new external dependency is expected. The implementation should use injectable filesystem/store seams so artifact inventories and restart behavior are testable without relying on production paths.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt` | Modified | Coordinate quiescence, stale-sync invalidation, durable erasure, state publication, and notices. |
| `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbWriter.kt` | Modified | Expand transactional deletion to every covered learning table. |
| `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbStore.kt` | Modified | Scrub legacy and restore artifacts while preserving migration gates. |
| `apps/android/app/src/test/java/com/jkdc/app/data/LearningCutoverFlowTest.kt` | Modified | Invert the surviving-progress expectation and cover the complete erasure contract. |
| `apps/android/app/src/test/java/com/jkdc/app/data/LearningDbStoreTest.kt` | Modified | Prove restart, migration-source, artifact cleanup, and retry behavior. |
| `apps/ios/App/ConversationCoordinator.swift` | Modified | Cancel or invalidate deferred work and coordinate durable deletion before UI refresh. |
| `apps/ios/App/Storage.swift` | Modified | Preserve preferences and migration gates while scrubbing SwiftData, backup, and restore artifacts. |
| `apps/ios/Core/Database/LearningDbWriter.swift` | Modified | Expand transactional deletion to every covered learning table. |
| `apps/ios/Tests/LearningCutoverFlowTests.swift` | Modified | Invert the surviving-progress expectation and cover the complete erasure contract. |
| `apps/ios/Tests/LearningDbStoreTests.swift` | Modified | Prove restart, migration-source, artifact cleanup, and retry behavior. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| SQLite deletion succeeds but artifact cleanup fails. | Medium | Order database deletion before an idempotent scrub, return a retryable failure, and never display complete success for partial cleanup. |
| Deleting a migration marker re-arms import from a surviving source. | Medium | Preserve committed migration gates, distinguish them from erasable payloads, and test restart after cleanup. |
| Deferred saves or an in-flight progress response repopulate erased state. | Medium | Quiesce writers and use a generation/token boundary checked before stale work can commit. |
| Preserved preferences or credentials are deleted accidentally. | Low | Specify the preservation boundary explicitly and assert preserved values in platform tests. |
| Future remote synchronization restores server-held progress and surprises users. | Medium | Keep this change explicitly local-only; do not imply remote deletion and cover only stale work that began before erasure. |
| Ordinary SQLite deletion leaves recoverable bytes outside the application model. | Low | Document the application-level threat model; require a separate secure-delete design before promising forensic erasure. |

## Rollback Plan

Revert the platform coordinator, database-writer, artifact-scrub, and test changes together; no schema downgrade is expected. If post-release failures threaten preserved settings or startup reliability, disable the destructive action or fail it closed until a corrected build is available rather than falling back to the known-incomplete deletion path.

Rollback cannot restore learning data that a user already erased successfully. Preserve this irreversible boundary in release and support guidance, and never attempt recovery from the historical artifacts this change is designed to remove.

## Dependencies

- Existing Android and iOS SQLite transaction and migration abstractions.
- Testable filesystem and legacy-store seams for enumerating and scrubbing platform-specific artifacts.
- Native build environments are required for final platform verification; the audit environment could not run Android or iOS checks.

## Success Criteria

- [ ] Android and iOS erase every covered learning-data table in a single platform-local SQLite transaction while preserving preferences, credentials/account state, schema flags, and migration commit markers.
- [ ] Legacy payloads, migrated backups, and restore/verification staging artifacts cannot re-import erased learning data after restart.
- [ ] A failed or partial artifact scrub is reported as failure, is safe to retry, and never produces a completed-erasure notice.
- [ ] Observable archives and derived UI state become empty only after durable deletion succeeds; persistence failure does not leave a falsely empty UI state.
- [ ] Deferred writers and progress fetches started before erasure cannot repopulate the erased local state.
- [ ] Platform tests seed all covered tables and artifacts, erase, reopen/restart, and prove absence of erased data and preservation of excluded state.
- [ ] Verification reports native or environment-blocked checks honestly and does not claim remote, forensic, device, or account deletion guarantees.
