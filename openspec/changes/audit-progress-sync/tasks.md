# Tasks: Complete, Convergent, and Time-Safe Progress Sync

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | Approximately 2,400–3,000 authored lines across shared contracts, API, two native clients, migrations, tests, and generated outputs |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 shared contract/schema; PR 2 API protocol; PR 3 iOS persistence/recovery/projection; PR 4 Android persistence/recovery/projection; PR 5 iOS transport/read-model wiring; PR 6 Android transport/read-model wiring; PR 7 integration/freshness checks |
| Delivery strategy | auto-chain |
| Chain strategy | pending |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: pending
400-line budget risk: High

The forecast is high because this change crosses one wire contract, a Fastify/PostgreSQL API, two native database schemas, two independent transport coordinators, multiple read-model consumers, generated artifacts, and platform-specific tests. Each proposed work unit has an independent rollback boundary and is intended to stay within the 400-line review ceiling. The parent orchestrator must obtain the chain strategy (`stacked-to-main` or `feature-branch-chain`) before launching `sdd-apply`; this phase does not choose it.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 | Add the shared v2 contract, generated constants, schema-v6 definitions, and freshness checks | PR 1 | `python3 -m unittest discover -s scripts/tests -t .` | `make check` (includes contract/schema freshness) | Revert shared fixture/exporter/schema additions; no client or API behavior is enabled |
| 2 | Add opt-in API v2 parsing, cursor pagination, compatibility handling, and bounded merge tests | PR 2 | `npm test` and `npm run check` from `services/api` | `make test-api` for PostgreSQL pagination/index evidence | Revert v2 route/config and API module changes; legacy GET/POST remains |
| 3 | Add iOS schema-v6 persistence, recovery, rollups, and pure projection | PR 3 | `swift test --package-path apps/ios` | iOS database/store test fixtures with injected failures before/after commit; macOS/Xcode execution is required and must be reported if unavailable | Revert iOS database/core changes; additive local tables remain unused by old code |
| 4 | Add Android schema-v6 persistence, recovery, rollups, and pure projection | PR 4 | `./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest` | JVM database fixtures with interrupted transactions and account switching; device UI is not required for this unit | Revert Android database/core changes; additive schema can remain unread |
| 5 | Add iOS paged transport, acknowledged resume loops, compatibility fallback, and projection readers | PR 5 | `swift test --package-path apps/ios` | Mock transport with 1,001 downloads, 201 uploads, replay, and interruption at each acknowledgement boundary | Revert iOS client/coordinator/read-model wiring to legacy transfer and event-only readers |
| 6 | Add Android paged transport, acknowledged resume loops, compatibility fallback, and projection readers | PR 6 | `./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest` | Mock transport with 1,001 downloads, 201 uploads, replay, and interruption at each acknowledgement boundary | Revert Android client/view-model/read-model wiring to legacy transfer and event-only readers |
| 7 | Run cross-platform freshness, compatibility, and regression checks; document unavailable environments | PR 7 | `make check` plus applicable `npm run check`, Android lint/build, and `swift test --package-path apps/ios` | API PostgreSQL smoke, Android build, and Xcode/application checks when environments permit | Revert only test/checker/documentation changes; behavior units remain independently revertible |

## Phase 1: Shared Contract and API Foundation

- [ ] 1.1 Create `shared/progress-sync-contract.json` with version 2, Unix-millisecond wire metadata, Apple-reference local metadata, the first accepted millisecond after the strict `2025-01-01T00:00:00Z` lower boundary, 48-hour future skew, upload limit 200, and download limit 1,000; validate safe integers and ordered runtime bounds.
- [ ] 1.2 Create `scripts/export_progress_sync.py` with deterministic generation and `--check`; emit `apps/ios/Core/ProgressSyncContractData.swift` and `apps/android/app/src/main/java/com/jkdc/app/core/ProgressSyncContractData.kt`; the API work unit consumes the shared fixture separately rather than hand-maintained platform literals.
- [ ] 1.3 Modify `shared/db/schema.sql` to define `progress_rollups` and `progress_sync_state` with account/direction keys, monotonic aggregate columns, and documented erasure/account semantics; modify `scripts/check_db_schema.py` to verify schema-v6 parity and generated `apps/android/app/src/main/java/com/jkdc/app/data/SchemaData.kt` and `apps/ios/Core/Database/SchemaData.swift` inputs.
- [ ] 1.4 Add `services/api/src/progress.ts` v2 request/body parsing, strict base64url JSON cursor codec, shared timestamp bounds, identity keyset ordering, page-limit-plus-one completion derivation, and monotonic upload merge while preserving the legacy response/body shape.
- [ ] 1.5 Modify `services/api/src/app.ts` and `services/api/src/main.ts` to route version parameters and inject the explicit `PROGRESS_SYNC_V2_ENABLED` flag; return the bounded version-unavailable error only for v2 requests and preserve future-skew rejection for both paths.
- [ ] 1.6 Modify `services/api/tests/progress.test.ts` with table-driven wire-time boundary/zero cases, malformed and oversized cursor cases, version compatibility, timestamp ties, replay, empty/small/1,001-plus multi-language pages, 200-entry upload limits, and next-pass convergence fixtures.
- [ ] 1.7 Evaluate `services/api/migrations/030_user_progress.sql` and `services/api/migrations/032_user_progress_language.sql` with PostgreSQL `EXPLAIN`; add only an additive identity index if the existing primary-key prefix is not used, and cover the chosen plan in the API integration test.
- [ ] 1.8 Verify shared contract/exporter/schema/API work with `python3 -m unittest discover -s scripts/tests -t .`, `npm test`, `npm run check`, and `make test-api`; report unavailable PostgreSQL or network checks rather than treating them as passing.

