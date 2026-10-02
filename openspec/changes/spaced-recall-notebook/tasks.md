# Tasks: spaced-recall-notebook

## Task 1 — Data model (both cores)

- [x] 1.1 `ExpressionState` + optional `Archive.expressions` (Kotlin/Swift
      mirror); encode/decode; old archives still decode.
- [x] 1.2 `check_cross_platform.py` Archive list + shared fixture pin;
      cap/eviction helper.

## Task 2 — Capture + scheduling

- [x] 2.1 Lesson practice seeds target expressions (idempotent).
- [x] 2.2 Lookup sheet "save" action; notebook upsert.
- [x] 2.3 Ladder scheduling + due-queue ordering (JVM tests; iOS mirror).

## Task 3 — Recall + notebook UI

- [x] 3.1 Learn tab recall card + recall session (typed v1) on Android.
- [x] 3.2 iOS mirror; project regenerated if needed. *(Recall card, typed
      recall session and "Saved expressions" notebook under Words in
      `apps/ios/App/RecallViews.swift`, wired through CoursesViews,
      LibraryViews and Storage; `RecallEngineTests` exist in
      `apps/ios/Tests/` — macOS compile/test gate pending, device e2e
      pending.)*
- [x] 3.3 Notebook tab under Words (Android; iOS mirrored with 3.2).
- [x] 3.4 EN/ES strings parity; `assembleDebug`; full JVM suites.

## Task 4 — Record close

- [x] 4.1 `check_cross_platform.py` + export `--check`s green.
- [x] 4.2 verify-report.md with honest gates (macOS, device).
