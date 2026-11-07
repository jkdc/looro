# Proposal: talk-pause-resume

## Intent

While a voice conversation is running, leaving the Talk tab must stop its
audio immediately; returning to the tab must offer to continue the
conversation where it stopped (resume) or start over (restart). Typed replies
must always continue the current conversation instead of silently doing
nothing or opening a new one. The chosen app UI language must survive app
restarts on both platforms. Finally, deploying the debug app to a connected
phone against the local lab API (with the test engine keys) should be one
`make deploy` command.

## Problem

- **Audio leaks across tabs.** `TalkScreen` (Android) has no
  `DisposableEffect`; the transport lives in the activity-scoped
  `AppViewModel`, so switching tabs keeps the WebRTC playout or local TTS
  playing — and the mic capturing — indefinitely (other screens like
  `ListeningScreen` already stop their audio on dispose). iOS is identical:
  nothing hooks the tab change, `ConversationCoordinator` and `LiveTransport`
  keep running, and the only lifecycle hook is scenePhase→background.
- **Resume is implicit but half-broken.** Returning to the Talk tab reattaches
  the live session (state lives in the ViewModel / root-owned coordinator),
  but there is no way to stop it without ending it, and no restart affordance
  while it runs. After an end, `sendTyped` silently no-ops or starts a fresh
  typed conversation.
- **Typed continuation.** Android `sendTyped` appends to the active session
  but returns silently when the state is connecting/closing/paused; iOS
  `sendTyped` requires `.active`. The user expectation: typing from the Talk
  screen continues the current conversation.
- **Language persistence.** Android wires appcompat per-app locales
  correctly (service + localeConfig, no reset paths), and iOS uses
  `@AppStorage`; neither has a regression test proving persistence across
  recreation, and the user reports it not sticking on their phone.
- **Deploy.** `apps/android/scripts/deploy-local.sh` and `adb-device.sh`
  exist but no Makefile target calls them; `lab-install`/`android-install`/
  `android-run` use bare `adb` and fail when a phone and an emulator are
  both attached; the lab flow does not push the test engine keys or grant
  the microphone.

## Approach

- **Pause/resume/restart (both platforms).** New `paused` state. Leaving the
  Talk tab while a voice session runs pauses it: graceful transport close
  (`transport.close()` / `transport.disconnect()`), session record kept, no
  15-second auto-reset. Returning shows the transcript with **Resume**
  (reconnect the transport feeding the same session's history — no new
  session) and **Restart** (close the pause and start a fresh voice
  conversation). Both platforms get the same semantics; iOS has no reliable
  "silence inbound audio without disconnecting" (WebRTC owns the audio
  unit), which is why pause closes the transport on both.
- **Typed continuation.** `sendTyped` accepts the paused state on both
  platforms: the user fragment and the helper reply append to the same
  session; the state stays paused; no data-channel commands.
- **Language persistence regression.** Instrumented Android test (select a
  language → recreate the activity → assert localized UI) plus a documented
  manual check on the phone; iOS persistence is `@AppStorage` (already
  covered by code reading — no runtime test possible on Linux beyond the
  existing catalog gate).
- **`make deploy`.** New target orchestrating `lab-up`, the
  `-Papp.apiOrigin` / `-Papp.conversationEngine` build, device resolution via
  `scripts/adb-device.sh` (`adb -s`), install + mic grant + test engine keys
  push (`api-keys.properties`) + launch, and optional lab-account adoption
  (`ACCOUNT=1`). Existing install/run targets switch to `-s` resolution.

## Non-goals

- iOS typed replies after a conversation has fully ended (still not possible;
  only paused state is added).
- Keeping the WebRTC peer alive while paused (no reliable inbound-silence API
  on iOS; disconnect-then-reconnect keeps semantics identical).
- Localizing the iOS coordinator status strings that are not in the catalog
  (separate follow-up; tracked in the design notes).
- Local learning-content localization (per-learning-language by design).

## Risks

- Recreate paths (locale change, theme, configuration changes) also fire
  `onDispose` on Android — the pause guard must only act on running *voice*
  sessions; the language selector already disables itself while running, and
  the app is portrait-locked, but this is called out in the design.
- Reconnect-with-history for the hosted (`api`) engine costs a new hosted
  session — resumed conversations must settle the previous hosted session
  like `beginProvider` already does on iOS (Android mirrors its existing
  provider settlement in `start()`).
- iOS coordinator changes are macOS-only verifiable; behavior coverage is
  written as AppTests and marked unverified in the verify report.
