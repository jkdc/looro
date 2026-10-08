# Proposal: placement-test

## Intent

A free, optional CEFR placement test (test de nivel) so the Learn tab can
suggest the right starting level. Fully authored item banks (no AI, no
network), an adaptive band ladder, listening (system TTS) and short-form
writing blocks, and an always-shown CEFR self-assessment (can-do) after the
test — even if it is abandoned. The result **suggests, never fixes**: the
learner's manual level choice always wins.

Designed and decided in `informe-test-nivel-2026-10-07.md` (audited same day;
decisions closed 2026-10-08, §7).

## Problem

The Learn tab now organizes the catalog by CEFR level (see
`docs/pantalla-aprender-diseno.md`), and the default level is derived from the
recommended lesson — which starts everyone near A1 regardless of real ability.
A learner who already speaks some of the language has no way to say so, so
they face a wall of "New" lessons below their level or must hand-pick a level
with no guidance.

## Approach

- **Item banks** `shared/placement/<lang>-placement.json` (EN + ES in v1),
  authored, exported by `scripts/export_placement.py` into
  `PlacementData.kt` with exporter-side validation (new JSON, so the existing
  checkers do not cover it). Each item reuses the authored `Exercise` shapes
  (`choice`/`written`) tagged with `level` (A1–C1) and `mode`
  (`core`/`listening`/`writing`), plus a skill id from the existing catalog.
- **Adaptive band ladder** (`PlacementEngine`, pure Kotlin, JVM-testable,
  deterministic): start at the recommended lesson's level (A1 from zero);
  2-of-last-3 correct in band → up, 2-of-last-3 wrong → down (floor A1, cap
  C1); stop on 18 core answers or a floor/ceiling verdict. Fixed final blocks
  at the resulting band: 3 listening items (system TTS in the learning
  language voice) and 3 short-form writing items (exact-match grading via the
  existing `ExerciseEngine`). Listening/writing can each lower **their own
  breakdown** by one band; they never change the global band.
- **Can-do always after** the test, complete or abandoned: 5 self-assessment
  statements, one per band; it is the guaranteed level signal.
- **Persistence with zero schema change**: the ledger keeps one
  `assessment` completion (`itemID = placement-<lang>-v<version>`, percent of
  the core — slug-safe per the server's `progress.ts` validation); the full
  last result (band, category breakdown, confidence, date, source) lives in a
  local result store (SharedPreferences JSON), re-derivable by re-taking the
  test. Cross-platform sync rides the existing `user_progress` machinery.
- **Integration**: Learn's CEFR bar defaults to the placement suggestion when
  present (recommended lesson level otherwise); a "¿Cuál es tu nivel?" card
  opens the test; a new generic "Instructions language" setting
  (`Subtitles` by default, or a translated language) drives the test's
  instructions.

## Out of scope (this change)

- iOS implementation (the shared JSON is the contract; the Swift mirror of
  `PlacementEngine`/`PlacementScreen` is follow-up, same policy as
  `ios-backend-parity`), free-form writing/speaking scoring (v2 calibration
  conversation), server events (the ledger kind already exists), and
  non-EN/ES banks (F4).
- Native-speaker content sign-off: banks ship as **pilot content** with the
  review logged as an open gate in `verify-report.md`, per the repo's rule
  that teaching claims need competent-speaker review.
