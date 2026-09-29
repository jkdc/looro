# Proposal: spaced-recall-notebook

## Intent

Retention layer for everything the learner already touches: a spaced-repetition
queue and a vocabulary notebook built on the engines that already exist
(`WordState` bars with the `[1, 1, 4, 14]` day ladder, `WordPractice` writing
drill, `WordLookupSheet`, lesson target expressions). Roadmap Fase 3 flagship
(`informe-mejoras-2026-09-22.md`, feature 1), implementing informe-temario T3
+ T6 in course form.

## Problem

Words and expressions a learner meets in conversation or lessons are visible
once and then fade: the archive keeps word bars, but there is no deliberate
recall surface, no place to look up "the words I saved", and lesson target
expressions do not feed any review loop. Retention — the product's core
promise — has no daily habit loop.

## Approach

- **Expressions become first-class.** New `ExpressionState` (text, meaning,
  source: lesson | conversation | lookup, bars 0–3, `dueAt`) in the Archive as
  an OPTIONAL list — the repo's compatibility convention (old archives decode
  unchanged; `check_cross_platform.py` Archive list updated).
- **Capture paths.** Lesson target expressions are seeded into the notebook on
  first lesson practice (bars 0). The lookup sheet gains a "save" action. The
  conversation tutor's taught expressions are deferred to a later change
  (needs the T3 helper-event wiring in `TeachingClient` — not this change).
- **Recall session.** "Repasar" entry in the Learn tab: a mixed queue of due
  expressions and words, practised by typed recall first (existing drill
  machinery) with spoken recall as a follow-up. Correct recall promotes the
  bar and reschedules via the existing ladder; failures reset to bar 1.
- **No notifications here.** Local practice reminders are a separate small
  change once the queue exists (avoid bundling permission prompts with this).

## Out of scope

- Tutor-taught expression capture (T3 helper events), spoken-recall grading by
  the assessment pipeline (T6 full form), notifications, AI-generated decks,
  cloud sync (never).

## Risks

- Archive growth: expression list needs a cap (pattern: `LessonState` 1000) —
  2000 entries with oldest-demotion.
- Parity: `Archive`, LearningEngine ladder reuse and fixtures must stay
  byte-equal across cores; `check_cross_platform.py` pins the new fields.
