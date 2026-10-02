# Content types: schemas and worked examples

One section per authored file type. Field names are exact — the validators
reject unknown-but-required and missing-required alike.

## Lesson (in `shared/courses/<lang>-foundations.json`)

Units carry `id, title, subtitle, level, themes[≥1], lessons[≥1]`. Themes:
`{id, title, subtitle, symbol, category: "Course"|"Connection", situation, colorIndex}`.

```json
{
  "id": "en-foundations-change-will",
  "title": "Will: decisions & predictions",
  "goal": "One sentence, learner-facing, verb-visible.",
  "intro": {
    "explanation": ["2-4 bullets, each rule + inline example."],
    "examples": [
      {"text": "I'll help you with that.", "meaning": "Spontaneous offer."}
    ]
  },
  "expressions": [
    {"text": "I'll + verb", "meaning": "Spontaneous decision.", "note": "I'll get the door."}
  ],
  "situation": "The Talk scenario the practice questions walk through.",
  "practice": {
    "questions": [
      {"prompt": "Offer to carry the bags.",
       "expected": ["I'll carry the bags.", "I'll carry them."],
       "focus": "I'll + verb",
       "watch": "The tutor's recast guidance when the learner misses."}
    ]
  },
  "exercises": [
    {"id": "en-foundations-change-will-e1", "kind": "choice", "prompt": "I ___ help you.",
     "options": ["'ll", "will to", "am", "do"], "answers": ["'ll"], "explain": "will + base verb."},
    {"id": "…-e2", "kind": "gap", "prompt": "I ___ call you later. (promise)", "answers": ["will", "'ll"], "explain": "…"},
    {"id": "…-e3", "kind": "order", "prompt": "Predict the match.",
     "answers": ["Spain will win the match"], "explain": "…"},
    {"id": "…-e4", "kind": "written", "prompt": "Make a prediction about the weather.",
     "answers": ["it will rain", "it'll rain", "it will be sunny"], "explain": "…"}
  ],
  "skills": ["grammar:future-forms", "function:make-future-plans"],
  "canDo": ["make-future-plans"],
  "prerequisites": ["grammar:verb-be"],
  "exposureSkills": []
}
```

Rules: 6 expressions, ≥6 practice questions, 4 exercises (one per kind is the
house pattern), ≥3 exercises required by the validator. Exercise `kind` ∈
`choice | gap | order | written`; `order` = ONE answer, 3+ words; `gap` needs
`___` in the prompt; `written` answers support `<placeholder>` spans.

## Listening (in `shared/listening/<lang>-listening.json`)

Root: `{"schemaVersion": 2, "texts": [...]}`.

```json
{
  "id": "en-listen-monday-predictions",
  "languageID": "en",
  "title": "Monday predictions",
  "level": "B1",
  "topic": "work",
  "unitID": "en-foundations-change",
  "text": "40-200 words. The transcript is what the TTS reads aloud and what is revealed at the end. B1+ writes dialogue.",
  "questions": [
    {"id": "en-listen-monday-predictions-q1", "kind": "choice", "prompt": "…",
     "options": ["…", "…", "…", "…"], "answers": ["…"], "explain": "Quote the exact line."}
  ],
  "skills": ["listening:detail", "grammar:future-forms"],
  "exposureSkills": []
}
```

Minimum 5 questions; mix `choice` (2-4 options) and `written` (2-4 accepted
variants, no options). `unitID` optional but level-matched; without it the
text sorts as a topic-only extra of its level.

## Reading (in `shared/readings/<lang>-readings.json`)

Root: `{"schemaVersion": 1, "readings": [...]}`. Same text shape as
listenings with `readings` key: 40-220 words, **minimum 4 questions**, and
the text STAYS VISIBLE — so inference and scan questions are fair game.

## Checkpoint questions (in `shared/assessments/<lang>-checkpoints.json`)

Root: `{"schemaVersion": 1, "checkpoints": [...]}`.

```json
{
  "id": "checkpoint-en-b1",
  "languageID": "en", "level": "B1", "title": "B1 checkpoint",
  "thresholds": {"overall": 70, "grammar": 60, "vocab": 60},
  "questions": [
    {"id": "checkpoint-en-b1-qN", "kind": "choice|written|gap|order", "skill": "grammar:present-perfect",
     "prompt": "…", "options": [], "answers": ["…"], "explain": "…"}
  ]
}
```

Minimum 12 questions. Every question carries the ONE `skill` it examines;
spread them so each thresholded category has ≥3 items. Kinds reuse the
Exercise rules (gap needs `___`, order needs one 3+ word answer).

## Skill catalog (in `shared/skills/skill-catalog.json`)

```json
{"id": "grammar:phrasal-verbs", "category": "grammar", "prerequisites": ["grammar:present-simple"]}
```

Categories: `grammar vocab function speaking listening reading writing pron`.
`prerequisites` reference existing ids. Can Do matrix
(`shared/skills/cando.json`): `{"id": "describe-goals", "label": "…"}`.
