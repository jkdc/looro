# Design: courses-and-lessons

## Data model

Content (static, embedded — canonical JSON, generated per platform):

```jsonc
// shared/courses/en-foundations.json
{ "id": "en-foundations", "languageID": "en", "title": "...", "subtitle": "...",
  "symbol": "graduationcap", "description": "...",
  "units": [ { "id": "...", "title": "...", "subtitle": "...",
               "themes": [ /* ConversationTheme objects (new or verbatim reuse) */ ],
               "lessons": [ { "id": "...", "title": "...", "goal": "...",
                              "intro": { "explanation": ["..."], "examples": [{"text","meaning"}] },
                              "expressions": [{"text","meaning","note"}],
                              "situation": "...",
                              "practice": { "questions": [{"prompt","expected":[],"focus","watch"}] } } ] } ] }
```

```swift
// apps/ios/Core/Courses.swift (hand-written, stable)
public struct Course { id, languageID, title, subtitle, symbol, description, units: [CourseUnit] }
public struct CourseUnit { id, title, subtitle, themes: [ConversationTheme], lessons: [Lesson] }
public struct Lesson { id, title, goal, intro: LessonIntro, expressions: [TargetExpression], situation, practice: LessonPractice }
public struct LessonIntro { explanation: [String], examples: [ExampleLine] }
public struct ExampleLine { text, meaning }
public struct TargetExpression { text, meaning, note }
public struct LessonPractice { questions: [PracticeQuestion] }
public struct PracticeQuestion { prompt, expected: [String], focus, watch }
public enum CourseRegistry { public static let all = CoursesData.all  // generated
  public static func forLanguage(_ id: String) -> [Course]; public static func lesson(id: String) -> Lesson? }
```

Kotlin mirror identical (`core/Courses.kt` hand-written + `core/CoursesData.kt`
generated). Only authored lessons ship; the temario outline for future lessons
stays in `informe-cursos-2026-09-21.md` §7 until authored (follow-up change).

### Generator (`scripts/export_courses.py`)

- Reads every `shared/courses/*.json`; validates schema (unique ids across
  courses/units/lessons/themes-per-unit, non-empty fields, `languageID`
  resolvable against the Swift `LanguageRegistry.all` ids, ≥1 lesson per
  unit).
- Emits `CoursesData.swift` (Swift literals) and `CoursesData.kt` (Kotlin,
  package line read from the existing generated `Languages.kt` so the
  in-flight rebrand cannot desync it).
- `--check` regenerates and compares, exit 1 on drift (same contract as
  `export_android_content.py`).

## User-data model (Archive)

- `SessionRecord.lessonID: String?` — optional (Swift `String?`, Kotlin `= null`),
  absent from `ArchiveCodec.fields(...)` checks; old archives decode.
- `LessonState(lessonID, bars, attempts, lastSeen, dueAt)` — WordState-shaped;
  `Archive.lessons` optional list. v1 only ever writes `bars = 0`, `dueAt = 0`
  and increments `attempts`/`lastSeen`; the bars/dueAt machinery is the
  follow-up evidence change. `check_cross_platform.py` archive-fields: add
  `LessonState` to its struct list so the two cores cannot drift.

## Prompts (TeachingPolicy, parity-checked)

- `lessonPractice(lesson, language)` — base instructions for the practice
  session: rigorous-but-warm teacher role; ask the pre-set questions **in
  order, one at a time**; after each answer explicitly say whether it was
  correct; correct with a recast + one-line rule (guided by the question's
  `focus`/`watch`); do not advance until the target form appears (assisted is
  acceptable); support in any language, echo in the course language; close
  with a 3-expression mini-review. The numbered questions (with focus/watch)
  are interpolated from lesson data.
- `lessonOpening(lesson, language)` — first-turn command (sibling of
  `greeting`): present the lesson in two short sentences, then ask question 1.
- `lessonSituation(lesson, language)` — unused in v1 flows; reserved for
  focused-theme free conversation if a unit theme is chosen outside a session
  (falls back to `theme()` today — decision: reuse `theme()` and skip this
  function until needed; NOT shipped).
- Session wiring: `mode = "lesson"`, `title = lesson.title`, `themeID = nil`;
  opening command picks `lessonOpening` over `greeting` when `lessonID` is
  set (mirrors the `listeningStory` branch). Typed replies append one
  lesson-aware line via a `typedReply(language, lesson = null)` optional
  parameter.

## UI flow

1. New 4th tab **Learn** (Android `FloatingNavigation` gains a 4th item,
   `maxWidth / 4`; iOS tab equivalent).
2. Courses list for the current learning language (empty state if none).
3. Course screen: units in order, each showing its lessons (attempts badge)
   and its focused themes (tap = same flow as Topics themes: sets the theme
   and returns to Talk).
4. Lesson screen: goal, explanation, examples (text + meaning), expressions,
   situation card, themes footer; speaker button reads the card aloud in the
   course language, audio switch (persisted per app run) silences it; primary
   CTA "Practise speaking" starts the practice session and switches to Talk.

## Platform notes

- Android reader: a dedicated `SpeechSink` instance (engine-independent —
  system TTS), `setLanguage(course.locale)` with degrade-on-failure semantics
  from cabling-remediation T1; stop on navigation/tab change.
- iOS reader: `AVSpeechSynthesizer` wrapper in the App layer (not Core);
  voice picked by locale, degrade to silent reading.
- New strings EN/ES in key parity (Android); iOS strings English.
- Generated Xcode project: re-run `scripts/generate_project.py` after adding
  Swift files (verify whether Core files are globbed; follow existing
  mechanics).

## Tests

- Generator: schema validation failures; emitted Kotlin/Swift compile (suite
  run) and `--check` idempotence.
- Registry: `forLanguage` filters by course language; unique ids; lesson
  lookup by id.
- Archive: pre-change archive decodes without `lessons`/`lessonID`; round-trip
  preserves a `LessonState`; `lessonID` sessions round-trip.
- Policy: practice prompt contains the rigor lines + every question prompt;
  opening differs from generic greeting; typed reply carries the lesson line
  only with a lesson (mirrors themed-conversation-steering test style).
- Checker + full Android suites green at close; iOS tests mirrored under the
  macOS gate.
