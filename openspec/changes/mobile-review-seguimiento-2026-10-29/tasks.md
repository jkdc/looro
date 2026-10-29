# Tasks: Mobile review follow-up round 3 (N01–N06)

Authority: `informe-revision-seguimiento-app-movil-2026-10-29.md`. Check a task
only after its regression (or documented verification path) is observed.
Swift/iOS execution remains a declared Linux limitation, not a completed proof.

## Implementation

- [x] N01 — `legacyResidueScope` provenance classifier: with the gate open and
  no pending claim, only a fresh install or same-owner residue may claim;
  other-owner or unattributed content (any non-namespaced mastery row
  included) refuses the migration and the open quarantines with the database
  and source untouched (the abort message names the failing probe).
  Regressions: `mixedLegacyResidueWithoutClaimIsQuarantinedNotClaimed` (RED
  then GREEN, covers the B attempt, preservation on both sides, no
  fabricated claim, unmodified source bytes, and the refusal surviving a
  reopen as A) and `legacyResidueOfTheOpeningOwnerAloneCanResume` (GREEN;
  pins that demonstrated same-owner residue still recovers).
  `contentFailureRollsBackTheWholeMigrationAndKeepsThePendingClaim` moved to
  the fresh-install path (rollback + surviving claim). Focused
  LearningDbStoreTest 24/24 GREEN.
- [x] N05 — a capture-epoch change (mute toggles) clears `openingFrames`,
  `openingBufferedSamples` and `openingCaptureFull` together with the
  ordinary buffer. Regressions: `mute toggles during a full opening buffer
  clear it for the post unmute phrase` (RED then GREEN: exactly greeting +
  one reply, STT receives only the post-unmute phrase) and `mute before the
  opening saturates keeps the buffer usable after unmute` (toggle-coverage
  guard). LocalVoiceTransportTest 52/52 GREEN.
- [x] N03 — `ProgressProjection.identityConsistent` (pure, unit-tested in
  `identityConsistencyGateFailsDuringSwitches`) and the
  `remoteProgressForLanguage` fence: remote rollups are consulted only when
  requested == loaded == active database owner; otherwise the empty/loading
  projection is served. Mounted verification happens through the emulator
  pass (no member bootstrap exists on the accounts-disabled local stack).
- [x] N02 — iOS `LearningDbStore` adopts the full single-transaction
  contract (clear + `ArchiveMapper.importInTransaction` + owner-scoped
  `verifyCounts` with explicit LIKE bind + metadata + pending-claim removal),
  writes the pending claim with the foreign-claim skip, and refuses
  unclaimed ambiguous residue like N01; `markMigrated` stays post-commit.
  Tests added: `testMixedLegacyResidueWithoutClaimIsQuarantinedNotClaimed`,
  `testLegacyResidueOfTheOpeningOwnerAloneCanResume`; the legacy
  `testCountMismatch...` was re-pinned to total rollback. Source + byte-level
  review only — NO Swift/Xcode execution (Linux); macOS gate deferred.
- [x] N04 — `activateAccountAndSync` is the unified idempotent transition
  (adopt → invalidate → sync), a refused `begin()` registers
  `pendingSyncSubstituteAccountID`, and
  `schedulePendingSyncSubstitute()` re-runs it when the active operation
  releases (self-fencing re-read); `invalidateProgressSync` clears the fence
  key. Controlled-scheduling XCTest execution deferred to macOS with the
  rest of the iOS gate.
- [x] N06 — after a successful remote account DELETE and before announcing,
  `keychain.markCredentialRejected()` blocks every load fail-closed even if
  `remove()` then fails (session empty, marker active, pending-cleanup
  message). Regression `testAccountDeleteMarksCredentialRejectedEvenWhenKeychainRemovalFails`
  (transport stub gained the DELETE branch; asserts notification, blocked
  independent load, attempted removal, communicated cleanup). A remote
  DELETE failure still does not mark anything. XCTest execution deferred.

## Verification log

- 2026-10-29: focused suites GREEN after each fix (LearningDbStoreTest
  24/24; LocalVoiceTransportTest 52/52; ProgressProjectionTest green).
- 2026-10-29 gates: full Android app + engine-local suites BUILD SUCCESSFUL
  (839 app tests, 0 failures/errors — one earlier full run had the known
  scheduling-sensitive `cloud turn processed` flake plus a flaky first
  version of the new mute test; see below — and the rerun was clean);
  `make check` exit 0 (148 Python tests, exporters/parity);
  `:app:lintDebug :app:assembleDebug` (engine=glm,
  apiOrigin=local-docker-relay) exit 0 with the APK rebuilt for the
  emulator pass.
- N05 test hardening: the first version fed a 0 ms frame burst, which
  artificially overflows the handoff (the same artifact the 28/10 report's
  probe already documented); the final test paces the producer at 1 ms per
  frame and tops up the fill until the limit event, then passed 6/6
  consecutive focused runs and the full suite. A temporary internal
  `captureWindowState` snapshot was added to the transport for the triage
  and kept as a one-line diagnostic.
- 2026-10-29 emulator end-to-end re-pass (Medium_Phone_API_36, Docker stack
  + loopback relay, rebuilt APK with engine=glm and the local debug origin):
  launch with existing local data (no quarantine — the committed migration
  is recognized), Learn renders the loaded identity's marks through the N03
  fence, learning-language switch EN↔ES (per-language partition: ES shows
  Level 0 with 0 points), level check full cycle (fresh attempt → answers →
  unanswered-questions confirm dialog → on-device deterministic grading →
  scorecard → history refreshed to "Score: 8% · Oct 29"), and the relay log
  recorded the app's live `/v1/auth/providers` traffic. Zero `FATAL
  EXCEPTION`s across the whole pass. Observation for a future round (not
  one of N01–N06, pre-existing): the ES level-check catalog card surfaces
  the EN Oct 28 attempt as "score summary only", so that catalog's attempt
  summary does not appear language-scoped.
- Not exercisable on-device (unchanged from 335f030's pass): member
  sign-in/sync (accounts disabled on the local stack by design), live LLM
  voice conversation (engine radio rows unresponsive to adb input), and the
  iOS runtime gates (no macOS).
