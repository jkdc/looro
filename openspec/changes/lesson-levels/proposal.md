# Proposal: lesson-levels

## Intent

Motivating progression: the user asked for a level that grows as they surpass
each theme. Decision taken on `informe-niveles-2026-09-28.md`: **option B
(0-3 lesson mastery + derived global level) plus the listening medal** from
option C-lite. Conversation themes stay out of v1 (no reliable "surpassed"
notion yet).

## Problem

Progress exists (HECHA badges, course %, activity streak) but has no ladder:
nothing celebrates mastery or invites replaying a lesson's exercises to raise
its score.

## Approach

- **Derived, never stored.** A pure `LevelEngine` (mirrored Kotlin/Swift)
  projects mastery and level from data the app already has and already syncs
  (`LessonState.attempts`, `exercise_set`/`lesson`/`listening` completion
  marks). No schema change, no backend, no sync work.
- **Mastery tiers per lesson:** ★1 practice completed; ★2 additionally
  exercises ≥60%; ★3 additionally ≥90% or the set at ≥60% with the oral
  practice repeated on a distinct day (spaced evidence, ledger dates).
- **Global level per course:** points = Σ tiers (0-54 for Foundations),
  fixed ladder `1,4,8,13,19,26,34,42,48,54` → 10 levels with playful titles
  (presentation-only, EN strings + ES translations; iOS inline English).
- **Listening medal:** per text, silver at best ≥80, gold at 100; shown in
  the listening list and counted in the progress sheet. Not level points.
- **Claim discipline:** engagement titles only ("Con oficio", "Cima cerca");
  never a language-proficiency statement; CEFR stays unrendered.

## Scope

F1 `LevelEngine` + tests + CONSTANTS pinning; F2 Android UI (level card in
Learn, stars on lesson rows, progress-sheet rows, strings); F3 iOS mirror;
F4 verification + report. Reuses everything from
progress-exercises-listening.

## Risks

- Threshold tuning: 60/90/80 and the ladder live as pinned constants;
  telemetry (`exercise_completed.scoreBucket`) can inform later tuning.
- Restored-after-reinstall lesson marks may collapse to one day → tier 3 via
  spacing may not restore (accepted v1 limit, same as per-day history).

## Delivery strategy

Conventional commits per phase, tests per phase (Gradle locally; iOS core
mirrored with tests, macOS gate pending).

## Out of scope

Conversation-theme levels, badges/achievements, XP numbers, CEFR display.
