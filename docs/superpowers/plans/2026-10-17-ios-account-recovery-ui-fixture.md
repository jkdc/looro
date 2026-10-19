# iOS authenticated exam-recovery UI fixture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Do not commit: the user deferred all commits until final verification/report.

**Goal:** Exercise T12/T13 same-account restore, account isolation, and explicit conflict handling through real iOS UI tests using a deterministic synthetic account fixture.

**Architecture:** Keep the current bounded URLSession transport as the production default and inject a request-transport seam into `ManagedAccountClient`. An explicit DEBUG simulator UI-test argument enables a synthetic account session and strict fake routes; normal preview and production authentication remain unchanged.

**Tech Stack:** Swift 5, SwiftUI, Foundation URLSession, XCTest/XCUITest, existing `LearningCore` snapshot factory and SQLite DAO.

**Spec:** `docs/superpowers/specs/2026-10-17-ios-account-recovery-ui-fixture-design.md`; behavior source `odd/tasks/weekly-exam-recovery.md` T12/T13 and `openspec/changes/t09-ios-exam-recovery/`.

## Global Constraints

- “Keep the seam DEBUG/simulator-only and opt-in by explicit UI-test launch arguments.”
- “Never use a real account or bearer token.”
- “Normal preview, release builds, and production transport behavior remain unchanged.”
- “Do not run Swift tests, parsers, typechecks, builds, or Xcode during T01–T14 implementation.”
- All repository commands, source reads/writes, and non-Swift verification run inside Docker; cached image only, `--pull=never --network none`.
- Preserve all unrelated dirty T09 work. No commit, merge, push, PR, dependency download, or project regeneration.

## Review Focus

- Preview launch without the fixture must continue to suppress account loading and network operations; Task 1 covers this as a UI regression.
- Invalid fixture names/accounts and unknown routes must fail closed without falling through to real HTTP; Task 1 pins invalid arguments and Task 2 pins unknown route/fixture requests.
- A response with a foreign attempt ID/account must not enter the local store; Task 2 asserts the foreign row never appears.
- Account change during an async restore or sync must not import/ACK under the new session; Task 2 uses a deliberately held response then changes account before release.
- A stale event's 409 must persist both branches and require a visible explicit fork/takeover action.

---

### Task 1: Add an isolated synthetic account and injectable transport

**Files:**
- Modify: `apps/ios/App/ManagedAccountClient.swift`
- Modify: `apps/ios/App/ManagedAccountStore.swift`
- Test: `apps/ios/UITests/AppUITests.swift`

**Interfaces:**
- `ManagedAccountRequestTransport.send(_ request: URLRequest, limit: Int) async throws -> (Data, HTTPURLResponse)` is the request boundary; `ManagedAccountHTTP` remains its production implementation.
- `ManagedAccountClient.init(configuration:transport:)` defaults to `ManagedAccountHTTP` so all current callers retain existing behavior.
- `ManagedAccountUIFixture` parses only `--exam-recovery-ui-test=<known-scenario>` plus `--exam-recovery-account=<A|B>` in DEBUG simulator builds and owns a synthetic valid session and strict fake transport.

- [ ] **Step 1: Write UI regression for default preview isolation.** Add an assertion that `--preview` alone still shows no account-only restore/sync controls and creates no fixture-backed session.
- [ ] **Step 2: Write UI regressions for valid and invalid fixture arguments.** A named restore fixture exposes account-gated controls; an unknown scenario/account exposes no authenticated actions and must not trigger a real request.
- [ ] **Step 3: Implement the transport interface and keep URLSession as the default.** Route every ordinary client call through the interface; preserve URLSession request bounds, headers, redirect behavior, and error mapping.
- [ ] **Step 4: Add the opt-in simulator fixture and synthetic session.** Use an invalid HTTPS hostname that the fake transport handles locally, a fixed non-secret synthetic token accepted by the existing session validator, and no Keychain read/write. Unknown arguments/routes return a deterministic failure; they never use live transport.
- [ ] **Step 5: Verify source scope only.** Run `git diff --check` in Docker and inspect the exact initialization/guard branches. Swift execution remains deferred and this task remains unverified until final native checks.

### Task 2: Prove restore and account isolation in the real UI

**Files:**
- Modify: `apps/ios/App/ManagedAccountClient.swift`
- Modify: `apps/ios/App/ManagedAccountStore.swift`
- Modify: `apps/ios/App/CoursesViews.swift`
- Test: `apps/ios/UITests/AppUITests.swift`

**Interfaces:** Consume Task 1 transport/session. Fixture routes mirror `GET /v1/level-test-attempts?limit=10[&cursor=...]` → `{attempts, nextCursor}` and `GET /v1/level-test-attempts/:attemptId` → `{attempt, snapshot}`. Snapshot uses `ExamSnapshotFactory.bundled` over a fixed existing `LevelTestsRegistry` level and fixed week; account-specific data is selected by synthetic bearer identity.

- [ ] **Step 1: Write restore regressions.** Launch a clean in-memory store, tap `weekly-exam-restore`, assert page-one and page-two attempts appear, reopen the completed attempt, and assert its frozen prompt and original result remain visible. A second fixture returns a foreign attempt ID and asserts that row is not imported.
- [ ] **Step 2: Write account-switch regressions.** Create an account-A pending attempt, activate the fixture-only A→B switch, assert the A history row disappears, tap sync, and assert the fixture reports no A-owned event under B. Hold an A restore response, switch to B, release it, and assert the attempt is not imported or ACKed under B.
- [ ] **Step 3: Implement strict list/detail pages.** The fake accepts only expected paths, cursors, bearer identity, and attempt IDs; serves a two-page restore and held response; and rejects foreign records and unknown routes without falling through to HTTP.
- [ ] **Step 4: Add the fixture-only account switch action.** Expose it only when the dedicated DEBUG simulator fixture is active; it mutates the existing `ManagedAccountStore.session` through an internal method and reuses the existing `.onChange` history clearing behavior.
- [ ] **Step 5: Verify source scope only.** Run Docker `git diff --check`; confirm release/ordinary preview code paths do not construct the fixture. Do not execute Swift.

