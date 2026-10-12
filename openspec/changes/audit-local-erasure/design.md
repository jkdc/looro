# Design: Complete and Durable Local Learning-Data Erasure

## Technical Approach

Implement erasure as a platform-owned, ordered operation rather than another ordinary archive mutation. Both clients establish a new storage generation, quiesce producers of learning writes, perform one SQLite transaction that removes every covered row while preserving excluded state, then scrub historical artifacts through injectable platform filesystem/legacy-store seams. Only a fully successful database-and-artifact result publishes the success notice and the preferences-preserving empty archive.

The database transaction and artifact cleanup are deliberately separate durability domains. The transaction is the irreversible first commit; artifact cleanup is idempotent and retryable. If cleanup fails after the commit, the client reloads the active durable projection, exposes an explicit incomplete-erasure error, and does not claim that historical copies were removed. A retry repeats the empty database transaction and resumes the scrub safely.

This maps directly to the `local-learning-data-erasure` specification: the database operation covers sessions/fragments, lesson state, expressions, completions, skill errors, assessment tickets, hidden words, and mastery metadata; it preserves credentials/account state, all other preferences, schema and migration metadata, and `usage_outbox`. Storage-generation and progress-sync tokens prevent work started before the boundary from committing afterward.

## Architecture Decisions

### Decision: Use one explicit erasure transaction instead of reusing migration cleanup

**Choice**: Expand `LearningDbWriter.learningDataDeleted` on both platforms into the canonical erasure transaction. It explicitly deletes `fragments`, `sessions`, `expressions`, `lesson_state`, `completions`, `skill_errors`, and `assessment_tickets`, deletes only `meta` keys under `MetaDao.masteryPrefix`, and writes the existing preferences with `hiddenWords` emptied. `usage_outbox`, non-mastery `meta` rows (including `schema_flags`, `migration_hash`, and `migrated_at`), and all other preferences remain untouched.

**Alternatives considered**: Reuse `LearningDbStore.clearLearningTables`; reset the whole database; issue independent DAO calls.

**Rationale**: Migration cleanup intentionally clears `usage_outbox` and runs only while the migration gate is open, so it has the wrong preservation contract. Resetting the database would require reconstructing preserved state and reopening live stores. One dedicated transaction gives the erase operation its own explicit, testable boundary and avoids partial row deletion.

### Decision: Commit the database before scrubbing historical artifacts

**Choice**: Execute two ordered stages: (1) the SQLite erasure transaction, then (2) idempotent legacy/restore-artifact cleanup. Return a typed outcome that distinguishes database failure from post-commit artifact-cleanup failure.

**Alternatives considered**: Scrub artifacts first; optimistically clear UI first; attempt an artificial cross-store transaction.

**Rationale**: SQLite and filesystem/SwiftData cannot share a transaction. Database-first guarantees that active rows are gone before any success is possible. Artifact-first could remove the only fail-safe source before the database commit. A typed partial result makes the irreversible commit visible without lying about complete erasure.

### Decision: Preserve migration gates while removing or sanitizing payloads

**Choice**: Treat migration markers and payloads as different classes. Keep Android `migration_hash`/`migrated_at` and iOS `app-v1.migrated`; remove historical payloads only in database mode. In fail-safe legacy mode, replace the active payload with a preferences-preserving empty archive rather than deleting the only writable source.

**Alternatives considered**: Delete markers and payloads together; retain historical payloads because the marker normally ignores them.

**Rationale**: Deleting a marker can re-arm import, while retaining a payload violates the erasure promise. Fail-safe mode is the exception: deleting its active source would make the next open unable to recover safely, so sanitization is required instead.

### Decision: Model stale work with a storage generation, not cancellation alone

**Choice**: Each coordinator owns a monotonically changing storage generation. Deferred learning writes and progress sync capture the generation at start and must match it immediately before mutating the working archive, enqueuing/writing SQLite changes, or sending a snapshot. Erasure cancels known jobs and advances the generation before any durable work.

**Alternatives considered**: Cancel jobs only; wait only for Android's FIFO barrier; suppress all future remote synchronization.

