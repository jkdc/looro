# Design: progress-exercises-listening

## Data model

### Local ledger (both cores, mirrored)

```
CompletionMark(kind: String, itemID: String, at: Double, bestScore: Int = 0)
Archive.completions: MutableList<CompletionMark>? = null   // Kotlin
Archive.completions: [CompletionMark]? = nil               // Swift
```

- `kind` is one of the closed set `lesson | conversation_theme | exercise_set |
  listening | recall_round | words_practice | assessment` (same set as the
  server's `user_progress.kind` CHECK).
- `at` is Apple-epoch seconds like every other archive timestamp.
- Optional (nullable) on **both** platforms on purpose:
  - synthesized Swift `Decodable` must keep accepting archives/backups from
    before this change (missing key → nil);
  - `CrossPlatformFixtureTest` compares re-encoded field paths against the
    shared fixture; a null/nil empty list encodes to nothing on both sides,
    so the fixture files stay untouched.
- `ArchiveCodec.merge` unions marks with exact-tuple dedupe on
  `(kind, itemID, at)` and caps at 2,000 (newest kept). Validation mirrors
  the existing lessons/expressions checks.
- `ARCHIVE_STRUCTS` in `scripts/check_cross_platform.py` gains
  `CompletionMark`.

### Lesson completion

No LessonState change. A lesson is HECHA when `attempts >= 1` — the same
semantics the Learn tab already renders as "Practicada N×". A
`CompletionMark(lesson, …)` is appended when the attempt lands, which gives
per-day history the counter cannot.

### itemID slugs

Course/theme/lesson/listening ids are already lowercase slugs. Words practice
keys (lemmas with accents/spaces) are mapped with `ProgressEngine.completionKey`:
the raw string when it already matches `^[a-z0-9][a-z0-9_-]{0,63}$`, otherwise
the first 16 hex chars of SHA-256 (Kotlin `MessageDigest`, Swift `CryptoKit`).

### Sync wire (mirrors usage events)

- `POST /v1/progress` `{ entries: [{kind, itemID, at(ms), attempts, bestScore}] }`
  → 202 `{merged}`; upsert on `(account, kind, item)` with
  `LEAST(first_at) / GREATEST(last_at, attempts, best_score)`. Cumulative
  counters + monotonic merge make replays and multi-device pushes converge.
- `GET /v1/progress` → `{ progress: [rows] }`, newest first, capped at 1,000.
- Entries are derived by `ProgressSync` from marks + LessonState; non-slug
  item keys are hashed exactly once (at mark creation), so wire ids are stable.
- Pull applies lesson rows into `LessonState` (max merge) and, for
  `exercise_set`/`listening`, synthesizes one mark per item the device has
  never seen, so counts survive reinstall. Per-day history does not survive —
  accepted v1 limit.

## ProgressEngine (pure, mirrored)

`stats(sessions, lessons, completions, courses, zone, now)` →
`ProgressStats(courses[], lessonsDone/Total, activeStreakDays,
longestStreakDays, last30DaysActivities[30], themesPractised,
exercisesCompleted, listeningCompleted, practiceRounds, wordsPracticeItems,
assessmentsPassed)`. Days are local-zone epoch days over mark days ∪
voice-minute session days. Mirrors the style of `LearningEngine.stats`.

## Exercises & listening content

- Lesson JSON gains `exercises: [{id, kind: choice|gap|order|written, prompt,
  options?, answer|answers, explain}]`. Exporter validates ids, kinds,
  option counts and gap markers; `CourseRegistry` models gain `Exercise`.
- `shared/listening/en-listening.json`: `{id, languageID, title, level, text,
  questions:[…same exercise shapes…]}`; new exporter
  `scripts/export_listening.py` (pattern of `export_courses.py`, with
  `--check` in contracts.yml).
- Grading lives in one `ExerciseEngine` per platform (normalization borrowed
  from `RecallEngine`/`WordPractice`: NFC, casefold, punctuation strip,
  `<name>` placeholders).

## UI

- Learn tab: HECHA check + per-unit/course percent (from ProgressStats).
- Progress sheet ("Tu progreso"): activity streak, lessons done, exercises,
  listening, 30-day activity chart.
- New Listening tab (5th) with list → player (TTS controls, hidden
  transcript during questions) → written questions → score.
- Telemetry: `exercise_completed {courseID, lessonID, scoreBucket}`,
  `listening_completed {itemID, scoreBucket}` (catalog + CHECK extended in
  031); `practice_completed{mode:"word"}` starts being emitted.

## Platform gates

Android verified locally (Gradle). iOS core mirrored + tests, macOS gate
pending per repo policy. Backend verified in the node:22 + postgres:17
recipe.
