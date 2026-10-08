# Tasks: placement-test

## Task 1 — Item banks (EN + ES, pilot)

- [x] 1.1 `shared/placement/en-placement.json` + `es-placement.json`:
      core 6/band (pilot floor), listening 3/band, writing 3/band — 60 items
      per language, band-tagged, skill-tagged from the existing catalog.
- [x] 1.2 OPEN GATE (not code): competent-speaker review of both banks
      before calling the content final; mis-banded items get re-banded or
      rewritten (`verify-report.md` tracks it).

## Task 2 — Exporter

- [x] 2.1 `scripts/export_placement.py`: validation (ids unique, one correct
      answer, band/mode/kind coherence, skill ids known, minimums per band)
      and `PlacementData.kt` generation; `--check` mode for drift.
- [x] 2.2 Generated data committed; Android-only in this change (Swift
      mirror is follow-up).

## Task 3 — Engine (pure, JVM)

- [x] 3.1 `PlacementEngine.kt`: ladder (start level, 2/3 windows, floor and
      ceiling verdicts, 18-item cap), item selection (category balance, no
      repetition), final listening/writing blocks with per-breakdown drops,
      result mapping with confidence.
- [x] 3.2 `PlacementEngineTest.kt` (JVM): floor ladder, ceiling ladder,
      up/down windows, cap, non-repetition, block drops, id shapes,
      deterministic ordering.

## Task 4 — App integration (Android)

- [x] 4.1 `PlacementScreen.kt`: core/listening/writing phases with per-answer
      feedback, listening replay via the learning-language TTS, can-do step,
      final screen with suggestion actions.
- [x] 4.2 Learn tab: entry card (with/without result) and placement
      suggestion as the CEFR bar default (suggest, never fix).
- [x] 4.3 Result recording: `assessment` ledger completion (slug-safe id) +
      local `PlacementResultStore`.
- [x] 4.4 "Instructions language" setting (`subtitles`/`en`/`es`) consumed by
      the placement UI via localized context.
- [x] 4.5 Strings EN/ES; Android unit suite green; emulator smoke of the full
      flow.
