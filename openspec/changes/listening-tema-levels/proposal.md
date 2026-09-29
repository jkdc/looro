# Proposal: listening-tema-levels

## Intent

The user asked to improve the Listen tab: order the listenings by level with
rising difficulty, add several topics per level (ideally tied to the course
units — "listening del tema 1, tema 2, …"), more questions per listening, and
make listening count towards levelling up ("ocupando parte del porcentaje
para subir el nivel").

## Problem

Listenings are a flat, authoring-ordered list: the `level` field is display
only, there is no topic or unit link, every text has just 3-4 questions, and a
listening only ever earns a medal — zero level points, so the Listen tab does
not move the level ladder at all.

## Approach

- **Topic + unit schema.** `shared/listening/*.json` texts gain a required
  `topic` and an optional `unitID`; the exporter validates that a `unitID`
  exists in `shared/courses/<lang>-foundations.json` and that its level
  matches the text's. `ListeningRegistry.forLanguage` sorts by level
  (A1<A2<…), then course-unit order, then id. No locks: every level stays
  playable.
- **Content.** en: 15 texts — 12 unit-linked (two per unit u1-u6; the three
  existing A1 re-labelled, four A2 assigned to u5/u6) + five new A1 +
  three existing A2 kept as topic-only extras. es/fr/it/de/pt: six texts each
  (the three existing + three new aligned with their single A1 unit). Every
  text carries six questions (existing ones gain two); the validated minimum
  rises 3→5. Existing ids are untouched so `bestScore` history and server
  sync survive.
- **Listening feeds the level.** The medal grade doubles as the level tier:
  silver (best ≥80) = 1 point, gold (100) = 2, per text. `LevelEngine.stats`
  adds Σ listening points to the lesson tiers and `LevelStats` exposes the
  breakdown (`lessonPoints`/`listeningPoints`), shown in the level card.
  Ladder recalibrated to `1,6,12,19,27,37,49,61,69,78` (78 = full en
  Foundations: 54 lesson + 24 listening points).
- **UI.** The Listen list groups by level with section headers; each row
  reads "Theme N · <unit title> · 6 questions" (unit-linked) or
  "A2 · <topic> · N questions" (extras). Mirrored on iOS (inline English).

## Scope

F1 schema + exporter + content + generated data + registry ordering + content
tests; F2 `LevelEngine` (both cores) + ViewModel + level-card breakdown +
level tests; F3 Listen-list grouping + strings EN/ES + iOS mirror; F4
verification + reports. No server change: the `listening` kind already syncs
`bestScore`.

## Risks

- Ladder recalibration moves existing levels (lesson-only 54 pts now lands on
  level 7, not 10). Accepted pre-release; levels are a pure projection and
  never stored.
- One-third of English level points now come from listening — intended, per
  the request, and visible in the breakdown.

## Delivery strategy

Conventional commits per phase, tests per phase (Gradle locally; iOS core
mirrored with tests, macOS gate pending as in previous changes).

## Out of scope

Locking higher levels, per-listening XP numbers, CEFR display, new question
kinds, audio files (still local TTS).
