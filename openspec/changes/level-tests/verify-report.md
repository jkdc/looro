# Verify report: level-tests

Date: 2026-10-08 · Branch `feature/learning-platform` · Android-only change.

## What ran green

- **Android unit suite: 663 tests, 0 failures** (`:app:testDebugUnitTest`,
  fresh run). Includes 11 new `LevelTestEngineTest` JVM tests: weekly
  determinism, bounded consecutive-week overlap, fixed section counts and
  ordering, disjoint reading/listening passages per exam, listening stimuli
  present, pass → next-band suggestion, fail → weak sections and units,
  slug-safe ledger ids, missing-attempt tolerance, ISO week index, EN/ES
  parity, `gradeByAttempts` equivalence.
- **Harvest pipeline**: `build_leveltests.py` composed pools for **EN and ES,
  levels A1/A2/B1** (B2/C1 dropped at build time for insufficient
  content — 8/5 and 5/5 written items vs the 9 minimum — per the
  no-empty-promises decision). `export_leveltests.py` validates (sections,
  minimums, reading-ready ≥2, listening-ready ≥2, choice/answer coherence,
  unique ids) and generates `LevelTestsData.kt` (1060 pool items, 71
  passages, 6 levels); `--check` wired into `contracts.yml`.
- **Emulator smoke (Spanish course)**: the "Verify your B1" card renders
  inside the B1 level view with the weekly copy; the exam opens with all
  sections (recognition → reading passages as visual cards → listening
  stimuli with replay buttons → writing fields); answering registers
  (submit stays disabled until every item is answered); the placement card
  and its suggestion keep working alongside.

## Defects caught and fixed during verification

1. **`Method too large` JVM limit**: 1060 items in a single `listOf()` blew
   the 64KB static-initialiser limit — pools are now emitted in 40-item
   chunk functions concatenated per level.
2. **Composer ordering bug**: listening passages were filtered before the
   reading ones were picked, so a week's exam could pair the same passage in
   both blocks — the listening filter now runs after reading selection.
3. **Ledger id case**: `levelcheck-en-A1` violates the server slug rule —
   levels are lowercased when recording.
4. Exporter emission bugs while iterating (multiline level entries dropped
   by the per-bank filter, duplicated `unitID`, missing bank separators) —
   caught by paren-balance + grep checks and fixed; `--check` now locks the
   output.

## Known gaps / open gates

- **UI automation could not drive the full 25-item submission** (the driver
  answered ~everything but the final submit tap needs internal state we
  can't observe); the submit→grade→result path is locked by
  `gradeByAttempts` JVM tests instead. A manual full pass on a real device
  (including TTS listening quality) is the remaining gate.
- **Content review gate**: harvested items inherit the authored content's
  quality; a competent-speaker pass over the composed B1 exam is pending
  (same gate as the placement banks).
- B2/C1 ship no entry until their pools reach the minimums (needs authored
  writing/recognition items — see informe §9 F4).
- The old checkpoint entry was **not** removed yet: that swap is one line
  in `LearnScreen` when the product decision lands (§11.9), kept out of this
  change to isolate review.
- iOS parity and the v2 weekly endpoint (`GET /v1/level-tests/current`,
  ETag cache, offline fallback) are follow-ups per proposal.


## v2 — weekly endpoint (2026-10-08, same day)

- **Shared rotation contract**: the Kotlin engine refactored from
  `java.util.Random` to a language-neutral rotation (sha256 stable order of
  `version|SECTION|id`, ISO week picks a block, trailing remainder chunks
  fall back to the stable order head, listening pool excludes the reading
  picks) so the server composes byte-identical exams. **Golden parity
  asserted on both sides**: the same 25 item ids + reading/listening passage
  ids for (EN, B1, ISO week 202641) in `LevelTestEngineTest` and
  `tests/level-tests.test.ts`.
- **Server**: `src/level-tests.ts` (composer + ISO week + expiresAt) over
  `src/level-tests-content.generated.ts` (emitted by the exporter, null-
  safe, `--check` covers it); `GET /v1/level-tests/current` with ETag/304,
  proxy-exempt; 404 `level_test_unavailable` / 400 `invalid_request` per
  matrix.
- **Verification**: server suite **459/459** (Node 20.19.4, PG 17 ephemeral;
  4 new tests incl. golden parity and HTTP behaviors). Android suite re-run
  green after the engine refactor. Type-check clean.
- Defects caught: composer picked listening passages before reading (overlap
  possible), trailing remainder chunks short-changing an exam, and the
  ledger-id slug rule — all fixed with tests.
- Pending: client consumption of the endpoint (the bundled composition is
  identical by golden tests, so the wire-up only matters once the backend
  deploys curated or newer pools); iOS parity.
