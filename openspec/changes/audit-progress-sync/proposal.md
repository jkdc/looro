# Proposal: Make Progress Sync Complete, Convergent, and Time-Safe

## Intent

Resolve audit findings F01, F03, F04, and F12 as one progress-synchronization root-cause cluster. The current API and native clients exchange monotonic aggregates, but undocumented timestamp semantics, one-shot upload/download limits, and event-only local projection prevent safe eventual convergence across devices. This change establishes a versioned, bounded synchronization protocol and a distinct local synchronized-rollup projection without claiming snapshot isolation or reconstructing complete event history.

## Scope

### In Scope

- Define Unix milliseconds as the progress wire timestamp representation and convert it only at API/client boundaries.
- Replace one-shot transfer behavior with deterministic, replay-safe download pagination and upload batching that eventually covers every progress identity.
- Add durable native-client resume state that advances only after acknowledgement and restarts a completed pass so concurrent changes converge on later passes.
- Add an idempotent recovery policy for previously contaminated native timestamps, excluding unrecoverable values from upload and surfacing diagnostics.
- Store synchronized remote rollups separately from local completion events and combine both projections monotonically for score and progress reads.
- Add shared contract fixtures and platform-specific tests for timestamp round trips, interrupted pagination, replay, multi-language identity, recovery, and score convergence.

### Out of Scope

- Replacing aggregate synchronization with server-owned immutable completion-event history.
- Providing transaction-wide snapshot isolation across a complete synchronization pass.
- Changing unrelated local history/archive retention caps or the local-erasure contract.
- Resolving audit findings outside F01, F03, F04, and F12.
- Choosing exact cursor encoding, schema field names, recovery cut-off dates, or legacy-envelope transition details before design.

## Capabilities

### New Capabilities

- `progress-sync-protocol`: Versioned, deterministic, bounded upload and download exchange with explicit Unix-millisecond timestamps, opaque keyset cursors, replay safety, and eventual full-pass convergence.
- `progress-sync-recovery`: Idempotent repair and quarantine of contaminated local timestamps plus a durable synchronized-rollup projection that improves remote aggregates without inventing local learning events.

### Modified Capabilities

None. No existing OpenSpec capability covers progress synchronization.

## Approach

Introduce a versioned paged response for `GET /v1/progress` using an opaque cursor over the stable progress identity `(kind, itemID, language)`, with an explicit continuation/completion signal. Keep POST batches at no more than 200 entries, but make both native clients traverse a stable identity ordering and persist resume state after each successful acknowledgement. Page replay remains harmless because the API merge is monotonic; mutations missed behind a cursor are recovered by a later full pass.

At each native boundary, decode Unix milliseconds into the clients' Apple-reference-second domain before persistence and encode back only for transport. Run a versioned, idempotent recovery that repairs a contaminated value only when its Unix-millisecond interpretation falls inside the supported history and future-skew bounds; otherwise quarantine it from upload and expose a diagnostic.

