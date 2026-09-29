# Proposal: foundations-temario

## Intent

Complete the *English · Foundations* course content: the full temario from
`informe-cursos-2026-09-21.md` §7 (6 units × 3 lessons = 18 lessons; lesson 1
was authored as the pilot) plus the unit-level CEFR field that the roadmap
(`informe-mejoras-2026-09-22.md`, item A1/feature 2) requires to be designed
**before** authoring, so future placement/routing never re-authors content.

## Problem

The Learn tab ships with one authored lesson against an 18-lesson syllabus:
the product's bottleneck is content, not platform. The syllabus structure
(units, titles, focus) is already fixed in the course design report; the JSON
schema has no notion of level; and the iOS mirror test still imports the
pre-rebranding `MuralCore` module, so the macOS gate would fail to build.

## Approach

- **Content is data, again.** All 17 remaining lessons are authored directly
  in `shared/courses/en-foundations.json` following the L1 pattern exactly
  (3 explanation paragraphs, 6 examples with glosses, 6 target expressions,
  6 practice questions with `expected`/`focus`/`watch`, a task-pedagogy
  situation). Each new unit gets 1–2 focused `ConversationTheme`s.
- **Unit level as data.** New required `level` field on every unit
  (`A1` ×4, `A2` ×2 for Foundations). Validated by the exporter, emitted into
  both cores, carried by `CourseUnit` on both platforms. Not rendered in this
  change — claim discipline (no public CEFR announcements before
  competent-speaker review); it exists so level-aware routing can be built
  later without re-authoring.
- **Mirror tests updated in lockstep.** JVM `CoursesTest` asserts the full
  shape (6 units × 3 lessons, levels present); iOS `CoursesTests` mirrors it
  and gets its import fixed to `LearningCore`.

## Out of scope

- Rendering the level in the UI (future placement/routing change).
- Evidence bars / spaced repetition for lessons (parked F3).
- Courses for other languages; multiple-choice quizzes; unlocking.
