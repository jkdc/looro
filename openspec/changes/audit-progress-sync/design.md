# Design: Complete, Convergent, and Time-Safe Progress Sync

## Technical Approach

Progress synchronization remains an aggregate protocol, but version 2 makes each boundary explicit and resumable. The API serves an opt-in paged envelope ordered by the immutable identity `(kind, itemID, language)`. Native clients persist the last acknowledged identity per account and direction, replay unacknowledged work safely, and start a new full pass after completion so mutations missed behind a stateless cursor converge later.

Unix milliseconds exist only in the HTTP contract. Both clients decode them to the canonical local representation—Apple-reference seconds—before persistence and encode them back only when constructing requests. A versioned recovery runs before progress is loaded, repairs only evidence-backed epoch/unit contaminations inside the supported time window, and excludes ambiguous timestamps from upload without clamping them.

Remote aggregates are stored in a new synchronized-rollup table rather than being translated into `CompletionMark` or `LessonState` history. A shared `ProgressProjection` combines local event-derived aggregates with account-scoped synchronized rollups. Aggregate readers and future uploads use that projection; event-history readers continue using local events, so sync improves scores and completion state without inventing sessions, streak events, or activity-chart entries.

This maps directly to `progress-sync-protocol` and `progress-sync-recovery`: pagination and acknowledged resume state solve F03/F12, boundary conversion and recovery solve F01, and the separate projection solves F04.

## Architecture Decisions

### Decision: Use a versioned identity keyset cursor

**Choice**: Version 2 download requests use `GET /v1/progress?version=2&cursor=<opaque>`. The service orders rows ascending by `(kind, item_id, language)` and applies the strict predicate `(kind, item_id, language) > ($kind, $itemID, $language)`. It fetches `pageLimit + 1` rows, returns at most 1,000, and derives `complete` and `nextCursor` from the extra row. The existing primary key `(account_id, kind, item_id, language)` supplies the required index prefix; no server migration is needed unless `EXPLAIN` disproves index use.

The cursor is base64url-encoded UTF-8 JSON with the exact schema `{"v":2,"kind":"...","itemID":"...","language":"..."}`. Decoding performs strict field, version, allowlist, slug, and size validation before values reach SQL. Clients treat the value as an opaque string.

**Alternatives considered**: Offset pagination; ordering by `last_at`; a database snapshot token; encrypting or signing the cursor.

**Rationale**: Offset and mutable-time pagination can skip or repeat rows under concurrent updates. A transaction-wide snapshot conflicts with the stateless eventual-convergence requirement. The identity tuple contains no credential and can only narrow the authenticated account's own traversal; strict validation makes an HMAC unnecessary. Base64url JSON is debuggable, bounded, versioned, and still opaque to clients.

### Decision: Keep request limits distinct from pass completeness

**Choice**: Downloads return at most 1,000 entries per page; uploads retain the existing maximum of 200 entries per request. Clients build the complete identity-sorted effective projection, select the strict suffix after the durable cursor, and send one bounded page at a time. Upload cursor advancement occurs only after the POST acknowledgement. Download cursor advancement occurs in the same local transaction that persists the received rollups. A final page clears the cursor; the next synchronization begins a fresh pass.

**Alternatives considered**: Raising the limits; marking each identity delivered; using timestamps as upload watermarks.

**Rationale**: Larger limits defer rather than solve completeness. Per-row delivery flags add mutation and cleanup complexity. Timestamp watermarks can starve identities whose mutable aggregate changes behind the cursor. Identity passes plus replay-safe monotonic merge are sufficient, and later full passes recover concurrent changes without claiming snapshot isolation.

### Decision: Scope durable synchronization state by authenticated account

**Choice**: Synchronized rollups use database identity `(account_id, kind, item_id, language)`, where the aggregate identity inside an account remains `(kind, itemID, language)`. Resume state uses `(account_id, direction)` because a download pass spans all languages and the upload iterator emits every language represented locally. The selected bearer session's `accountID` is captured before a pass and checked before each durable merge/advance. Account changes cancel or invalidate the in-flight pass. When no account is selected, read models use local evidence only.

