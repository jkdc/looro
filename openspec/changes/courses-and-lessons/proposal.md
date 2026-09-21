# Proposal: courses-and-lessons

## Intent

Add structured learning: courses organized into temarios (units of lessons)
that teach grammar points and expressions — like a professional language
course — each lesson combining a pre-loaded study card (readable, optionally
read aloud on-device) with a guided practice conversation driven by a rigorous
prompt and pre-set questions. This change builds directly on the
`teaching-experience` capability introduced by `gentle-ai` and follows its SDD
structure; it consumes the theme anchoring delivered by
`themed-conversation-steering`.

## Problem

The app teaches emergently: there is no route, no lesson entity, no way to say
"today I want to practise the verb to be" and get an introduction, worked
examples, and a focused practice conversation with explicit right/wrong
feedback. The 2026-09-21 course request (`informe-cursos-2026-09-21.md`)
defines the product: course types → units → lessons; study material bundled in
the app (no model cost); TTS reading with an audio on/off switch; practice
sessions with pre-set questions where the tutor validates each answer; extra
conversation themes focused on the syllabus.

## Approach

- **Content is data.** Canonical JSON in `shared/courses/` (one file per
  course), emitted by a new `scripts/export_courses.py` into BOTH cores
  (`CoursesData.kt` / `CoursesData.swift`) with a `--check` drift gate — the
  same role `export_android_content.py` plays for language content, but
  symmetric so neither platform can drift. Hand-written struct/registry files
  (`Courses.kt` / `Courses.swift`) stay small and stable.
- **Study screen.** New "Learn" tab → course → unit → lesson card:
  explanation paragraphs, examples with meaning glosses, target expressions,
  focused situation, unit themes. A TTS reader (Android `SpeechSink` with the
  course language; iOS `AVSpeechSynthesizer`) reads the card aloud; an audio
  switch turns it off for self-reading.
- **Practice session.** `SessionRecord` gains optional `lessonID`; starting a
  lesson's practice creates a `mode = "lesson"` session whose instructions are
  new parity-checked `TeachingPolicy` functions (`lessonPractice` with the
  rigor policy + numbered pre-set questions, `lessonOpening` for the first
  turn). The tutor asks questions in order, says whether each answer is right,
  corrects with a recast plus a one-line rule, and closes with a mini-review.
  Typed replies stay lesson-aware. All three voice paths work unchanged
  because everything travels through the existing instructions channel.
- **Progress (minimal, honest).** Optional `Archive.lessons: [LessonState]`
  (WordState pattern) records attempts and lastSeen when a practice session
  runs. Evidence-based bars/due dates are a FOLLOW-UP change (assessment
  extension), out of scope here — no mastery claims in v1.

## Scope

- **In scope:** the pilot course `English · Foundations` unit 1 with lesson
  L1 "The verb to be" fully authored (+ its two focused themes); data
  pipeline and registry on both platforms; Learn tab and study screens on
  both platforms; TTS reader with on/off; practice sessions with pre-set
  questions; attempts tracking; tests and record.
- **Out of scope (parked for follow-up changes):** evidence-based lesson
  progress (bars/dueAt from validated assessments); authoring the remaining
  17 lessons of the temario (outline lives in the informe; needs
  proficient-speaker review); courses in other languages; lesson unlocking;
  AI-generated lessons; multi-choice quizzes.

## Risks

- **Parallel rebrand in flight:** `rebranding-looro` is restructuring Android
  packages in this same working tree. Implementation order: path-isolated
  artifacts first (record, JSON, script), app-tree integration only after the
  rebrand lands. Commits are pathspec-scoped to avoid swallowing the rebrand's
  staged renames.
- `export_android_content.py --check` currently fails on a clean tree (the
  rebrand commit updated its target package but not the generated file). This
  change does not depend on that script; if the drift survives the rebrand,
  fixing it belongs to the rebrand change.
- iOS is reasoning-verified only on this Linux machine; macOS `swift test` +
  build remains a REQUIRED gate before merge (unchanged policy).
- Authored lesson content (explanations, examples, questions) is English
  teaching material: it must pass proficient-speaker review before any public
  pedagogical claims (repo invariant).
- New UI strings must keep EN/ES key parity (both locales edited together).

## Delivery strategy

`single-pr` — direct conventional commits on
`feature/switchable-conversation-engine` (never pushed), one commit per
phase: record → content pipeline → data model → Android UI → iOS UI →
practice → verified close. Full Android suites + both export `--check`s +
`check_cross_platform.py` green before the close.
