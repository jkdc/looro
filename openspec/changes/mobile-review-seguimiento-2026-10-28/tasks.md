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
- Android full app + engine-local suites: BUILD SUCCESSFUL, 0 failures
  (app XML reports ≈832 tests; engine-local 11 up-to-date from its prior
  green run).
- 2026-10-28 emulator end-to-end pass (Medium_Phone_API_36, headless):
  APK assembled with `-Papp.conversationEngine=glm
  -Papp.apiOrigin=http://10.0.2.2:8091` against the Docker stack
  (`make up`, healthz/readyz ok) through a loopback HTTP relay to the api
  container (the app's debug origin policy only accepts plain http; Caddy
  TLS could not be trusted — the AVD image is non-rootable). Screenshots in
  `/tmp/looro-e2e-s0928/`. Covered: launch, guest provisioning (encrypted
  guest installation pref), Talk start flow incl. the minutes-check error
  sheet against the accounts-disabled API and the personal-key/provider
  settings, mic permission grant, Learn (course, CEFR bar, level check full
  flow: content → answers → confirm dialog → on-device deterministic grading
  → scorecard → refreshed history "Saved on this device"), Words empty
  state, Listen (player + question + correct feedback), Themes, Settings
  (learning-language switch EN↔ES with per-language partition and Spanish
  foundations content, provider section, backup section, account sheet
  rendering `google:false` providers from the real API with the
  guest-continuation path). Zero `FATAL EXCEPTION`s; no app ANRs (one
  SystemUI ANR at emulator first boot, before launch).
- Observations (not reproduced as defects): the engine radio rows in the
  provider section did not register taps through adb input in this
  environment, so the compiled engine (glm) could not be explicitly
  re-selected on-device and the live GLM voice turn was not exercised (the
  voice engine itself is covered by LocalVoiceTransportTest 50/50); the
  account sheet's "We couldn't reach Palique" line persists next to the correct providers state and "Refresh" did not re-issue the providers call.
- Not exercised on-device: member sign-in/progress sync (the local stack
  intentionally runs with accounts disabled — `auth/providers` reports no
  providers), live LLM voice conversation, physical microphone audio.
