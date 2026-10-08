# Design: placement-test

## Requirements
- Offline, deterministic, no AI in the measurement path (same standing as
  `ExerciseEngine`/`CheckpointEngine`).
- Free and repeatable; the test never gates content and never overwrites a
  manual level choice.
- Zero server/schema change: reuse the `assessment` progress kind and its
  sync; the server validates `itemID` as `/^[a-z0-9][a-z0-9_-]{0,63}$/`, so
  ids are `placement-<lang>-v<version>`.
- Core fits 12–18 items (hard cap 24); whole flow ≤ 6 minutes.

## Band ladder (core phase)
- Start: the recommended lesson's band when the learner has any progress in
  the language (the same signal the Learn tab already computes); A1 from
  zero.
- Per answer, over the **last 3 core answers since entering the band**:
  ≥2 correct → move up; ≤1 correct → move down. Floor A1 (a down at A1 is
  the floor verdict), cap C1 (an up at C1 is the ceiling verdict).
- Stop: 18 core answers, the floor verdict, or the ceiling verdict. The
  current band at stop is the global band.
- Confidence: high (<15 core answers), medium (15–17), low (18 = cap hit).
- Item selection: unused core items of the current band, the category with
  the fewest answers first, bank order as tiebreak. No randomness; a session
  never repeats an item.

## Final blocks (at the resulting band)
- Listening: 3 bank items `mode=listening` of that band; the stimulus
  sentence is spoken with the learning-language TTS voice already used by
  lesson reading (`AppViewModel.readLessonCard` machinery, dedicated entry
  point so it works regardless of the lesson-audio switch). ≥2/3 correct
  keeps the listening breakdown at the global band; below, it drops one band
  (floor A1). Same rule for the writing block (3 items `mode=writing`,
  exact-match grading via `ExerciseEngine.isCorrect`).
- The blocks adjust **their own breakdown only**; the global band comes from
  the core ladder alone.

## Result & persistence
- `PlacementResult { languageID, band, categories, listeningBand,
  writingBand, confidence, source(test|cando), version, at }` stored in a
  local JSON preference (`PlacementResultStore`); the ledger completion is
  recorded for sync/audit. The suggestion in the Learn tab uses the last
  result's band; the ledger keeps the best core percent as history.
- The can-do answers are never saved as a level by themselves unless the
  test was abandoned (then `source=cando`); when a test exists, the can-do
  result is displayed for contrast and the test wins unless the user picks
  otherwise on the final screen.

## Instructions language
- New generic preference `InstructionsLanguageStore`:
  `subtitles` (default) / `en` / `es` — only languages with translated
  strings. The placement UI resolves it via `createConfigurationContext`, so
  no Activity recreation is involved. Future screens may consume the same
  setting.

## UI shape
- `PlacementScreen` mirrors `CheckpointScreen` (same chrome, back button,
  immediate feedback per answer) plus: a phase strip (core → listening →
  writing → can-do → result), a replay button on listening items, and the
  final screen with the suggested band, per-category breakdown, confidence
  sentence, and two actions: "Start at <band>" (applies the suggestion to
  the Learn bar for this session) and "Choose another level" (opens the bar;
  no state change). Re-entering the test is always possible and restarts
  from scratch; the previous result stays until a new one lands.

## Testing
- JVM (`PlacementEngineTest`): floor/ceiling ladders, up/down windows,
  cap-at-18, item non-repetition, category balancing, block-band drops,
  slug-safe ids, result mapping. UI smoke in the emulator; instrumented
  tests unchanged (testTags additive).
