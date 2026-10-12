# Tasks: level-tests-coverage

## Task 1 — Language names + B2/C1 exam models

- [x] 1.1 `LANGUAGE_NAMES` map (8 languages) in `level-tests-llm.ts`; the
      generation prompt uses the target-language name (not just English/Spanish).
- [x] 1.2 B2/C1 exam models in `shared/leveltests/exam-models.json` (wildcard
      languageID, same structure as A1–B1) + `EXAM_MODELS` regenerated.
- [x] 1.3 Gate-1 `TARGET_MARKERS` extended to fr/de/it/pt/nb/zh (zh uses CJK
      character presence; European languages get stopword regexes).

## Task 2 — Full-grid generation

- [x] 2.1 Generation run: 8 languages × A1–C1 = 40 weekly tests approved
      against GLM 5.3 Flash (z.ai coding endpoint).
- [x] 2.2 Serving verified: `/v1/level-tests/generated` returns 200 with
      25 items × 3 variants for all 40 combos.

## Task 3 — Verification

- [x] 3.1 Server suite green (464+ tests, Node 20, PG 17 ephemeral).
- [x] 3.2 Content first-pass: language correctness spot-checked per language;
      flagged items annotated for the human review gate.
