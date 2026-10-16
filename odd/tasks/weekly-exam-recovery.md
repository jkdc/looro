# Weekly exam recovery — ODD feature tasks

Implement immutable exam snapshots and durable local attempts, with authenticated
cross-device/reinstall recovery, before enabling automated weekly generation.
This is the single ODD recovery document; checkboxes are progress, not review authority.

## Objective, problem, and rationale

**Objective:** learners can start offline, resume after interruption, and review the
exact questions, answers, passages, and original grading they used. The same account
can restore server-confirmed attempts on another device or a clean installation.

**Problem:** Android currently composes bundled exams in memory, retains only the
latest summary in preferences, and syncs aggregate progress rather than attempts.
Generated content has contract/validation/settings gaps; backend history lacks a
stable retrieval API; iOS lacks the exam/placement implementation and full recovery.

**Why:** downloading newer content must not change an ongoing or historical attempt.
Local persistence must not be presented as cloud durability. Generation must not
publish malformed questions or duplicate billable work across schedulers/CLI.

## Authorization and working identity

| Setting | Value |
|---|---|
| Feature identity | `weekly-exam-recovery` |
| Repository-relative locator | `odd/tasks/weekly-exam-recovery.md` |
| Engram project/topic | `looro` / `odd/weekly-exam-recovery/tasks` |
| Isolated worktree | `/home/joaquin/Proyectos/looro-test/looro-worktrees/weekly-exam-recovery` |
| Feature branch | `feat/weekly-exam-recovery` |
| Branch point | `a836e8269a2d77cd6a081bc635a59439510c0e89` |
| Integration target | `feature/learning-platform` — NEVER `main` |
| Delivery strategy | `ask-on-risk` |
| Chain strategy / user selection | `feature-branch-chain` |
| Local integration | Explicitly requested final local merge into `feature/learning-platform` |
| Remote authorization | None: no push, PR creation, remote execution, or deployment |
| Current parent authorization | Full plan T01–T14 authorized by the active user goal; parent-controlled implementation resumes at T03. No delegated writes. |

The approved requirement source remains the original checkout's untracked report:
`/home/joaquin/Proyectos/looro-test/looro/informe-plan-pendientes-2026-10-13.md`.
Baseline SHA-256: `1120fad002719b4183c3446892a4d993b5ea0f2fb2e991cf2bd0cfc05217e3e7`.
Do not edit, stage, or duplicate that report inside the isolated worktree.
Implementation authorization covers the approved report and same-account recovery;
parent reconciliation of this file and full mirror precedes the first source edit.
Source implementation dispatch and local integration remain parent-controlled.

## Scope and constraints

- Implement report sections 1–10 and stable tasks T01–T14, not an SDD pipeline.
- Freeze full snapshots, including bundled content, before displaying the first
  question; keep schema/content/grading versions distinct from week ID and ETag.
- Store attempts, answers by question ID, position, original per-question verdicts,
  and original result transactionally in SQLite; cache expiry never erases history.
- Use authenticated account ownership, durable exam outbox, idempotent ACK/CAS,
  explicit conflicts, and bounded paginated restore. Do not reuse usage telemetry
  or aggregate progress as attempt history.
- Anonymous attempts remain local until explicit ownership attachment. Never send
  account A's answers under B; honor existing local/account deletion and backups.
- Recovery requires successful server confirmation under the same account.
  Unuploaded changes cannot survive losing their only device; expose pending state.
- Generated content must preserve kind/unitID/passages/thresholds and validate
  structure; strict judging fails closed, drafts require approval before publication.
- Scheduler stays off by default; UTC one-shot scheduling, pre-call distributed
  exclusion, preserved drafts, bounded retries/shutdown, no live `/proc` env switch.
- iOS must match Android's offline and remote attempt/review/restore lifecycle;
  Swift Core tests do not prove App/UI. Missing macOS/native/DB checks stay pending.
- Preserve existing unrelated changes and historical artifacts. No content authoring,
  credentials, real LLM calls, remote transfer, or production activation is authorized.
- Technical artifacts default to English; extend existing UI language where appropriate.
- Conventional work-unit commits only; never AI attribution or Co-Authored-By.

## Effective testing mode and verified command inventory

- `strict_tdd: false`
- Source: `openspec/config.yaml` (`testing.strict_tdd` and project entries).
- Mode: **Standard** — ordinary functional checks required; RED-first is not enabled.
- Project/root runner: `make check` (configured project test command).
- `testing.workspace_test_command` is empty: `make check` is not evidence that all
  native/API suites ran. Do not infer all-platform coverage or invoke `sdd-init`.
- Android JVM verification is reproducible through the pinned Docker runner;
  do not install JDK, Gradle, or Android SDK on the host for this work.

Commands below are inventory, NOT execution evidence. Run only commands applicable
to the dispatched work unit and available authorized environment, in stated cwd.

| Area / cwd | Exact commands from configuration/report |
|---|---|
| Repository root | `make check`; `python3 -m unittest discover -s scripts/tests -t .` |
| Root additional targets | `make test-api`; `make android-build`; `make ios-test` |
| `services/api` | `npm run check`; `npm run build`; `npm test` |
| `apps/android` JVM | `apps/android/docker/test-unit.sh` (Docker; invokes the Gradle Wrapper tasks below) |
| Android Gradle tasks inside the container | `./gradlew --no-daemon :app:testDebugUnitTest :core:engine-local:testDebugUnitTest` |
| `apps/android` quality | `./gradlew :app:lintDebug :app:assembleDebug` |
| `apps/android` UI | `./gradlew :app:connectedUiTest` — device/emulator required |
| Root exporter freshness | `python3 scripts/export_placement.py --check`; `python3 scripts/export_leveltests.py --check` |
| Root platform contracts | `python3 scripts/export_android_content.py --check`; `python3 scripts/check_cross_platform.py` |
| Root iOS Core | `swift test --package-path apps/ios` — current project's macOS gate |

