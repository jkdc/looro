# Design: Complete Mobile Audit Remediation

> **Status:** Design baseline approved on 2026-10-25; corrected in the subsequent user-requested document review. Implementation has not started. This design does not authorize a silent choice of any new product behavior: the only additional behaviors are constrained by the accepted mobile audit and are listed below.
> **Source of truth for scope:** [`informe-revision-app-movil-2026-10-25.md`](../../../informe-revision-app-movil-2026-10-25.md), requirements M01–M16.

## Document authority

The audit defines scope. The normative S1–S16 scenarios and this design define the corrected contract; the execution plan and task list implement it. Older `audit-progress-sync` artifacts are evidence and reconciliation inputs, not authority to replay stale migrations, introduce another route, or discard historical records. T2 updates only the conflicting old instructions before any source/schema work.

## Intent

Deliver the requested fixes and improvements for the complete Android/iOS audit. Learners must see only their own exam history, retain and safely synchronize their learning, use truthful/interruptible voice controls, and understand when platform or engine capabilities prevent an operation. The objective is reliable, recoverable behavior—not expanding the product or the provider surface.

## Existing behavior and constraints

- Android `LevelTestAttemptStore.list` does not filter the caller's owner and the Learn screen does not key result state by that owner.
- Both native clients exchange Unix milliseconds but persist domain dates as Apple-reference seconds. iOS also has an inconsistent local timestamp writer.
- Both clients upload one 200-row slice without cursor/ack progression; the API returns at most 1,000 progress rows with no continuation. Native merge loses better remote scores and currently places some downloaded aggregates into the event projection.
- Both progress-sync paths lack a retained account-and-data-generation-bound operation guard around awaits and writes.
- Android topic choice mutates an active conversation before confirmation and attempts the next start while the old conversation can still be closing. Its local voice receive loop stores unlimited initial silence and suspends on a spoken response while it is responsible for barge-in detection; initial assistant speech is omitted from rolling context.
- iOS pronunciation requests Speech permission without the corresponding usage description, ignores recognizer errors, lacks cancellation/resource cleanup, and does not enforce the promised on-device mode. The typed-reply sheet dismisses even on failure. One topic-search path expects sources the token engine does not return. Session change notification waits for a later profile call.
- Current database checker reports `user_version 5`; old progress-sync tasks target 2→3 and require reconciliation before implementation. Current SDD and language/security rules govern generated outputs and copy.
- Execution route is the user's choice: sequential Superpowers implementer/reviewer phases. Work remains on `feature/learning-platform`; no Gentle AI, worktrees, parallel writers, provider calls, credentials, deployment, push or PR.
- Android unit/lint and shared Python checks worked offline in the prior audit. There is no attached device. The prior host had no Swift/Xcode/macOS; recheck before the iOS test phase.

## Goals

1. Isolate learning summaries, remote aggregates, sync cursors and asynchronous work by the identity/generation that owns them.
2. Define and test one explicit time representation at every progress boundary; repair only well-supported legacy timestamps.
3. Make a finite sync page/batch safe to retry and able to complete/repeat whole passes until the bounded dataset converges.
4. Preserve local events as local events; project remote monotonic aggregates into score/progress reads without inventing activity history.
5. Bound voice memory and allow capture/detection to remain responsive while turn processing and TTS run.
6. Make permission, audio, network and recognizer failures visible, cancellable and recoverable.
7. Keep all user-facing capabilities truthful and avoid new cloud/provider, purchase or proxy semantics.

## Non-goals

- Reconstruct historical event/day/session timelines from aggregates.
- Force same-device snapshots across a multi-page concurrent sync; the target is bounded, replay-safe eventual convergence on later full passes.
- Make every iOS voice operation work on unsupported on-device languages or newer Android APIs below current minSdk.
- Change anonymous-record ownership, account deletion policy, data-export UI, account endpoints, provider pricing or service credentials.
- Treat warnings, absence of a test, or an unverified field-device hypothesis as new defects without fresh evidence.
- Claim iOS/device proof in a Linux-only environment.

## Approaches considered

