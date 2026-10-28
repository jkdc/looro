# Verification report: F09–F10 Android voice

**Outcome:** F09 and F10 are implemented in code commit `53fb67d`. Local Android verification passed; iOS/Xcode and physical microphone verification remain deferred to F16.

## Evidence

| Phase | Regression coverage | Observed result |
|---|---|---|
| F09 / R11 | Silent wait followed by long manual speech; retained audio through the bounded cap; manual overflow notice; no auto-submit; explicit end | RED observed for the absent limit event, then focused tests and `LocalVoiceTransportTest` passed. Independent scratch verified R11. |
| F09 / R12 | Distinguishable low-amplitude onset in a >18 s utterance | The STT input stayed within 18 s and retained the onset; independent scratch verified R12. |
| F10 / R13 | Instructions gate greeting; block the greeting provider, inject voice, verify no premature request, then confirm voice becomes the next request with greeting context | RED first exposed the missing gate, then independent review exposed dropped opening-period audio. The corrected bounded buffer passed the targeted independent scratch recheck. |

Commands run from `apps/android` (using `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/var/tmp` to avoid the host `/tmp` quota issue):

```bash
./gradlew --offline --no-parallel --max-workers=1 :app:testDebugUnitTest \
  --tests 'com.jkdc.app.LocalVoiceTransportTest.manual capture excludes wait silence preserves onset at limit and notifies without auto submit' \
  --tests 'com.jkdc.app.LocalVoiceTransportTest.manual frame overflow keeps queued speech until explicit end and signals limit'

./gradlew --offline --no-parallel --max-workers=1 :app:testDebugUnitTest \
  --tests 'com.jkdc.app.LocalVoiceTransportTest.opening greeting waits for initial instructions before processing voice'

./gradlew --offline --no-parallel --max-workers=1 :app:testDebugUnitTest \
  --tests 'com.jkdc.app.LocalVoiceTransportTest'

./gradlew --offline --no-parallel --max-workers=1 :app:testDebugUnitTest
```

The focused tests, complete `LocalVoiceTransportTest` class, and full Android app unit suite all returned `BUILD SUCCESSFUL` on the committed candidate. Independent verification used fresh `/var/tmp` snapshots; after the one bounded R13 correction, its recheck passed 1/1. The corrected candidate diff hash was `bce4c5c2a35af3c00ee7f7ed3efe91eff78f25737eaf4eba875ab525566f3b2c`.

## Limitations

- No device/emulator microphone run or mounted UI/audio accessibility verification was performed; retain these for F16.
- iOS behavior and Swift/Xcode verification were not part of F09–F10 and remain deferred as requested.
- The existing uncommitted mobile-audit documentation/artifacts were not staged or included in the voice work-unit commit.

**Risk:** item 4 — asynchronous microphone capture, bounded queue overflow, greeting completion, and first-turn ordering.

## F11 — Android manual mute during pending work

**Outcome:** Implemented in code commit `c67c2cd`. While a voice session is active, the manual mute control is enabled even if unrelated provider work is pending; outside the active state it remains disabled/absent. The mute handler changes microphone capture state without cancelling the tutor operation.

**Evidence**

- Independent fresh-scratch inspection at `/var/tmp/f11-verify.2cziWr` confirmed the `TalkScreen` gate depends on active session state rather than `working`, and `toggleMute` updates mute/transport state without clearing pending work or cancelling the turn.
- Scratch commands `:app:compileDebugKotlin`, `:app:compileUiTestAndroidTestKotlin`, and `:app:testDebugUnitTest --tests '*AppViewModelTest'` passed; the focused ViewModel suite reported 14/14 tests.
- Parent `:app:compileUiTestAndroidTestKotlin` passed after adding a state assertion that an already-pending tutor-turn indicator remains pending through mute/unmute. `git diff --check` passed before commit.
- Connected `ManualMuteUiTest` was not executed: the emulator appeared booted but Gradle reported “Unknown API Level / No compatible devices connected.” The test compile succeeded.
- The full `make android-build` was attempted and failed at `:app:testDebugUnitTest`: 824 tests completed, 190 failed, predominantly SQLite native-load and disk-quota errors. This is recorded as a failed environment run, not a green suite and not evidence of an F11 regression.

