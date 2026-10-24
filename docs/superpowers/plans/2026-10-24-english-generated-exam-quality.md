# English Generated Exam Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve the quality, level differentiation, and learner-facing length of English API-generated level exams.

**Architecture:** A single typed English blueprint governs prompts, deterministic validation, judge, pool/form composition, and publication checks. The candidate pool and served form are explicitly separate; compatibility preserves existing item schema and history.

**Tech Stack:** Node.js 22, TypeScript, Fastify, PostgreSQL integration tests, canonical shared JSON/Python exporter.

**Spec:** `docs/superpowers/specs/2026-10-24-english-generated-exam-quality-design.md` and `openspec/changes/english-generated-exam-quality/`.

## Global Constraints

- English-only behavior changes; leave all other languages, including Chinese, unchanged.
- Do not generate/edit authored banks or generated app data.
- Do not retry provider calls with unknown outcomes.
- Do not rewrite existing snapshots or historical grades.
- Treat form totals as pilot targets, not psychometric validity or readiness guarantees.
- Run deterministic tests test-first and retain existing integration patterns.

## Review Focus

- A larger form stays under all API/snapshot/client limits; assert full composition with maximum English level shape.
- Invalid provider JSON and null members return structured errors without partial persistence; test through public validator/generation path.
- Provider timeout/unknown result remains one call; test the call count and absence of new generation rows.
- A judge reject/flagged result cannot be accidentally treated as clean; assert persisted pool, variants, and publication state.
- Non-English requests and existing snapshots are byte/behavior compatible; add a targeted regression for touched shared resolution paths.

---

### Task 1: Blueprint and validator contracts

**Files:**
- Modify: `services/api/src/level-tests-validate.ts`
- Modify: `services/api/tests/level-tests-llm.test.ts` or add focused validator tests under `services/api/tests/`
- Modify: `shared/leveltests/exam-models.json` only if a canonical English shape field is required
- Generate: API model bundle only through `python3 scripts/export_leveltests.py`

**Interfaces:**
- Consumes: Existing `ExamModelEntry`/`examShape` model and current API item types.
- Produces: A pure, typed resolved English blueprint accepted by generation, validation, judge, composition, and publication; exact exported name/signature finalized from current module boundaries before implementation.

- [x] Write tests for English A1/C1 distinct requirements, invalid/null section members, wrong item kind, option membership, duplicate full-text passages, and writing type rules.
- [x] Run focused test and observe RED: missing blueprint module, null-item `TypeError`, writing choices accepted, and duplicate reading text accepted.
- [x] Implement typed exact-language-first blueprint resolution and deterministic English section/passage checks; preserve wildcard fallback behavior.
- [x] Run focused tests to GREEN: 9 passed, 4 PostgreSQL tests skipped; API type-check passed.
- [x] Run exporter `--check`: `level test data up to date`; `git diff --check` passed.
- [x] Commit task: `fix(level-tests): validate English generated sections against blueprint`.

### Task 2: Prompt and judge alignment

**Files:**
- Modify: `services/api/src/level-tests-llm.ts`
- Modify: `services/api/tests/level-tests-llm.test.ts`

**Interfaces:**
- Consumes: Typed resolved English blueprint from Task 1.
- Produces: Capturable prompts that use per-level targets and an active judge rubric requiring concise evidence, using server IDs and closed result codes.

- [x] Test captured A1/C1 generation and judge messages for the actual level-specific requirements and correct passage/question context.
- [x] Observe RED with the fixed prompt/judge: new prompt functions were not exported.
- [x] Connect blueprint fields to English prompt and active judge; leave other languages on their previous prompt path.
- [x] Add judge prompt requirements for correction support, passage evidence, distractors, level/task fit, and format; remove the unused alternate judge builder.
- [x] Run focused generation tests: 12 passed, 4 PostgreSQL tests skipped; API type-check and exporter check passed.
- [x] Commit task: `feat(level-tests): align English generation prompts and judge with blueprint`.

### Task 3: Bounded per-section repair

**Files:**
- Modify: `services/api/src/level-tests-llm.ts`
- Modify: `services/api/tests/level-tests-llm.test.ts`

**Interfaces:**
- Consumes: Section validation errors from Task 1 and active prompt/judge contracts from Task 2.
- Produces: At most one repair call for a known repairable invalid section; structured terminal failure otherwise.

- [x] Test distinct affected-section call count, sanitized feedback, and unknown-outcome stop behavior.
- [x] Observe RED: repair helper export missing.
- [x] Implement one repair call per affected section using only validation locations/codes, then revalidate the full pool.
- [x] Parse malformed/null response members into validation failures; provider-unknown outcomes stop without retry.
- [x] Run focused tests: 12 passed, 4 PostgreSQL tests skipped; API type-check passed.
- [x] Commit task: `fix(level-tests): repair invalid generated sections once`.

