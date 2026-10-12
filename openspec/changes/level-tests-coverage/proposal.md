# Proposal: level-tests-coverage

## Intent

Fill the weekly level-test coverage gap: from 6 weeks (EN/ES × A1–B1) to the
full grid of 8 languages × 5 CEFR levels (A1–C1) = 40 weekly tests, plus the
infrastructure fixes that make it possible (language-name map in the
generation prompt, B2/C1 exam models, per-language stopword markers in the
gate-1 validator).

Analysis and phases: `informe-relleno-niveles-idiomas-2026-10-09.md`.

## Problem

`buildGenerationMessages` hardcodes `languageID === 'es' ? 'Spanish' :
'English'` — every non-English/Spanish language would receive items written
in the wrong language. `exam-models.json` only defines A1/A2/B1 (no B2/C1),
and the gate-1 language heuristic only carries markers for EN/ES.

## Approach

1. `LANGUAGE_NAMES` map (8 languages) used by the generation prompt.
2. B2/C1 exam models (wildcard languageID, same shape as A1–B1).
3. Gate-1 stopword markers extended to all 8 languages (CJK character
   presence for zh; stopword lists for the European languages).
4. Generate all 8 × 5 = 40 weeks against GLM 5.3 Flash.

## Out of scope

- App consumption of generated/hybrid (needs `MANAGED_API_ORIGIN` deployed).
- Admin panel web page (backend API shipped).
- nb/zh deterministic pools and temario (no harvest content exists; the
  weekly tests can still be LLM-generated, but the preparation lessons
  cannot).
- Competent-speaker review of the 8×5 generated weeks (flag-mode annotates;
  review is the human's pass).
