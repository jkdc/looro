# Tasks: gentle-ai

## Task 1 — K10: tutor speech pace

- [x] 1.1 `Preferences.slowSpeech: Bool?` / `slowSpeech: Boolean? = null` on both
      platforms; old archives and cross-platform fixtures still decode.
- [x] 1.2 `TeachingPolicy.voice` gains `slowSpeech: Bool` + the pace line;
      `paceSlow()`/`paceNormal()` prompts added on both platforms with
      identical wording; `check_cross_platform.py` green.
- [x] 1.3 Session start passes the preference on both platforms; the Android
      local pipeline applies the system-TTS speech rate.
- [x] 1.4 Settings toggle on both apps (Android EN/ES strings); applies to new
      sessions only.
- [x] 1.5 Unit tests: pace line present/absent in the voice prompt (JVM);
      local transport configures TTS rate (fake-based).

## Task 2 — K9: listening comprehension mode

- [x] 2.1 `Preferences.listeningPractice: Bool?` and `SessionRecord.mode:
      String?` on both platforms; fixtures still decode.
- [x] 2.2 `TeachingPolicy.listeningStory(language:)` prompt on both platforms,
      checker-enforced; sent instead of `greeting` when the session mode is
      listening.
- [x] 2.3 Sessions created with mode `"listening"` when the preference is on;
      `scheduleTranslation` suppressed for listening sessions on both
      platforms (word lookup unaffected).
- [x] 2.4 Settings toggle on both apps (Android EN/ES strings) with a caption
      explaining the exercise.
- [x] 2.5 Unit tests: listening prompt replaces greeting (JVM + fake);
      meanings not scheduled for a listening session; mode persisted.

## Task 3 — K3: progress and streaks

- [x] 3.1 `LearningStats` + `LearningEngine.stats` on both platforms: totals,
      words per bar, active/longest streak (local-day boundary), last-14-days
      voice minutes, capabilities count and latest descriptor.
- [x] 3.2 JVM tests with fixed archives (streak across month boundary, gap day,
      same-day multiple sessions, empty archive); equivalent Swift core tests.
- [x] 3.3 Progress screen on both apps reached from Settings; read-only,
      recomputed on open; Android strings EN/ES.

## Task 4 — K12: directed writing practice

- [x] 4.1 `WordPractice` core on both platforms: normalization (trim, NFC,
      casefold, whitespace collapse, surrounding punctuation) and acceptance
      of `form` or `lemma`.
- [x] 4.2 Unit tests: Android `WordPracticeTest` (7 JVM cases: exact, case/
      punctuation, lemma, diacritics, empty, normalization, masking). iOS:
      the Swift core is reasoning-verified only — its tests land with the
      macOS gate (no Swift toolchain on the dev machine).
- [x] 4.3 Drill screen on the Words tab of both apps: up to 10 due words,
      masked example hint, correct/incorrect feedback with the right spelling,
      end summary; Android strings EN/ES.

## Task 5 — D3: partials, barge-in, adaptive VAD (Android local path)

- [x] 5.1 `AdaptiveVad`: noise-floor tracking + hysteresis; unit tests with
      synthetic frames (quiet-room floor drop, speech detection, soft trailing
      syllable kept, sustained noise floor rise).
- [x] 5.2 `LocalVoiceTransport` uses `AdaptiveVad` in auto mode; manual mode
      semantics unchanged; silence-window setting still honored.
- [x] 5.3 Partial transcription: bounded snapshot (≤ 8 s), 2 s cadence, 1 s
      minimum, supersede/cancel of stale jobs, dedup of unchanged text,
      `session.input_transcript.partial` event; fake-based tests.
- [x] 5.4 ViewModel maps partials to provisional UI state only (never a
      fragment) and clears on final input delta/end; Talk screen renders the
      interim line.
- [x] 5.5 Barge-in gated on AEC availability: sustained ≥ `VAD_BARGE_RMS` for
      250 ms during playback stops TTS through the depth-accounted path; frames
      during playback never enter the turn buffer; fake-based tests; disabled
      without AEC.
- [x] 5.6 `:app` + `:core:engine-local` suites green; instrumented tests still
      compile.

## Task 6 — Record and verification

- [x] 6.1 Full Android suites (`:app:testDebugUnitTest`,
      `:core:engine-local:testDebugUnitTest`) + `check_cross_platform.py` +
      scripts tests green.
- [x] 6.2 Spec delta and this record finalized; verify-report.md written with
      the macOS/iOS gate restated.