### Task 4: English form length, candidate pool, and diversity

**Files:**
- Modify: `shared/leveltests/exam-models.json`
- Modify: `services/api/src/level-tests-llm.ts`
- Modify: `services/api/src/level-tests-publication.ts` if contract verification is required
- Modify: `services/api/tests/level-tests-llm.test.ts`, policy/publication tests as needed

**Interfaces:**
- Consumes: Blueprint shape and section validator.
- Produces: English pilot form targets A1–C1 of 34/39/44/54/58 closed items; separately configured candidate pool and diversity evidence; existing response item schema.

- [x] Test requested candidate counts differ from served form counts; observe RED: exported composition helpers missing and configured form remained 25 items.
- [x] Test all English form totals and double-sized candidate targets (A1–C1), plus A1 variant item/stimulus set divergence.
- [x] Implement English pool requests from `poolTarget`, per-level form sizing from `examShape`, and validator enforcement of post-filter candidate minima.
- [x] Implement deterministic, spaced selection so configured variants differ when the pool exceeds one form; preserve wildcard/non-English shape behavior.
- [x] Keep maximum form (58) below existing 100-item snapshot cap; update public API integration expectation to 34 A1 items.
- [x] Run focused tests: 18 passed, 4 PostgreSQL tests skipped; API type-check and exporter `--check` passed.
- [x] Commit task: `feat(level-tests): expand English generated exam form by CEFR level`.

### Task 5: Judge verdict and publication policy

**Files:**
- Modify: `services/api/src/level-tests-llm.ts`
- Modify: `services/api/src/level-tests-publication.ts`
- Modify: `services/api/tests/level-tests-llm.test.ts`
- Modify: `services/api/tests/level-tests-policy.test.ts`

**Interfaces:**
- Consumes: Typed judge verdict/result codes from Task 2.
- Produces: Rejected items excluded; unresolved `flagged` items cannot be silently treated as clean for English publication; human-review policy remains auditable.

- [ ] Repair the misleading rejection test so its mock really returns `reject`; cover `flagged`, missing, duplicate, and unknown IDs.
- [ ] Observe RED for current acceptance of flagged English content under strict publication.
- [ ] Align generation and publication semantics; maintain existing review/publish workflow for explicit human decisions.
- [ ] Assert rejected or unresolved item IDs never appear in served variants and failed publication leaves state unchanged.
- [ ] Run generation/policy/publication tests.
- [ ] Commit task: `fix(level-tests): block unresolved flagged English items from publication`.

### Task 6: Usage and provenance

**Files:**
- Modify: `services/api/src/level-tests-llm.ts`
- Modify: provider result types/transport only if necessary after checking consumers.
- Modify: focused usage and failure tests.

**Interfaces:**
- Consumes: Existing LLM transport replies and generation row persistence.
- Produces: Per-run known token totals and version/hash provenance for prompt, judge, and blueprint, with absent usage represented as unknown/partial.

- [ ] Test exact usage aggregation across generation, judge, and repair on success and failure, without double counting.
- [ ] Observe RED for current always-zero run totals.
- [ ] Implement a single aggregation boundary and compatible persistence using existing columns; avoid migrations unless unavoidable.
- [ ] Test missing provider usage remains unknown, never a fabricated confirmed zero.
- [ ] Run focused generation and persistence tests.
- [ ] Commit task: `fix(level-tests): persist known generation usage and provenance`.

### Task 7: Editorial skill guidance

**Files:**
- Modify: `.agents/skills/looro-content/SKILL.md` only if a specific editor-facing gap remains after the runtime contract is documented.
- Test/read back: relevant skill documentation.

- [ ] Ensure guidance distinguishes authored learning content from runtime-generated exams and describes English quality evidence without claiming the API loads the skill.
- [ ] Preserve exporter/content-authoring rules and non-English content support.
- [ ] Structurally read back the skill; no artificial test harness.
- [ ] Commit task: `docs(skills): clarify runtime exam quality guidance` only if an edit is warranted.

### Task 8: End-to-end verification and limitations

**Files:**
- Update: `openspec/changes/english-generated-exam-quality/tasks.md`
- Update: `docs/superpowers/plans/2026-10-24-english-generated-exam-quality.md`
- Optionally update report only if a factual correction is needed; preserve original audit findings.

- [ ] Run API focused test(s), policy/snapshot tests touched, and API type-check.
- [ ] Run exporter `--check` and `make check` if model JSON or shared content was touched.
- [ ] Run `make test-api` against the ephemeral stack if available; report environmental failures honestly.
- [ ] Verify diff/status and confirm pre-existing untracked files were not altered.
- [ ] Record that teacher review/learner pilot remain necessary for CEFR/readiness claims.
