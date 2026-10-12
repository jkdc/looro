# Tasks: level-tests-llm

## Task 1 — Storage & providers

- [x] 1.1 Migration 041: `llm_provider_configs` (encrypted key, single
      active via partial unique index), `level_test_settings` (publish/verify
      mode, variants), `level_test_weeks` (unique lang+level+week, immutability
      trigger on approved).
- [x] 1.2 `src/llm-config.ts`: presets (openai/glm/opencode), AES-256-GCM
      encrypt/decrypt + tamper detection, config resolution (DB active →
      env → none), OpenAI-compatible chat transport (JSON extraction).
- [x] 1.3 `src/llm-config-admin.ts`: admin API shapes (masked key, presets,
      publish/verify modes, upsert with encryption, connection test).

## Task 2 — Exam model, prompts, gate 1

- [x] 2.1 `shared/leveltests/exam-models.json` (A1–B1, EN/ES wildcard) +
      emitted into the server bundle by `export_leveltests.py`.
- [x] 2.2 `src/level-tests-validate.ts`: gate-1 validator (schema, single
      correct choice answer, passage ranges, disjoint blocks, in-generation
      dedupe, language heuristic, section minimums) returning per-section
      errors for directed retries.

## Task 3 — Generation pipeline

- [x] 3.1 `src/level-tests-llm.ts`: prompt builders (generation v1 + judge
      rubric v1), orchestration (LLM pool → gate 1 retries → gate 2 judge
      flag/strict → K variants via stable-hash blocks → persistence),
      publish-mode approval, token accounting.
- [x] 3.2 `src/level-tests-generate.ts` CLI (`--week/--langs/--levels/
      --force/--approve`) + npm script; idempotent per week.

## Task 4 — Endpoints

- [x] 4.1 `GET /v1/level-tests/generated` (404 `level_test_not_generated`
      when absent; `?variant=`; ETag per variant) and
      `GET /v1/level-tests/hybrid` (LLM → deterministic fallback); proxy-
      exempt; param validation (400) and pool-aware 404.
- [x] 4.2 Admin endpoints: `GET/PUT /v1/admin/llm-config`,
      `POST /v1/admin/llm-config/test` (auth + CSRF, keys never returned).
- [x] 4.3 `level_test_weeks` added to `admin-browse`.

## Task 5 — Verification

- [x] 5.1 Unit: validator fixtures (good/bad), crypto round-trip + tamper,
      resolution precedence, variant reproducibility/disjointness, judge
      parsing (flag/strict).
- [x] 5.2 HTTP: generated 404 → draft+approve → 200 with variant; hybrid
      fallback; ETag/304; admin auth gates. Server suite green on PG 17.
- [x] 5.3 Pending gates (documented, not code): real-provider generation
      run, competent-speaker content review, admin panel web page.
