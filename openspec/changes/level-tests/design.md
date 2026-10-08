# Design: level-tests

## Data model

`shared/leveltests/<lang>-leveltests.json`:

```json
{ "schemaVersion": 1, "languageID": "en", "levels": [ {
    "level": "B1", "version": 1,
    "thresholds": { "overall": 70, "grammar": 60, "vocab": 60 },
    "passages": [ { "id": "...", "title": "...", "text": "...", "speech": "..." } ],
    "pool": [ { "id": "...", "section": "vocab|grammar|reading|listening|writing",
                "kind": "choice|gap|order|written", "skill": "grammar:...",
                "unitID": "en-foundations-u03" | null,
                "passageId": "..." | null, "speech": "..." | null,
                "prompt": "...", "options": [...]|absent, "answers": [...], "explain": "..." } ] } ] }
```

Sections and their harvest sources: `vocab`/`grammar`/`writing` from lesson
exercises + checkpoint questions (unit-tagged where the source has it);
`reading` = listening texts shown visually (3 questions per passage);
`listening` = other texts' questions spoken via the learning-language TTS
(two passages × two questions, so stimuli stay short). Within one composed
test the reading and listening passages are always disjoint (the composer
enforces it), so the listening is never pre-read.

## Weekly composition (LevelTestEngine)

- `weekIndex = ISO week-based year * 100 + week of week-based year`
  (`java.time.temporal.IsoFields`) — injectable for tests.
- `seed = sha256("leveltest|lang|level|weekIndex|version")` feeding
  `java.util.Random` (its algorithm is specified, so Android and any future
  JVM-based server compose identically).
- Per section, shuffle the pool with the seeded PRNG and take the fixed
  counts: vocab 4, grammar 8, reading = 2 passages × 3 questions,
  listening = 2 passages × 2 questions, writing 3. Section order
  is fixed (warm-up first); within a section the shuffled order stands.
- Pure function of (pool, lang, level, weekIndex, version): testable in JVM,
  identical on every device for a given week.

## Grading & result

- `CheckpointEngine`-style: overall percent ≥ `thresholds.overall` AND every
  section percent ≥ its floor (floor = `thresholds[<section>]` or 60).
- Passed → suggestion "ready for <next band>" with a jump action (never
  fixes the level); failed → weak sections (below floor, lowest first) and
  the `unitID`s of wrong unit-tagged items as "reinforce these lessons".
- Persisted: ledger `assessment` completion per attempt
  (`levelcheck-<lang>-<level>`, bestScore = overall) + local
  `LevelTestHistoryStore` keeping the last result per (language, level):
  weekId, overall, categories, passed, weak unit ids, date. The Learn card
  for the selected level always shows it.

## UI

- Entry: a card inside the selected level's view in Learn (under the level
  bar), visible whenever a pool exists for that level; shows the last
  result when present ("Last: 84% · passed · Oct 8").
- `LevelTestScreen` mirrors `PlacementScreen` chrome with exam rules: no
  per-answer feedback, section labels, progress by section, final review
  listing every item with verdict + explanation, result screen with the
  directional suggestion and (on fail) the reinforce list. Instructions
  follow the existing instructions-language setting. Back mid-exam asks
  nothing and discards (weekly test, retake is free) — the can-do concept
  does not apply here.

## Pipeline

`build_leveltests.py` (harvest + compose + validate minimums: vocab ≥12,
grammar ≥24, reading passages ≥6, listening ≥12, writing ≥9 per level)
writes the JSON; `export_leveltests.py` re-validates and generates
`LevelTestsData.kt` with `--check` wired into `contracts.yml`. Levels that
miss minimums are dropped at build time (the app only ever sees valid
levels).

## Testing

JVM: composition determinism (same week → identical test), consecutive-week
non-overlap beyond a bounded rate, section counts and ordering stability,
grading floors, next-band suggestion, slug-safe ledger ids, dropped-level
absence. UI smoke on the emulator: entry → exam → submit → review → result
→ card updated.
