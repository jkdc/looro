# Verify Report: lesson-levels

## F1 — LevelEngine (83a4c80)

- Android unit: **452/452** (5 new LevelEngineTest: tier growth by practice
  and score, spaced-practice tier 3 at ≥60%, ladder climb with points-to-next
  and in-level progress, top-of-ladder completion, listening medals).
- `check_cross_platform.py` green with new pinned constants:
  `level_exercise_good` (60), `level_exercise_mastered` (90),
  `level_listening_medal` (80), `level_ladder` (list of 10 thresholds).
- iOS core mirrored (`LevelEngine.swift`) + `LevelEngineTests.swift`
  (macOS gate pending, repo policy).

## F2 — Android UI (78e657f)

- Level card inside each course (number circle, "Level N · title", points to
  next, progress bar), 0-3 stars replacing the check on lesson rows, Level +
  Listening medals rows in the progress sheet, star medal on listening rows.
- Strings EN/ES in lockstep (343 strings + 1 plurals set each side).

## F3 — iOS mirror (a0b7650)

- Same surfaces in CoursesViews / LearningProgressView / ListeningView;
  Xcode project regenerated. Plural-correct "1 point / N points to Level X"
  on both platforms (Android plurals resource, iOS conditional).

## Manual emulator verification (2026-09-28)

- With the manual-test state (1 lesson practised + its exercise set at 100%):
  Learn shows **"Level 1 · First steps — 1 point to Level 2"** with the
  progress bar, and the lesson row shows **★★★** (tier 3: practice + 100%
  exercises) — `e2e-evidence/manual-2026-09-28/22-23`.
- Full Gradle suite re-run green after the plural fix (452/452).

## Pending gates

- macOS `swift test` / xcodebuild for the mirrored core and app UI.
- Device pass on the Pixel (level card and stars with real history).
- Threshold tuning (60/90/80, ladder) may be revisited with real telemetry
  once `exercise_completed` scores accumulate.
