## Exploration: Audit local erasure

### Current State
Both clients present an explicit local-device confirmation promising removal of conversations, vocabulary, and progress while preserving the API key and preferences. The current runtime paths quiesce active conversation work and clear sessions, assessment tickets, and hidden words, but intentionally leave lesson state, expressions, completions, skill errors, and cached mastery in the working archive and SQLite database.

Android enqueues `LearningDbWriter.learningDataDeleted` asynchronously after mutating `AppViewModel.archive`; a write failure therefore leaves UI state temporarily empty while durable rows survive for restart. iOS mutates `LearningStore.archive` before its synchronous writer and reloads the database projection if that writer fails. Both platform writers already use one database transaction, but that transaction covers only the incomplete subset.

Historical copies can retain the same learning data. Android migration leaves `learning.json.migrated` (or `.migrated.bak`) and restore flows can strand `.preimport`, `.install-tmp`, or verification copies. iOS retains the legacy SwiftData `StoredArchive` payload plus its migrated marker and may retain `before-language-modules.json` and restore staging files. Migration gates must remain committed while payload copies are scrubbed; deleting the gate together with a surviving source can re-import erased data.

Local erasure does not mean account or remote deletion. Both clients fetch remote progress at startup, so previously synchronized progress can legitimately return unless the product later defines a remote tombstone or a local sync-suppression rule. Any in-flight progress fetch must be prevented from writing into the freshly erased state.

### Affected Areas
- `apps/android/app/src/main/res/values/strings.xml` — existing confirmation defines the local erasure promise.
- `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt` — coordinates quiescence, working-state refresh, asynchronous persistence, progress sync, and user notices.
- `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbWriter.kt` — transactional deletion currently omits most archive-projected learning tables.
- `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbStore.kt` — owns migration gates and Android legacy JSON artifact paths.
- `apps/android/app/src/test/java/com/jkdc/app/data/LearningCutoverFlowTest.kt` — currently asserts that lesson progress survives delete-all and must be inverted and expanded.
- `apps/android/app/src/test/java/com/jkdc/app/data/LearningDbStoreTest.kt` — suitable JVM seam for migration-source and restart/resurrection coverage.
- `apps/ios/App/LibraryViews.swift` — existing confirmation defines the corresponding local-device promise.
- `apps/ios/App/ConversationCoordinator.swift` — cancels runtime work and must invalidate in-flight progress/deferred writers before erasure.
- `apps/ios/App/Storage.swift` — owns working archive refresh, SwiftData legacy payload, migration backup, and restore artifacts.
- `apps/ios/Core/Database/LearningDbWriter.swift` — transactional deletion currently mirrors Android's incomplete subset.
- `apps/ios/Tests/LearningCutoverFlowTests.swift` — currently asserts that lesson progress survives delete-all and must be inverted and expanded.
- `apps/ios/Tests/LearningDbStoreTests.swift` — existing in-memory migration coverage can verify that historical payloads cannot restore erased learning.

### Approaches
1. **Transactional content purge plus idempotent artifact scrub** — add a shared per-platform erasure operation that clears all archive-projected learning state in one SQLite transaction, preserves non-learning preferences and migration gates, then removes or sanitizes historical payload artifacts through testable platform seams. Publish the empty working archive only after durable deletion succeeds, invalidate in-flight writers/sync, and make cleanup retry-safe.
   - Pros: Matches the current UI promise; reuses existing database and migration abstractions; preserves keys, account state, preferences, schema flags, and migration safety; supports focused in-memory and fake-filesystem tests.
   - Cons: SQLite and filesystem/SwiftData changes cannot share one transaction, so partial cleanup needs an explicit error/result contract and idempotent retry; platform coordinators need stronger durability ordering.
   - Effort: Medium

2. **Reset the entire learning store and rebuild preserved settings** — close and delete the database and all sidecars, remove legacy stores, create a fresh store, then restore allowed preferences.
   - Pros: Naturally removes unknown future learning tables and SQLite remnants.
   - Cons: Much larger lifecycle and crash-recovery surface; risks deleting or incorrectly reconstructing preferences; requires store reopening and state rebinding on both clients; duplicates startup-failure recovery behavior and is harder to verify safely.
   - Effort: High

3. **Expand only the existing database DELETE statements** — clear the omitted tables and in-memory fields without addressing historical copies or synchronization races.
   - Pros: Smallest code change and easy database-only tests.
   - Cons: Does not satisfy the audited acceptance condition because legacy/staged artifacts can retain learning data and remote or deferred work can repopulate state.
   - Effort: Low

### Recommendation
Use approach 1. Define the local learning-data set as sessions/fragments, lesson state, expressions, completions, skill errors, assessment tickets, hidden words, and mastery cache. Clear those rows atomically while retaining the rest of `Preferences`, secure credentials/account state, schema flags, and migration commit markers. Keep `usage_outbox` outside this learning-data contract unless product policy separately classifies telemetry as erasable learning content; it is not represented in `Archive` and the current promise preserves preferences rather than resetting the app.

After the database commit, scrub Android legacy JSON and restore artifacts (`learning.json`, `.bak`, `.migrated`, `.migrated.bak`, `.preimport`, `.install-tmp`, verification copies) and the corresponding iOS legacy payload/backup/staging artifacts. In iOS fail-safe mode, replace the active SwiftData payload with an empty archive that retains preferences rather than deleting the only writable backing record; in migrated database mode, remove the historical payload while retaining the migrated marker. Artifact cleanup should be idempotent, and a partial cleanup must surface as failure rather than claim complete erasure.

The coordinators should cancel/debounce outstanding local writers, invalidate any active progress-fetch generation, perform the durable operation, and only then replace the observable archive with a preferences-preserving empty projection and clear derived UI state. Tests should seed every covered table and artifact, erase, reopen/restart, and prove the data is neither exported nor re-imported. A separate test should hold an in-flight remote-progress result across erasure and prove that stale completion cannot repopulate local state.

### Risks
- Database deletion and artifact cleanup are not jointly atomic; ordering and retry semantics must prevent a successful UI message after partial cleanup.
- Removing migration markers can re-arm legacy import, while retaining payloads leaves private learning history on disk; the marker and payload have different erasure roles.
- Android's current fire-and-forget writer can report empty UI state before durability and must not remain the erasure mechanism.
- Deferred saves or in-flight progress fetches can repopulate erased data unless guarded by a generation/token boundary.
- Future legitimate remote synchronization may restore server-held progress; the local action must not be represented as remote/account deletion.
- Ordinary SQLite DELETE provides application-level erasure but not guaranteed forensic byte sanitization; a stronger storage threat model would require an explicit secure-delete/checkpoint policy.

### Ready for Proposal
Yes. The proposal can specify a cross-platform local-erasure contract, transaction boundaries, artifact inventory, state-refresh ordering, and restart tests. Non-blocking gaps to state explicitly are the telemetry-outbox classification, the forensic-erasure threat model, and whether later remote sync should be allowed to restore progress; none authorizes remote or account deletion in this change.
