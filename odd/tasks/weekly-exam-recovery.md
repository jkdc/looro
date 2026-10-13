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
| Current worker authorization | T02 only: backend validation/judge/settings/publication, tests/documentation, local work-unit commit and progress mirror; T03 and later tasks need parent dispatch |

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

Commands below are inventory, NOT execution evidence. Run only commands applicable
to the dispatched work unit and available authorized environment, in stated cwd.

| Area / cwd | Exact commands from configuration/report |
|---|---|
| Repository root | `make check`; `python3 -m unittest discover -s scripts/tests -t .` |
| Root additional targets | `make test-api`; `make android-build`; `make ios-test` |
| `services/api` | `npm run check`; `npm run build`; `npm test` |
| `apps/android` JVM | `./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest` |
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
- [ ] **T02 — Enforce structural validation, judge policy, settings, publication.**
  Phase P0; report §4, 9/T02. Files: level-tests validation/generator/config/admin API.
  Prove rejected items excluded, strict judge failure/incompleteness/minimum shortfall
  blocks publication, flag advisory, drafts private until approved, faithful save/reload
  and partial settings updates including strict → flag → off.
  Checks: API typecheck/build/unit+applicable integration.
- [ ] **T03 — Add durable local snapshots, attempts, answers, result, and outbox.**
  Phase P1; report §3, 9/T03. Files: shared SQL/exporters, both platform DAOs/writers,
  backup/restore/erase paths. Prove atomic start before first question, answer/position
  rollback, immutable result and idempotent completion; legacy stays summary-only.
  Checks: root schema/contracts/Python, JVM database tests, Swift database gate.
- [ ] **T04 — Add owner-scoped cloud attempts and immutable retrieval.**
  Phase P2; report §4, 9/T04. Files: proposed exam-attempts API/migrations/tests.
  Prove account authorization, bounded pagination/bytes, no dangling snapshot refs,
  validated private bundled upload, published/private separation and retired retention.
  Checks: API typecheck/build/unit+PostgreSQL integration when available.
- [ ] **T05 — Implement durable sync, ACK idempotence, CAS, and conflicts.**
  Phase P2; report §§1, 4–6, 9/T05. Files: dedicated exam sync clients/outbox and API.
  Prove duplicate event retry, lost ACK, stale ACK with new edits, concurrent devices,
  immutable submitted answers/results, explicit takeover/fork resolution, owner isolation.
  Checks: API and platform state-machine tests with fake transport; DB transaction tests.
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
| S1 | T01 commit 9106881b4c637dc296b030aad5b404533c0739e7 | 378 (352 additions + 26 deletions; no generated changes) | Functional checks recorded; risk medium; native review deferred to slice close |

No final child-branch or PR boundaries are fabricated now. Parent records actual
coherent boundaries and local work-unit commit IDs as implementation progresses.

## Progress, verification, and recovery handoff

- Completed implementation tasks: **1/14** (T01); **13/14 remain**.
- Latest work-unit commit: `9106881b4c637dc296b030aad5b404533c0739e7`.
- Measured authored total: **378** changed lines; initial task-document preparation
  is not included in the source work-unit count.
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

Behavior commit: pending local work-unit commit after the checks below; do not close
T02 until its immutable commit ID is recorded. Functional verification is complete.
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
| Root: `git diff --check` before documentation update | Exit 0. Final staged whitespace check required before commit. |

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

**Next step:** finish the T02 local commit, record its SHA/count and mirror this full
file; then stop for parent risk assessment/reconciliation. T03 is the next implementation
task but is not authorized for this worker. No merge, push, PR or deployment.