**Rationale**: Cancellation is cooperative and cannot reject a result already returning from the network. The Android FIFO drains only work already enqueued and does not stop an older producer from enqueueing later. A generation check closes both races while allowing a new, legitimate post-erasure sync to restore server-held progress, as required by the local-only boundary.

### Decision: Adapt ordering to each platform's existing persistence model

**Choice**: Android tags every `WriteRequest` with the storage generation and performs erasure as an awaited request on the existing FIFO/IO consumer. iOS keeps synchronous directed database writes on `@MainActor`, cancels deferred tasks, advances its existing `dataGeneration`, and calls a throwing store erasure operation directly.

**Alternatives considered**: Introduce a second Android writer; make all iOS storage asynchronous; force identical coordinator implementations.

**Rationale**: The Android channel is already the single-writer boundary and provides an ordering point. iOS directed writes are already synchronous and serial on the main actor. Preserving these patterns reduces concurrency surface while enforcing the same external contract.

## Data Flow

### Successful erasure

```text
User confirmation
      |
      v
Coordinator refuses while a conversation is active
      |
      v
Cancel deferred saves/assessments/reviews + cancel progress sync
Advance storage generation; old producers and queued writes become stale
      |
      v
SQLite transaction
  - delete covered learning rows and mastery metadata
  - persist preferences with hiddenWords = []
  - preserve usage_outbox, schema/migration metadata, account/credentials
      |
      v
Idempotent artifact scrub
  - database mode: delete legacy payloads/backups/staging copies
  - fail-safe mode: replace active legacy payload with empty preserved preferences
  - retain committed migration markers
      |
      v
Publish empty archive + clear derived learning UI + local-only success notice
```

### Failure and retry

```text
Database transaction fails
      -> transaction rolls back
      -> reload/retain durable archive
      -> report failure; do not scrub; do not publish success

Database commits, artifact scrub fails
      -> reload active database projection (now empty)
      -> report incomplete erasure; do not publish success
      -> retain committed migration gates
      -> later retry repeats the empty transaction and scrub
```

### Stale progress response

```text
sync starts with token G0 ---- network fetch is in flight
                                 |
erase advances generation G1 ---+
                                 v
fetch completes with G0 -> token mismatch -> no merge, no DB write, no send

later explicit/startup sync captures G1 -> normal local merge is allowed
```

## Platform-Specific Ordering and Recovery

### Android

1. `AppViewModel.deleteLearningData` launches one owned erasure job after the existing busy/storage guards.
2. It dismisses reports; cancels final-assessment, hosted-assessment, scenario-review, conversation-reset, and progress-sync jobs; resets transient conversation state; and advances `storageGeneration` before awaiting storage.
3. `persist` captures the current generation in `WriteRequest`. The FIFO consumer discards stale requests and completes their waiters without executing learning writes. The erasure request is tagged with the new generation and is awaited, so success cannot race durability.
4. In database mode, `LearningDbWriter.learningDataDeleted` commits the complete deletion transaction. In JSON fail-safe mode, `LearningRepository.save` atomically writes a preferences-preserving empty snapshot; the database is not presented as authoritative for that run.
5. The `LearningDbStore.Files` seam is extended to scrub `learning.json`, `.bak`, `.migrated`, `.migrated.bak`, `learning.db.preimport`, `learning.db.install-tmp`, `learning.db.backup-verify`, and stranded `backup-import-verify*.db` copies. In database mode all payload copies are removed; in JSON mode the active `learning.json` remains as the sanitized source and only stale siblings are removed.
6. Only after the scrub succeeds does the view model replace all covered archive collections, clear tickets/derived learning state, bump the ledger revision, and show the local-device success notice. On a database failure it reloads the prior projection. On a post-commit scrub failure it reloads the now-empty database projection and exposes an incomplete-erasure error; retry starts again at step 3.

### iOS