Backend integration requires the authorized PostgreSQL test environment and
`TEST_DATABASE_URL`; `make test-api` starts an isolated Docker/PostgreSQL smoke
stack and needs its own scoped execution authorization. None has been run here.
Admin browser e2e runner must be resolved before T11 verification; no invented runner.
Project regeneration is source-mutating normalization, not verification:
`python3 scripts/generate_project.py` runs when App files change, BEFORE final checks.

Exact report App/UI commands (repository root; macOS/Xcode and simulator required):

```sh
xcodebuild -project apps/ios/App.xcodeproj -scheme App \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath .build/DerivedData \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES build
xcodebuild -project apps/ios/App.xcodeproj -scheme App \
  -destination 'platform=iOS Simulator,name=iPhone 17,arch=arm64' \
  -derivedDataPath .build/DerivedData CODE_SIGNING_ALLOWED=NO ARCHS=arm64 \
  ONLY_ACTIVE_ARCH=YES -parallel-testing-enabled NO test
```

## Ordered task checklist and acceptance proof

Each stable ID maps to the approved report section and phase. Break large tasks into
coherent work-unit commits without changing IDs; keep their tests/docs together.
A checkbox closes only after observed acceptance checks and commit identity are
recorded below. Preparation is not completion; no task is checked at initialization.

- [x] **T01 — Normalize immutable snapshot contracts and identity.**
  Phase P0; report §§3–4, 9/T01. Files: API level-tests/types/routes, shared fixtures.
  Prove kind/unitID/passages/thresholds, variant compatibility, hash/version semantics,
  stable stored identity and new identity on regeneration; never recompose remote.
  Parent scope clarification: by-ID historical API is T04, not T01.
  Checks: API typecheck/build/unit+applicable integration; cross-platform fixtures.
- [x] **T02 — Enforce structural validation, judge policy, settings, publication.**
  Phase P0; report §4, 9/T02. Files: level-tests validation/generator/config/admin API.
  Prove rejected items excluded, strict judge failure/incompleteness/minimum shortfall
  blocks publication, flag advisory, drafts private until approved, faithful save/reload
  and partial settings updates including strict → flag → off.
  Checks: API typecheck/build/unit+applicable integration.
- [x] **T03 — Add durable local snapshots, attempts, answers, result, and outbox.**
  Phase P1; report §3, 9/T03. Files: shared SQL/exporters, Android/iOS DAOs/writers,
  backup/restore/erase paths. Prove atomic start, answer/position rollback, immutable
  result/idempotent completion; legacy stays summary-only. Android and shared-schema
  acceptance verified. Per explicit user decision L9, retain Swift implementation but
  exclude Swift test execution from this task closure; Swift behavior remains unverified.
  Checks: 136 Python tests; schema/export; Android app 672/672 and engine-local 11/11;
  fresh-scratch independent verification; v1/v2 backup checks pass.
- [x] **T04 — Add owner-scoped cloud attempts and immutable retrieval.**
  Phase P2; report §4, 9/T04. Files: proposed exam-attempts API/migrations/tests.
  Prove account authorization, bounded pagination/bytes, no dangling snapshot refs,
  validated private bundled upload, published/private separation and retired retention.
  Checks: API typecheck/build/unit+PostgreSQL integration when available.
  Proof: API Docker build passes; focused PostgreSQL integration passes 4/4.
  Independent review found and drove a bounded correction for retired public lookup
  and malformed choice snapshots; exact regressions were observed RED then GREEN.
  Full API suite is not clean: test image lacks migration/operations SQL assets and
  a later hosted-paid test hung; no full-suite pass is claimed.
  Commit: `2542ef0` (`feat(level-tests): persist private cloud exam attempts`).
  Native review: approved and acknowledged; target `sha256:746d15f0290842aa80119da1e41109e1d2bb7bcdcfba65af11b2095a4a2b2cfb`, authority burned. One non-blocking WARNING `R3-001` remains at `services/api/src/level-test-attempts.ts:119` for later follow-up.
  Risk: item 1 (new persisted account-owned exam history) and item 2 (authenticated private routes and deletion cleanup).
- [ ] **T05 — Implement durable sync, ACK idempotence, CAS, and conflicts.**
  Phase P2; report §§1, 4–6, 9/T05. Files: dedicated exam sync clients/outbox and API.
  Prove duplicate event retry, lost ACK, stale ACK with new edits, concurrent devices,
  immutable submitted answers/results, explicit takeover/fork resolution, owner isolation.
  Checks: API and platform state-machine tests with fake transport; DB transaction tests.

### T05 implementation routing and scope ruling

- Route: direct inline after the completed read-only T05 mapping. The API's T04
  authenticated snapshot/attempt persistence, Android/iOS transactional outboxes,
  and schema are already mapped; this is one cross-platform sync contract, not
  independent write units. Keep code, tests, migration, and this record in its
  work-unit commit. All build/test commands run inside Docker; do not run Swift
  commands on Linux or claim iOS verification without macOS.
- T04's batch endpoint remains initial immutable snapshot/attempt creation only.
  T05 adds a dedicated event-sync contract: a stable event ID plus attempt ID,
  base revision, new revision, event kind, and payload; the server atomically
  records the event and conditionally applies its state transition. ACK is issued
  only after commit and replay of the same event returns its original ACK; reusing
  an event ID with different bytes is rejected. A stale base revision returns an
  explicit conflict containing server-confirmed state; it never merges or applies
  last-write-wins. A submitted result is immutable.