Legacy `nil`-language local marks are resolved from registered content identifiers when unambiguous; otherwise they upload as the existing server language `legacy`. A `legacy` remote rollup is retained for round-trip compatibility but is not projected into an arbitrary active language.

**Alternatives considered**: One device-global cursor and rollup table; one cursor per language; discarding rollups on sign-out.

**Rationale**: Device-global state can mix guest and member accounts. Per-language download cursors duplicate a server traversal that already includes language in identity. Account partitioning prevents cross-account display or resume, while retention permits returning to the same account without destructive churn.

### Decision: Negotiate version 2 without breaking shipped clients

**Choice**: A request without `version=2` preserves the current legacy GET shape `{ progress: [...] }`. A version-2 request returns `{ version: 2, progress: [...], nextCursor: string | null, complete: boolean }`. Version-2 POST bodies are `{ version: 2, entries: [...] }`; the service continues accepting legacy `{ entries: [...] }` during rollout. The server has an explicit `PROGRESS_SYNC_V2_ENABLED` configuration flag: when disabled, version-2 GET/POST returns a documented `progress_sync_version_unavailable` response while legacy behavior remains available.

New clients prefer version 2. On the documented unavailable response only—not on malformed data, authentication failure, or arbitrary transport errors—they may run one legacy compatibility pass using the corrected timestamp decoder. Compatibility mode never writes a version-2 cursor. Rollback disables the flag first and can then deploy legacy application code; additive local tables and repaired timestamps remain intact.

**Alternatives considered**: Replacing the legacy response in place; a new `/v2/progress` path; permanent silent fallback after any error.

**Rationale**: In-place replacement would break shipped decoders. A new top-level API path duplicates authentication and route policy for one contract evolution. Narrow fallback keeps rollback bounded without masking real protocol defects.

### Decision: Derive time bounds from existing authoritative constraints

**Choice**: Add `shared/progress-sync-contract.json` as the cross-platform contract fixture with:

- `version = 2`
- `wireEpoch = "unix"` and `wireUnit = "milliseconds"`
- `localEpoch = "appleReference"` and `localUnit = "seconds"`
- `earliestUnixMilliseconds = 1735689600000` (`2025-01-01T00:00:00Z`)
- `futureSkewMilliseconds = 172800000` (48 hours)
- `uploadBatchLimit = 200` and `downloadPageLimit = 1000`

The lower bound comes from the shipped `user_progress.first_at > '2025-01-01'` database constraint. The future bound comes from the existing API `FUTURE_SKEW_MILLISECONDS = 48 * 3_600_000` validation shared conceptually with usage events. The exporter generates typed Swift/Kotlin constants and a TypeScript module so production code and tests consume the same values; the existing freshness pipeline checks generated outputs.

**Alternatives considered**: Magnitude heuristics; a new product-selected date; duplicating literals in three implementations.

**Rationale**: Magnitude alone can reinterpret legitimate data, and an invented date has no repository evidence. The existing persistence and validation contracts provide defensible bounds; a checked shared fixture prevents future drift.

### Decision: Recover known contamination before loading progress

**Choice**: Recovery version 1 runs once per local store after schema opening and before `Archive`/read-model materialization. In one local transaction it inspects `lesson_state.last_seen` and `completions.at`:

1. A value already inside the Apple-reference-second interval `[earliestAppleSeconds, nowAppleSeconds + futureSkewSeconds]` is valid and unchanged.
2. Otherwise, interpret the value as Unix milliseconds and convert with `value / 1000 - 978307200`. Repair only if the candidate lies inside the same interval.
3. iOS `lesson_state.last_seen` additionally recognizes the repository-proven legacy writer in `Storage.recordLessonAttempt`, which stored Unix seconds. Convert `value - 978307200` only for this field and only when that candidate lies inside the interval. New writes switch to `timeIntervalSinceReferenceDate`.
4. Every other out-of-domain value remains stored for local-history preservation but is excluded when constructing sync aggregates. It produces only a bounded reason-code/count diagnostic, never the raw timestamp or payload.

