## Exploration: Audit progress synchronization findings F01/F03/F04/F12

### Current State

Progress sync is a pull-then-push exchange against `GET/POST /v1/progress`. The API stores one monotonic aggregate per `(account_id, kind, item_id, language)`: `first_at` takes the minimum while `last_at`, `attempts`, and `best_score` take the maximum. POST accepts at most 200 aggregate entries and is replay-safe at the database merge boundary.

Both clients build cumulative rollups from local lesson counters and completion marks, sort by newest timestamp, truncate to 200, and send exactly one batch. This makes the API's valid per-request limit into a permanent client-side ceiling: retries select the same newest subset. Both clients fetch exactly one response, while the API returns only the newest 1,000 rows and exposes no cursor or truncation signal.

The wire timestamp is an undocumented Unix-millisecond number. Upload converts the clients' internal Apple-reference seconds to Unix milliseconds correctly, but download passes Unix milliseconds directly into fields whose domain is Apple-reference seconds. Those contaminated values can later be multiplied and shifted again during upload, producing far-future timestamps rejected by the API.

Remote merge restores an unknown exercise/listening/reading item as one synthetic `CompletionMark`, but does nothing when that identity already exists. Consequently, a remote best score of 90 cannot improve a known local score of 40. Existing tests verify that no duplicate is appended, but do not require score convergence.

### Affected Areas

- `services/api/src/progress.ts` — POST validation and monotonic upsert; GET has an unstable completeness contract (`ORDER BY last_at DESC LIMIT 1000`) and emits database dates as Unix milliseconds.
- `services/api/tests/progress.test.ts` — parser and PostgreSQL-backed merge coverage; needs boundary fixtures for wire timestamps and paged retrieval.
- `services/api/migrations/030_user_progress.sql` and `032_user_progress_language.sql` — establish the aggregate identity and indexes that cursor design must respect.
- `apps/ios/App/ProgressClient.swift` — one-shot GET/POST transport and wire decoding.
- `apps/ios/App/ConversationCoordinator.swift` — pull-then-single-push orchestration.
- `apps/ios/Core/ProgressEngine.swift` — 200-entry truncation, Apple-to-Unix conversion, and remote aggregate projection into local history.
- `apps/ios/App/Storage.swift` — persistence boundary for remote merges and the natural home for idempotent recovery writes.
- `apps/ios/Tests/ProgressEngineTests.swift` — currently accepts local 40/remote 90 without score improvement.
- `apps/android/app/src/main/java/com/jkdc/app/network/ProgressClient.kt` — one-shot GET/POST transport with raw response handoff.
- `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt` — pull-then-single-push orchestration, wire parsing, and persistence of remote changes.
- `apps/android/app/src/main/java/com/jkdc/app/core/ProgressEngine.kt` — 200-entry truncation, Apple-to-Unix conversion, and remote aggregate projection.
- `apps/android/app/src/test/java/com/jkdc/app/core/ProgressEngineTest.kt` — currently checks deduplication rather than convergence.

### Approaches

1. **Patch each symptom independently** — Convert download timestamps, remove the client prefix, update an existing mark's score, and add a GET offset.
   - Pros: Small initial diff and limited schema work.
   - Cons: Offset paging is unstable under concurrent updates; an interrupted uploader can repeatedly resend early batches; rewriting an event as an aggregate blurs history; recovery of already contaminated data remains ad hoc.
   - Effort: Medium

2. **Stable cursor protocol plus a separate synchronized-rollup projection** — Version the progress response contract, use deterministic keyset cursors, page upload and download, normalize time only at wire boundaries, and keep remote aggregates separate from local event history.
   - Pros: Idempotent retries, bounded requests, eventual full recovery, explicit timestamp ownership, monotonic score convergence, and no fabricated learning events.
   - Cons: Requires coordinated API/iOS/Android changes and a small durable client-side sync-state/rollup model.
   - Effort: High