| Approach | Benefit | Cost / failure mode | Decision |
|---|---|---|---|
| Patch v1 timestamps and merge only | Small client/server delta | Does not retrieve rows beyond the first limits; no safe interruption resume; still confuses remote aggregates with local events | Reject |
| Add opt-in paged sync, durable owner-scoped progress and separate projection | Complete bounded traversal; idempotent retries; no invented history; safe compatibility staging | Additive schema/exporter work and more persistence/failure tests | **Selected** |
| Clear or rewrite all old progress and start a new ledger | Removes ambiguous state cheaply | Destructive and loses valid learning; no recovery for ambiguous timestamps | Reject |

For voice, use one continuous microphone-frame producer/VAD-barrier and one bounded, serial turn processor; do not share a coroutine that waits for LLM/TTS with the coroutine that detects interruption. For pronunciation, enforce on-device recognition or return unavailable; do not silently fall back to cloud. For UI, model pending user intent separately from the currently active operation and commit it only after acceptance/success.

## Architecture

### 1. Owner-aware stores and UI generations

Every local learner-data query and mutation receives the *active identity* as an explicit input and applies it in the SQL predicate. An active identity always exists: it is either a signed-in member or a stable per-install guest identity. `NULL` ownership is legacy-only during migration, never a wildcard or an active guest representation. Resolve identity before opening/migrating the learner database; a one-time development migration assigns existing ownerless learner rows to that resolved identity. New accounts and fresh databases do not inherit those rows. Pass the captured identity to row validation; clear old visible state immediately and discard asynchronous results after identity changes. Sign-out atomically changes the active identity to the installation guest; signing into a member does not merge or expose guest data.

Ownership applies to all learner data (preferences, sessions/transcript fragments, expressions, lesson state/completions, skill errors, assessment data, mastery metadata and exam attempts plus children), not merely exam history and progress rollups. Device-global caches, indexes, installation identifiers and schema/health metadata remain device-scoped. Export/import, delete/reset and database replacement must include only or preserve the active owner's learner rows and cannot overwrite other owners. Backend progress sync is member-only; guest progress remains local. The backend-to-device path is only a resumable one-time bootstrap for a member identity with no local data for that member on this device; afterward sync is device-to-backend only.

Use one shared learning database per installation and add a stable, namespaced `owner_key` to every mutable learner-owned record: `member:<accountID>` for authenticated users and `guest:<installationUUID>` for local guest identity. Existing exam-attempt owner columns may retain their schema name, but must carry the same namespaced key and all account/guest filters use exact equality. Resolve and persist the active identity before opening the learning database; at the additive migration boundary, backfill only rows whose owner is NULL exactly once to that resolved key, then require every new learner-data write to include it. Keep content snapshots and device-global state outside owner partitioning. On account switch, publish the new key and clear/reload identity-bound in-memory state; never copy rows between keys. This choice preserves the existing shared SQLite architecture while making ownership explicit and queryable.

For progress synchronization, use one retained operation/job per coordinator and a monotonically changing cancellation/generation token bound to owner plus local-data generation. On account change, database import, or other state replacement, cancel and invalidate the operation. Check its generation after each await and before storage mutation and before POST. A cancellation/invalidated result cannot be converted into an ordinary retryable error.

### 2. Time-safe, additive progress protocol

The instant recorded by the originating mobile device is canonical. Keep wire representation as safe-integer Unix milliseconds and native domain representation as explicitly typed Apple-reference seconds. Convert in the client transport once on read and once on write; update the inconsistent iOS completion writer. The backend stores and returns the supplied mobile instant without inventing or replacing it. Validate representable/history/future bounds before persistence.

Version the **existing `/v1/progress` endpoint**, not a new `/v2/progress` route. This reconciles the already documented `audit-progress-sync` design:

| Boundary | Required contract |
|---|---|
| Legacy GET/POST | No version selector: preserve the existing response and strict `{entries:[{kind,itemID,language,at,attempts,bestScore}]}` request |
| V2 GET | `GET /v1/progress?version=2&cursor=<opaque>`; omit cursor for the first page; response `{version:2,progress:[...],nextCursor:string|null,complete:boolean}` |
| V2 POST | `{version:2,entries:[{kind,itemID,language,firstAt,lastAt,attempts,bestScore}]}`; successful response `{merged:N}` acknowledges the entire submitted batch |
| Disabled v2 | `PROGRESS_SYNC_V2_ENABLED=false` by default; **after authentication**, return HTTP 503 with `{error:{code:"progress_sync_version_unavailable"}}` for an explicit v2 request |
| Ordering and bounds | Download at most 1,000 rows; upload at most 200; ASCII/codepoint `(kind,itemID,language)` comparison on API and both clients, including explicit database collation rather than locale ordering |

