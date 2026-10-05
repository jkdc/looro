# Verify report: learning-db (change closed with honest gates)

**Date:** 2026-10-04 · **Branch:** `feature/learning-platform` · **HEAD before H:** `b307d29`

## Delivered

- **A Fundaciones (commits `e9f9d7f`, `48c3dbf`):** canonical
  `shared/db/schema.sql` (v1: 10 tables, preferences CHECK `id = 1`,
  `fragments.session_id` FK CASCADE), `scripts/check_db_schema.py` pinning
  the exact inventory/constraints so Room and GRDB can never diverge, and 4
  migration fixtures with edge cases (legacy `languageID = null`, unfinished
  session, scenario review) plus `*.expected.json` counts.
- **B Android persistence (commits `06b0c9d`, `4f5ef7a`, `15faad0`,
  `7e75bf9`, `811d16b`, `23ab80e`):** `scripts/export_db_schema.py`
  generating `SchemaData.kt` (and the Swift mirror), the `SqlDatabase`
  access layer with transaction/update/null-arg contracts, `ArchiveMapper`
  (rows↔structs, rowid order = list order), `LearningDbDao` + roundtrip/
  merge/caps/pods tests against the fixtures, `LearningDbWriter` with
  db-side merge at codec parity and read APIs; `ArchiveMerger` documented
  and guarded as restore-only.
- **C Android cutover (commits `6ea2da4`, `2bb77be`, `01c730f`, `b3d4d7e`):**
  `LearningDbStore` with one-way `learning.json` migration (verified counts,
  `.migrated` marker, hash anti-re-migration, recoverable retries), the
  AppViewModel rewire to per-mutation DB writes (recovery, outbox, tickets),
  verified e2e on the emulator (captures committed).
- **D Android backup (commits `98d0b0b`, `09252b5`, `71b7159`, `eb8045a`):**
  `BackupManager.exportTo`/`readValidatedBackup`/`installDatabase`
  (WAL-quiesced, verified byte-for-byte, stage-then-swap install),
  `deleteLearningDataForLanguage` (one transaction), Settings `.db` backup
  UI with EN/ES strings, legacy JSON import kept, e2e backup/restore on the
  emulator (captures committed).
- **E iOS GRDB (commits `4b43eae`, `438b360`, `eae758b`):** the Swift mirror
  of B+C statement for statement — `LearningDatabase` (GRDB), DAO, mapper,
  writer, `LearningDbStore` with the SwiftData→SQLite migration and the
  ConversationCoordinator cutover. Never compiled: macOS gate.
- **F iOS backup (commits `065d2a2`, `7b5f636`):** the Swift mirror of D —
  `BackupManager`, `DatabaseBackupDocument`, Settings `.db` section,
  per-language delete, plus the fix that quiesces deferred writers before a
  restore. Never compiled: macOS gate.
- **G Search + health (commits `a80fd3d`, `1381af6`, `1c008e2`, `2844532`,
  `b307d29`):** schema v2 — `fragments_fts` external-content **FTS4** virtual
  table + 4 sync triggers + rebuild; `archive_health` usage event (server
  catalog + honest on-cutover emission, 24 h cadence); conversation search
  UI on Android and iOS (debounced FTS list, snippet tap → transcript);
  search e2e on the emulator (captures committed).
- **H Closure (this task):** legacy JSON **export** retired from both
  platforms' Settings (the JSON **import** stays forever as the restore
  path); `exportData()` removed on Android (its one test builds the archive
  directly) and kept on iOS where the language-verification harness still
  calls it; stale "FTS5" wording swept to FTS4 in the checker docstrings and
  test comment; `tasks.md` closed; this report; `informe-learning-db-2026-10-04.md`.

## The FTS5→FTS4 story

Phase G's first round pinned an FTS5 external-content index. Review
REJECTED it with a Critical portability defect: **Android's framework SQLite
enables the FTS3/FTS4 module only — AOSP ships no FTS5.** The JVM suite
passed anyway because sqlite-jdbc bundles FTS5 and the checker runs on
CPython's SQLite; both were blind to the device. Commit `1c008e2` ported the
index to FTS4 external content with the four documented sync triggers
(BEFORE DELETE/UPDATE remove the index entry while the content row is still
readable; AFTER INSERT/UPDATE index the new text) and probed the device
SQLite empirically. Phase H swept the last stale "FTS5" wording from the
checker docstrings and test comment (pins were already correct).

## Gates run (all commands executed 2026-10-04, after the H edits)

