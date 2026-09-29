# Design: foundations-temario

## Content plan (fixed by informe-cursos-2026-09-21 §7)

| Unit | Level | Lessons |
|---|---|---|
| U1 First sentences | A1 | L1 to be ✅ · L2 questions & negatives · L3 introducing yourself |
| U2 People & things | A1 | L4 a/an/the & plurals · L5 this/that & my/your · L6 have got: family |
| U3 Everyday actions | A1 | L7 present simple I/you/we/they · L8 he/she/it + -s & frequency · L9 time & routine |
| U4 Getting around | A1 | L10 can/can't · L11 there is/are · L12 directions & place |
| U5 Food & likes | A2 | L13 some/any · L14 like/love/hate + -ing · L15 ordering at a café |
| U6 The past begins | A2 | L16 was/were · L17 past simple -ed · L18 your weekend (repaso) |

Lesson ids follow the existing scheme `en-foundations-u<n>-<slug>` (ASCII
slugs). Each lesson matches L1's shape exactly: 3 explanation paragraphs in
very simple English, 6 examples with English glosses, 6 target expressions
(text/meaning/note), 6 practice questions (prompt/expected/focus/watch), and
a situation that forces the target form (task pedagogy).

Unit themes reuse `ConversationTheme`; ids may repeat global theme ids (the
existing U1 unit theme `introductions` already shares an id with the global
theme — accepted pattern, the lists are separate). `symbol` values are valid
SF Symbol names; Android falls back to its sparkles glyph for unmapped ids
(the `themeSymbol` `else` branch), which is the designed degradation.

## Unit level field

- JSON: required `level` on every unit. Exporter validates non-empty and
  emits it in both generators; `CourseUnit` on both platforms carries it.
- Constructor position: after `subtitle`, in both the Swift positional init
  and the Kotlin named-arg emitter, so the generated files stay symmetric.
- Not surfaced in UI. CEFR labels stay internal data until competent-speaker
  review (informe-temario §3.6 claim discipline).

## Tests

- Exporter: new pytest `scripts/tests/test_export_courses.py` covering the
  validator (missing level, duplicate ids, missing question fields) and both
  emitters (level lands in both outputs).
- JVM `CoursesTest`: full-registry shape (6 units × 3 lessons = 18, levels
  non-blank, unique ids, question guidance) replacing pilot counts.
- iOS `CoursesTests`: mirror of the same shape; import fixed
  `MuralCore` → `LearningCore` (latent build break caught by inspection; the
  suite itself runs under the macOS gate, recorded as pending).
- Gates: `export_courses.py --check`, `check_cross_platform.py`, Android
  `:app:testDebugUnitTest` green. macOS gate pending as usual.