1. `ConversationCoordinator.deleteLearningData` retains the running-conversation refusal, cancels meaning/assessment/debounced-save/scenario-review work, cancels the owned progress-sync task, invalidates final assessments through `onSessionInvalidation`, and advances the existing `dataGeneration` before touching storage.
2. `LearningStore.eraseLocalLearningData()` performs the database transaction synchronously on `@MainActor` through `LearningDbWriter`, then performs artifact cleanup. It returns success only when both stages finish.
3. In database mode, remove the SwiftData `app-v1` payload but preserve `app-v1.migrated`, and remove `before-language-modules.json`, `learning.db.preimport`, `.install-tmp`, `.backup-verify`, and stranded verification temp files. In JSON fail-safe mode, replace `app-v1` with an encoded empty archive carrying the current preferences and leave the prior marker state unchanged.
4. `FileManager` and SwiftData payload operations are wrapped by injectable closures/protocols used by `LearningDbStoreTests`; a missing artifact is success, while any other failure is collected and returned as incomplete cleanup.
5. Success publishes the empty archive and clears derived state. Database failure reloads the pre-existing database projection. Post-commit cleanup failure reloads the active empty projection and sets an explicit incomplete-erasure error without a success message. A repeated call is safe.

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt` | Modify | Add owned erasure/progress jobs, storage-generation tagging, awaited erase ordering, durable-state reload, and success/incomplete result handling. |
| `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbWriter.kt` | Modify | Expand `learningDataDeleted` into the complete covered-row transaction while preserving excluded rows and metadata. |
| `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbStore.kt` | Modify | Retain/extend the artifact seam and implement mode-aware, idempotent historical-artifact cleanup. |
| `apps/android/app/src/main/res/values/strings.xml` | Modify | Add local-device success and incomplete-erasure messages. |
| `apps/android/app/src/main/res/values-es/strings.xml` | Modify | Add the corresponding existing-project Spanish localization. |
| `apps/android/app/src/test/java/com/jkdc/app/data/LearningCutoverFlowTest.kt` | Modify | Invert the surviving-progress expectation and assert every covered/excluded database category. |
| `apps/android/app/src/test/java/com/jkdc/app/data/LearningDbStoreTest.kt` | Modify | Cover artifact inventories, fail-safe sanitization, restart, partial scrub, retry, and migration-marker preservation. |
| `apps/android/app/src/test/java/com/jkdc/app/core/ProgressEngineTest.kt` | Modify | Cover generation invalidation of an in-flight progress result and acceptance of a later sync. |
| `apps/ios/App/ConversationCoordinator.swift` | Modify | Track/cancel progress sync, advance `dataGeneration` for erasure, and publish state only from the typed store outcome. |
| `apps/ios/App/Storage.swift` | Modify | Implement the ordered store operation, SwiftData mode-aware payload handling, filesystem scrub seam, reload behavior, and local-only result messages. |
| `apps/ios/Core/Database/LearningDbWriter.swift` | Modify | Mirror the complete Android erasure transaction. |
| `apps/ios/Core/ProgressEngine.swift` | Modify | Provide the small generation/token gate used by the coordinator. |
| `apps/ios/Tests/LearningCutoverFlowTests.swift` | Modify | Invert the surviving-progress expectation and assert every covered/excluded database category. |
| `apps/ios/Tests/LearningDbStoreTests.swift` | Modify | Cover payload/marker behavior, artifact cleanup, restart, partial scrub, retry, and fail-safe sanitization. |
| `apps/ios/Tests/ProgressEngineTests.swift` | Modify | Cover stale-token rejection and later legitimate sync acceptance. |

No schema migration, API change, or external dependency is required.

## Interfaces / Contracts

The exact names may follow local naming during implementation, but the behavior is fixed:

```kotlin
sealed interface LocalErasureResult {
    data class Succeeded(val archive: Archive) : LocalErasureResult
    data class DatabaseFailed(val cause: Throwable) : LocalErasureResult
    data class CleanupIncomplete(val archive: Archive, val cause: Throwable) : LocalErasureResult
}

data class WriteRequest(
    val generation: Long,
    val work: () -> Unit,
    val completion: CompletableDeferred<Unit>? = null,
)