V2 upload preserves **both** timestamps: `0 < firstAt <= lastAt <= injectedNow + 172800000`, safe integer Unix milliseconds, with the existing kind/language/slug rules, attempts 1–10,000 and best score 0–100. Current v1 only accepts `at`, so its unchanged request cannot promise a distinct earliest timestamp on a fresh server ledger. A conversion helper may test the Unix epoch, but `0` is not a valid persisted API progress timestamp. Do not introduce an arbitrary 2025 cutoff for otherwise valid historical progress.

Uploads are device-originated and continue during routine authenticated member sync. A backend download is permitted only once, as the bootstrap for a member account on a device with no local progress for that member. Persist bootstrap completion per account/device; ordinary later syncs upload local/effective progress without issuing GET. Bootstrap pages remain bounded, resumable and atomic. Guest identities remain supported for local development/testing, are distinct per installation and never sync learner progress to the backend; guest data must never be merged into another guest or member implicitly.

Download cursors encode version and a validated last identity; clients never parse them. The API always queries the authenticated owner's rows; a cursor grants no authority. Use the existing proposed bounded base64url encoding, validate tuple shape and strict ordering, reject non-progressing or inconsistent envelopes (`complete` iff `nextCursor == null`), and never use offsets or mutable time ordering. Per-page writes check the operation owner/data generation under the same serialized write authority as transaction commit and database replacement: no asynchronous gap may separate the final check and write.

Persist upload continuation and one-time bootstrap state by `(accountID,direction)` plus protocol/database identity. Runtime job tokens may change on process restart without discarding valid state; database import/replacement invalidates and clears prior progress cursors under the same write authority. Upload selects a deterministic suffix of the current effective projection and advances only after full-batch ack; an uncertain ack repeats an equivalent identity range safely. Bootstrap download commits rollups and next cursor in **one** transaction. Completion clears the cursor and records bootstrap completion; later invocations upload only.

Only HTTP 503 **and that exact structured code** permit one v1 compatibility pass with corrected decoding and the same no-synthetic-event rollup write path. No v2 cursor is changed by that pass. 401/403, 429, other 5xx, timeout, cancellation, malformed 200, a v1-shaped 200 from an old server, invalid cursor, and unsupported version are not downgrade signals. Preserve data and report the actual error. New clients require the negotiated server contract; old clients retain v1 unchanged.

Local tests enable v2 explicitly. Production rollout/deployment is not authorized here. A default-off implementation can be verified locally, but M04 must not be described as fixed for a deployed app while it is still using capped v1 compatibility mode.

Reconcile the existing progress OpenSpec against the current schema first. Advance the current local schema from its actual checked-out version using the next valid additive migration. Do not apply the old 2→3 plan, delete tables/rows, or align schema version numbers with API protocol versions.

Legacy recovery must be versioned, transactionally idempotent and evidence-bounded: repair only values whose epoch/unit is determinable from persisted mobile-writer/version and timestamp-encoding evidence plus validation; this is not a requirement to classify a row as local versus downloaded. Size or a plausible date alone is insufficient; retain original ambiguous/unrepresentable records unchanged, track their exclusion from sync with bounded reason codes, and avoid advancing a global recovery-complete marker over an unresolved failure; valid progress keeps flowing. Never assume “large means milliseconds,” clamp bad dates to now, expose raw values/tokens/content, or silently omit every row after one bad row.

### 3. Local-event history and remote aggregates

Persist authenticated remote aggregates in a distinct account-scoped rollup table and continuation table. Project local events plus the active account's remote rollups into score/mastery/completion reads by stable `(kind, itemID, language)` identity. Merge `firstAt = min`, `lastAt = max`, attempts and best score by the current monotonic aggregate rules. Score projection and subsequent uploads must use this same effective aggregate, with attempts merged by maximum, never addition. It must be commutative, idempotent and language scoped. Preserve unresolved legacy-language rollups for round-trip, but never project them into an arbitrary current language. Exclude rollups belonging to other owners. The authorized one-time migration assigns legacy ownerless local learner data to the active identity; this is not a guest-to-member merge because the ownerless development rows are assigned once before new account data is created. Local streaks, sessions, recent-activity charts and event histories read local events only. Remote data must never generate synthetic `CompletionMark`, `LessonState` event, session or daily activity.

