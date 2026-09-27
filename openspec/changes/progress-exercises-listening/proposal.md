# Proposal: progress-exercises-listening

## Intent

Three connected capabilities, requested 2026-09-28:

1. **Completion ledger + progress metrics.** The app records everything the
   learner completes — temario lessons, conversation themes, exercise sets,
   listening texts, recall rounds, words practice, assessments — and shows the
   learner their own progress (course %, HECHA badges, activity streak, 30-day
   activity chart). A per-account ledger on the API makes the record survive
   reinstalls and device changes.
2. **Deterministic temario exercises.** Authored, offline, AI-free exercises
   (multiple choice, written gap, word ordering, free written answer with
   accepted patterns) attached to every Foundations lesson.
3. **Listening section.** A new top-level tab: the learner hears an authored
   text (on-device TTS at their chosen rate) and answers written
   comprehension questions; results join the same ledger.

## Problem

Today the only learning traces are a local per-lesson attempt counter
(`LessonState.attempts`) and content-free usage telemetry that only the admin
panel sees. The learner has no course completion view, no streak beyond voice
minutes, and words-writing practice is not recorded anywhere. Temario practice
is exclusively an AI conversation; there is no offline drill, and no reading-
comprehension format at all.

## Approach

- **Local-first ledger.** `Archive.completions: [CompletionMark(kind, itemID,
  at, bestScore)]` — an append-only mark per completion event, capped, merged
  by exact-tuple dedupe. "Lesson HECHA" stays `attempts >= 1` (no LessonState
  schema change). `ProgressEngine` (pure, mirrored Kotlin/Swift) projects
  course/unit percent, streaks and the 30-day chart; nothing new is required
  at decode time on either platform.
- **Sync mirrors the usage-events pattern.** `POST/GET /v1/progress`
  (guests included) merge monotonic rollups (kind, item, attempts, first/last,
  best score) in `user_progress` (030). Push happens like usage flush (app
  open, session end); pull restores lesson state and completion counts.
- **Exercises and listening are data.** New `exercises` arrays in
  `shared/courses/en-foundations.json` and a new `shared/listening/*.json`,
  both exported to native Kotlin/Swift literals by (extended) exporters with
  `--check` CI drift guards. One deterministic grader (`ExerciseEngine`)
  shared by both features on both platforms.
- **New Listening tab** wired into the existing 4-tab shells on both
  platforms.

## Scope

F1 backend ledger + catalog extension (030/031); F2 local ledger +
ProgressEngine + sync client + progress UI (Android + iOS core/iOS app);
F3 exercises (schema, exporter, engine, player UI, authored content for all
18 lessons); F4 listening (content, exporter, tab, flow); F5 verification +
report. Android is test-verified locally; iOS core is mirrored with tests but
stays macOS-gate-pending per repo policy.

## Risks

- Archive growth: marks are capped (2,000) and validated on both platforms.
- Fixture tests pin re-encoded field paths: optional (nullable) list on both
  platforms keeps old backups decodable and the shared fixture untouched.
- Restored-vs-native history: pull reconstructs lesson state and completion
  counts, not the full per-day mark history; documented as a v1 limit.
- The product previously declared "learning records local-only"; this change
  deliberately revises that position (content slugs only, never user text) —
  recorded in the verify report.

## Delivery strategy

Direct conventional commits, one group per phase, tests per phase
(backend `npm test`, Android Gradle unit, Python exporter suites). iOS Swift
test gate pending on macOS, as everywhere in this repo.

## Out of scope

- Recorded audio assets (listening uses on-device TTS).
- AI grading of written answers; admin content-editing tab.
- Deployment to device; Play/App Store matters.