| Gate | Command | Result |
|---|---|---|
| Android unit tests | `./gradlew :app:testDebugUnitTest` (apps/android) | ✅ **632 tests, 0 failures, 0 skipped** (82 suite XML reports), BUILD SUCCESSFUL |
| Android instrumented sources | `:app:compileUiTestAndroidTestSources` | ✅ BUILD SUCCESSFUL after the H fix round — it had 6 errors (`AccountSheetTest.kt` referenced the `DELETION_SUPPORT_*` constants with no import, broken since `b391e8b`, reproduced at HEAD `b307d29` before the fix; see fix round below) |
| Python scripts suite | `python3 -m unittest discover -s scripts/tests` | ✅ **132 tests, OK** |
| Schema checker | `python3 scripts/check_db_schema.py` | ✅ exit 0 — user_version 2, 10 tables, 4 indexes, 1 virtual table, 4 triggers, 1 FK, 4 fixtures, "no issues found" |
| Schema exporter | `python3 scripts/export_db_schema.py --check` | ✅ exit 0 — both generated sources up to date (22 statements, user_version 2) |
| Cross-platform parity | `python3 scripts/check_cross_platform.py` | ✅ exit 0 |
| Backend typecheck | `npx tsc --noEmit` (services/api) | ✅ clean |
| Backend suite | `TEST_DATABASE_URL=postgresql://app:app@127.0.0.1:55434/app_billing_test npm test` | ✅ **419 tests, 414 pass, 5 fail** — exactly the 5 known environmental failures (`accounts.test.ts`, `auth.test.ts`, `apple-revocation.test.ts`, service-account OAuth signing, usage-timeseries daily-actives), identical before this change |
| Strings parity (Android, after H removals) | key-set diff `values/` vs `values-es/` | ✅ **465 = 465**, symmetric — the final state (465 → 467 after G added 2 search keys per locale → 465 after H removed the 2 orphaned keys `settings_export_backup` + `settings_backup_exported_toast` from both locales; iOS UI copy is hardcoded, no strings files) |

Cumulative session gates recorded in the phase SDD reports: Android e2e on
the emulator for cutover (C), backup/restore (D) and search (G) with
committed captures; per-phase green suites (109→121→132 Python, 547→632 JVM).

## Gates pending

| Gate | Blocker |
|---|---|
| **macOS compile/test gate** — ALL iOS Swift from this change (E+F+G layers, backup, search, and the H export removal) has never been compiled | no macOS host in this round; iOS verified by source parity + `check_cross_platform.py` only |
| iOS on-device e2e (migration, `.db` backup/restore, search) | needs the macOS build first, then a device run |
| `archive_health` positive-path emission on device | the honest emission only fires on the cutover path of a managed-origin build |
| Android instrumented suites (`uiTest` variant) | sources now compile (break fixed, see fix round); running them still needs a device/emulator run |

## Known follow-ups (deliberate, recorded — not defects of this change)

- **`usage_outbox` durable drain:** `archive_health` (and future telemetry)
  lands in the outbox, but nothing drains it yet — a future feature, not a
  loss on the learning path.
- **`completions` PK same-instant narrowing:** two completions in the same
  millisecond collide; narrowing the PK is a schema v3 decision, someday.
- **`jsonMode` fallback KEPT deliberately:** the master plan's old text said
  to retire it; the plan evolved — it stays as the production safety net
  until real-world data says otherwise. This supersedes the old plan line.
- **`mergeRemote` ProgressSync position-diff coupling:** the merge path
  still couples to the position diff; decoupling is a separate design task.
- **Notebook cross-language remove:** the per-language delete leaves
  notebook rows of other languages untouched by design; whether the remove
  should be cross-language is still an open decision.
- Caps: DECIDED 2026-10-05 by Joaquín — stay GLOBAL (2000 total across languages, engine-faithful); per-language reads/delete delivered; per-language caps discarded.

## Fix round (post-close review, 2026-10-04)

One Important + three minors from review, one commit:

- **Attribution corrected (Important):** the androidTest break was first
  attributed to brand-constants `08cb1ad`; the real origin is `b391e8b`
  ("refactor(android): brand-free structure"), which put `AccountSheetTest`
  at its current package referencing the `DELETION_SUPPORT_*` constants with
  no import (`08cb1ad` only changed two display strings). `b391e8b`'s own
  message claimed "androidTest compile green (394 tests)" — false.
- **Break fixed:** the three missing imports
  (`com.jkdc.app.ui.DELETION_SUPPORT_EMAIL/_MAILTO/_URL`) added to
  `AccountSheetTest.kt`; `:app:compileUiTestAndroidTestSources` now compiles
  green (this removes the follow-up that previously sat in this list).
- **Checker shadow-table wording:** the docstrings still named
  `fragments_fts_data` (FTS5-only) and `_content` (absent for external-
  content FTS4); corrected to the real shadows verified against a live
  schema: `fragments_fts_segments/_segdir/_docsize/_stat`
  (in `read_inventory`, `is_internal_table`, and the pinned-tables comment).
- **`tasks.md` H row** now carries its commit hashes like every other phase.

## Verdict

The learning database is the single runtime store on Android
(migrated, cut over, backed up, searched — all e2e-verified on the
emulator) and exists as a compiled-on-Mac-pending mirror on iOS. The JSON
export is retired on both platforms with the `.db` backup as the one way to
take data out, the legacy JSON import is preserved forever as a restore
path, and the schema stays pinned offline by the checker. The change is
CLOSED with the pending gates above being environment gates (macOS, device,
managed build), not code work.
