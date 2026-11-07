# Design: talk-pause-resume

## State machine

`idle → connecting → active → paused → connecting → …` and
`active/paused → ended/closing → (15 s) → idle`.

`paused` is a first-class state on both platforms:
- Android `AppViewModel.state` gains `"paused"`. `isRunning` keeps meaning
  "transport work in flight" (`connecting|active|closing`) — paused is NOT
  running, so selectors and navigation re-enable, but the Talk tab shows the
  paused UI instead of the idle orb. `finish()` consults `pausedByUser`:
  when set it writes `"paused"`, skips `scheduleReset()`, and clears the
  flag after the transport settles.
- iOS `ConversationCoordinator.State` gains `.paused`. `pauseConversation()`
  mirrors `end()`'s settle path (`finish(final: false)`-style: save, stop
  metering) but does not call `scheduleReset()`; `handle()` guards on
  `.active` stay correct because nothing is connected while paused.

## Pause triggers

- Android: `TalkScreen` gains
  `DisposableEffect(Unit) { onDispose { vm.maybePauseOnLeave() } }` where
  `maybePauseOnLeave()` guards `isRunning && isVoiceSession` (typed sessions
  have no playback; locale recreation cannot happen mid-run because the
  language row disables itself while running). This follows the existing
  pattern (`ListeningScreen.kt:54`, `LearnScreen.kt:78`).
- iOS: `RootView` gains `.onChange(of: tab)` — leaving tab 0 (Talk) while
  `coordinator.state == .active` calls `coordinator.pauseConversation()`.
  `scenePhase` background keeps calling `end()` (stronger: full save-and-end)
  exactly as today.

## Resume

`resumeVoiceSession()` (Android) / `resumePausedConversation()` (iOS):
- guard on the paused state and a non-nil session;
- keep the existing `SessionRecord` — no `newSession()`;
- rebuild instructions exactly like `start()` (Android:
  `ConversationHistory.messages(session)` + language module/locale; iOS: the
  same instruction build `start()` performs) and call
  `transport.connect(..., history, ...)`;
- state → `connecting` → `active` through the existing connected handlers.

The hosted (`api`) engine settles the previous hosted session during the
pause's transport close; resuming opens a new hosted conversation that
continues the same local `SessionRecord` — the same behaviour a topic
transition already has (`confirmPendingTopicTransition`).

## Restart

`restartVoiceSession()` / `restartConversation()`: if paused, clear the pause
(state → idle path without auto-reset) and call `start()` — a brand-new
voice conversation. UI: the paused card's Restart button.

## Typed replies in pause

Android `sendTyped`: extend the active branch to `state == "paused"`:
append the user fragment to `session`, run `teaching(...)`, append the reply
as an assistant fragment, keep `state == "paused"`, skip all
`command(...)` data-channel/voice paths. iOS `sendTyped`: accept `.paused`
the same way (fragments + helper reply; `append()` data-channel calls are
skipped because state is not `.active`). Reply identity/retry keys keep
working: Android keys off the session id; iOS
`TypedReplyRequestIdentity.messageID(for: snapshot.id, …)` is unchanged and
`invalidateTypedReplyContinuation()` is not called on pause (pause must not
cancel in-flight typed replies — end() still does).

## Language persistence regression

Android instrumented test: set the app language to French through the
selector, recreate the activity (`activityRule.scenario.recreate()`), assert
the rendered strings are French. This proves the appcompat + system per-app
locale persistence end to end. If it reproduces the user's report, the fix
lands in the same commit; if it passes, the report is re-checked on the
physical phone (the earlier `pm clear` during deployment reset any stored
choice).

## Make deploy

`make deploy [ENGINE=glm] [SERIAL=<id>] [ACCOUNT=1]`:
1. `lab-up` (idempotent; generates lab keys if missing).
2. `assembleDebug -Papp.apiOrigin=http://$(LAB_HOST_IP):$(LAB_PORT)
   -Papp.conversationEngine=$(ENGINE)`.
3. `SERIAL ?= $(shell …)` fallback: first non-emulator adb device, else the
   only device; resolution through `apps/android/scripts/adb-device.sh`.
4. `adb -s <dev> install -r -t`, `pm grant …RECORD_AUDIO`, push
   `apps/android/api-keys.properties` → `…/files/api_keys.properties`
   (skipped with a warning if the file does not exist).
5. Launch `MainActivity`; with `ACCOUNT=1`, seed/adopt the lab account via
   `lab-account` and the `debug.accountID`/`debug.accountToken` extras.
`lab-install`, `android-install`, `android-run` and `android-logcat` all
switch to the same device resolution so they work with a phone and an
emulator attached simultaneously.

## Testing

- Android JVM (`AppViewModelTest`): pause stops the transport and keeps the
  session; resume reconnects with the session's history and does not create
  a session; restart starts a fresh voice conversation; typed-in-pause
  appends to the same session; paused is not `isRunning` (selectors
  enabled). Fake transport follows the existing test doubles in that file.
- Android instrumented: tab switch during an active voice session leaves
  `state == "paused"` and returns to a paused card with working Resume;
  language-persistence regression test.
- iOS AppTests (written here, macOS-only to run): coordinator
  pause/resume/restart state transitions, typed-in-pause appends,
  `onChange(tab)` pause trigger via the prepare-typed-reply-testing seams.
- Strings for the paused card ship in all 8 languages; the UI-strings gate
  enforces coverage.