- Local ACK handling is compare-and-set on event ID and revision: acknowledge only
  the exact delivered event/revision, preserving later outbox rows. The client
  exposes conflict resolution as explicit operations: continue from the confirmed
  server revision (takeover) or fork the divergent local state into a new attempt;
  no automatic reset, overwrite, or cross-account upload. Transport/session owner
  must still match the attempt owner before draining.
- T05 proof covers server transaction/replay/CAS/owner behavior and testable local
  outbox ACK state transitions through fake transport. Android tests run in Docker;
  Swift source/tests may be retained but cannot be reported verified in this Linux
  environment. Any unresolved acceptance proof remains explicitly open.

### T05 implementation progress (not closed)

- Added migration `043_level_test_attempt_events.sql` and authenticated
  `POST /v1/level-test-attempts/events`: one event per request; SHA-256 canonical
  payload ledger, transaction-scoped event-ID advisory lock, commit-before-ACK,
  byte-identical replay ACK, changed-payload rejection, revision CAS, confirmed
  server branch on stale conflict, immutable completion, and explicit takeover
  event. Divergent forks use a new attempt ID through T04's immutable creation API.
- Android and Swift DAOs now list owner-scoped pending outbox events, ACK only an
  exact attempt/event/revision under the matching account, preserve newer pending
  edits, and mark conflicts. Local answer/completion mutations return owned attempts
  to `pending` (anonymous attempts remain `local_only`). Android and iOS authenticated
  HTTP clients expose the fixed event-sync endpoint; Android fake-transport test
  covers endpoint/session/payload binding. API contract is in
  `services/api/docs/level-test-attempt-sync.md`.
- Android now drains outbox events on session restore/sign-in, preserves the owner
  captured for the drain, verifies exact ACK identity/revision, and sends a local
  fork as a new ID with its retained answers and frozen snapshot. Normal creation
  remains revision 1 followed by ordered event delivery.
- Independent high-risk verification reproduced two local defects and one missing
  acceptance: a later conflict retained the old takeover resolution; JSON `null`
  created a result row for an unfinished attempt; platform fork resolution was
  absent. Corrected both DAO implementations. The explicit fork now supersedes old
  pending source events transactionally; its Android end-to-end test verifies upload
  without manual SQL repair. Android and Swift regression tests were added.
- Independent bounded recheck passes all three blockers in a fresh scratch copy:
  repeated takeover, nullable unfinished result, and fork upload without manual DB
  repair. Android full JVM run with `--rerun-tasks` passes 693 tests (682 app + 11
  engine-local); JUnit XML confirms 0 failures/errors/skips. Docker API checks pass:
  typecheck, focused PostgreSQL suite 6/6, independent transaction probes 7/7, and
  no-DB suite 506 tests (140 pass, 366 skipped, 0 fail). Python schema/export suite
  passes 136/136; generated schema checks are clean at user_version 5. `git diff
  --check` is clean. Swift tests were not run on Linux per explicit user constraint;
  Swift source and tests remain unverified.
- Implementation and acceptance proof are complete. Still open only: final task/mirror
  reconciliation, work-unit commit, native risk assessment and any consented review
  lifecycle. Keep the checklist open until those are observed.

### T05 continuation ruling

- Ruling: keep the local divergent attempt intact when a server CAS conflict arrives,
  and persist the server-confirmed attempt as a separate conflict branch before
  offering resolution. Explicit takeover adopts the server branch; explicit fork
  preserves the local branch as a new attempt. This costs one bounded local conflict
  record per attempt but prevents either branch from disappearing before a choice.
- Ruling: the `created` outbox event uses T04's authenticated immutable `/sync`
  endpoint with its frozen snapshot and initial attempt; only after that acceptance
  is the exact creation row ACKed. Subsequent progress/completion events use T05's
  event endpoint in revision order. This is necessary because the event endpoint
  deliberately accepts only mutations of an already-created attempt.
- Android and iOS authenticated clients now expose the fixed T04 initial-attempt
  sync route as well as T05 event sync. Android's MockWebServer test verifies the
  owner bearer, exact route, and one-snapshot/one-attempt envelope. This is a
  transport seam only, not a production outbox drain or conflict-resolution flow.
- Android client regression was observed RED before adding `syncExamAttempt`
  (unresolved method), then the focused MockWebServer class passed 10/10 in the
  pinned Docker test image. A concurrent first Gradle invocation held the shared
  cache lock; after it exited, the focused run completed successfully. The Docker
  `git diff --check` is clean. The full Android suite has not yet been rerun.
- [ ] **T06 — Implement Android defensive fetch, cache, and frozen selection.**
  Phase P3; report §5, 9/T06. Files: proposed remote client/snapshot adapter/store.
  Prove total five-second deadline, propagated cancellation, invalid schemas/IDs/passages,
  valid/invalid 304, fixed variant and late fetch after answering cannot replace exam.
  Checks: JVM parser/client/cache/state-machine tests, lint/build.
- [ ] **T07 — Implement Android autosave, resume, history, review, restore.**
  Phase P3; report §§3, 5, 9/T07. Files: ViewModel/Learn/LevelTest UI and store.
  Prove kill/reopen by attempt ID, DB-save failure visibility, no double submit/completion,
  UTC week/year rollover, updated bundle restore, force-generated old attempt review.
  Checks: JVM/instrumentation UI as applicable, lint/build; mocks rather than real LLM.
- [ ] **T08 — Add Swift placement/level-test exporters, engines, shared goldens.**
  Phase P4; report §6, 9/T08. Files: exporters, proposed Core models/data/engines/tests.
  Prove output freshness, SHA-256 and ISO-week parity, B1 week 202641 golden,
  grading equivalence, placement result/instruction preference integration.
  Checks: root Python/exporter/contracts and Swift Core gate.
