# Tasks: cabling-remediation

## Task 1 — TTS language wiring

- [ ] 1.1 `SpeechSink.setLanguage(languageTag: String): Boolean` (default
      false); `LocalTtsEngine` maps via `Locale.forLanguageTag` with the
      initialize-time usability rule and keeps the previous voice on failure.
- [ ] 1.2 `LocalVoiceTransport.connect` applies the session language after
      the rate; failure logs and degrades (never fails the session).
- [ ] 1.3 Fake-based test: connect passes the session code; unit test for the
      usability mapping where feasible.

## Task 2 — Gate local-voice controls

- [ ] 2.1 `SettingsScreen` renders the manual-turn switch and silence slider
      only for non-`openai` builds; compiles; no other settings moved.

## Task 3 — Partial cleared on discarded turns

- [ ] 3.1 `clearPartial()` emits an empty `session.input_transcript.partial`
      from both discard paths (noise blip, empty STT).
- [ ] 3.2 `MuralViewModel` clears `partialUserTranscript` on the empty delta
      (50 000-char cap retained).
- [ ] 3.3 Fake-based test: a discarded turn after a partial emits the empty
      event.

## Task 4 — Greeting failure surfaced

- [ ] 4.1 The opening-greeting catch invokes `onFailure` with a distinct
      message; cancellation still propagates; fake-based test.

## Task 5 — `member_deleted` guest link outcome

- [ ] 5.1 `GuestLinkResult` accepts `member_deleted`; the controller clears
      the pending intent terminally (no retry) and shows the new notice
      (EN/ES strings).
- [ ] 5.2 Fake-based test pins parse + terminal state.

## Task 6 — Orb accessibility + dead-code purge

- [ ] 6.1 `MuralOrb` gains `contentDescription`, wired in `TalkScreen` from
      the existing `talk_orb_*_desc` strings (state-dependent).
- [ ] 6.2 The remaining 17 orphan keys deleted from both locales by a script
      that greps each key and fails on any reference or EN/ES drift.
- [ ] 6.3 `VAD_SPEECH_RMS`/`SILENCE_END_MS` and caller-less `started`/
      `isMuted` transport properties removed after grep verification.

## Task 7/8 — Copy accuracy

- [ ] 7.1 Progress footers (Android EN/ES + iOS): totals span all languages;
      words/capabilities are the current language.
- [ ] 8.1 Listening-practice hint (Android EN/ES + iOS Settings footer)
      states that subtitles are hidden while the transcript stays visible.

## Task 9 — iOS (reasoning-verified; macOS gate REQUIRED)

- [ ] 9.1 `Config/Signing.xcconfig` includes `ManagedAccounts.xcconfig`
      (defaults unchanged); include path verified.
- [ ] 9.2 `#if DEBUG` around the two `--preview` checks (`MuralApp.swift`,
      `RootView.swift`).
- [ ] 9.3 Backup footer copy matches `Archive.merging` semantics.
- [ ] 9.4 `LearningStatsTests` non-optional `??` warning removed.
- [ ] 9.5 `Tests/WordPracticeTests.swift` mirrors the JVM cases (runs under
      the macOS gate).

## Task 10 — Documentation pass

- [ ] 10.1 Root README "What works today" + engine caveats (search sources,
        accounts posture, historical verification record wording).
- [ ] 10.2 `apps/android/README.md` feature list refreshed.
- [ ] 10.3 Backend test count re-run with a disposable Postgres if available,
        else static recount labeled as such; iOS core count refreshed.
- [ ] 10.4 `docs/conversation-minutes.md` and `docs/add-language.md` fixed.
- [ ] 10.5 `conversation-engines.md` delegation degradation note;
        `post-analysis-remediation/tasks.md` Future-work annotations.

## Task 11 — Record

- [ ] 11.1 Spec delta finalized; full Android suites + checker green;
      verify-report.md written (macOS + device gates restated).
