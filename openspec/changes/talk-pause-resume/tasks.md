# Tasks: talk-pause-resume

## 1. Android — pause on tab leave

- [ ] 1.1 JVM test-first: pausing stops the transport and keeps the session
  (fails: no pause API exists).
- [ ] 1.2 `AppViewModel.pauseVoiceSession()` + `pausedByUser` handling in
  `finish()`; `"paused"` state (not `isRunning`).
- [ ] 1.3 `TalkScreen` `DisposableEffect` on-leave pause; paused card UI
  (transcript + Resume/Restart, testTags `talk-resume` / `talk-restart`).
- [ ] 1.4 Instrumented: tab switch during voice ⇒ paused, Resume visible.

## 2. Android — resume and restart

- [ ] 2.1 JVM test-first: resume reconnects with the session history and no
  new session; restart starts a fresh voice conversation.
- [ ] 2.2 `resumeVoiceSession()` / `restartVoiceSession()` wired to the
  paused card buttons.

## 3. Android — typed replies continue the conversation

- [ ] 3.1 JVM test-first: typed while paused appends to the same session and
  keeps the paused state.
- [ ] 3.2 `sendTyped` accepts the paused branch (no voice commands).

## 4. Android — language persistence regression

- [ ] 4.1 Instrumented: select French → recreate activity → French UI
  (fix in the same commit if it reproduces).

## 5. iOS — pause / resume / restart / typed

- [ ] 5.1 AppTests (macOS-only): coordinator pause keeps session without
  auto-reset, resume reconnects with history, restart starts fresh, typed
  in pause appends.
- [ ] 5.2 `ConversationCoordinator`: `.paused` state, `pauseConversation()`,
  `resumePausedConversation()`, `restartConversation()`, `sendTyped` paused
  branch.
- [ ] 5.3 `RootView`: `.onChange(of: tab)` pause trigger; TalkView paused
  card (Resume/Restart); catalog strings.

## 6. Strings in 8 languages

- [ ] 6.1 New keys (paused title/footer, resume, restart) in Android values/
  ×8 and the iOS catalog ×8; `check_ui_strings.py` green.

## 7. Deploy

- [ ] 7.1 `make deploy` (lab-up + build with lab origin/engine keys + `-s`
  install + mic grant + keys push + launch, `ACCOUNT=1` option).
- [ ] 7.2 `lab-install` / `android-install` / `android-run` /
  `android-logcat` resolve the device via `adb-device.sh`.

## 8. Verification

- [ ] 8.1 `make android-build` green; instrumented suite green on the
  emulator; `make check` green.
- [ ] 8.2 `make deploy` to the connected Pixel 9; manual voice/tab/typed/
  language pass on the phone recorded in verify-report.
- [ ] 8.3 verify-report.md: what ran, what is macOS-only (iOS AppTests),
  residual risks.