## Phase 2: iOS Persistence, Recovery, and Projection

- [ ] 2.1 Regenerate `apps/ios/Core/Database/SchemaData.swift` from the shared schema and modify `apps/ios/Core/Database/LearningDatabase.swift` to migrate schema version 5 to version 6 with additive `progress_rollups`, `progress_sync_state`, and recovery-marker storage.
- [ ] 2.2 Modify `apps/ios/Core/Database/LearningDatabaseDao.swift` and `apps/ios/Core/Database/LearningDbWriter.swift` with account-filtered rollup upserts (`min(firstAt)`, `max(lastAt/attempts/bestScore)`), durable direction/cursor state, atomic page-merge-plus-cursor transactions, and bounded diagnostic/marker writes.
- [ ] 2.3 Modify `apps/ios/Core/Models.swift` and `apps/ios/Core/ProgressEngine.swift` with `ProgressIdentity`, `ProgressAggregate`, `SynchronizedProgress`, recovery reason codes, and a pure `ProgressProjection` that combines local event aggregates and remote rollups without creating `CompletionMark` or `SessionRecord` values.
- [ ] 2.4 Modify `apps/ios/App/Storage.swift` to run versioned timestamp recovery before archive/read-model materialization, repair only evidence-backed Unix-millisecond or legacy Unix-second values, quarantine ambiguous values, correct lesson timestamp writes to Apple-reference seconds, and scope all state by authenticated account.
- [ ] 2.5 Add/modify iOS database and engine tests covering v2-to-v3 migration, account partitioning, safe lower/future boundary recovery, idempotent markers, ambiguous quarantine diagnostics, failure before/after transaction commit, local 40/remote 90 convergence in both merge orders, replay idempotence, and unchanged event/session counts.
- [ ] 2.6 Verify iOS persistence/core work with `swift test --package-path apps/ios`; run application/Xcode database checks only where the environment provides them and record unavailable macOS/Xcode results explicitly.

## Phase 3: Android Persistence, Recovery, and Projection

- [ ] 3.1 Regenerate `apps/android/app/src/main/java/com/jkdc/app/data/SchemaData.kt` from the shared schema and modify `apps/android/app/src/main/java/com/jkdc/app/data/LearningDb.kt` to migrate schema version 5 to version 6 with additive rollup, resume-state, and recovery-marker tables.
- [ ] 3.2 Modify `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbDao.kt` and `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbWriter.kt` with account-filtered rollup upserts, durable direction/cursor state, atomic page-merge-plus-cursor transactions, and bounded diagnostic/marker writes.
- [ ] 3.3 Modify `apps/android/app/src/main/java/com/jkdc/app/core/Models.kt` and `apps/android/app/src/main/java/com/jkdc/app/core/ProgressEngine.kt` with aggregate/rollup/recovery types and a pure `ProgressProjection` whose merge is commutative and idempotent while preserving local event history.
- [ ] 3.4 Add/modify Android database and engine tests covering v2-to-v3 migration, account partitioning, safe/ambiguous timestamp recovery, idempotent markers, failure before/after transaction commit, local 40/remote 90 convergence, replay, multi-language identity, and no synthetic events.
- [ ] 3.5 Verify Android persistence/core work with `./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest`; run `./gradlew :app:lintDebug :app:assembleDebug` when the Gradle environment permits and report wrapper/cache failures honestly.

## Phase 4: iOS Transport and Read-Model Wiring