**Remaining proof:** no live blocked-provider scenario or tutor-audio playback continuation was exercised. Re-run connected UI and actual voice interaction in F16 when the Android device/test environment is available.

**Risk:** item 4 — concurrent microphone mute and pending tutor work.

## F14 — iOS pronunciation owner/data-generation fencing

**Outcome:** Implemented in code commit `33066e6`. Each pronunciation attempt snapshots the active learner identity and `LearningStore.dataGeneration`; account switches, full deletion, validated DB import, user-facing backup import and successful per-language deletion advance the generation. The controller observes invalidation, cancels the attempt and verifies the snapshot again before recording completion or invoking the success callback.

**Regression coverage added**

- `testOwnerSwitchFencesStalePronunciationAndAllowsCurrentOwnerRetry` covers stale A→B recognition plus a valid current-owner retry.
- `testReturningToSameOwnerDoesNotReviveOldPronunciationAttempt` ensures A→B→A cannot revive the original attempt.
- `testDataDeletionFencesSuspendedPronunciationResult` checks a late result after full deletion and owned resource cleanup.
- `testBackupImportFencesSuspendedPronunciationResult` checks a late result after backup replacement.

**Observed checks and limitations**

- Test-first regression tests were added before the production edits. `swift test --package-path apps/ios --filter PronunciationCheckControllerTests` exited 127 because `swift` is not installed; no RED/GREEN, XCTest, compile or runtime result is claimed.
- Independent source review found missing invalidation after user-facing backup import and per-language deletion. One bounded correction added generation advancement at both successful mutation boundaries and a backup-import late-callback regression. The scoped recheck closed the source blocker; direct per-language deletion regression remains advisable.
- `git diff --check` passed. iOS targets and mounted account/import/deletion lifecycle remain deferred to F16 on macOS/Xcode, as requested.

**Risk:** items 1 and 4 — learner data integrity and asynchronous recognition lifecycle.

## F15 — iOS audio-session ownership

**Outcome:** Implemented in code commit `73f6630`. Conversation and pronunciation now share a main-actor exclusive UUID lease. A component cannot replace the audio category while the other owns it, stale tokens cannot release a newer owner's lease, and prior category/mode/options are restored before lease release. A failed deactivation or restoration retains ownership; the next cleanup/acquisition can retry rather than allowing an overlapping owner. LiveTransport's async connect failure cleanup is bound to its attempt token.

**Regression coverage added**

- `testSharedAudioSessionLeaseIsExclusiveAndRequiresExactReleaseToken` covers cross-owner exclusion and exact-token handoff.
- `testStaleAudioSessionReleaseCannotReleaseNewOwner` prevents an obsolete release from ending a later lease.
- `testFailedAudioDeactivationKeepsLeaseUntilCleanupSucceeds` verifies failed deactivation cannot admit a competing owner before successful cleanup.

**Observed checks and limitations**

- `git diff --check` passed. `swift test --package-path apps/ios --filter PronunciationCheckControllerTests.testSharedAudioSessionLeaseIsExclusiveAndRequiresExactReleaseToken` exited 127 because `swift` is unavailable; no XCTest or compilation result is claimed.
- Independent scratch review found the deactivation-failure release bug; the one bounded correction and scoped recheck closed it. Conversation/pronunciation hardware routing, TTS, backgrounding and system-interruption behavior remain deferred to F16 on iOS hardware/macOS.

**Risk:** item 4 — shared asynchronous audio ownership and cleanup.

## F12 — iOS typed reply edits and pending state

**Outcome:** Implemented in commit `832add8`. The editor captures the exact submitted text, retains later edits and exposes recoverable status while that provider call is active. Dismissing the sheet no longer pretends to cancel the provider operation; its draft/error survives presentation changes but is cleared at owner, language, or conversation invalidation. A late result cannot dismiss a new editor presentation.

