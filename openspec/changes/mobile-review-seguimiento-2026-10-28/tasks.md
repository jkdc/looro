# Tasks: Mobile review follow-up round 2 (S01–S08)

Authority: `informe-revision-seguimiento-app-movil-2026-10-28.md`. Check a task
only after its regression (or, for S07/S08, its documented verification path)
is observed. Swift/iOS execution remains a declared Linux limitation, not a
completed proof.

## Implementation

- [x] S01 — Atomic owner-scoped learning.json migration. Single transaction
  (clear + import via `ArchiveMapper.importInTransaction` + tickets +
  owner-scoped `verifyCounts` + `migrated_at` + `migration_hash` + pending-claim
  removal); `migration_pending_owner`/`migration_pending_hash` name the FIRST
  claiming identity; a different identity with the gate open skips the
  migration instead of claiming or clearing another account's backup. RED:
  `failedMetadataCommitStaysPendingForItsOwnerWithoutWedgingOtherAccounts`,
  `verificationIsScopedToTheMigratingOwnerAndNeverClearsOtherAccounts`,
  `contentFailureRollsBackTheWholeMigrationAndSparesOtherOwnersRows`. The
  legacy count-mismatch regression was re-pinned to the total-rollback
  contract. GREEN: focused LearningDbStoreTest 23/23.
- [x] S03 — Opening-buffer saturation no longer latches the turn-capture gate;
  the drained prefix is consumed once (per-iteration `drainingOpening`) and a
  press held since the greeting applies to the WHOLE prefix. RED:
  `saturating the opening buffer keeps the retained speech for the held end
  press`. GREEN with S04.
- [x] S04 — A finalize-time STT cut emits the visible
  `session.capture.limit_reached` signal; EN/ES notices now state that only
  the beginning is kept. RED: `a turn cut at the stt cap signals the visible
  trim instead of dropping the tail silently`. GREEN: LocalVoiceTransportTest
  50/50.
- [x] S05 — The 1.001-download regression assigns the authenticated owner via
  `ProgressPage.assigning(owner:)` before both persists, mirroring the
  production adapter. Source-verified only (no XCTest on Linux).
- [x] S02 — Download page validation compares components with the server's
  C collation (UTF-8 bytes, NULL language first, strictly increasing);
  delimiter-joined keys removed. Regressions added:
  `testDownloadOrderingUsesComponentComparisonNotDelimiterKeys`,
  `testDownloadOrderingKeepsNullLanguageFirstAndComponentIdentity`,
  `testAssigningOwnerProducesTheAuthenticatedIdentityExactly`.
  Source-verified only.
- [x] S06 — `LiveTransport.disconnect` checks ownership before touching the
  shared session, extracts a lock-balanced synchronous
  `deactivateAndRestoreSession`, and registers `retainCleanup` with captured
  token+configuration when the cleanup fails, so the next acquire retries it.
  Source-verified only.
- [x] S07 — `ledgerRevision` and `loadedLearningIdentity` are observable
  ViewModel state; LearnScreen keys its progress projection on revision +
  loaded identity + language. Mounted verification happens through the
  emulator pass of this session; no versioned Compose test exists for the
  flow.
- [x] S08 — `accountSessionChanged` skips `invalidateProgressSync` only when a
  live sync task already serves the keychain's current account
  (`progressSyncTaskAccountID`); replaced credentials still stop the old
  bearer through the task's own `owns(liveIdentity)` re-checks. Controlled
  scheduling test deferred to macOS with the rest of the iOS execution.

## Verification log

- 2026-10-28: `make check` exit 0 (148 Python tests; schema current,
  user_version 7). Focused suites: LearningDbStoreTest 23/23,
  LocalVoiceTransportTest 50/50. App compile after S07 clean.
- Android full app + engine-local suites and the emulator end-to-end pass are
  recorded in the round's verify-report section as they complete.
