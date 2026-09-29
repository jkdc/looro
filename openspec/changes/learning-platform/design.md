# Design: learning-platform

## Skill catalog (`shared/skills/skill-catalog.json`)

```json
{ "schemaVersion": 1,
  "skills": [ {"id": "grammar:present-simple", "category": "grammar",
               "prerequisites": ["grammar:verb-be"]} ] }
```

- Categories (closed set): `grammar`, `vocab`, `function`, `speaking`,
  `listening`, `reading`, `writing`, `pron`.
- Ids are language-agnostic (`grammar:present-simple` exists in every
  language that teaches it); per-language coverage is a content concern.
- `prerequisites` reference other skill ids and form the pedagogical graph
  the recommender walks (phases 4-5).
- `shared/skills/cando.json` holds the universal competence matrix
  (`{id, label}`); lessons reference competence ids in `canDo`.

## Schema v2

- Courses: root gains `"schemaVersion": 2`; lessons gain optional
  `skills`, `canDo`, `prerequisites`, `exposureSkills` (string arrays).
- Listenings: root gains `"schemaVersion": 2`; texts gain optional `skills`
  and `exposureSkills`.
- Exporters require `schemaVersion == 2` (courses and listening) and reject
  any reference not present in the catalog; unit levels must be in
  `A0, A1, A2, B1, B2, C1` (A0 reserved for the future Starter block).
- Generated models: new fields are emitted **only when non-empty** and carry
  defaults (`emptyList()` / `[]` / nil) so hand-written fixtures and old
  positional constructors keep compiling.

## MasteryEngine (pure projection, mirrored kt/swift)

```
evidence(skill) = weight(kind) × bestScore/100 × decay(days)
weight: checkpoint 1.0 · guided oral 0.9 · exercise set 0.6 · listening 0.5
decay(days) = 0.5 ^ (days / 60)          # half-life 60 days
oral baseline score = 75 (the ledger records attempts, not marks)
mastery(skill) = round(100 × max(evidence))
category = round(mean of its tagged skills)     overall = round(mean all)
```

- Inputs: the language's course + listening registry (skill tags), the
  ledger, the lesson-attempt states and `now`. No storage, no server work;
  snapshots are deferred until the catalog makes recomputation expensive
  (documented in proposal risks).
- `skillEvidence` resolves `itemID → lesson/listening → skills`; ledger
  kinds map 1:1 to weights. Checkpoint evidence rides the `assessment` kind
  with `checkpoint-` itemIDs.
- Tagged-skill scope: only skills referenced by the language's content are
  listed, so the surface never shows empty categories for languages that
  don't teach a skill yet.

## Metric separation (UI)

Progress sheet groups become: **Practice** (streaks/time — unchanged),
**Course** (level-as-gamification card + % walked — unchanged maths),
**Skills** (new: overall mastery % + one bar per category, localized
category labels, no CEFR wording anywhere). Learn tab keeps the level card
(gamification) and gains nothing else in F1.

## Data flow unchanged

`shared/*.json` → `export_*.py` (validate + emit) → generated Kotlin/Swift →
engines project from the local ledger. Checkpoints (F2+) author items in
`shared/assessments/` reusing the `Exercise` shape; grading stays
deterministic via `ExerciseEngine`.

## Testing strategy

- Python: catalog/cando loaders + validation errors + emitter fields.
- Kotlin/Swift: MasteryEngine (weights, decay, best-evidence, category/overall
  maths, untagged languages → empty), content tests (every reference
  resolves, schema version present), exporter drift checks.
- `check_cross_platform.py` keeps pinning the shared constants the day they
  exist (mastery weights arrive as mirrored constants in F1).