After the transaction succeeds, meta key `progress_timestamp_recovery` stores a compact JSON marker containing the recovery version and bounded counts by reason. Database failure leaves the marker unset so the whole recovery retries. JSON fail-safe stores persist the equivalent optional recovery marker with the archive snapshot. Re-running the same version performs no repair and emits no duplicate diagnostic.

**Alternatives considered**: Clamp to the nearest bound; repair any large number; delete invalid rows; run conversion during every sync.

**Rationale**: The first two contamination forms are demonstrated by current code and F01; the third is demonstrated by the current iOS lesson writer and conflicts with the canonical schema's Apple-epoch convention. Bounded interpretation avoids guessing, transaction plus marker makes recovery idempotent, and preserving invalid local rows avoids destructive history changes.

### Decision: Persist remote aggregates separately and project once

**Choice**: Learning database schema version 3 adds:

```sql
CREATE TABLE progress_rollups (
    account_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    item_id TEXT NOT NULL,
    language TEXT NOT NULL,
    first_at REAL NOT NULL,
    last_at REAL NOT NULL,
    attempts INTEGER NOT NULL,
    best_score INTEGER NOT NULL,
    PRIMARY KEY (account_id, kind, item_id, language)
);

CREATE TABLE progress_sync_state (
    account_id TEXT NOT NULL,
    direction TEXT NOT NULL CHECK (direction IN ('download', 'upload')),
    cursor TEXT NULL,
    PRIMARY KEY (account_id, direction)
);
```

`progress_rollups` merges with `first_at = min` and `last_at/attempts/best_score = max`. It never writes `lesson_state`, `completions`, `sessions`, or fragments. `ProgressProjection` receives local lessons, local completions, and rollups already filtered to the active account. It exposes effective aggregates keyed by `(kind, itemID, language)` and retains local events separately.

Consumers move to the projection as follows:

| Consumer | Effective aggregate usage | Local-event-only usage |
|---|---|---|
| `ProgressSync.entries` | Upload all effective identities in stable order | Invalid timestamps are filtered before aggregation |
| `ProgressEngine.stats` | Course completion, distinct completed items, assessment threshold | Streaks, 30-day activity, and repeated practice-event counts |
| `LevelEngine` | Lesson tiers and listening/reading/exercise best score | None |
| `MasteryEngine` | Remote best score/last-at contributes as aggregate evidence | Detailed local evidence remains unchanged |
| Store/view score helpers | `bestScore(kind,itemID,language)` | None |

Direct scans of `CompletionMark.bestScore` in `LearningProgressView`, `ListeningView`, iOS `Storage`, Android `AppViewModel`, and their engine call sites are replaced by the projection API. Remote aggregate evidence is passed as `ProgressAggregate`; it is never materialized as a `CompletionMark` to reuse old APIs.

**Alternatives considered**: Update or append completion marks; overwrite lesson state; make each view merge independently.

**Rationale**: Event mutation fabricates history and cannot represent a higher score for an existing identity cleanly. Independent view merges will drift. A single pure projection gives all aggregate readers identical monotonic semantics while preserving event-based analytics.

### Decision: Keep diagnostics bounded and non-sensitive

**Choice**: Recovery and pass diagnostics use fixed reason codes (`timestamp_before_supported_history`, `timestamp_after_future_skew`, `cursor_invalid`, `pass_interrupted`) plus account-scoped opaque identity hashes and aggregate counts capped per run. They never include bearer tokens, transcript text, complete progress payloads, raw cursor bodies, or raw contaminated values.

**Alternatives considered**: Persist complete rejected rows; silent rejection.

