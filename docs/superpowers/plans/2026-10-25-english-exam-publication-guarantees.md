# English Exam Publication Guarantees Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` and `superpowers:test-driven-development`. Execute on the active feature branch, without creating a worktree.

**Goal:** Implement the user-approved English-only technical guarantees before collecting pedagogical evidence.
**Architecture:** Keep uncertain language content in private candidates, but require explicit judge resolution for publication. Freeze the complete blueprint at generation start and store it with a canonical hash. Record provider usage incrementally on the existing generation run, including failures and interrupted calls.
**Tech Stack:** TypeScript, Node 22, PostgreSQL 17, existing isolated Docker test stack.
**Spec:** `openspec/changes/english-exam-publication-guarantees/design.md` and the user's action plan of 2026-10-25.

## Global Constraints

- English only. Do not expand banks or introduce open writing/speaking.
- `verifyMode=off` may produce private drafts, never publish English exams.
- Do not change previously published snapshots or historical grading.
- Add new migrations; do not rewrite applied migrations or invent legacy contracts.
- Unknown provider outcomes are terminal; preserve known usage without fabricated zeros.
- Human approval of the skill matrix and editorial examples, followed by a consented pilot, cannot be manufactured by code.

## Review Focus

- Both automatic publication and manual approval require complete, bounded, explicit language/quality judge evidence.
- Foreign passages/explanations cannot borrow English evidence from their questions; uncertain proper names/short answers remain reviewable.
- JSONB key ordering does not invalidate frozen hashes; current blueprint edits never reinterpret frozen drafts.
- A failed or interrupted call preserves previously reported input/output usage independently.
- Non-English publication, existing snapshots and isolated test teardown retain their previous behavior.

### Task 1: Enforce English judge publication policy
- [x] Add RED regressions for off-mode drafts and denied manual approval without complete evidence.
- [x] Require clean per-item judge evidence in both publication paths; retain non-English behavior.
- [x] Run focused policy tests; record results.

### Task 2: Complete language validation and resolution
- [x] Add RED tests for Spanish passages/explanations, uncertain short text and proper names.
- [x] Add pass/fail/unknown language assessments for every content field. Reject clear failures; retain uncertain candidates for judging.
- [x] Require the judge to explicitly resolve language for all supplied fields, with unknown/failed language incompatible with an `ok` verdict.
- [x] Run focused validation and pipeline tests.

### Task 3: Freeze generation contracts
- [x] Add RED generation/publication tests: current-model changes, legacy drafts, mismatches and tampered contracts.
- [x] Persist a cloned complete English blueprint with canonical SHA-256 hash/version in a new migration.
- [x] Approve English drafts only against the verified frozen contract; legacy drafts require regeneration. Preserve published rows.
- [x] Run focused publication/type tests and PostgreSQL migration tests.

### Task 4: Persist failed-run usage
- [x] Add RED tests for complete, partial, unknown, explicit zero and interrupted usage.
- [x] Add nullable known input/output totals and usage state to run records in a new migration.
- [x] Checkpoint usage before/after provider calls; retain known amounts on parse/validation/transport/persistence failures.
- [x] Run focused tests and real isolated PostgreSQL tests.

### Task 5: Pedagogical gate
- [ ] Obtain a named pedagogical owner's approved per-level matrix and reviewed/versioned examples with complete passages, keys and evidence.
- [ ] Implement catalog-validated retained assessed-skill tags, composition coverage and curated examples against that approved input.
- [ ] Do not substitute arbitrary quotas or generated examples for approval. This task remains externally pending until those inputs exist.

### Task 6: Verify and document
- [x] Run full isolated PostgreSQL API suite, typecheck, contracts and exporters; no DB skips.
- [x] Run independent whole-change review and address concrete important findings with RED/GREEN tests.
- [x] Record human pilot prerequisites separately; do not claim pedagogical readiness from engineering tests.

## Log

- 2026-10-25: User explicitly requested implementation of the supplied six-stage action plan, including mandatory English judge publication evidence. Baseline `495689b`, active branch `feat/exam-generation-readiness`.
- Ruling: Use the project's explicit Superpowers-only workflow rather than the conflicting generic Gentle AI routing. Do not let an unavailable Gentle AI worker alias block local implementation. Preserve the human approval gates in Tasks 5/6.

- Tasks 1/2: observed 3 policy RED failures and 4 language RED failures; GREEN policy/blueprint tests 94/94, typecheck passed. English judge version is now 4; other-language identities unchanged.
- Task 3: observed 4 frozen-contract RED failures plus JSONB reorder RED; GREEN focused tests 99/99 and typecheck passed. Migration 052 awaits real isolated PostgreSQL verification.
- Task 4: 6 usage regressions observed RED; durable PostgreSQL regressions are running in the full isolated stack before implementation.

- Task 4: independent-direction usage tracker plus migration 053; six focused usage regressions observed RED, two PostgreSQL durable-run regressions observed RED, then the first complete isolated API candidate passed 730/730 with zero skips. This is not proof of the later correction bytes.
- Final review: one independent reviewer found two Important issues: transient post-call checkpoint loss, and concatenation of uncertain city-name options. Both reproduced with new RED tests. One correction batch records latest outcome usage in final closure and assesses individual alternatives. Targeted PostgreSQL/public-pipeline recheck passes 3/3, zero skips; focused tests pass 123/123 and typecheck passes. Final isolated full-suite rerun passed 733/733 with zero skips.
- Review declined external teacher approval, curated examples, consented pilot and empirical CEFR claims. Ruling: retain all as explicit external gates, not code completion; no approved pedagogical inputs were supplied. Pre-baseline changes are not part of this review.
- Root contracts/exporters pass: 138 Python tests plus cross-platform/schema checks and level-test export. Existing SQLite ResourceWarnings remain visible and non-failing. A host emitting build hit pre-existing unwritable dist files; Docker compilation and no-emit typecheck work without changing ownership or permissions.

- Final verification: 733/733 API tests with zero failures/skips; 123/123 focused; 3/3 targeted review regressions; typecheck, contracts (138 Python tests), exporter and diff checks pass. Disposable PostgreSQL volume/network removed. Source changed only on the active feature branch; no production/remote/provider operations. Task 5 and human pilot remain explicitly pending approved external inputs.