- [ ] **T09 — Implement full iOS storage, navigation, UI, sync, restore parity.**
  Phase P4; report §6, 9/T09. Files: Core/Database, App storage/views/clients, project.
  Prove generated/bundled exact snapshots, kill/reopen/review, owner switch/conflict,
  restore; do not substitute Core tests for native App/UI checks.
  Checks: normalization then Swift Core, xcodebuild App and UI gates on macOS.
- [ ] **T10 — Add disabled scheduler and complete Compose configuration.**
  Phase P5; report §7, 9/T10. Files: scheduler/main/compose and fake-clock tests.
  Prove UTC next-run recalculation, missed-run recovery, input bounds, preserved drafts,
  per-combination failure isolation, shutdown, pre-call exclusion across replicas/CLI,
  and no blind reissue after unknown external LLM outcome.
  Checks: API typecheck/build/unit; distributed-lock DB integration when available.
- [ ] **T11 — Add effective-config panel and audited draft approval.**
  Phase P5; report §8, 9/T11. Files: admin static UI and admin API.
  Prove save/reload, masked secrets, unsaved form not claimed tested, saved-config
  connection semantics, bounded test, admin auth/Origin, approval gates and job status.
  Checks: API tests plus resolved local browser e2e runner; no real provider calls.
- [ ] **T12 — Demonstrate exact recovery on reinstall and second device.**
  Phase P6 acceptance; report §§1, 3–6, 9/T12. Prove SAME ACCOUNT restores snapshot,
  order/options/passages, own answers, position and original results for old bundled
  and retired generated content, independent of current week/app content.
  Checks: API/platform integration and native recovery flow with fake provider.
- [ ] **T13 — Demonstrate recovery limits, account isolation, explicit conflicts.**
  Phase P6 acceptance; report §§1, 4, 9/T13. Prove unuploaded offline edits cannot be
  recovered from a lost device; pending/synced/error/conflict is visible, switch/logout
  does not leak/sync another owner's content, divergent answers are never silently merged.
  Checks: API authorization, sync/restore state machines and applicable platform UI.
- [ ] **T14 — Record final proof and complete authorized local integration.**
  Phase P6 readiness; report §9/T14 adjusted to current authorization. Record every
  failed/skipped/pending check and final diff/commit evidence; no claim of verified
  iOS without macOS. Merge locally into feature/learning-platform only after applicable
  verification/review and parent approval of readiness. Deployment remains unauthorized.
  Checks: applicable command inventory, clean scope and local merge evidence;
  native review only under effective user-owned RDD mode/returned consent.

## Delivery forecast and slice boundaries

Pre-implementation rough forecast: **7,450–14,350 authored changed lines**
(additions plus deletions), excluding generated Swift/Kotlin/TypeScript/schema/project
outputs. This is a broad planning estimate, not measured proof or a cap.

| Task | Estimated authored changed lines |
|---|---:|
| T01 | 350–650 |
| T02 | 450–900 |
| T03 | 1,200–2,200 |
| T04 | 700–1,400 |
| T05 | 900–1,700 |
| T06 | 350–700 |
| T07 | 600–1,100 |
| T08 | 650–1,200 |
| T09 | 900–1,800 |
| T10 | 350–650 |
| T11 | 350–650 |
| T12 | 300–650 |
| T13 | 250–500 |
| T14 | 100–250 |

Rationale: new local/cloud persistence and sync, two native UI lifecycles, contracts,
review/restore tests, scheduler and panel are coordinated subsystems; native/environment
limitations and existing abstractions can materially change this estimate.

Use approximately 400 authored changed lines per coherent ODD work unit as advisory
review-sizing only: do not omit tests, minify, or split behavior artificially.
Record accumulated authored counts from commits and generated-file exclusions.
For a naturally larger coherent unit, record why rather than size-only rewrite loops.
User selected feature-branch-chain: tracker remains feat/weekly-exam-recovery;
any future authorized PR chain targets its tracker/previous slice, never main.
No PR or remote branch is created by this document or current authorization.

| Slice | Boundary / commits | Measured authored lines | State |
|---|---|---:|---|
| S0 | Branch point a836e8269a2d77cd6a081bc635a59439510c0e89 | 0 | Initial boundary |
| S1 | T01 9106881b4c637dc296b030aad5b404533c0739e7 + T02 13d65ebef8e1db626dbfb5971a910435a7eeb6c1 + R3-001 correction cc5ce58724fc09d427cc7d2a7d11f3bd1405a29f | 1441 (378 + 1051 + 12; no generated changes) | Native four-lens review approved and acknowledged for corrected candidate; see T02 evidence below |

| S2 | T04 `2542ef0` | 564 (no generated changes) | Native three-lens review approved and acknowledged; one non-blocking WARNING `R3-001`; full API suite blocked as recorded above |

No final child-branch or PR boundaries are fabricated now. Parent records actual
coherent boundaries and local work-unit commit IDs as implementation progresses.

## Progress, verification, and recovery handoff

- Completed implementation tasks: **4/14** (T01–T04); **10/14 remain**. T05 is in
  progress; the identified Android sync defects are corrected and independently
  verified, but T05 remains open pending an achievable native review/receipt.
- Latest work-unit commit: `ca849a888ca72558b228156ffd2f2c39c8de25f1` (T05 durable attempt sync; native review remains unacknowledged).
- Measured committed authored total: **2005** changed lines (1441 through T02 + 564 T04; no generated changes).
  T02 includes the initial 376-line recovery document; generated changes: 0.
- Read configuration: strict_tdd=false; Standard Mode and exact runners above.
- Worktree observed clean at the branch point before this document was created.
- Original report is untracked in the original checkout and must remain untouched.
- Local document initialized; Engram full-document mirror required and read back
  before parent dispatch. A failed mirror stays pending, never reported synchronized.
