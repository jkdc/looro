# Mobile Review Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task by task. Preserve the existing active branch and pre-existing worktree changes; no worktrees.

**Goal:** Implement and verify R01–R19 from the mobile implementation review without weakening account isolation or local-first ownership.
**Architecture:** Reuse the existing owner/generation fencing, local SQLite stores and versioned progress route. Quarantine unattributed legacy fallback, make continuation truly durable and surface failures; repair audio and iOS interaction around explicit state and cancellation.
**Tech Stack:** Kotlin/Compose/SQLite/Gradle, Swift/SwiftUI/GRDB/Xcode, TypeScript/Fastify/PostgreSQL.
**Spec:** `openspec/changes/mobile-review-followup/{proposal,design,tasks}.md` and `specs/mobile-review-followup/spec.md`; source scenarios and evidence: `informe-revision-implementacion-app-movil-2026-10-27.md`.

## Global Constraints

- Work on `feature/learning-platform`; do not create worktrees, switch branches, push or merge.
- Preserve pre-existing T10 voice changes and user untracked files; stage exact task hunks only.
- Active owner always exists; guest data stays isolated and is not merged; timestamps originate on the mobile device.
- Download backend progress only during eligible first-device bootstrap; preserve explicit recovery and do not infer ambiguous legacy timestamp provenance.
- Use deterministic test-first RED → GREEN for each behavior where a runner exists. Do not claim Swift/iOS or device execution on Linux without tools.
- Android UI text must include English and Spanish resources; source identifiers/comments default to English; do not edit generated files.

## Review Focus

1. Failed migration with existing legacy backup: A→B→restart cannot read or mutate another owner's data (F01).
2. Exact v1 response and malformed/other HTTP errors: no accidental fallback or batch loss (F02–F03).
3. Legacy cursor with prefix identifiers and interruption after ack/page: no skipped or duplicated identities (F05–F06).
4. Stale account, data import, failed Keychain removal and restart: no cross-owner writes or rejected bearer reuse (F04,F13–F14).
5. Silence, overlong voice, early greeting, cancellation and shared audio lease: preserve input and avoid phantom turns or disrupting other audio (F09–F10,F15).

## Tasks

### F01 — Quarantine ownerless fallback (R01)
**Files:** Android `data/LearningDbStore.kt`, `LearningRepository.kt`, `AppViewModel.kt`, matching `LearningDbStoreTest.kt`/repository instrumentation; iOS `App/Storage.swift`, `App/AppRoot.swift`, matching AppTests. **Interfaces:** preserve existing backup bytes; an unavailable/quarantined state must not call shared-archive load/save. Add a failing A→B→restart regression checking both visibility and exact bytes, including rollback and non-destructive retry; run Android focused tests; implement the smallest fail-closed seam. Author iOS equivalents and explicitly record inability to observe RED/GREEN without Xcode. Independent review required for stored data/account boundaries.

### F02 — Actual Android v1 contract (R02)
**Files:** Android `network/ProgressClient.kt`, `AppViewModel.kt` legacy parsing, `network/ProgressClientTest.kt`, relevant database/transport tests. **Interfaces:** decode v1 `firstAt`/`lastAt`, preserving both in rollups; exact 503 alone allows downgrade. Add failing MockWebServer test for real v1 JSON plus page→database→upload; verify malformed, empty and unrelated errors. Implement and run focused/full Android tests.

### F03 — Exclude invalid contributions (R03)
**Files:** Android `data/LearningDbDao.kt`, `core/ProgressEngine.kt`, `AppViewModel.kt`, DB/engine/transport tests. **Interfaces:** validate each local record before aggregate so valid same-ID contributions survive; do not mutate excluded data. Test actual DB→snapshot→transport with a valid and invalid mark, then observe RED/GREEN and suite.

### F04 — Adoption and bootstrap policy (R04,R07,R18)
**Files:** Android `MainActivity.kt`, `AppViewModel.kt`, `core/ProgressSyncOperation.kt`, ownership/state DAO and writer; iOS `Storage.swift`, `RootView.swift`, `ConversationCoordinator.swift`, corresponding integration tests. **Interfaces:** owner-scoped activation/load precedes bootstrap; durable eligibility distinguishes generated data from default settings, and resumes an in-progress pass. Test guest→member, A→B while suspended, each data category, default vs changed preferences, restart/import and recovery. Avoid schema change without updating shared schema/exporters and migration tests.

### F05 — Tuple-ordered upload continuation (R05)
**Files:** Android `AppViewModel.kt` and tests; iOS `ConversationCoordinator.swift`, `ProgressClient.swift` and AppTests. **Interfaces:** parse legacy `kind|item|language`, compare tuple fields identically to sort; invalid cursor restarts an idempotent pass; v1 does not advance v2 cursor. Test 201+ prefix identities `a`/`a-1`, ack→restart→exact server set, malformed cursor and fallback. Observe Android RED/GREEN; iOS execution pending macOS.

