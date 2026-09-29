# Verify Report: listening-tema-levels

## F1 — Schema, exporter, content (a001ff0)

- `python3 scripts/export_listening.py` regenerates both cores from the six
  rewritten JSONs: **45 texts** (en 15 · es/fr/it/de/pt 6 each), every text
  with **6 questions**, required `topic`, `unitID` validated against
  `shared/courses/<lang>-foundations.json` (existence, language and level
  match), question minimum now 5. `--check` green.
- `ListeningContentTest` (Kotlin) / `ListeningContentTests` (Swift): new
  counts, ≥5 questions, topic presence, level→unit ordering (A1 u1–u4, A2
  u5–u6, extras last), unit-level agreement, every question still grades its
  own answers correct.
- Existing ids untouched → `bestScore` history and the `user_progress`
  server ledger keep validating (ProgressEngineTest unaffected).

## F2 — Listening points (3d4ab3d)

- Android unit: **469/469**, including the rewritten `LevelEngineTest`:
  ladder climb now 7 pts → level 2 with 5 to next, listening points double
  the medal grade from the best score (79/80/100, unknown items never count),
  lesson-only 54 pts → level 7, and 54+24 = 78 → level 10 complete with the
  `lessonPoints`/`listeningPoints` breakdown.
- `check_cross_platform.py` green (the pins still match: ladder,
  listening medal, exercise thresholds — both cores updated in lockstep).

## F3 — Listen tab UI (3e175e5)

- Level sections with heading semantics (`listening-section-<level>` tags),
  "Theme N · <unit> · 6 questions" / "A2 · <topic> · N questions" rows and
  detail line; iOS `ListeningView` mirrored (inline English).
- Strings EN/ES in lockstep: **354/354 keys** (listening_level replaced by
  listening_theme + listening_topic; learn_level_breakdown added).
- Full Gradle suite green after the UI change. One flake in
  `LocalVoiceTransportTest` ("mic is paused during playback") failed once in
  the suite and passed in isolation and in the full re-run — pre-existing
  timing sensitivity, untouched by this change.

## F4 — Verification

- `python3 -m unittest discover -s scripts/tests -t .`: **76/76** (the
  exporter tests updated to the new contract: topic required, 5-question
  minimum, unitID existence/language/level cases, unitID omitted in emitted
  code when absent).
- `python3 scripts/export_listening.py --check` and
  `python3 scripts/check_cross_platform.py` green.

## Pending gates

- macOS `swift test` / xcodebuild for the mirrored core and app UI (repo
  policy; Linux machine).
- Manual emulator pass over the new list (sections, tema labels, six-question
  flow) and the level card breakdown with real history.
- Ladder recalibration moves existing levels: lesson-only 54 pts now reads
  level 7 — accepted pre-release (levels are a pure projection, nothing
  stored to migrate).