**Rationale**: Complete payloads are unnecessary and increase privacy risk. Silent rejection makes quarantine and incomplete passes impossible to diagnose.

## Data Flow

### Download pass

```text
Coordinator      ProgressClient          API/PostgreSQL         Local DB          Projection
    |                   |                       |                    |                  |
    |-- load account + durable cursor -------->|                    |                  |
    |-- GET ?version=2&cursor=... ------------>|                    |                  |
    |                   |                       |-- identity page -->|                  |
    |<-- envelope(progress,next,complete) ------|                    |                  |
    |-- decode Unix ms -> Apple seconds ---------------------------->|                  |
    |-- transaction: monotonic rollup merge + cursor advance ------>|                  |
    |   (crash before commit => replay same page; crash after => resume next page)      |
    |-- repeat until complete ------------------------------------->|                  |
    |-- read account rollups --------------------------------------------------------->|
    |<------------------------ effective aggregate reads ------------------------------|
```

The merge and download-cursor advance are one transaction. A page repeated after any uncertain outcome is harmless. An update behind the current cursor is intentionally not recovered within the current pass; the next pass starts at the beginning and converges.

### Upload pass

```text
Local events + account rollups
             |
             v
      ProgressProjection -- validate time bounds --> identity-sorted aggregates
             |                                         |
             |<-- strict suffix after durable cursor --|
             v
       batch <= 200 -- POST version 2 --> monotonic server upsert
             |<------------- acknowledgement ----------|
             v
       persist upload cursor
             |
       repeat; clear cursor after final batch
```

If acknowledgement is uncertain, the client does not advance and resends the same identity range. Server `LEAST/GREATEST` merge makes replay idempotent.

### Startup recovery

```text
Open schema v3 -> read recovery marker
     | missing
     v
scan progress timestamps -> bounded interpretation -> repair or quarantine classification
     |                                                   |
     +------------ one transaction + version marker ----+
                              |
                              v
                   load archive and sync projection
```

## File Changes

