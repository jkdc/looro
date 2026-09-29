# Tasks: foundations-temario

## Task 1 — Unit level in schema, exporter and models

- [x] 1.1 `scripts/export_courses.py`: require non-empty `level` on every
      unit; emit it after `subtitle` into both generated files.
- [x] 1.2 `Courses.kt` / `Courses.swift`: `CourseUnit.level` at the matching
      constructor position.
- [x] 1.3 New `scripts/tests/test_export_courses.py` for validator + emitters.

## Task 2 — Author the full temario

- [x] 2.1 U1: L2 (questions & negatives), L3 (introducing yourself).
- [x] 2.2 U2 People & things: unit themes + L4 articles/plurals, L5
      this/that & my/your, L6 have got: family.
- [x] 2.3 U3 Everyday actions: unit themes + L7 present simple, L8 third
      person -s & frequency, L9 time & routine.
- [x] 2.4 U4 Getting around: unit themes + L10 can/can't, L11 there is/are,
      L12 directions.
- [x] 2.5 U5 Food & likes (A2): unit themes + L13 some/any, L14
      like/love/hate + -ing, L15 ordering at a café.
- [x] 2.6 U6 The past begins (A2): unit themes + L16 was/were, L17 past
      simple -ed, L18 your weekend (repaso).
- [x] 2.7 Unit `level` values on all six units (A1 ×4, A2 ×2).

## Task 3 — Regenerate + gates

- [x] 3.1 Regenerate `CoursesData.kt`/`CoursesData.swift`; `--check` green.
- [x] 3.2 `check_cross_platform.py` green.
- [x] 3.3 Android `:app:testDebugUnitTest` green with updated `CoursesTest`.

## Task 4 — Mirror + record close

- [x] 4.1 iOS `CoursesTests`: import fix + full-shape mirror (runs under the
      macOS gate).
- [x] 4.2 verify-report.md with honest gates (macOS pending; content pending
      competent-speaker review before any public CEFR claim).