- [ ] 4.1 Modify `apps/ios/App/ProgressClient.swift` to request/decode the v2 page envelope, convert Unix milliseconds at the boundary, enforce response invariants, expose only the documented version-unavailable legacy fallback, and reject malformed/future data without clamping.
- [ ] 4.2 Modify `apps/ios/App/ConversationCoordinator.swift` to serialize account-bound download/upload passes, consume every page, select deterministic upload suffixes of at most 200, advance state only after acknowledgement, replay uncertain batches safely, clear state at completion, and start a fresh pass for later changes.
- [ ] 4.3 Modify `apps/ios/Core/LevelEngine.swift`, `apps/ios/Core/MasteryEngine.swift`, `apps/ios/App/LearningProgressView.swift`, and `apps/ios/App/ListeningView.swift` to consume `ProgressProjection` for aggregate scores while retaining local-event-only streak/activity/history calculations; update `apps/ios/App/Storage.swift` score helpers accordingly.
- [ ] 4.4 Add/modify `apps/ios/Tests/ProgressEngineTests.swift` and iOS transport/store tests with 1,001 downloads, 201 uploads, every interruption boundary, replayed pages, account switches, multi-language identities, compatibility fallback, and no synthetic completion/session records.
- [ ] 4.5 Verify iOS transport/read-model work with `swift test --package-path apps/ios`; run app/UI smoke checks only in an Xcode-capable environment and distinguish unavailable checks from failures.

## Phase 5: Android Transport and Read-Model Wiring

- [ ] 5.1 Modify `apps/android/app/src/main/java/com/jkdc/app/network/ProgressClient.kt` to request/decode the v2 page envelope, convert Unix milliseconds at the boundary, enforce response invariants, expose only documented version-unavailable fallback, and reject malformed/future values without clamping.
- [ ] 5.2 Modify `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt` to serialize account-bound download/upload passes, consume every page, upload deterministic batches of at most 200, persist acknowledgement-driven cursors, replay uncertain requests, clear completed state, and route progress reads through the projection.
- [ ] 5.3 Modify `apps/android/app/src/main/java/com/jkdc/app/core/LevelEngine.kt` and `apps/android/app/src/main/java/com/jkdc/app/core/MasteryEngine.kt` plus their score/progress consumers to use `ProgressProjection` for aggregate evidence while retaining event-only streak/activity/history behavior.
- [ ] 5.4 Add/modify Android transport, view-model, engine, and screen-helper tests with 1,001 downloads, 201 uploads, interruption at every acknowledgement boundary, replay, account switching, multi-language identities, compatibility fallback, and unchanged local event/session counts.
- [ ] 5.5 Verify Android transport/read-model work with `./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest`; run `./gradlew :app:lintDebug :app:assembleDebug` when available and report device/emulator checks separately.

## Phase 6: Cross-Platform Integration and Rollout Checks

- [ ] 6.1 Update root freshness/check orchestration (including `Makefile` and existing checker tests as applicable) so `scripts/export_progress_sync.py --check` and schema-v6 parity run alongside existing generated-content checks; do not broaden the scope to unrelated audit findings.
- [ ] 6.2 Add shared contract fixtures and API/iOS/Android assertions for the same instant, epoch/unit, limits, cursor invariants, merge operation, language/account partitioning, recovery bounds, and rollback-disabled-v2 behavior.
- [ ] 6.3 Exercise the documented rollout order: server v2 disabled with legacy behavior, client schema/recovery with corrected legacy decoding, controlled v2 enablement, and narrow fallback only on `progress_sync_version_unavailable`; verify repaired timestamps/rollups are never deleted or reversed during rollback.
- [ ] 6.4 Run applicable checks: `make check`, `python3 -m unittest discover -s scripts/tests -t .`, API `npm test`/`npm run check`/`make test-api`, Android unit/lint/build commands, and `swift test --package-path apps/ios`; record PostgreSQL, Android, macOS/Xcode, simulator, and device limitations from the audit report rather than claiming unsupported proof.
- [ ] 6.5 Update the change documentation and task evidence with observed results, failed/skipped/unavailable checks, the chosen chain strategy, and the final PR slice boundaries before archive; do not modify `docs/reports/project-audit-2026-10-12.md`.

## Key Learnings

1. Progress synchronization must separate bounded transfer mechanics from pass completeness so repeated full passes can converge without snapshot-isolation claims.
2. The shared contract fixture is the only reliable way to keep Unix-millisecond bounds and native epoch conversions identical across API, iOS, and Android.
3. Remote aggregate evidence belongs in synchronized rollups and projections rather than fabricated completion events or sessions.
4. This change crosses enough independent components to require chained review slices under the existing 400-line ceiling.