- Review boundary: branch point until parent records a reviewed work-unit/slice.
  T01 assessment is medium; review is deferred under the ODD slice policy, not an
  inferred approval. Preserve the branch-point boundary until slice review is recorded.
- On each task update, preserve prior valid progress and mirror the entire current
  document plus its locator; read back local file and full observation, reconcile.
- On resume: mem_context → project/feature search → full observation → actual file;
  refresh testing mode/source and reconcile proof before next unfinished task.

### T04 implementation routing and scope ruling

- Route: direct inline implementation after bounded CodeGraph mapping; one required
  read-only explorer mapped T04 API/auth/migration/test surfaces. The exact configured
  `gpt-6.1-luna` worker ID is unavailable in this runtime; per the user's instruction
  to assign what is needed, the closest available `gpt-6-luna` ran at high effort.
- Ruling: T04 owns immutable exam content and private attempt persistence/retrieval,
  account ownership, bounded request/page sizes, validation, FK integrity, and public
  published-by-ID retrieval. T05 remains responsible for update-event replay,
  revision CAS, ACK/outbox synchronization semantics, and divergent-device conflicts;
  this boundary avoids prematurely inventing T05's conflict protocol while allowing
  T04 to prove uploads do not create dangling or public private snapshots.
- Keep retired generated rows retrievable through an owned attempt's frozen snapshot;
  public exam-ID lookup serves only currently approved content. Do not delete retired
  generations while attempts reference them.
- Ruling: account deletion must remove private exam attempts, aliases, and snapshots
  in the same existing account-deletion transaction, including when financial records
  require retaining a redacted account row; a signed-out account is not deleted.
- T04-specific test runner: API Node test suite and PostgreSQL integration through
  Docker. No project test/build command will run on the host.

### T05 review-finding corrections (2026-10-15)

The existing T05 candidate `ca849a888ca72558b228156ffd2f2c39c8de25f1` was reviewed
as-is. The advisory review found three defects; these uncommitted corrections do
not rewrite or split the candidate commit:

- Android DAO no longer treats `SqlDatabase.update()` as an affected-row count;
  it confirms ACK, conflict, takeover, and fork transitions through transactional
  readback, compatible with Android's documented `-1` result.
- Fork creation now stores its exact revision-1 attempt payload in the durable
  creation event. Later edits are sent as later revision events, not folded into
  the retry payload.
- Android and Swift outboxes sort by attempt identity and revision, not wall-clock
  `created_at`, so clock rollback cannot reorder dependent events.

| Verification | Observed result |
|---|---|
| New regression tests — RED before behavior fixes | Both row-count and clock-order tests failed as expected. Fork test failed on the mutable creation revision before immutable payload capture was implemented. |
| Android focused regressions in Docker | 3 passed, 0 failures/errors/skips. |
| Full Android JVM app suite in Docker (`:app:testDebugUnitTest`) | 684 passed, 0 failures/errors/skips. |
| Independent verifier, fresh Docker scratch with Android `update() == -1` wrapper | 27 `LearningDbTest` tests passed, including ACK/takeover/fork persisted-state probes. |
| `git diff --check` in Docker | Exit 0. |

Swift source mirrors the revision-order correction but was not executed, per the
user's Linux verification scope. The earlier API tests remain the T05 API evidence;
this correction changed only Android/iOS local persistence and payload replay.
The first native review of this corrected candidate found one repeated blocker:
pre-upgrade fork creation events do not contain `initialAttempt`. Replay now falls
back to reconstructing the legacy fork from local persisted data. If an offline edit
already advanced that fork before its first upload, the uploaded current revision is
accepted as the creation snapshot and earlier outbox rows it covers are atomically
superseded; the drain skips rows no longer pending. Newly written fork events still
use their frozen revision-1 payload and send later revisions separately.

| Follow-up verification | Observed result |
|---|---|
| Legacy fork without `initialAttempt`, edited offline before first upload; frozen new fork; normal creation/event flow | Focused Docker Android tests: 3 passed, 0 failures/errors/skips. |
| Full Android JVM app suite after lost-ACK correction | Docker `:app:testDebugUnitTest`: 686 passed, 0 failures/errors/skips. |
| Independent verifier | Fresh scratch Docker probe passed: identical creation payload after lost ACK, later offline edit delivered separately, local answer retained, and outbox drained. |
| Native Gentle AI review | High-risk review found R1-001/R3-001/R4-001 (same legacy-event defect); bounded correction applied. Targeted native validation, final captures, acknowledgement and receipt remain pending. |

The subsequent advisory review found a narrower retry gap: if the first legacy
creation request reached the server but its ACK was lost, then the user edited
offline before retry, the retry reconstructed a changed creation payload and the
server rejected it as immutable. `initialPayload` now reconstructs and durably
stores `initialAttempt` in the pending creation outbox row within the same SQLite
transaction before transport. Retries reuse those exact bytes; the later edit is
sent as its own revision event. The new regression test observed RED before this
fix, then passed together with the existing legacy creation replay test (2/2).

Do not report T05 complete or the native review as approved until the independent
verification and exact native review acknowledgement are observed.

### T01 proof and containment

Commit: `9106881b4c637dc296b030aad5b404533c0739e7`
(`feat(level-tests): persist immutable weekly exam snapshots`).
Changes: shared snapshot types/identity/hash; deterministic detached snapshots;
new generations store full variants with frozen thresholds/versions and UUID;
kind/unitID survive generation and flattening; listening titles preserved.
Existing generated legacy rows remain explicitly `legacy: true`: their missing
original grading metadata is not fabricated. By-ID routes, account sync, validation,
judging/settings/approval fixes, clients and scheduler were NOT implemented.

Root causes: flattening omitted kind/unitID; serving selected only pool/variants and
returned absent thresholds; neither composer exposed content/publication identity.
An additional observed hash regression included old identity metadata when hashing
JSON-restored content; tests reproduced it and the helper now excludes those fields.

