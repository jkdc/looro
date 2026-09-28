# Design: lesson-levels

## LevelEngine (pure, mirrored Kotlin/Swift)

```
const EXERCISE_GOOD = 60        // tier 2 threshold on exercise best score
const EXERCISE_MASTERED = 90    // tier 3 by score
const LISTENING_MEDAL = 80      // silver medal; 100 = gold
const LADDER = [1,4,8,13,19,26,34,42,48,54]  // points for level 1..10
```

- `lessonTier(lessonID, lessons, completions, calendar)`:
  - attempts < 1 → 0
  - exercise best < EXERCISE_GOOD (or absent) → 1
  - best ≥ EXERCISE_MASTERED **or** lesson-completion marks on ≥2 distinct
    local days → 3
  - otherwise → 2
- `stats(course, lessons, completions, calendar)` → `LevelStats(courseID,
  points = Σ tiers, level (ladder), pointsToNext (null at top),
  progressInLevel (0..1), mastery per lesson)`.
- `medal(bestScore)` → 0 none / 1 silver (≥80) / 2 gold (100).
- Day boundaries follow the local calendar, same convention as
  `LearningEngine.stats`/`ProgressEngine.stats` (tests pin UTC).
- Monotonic by construction: every input (attempts, best via max-merge,
  marks) only grows, so tiers and levels never regress.

Titles are presentation data: 10 string resources EN/ES on Android, inline
English literals on iOS. The engine returns numbers only.

## CONSTANTS pinning

`check_cross_platform.py` gains: `level_exercise_good`, 
`level_exercise_mastered`, `level_listening_medal` (scalars) and
`level_ladder` (list), extracted from both cores.

## UI

- Learn (both platforms): level card under the heading — level number in a
  circle, "Level N · <title>", points/progress to the next level
  ("Course complete!" at the top); per-lesson rows show 0-3 stars instead of
  the single check.
- Progress sheet: "Level" row (value "N · title") first in the Course
  progress group; "Listening medals" count row.
- Listening list: medal star next to "Best %" when ≥80.

## Fases

F1 engine+tests+CONSTANTS; F2 Android UI+strings; F3 iOS mirror; F4
verification, commits per phase, informe + memory update.