Persist remote aggregates in a synchronized-rollup store keyed by `(kind, itemID, language)`. Read models combine local event-derived and synchronized rollups with `min(firstAt)` and `max(lastAt, attempts, bestScore)`, preserving local event history while making local 40/remote 90 converge to 90.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `services/api/src/progress.ts` | Modified | Define the versioned timestamp and cursor contract; implement deterministic paged retrieval while preserving monotonic bounded uploads. |
| `services/api/tests/progress.test.ts` | Modified | Cover wire-time boundaries, cursor ties, replay, 1,001+ rows, multiple languages, and concurrent-update convergence. |
| `services/api/migrations/030_user_progress.sql`, `services/api/migrations/032_user_progress_language.sql` | Evaluated/Modified | Confirm or add indexing needed for stable identity keyset pagination without changing aggregate identity. |
| `apps/ios/App/ProgressClient.swift`, `apps/ios/App/ConversationCoordinator.swift` | Modified | Consume every download page, upload every bounded batch, and persist acknowledged resume state. |
| `apps/ios/Core/ProgressEngine.swift`, `apps/ios/App/Storage.swift` | Modified | Normalize boundary time, recover contaminated values, persist synchronized rollups, and combine projections idempotently. |
| `apps/ios/Tests/ProgressEngineTests.swift` | Modified | Prove timestamp recovery, interrupted resume, replay, and score convergence without synthetic events. |
| `apps/android/app/src/main/java/com/jkdc/app/network/ProgressClient.kt`, `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt` | Modified | Consume every download page, upload every bounded batch, and persist acknowledged resume state. |
| `apps/android/app/src/main/java/com/jkdc/app/core/ProgressEngine.kt` | Modified | Normalize boundary time and combine local events with synchronized rollups. |
| `apps/android/app/src/test/java/com/jkdc/app/core/ProgressEngineTest.kt` | Modified | Prove timestamp recovery, interrupted resume, replay, and score convergence without synthetic events. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Older clients continue producing or storing incompatible timestamps during rollout. | Medium | Preserve API future-skew validation and define legacy compatibility/rollout containment in design before deployment. |
| Cursor or resume state can starve identities after interruption. | Medium | Order by immutable identity, advance durable state only after acknowledgement, and test interruption at every batch boundary. |
| Stateless keyset paging misses concurrent mutations behind the current cursor. | Medium | Specify eventual convergence through repeated full passes rather than snapshot completeness, and test next-pass recovery. |
| Recovery misclassifies legitimate historical data. | Medium | Require a bounded plausibility window plus a durable recovery-version marker; quarantine rather than clamp ambiguous values. |
| Some UI/stat readers bypass the synchronized-rollup projection. | Medium | Inventory every score/progress read path in design and add cross-platform projection tests. |
| Coordinated API and client deployment creates a compatibility gap. | Medium | Design a version-negotiation or bounded legacy-serving plan before implementation and retain rollback compatibility until adoption is verified. |

## Rollback Plan

Contain rollout behind the versioned progress contract while retaining the legacy API response and client path until the new clients are deployed and verified. If synchronization regressions appear, stop advertising the new version, revert clients to the legacy transfer path, and leave synchronized-rollup/recovery data intact but unread; do not reverse repaired timestamps or delete remote aggregates automatically. Database changes must be additive so code rollback does not require destructive schema rollback. Continue rejecting implausible future timestamps throughout rollback.

## Dependencies

- Design must define the opaque cursor encoding, legacy protocol transition, earliest recoverable instant, accepted future skew, and per-store recovery-version marker.
- Design must inventory every iOS and Android projection that currently reads completion scores or timestamps.
- Verification requires PostgreSQL-backed API tests plus Swift and Kotlin tests; unavailable native/database environments must be reported honestly rather than treated as passing.
- The proposal relies on the audit report as static/mock evidence only; it does not upgrade unexecuted native or database checks into runtime proof.

## Success Criteria

- [ ] A shared instant survives API GET, Swift/Kotlin persistence and merge, and POST as the same Unix-millisecond wire value.
- [ ] Previously contaminated timestamps are repaired once when safely identifiable; ambiguous values are excluded from upload and diagnosed.
- [ ] At least 201 distinct local activities eventually upload across bounded batches, including interruption and replay at every acknowledgement boundary.
- [ ] At least 1,001 server aggregates across languages download through deterministic pages, including timestamp ties and replayed pages.
- [ ] Concurrent changes missed during a stateless pass converge on a subsequent completed pass, with no snapshot-isolation claim.
- [ ] Local score 40 and remote score 90 converge to 90 in either merge order and repeated merges remain idempotent.
- [ ] Synchronization creates no fictitious completion event or learning session.
- [ ] API, Swift, and Kotlin contract tests document and enforce units, epochs, limits, cursor behavior, and merge semantics.