interface LearningArtifacts {
    fun sanitizeActiveLegacy(emptyArchive: ByteArray)
    fun scrubHistoricalCopies(databaseMode: Boolean)
}
```

```swift
enum LocalErasureResult {
    case succeeded(Archive)
    case databaseFailed(Error)
    case cleanupIncomplete(archive: Archive, error: Error)
}

struct ProgressSyncToken: Equatable { let generation: UUID }

protocol LearningArtifactScrubbing {
    func sanitizeActiveLegacy(with emptyArchive: Data) throws
    func scrubHistoricalCopies(databaseMode: Bool) throws
}
```

Contract invariants:

- A `Succeeded` result means both the active store and every enumerated historical artifact are clean.
- `CleanupIncomplete` means the database commit may already be irreversible; callers MUST reload its active projection, MUST NOT show success, and MAY retry the whole operation.
- Missing artifacts are treated as already clean.
- Cleanup MUST NOT delete migration commit markers or `usage_outbox`.
- A pre-boundary generation MUST NOT mutate the archive or covered database tables after erasure starts.
- A new post-boundary sync remains permitted; this feature does not create a remote tombstone.

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Unit | Complete database deletion and preservation boundary | Seed all eight covered tables, mastery metadata, preferences, schema/migration metadata, and `usage_outbox`; run erasure; assert covered counts are zero and excluded values are byte/value equivalent. Run on Kotlin JVM SQLite and Swift GRDB in-memory stores. |
| Unit | Transaction rollback | Inject a statement/commit failure and assert no partial covered-table deletion, no success result, and no artifact scrub call. |
| Unit | Artifact inventory and idempotency | Fake Android `Files` and iOS filesystem/SwiftData seams with every payload, backup, migration, preimport, install, and verification artifact; scrub twice and assert payload removal/sanitization plus marker preservation. |
| Unit | Partial cleanup recovery | Commit database deletion, fail one artifact removal, assert `CleanupIncomplete`, then retry with the failure removed and assert success without damage to preserved state. |
| Unit | Fail-safe legacy mode | Seed allowed preferences plus learning data in Android JSON and iOS SwiftData payloads; erase; reopen; assert the active payload is empty except for preferences and the migration gate state is unchanged. |
| Unit | Stale async work | Begin progress sync/deferred write with generation G0, advance to G1, complete G0, and assert no archive or database mutation; start G1 and assert it is accepted. |
| Integration | Restart/no resurrection | Seed database plus every historical artifact, erase, close/reopen the store, and prove startup migration/adoption/restore logic cannot reintroduce covered data. |
| Integration | Coordinator publication ordering | Hold the database and cleanup seams at controlled barriers and assert the visible archive/success notice changes only after full success; database and cleanup failures expose their distinct states. |
| Platform verification | Android and iOS native checks | Run Android JVM tests and build/lint where available; run `swift test --package-path apps/ios` for core behavior and Xcode application tests/build for `ConversationCoordinator`/`LearningStore`. Report unavailable macOS/Xcode or device checks as blocked, never passing. |

Planned RED tests begin by changing the current cutover assertions that lessons survive deletion; they must fail until all covered tables are deleted. Artifact tests must first demonstrate restart resurrection from each retained copy. Generation tests must first demonstrate that a fetch/deferred writer started before erasure can currently land afterward.

## Threat Matrix

N/A — this change does not add routing, shell commands, subprocesses, VCS/PR automation, executable-file classification, or a process-integration boundary. Its filesystem and database failure cases are covered by the storage/recovery testing strategy above.

## Migration / Rollout

No schema migration or feature flag is required. Existing databases remain readable; the new transaction only runs after explicit user confirmation. Roll out Android and iOS together with their platform tests, but do not couple their releases operationally.

Rollback is code-only because no new schema exists. Reverting restores the previous incomplete behavior for future requests but cannot recover data already erased successfully. If cleanup defects are discovered, fail the destructive action closed rather than falling back to the old partial deletion path.

## Open Questions

None. `usage_outbox`, forensic byte sanitization, remote deletion, and permanent sync suppression are explicitly outside this change rather than unresolved implementation decisions.
