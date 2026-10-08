# Tasks: level-tests

## Task 1 — Harvest pipeline

- [x] 1.1 `scripts/build_leveltests.py`: harvest lesson exercises +
      checkpoint questions + listening texts per (language, level); dedupe;
      unit provenance; per-section minimums; writes
      `shared/leveltests/<lang>-leveltests.json` (EN + ES; levels below
      minimums are dropped, C1 included only where it qualifies).
- [x] 1.2 `scripts/export_leveltests.py`: re-validation + `LevelTestsData.kt`
      generation with `--check`; wired into `contracts.yml`.

## Task 2 — Engine (pure, JVM)

- [x] 2.1 `LevelTestEngine.kt`: seeded weekly composition (fixed section
      counts, reading passages ×3, listening from distinct stimuli),
      `IsoFields` week index (injectable), grading with overall + section
      floors, next-band suggestion, weak-unit extraction, slug-safe ledger id.
- [x] 2.2 `LevelTestEngineTest.kt` (JVM): determinism, week rotation with
      bounded consecutive overlap, counts/order, floors, pass suggestion,
      reinforce extraction, bank parity EN/ES, dropped levels absent.

## Task 3 — App (Android)

- [x] 3.1 `LevelTestScreen.kt`: exam mode (no mid-feedback), section labels,
      final review with verdicts + explanations, result with directional
      suggestion and reinforce list; session survives rotation; instructions
      language setting respected.
- [x] 3.2 Learn integration: per-level entry card under the level bar with
      the always-visible last result; pass jump action applied through the
      level bar (suggest, never fix).
- [x] 3.3 Persistence: `LevelTestHistoryStore` + ledger `assessment`
      completion per attempt; strings EN/ES.
- [x] 3.4 Android unit suite green; emulator smoke of entry → exam → review

## Task 4 — v2 weekly endpoint (server)

- [x] 4.1 `scripts/export_leveltests.py` also emits the server content mirror
      (`src/level-tests-content.generated.ts`, null-safe, `--check` covers it).
- [x] 4.2 `src/level-tests.ts`: language-neutral weekly composer (sha256
      stable order + week blocks, remainder falls back to the head; reading
      picked before listening so a text never appears in both blocks of one
      exam) — the Kotlin engine refactored to the same algorithm.
- [x] 4.3 `GET /v1/level-tests/current?language=&level=`: composed weekly test
      with `weekId`/`expiresAt`/thresholds, ETag + 304 revalidation, proxy-
      exempt like `/v1/pricing`; 404 `level_test_unavailable` for unknown
      pools, 400 for missing params. Grading stays on device.
- [x] 4.4 Golden parity: the same 25 item ids for (EN, B1, ISO week 202641)
      asserted by BOTH the Kotlin and TypeScript composers; server suite
      green (459/459, Node 20, PG 17 ephemeral).
      → result → card updated.
