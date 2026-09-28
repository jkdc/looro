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
- **Device gate**: manual run on Joaquín's Pixel 9 (tabs, TTS playback of
  listening texts on-device, exercise players).
- Reused-trait note: `wordsPracticeItems` counts distinct words, and
  restored `exercise_set`/`listening` completions after reinstall come back
  as one mark per item — per-day history does not survive a reinstall (v1
  limit, documented in design.md).

## Position change

The repo previously stated "learning records local-only (no cloud sync
planned)". This change deliberately revises that: completions (content slugs
+ coarse scores only, never user text) sync to the user's own ledger at
`/v1/progress` with guests included. Documented here and in the memory index.