### F06 — Transactional page continuation (R06)
**Files:** Android `network/ProgressClient.kt`, `core/ProgressSyncOperation.kt`, `AppViewModel.kt`, `data/LearningDbWriter.kt`, DB/client integration tests. **Interfaces:** each validated page and its next cursor commit atomically before the next request, fenced by active operation. Test real path with 1,001 records, second-page failure, reopened DB, next request cursor, exact final set, stale owner and malformed page. Observe RED/GREEN and suite.

### F07 — Sync feedback (R08)
**Files:** Android sync runner/ViewModel, existing UI and `values/strings.xml` + `values-es/strings.xml`; iOS `ConversationCoordinator.swift`, appropriate UI, tests. **Interfaces:** typed owner-scoped pending/failure/success state and explicit retry, no secrets; stale-owner cancellation must not alert new owner. Test HTTP/network, malformed response, navigation/account switch, retry and restart where applicable.

### F08 — iOS compile/test defects (R09,R10)
**Files:** `apps/ios/App/PronunciationCheckController.swift`, `AppTests/TopicSearchCapabilityTests.swift`, `AppTests/ProgressClientTests.swift`. Verify missing import, instance member access and lexicographic fixture; correct each and add integration coverage for persisted 201/1001 continuation. Record unavailable compiler as a gap; run `swift test --package-path apps/ios`, `make ios-build`, `make ios-test` when macOS/Xcode is available.

### F09 — Bounded manual speech (R11,R12)
**Files:** Android `core/voice/LocalVoiceTransport.kt`, `LocalVoiceTransportTest.kt`, event consumer/UI and bilingual resources. **Interfaces:** separate silent wait from active capture; never auto-submit a manual turn; preserve utterance onset through STT and signal bounded overflow nonterminally. Tests: long silence→speech over limit; >18s with distinguishable prefix; overflow/noise; exact sample and notice assertions. Preserve uncommitted T10 hunks and run focused/full suites.

### F10 — Greeting order and T10 race (R13)
**Files:** Android `LocalVoiceTransport.kt`, `AppViewModel.kt`, `LocalVoiceTransportTest.kt`. **Interfaces:** explicit instruction-ready → greeting → first turn barrier, including cancellation/failure; do not replace existing handshake with `delay`. Block greeting provider in test, inject immediate voice, assert request/event order and exact context; replace pre-classification test latch with a post-classification barrier. Run focused/full suites.

### F11 — Manual mute during work (R14)
**Files:** Android `ui/TalkScreen.kt`, `androidTest/.../ManualMuteUiTest.kt`, related tests. Test active voice with blocked typed provider and `working=true`: mute remains actionable, state/semantics update, input stops but tutor playback continues. Run unit/build/UI APK checks; connected UI requires a device.

### F12 — iOS typed editor (R15)
**Files:** `apps/ios/App/RootView.swift`, `ConversationCoordinator.swift`, `AppTests/TypedReplyTests.swift` and UI tests. Define edit-after-send semantics without losing text; show in-flight on reopen and preserve no-duplicate guarantees. Test A→edit B→success/failure, close→reopen before completion, retry/focus and late responses.

### F13 — Rejected credential invalidation (R16)
**Files:** iOS `ManagedAccountStore.swift`, credential consumers including `ConversationCoordinator.swift` and `TokensCommerceModel.swift`, `AppTests/SessionAdoptionTests.swift`. Introduce shared fail-closed invalidation that survives restart without storing new secrets; failed Keychain delete must not allow any consumer to reread rejected credentials. Test delete failure, notification, restart, cleanup retry and subsequent valid sign-in; independently review security semantics.

### F14 — Pronunciation identity fence (R17)
**Files:** iOS `PronunciationCheckController.swift`, `Storage.swift`, `CoursesViews.swift`, AppTests. Capture and revalidate owner plus data generation, cancel on identity/import changes and reject stale writes/callbacks. Controlled tests A→B, A→B→A and import during recognition, exact B DB and callback unchanged, followed by successful B attempt.

### F15 — Shared audio-session ownership (R19)
**Files:** iOS `PronunciationCheckController.swift`, `LiveTransport.swift`, lease dependency and AppTests. First reproduce or classify shared activation/restore ownership; implement scoped arbitration if needed, never globally deactivate another owner's audio. Test overlapping lease/cancellation, background/interruption and restore; physical audio validation remains pending without device.

### F16 — Feature review and final verification (R01–R19)
Reconcile every report finding with executed tests and current source. Run `make check`, `make test-api`, Android full unit/lint/build/UI APK, connected Android UI, Swift Core and iOS App/UI when available. Record precise failures, skips and device limitations in `openspec/changes/mobile-review-followup/verify-report.md`; perform fresh whole-feature review. Never call unavailable proof passing. Commit reviewable work units with conventional commits, keeping tests/docs together and excluding unrelated existing changes.