| File | Action | Description |
|---|---|---|
| `shared/progress-sync-contract.json` | Create | Authoritative protocol version, epochs, bounds, and limits. |
| `scripts/export_progress_sync_contract.py` | Create | Generate typed TypeScript, Swift, and Kotlin contract constants and support `--check`. |
| Generated contract files under API/iOS/Android progress modules | Create | Platform-specific constants generated from the shared contract. |
| `shared/db/schema.sql` | Modify | Add schema-v3 rollup and sync-state tables and document account/time semantics. |
| `scripts/check_db_schema.py` | Modify | Pin schema v3 tables, constraints, indexes, and migration parity. |
| `apps/android/app/src/main/java/com/jkdc/app/data/SchemaData.kt` | Regenerate | Carry schema-v3 fresh-install and upgrade statements. |
| `apps/ios/Core/Database/SchemaData.swift` | Regenerate | Carry the same schema-v3 statements. |
| `apps/android/app/src/main/java/com/jkdc/app/data/LearningDb.kt` | Modify | Advance the Android learning database version to 3. |
| `apps/ios/Core/Database/LearningDatabase.swift` | Modify | Advance the iOS learning database version to 3. |
| Android/iOS database DAO and writer files | Modify | Add transactional rollup merge, resume state, and recovery-marker operations. |
| Android/iOS database store tests and schema tests | Modify | Prove v2-to-v3 upgrade, account partitioning, atomic cursor merge, and recovery idempotence. |
| `services/api/src/progress.ts` | Modify | Add v2 parsing, cursor codec, identity-keyset query, shared time constants, and legacy compatibility. |
| `services/api/src/app.ts`, `services/api/src/main.ts` | Modify | Route version parameters and inject the explicit v2 enablement flag. |
| `services/api/tests/progress.test.ts` | Modify | Cover cursor validation/replay, 1,001+ rows, ties, version compatibility, time boundaries, and next-pass convergence. |
| `apps/ios/App/ProgressClient.swift` | Modify | Decode v2 pages, convert wire time at ingress, and expose narrow legacy fallback. |
| `apps/ios/App/ConversationCoordinator.swift` | Modify | Serialize account-bound download/upload passes and loop until completion. |
| `apps/ios/App/Storage.swift` | Modify | Persist atomic page merges/state, run recovery, expose account-filtered projection, and correct lesson timestamp writes. |
| `apps/ios/Core/Models.swift`, `apps/ios/Core/ProgressEngine.swift` | Modify | Add aggregate/rollup/recovery types and the pure `ProgressProjection`; remove synthetic remote event merge. |
| `apps/ios/Core/LevelEngine.swift`, `apps/ios/Core/MasteryEngine.swift` | Modify | Consume effective aggregate evidence. |
| `apps/ios/App/LearningProgressView.swift`, `apps/ios/App/ListeningView.swift` | Modify | Replace direct completion-score scans with the projection. |
| `apps/ios/Tests/ProgressEngineTests.swift` and database/store tests | Modify | Cover recovery, projection readers, account isolation, replay, and no synthetic events. |
| `apps/android/app/src/main/java/com/jkdc/app/network/ProgressClient.kt` | Modify | Request/decode v2 pages and expose narrow legacy fallback. |
| `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt` | Modify | Serialize account-bound passes, persist resume state, and route reads through the projection. |
| `apps/android/app/src/main/java/com/jkdc/app/core/Models.kt`, `ProgressEngine.kt` | Modify | Add aggregate/rollup/recovery types and pure projection; remove synthetic remote event merge. |
| `apps/android/app/src/main/java/com/jkdc/app/core/LevelEngine.kt`, `MasteryEngine.kt` | Modify | Consume effective aggregate evidence. |
| Android progress, database, and view-model tests | Modify | Mirror iOS recovery, projection, account, resume, and no-synthetic-event coverage. |
| Root/generated freshness checks (`Makefile` and existing checker tests as applicable) | Modify | Include `export_progress_sync_contract.py --check`. |

Exact generated file names should follow the existing `SchemaData` naming/location pattern chosen by the exporter; tasks must name them before implementation rather than creating hand-maintained duplicates.

## Interfaces / Contracts

### HTTP v2

```ts
interface ProgressPageV2 {
  version: 2;
  progress: Array<{
    kind: ProgressKind;
    itemID: string;
    language: ProgressLanguage;
    firstAt: number; // integer Unix milliseconds
    lastAt: number;  // integer Unix milliseconds
    attempts: number;
    bestScore: number;
  }>;
  nextCursor: string | null;
  complete: boolean;
}

interface ProgressUploadV2 {
  version: 2;
  entries: ProgressEntryV2[]; // 1...200
}
```

Invariants:

- `complete === true` implies `nextCursor === null`.
- `complete === false` implies a non-empty page and non-null cursor.
- A cursor identifies the last returned immutable identity, not a timestamp or snapshot.
- `firstAt` and `lastAt` are safe integers inside the shared bounds, with `firstAt <= lastAt`.
- Unsupported/invalid versions and cursors fail with bounded service codes; the server never guesses a cursor version.

### Native projection

```text
ProgressIdentity(kind, itemID, language)
ProgressAggregate(firstAt, lastAt, attempts, bestScore)
SynchronizedProgress(accountID, identity, aggregate)

ProgressProjection.build(
  lessons,
  completionEvents,
  synchronizedRollups,
  activeLanguage,
  now
) -> ProgressProjection

ProgressProjection.aggregate(identity) -> ProgressAggregate?
ProgressProjection.bestScore(kind, itemID, language) -> Int?
ProgressProjection.uploadEntries(afterIdentity, limit) -> [ProgressAggregate]
```

Merge is commutative and idempotent:

```text
firstAt   = min(local.firstAt, remote.firstAt)
lastAt    = max(local.lastAt, remote.lastAt)
attempts  = max(local.attempts, remote.attempts)
bestScore = max(local.bestScore, remote.bestScore)
```