| Verification command / cwd | Observed result |
|---|---|
| API: `./node_modules/.bin/tsx --test --test-concurrency=1 tests/level-test-snapshot.test.ts` — RED | 6 expected assertion failures before source implementation; absent identity/hash and empty thresholds. |
| Same focused suite — initial GREEN | 6 passed, 0 failed. |
| Same suite with hashing round-trip regressions — RED | 7 passed, 2 expected hash-identity failures before refinement. |
| API: `./node_modules/.bin/tsx --test --test-concurrency=1 tests/level-test-snapshot.test.ts tests/level-tests.test.ts tests/level-tests-llm.test.ts` — final GREEN/REFACTOR | 18 tests: 16 passed, 0 failed, 2 skipped (TEST_DATABASE_URL unavailable). |
| API: `npm run check` | Exit 0; TypeScript check passed. |
| API: `npm run build` | Exit 0; TypeScript build passed. |
| API: `npm test` | Exit 0; 473 tests: 114 passed, 0 failed, 359 skipped (356 explicitly require TEST_DATABASE_URL; 3 have no emitted reason). |
| Root: `python3 scripts/check_cross_platform.py` | Exit 0. |
| Root: `git diff --cached --check` before commit | Exit 0; 5 authored files, 352 additions + 26 deletions. |

T01 used task-required RED → GREEN → REFACTOR; project strict_tdd remains false
and its Standard configuration was not changed. Initial sandbox tsx IPC attempts
failed EPERM (not functional RED); authorized local escalation ran the real suite.
An initial test-double prompt assumption and an option-fixture assumption were
corrected before their final checks; no production workaround hid those failures.

Runtime harness: actual Fastify inject routes/current+generated hybrid, pinned 304,
real generation/composition/serving with fake external transport and SQL persistence.
PostgreSQL constraints/transactions are NOT proven by the SQL double; real DB tests
were skipped and need the applicable authorized environment later. No provider keys,
real LLM calls, containers or remote operations were used.
Dependency reuse: temporary linked-worktree node_modules symlink to the original
unchanged dependency tree; removed BEFORE commit. No installs or cache repair.

Rollback boundary: the 5 files in the T01 commit; no database migration introduced.
New stored variants contain additional JSON fields tolerated by former serving;
reverting serving loses frozen-grading guarantees until restored. Keep historical rows.
Original report SHA-256 remains the baseline above; no duplicate was created.

T01 native assessment: `gentle-ai review assess` on the committed-only candidate
returned `medium` (`executable_change`, 5 paths, 378 lines); medium review is deferred
to the slice close. An earlier selectorless STATUS returned a collect transition for
`external.select_intended_untracked` because this progress document is untracked; no
START or review transaction was created. The local CLI exposes no capture operation,
so do not infer review approval or follow that stale preflight. At slice close, run a
fresh native STATUS against the exact committed slice and follow its returned inputs.

### T02 proof and containment

Behavior commit: `13d65ebef8e1db626dbfb5971a910435a7eeb6c1`
(`fix(level-tests): enforce validation and publication policy`).
Measured: 11 files, 885 additions + 166 deletions = **1051 authored changed lines**;
376 are the newly tracked recovery document. Generated changes: 0.
Functional verification, correction, local commits, and the native review transaction are complete.
Changes: shared persisted effective settings; serialized transactional partial PUT;
always-on structural validation; complete strict verdicts; reject removal and final
minimum revalidation; exact source passages supplied to comprehension judges;
generation-level authenticated, validated, audited and idempotent draft approval.
Public selectors continue selecting approved rows only; no panel or historical API.

Root causes: generation did not invoke its structural validator or apply rejects;
strict judge failures were swallowed and missing verdicts accepted; admin reads and
partial writes used environment defaults rather than saved effective settings, and
strict-to-flag was ignored. Approval had no admin mutation. A hash alone also cannot
prove that section/stimulus matches the source pool, so approval checks both.
Optional blank environment URL/model use provider defaults; provider switching must
not implicitly forward a saved or environment credential.

| Verification command / cwd | Observed result |
|---|---|
| API: `./node_modules/.bin/tsx --test --test-concurrency=1 tests/level-tests-policy.test.ts` — initial RED | 17 tests: 16 expected failures, 1 passed before behavior changes. |
| Same focused suite — initial GREEN | 17 passed, 0 failed. |
| Same suite with publication refinements — RED | 20 tests: 17 passed, 3 failed before pool/section/stimulus/variant guards. |
| Same suite with passage-context/blank-env regressions — RED | 22 tests: 20 passed, 2 expected failures. |
| Same suite with cross-provider credential regression — RED | 25 tests: 24 passed, 1 expected failure before guard. |
| API: `./node_modules/.bin/tsx --test --test-concurrency=1 tests/level-tests-policy.test.ts tests/level-test-snapshot.test.ts tests/level-tests.test.ts tests/level-tests-llm.test.ts` — final GREEN/REFACTOR | 43 tests: 41 passed, 0 failed, 2 skipped (TEST_DATABASE_URL unavailable). |
| API: `npm run check` | Exit 0; TypeScript check passed after correcting inferred passage-map key type. |
| API: `npm run build` | Exit 0; TypeScript build passed. |
| API: `npm test` | Exit 0; 498 tests: 139 passed, 0 failed, 359 skipped (356 explicitly require TEST_DATABASE_URL; 3 have no emitted reason). |
| Root: `git diff --cached --check` before commit | Exit 0; exact 11-file candidate staged and measured. |
| API policy regression after review correction | Docker focused suite: 26 passed, 0 failed; `npm run check`: exit 0; full API suite: 499 tests, 140 passed, 0 failed, 359 skipped (DB unavailable). |
| Independent R3-001 verification | Docker scratch copy: 26 policy tests passed, API check exit 0, same-provider blank-key HTTP probe preserved ciphertext, provider switch blank-key probes rejected without state changes. |
| Native review | Four lenses found R3-001; bounded correction commit `cc5ce58724fc09d427cc7d2a7d11f3bd1405a29f`; targeted validator approved; exact acknowledgement burned lineage `review-2f5bac97bd94bf8c`. |