Do not delete or relabel existing completion marks as part of this work: those stored marks do not encode trustworthy local-versus-remote provenance. Additional evidence may be recorded for diagnostics, but reclassifying or deleting existing marks is outside this remediation; unknown legacy entries stay intact. New sync pages never create such marks. This preserves valid learner records without claiming that legacy aggregates can be reconstructed as true local history.

### 4. Continuous, bounded Android voice

Bound sample buffering in samples/time for all pre-speech and active-turn states; preserve enough preroll for the first real syllable. Use a bounded audio-frame handoff with a continuous source/VAD/barge-in monitor and one owned serial consumer for STT→LLM→TTS. While TTS is active, detect sustained user speech and cancel playback without feeding playback echo/interrupt frames into the next turn. Explicitly close/cancel child jobs with the conversation; do not spawn per-frame jobs.

Route initial tutor opening, voice replies and typed commentary through one role-aware bounded conversation history exactly once. Theme switching is a `pendingTheme` transition: mutate no active history or prompt until confirmation; on confirmation wait for terminal session close/settlement before starting exactly one new session. Preserve cancellation, close timeout and account changes.

Specify queue capacity and preroll/active-turn sample limits in the task brief before implementation, derive them from the real sample rate, and test deterministic overflow/cancellation without per-frame coroutines or dropping a first syllable silently. Preserve the existing safe behavior on devices without usable echo cancellation; do not enable unsafe always-listening playback there.

Offer a separate, state-exposed microphone mute in manual mode. Mute means silence microphone input, not stop tutor output or cancel pending replies. Continue EN/ES copy, Compose tags and accessible state/actions. The mute target is at least 48 dp, has a state description, and discards accumulated muted samples rather than replaying them on resume.

### 5. Honest and private iOS speech/UI

Supply localized Speech and microphone usage descriptions in the built app Info.plist. Pronunciation state is explicit (`idle`, permission, recording, recognizing, result, failure/unavailable) and request scoped. Recognizer, recorder, timeout and temporary audio URL have one idempotent cleanup path, and navigation/background cancels/invalidate work before it can mutate lesson results. Prefer unique temporary audio per attempt and remove it on every terminal path. Coordinate AVAudioSession deactivation with shared conversation playback; only release the session acquired by this operation.

Check both recognizer availability and on-device capability and require on-device recognition for the request. Unsupported locale/model or a local recognizer failure returns a truthful non-scored listen-and-repeat fallback. Neither prompt description nor UI may promise stronger privacy than the request enforces.

For typed reply, keep the editor, draft and focus on network/HTTP error. Track one pending message identity and allow at most one in-flight generation for it; retry reuses the same local history entry rather than appending a duplicate. A second concurrent submit is ignored. Do not automatically resend an uncertain provider operation: after a visible failure, an explicit user retry may issue a new request, with its normal provider cost. The client has no guarantee of idempotency at the external LLM boundary. Dismissing explicitly cancels/invalidates the local continuation; a response for an old conversation cannot render success. For topic search, expose actual engine source/search capability, preserve cached topics, and block unsupported fresh searches before a model call. Do not solve either UI limitation by altering the proxy or switching keys/providers. Announce account-session adoption immediately after its guarded keychain/session commit, independently of profile fetch; distinguish profile failure from invalid credential and retain symmetric invalidation behavior.

## Requirements addressed

These requirements preserve the IDs and acceptance intent of the user-requested audit; path details and expected tests are in its M01–M16 entries.

