# Verify report: foundations-temario

**Date:** 2026-09-22 · **Branch:** `feature/rebranding-looro`

## Gates run (this machine: Linux)

| Gate | Result |
|---|---|
| `scripts/export_courses.py` (regenerate both cores) | ✅ Wrote `CoursesData.kt` + `CoursesData.swift` |
| `scripts/export_courses.py --check` | ✅ In sync |
| `python3 -m unittest discover -s scripts/tests` | ✅ 61/61 (incl. 7 new `test_export_courses.py`) |
| `scripts/check_cross_platform.py` | ✅ exit 0 |
| Android `:app:testDebugUnitTest` | ✅ 408 tests, 0 failures (51 suites) |
| Android `:core:engine-local:testDebugUnitTest` | ✅ 5 tests, 0 failures (total 413) |
| Updated JVM `CoursesTest` (6 units × 3 lessons, levels, shape, guidance) | ✅ green in the run above |

## Pending gates (require hardware this machine lacks)

- **macOS gate:** `CoursesTests.swift` (and the whole iOS suite) not executed. In
  addition to the content mirror, this round **fixed a latent build break** the
  pending gate would have caught: `CoursesTests.swift` still imported the
  pre-rebranding `MuralCore` module; now `LearningCore`.
- **Competent-speaker review:** the 17 authored lessons are pedagogically
  structured but unreviewed. Per repo claim discipline, no public CEFR/coverage
  claims until reviewed; unit `level` stays internal data (not rendered).

## Incidental fix

`scripts/tests/test_check_android_release.py` expected 13 characters for
`Palique: Bokmål` — the length of the pre-rebranding `Looro: Bokmål`. Failed on
clean HEAD before this change; expectation corrected to 15. Pre-existing, not
caused by this change.

## Verdict

Implemented as specified: full Foundations temario (6 units × 3 lessons = 18,
per `informe-cursos-2026-09-21.md` §7), unit `level` in schema/exporter/models
on both platforms, drift-checked. Android fully green; iOS verified by
reasoning + mirror test update only (macOS gate pending, as recorded).
