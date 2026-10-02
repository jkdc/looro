# Verify report: spaced-recall-notebook (CLOSED 2026-10-02 — macOS/device gates pending)

**Dates:** 2026-09-22 (Android round, branch `feature/rebranding-looro`) ·
2026-10-02 (iOS UI mirror + Android Words-notebook repair, branch
`feature/learning-platform`) · 2026-10-02 (fix wave: notebook remove
recomposition, iOS parity cosmetics)

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
  saved-expressions section was later deleted at `033252c` and repaired at
  `e3afbd9` — see the Android repair note below.)*
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
  (per design.md). **Parity note (corrected again, fix wave):** the Android
  surface this section mirrors has landed — `e3afbd9` restored the
  saved-expressions section to `LibraryScreens.kt` (rows under the search
  field with `words_source_*` origin labels, Remove wired through
  `removeExpression`, empty-state CTAs using `words_empty_cta_*`), so
  `notebookExpressions()`/`removeExpression()` are UI-live on both
  platforms and parity is restored. Fix-wave commit `482b623` additionally
  makes `removeExpression` copy the archive before mutating so the notebook
  actually recomposes on remove (the state setter skips element-wise-equal
  writes).
- **Wiring:** `Storage.recordLessonAttempt` now seeds the lesson's target
  expressions through `RecallEngine.seedFromLesson` (mirror of Android's
  `recordLessonAttempt`); save-for-recall already existed on iOS via
  `ScenarioReviewSheet` → `saveLookupExpression`. `RecallViews.swift` added
  to `App.xcodeproj/project.pbxproj` with unique explicit references
  (group children + Sources phase).
- **Deviation noted (resolved):** Android commit `d388c86` had dropped the
  `answer = ""; correct = null` reset from the Continue handler (stale answer
  and verdict would carry into the next item); `e3afbd9` restored the reset
  on Android, matching the iOS mirror and the pre-`d388c86` behaviour, so
  the platforms no longer diverge here.
- **Verification without a local toolchain:** `check_cross_platform.py` exit
  0; grep audit that every Core/Storage symbol called exists with a matching
  signature; bracket-balance check on all touched Swift files; pbxproj
  ID-uniqueness + reference-integrity checks.

## Android Words-notebook repair (2026-10-02, `e3afbd9` + fix wave `482b623`)

- `e3afbd9` landed the Words saved-expressions section that `033252c` had
  deleted: notebook rows under the search field (text, meaning, `words_source_*`
  origin label, recall bars, Remove), empty-state copy with two CTAs
  (`words_empty_cta_lessons` → Learn tab, `words_empty_cta_talk` → Talk tab),
  and the recall-session answer/verdict reset restored in the Continue
  handler.
- Fix wave `482b623`: `removeExpression` now copies the archive before
  `RecallEngine.remove` mutates it. The previous shape removed on the live
  list first and then re-assigned an element-wise-equal copy, which the
  `mutableStateOf` structural-equality setter skipped — Remove worked on
  the model but the notebook never recomposed.

## Fix-wave iOS parity cosmetics (2026-10-02)

- Notebook rows now show the same origin label as Android ("From a lesson" /
  "Saved by you", derived from `ExpressionState.source` like Android's
  `expressionSourceLabel`); the empty state gained the two CTAs ("Practise a
  lesson" → Learn tab, "Start talking" → Talk tab, wired from `RootView` the
  same way `CoursesView` switches tabs); and the "Saved expressions" heading
  carries `.accessibilityAddTraits(.isHeader)`.

## Gates

| Gate | Result |
|---|---|
| Android `:app` + `:core:engine-local` suites | ✅ 420/420 (2026-09-22) · ✅ 518/518 (2026-10-02, at `e3afbd9` + fix wave) |
| `check_cross_platform.py` | ✅ exit 0 (2026-10-02) |
| Emulator manual flow (Android) | ✅ (2026-09-22) — ⏳ no fresh emulator pass on the repaired notebook/CTA surfaces yet |
| **iOS UI mirror (recall screen, notebook)** | ✅ implemented 2026-10-02 — compile/device gates pending |
| iOS `swift test` mirror (`RecallEngineTests`) | ⏳ macOS gate pending |
| iOS compile (Xcode build incl. `RecallViews.swift`) | ⏳ macOS gate pending |
| iOS device e2e (card → session → notebook) | ⏳ pending |

## Verdict

CLOSED. Both cores are mirrored and pinned for parity; the Android UI was
user-tested in the 2026-09-22 round (the `e3afbd9`-repaired notebook
surfaces still owe a fresh emulator pass), and the iOS UI mirror is
implemented and statically verified. The
record stays honest: the macOS compile/test gate (`RecallEngineTests` +
Xcode build) and an iOS device e2e pass remain pending and are the first
things to run on a Mac.
