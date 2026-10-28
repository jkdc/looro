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