| Spec | Audit item | Required observable result |
|---|---|---|
| S1 | M01 | Exam history exposes only the requesting owner's records and discards stale reads after identity change |
| S2 | M02 | The same real timestamp completes API→native persistence→API round-trip; proven legacy damage is recoverable and ambiguity quarantined |
| S3 | M03 | Responses and uploads from an older owner/data generation cannot change or transmit the replacement data |
| S4 | M04 | Replaying/restarting pages eventually transfers every identity without omission or duplication |
| S5 | M05 | Better remote scores become visible; new remote pulls create no local events; unattributable existing history is retained unchanged |
| S6 | M06 | Canceling theme switch is a no-op; confirming waits for close and starts exactly one requested conversation |
| S7 | M07 | Prolonged silence retains bounded samples; the first utterance remains complete |
| S8 | M08 | Live voice can interrupt TTS after a voice-originated turn without turning echo into a user turn |
| S9 | M09 | The opening assistant message is available exactly once to the next request and in history order |
| S10 | M10 | Manual voice session offers an accessible, EN/ES microphone mute independent of ending the session |
| S11 | M11 | Every built iOS target invoking Speech authorization includes a real localized usage description |
| S12 | M12 | Permission/recognizer errors, navigation and cancellation always settle busy state and clean resources safely |
| S13 | M13 | Every pronunciation request enforces local-only processing or degrades without scoring/audio upload |
| S14 | M14 | Failed typed send preserves the draft; manual retries reuse one local message with at most one in-flight generation, not one retry for its lifetime |
| S15 | M15 | Search unsupported by the selected engine does not make a provider call and cached topics remain usable |
| S16 | M16 | Session adoption is observed after persistence even when profile lookup fails; invalidation still works |

## Builder-check resolutions

- **Legacy event provenance (S5/M05):** fresh review noted that stored completion marks cannot distinguish earlier remote-to-event conversion from genuine local activity. Preserve the existing marks, do not rewrite history without origin evidence, and ensure all new server pulls bypass local event insertion. Cost if this is wrong: old derived activity may remain visible, but no valid learner history is destroyed.
- **Typed-send retries (S14/M14):** fresh review highlighted that a one-retry lifetime cap could prevent eventual recovery. Define the limit as one *concurrent* generation per pending local message, not one retry forever. Explicit user retries reuse the one local message; do not automatically replay ambiguous paid provider requests. Cost: a manually repeated provider call may incur the provider's normal additional charge because no cross-provider idempotency guarantee exists.

## Out of scope and anti-goals

- Do not introduce payment, credential, cloud-Speech, source-retrieval or account behavior beyond the current accepted contracts.
- Do not transform private/guest progress into another owner's history or turn a remote aggregate into historical local activity.
- Do not erase valid progress, automatically overwrite/quarantine prior task artifacts or generated files, or claim unexecuted build/UI evidence.
- Do not fork or hide the implementation on another branch/worktree: repository instructions require the active feature branch.

## Security, privacy and recovery properties

- Owner filters and cursor scopes are always derived from authenticated account identity, not client-supplied arbitrary owner IDs.
- V2 pages are bounded, cursor inputs validated, response data account scoped, durable continuation state private, retries idempotent.
- Logs and quarantine diagnostics contain only bounded reason/count/opaque identifiers; never credentials, audio/transcripts or full progress payloads.
- No microphone sample is uploaded by the pronunciation feature; UI/audio resources are closed on every terminal path.
- Schema, data recovery and generated code changes are additive, transaction protected, repeatable, and recoverable without a destructive reset.

## Verification strategy

For each work unit, implementer authors a focused public-boundary regression first, observes the expected RED, implements the minimum behavior, runs that phase's focused test and suite once, and reports the exact command/output. Fresh task reviewer reads the immutable unit diff and verifies the report against S# before the next unit. Parent verifies each accepted diff against its declared edit surface, runs one integration seam check after related units, then performs final whole-change readback.

Android tests/lint use repository Gradle tasks offline when cached; shared exporters and schema use `make check`; API protocol work uses service unit and PostgreSQL-backed tests; iOS uses `swift test --package-path apps/ios` for Core and `make ios-test` for the App/UI target only when the necessary macOS/Xcode environment is available. Connected Android tests, TalkBack/VoiceOver and device audio tests require an actual test device. An unavailable platform check remains pending and is stated explicitly, never substituted with static review.

## Implementation phases and dependencies

The execution plan and `tasks.md` share T1–T16. S1–S16 still denote audit requirements; task numbering is not requirement numbering.