3. **Server-owned event history** — Upload immutable completion events and derive aggregates server-side.
   - Pros: Preserves full history and permits exact reconstruction.
   - Cons: Broad protocol and storage redesign, new event identities/deduplication rules, migration complexity, and scope far beyond F01/F03/F04/F12.
   - Effort: High

### Recommendation

Choose approach 2 and keep the proposal bounded to two capabilities:

- **`progress-sync-protocol`** — define Unix milliseconds as the only wire time representation; return a versioned paged envelope; use deterministic keyset ordering by the stable progress identity `(kind, itemID, language)` rather than mutable `lastAt`; require clients to consume all download pages and upload all rollups in batches of at most 200. Cursors must advance strictly, page replay must be harmless, and a sync interrupted after any acknowledged batch must resume without permanently starving later identities. Concurrent mutation is a convergence contract, not a snapshot claim: an update or insert missed because it occurs behind the current cursor is recovered on the next full pass.
- **`progress-sync-recovery`** — convert wire Unix milliseconds to internal Apple-reference seconds on ingress; detect previously contaminated local timestamps only when interpreting the value as Unix milliseconds yields a plausible instant within the supported history window and no later than the accepted future-skew boundary; persist the repair once and idempotently. Unrecoverable out-of-domain timestamps must be excluded from upload and surfaced to diagnostics rather than clamped into false history.

For F04, introduce a durable synchronized-rollup projection keyed by `(kind, itemID, language)` instead of mutating or appending `CompletionMark` events. Local UI/stat projections and future uploads should combine local event-derived rollups with the synchronized rollup using `min(firstAt)` and `max(lastAt, attempts, bestScore)`. This preserves the distinction between evidence history and cross-device aggregates while making local 40/remote 90 converge to 90 without creating a fictitious session.

The paging contract should use an opaque cursor, a deterministic identity tie-breaker, and an explicit `nextCursor`/completion signal. Upload ordering should also be stable by identity, with durable resume state advanced only after a successful server acknowledgement and reset after a completed pass. Because POST is a monotonic upsert, retrying the current page is safe. Avoid mutable-time cursors, offsets, unbounded requests, or a promise of transaction-wide snapshot isolation that the current stateless API does not provide.

Required proof should include shared timestamp fixtures for the exact same instant across API, Swift, and Kotlin; idempotent contaminated-data migration; 201+ uploads with interruption at every batch boundary; 1,001+ multi-language downloads with timestamp ties and concurrent aggregate updates; replayed pages; local 40/remote 90 convergence in both merge orders; and confirmation that no completion event/session is invented. Database-dependent and native checks must remain reported as unavailable unless actually run.

### Risks

- Old client versions can reintroduce contaminated timestamps until deployment compatibility is defined; the API must continue rejecting implausible future values.
- Persisted upload cursors can starve identities if based on mutable timestamps or if acknowledgement and cursor advancement are not ordered durably.
- Identity-ordered stateless paging provides eventual convergence, not a transaction snapshot; this must be explicit in the contract and tests.
- A separate synchronized-rollup projection affects every score/progress read path; missing a projection site can leave cross-platform UI disagreement.
- Recovery heuristics can corrupt legitimate data if they are based only on magnitude; repair requires a plausibility window and idempotence marker/version.
- Existing local archive caps (including completion history) remain separate product limits and must not be silently presented as solved by this change.

### Open Gaps for Proposal/Design

- Define the earliest supported recoverable instant and the durable recovery-version marker for each client store.
- Decide the exact opaque cursor encoding and whether API compatibility requires serving the legacy unpaged envelope during a transition.
- Inventory every UI/stat projection that reads `CompletionMark.bestScore` so the synchronized-rollup overlay is applied consistently.
- Confirm whether account/language changes require independent upload cursors and rollup partitions; the identity already includes language, but local session ownership must be explicit.

### Ready for Proposal

Yes. The affected boundaries, recommended capabilities, invariants, migration safety, and verification targets are sufficiently defined. The proposal should preserve the audit's limitations, avoid claiming snapshot isolation or full event-history recovery, and defer exact schema/API field names to design.
