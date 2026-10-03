# Phase D, task 2 — Backup/restore + per-language delete e2e (2026-10-04)

Device: emulator-5554 (Medium_Phone_API_36, API 36, cold-booted — see
"Environment incident" below). App: `com.jkdc.palique.android` 0.1,
debug build of 71b7159 (`feat(db): backup ui and settings strings`),
installed fresh via `adb install -r` before the run.

Pre-state: learning.db with 4 sessions (es: 3, fr: 1), 14 fragments,
4 expressions, 2 lesson_state rows, 6 completions (1 marker created
during this run: a "Questions & negatives" exercise completion, written
before the export), 2 assessment_tickets, user_version 1.

## (a) Export .db via UI → pull → python3 verify — PASS

- b1-db-section.png — Settings → "BACKUP (DATABASE)" group with
  "Export database backup (.db)", "Restore database backup (.db)" and the
  red "Delete a language's learning data"; legacy JSON entries still in
  "YOUR WORDS BELONG TO YOU" above (retired in H, as planned).
- b2-saf-create.png — SAF CreateDocument with suggested name
  `palique-backup-20261004.db` (palique-backup-YYYYMMDD pattern), mime
  `application/x-sqlite3`.
- b3-after-save.png — back on Settings after SAVE.
- Pulled `/sdcard/Download/palique-backup-20261004.db` (114 688 bytes).

Verification (python3 sqlite3): user_version 1 in both; integrity_check
ok; table lists identical (10 canonical tables + android_metadata +
sqlite_sequence); every table's row count identical to the live DB pulled
just before the export — including the 4 sessions created before the
export and the completions=6 marker written minutes earlier — and the
exported file is BYTE-IDENTICAL to the live learning.db (sha256 match),
proving the WAL TRUNCATE checkpoint put every committed page in the main
file. Evidence file: palique-backup-20261004.db (this directory).

## (b) pm clear → onboarding → restore from .db via UI — PASS

- b4-after-pm-clear-launch.png — `pm clear` + relaunch: onboarding.
- b5/b6/b7 — Spanish onboarding, consent gate ("Not now"), fresh empty
  state (pulled fresh.db: 0 sessions, 0 completions).
- b8-saf-open.png — Restore database backup → SAF OpenDocument picker
  (mime filter application/x-sqlite3 + application/octet-stream) showing
  the backup in Downloads.
- b9-after-restore.png — back on Settings; store reloaded.
- b10-history-restored.png — Past conversations shows the restored
  "Commander au café" (French) conversation.

Verification: the post-restore learning.db is byte-identical to the
exported backup (sha256), no .preimport/.install-tmp leftovers, all
counts back (sessions 4, fragments 14, completions 6, expressions 4,
lesson_state 2, tickets 2, meta 5).

## (c) Invalid files → rejection notice, live data intact — PASS

Three invalid files pushed to Downloads and offered to the restore UI:

- c1-corrupt-toast.png — 4 KB of random bytes → toast "That file isn't a
  valid learning database." (notice_backup_invalid, BackupIssue.CORRUPT).
- c2-future-version-toast.png — copy of the backup with PRAGMA
  user_version=2 → toast "This backup is from a newer version of the app.
  Update the app and try again." (notice_backup_future_version,
  BackupIssue.FUTURE_VERSION).
- not-learning.db (valid SQLite, wrong tables) — covered by the same gate
  (MISSING_TABLES); unit-tested in BackupManagerTest, not screenshotted.

Verification after BOTH rejections: sha256 of the live learning.db
unchanged (identical to the restored copy) — the validation gate rejects
before touching the live file.

## (d) Delete a language's learning data (2-language state) — PASS

- d1-language-picker.png — "Delete which language?" radio picker.
- d2-confirm-dialog.png — danger confirm naming the language:
  "Delete French learning data?" / red "Delete French data".
- d3-after-delete-toast.png — toast "French learning data deleted."
- d4-french-history-empty.png — French (still the learning language) →
  Past conversations: "Your conversations will appear here."
- d5-spanish-history-intact.png — switched the learning language to
  Spanish → Past conversations shows all 3 Spanish conversations.

DB pull after the delete (after-delete.db in the run log):

- sessions: only es (3) — the fr session is gone.
- fragments 14 → 11 (the fr session's fragments followed by CASCADE).
- completions: (NULL, 1) + (es, 3) — fr row deleted, legacy NULL row
  conserved by design.
- expressions: (NULL, 1) + (es, 2) — same semantics.
- lesson_state: only es-foundations-u1-ser (fr-foundations-u1-etre gone).
- assessment_tickets 2 (both reference es sessions — nothing to delete),
  meta mastery rows and preferences conserved by design.

Final state: the backup was restored once more through the UI after the
delete (restore-after-delete exercise); the device is back to the full
4-session state, sha-verified.

## Honesty notes (manual remainder)

1. No NEW LLM conversation session could be created in this run: hosted
   free minutes are currently unavailable (the app shows the paywall
   "Free minutes currently unavailable") and no personal OpenAI key is
   configured in this test environment. The "session created before
   export" freshness proof therefore uses (i) the 4 sessions created in
   the phase-C run (all before the export) and (ii) a completions row
   written during THIS run via a local lesson exercise (no LLM needed).
   The export pipeline itself is fully exercised (checkpoint + verify +
   copy + byte-identity).
2. The isRunning UI guards (rows disabled during a live conversation and
   the ViewModel refusal notices) could not be screenshotted for the same
   reason: no live conversation can be started in this environment right
   now. The guards are covered by unit tests (AppViewModelTest
   everyBackupRejectionReasonMapsToADistinctNonZeroResource; refusals
   refuseBusyNotice/refuseStorageNotice) and by the SettingsScreen
   `enabled = !vm.isRunning` wiring reviewed in the feature commit.
3. The engine-radio section in Settings shows no selection in this build
   (CONVERSATION_ENGINE=openai WebRTC build — the radios are inert
   there); not a backup-feature concern, noted only because it was
   investigated on the way.

## Environment incident (not a product bug)

While installing the build, the emulator's system_server entered a
watchdog crash loop (~every 3 min, "Blocked in handler on main thread"),
which also broke `adb install` (Broken pipe / freeStorage NPE). A plain
`adb emu kill` + start LOADED the stale quickboot snapshot and resumed
the wedged state (guest uptime 3h). The fix was a true cold boot with
`emulator -avd Medium_Phone_API_36 -no-snapshot`, after which the install
succeeded on the first try and the system stayed stable for the whole
run. The staged `default_boot` snapshot was the wedge carrier; userdata
was never wiped (all app data survived).

Helper: ui.py (uiautomator dump/tap/screenshot driver, reused from the
phase-D task-1 session).
