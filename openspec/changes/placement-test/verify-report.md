# Verify report: placement-test

Date: 2026-10-08 · Branch `feature/learning-platform` · Android-only change
(iOS mirror is follow-up, see proposal).

## What ran green

- **Android unit suite: 652 tests, 0 failures, 0 skipped** (`:app:testDebugUnitTest`,
  fresh run with all changes). Includes the 10 new `PlacementEngineTest` JVM
  tests: floor/ceiling ladders, up/down windows (three-answer window), cap,
  item non-repetition, category balancing, block-band drops, id shapes,
  session reconstruction, EN/ES bank parity.
- **Exporter**: `scripts/export_placement.py` validates both banks (ids,
  kinds per mode, band/mode minimums, skill ids against the catalog, answer
  coherence) and generates `PlacementData.kt` (120 items, 2 banks);
  `--check` clean and wired into `.github/workflows/contracts.yml`.
- **Emulator smoke (Pixel 9 API, Spanish course), full walk-through**:
  entry card under Today's training → test opens with the ES bank at A1 →
  answered correctly with feedback + explanation → back mid-session lands on
  the can-do step (always-offered rule) → B2 can-do → result screen
  ("Estimated level: B2 · Based on your self-assessment · rough-guide
  confidence · not-a-certificate footnote") → "Start at B2" applies the
  suggestion and the Learn catalog re-renders at B2 → entry card now reads
  "Estimated B2 · Oct 8, 2026 · retake" (persisted across process restarts).

## Defects caught and fixed during verification

1. Stale suggestion: the Learn card remembered the result without a key and
   kept the pre-test text after returning — now re-read on return
   (`remember(placementOpen)`).
2. Can-do result saved with `at = 0` ("Jan 1, 1970") — now stamped at
   creation.
3. Kotlin nested-comment gotchas (`placement/*.json` inside KDoc) — reworded.

## Known gaps / open gates

- **Content review (blocking for release, not for code)**: both item banks
  are pilot-authored; competent-speaker review of banding and answer
  validity is still pending (tasks 1.2). The in-test "explain" copy inherits
  this.
- Listening stimuli use the device TTS in the learning-language voice;
  quality varies per device — manual listening check on a real device is
  still pending (design §Final blocks).
- The cando-only result records **no** ledger completion (local suggestion
  only), by design; only `source=test` writes the synced `assessment` entry.
- iOS parity (`PlacementData.swift`, engine + screen) and the remaining
  language banks are follow-ups (`proposal.md`, out of scope here).
- Instrumented (on-device) tests do not cover the new screen; the smoke was
  manual/automated UI driving on the emulator.
