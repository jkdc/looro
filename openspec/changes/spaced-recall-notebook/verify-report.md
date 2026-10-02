# Verify report: spaced-recall-notebook (CLOSED 2026-10-02 — macOS/device gates pending)

**Dates:** 2026-09-22 (Android round, branch `feature/rebranding-looro`) ·
2026-10-02 (iOS UI mirror, branch `feature/learning-platform`)

## Delivered in the Android round (commits `8d10abe`, `9f74da3`)

- **T1 (both cores):** `ExpressionState` in the Archive as an optional list
  (Kotlin default-empty list; Swift optional array) following the repo
  compatibility convention; codec validate/merge extended with a 2,000-entry
  cap and upsert-by-normalized-text; `check_cross_platform.py` pins the new
  struct; shared fixture `archive.json` extended (2 expressions) and asserted
  on both sides.
- **T2 (core):** `RecallEngine` (Kotlin + Swift mirror): idempotent lesson
  seeding, lookup save/upsert, remove, due-queue ordering, ladder grading
  (1/1/4/14 days; lapse → bar 1).
- **T3 (Android, 2026-09-22):** Learn tab recall card, typed
  recall session, saved-expressions section in Words, save-for-recall in the
  lookup sheet. EN/ES strings in lockstep (262/262 → 279/279). *(The Words
  saved-expressions section was later deleted at `033252c` — see the parity
  note in the iOS round below.)*
- **Emulator battery (fresh install):** onboarding → L1 practice with GLM →
  session end seeds expressions → Recall card shows "7 expressions ready" →
  typed recall grades ("Correct!" verdict, Continue) → Words notebook lists
  entries with bars and Remove. Evidence: `e2e-evidence/bateria-2026-09-22/`
  44–48.

## Delivered in the iOS round (2026-10-02) — the iOS UI mirror (3.2, 3.3 iOS half)

- **Recall card on the Learn tab** (`RecallCardView` in
  `apps/ios/App/RecallViews.swift`, mounted in `CoursesView`): due count from
  `RecallEngine.dueCount`, hidden when nothing is due (same condition as
  Android's `recall-card`), opens the session full-screen like the Android
  screen swap.
- **Typed recall session** (`RecallSessionView`): prompt (meaning) → typed
  answer → Check (`RecallEngine.normalizeKey` comparison, grading + ladder via
  `LearningStore.gradeRecall` → `RecallEngine.grade`) → verdict card
  ("Correct!" / "It was: …" + meaning) → Continue; round end records one
  `recall_round` completion. EN copy byte-matched to Android `strings.xml`
  (recall_* family, verified programmatically).
- **Notebook under Words** ("Saved expressions" section in `WordsView`):
  saved expressions (most recent first), recall bars, Remove action via
  `LearningStore.removeExpression`; empty state copy from
  `words_expressions_empty`. Bars carry the due state; no extra due label
  (per design.md). **Parity note (corrected):** this section mirrors the
  change's design.md, not Android at HEAD — Android's saved-expressions
  section was deleted from `LibraryScreens.kt` at `033252c` and its
  replacement never landed: `words_expressions_heading/_empty`,
  `words_expression_remove` and the intended `words_source_*` /
  `words_empty_cta_*` strings are defined but referenced by zero Kotlin
  files, and `notebookExpressions()`/`removeExpression()` are UI-dead.
  Android currently saves expressions with no way to view or remove them;
  iOS now leads parity on this surface (Android repair tracked separately).
- **Wiring:** `Storage.recordLessonAttempt` now seeds the lesson's target
  expressions through `RecallEngine.seedFromLesson` (mirror of Android's
  `recordLessonAttempt`); save-for-recall already existed on iOS via
  `ScenarioReviewSheet` → `saveLookupExpression`. `RecallViews.swift` added
  to `App.xcodeproj/project.pbxproj` with unique explicit references
  (group children + Sources phase).
- **Deviation noted:** Android commit `d388c86` accidentally dropped the
  `answer = ""; correct = null` reset from the Continue handler (stale answer
  and verdict carry into the next item); iOS restores the reset, matching the
  pre-`d388c86` behaviour and the repo's other drills. Android itself is
  untouched in this round.
- **Verification without a local toolchain:** `check_cross_platform.py` exit
  0; grep audit that every Core/Storage symbol called exists with a matching
  signature; bracket-balance check on all touched Swift files; pbxproj
  ID-uniqueness + reference-integrity checks.

## Gates

| Gate | Result |
|---|---|
| Android `:app` + `:core:engine-local` suites | ✅ 420/420 (2026-09-22) |
| `check_cross_platform.py` | ✅ exit 0 (2026-10-02) |
| Emulator manual flow (Android) | ✅ (2026-09-22) |
| **iOS UI mirror (recall screen, notebook)** | ✅ implemented 2026-10-02 — compile/device gates pending |
| iOS `swift test` mirror (`RecallEngineTests`) | ⏳ macOS gate pending |
| iOS compile (Xcode build incl. `RecallViews.swift`) | ⏳ macOS gate pending |
| iOS device e2e (card → session → notebook) | ⏳ pending |

## Verdict

CLOSED. Both cores are mirrored and pinned for parity; the Android UI is
user-tested; the iOS UI mirror is implemented and statically verified. The
record stays honest: the macOS compile/test gate (`RecallEngineTests` +
Xcode build) and an iOS device e2e pass remain pending and are the first
things to run on a Mac.
