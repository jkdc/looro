# Proposal: learning-platform

## Intent

Evolve Looro from a linear course into a competency-based learning platform
that can answer: what the user can do, which structures they master, which
errors repeat, and what to study today. Source: `docs/plan_mejoras_app_idiomas.md`
(77 sections, 10 absolute priorities). All seven roadmap phases land on this
branch, one SDD-checked commit series per phase.

## Decisions taken with the product owner

- **All phases on this branch**, sequential and shippable per phase.
- **Mastery without CEFR labels**: percentages per skill category only; the
  existing claim discipline (never present CEFR as certification) stays
  intact. Level readiness per CEFR level is computed internally for
  checkpoints but never rendered as a level claim.
- **Universal catalog, English tagged first**: skill ids and the Can Do
  matrix are language-agnostic; only English content carries `skillIDs` in
  phases 1-5. Other languages join in phase 6.
- **Checkpoints reuse the `assessment` ledger kind** with itemID namespace
  `checkpoint-<lang>-<level>` — no server catalog migration.

## The four separated concepts (doc §2)

1. **Content** — courses/units/lessons/listenings/readings/checkpoints in
   canonical JSON (schema v2).
2. **Course progress** — existing ProgressEngine (% walked), unchanged.
3. **Mastery** — new MasteryEngine: per-skill, per-category and overall
   capability projected from weighted evidence with time decay.
4. **Gamification** — the 0-10 level ladder + medals + streaks, explicitly
   motivation only.

## Phase plan

- **F1 motor**: skill catalog + Can Do matrix, schema v2 (`schemaVersion`,
  `skills`, `canDo`, `prerequisites`, `exposureSkills` on lessons;
  `skills`, `exposureSkills` on listenings), exporter validation against the
  catalog, MasteryEngine (kt+swift), progress-sheet separation of the three
  metrics. English content tagged.
- **F2 A1 completo**: +2 A1 units (~7 lessons: present continuous,
  imperatives, prepositions, dates/hobbies), 4 new A1 listenings, 6 A1
  readings (new `shared/readings/`, "Listen | Read" segmented surface in the
  existing tab), checkpoint A1 (authored item bank), minimal in-lesson
  pronunciation drill (TTS model + self-verified, low evidence weight).
- **F3 A2 completo**: +2 A2 units (~6 lessons: past irregular, comparatives,
  going to, modals), 4 two-voice A2 listenings, 4 A2 readings, checkpoint A2,
  deliberate `exposureSkills` on advanced-structure listenings.
- **F4 personalización**: ErrorMemory (skill-tagged mistakes feed the recall
  queue), Daily Plan engine (5/10/15/20 min budgets), lesson recommender
  (prerequisites + mastery), skill-aware tutor context.
- **F5 B1**: 2 B1 units (~6 lessons), 4 B1 listenings, 4 B1 readings,
  checkpoint B1.
- **F6 multiidioma**: es/de/fr/it/pt tagged via the shared Can Do matrix;
  validators for per-level coverage.
- **F7 B2/C1 + tracks**: 1 B2 unit + 1 C1 unit, own listenings/readings,
  checkpoint B2, first professional track (English for Work).

## Approach / architecture

- Content stays canonical JSON; exporters are the only writers of generated
  code and the only validation gate (skill references, prerequisites,
  schemaVersion, CEFR level set A0-C1).
- Mastery is a **pure projection of the ledger + content** (same philosophy
  as LevelEngine): no schema change, no server work, snapshots deferred
  until the catalog makes recomputation genuinely expensive.
- Evidence weights: guided oral 0.9 (baseline score 75 when unmarked),
  exercise sets 0.6, listening 0.5, checkpoints 1.0. Time decay with a
  60-day half-life; mastery never regresses within a day and recovers via
  new evidence (recall).
- Talk evidence keeps flowing through the existing per-passage assessments;
  phase 4 adds skill attribution for exercise mistakes (ErrorMemory).

## Risks

- Content volume (F2/F3/F5/F7 are authoring-heavy): each phase ships
  complete, playable blocks; growing them later is editorial work on the
  same pipeline.
- Prompt parity: skill-aware tutor prompts must change in lockstep on both
  platforms (pinned by `check_cross_platform.py`).
- Ledger cap (2,000 marks) eventually tightens as the catalog grows;
  snapshot design is documented but deliberately deferred.

## Delivery strategy

SDD per phase inside this change folder, conventional commits, Gradle +
python + cross-platform checks green per phase, verify-report updated per
phase. iOS core mirrored each phase (macOS gate pending, repo policy).

## Out of scope

Variant courses (US/UK, LATAM), full editorial tooling, AI-assisted content
generation pipeline, fluency/accuracy score split, server-side snapshots.
