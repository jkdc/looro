# Verify Report: courses-and-lessons

Date: 2026-09-21 · Branch: `feature/switchable-conversation-engine` (local)

## What was delivered

- **Content pipeline:** `shared/courses/en-foundations.json` (canonical) +
  `scripts/export_courses.py` emitting `CoursesData.swift`/`CoursesData.kt`
  with a `--check` drift gate (package anchored to the generated
  `Languages.kt`, so platform renames cannot desync it). Hand-written shapes
  and `CourseRegistry` on both cores.
- **Pilot content:** English · Foundations, unit 1, lesson "The verb to be"
  fully authored (explanation, 6 examples with glosses, 6 target expressions,
  situation, 6 pre-set practice questions with expected/focus/watch) + two
  focused conversation themes.
- **Data model (both cores):** optional `Archive.lessons: [LessonState]`
  (capped at 1000, max-progress merge on import) and
  `SessionRecord.lessonID`; pre-change archives still decode; the shared
  cross-platform fixture pins the `lessons` field; the checker pins
  `LessonState`.
- **Practice prompts (parity-pinned):** `lessonPractice` (rigor policy +
  numbered questions with focus/watch), `lessonOpening`; `typedReply`
  gained an optional lesson anchor.
- **Android UI:** 4th tab "Learn" → course → lesson card (explanation,
  examples, expressions, situation, focused themes) with local-TTS
  read-aloud behind an audio switch (course-language voice; missing voice
  degrades to silent reading). "Practise speaking" routes through consent +
  microphone into a `mode = "lesson"` session with `lessonID`, lesson
  instructions and lesson opening; finishing records one attempt.
- **iOS UI:** CoursesView mirror + AVSpeech read-aloud with the same switch
  and degradation; coordinator `selectLesson` + lesson sessions + attempt
  recording in the store; Xcode project regenerated for the new file.

## Verified on this machine

- `scripts/check_cross_platform.py` green (prompts + LessonState archive
  fields).
- `scripts/export_courses.py --check` green; `export_android_content.py
  --check` green (the rebrand left this broken earlier today; it passes on
  the settled tree).
- Android: `:app:testDebugUnitTest` 401/401, `:core:engine-local` 5/5, 0
  failures (new: registry, archive round-trip/merge/rejection, lesson
  session, policy prompts), `:app:assembleDebug` + resource processing
  green; EN/ES strings in parity (255 keys each).

## Gates pending (not run here)

- **macOS gate (REQUIRED before merge):** `swift test --package-path apps/ios`
  (new `CoursesTests`, extended `TeachingPolicyTests`) and an Xcode build of
  the regenerated project; iOS sources are reasoning-verified only.
- **Device checks (recommended, not blocking):** one practice conversation on
  the Pixel 9 (engine glm) to hear the tutor run the pre-set questions and
  correct answers; Learn-tab read-aloud voice availability per device.

## Coordination note

The `rebranding-looro` change landed in this same tree mid-flight (chat.mural
→ com.jkdc.looro, MuralViewModel → LooroViewModel). This change was rebased
onto it: path-isolated artifacts were committed first, app integration only
after the tree settled, with pathspec-scoped commits throughout.

## Follow-ups (parked)

- Evidence-based lesson progress (bars/dueAt from validated assessments).
- Authoring the remaining Foundations lessons (outline in
  `informe-cursos-2026-09-21.md` §7; proficient-speaker review required).
- Courses for other languages.