Runtime boundary: actual Fastify inject exercises authenticated settings save/reload,
CSRF Origin rejection, unauthenticated approval rejection, private draft/public
approval, replay without duplicate audit, incomplete strict draft and snapshot
corruption rejection. Real generator/settings/approval services use fake external
transport and SQL only. Strict complete flags remain advisory by parent-confirmed
policy; off bypasses semantic judging only, never structure. No real LLM/keys used.

PostgreSQL integration remains unavailable: the SQL double does NOT prove locks,
transaction isolation, constraints or rollback/audit atomicity on a real database.
No containers, database setup, network calls or installations were performed.
Earlier real TypeScript check failed on map-key inference; fixed and rerun successfully.
A masked-key test expectation was corrected to the existing maskKey contract rather
than changing production masking. Existing generator test fixtures were made valid
and their HTTP source asserted so deterministic fallback cannot mask failed generation.

Rollback boundary: T02 settings/validator/generator/admin configuration/approval route
and service, policy tests, fixture updates, and publication docs as one behavior unit.
No database migration. Preserve approved/retired generation content and admin audits;
code rollback does not authorize deleting or silently unapproving historical rows.
Original requirement report remains untouched with its baseline SHA-256.

RDD: parent assessment pending for the T02 committed behavior candidate; no review
approval, receipt or new boundary is inferred. Keep T01's medium/deferred status and
branch-point reviewed boundary. The coherent unit also initially tracks this recovery
document; disclose that authored documentation in its count rather than excluding it.
The approximately 400-line task heuristic is advisory: validation/publication/settings
and their boundary tests are kept together instead of omitting proof for size savings.

Dependency reuse: the temporary node_modules symlink resolved to the unchanged
original checkout dependency tree and was removed before commit; no installation.
The committed recovery file includes all functional proof; this post-commit SHA/status
update remains uncommitted by parent instruction until the behavior candidate is
assessed. Do not create a second evidence-only commit before that assessment.

### T03 Android Docker verification (2026-10-14)

Work-unit commit: `8ff9635fc11080a48f5ba0fd2d43c834d62bfb90` (`feat(exams): persist local exam attempts`).

Environment: `eclipse-temurin:17-jdk-jammy@sha256:43f4431cc895d37ceb115e2e1f160545ec45caee9c1cb4246fc6bb879d58aeda`, AGP 8.10.1,
Gradle Wrapper 8.11.1, Android platform 36, Build Tools 35.0.0, NDK 27.0.12077973,
CMake 3.22.1. Android CLI archive revision 15859902 is SHA-256 checked in the
Dockerfile. Gradle cache persists in Docker volume `looro-gradle-cache`.

| Command | Observed result |
|---|---|
| `apps/android/docker/test-unit.sh` | Exit 0; `:app:testDebugUnitTest`: 672 passed, 0 failed/skipped; `:core:engine-local:testDebugUnitTest`: 11 passed, 0 failed/skipped. Full JVM suite completed in 53 seconds after cache warm-up. |
| Focused T03 app suites (`LearningDbTest`, `BackupManagerTest`, `LearningCutoverFlowTest`, `LearningDbStoreTest`, `ArchiveMapperTest`) in Docker | Exit 0; all selected classes passed. |
| Root Python/schema checks in Docker | 136 Python tests passed; schema checker and generated-source freshness passed (v3, 16 tables, 33 statements). |
| `bash -n apps/android/docker/test-unit.sh`; `git diff --check` | Exit 0. |

Independent verification in a fresh Docker scratch copy also passed: the full runner executed all 683 Android JVM tests (672 app + 11 engine-local), Python/schema checks passed, and `git diff --check` was clean. Parent spot-check reran both Gradle tasks in the pinned image: `BUILD SUCCESSFUL` (43 tasks, 42 up-to-date). The warning below remains non-fatal.

The initial Android run exposed 15 failures: JVM Android JSON stubs returned null for
chained `JSONObject.put`, the v1 fixture accidentally included v3 tables, two row
count assertions still expected ten tables, and a trigger-name assertion differed
from the schema. These were corrected; the focused suites and subsequent complete
Android JVM suite passed. A non-fatal SDK XML v4 compatibility warning from the
current command-line tools remains. Per the explicit L9 scope decision, Swift verification is excluded from T03
closure while the Swift implementation is retained. Swift behavior remains unverified;
this is not evidence of iOS correctness. Android/shared-schema acceptance is complete.

**Next step:** Proceed to T04. Swift verification remains deferred and is not claimed.
No push, PR, deployment, or merge to `main`.

## Log

- L1 (2026-10-13), original request: “implementa los cambios que se exponene en el documento informe-plan-pendientes-2026-10-13.md”.
- L2 (2026-10-13), user constraint: “todos los comando debes lanzarlos via docker. continua con lo que sigue con 1. revisar este cambio”.
- L3 (2026-10-13), user branch constraint: “feature-branch-chain pero despues hay que volver a la misma rama sobre la que estabamos y mergear los cambios sobre esta, no sobre main”.
- L4 (2026-10-13), user authorized review-provider transmission: “te doy autorizacion para hacerlo”. The targeted native validator approved the corrected candidate and exact acknowledgement burned its authority; this is review evidence, not delivery authorization.
- L5 (2026-10-13), parent resumes T03 from the approved report. Mapping confirmed current Android/iOS schema is v2; migrations are generated from `shared/db/schema.sql`; both platform transaction APIs use `BEGIN IMMEDIATE` and do not allow nested transactions. T03 must cover schema, both stores, backups, restore, erase, and legacy summary compatibility before completion.
- L6 (2026-10-13), route: direct inline implementation after one read-only explorer mapped the cross-platform data surfaces; T03 is one coordinated persistence behavior with shared schema and no disjoint write units. It is high risk because it adds persisted records, a schema migration, account-sensitive local data, and backup/erase behavior; use an independent verifier after T03's own checks.