`completionEvents` remains available for true event-history calculations. No projection method returns fabricated `CompletionMark` or `SessionRecord` values.

## Testing Strategy

| Layer | What to Test | Approach |
|---|---|---|
| Shared contract | Constant parity and freshness | Exporter unit tests plus `--check`; fixture asserts exact 2025 lower bound and 48-hour skew on all platforms. |
| API unit | Cursor codec, strict validation, v1/v2 body parsing, time bounds | Table-driven Node tests including malformed/oversized/base64 variants and boundary instants. |
| API PostgreSQL integration | Deterministic pages, index-compatible query, replay, 1,001+ multi-language identities, concurrent mutation | Real database fixture; assert union/order, `EXPLAIN` plan expectation, same-cursor replay, and recovery on a later full pass. |
| Swift/Kotlin unit | Wire conversion, both evidence-backed recovery paths, quarantine, idempotent marker | Shared instant fixtures and injected `now`; first run changes only safe values, second run is a no-op. |
| Swift/Kotlin projection | Local 40/remote 90, reverse order, replay, multi-language/account isolation | Pure engine tests assert score 90, unchanged event/session counts, and equal results in both merge orders. |
| Local DB integration | Schema v2-to-v3 migration, page merge + cursor atomicity, account partitioning | Inject failure before commit and after commit; reopen and assert replay/resume state. |
| Client transport | Every download page and every upload batch | Mock transport with 1,001 downloads and 201 uploads; interrupt at each acknowledgement boundary. |
| Read-model regression | Every inventoried score/progress reader uses projection | Platform tests for progress screen, level/medal, mastery, listening/reading helpers, streak/activity preservation. |
| Compatibility | Legacy client shape and rollback flag | API tests missing version, new-client documented fallback, disabled-v2 response, and continued future-skew rejection. |

Database, Android, Swift package, and iOS application checks remain separate. Any unavailable PostgreSQL, Android device, macOS/Xcode, or simulator check must be reported as unavailable rather than passing.

## Threat Matrix

N/A — this change does not introduce shell execution, subprocesses, VCS/PR automation, executable-file classification, or agent/process-integration boundaries. HTTP request routing remains inside the existing authenticated Fastify application and is covered by the protocol validation and compatibility tests above.

## Migration / Rollout

1. Ship the additive server implementation with version 2 disabled; legacy GET/POST behavior is unchanged.
2. Ship client schema v3, startup recovery, account-scoped rollup storage, projection readers, and v2-capable transport. Recovery completes before progress is loaded. Clients can still use the corrected legacy decoder while v2 is unavailable.
3. Enable server version 2 in a controlled environment and run API/PostgreSQL plus both client contract suites.
4. Enable version 2 generally. Monitor bounded cursor/recovery/pass reason codes and verify completed passes across supported client versions.
5. Retain legacy serving until supported-client adoption and rollback policy explicitly permit removal; removal is a later change, not part of this design.

Rollback disables `PROGRESS_SYNC_V2_ENABLED`. New clients take the documented legacy fallback; older clients continue unchanged. Local schema v3 tables, repaired timestamps, markers, and remote rollups remain in place. They are not deleted or reverse-migrated. Reverting client code simply leaves the additive tables unread. Server database schema is unchanged unless query-plan evidence later requires an additive index.

The local-erasure change must include `progress_rollups`, `progress_sync_state`, and the progress recovery marker in synchronized-progress scope. That integration is a dependency between changes: this design does not silently alter the separately specified erasure operation.

## Open Questions

None blocking. The cursor representation, compatibility path, evidence-based recovery bounds, account/language partitioning, and projection ownership are resolved above. If implementation evidence shows the existing PostgreSQL primary key is not used for the v2 keyset query, tasks may add an additive account/identity index after an `EXPLAIN`-backed decision; that is a bounded implementation finding, not an unresolved protocol decision.
