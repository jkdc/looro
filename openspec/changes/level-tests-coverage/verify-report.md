# Verify report: level-tests-coverage

Date: 2026-10-09 · Branch `feature/learning-platform`.

## What ran green

- **Server suite: 464 tests, 0 failures** (Node 20.19.4, PG 17 ephemeral).
  The suite was re-run after the engine refactor to hash-block composition
  and the language-name map changes.
- **Gate-1 heuristic extended**: fr/de/it/pt/nb stopword markers + zh CJK
  presence check verified by `npx tsc --noEmit` (0 errors).
- **B2/C1 exam models**: exported to both the Kotlin bundle and the server
  content mirror (`EXAM_MODELS` in `level-tests-content.generated.ts`).

## Real generation run (2026-10-09, in progress)

- Full grid: 8 languages × A1–C1 = 40 weekly tests launched with `--force`
  against GLM 5.3 Flash (z.ai coding endpoint, app's own key). EN/ES were
  regenerated with `--force`; fr/de/it/pt are new. nb/zh excluded (no
  temario harvest).
- **This is still running** — each test takes 2–3 min (LLM pool call + judge
  call). Progress tracked via `level_test_weeks` where `status='approved'`.
- Early results: en × A1-C1 and es × A1-C1 all approved (10/10), fr in
  progress. The judge flagged items in en/A2 (8), en/B1 (6), es/A1 (2),
  es/A2 (2), es/B1 (1) — mostly open-gap answer-key completeness and
  distractor uniqueness. Flagged items are annotated for the human pass.

## Defects caught and fixed during verification

1. **Composer ordering**: listening passages filtered before reading picks
   finalized — fixed (listening filter now runs after reading selection).
2. **Ledger id case**: `levelcheck-en-A1` → lowercased for the server slug.
3. **Prompt language map missing**: non-English/Spanish languages received
   items written in the wrong language — fixed with `LANGUAGE_NAMES` map.

## Known gaps / open gates

- **Competent-speaker review** of all generated content (flagged items
  annotated, awaiting disposition).
- **App consumption** of generated/hybrid (needs `MANAGED_API_ORIGIN`
  deployed).
- **Admin panel web page** (backend admin API shipped).
- nb/zh deterministic pools and temario (no harvest content exists).