- L7 (2026-10-13), T03 implementation added schema v3, immutable snapshots/IDs, attempt/answer/result/outbox tables, Android/iOS DAOs and writer/delete/backup integration. Independent review caught v1/v2 backups rejected by the v3 inventory; fixed with version-aware ten/sixteen-table validation and v2 regressions; scoped recheck confirmed fix. Python suite 136/136 and schema/export checks pass. Native Kotlin/Swift suites unavailable in local Docker images; keep T03 open pending those checks. Engram full mirror pending synchronization.
- L8 (2026-10-14), user request verbatim: “Te autorizo expresamente a descargar imágenes Docker oficiales y las dependencias necesarias para crear un entorno reproducible de ejecución de tests Kotlin/Android en Linux. Condiciones: Todos los comandos de compilación y tests deben ejecutarse dentro de Docker. Prioriza una imagen oficial con JDK 17 o 21, según los requisitos reales del proyecto. Si los tests requieren Android SDK, crea un Dockerfile específico con las herramientas y versiones necesarias. Utiliza el Gradle Wrapper del repositorio. No instales JDK, Gradle ni Android SDK directamente en el host. Prioriza tests unitarios JVM de Android que no necesiten emulador. Mantén cachés persistentes de Gradle para acelerar las siguientes ejecuciones. Documenta cómo reproducir las pruebas mediante Docker. Ejecuta las suites Android de T03 y registra los resultados reales. Mantén T03 abierta si alguna prueba obligatoria sigue sin poder ejecutarse. Puedes descargar las imágenes y dependencias necesarias, pero no hagas commit ni merge hasta completar las verificaciones y comunicarme los resultados. No hace falta resolver Swift en este paso. Prioriza desbloquear Kotlin/Android.” Docker runner creado; Android suites pasan (672 app + 11 engine-local); T03 sigue abierta porque Swift Core es obligatoria y no se ejecutó. No se hizo commit/merge. |

- L9 (2026-10-14), user scope decision verbatim: “si conservalos y continua hasta terminar las siguientes fases”. Interpreted with the preceding instruction (“descartamos esa parte”) as retain current Swift code but exclude Swift test execution from T03 acceptance. Swift remains explicitly unverified; do not treat this as iOS validation. Continue the already authorized feature plan from T04.
- L10 (2026-10-14), user instruction verbatim: “continua y asigna lo que necesitas”. Runtime rejected configured odd-explorer model `gpt-6.1-luna`; user authorized assigning the closest available model, so bounded read-only mapping used `gpt-6-luna` at high effort. T04 scope ruling separates initial private persistence/retrieval from T05 replay/CAS/conflict sync.
- L11 (2026-10-14), independent T04 verification found public ID retrieval included retired generations and upload validation accepted a choice without options. Added regression assertions, observed both fail (2/4 focused tests), restricted public retrieval to approved rows and enforced at least two choice options with accepted answers among them; focused PostgreSQL suite passed 4/4 and API TypeScript build passed in Docker. Full API suite remains partially blocked by test-image asset packaging and an unrelated hosted-paid test hang; no clean full-suite claim.
- L12 (2026-10-15), user selected “revisar este cambio”. Gentle AI completed the exact high-risk three-lens review for T04 target `sha256:746d15f0290842aa80119da1e41109e1d2bb7bcdcfba65af11b2095a4a2b2cfb`; final capture approved and exact acknowledgement burned authority. One non-blocking `R3-001` WARNING at `services/api/src/level-test-attempts.ts:119` remains follow-up; no correction required.
- L13 (2026-10-15), user request verbatim: “resuelve los problemas encontrados”. Fixed the Android update-count contract, immutable fork creation retry payload, and revision ordering independent of wall-clock time. RED was observed for each regression, focused Android tests passed 3/3, full Android JVM app suite passed 684/684, and independent fresh-scratch verification passed 27/27 under Android's `-1` update-count semantics. No commit/merge. The prior native review remains blocked by candidate context budget, with no receipt.
- L14 (2026-10-16), user request verbatim: “resuelvelo y vuelve a verificar despues”. Fixed the legacy lost-first-ACK plus offline-edit case by persisting the reconstructed initial attempt transactionally before sending; retries replay the frozen snapshot and transmit later edits as revision events. Observed the new regression fail before the fix, then focused tests passed 2/2; full Android JVM app suite passed 686/686; independent fresh-scratch Docker verification passed. Normal commit `8e83b9faa5f9423338aa5e8ed5b1a2ee3e2ec007` was created without rewriting history, pushing, or merging. Native RDD receipt remains pending.
- L15 (2026-10-16), user request verbatim: “completa lo que falta excepto el punto 3”. Re-ran the full Android JVM suite with `--rerun-tasks`: BUILD SUCCESSFUL. Native assessment of `b3f1e5b..8e83b9f` returned medium/under-budget, then explicit preflight STATUS produced a START invocation. The exact provider-issued START failed before authority creation with `invalid_request`, `mutation_outcome: not_started`, cause `unexpected review start argument "commit"`; current `review start --help` documents `--committed-only` as a valueless flag while STATUS emitted `--committed-only=true`. Fresh selectorless STATUS still reoffered that invocation. No native authority or receipt was created; stop pending runtime correction. Swift remains excluded as requested.
