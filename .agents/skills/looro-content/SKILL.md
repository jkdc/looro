---
name: looro-content
description: Author and extend Looro's pedagogical content — units, lessons, exercises, listenings, readings, checkpoints and skill tagging in shared/*.json — through the validated export pipeline. Use whenever the user wants to add, edit, review, translate or extend any learning content ("añade una lección", "new B2 unit", "crece el B1", "translate the course", "fix this exercise", "more listenings for B1"), even if they never mention JSON, exporters or tests.
---

# Looro content authoring

Looro's teaching content is **canonical JSON in `shared/`**, validated and
compiled into both apps by Python exporters. Nothing in `apps/*/…Data.*` is
hand-written; nothing in `shared/` reaches the app without passing the
validators. Your job in an authoring session is: write content that passes
the validators on the first try, keep the pedagogical bar of
`docs/plan_mejoras_app_idiomas.md`, and update the few test assertions that
pin content counts.

This skill governs authored course and bundled assessment content only. Runtime
LLM-generated level-test prompts, validation, judging, repair, and publication
policy live in `services/api/src/level-tests-llm.ts` and its neighboring API
modules; the runtime does not load this skill. Keep changes to those exam
behaviors in the API implementation and tests rather than assuming this
document changes model behavior.

## The pipeline (memorise this shape)

```
shared/{courses,listening,readings,assessments,skills}/*.json   ← you edit ONLY here
        ↓ python3 scripts/export_{courses,listening,readings,assessments}.py
generated ListeningData / CoursesData / ReadingData / CheckpointsData / SkillsData (.kt + .swift)
        ↓ gradle test  +  python3 -m unittest discover -s scripts/tests -t .
counts pinned in content tests (see "Test counts" below)
```

`--check` on each exporter fails when a generated file is stale — always run
it before committing. `scripts/check_cross_platform.py` pins shared constants
and tutor prompts: if you touch `LevelEngine`, `MasteryEngine` or
`TeachingPolicy`, update BOTH cores identically.

## Read before writing

- `references/content-types.md` — field-by-field schemas and one full worked
  example per content type. Read it once per session before authoring.
- `docs/plan_mejoras_app_idiomas.md` §7-§14 — the pedagogical targets per
  level (what A1/B1/B2/C1 must cover). This defines "complete", not vibes.
- Run the report helper for live counts before and after authoring:
  `python3 <skill-dir>/scripts/content-report.py`

## Golden rules (each one broke a build at least once)

1. **Order exercises take exactly ONE answer of 3+ words.** No variants
   ("Please be quiet" / "Be quiet, please" → pick one).
2. **`written`/`gap`/`order` questions carry no `options`.** Only `choice`.
3. **Choice answers must be listed options**, verbatim.
4. **`unitID` level must equal the text's level.** An A2 text cannot point at
   an A1 unit. New units: semantic ids (`en-foundations-change`), appended in
   teaching order — array order defines the Tema numbering.
5. **Word counts:** lessons' example texts are free; listenings 40-200 words,
   readings 40-220; min questions: listening 5, reading 4, checkpoint 12.
6. **Every question must grade its own first answer correct.** Written answers
   are normalised (lowercase, punctuation stripped) — give 2-4 natural
   variants; `<name>` spans accept any text.
7. **Honest CEFR levelling.** A1 texts are present tense. If a text needs a
   structure the level hasn't learned, keep the level and add that structure
   to `exposureSkills` — never penalise exposure (MasteryEngine ignores it).
8. **Skill references must exist** in `shared/skills/skill-catalog.json`; new
   skills go into the catalog FIRST (with `prerequisites` where sensible).
   `canDo` ids come from `shared/skills/cando.json`. Coverage validators fail
   any untagged lesson or listening in any language.
9. **Stable ids.** Never rename existing ids (ledger, sync, analytics hang off
   them). New content gets slug ids: `en-foundations-<unit>-<slug>`,
   `en-listen-<slug>`, `en-read-<slug>`, `checkpoint-<lang>-<level>-qN`.
10. **Skill-tag every lesson**: 3-5 skills, dominant grammar first, one
    `function:` at least, `pron:` only when genuinely drilled.

## Pedagogical bar

- One target grammar per lesson; examples > rules; every rule sentence in the
  intro ends in an example.
- Stories follow situation → action → result. Distractors are plausible, not
  silly; the `explain` quotes the exact line of text that answers the question.
- Chunks before isolated words (doc §58). Listenings B1+ use dialogue form;
  comprehension goes gist → detail → inference as level rises (doc §17-18).
- Bilingual quality is not negotiable: write like a native teacher, not a
  translator. If you cannot write it natively, say so instead of shipping it.

## Test counts to update after content changes

| Assertion | Kotlin | Swift mirror |
|---|---|---|
| en listening count + per-level `unitID` lists | `ListeningContentTest.kt` | `ListeningContentTests.swift` |
| en reading count + checkpoint id list | `ReadingContentTest.kt` | `ReadingContentTests.swift` |
| en-foundations units/lessons/levels + en course count + work track | `CoursesTest.kt` | — |
| non-en listening counts (6 each) | same | same |

The report helper prints the exact numbers to paste. Update BOTH languages'
mirrors when counts change.

## Per-batch workflow

1. `python3 <skill-dir>/scripts/content-report.py` — current state.
2. Author JSON (see `references/content-types.md` for the shapes).
3. Run all four exporters (`export_{courses,listening,readings,assessments}.py`).
   Fix validator complaints; they name the exact file and reason.
4. Update count assertions (table above) — both platforms.
5. `gradlew test` in `apps/android` + `python3 -m unittest discover -s
   scripts/tests -t .` + the four `--check`s + `check_cross_platform.py`.
6. Commit: `feat(content): <what> (<level>, <language>)` — one batch per
   commit, reports separate.
