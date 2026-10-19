# T07 Android Exam Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Connect Android weekly exams to T03 durable attempts for autosave, resume, review history, and local restore by attempt ID.

**Architecture:** Add a versioned snapshot round-trip codec and repository over existing exam DAOs. AppViewModel owns ordered IO writes and typed attempt state; Compose renders durable save status and routes active/history/review without recomposing old attempts.

**Tech Stack:** Kotlin, Jetpack Compose, existing SQLite DAO/schema, Gradle Wrapper, approved Docker Android image.

**Spec:** openspec/changes/t07-android-exam-recovery/ and docs/superpowers/specs/2026-10-16-t07-android-exam-recovery-design.md.

## Global Constraints

- Reuse ExamHistoryDao transactions; do not add a parallel attempt store.
- Capture content before questions; resume by attemptId, never current week or level.
- Preserve remote canonical snapshot/hash identity; bundled identity must match API hashing.
- Keep UI responses after failed writes and never render uncommitted state as durable.
- All build/test/Git commands run inside Docker; no host JDK, Gradle, Android SDK, Swift or Gentle AI.

## Review Focus

- Process death during an answer write: retain input and expose retry, never claim it was saved.
- Late catalog refresh/week rollover: current attempt stays bound to its snapshot.
- Repeated submit/resume: one immutable result and one completion.
- Old generated/bundled content: review must not depend on current catalogue.
- Existing summary-only preferences: preserve display without fabricating detailed history.

---

### Task 1: Snapshot codec and repository

**Files:**
- Create: apps/android/app/src/main/java/com/jkdc/app/data/LevelTestAttemptRepository.kt
- Create: apps/android/app/src/test/java/com/jkdc/app/data/LevelTestAttemptRepositoryTest.kt

- [x] Write failing tests for bundled/remote round-trip and API hash identity, resume after DB close/reopen, and rejection of unsupported/corrupt snapshot.
- [x] Run focused JVM class and observe missing codec/repository failure.
- [x] Implement minimal codec/repository using existing atomic LearningDbWriter/ExamHistoryDao methods.
- [x] Verify round-trip and stored exam remain unchanged when current-week input advances.

### Task 2: ViewModel persistence lifecycle

**Files:**
- Modify: apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt
- Test: focused repository/ViewModel tests

- [x] Add failing tests for start hidden until durable, autosave failure retaining response, exact question ID/position retry, and idempotent completion.
- [x] Run focused tests and observe RED before implementation.
- [x] Add queue-backed start/save/retry/complete/resume/history operations; expose typed storage failure and durable status.
- [x] Verify focused tests pass.

### Task 3: Learn and Compose lifecycle

**Files:**
- Modify: apps/android/app/src/main/java/com/jkdc/app/ui/LearnScreen.kt
- Modify: apps/android/app/src/main/java/com/jkdc/app/ui/LevelTestScreen.kt
- Modify: apps/android/app/src/main/res/values/strings.xml
- Modify: apps/android/app/src/main/res/values-es/strings.xml

- [ ] Compose assertions for active/history routing and the legacy summary-only label (source reviewed independently; instrumentation execution deferred to final Android gate).
- [x] Run tests first, then wire resume/review by attempt ID and durable save/error/retry states with test tags.
- [x] Render historical review exclusively from stored snapshot, answers, verdicts, and original result.
- [x] Verify localization and applicable UI/JVM checks.

### Task 4: Verification and closure

**Files:** T07 OpenSpec verify report, odd/tasks/weekly-exam-recovery.md, Engram mirror.

- [x] Run Android JVM tasks in pinned Docker; record actual outcomes. `lintDebug`, APK and instrumented UI checks are deferred until all development phases per user instruction; the known missing Whisper submodule will be revisited then.
- [x] Run `git diff --check` and inspect changed-file scope from Docker.
- [x] Run independent high-risk verification in fresh scratch and parent spot-check.
- [x] Create one coherent Conventional Commit; record exact SHA, checks, Risk line, and pending gates.

## Verification evidence

- Focused `LevelTestAttemptStoreTest` and `LevelTestSnapshotAdapterTest`: passed.
- Full JVM suite: 712 app + 11 engine tests passed, zero failures/errors/skips.
- Independent fresh-scratch review passed S1–S6 invariants; local database file reopen preserved snapshot, position, stored score, original verdict and explanation.
- UI instrumentation and lint/APK are deferred until the final phase; no Whisper submodule download was attempted.
- Work-unit commit: `11e3cf6` (`feat(android): persist and recover weekly exam attempts`). The 1,111 authored-line change is one cohesive persistence-and-review behavior with tests and docs; splitting its atomic completion behavior by file would weaken the review boundary.
- Runtime harness: `N/A` for a connected Android device in this Docker JVM-only verification; the transaction lifecycle is covered by SQLite JVM tests and independent failure-injection probes.
- Rollback boundary: revert T07 commit `11e3cf6` to remove the Android attempt resume/history UI and repository changes without reverting prior T06 or T05 units.
- Risk: item 1 (durable exam/result persistence) and item 4 (ordered asynchronous writes and completion retry).
