# Tasks: courses-and-lessons

## Task 1 — Content pipeline

- [ ] 1.1 `shared/courses/en-foundations.json`: pilot course (unit 1, lesson
      "The verb to be" fully authored + 2 focused themes).
- [ ] 1.2 `scripts/export_courses.py`: schema validation, emits
      `CoursesData.kt`/`CoursesData.swift`, `--check` drift gate.
- [ ] 1.3 Hand-written struct/registry files (`Courses.kt`, `Courses.swift`).

## Task 2 — Data model (both cores)

- [ ] 2.1 `SessionRecord.lessonID` optional on both platforms.
- [ ] 2.2 `LessonState` + optional `Archive.lessons`; encode/decode; old
      archives still decode; `check_cross_platform.py` archive list updated.
- [ ] 2.3 Registry + archive tests (JVM; iOS mirrored under macOS gate).

## Task 3 — Practice prompts

- [ ] 3.1 `TeachingPolicy.lessonPractice` / `lessonOpening`; optional
      `lesson` parameter on `typedReply`.
- [ ] 3.2 JVM policy tests; checker green.

## Task 4 — Android study + practice UI

- [ ] 4.1 4th tab "Learn"; `CoursesScreen` + `LessonScreen` (EN/ES strings,
      icon).
- [ ] 4.2 TTS card reader with audio switch (`SpeechSink`, course locale,
      degrade on failure, stop on navigation).
- [ ] 4.3 Practice start: `mode="lesson"` + `lessonID` + lesson prompts;
      attempts recorded on session end.
- [ ] 4.4 Compile (`assembleDebug`), unit suites green.

## Task 5 — iOS study + practice UI (reasoning-verified; macOS gate REQUIRED)

- [ ] 5.1 Learn tab + `CoursesViews` mirror; AVSpeech card reader with switch.
- [ ] 5.2 Practice start mirror; project regenerated if the generator needs
      explicit file references.
- [ ] 5.3 `Tests/CoursesTests.swift` mirrors the JVM cases.

## Task 6 — Record close

- [ ] 6.1 `check_cross_platform.py` + both export `--check`s green; full
      Android suites green.
- [ ] 6.2 verify-report.md with honest gates (macOS, device listen-check of
      one practice session, rebrand coordination note).