**Regression coverage added**

- `testSuccessfulSendPreservesDraftEditedWhileProviderIsPending` verifies A remains the sent message while draft B remains editable and is not dismissed after A succeeds.
- `testFailedSendPreservesDraftEditedWhileProviderIsPending` verifies B and the recoverable provider error remain after A fails.
- `testInFlightReplyStateRemainsVisibleUntilProviderReturns` closes/reopens editor state while the provider is suspended, verifies draft rehydration and the in-flight gate, then releases the gate and allows a subsequent send. This is model-level, not a mounted SwiftUI test.
- `testInFlightSubmitShowsRecoverableMessage` pins feedback for a raced `.inFlight` result.
- `testInvalidatingReplyContinuationClearsDraftAndError` ensures invalidation does not leak retained text or errors to a new owner/context.
- `testLateReplyCannotCloseNewSheet` verifies the disappeared editor is no longer current when the old completion arrives.

**Observed checks and limitations**

- Test-first was followed in order, but `swift test --package-path apps/ios --filter TypedReplyTests` could not start: `swift: command not found` (exit 127). Therefore no Swift compilation, XCTest RED/GREEN, or mounted SwiftUI result is claimed.
- Independent source verification in fresh scratch `/var/tmp/ios-r15-final.ne9qnZ` found and prompted correction of retained draft/error across account invalidation; the final bounded recheck reported no blockers and confirmed the three scoped files matched the candidate.
- Parent `git diff --check` passed before commit. The full `AppTests` target, keyboard/focus/accessibility, actual close/reopen sheet lifecycle, and provider late-completion interaction are deferred to F16 on macOS/Xcode.

**Risk:** item 4 — asynchronous provider completion, presentation lifecycle, retry and owner/context invalidation.

## F13 — iOS rejected credential after Keychain deletion failure

**Outcome:** Implemented in commit `9bc45dd`. The rejected session is fenced at the shared credential-read boundary with a non-secret, storage-scope-specific UserDefaults marker. On exact `sign_in_required`, the store clears in-memory session/profile, marks rejection before attempting Keychain deletion, and posts session invalidation regardless of deletion success. A successfully persisted replacement session clears the marker; failed replacement persistence does not.

**Regression coverage added**

- `testRejectedCredentialRemainsBlockedAfterKeychainRemovalFailureAndRestart` simulates Keychain deletion failure, confirms the old raw session remains but is masked from consumers, checks invalidation notification/helper reset, and reconstructs a store that refuses the stale session with a recoverable message.
- `testSuccessfulSignInClearsRejectedCredentialMarker` verifies failed replacement persistence keeps the marker and a later successful sign-in clears it.
- `testRejectedCredentialMarkerPersistsAcrossInstancesAndIsScopeSpecific` checks the production UserDefaults marker across separate keychain wrapper instances and ensures a distinct scope is not blocked.
- Existing `testActualCredentialRejectionClearsSessionAndNotifiesInvalidation` retains coverage for successful Keychain removal and notification.

**Observed checks and limitations**

- Test-first regression files were authored before production edits, but `swift test --package-path apps/ios --filter SessionAdoptionTests` exited 127 (`swift: command not found`); no RED/GREEN, compile, or XCTest pass is claimed.
- Independent source verification in `/var/tmp/ios-r16-review.2jtfeW` initially caught a missing fake persistence protocol member; the correction added that witness and additional scope/failure coverage. The one bounded recheck in `/var/tmp/ios-r16-recheck.BCwvXZ` found the blocker closed. Parent `git diff --check` passed before commit.
- Swift/Xcode AppTests, real Keychain removal-failure behavior, process-restart, and mounted account-consumer proof are deferred to F16 on macOS. The memory-backed test reconstructs a new store and the marker test reconstructs its wrapper; neither is a process-restart test.

**Risk:** item 2 — rejected bearer credentials and authentication consumers.
