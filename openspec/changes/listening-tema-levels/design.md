# Design: listening-tema-levels

## Data model

`ListeningText` gains `topic: String` (required, short category label) and
`unitID: String?` (course unit this listening belongs to). Both platforms
mirror the change; the generated `ListeningData` emits `topic` always and
`unitID` only when authored (Swift optional default `nil`, Kotlin default
`null`), so hand-written fixtures keep compiling.

## Ordering

`forLanguage` becomes a projection, sorted with a stable comparator:

1. level rank, indexed in `["A1","A2","B1","B2","C1","C2"]`, unknown last;
2. unit rank — index of `unitID` in the first course of that language
   (`CourseRegistry`), texts without a unit last within their level;
3. id, so authoring order in the JSON never matters.

The Listen list then groups consecutive texts by level for its section
headers, which is why the registry itself (not the view) owns the order —
the same list feeds progress stats and future surfaces.

## Level points

The medal grade is reused as the listening tier — no new thresholds:

- `0` best <80 · `1` silver (≥80, `LISTENING_MEDAL`) · `2` gold (100).

`LevelEngine.stats` sums the per-text best scores over the texts passed in
(the language's registry, capped by construction at 12 en / 6 others) and
adds them to the lesson tiers. `LevelStats` grows `lessonPoints` and
`listeningPoints` so the level card can show where the points come from; the
ladder, `pointsToNext` and `progressInLevel` maths are unchanged.

New `LADDER = [1, 6, 12, 19, 27, 37, 49, 61, 69, 78]`. Rationale: 78 = 54
(en lesson max) + 24 (12 texts × 2), so level 10 still means "everything
done, everything understood". Early rungs stay cheap (1, 6) so new users
level up within their first sessions. Other languages cap lower (21 points
max: 9 lesson + 12 listening → level 4) until their temarios grow — visible
in the progress sheet, not a bug.

`check_cross_platform.py` already pins `level_ladder`, `level_listening_medal`
and the exercise thresholds by regex on both files; no new pinned constant is
needed (the 100 gold bound lives in code on both platforms, as before).

## Content shape

- Six questions per text, mixed `choice`/`written`, each with `explain`.
  Existing texts keep their four questions and gain two; new texts are
  authored with six. Validator minimum moves 3→5 to keep authoring honest.
- en unit map (grammar-led): u1 language-cafe/new-neighbour, u2
  lost-umbrella/new-flatmate, u3 coffee-morning/night-shift, u4
  city-walk/sunday-market, u5 rainy-picnic/language-exchange (A2),
  u6 first-day/small-accident (A2); lost-jacket, night-train and
  grandpas-garden stay A2 topic-only extras (past-tense narratives that would
  be mis-graded as A1). Non-en: all six texts link to the language's single
  `u1` and are present-tense A1.
- The three new non-en stories (language class, new neighbour, little
  sister) are authored once in Spanish and translated with localised names —
  the same bargain as the existing three shared stories.

## UI

Android `ListeningScreen`: `groupBy(level)` over the sorted registry
(insertion-ordered map keeps registry order); each section header carries
`heading()` semantics and a `listening-section-<level>` test tag. Row
subtitle: unit-linked → `listening_theme` ("Theme %1$d · %2$s · %3$d
questions"), where N is the unit's 1-based index in the course; topic-only →
`listening_topic` ("%1$s · %2$s · %3$d questions" with level + topic). The
detail reuses the same line. iOS mirrors both inline (English strings live in
the view, as in lesson-levels).

Level card (Learn tab, both platforms) gains a caption line
`learn_level_breakdown`: "%1$d lesson points · %2$d listening points".

## Compatibility

- Ledger/server: untouched — kind `listening`, `itemID` slugs and
  `bestScore` already sync; ids are stable, so history survives the
  re-labelling.
- `ProgressEngine.stats` keeps taking the registry id set; ordering changes
  are invisible to it.
- Tests pin the new counts (en 15, others 6), the ≥5-questions minimum, the
  unit-level agreement, the registry ordering, the tier maths, the new
  ladder endpoints (78 → level 10; lesson-only 54 → level 7) and the
  breakdown fields.