### Task 3: Prove conflict visibility and explicit resolution

**Files:**
- Modify: `apps/ios/App/ManagedAccountClient.swift`
- Modify: `apps/ios/App/ManagedAccountStore.swift`
- Test: `apps/ios/UITests/AppUITests.swift`

**Interfaces:** Fixture simulates a successful initial attempt creation followed by `POST /v1/level-test-attempts/events` returning HTTP 409 `{error:"exam_attempt_conflict", conflict:{attempt:<server-confirmed-attempt>}}`. Existing UI identifiers are `exam-conflict-fork-<attemptID>` and `exam-conflict-takeover-<attemptID>`.

- [ ] **Step 1: Write fork regression.** Trigger a stale event, assert “sync conflict needs review” and “Keep both versions”, select fork, then assert the source and fork remain distinct and the fake receives the fork under the same account.
- [ ] **Step 2: Write takeover regression.** Trigger the same conflict scenario with a separate fixture state, select “Use account version”, and assert server answers/revision replace only the selected account-owned attempt.
- [ ] **Step 3: Implement stateful strict conflict replies.** Track per-fixture attempt revisions, reject unexpected event order, preserve 409 shape/status through `ManagedAccountClient`, and never auto-merge.
- [ ] **Step 4: Verify source scope only.** Docker `git diff --check`; manually inspect request identity, conflict persistence, and explicit action paths. Keep T12/T13 and Swift proof pending until their full verification batch.

## Execution notes

- Execute inline using `superpowers:executing-plans`; tasks share one fixture/transport boundary and are not independent writer units.
- User explicitly chose autonomous continuation; no intermediate approval is required. User also explicitly forbids Swift execution until all implementation phases complete and forbids commits/merges until verification/report.
- Since Swift RED/GREEN cannot be observed now, write each UI test before its fixture behavior, record RED/GREEN as deferred (not passed), and leave task checkboxes open until the final native run.


### Progress update — 2026-10-17

- Added an account-switch UI regression first, then an explicit DEBUG-simulator-only switch control that calls the existing store method. Also made the fixture transport type usable across source files and simplified scenario allowlist validation.
- Docker `git diff --check` passes for the current worktree. No Swift test/build/parser/typecheck/Xcode command was run, by user instruction.
- The fixture now serves a deterministic two-page completed-attempt restore for account A from a frozen bundled EN/A1 week-202641 snapshot. Conflict scenarios still have no responses; T12/T13 and this plan remain open.
- Risk: item 2 (account identity isolation) and item 4 (async account-switch ordering).

- Independent verification caught an undefined non-simulator unavailable-fixture helper, a switch action that always selected B, and a Google OAuth consumer still requiring the concrete HTTP type; all three were corrected. A scoped recheck is pending.


### Progress update — deterministic restore — 2026-10-17

- Added two fixture list pages with completed attempt details, stored answers, original result, and one canonical frozen bundled snapshot. The UI regression checks both restored rows, historical week identity, and the persisted original score.
- The fake rejects unknown host, path, method, account, query, and cursor; it does not use URLSession. Docker `git diff --check` passed; Swift execution remains deferred.
- T12 is partial: retired generated content, exact item/order/passage assertions, and native UI execution remain pending. T13 conflict and offline-loss acceptance remain pending.
- Risk: item 1 (restored persistent data) and item 2 (account identity and isolation).


### Progress update — conflict fixtures — 2026-10-17

- Added isolated conflict-fork and conflict-takeover scenarios: a local attempt creation receives a deterministic acceptance, then a progress event receives the API-shaped 409 with the original server attempt. UI tests select fork or takeover explicitly.
- The fake is still strictly injected; it accepts only the invalid fixture host, account A, expected scenario and endpoint shape. Unknown operations throw rather than fall through to live HTTP.
- Docker `git diff --check` is the only check; Swift execution remains deferred. T09/T13 remain open until native UI proof and offline-loss/account isolation checks complete.
- Risk: item 1 (persisted conflict state), item 2 (account identity), and item 4 (event ordering/ACK).


### Progress update — conflict fixture contract correction — 2026-10-17

- Independent verification found the first conflict fake returned 409 even when the local base revision equaled the remote revision, and accepted underspecified POST bodies. Updated the simulator-only fake to reject all query parameters, validate exact attempt/event shapes and revision fields, require a matching snapshot exam ID, and model an independent remote revision advance before returning a stale-write 409.
- Docker `git diff --check` passed. Swift execution remains deferred by L19. A scoped independent recheck is pending; restore-content assertions, branch/upload assertions, held-response account-race coverage, and final native proof remain open.
- Risk: item 1 (remote/local persisted revision contract), item 2 (account-scoped identity), and item 4 (event ordering and conflict handling).


### Recheck correction — 2026-10-17

- The scoped verifier caught two regressions in the preceding hardening: query validation was accidentally global (blocking restore `?limit=10`) and the conflict fixture expected a nested exam envelope while the store sends a flat snapshot. Both predicates were corrected to be POST-specific and to match the actual top-level `examId`; attempt/event timestamps now parse as ISO-8601 dates.
- Parent source readback and Docker `git diff --check` pass. No subsequent independent recheck was run; Swift remains deferred. Per bounded correction policy, do not claim fixture acceptance or close T09/T12/T13.