| Task | Requirement | Deliverable and prerequisite |
|---|---|---|
| T1 | S1 | Owner-filtered history and stale UI fencing |
| T2 | S2,S4 | Reconciled SDD, shared contract, pure time primitives and recovery classification; no premature live-store rewrite |
| T3 | S3 | Live-identity/generation fences plus actual iOS App test-target infrastructure; do not rely only on the delayed notification fixed in T15 |
| T4 | S4 | Existing-route v2 negotiation, distinct timestamp upload, bounded pages; consumes T2 contract |
| T5 | S2,S4,S5 | Additive schema, evidence-backed recovery, **remote-rollup destination**, atomic page/cursor write, ack persistence and pure projection; consumes T2–T4 |
| T17 | S1,S17 and account-bound S3–S5 | Resolve active member/guest identity before learner DB migration; one-time owner backfill; enforce owner-scoped local reads/writes, archive/import/reset and member-only sync. Required before T6–T8 are considered complete. |
| T6 | S2,S3,S4,S5 | iOS transport writes through T5, not the legacy synthetic-event path |
| T7 | S2,S3,S4,S5 | Android transport writes through T5, not the legacy synthetic-event path |
| T8 | S5 | Wire read-model consumers to T5 projection and validate aggregate upload feedback; T6/T7 remain staged until this integration gate passes |
| T9 | S6 | Pending theme, awaited close and single restart |
| T10 | S7,S8,S9 | One coherent bounded voice pipeline and exact-once shared context |
| T11 | S10 | Accessible manual mute built on T10 |
| T12 | S11,S12,S13 | Local-only pronunciation permissions and resource lifecycle; uses T3 App tests |
| T13 | S14 | Typed reply retry and editor recovery; separate reviewable behavior |
| T14 | S15 | Engine capability gating; independent from typed-send and session adoption |
| T15 | S16 | Session adoption notification/profile failure; preserves T3 generation fencing |
| T16 | S1–S16 | Cross-platform integration, fresh whole-change review and verification evidence |

T5 must exist before a client persists a page. T17 must establish account-owned local storage and identity-ready migration before T6–T8 can be considered complete. T2 is not a claim that existing data has been repaired: S2 also requires T5 recovery plus T6/T7 persistence round-trips. S4 requires T4–T7 and local v2 enablement; S5 requires T5–T8. Never check off an audit finding based solely on its earliest task. All tasks remain pending.

Parent owns git and final commits. Implementers produce code/tests and evidence, a fresh reviewer checks the frozen task candidate, then the parent commits the reviewed bytes conventionally. No worker commits, branch changes, worktrees, push or PR. If required platform tests cannot run, record `implemented / platform verification pending`, not complete or verified; the final report lists every missing gate.

## Test-target and schema facts

- Canonical SQL is `shared/db/schema.sql`; it already contains versioned blocks through **5**. Append the next valid version (6 if still free), preserve older blocks, update native openers and the pinned exporter/checker version and inventory. `python3 scripts/export_db_schema.py` generates **`SchemaData.kt` and `SchemaData.swift`**; course JSON exporters do not own these outputs. Test fresh install, v5 upgrade preserving exams/outbox/conflicts, all supported earlier paths, reopen and transactional rollback. Archive JSON fixtures are not evidence of existing versioned SQLite snapshots.
- `apps/ios/Package.swift` compiles only `Core` and `Tests`. Existing Xcode targets are App and AppUITests, not App unit tests. T3 adds an App-hosted `AppTests` target/scheme entry and deterministic injected adapters; extracted Core policies are also tested by SwiftPM. App tests exercise the real adapter, not only a detached policy or a source-text assertion. `make ios-test` and `swift test --package-path apps/ios` are separate checks.
- Android UI uses the isolated uiTest variant: `:app:assembleUiTestAndroidTest` and `:app:connectedUiTestAndroidTest`. API integration tests can silently skip without `TEST_DATABASE_URL`; parser-only green does not close PostgreSQL behavior. Use `make test-api` for isolated integration proof.

## Open questions / decisions delegated to implementation

- Exact monotonic migration number and platform upgrade fixtures: determine from the current source and shipped migration sequence during SDD reconciliation, never copy stale 2→3 numbers.
- Exact per-frame queue and preroll capacities: choose against current sample/frame duration, existing tests and device constraints; record one ruling and test the bound.
- Exact localized permission copy and unavailable-state copy: preserve product tone, privacy reality and EN/ES/Xcode localization conventions.
- Production enablement timing remains an operator decision; the local flag, status/code, envelopes and page limits are fixed above. Do not deploy or change production configuration.

No other product decisions may be inferred from an unavailable test device or error case.
### Whole-install backup scope (deferred)

For this remediation, whole-file database backup/export continues to include all locally stored owner partitions, and restore preserves their namespaced owner keys. Runtime application reads and writes remain scoped to the active member or guest. Whether a backup should instead contain only the active owner's rows is deferred for a later product decision; this is not a guarantee that exported files exclude other local identities.
