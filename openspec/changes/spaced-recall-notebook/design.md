# Design: spaced-recall-notebook

## Data model (Archive v2-compatible, both cores)

```kotlin
data class ExpressionState(
  val text: String, val meaning: String,
  val source: String,             // "lesson" | "lookup"
  val lessonID: String? = null,   // when source == "lesson"
  var bars: Int = 0,              // 0-3, same ladder as WordState
  var attempts: Int = 0, var lastSeen: Double = 0.0, var dueAt: Double = 0.0
)
// Archive: var expressions: MutableList<ExpressionState> = mutableListOf()
// Swift:   var expressions: [ExpressionState]?   (optional — old archives decode)
```

- Same conventions as `LessonState`: optional field outside `ArchiveCodec`
  `fields(...)`, cap 2000 with oldest-`lastSeen` eviction, `check_cross_platform.py`
  Archive struct list and the shared fixture (`expressions.json` or archive
  fixture extension) pinned.
- Scheduling reuses the `WordState` ladder constants (`[1, 1, 4, 14]` days) —
  no new numerics to pin beyond what the checker already pins.

## Queue semantics

- `due(now)` = expressions with `bars < 3 && dueAt <= now`, ordered by `dueAt`,
  interleaved with due words from the existing word bars (round-robin by
  source so lessons and conversation words mix).
- First practice of a lesson seeds its 6 target expressions (idempotent:
  existing text wins). Lookup save upserts on normalized text.
- Grading in v1: typed recall against `expected` patterns for lesson
  expressions (reuse `LessonPractice` matching helpers); free-form notebook
  entries use self-assessment buttons (knew it / again) — honest, no
  assessment-pipeline dependency yet (that is T6's later upgrade).

## UI

- Learn tab: "Repasar (N)" card above the course when N > 0 (N = due count).
- Recall screen: prompt → answer input → verdict + meaning card → next;
  session ends on empty queue or back. Reuses drill components on both
  platforms.
- Notebook: tab under Words ("Expresiones") listing saved expressions with
  bars, delete action, and "practise now" for due ones.

## Strings

New EN/ES keys in lockstep (recall card, queue, verdicts, notebook tab,
save/confirm). Course content stays in courses JSON.

## Tests

- JVM: Archive round-trip with old fixtures (compatibility), seeding
  idempotency, ladder scheduling math, queue ordering, cap eviction.
- iOS mirror under `Tests/` (macOS gate pending as always).
- `check_cross_platform.py`: Archive struct + fixture pins.
- Both export `--check`s unaffected.
