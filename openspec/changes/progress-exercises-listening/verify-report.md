# Verify Report: progress-exercises-listening

Verdicts are honest per repo policy: every number below was produced by a real
run in this session; anything not run is listed as a pending gate, not claimed.

## F1 — Backend ledger (commit 752afe1)

- `npm run check` clean; `npm test` **416/416** in node:22 + postgres:17
  (`TEST_DATABASE_URL` recipe), including 8 new tests: parser closed-shape
  rejections, monotonic merge (max attempts/score, LEAST first_at), replay
  idempotency, guest access, cascade on account deletion, SQL CHECK ↔ TS
  catalog pin (now 17 names after 031).
- Migration review: `user_progress` carries slugs (deliberate divergence from
  the content-free usage_events position), allows UPDATE (monotonic upsert),
  no retention prune; `usage_events` immutability untouched.

## F2 — Local ledger + progress (commit d263f94)

- Android: `testDebugUnitTest` **441/441** (9 new ProgressEngine tests).
- Fixture safety verified by reasoning + existing green fixture tests:
  `completions` is nullable on both sides, encodes to nothing when empty, so
  pre-ledger archives/backups decode and the shared fixture is untouched.
- `check_cross_platform.py` green with `CompletionMark` in `ARCHIVE_STRUCTS`.
- Strings EN/ES lockstep maintained.

## F3 — Exercises (commit 2c12bae)

- Android: `testDebugUnitTest` **444/444** (ExerciseEngineTest + CoursesTest
  exercise-shape tests; UsageMetricsTest catalog pin moved to 17).
- 72 exercises authored across all 18 lessons; exporter validates kinds,
  option membership, gap blanks and order sentence length; regenerated
  `CoursesData.kt`/`.swift` are byte-stable under `--check`.
- Self-grading property test: every authored exercise's own answers grade
  correct on both platforms' test suites (Android run here; Swift mirrored).

## F4 — Listening (this commit)

- Android: full suite green with ListeningContentTest (10 texts × 3-4
  questions, self-grading property); `lintDebug` and `assembleDebug` pass.
- `export_listening.py --check` stable; contracts.yml now runs both
  exporters' `--check`.
- Strings 328/328 EN/ES.

## Pending gates (not run here, by environment)

- **macOS gate**: `swift test` for the mirrored core (ProgressEngine,
  ExerciseEngine, ListeningData, Archive changes) + iOS app build via
  xcodebuild — required before any release, same policy as all prior changes.
- **Device gate**: final pass on Joaquín's Pixel 9 (real microphone, voice and
  user history). The emulator stays running with the test state.

## Manual emulator testing (2026-09-28, added after F4)

Full walkthrough of all three features on Medium_Phone_API_36 with
`make deploy ENGINE=glm`; screenshots in `e2e-evidence/manual-2026-09-28/`.

- **Found**: a real crash — advancing past the last exercise crashed with
  `ArrayIndexOutOfBoundsException` (the LazyColumn item provider re-ran with a
  fresh `index` but a stale `finished`, evaluating `exercises[4]` of 4). Fixed
  in `6266e18` by resolving the exercise inside the item with a bounds guard
  (same latent pattern fixed in ListeningScreen; the iOS mirror was already
  safe). Suite re-run green (447/447) after the fix; zero FATAL/ANR in logcat
  afterwards.
- Verified end-to-end: Learn percentages + HECHA badge with real archived
  data; all four exercise kinds completed with verdicts/explanations and the
  4-of-4 summary; progress sheet counting Exercises completed 1 and Activity
  streak 1; Listening TTS playback (Playing… state, Stop enabled), 4 written
  questions answered, transcript toggle, Best 100% in the list; full
  persistence after force-stop + relaunch (Exercises 1, Listening texts 1).
- Not covered manually: Words writing practice and Recall rounds (this
  emulator archive has no learner words — they need a real AI conversation);
  live progress sync from the app (managed origin points off-box; the HTTP+DB
  layer is covered by the integration suite, which boots the real server
  against real Postgres).

## Position change

The repo previously stated "learning records local-only (no cloud sync
planned)". This change deliberately revises that: completions (content slugs
+ coarse scores only, never user text) sync to the user's own ledger at
`/v1/progress` with guests included. Documented here and in the memory index.

## Second manual battery (2026-09-28, post-lesson-levels + multilanguage)

Full regression pass on the emulator (evidence 28–44 in the same folder):

- Exercises on a second lesson with a deliberate wrong answer ("Not yet —
  here is why:" + explanation) then 3/4 summary; the LazyColumn crash fix
  held again on the finish path.
- **Typed lesson practice** (keyboard, no mic): StartLesson → tutor replied in
  the lesson script ("Hello Pedro! …") → session ended from background →
  `recordLessonAttempt` + stamped lesson mark landed in the ledger; course
  moved to 11% / unit 66%.
- Full recall round (12 expressions): "Nothing due right now." at the end and
  a `recall_round` mark with languageID in the ledger.
- Second listening text 4/4 → two ★ Best 100% medals.
- Progress sheet live: Level row, 2/18 lessons, 2 exercises, 2 listening,
  2 medals, activity streak; persistence intact after force-stop; **0 FATAL
  in logcat** across the whole battery.
- Not covered: audio speech (no mic) and full words-practice (the typed
  session produced no word proposals — expected for a one-turn session).
