# English Generated Exam Quality Audit Corrections Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` and `superpowers:test-driven-development` task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Close every verified gap between the English generated-exam implementation and its approved quality specification, and make the default API test command run DB-backed tests only against an isolated PostgreSQL test stack.

**Architecture:** Keep the existing English blueprint authoritative. Validate the final serialized snapshot against a single limit shared by generation and attempt sync; enforce the blueprint's explicit candidate quotas; check stimulus-level diversity after judge filtering; require bounded, evidence-based judge results; and preserve blueprint provenance and truthful token totals. Route `npm test` through the existing ephemeral Compose stack, with a clearly named fast/unit path and no caller `DATABASE_URL` fallback.

**Tech Stack:** Node.js 22, TypeScript, Fastify, PostgreSQL 17, Docker Compose, existing API test runner.

**Spec:** `docs/superpowers/specs/2026-10-24-english-generated-exam-quality-design.md`, findings in `informe-generacion-examenes-api-2026-10-24.md`, plus the correction requirements recorded in the matching OpenSpec change.

## Global Constraints

- Preserve the current public exam and grading shapes; additive internal DB provenance is permitted, but historical snapshots and grades are not rewritten.
- English-only validation and composition changes must not alter non-English behavior.
- Never use a caller's `DATABASE_URL` or production credentials for test execution; the full test path owns a disposable test database.
- Keep form totals 34/39/44/54/58 as pilot targets, not CEFR-readiness or psychometric claims.
- Provider calls with unknown outcomes remain terminal and are not retried.
- Use tests first and run the complete PostgreSQL-backed API suite before claiming completion.

## Review Focus

- A C1 snapshot near the largest allowed exam size is accepted by generation and the same sync contract, without exceeding the API request-body cap.
- A short but valid English lexical item is accepted, while clear non-English content remains rejected and all non-English baselines remain unchanged.
- A provider cannot satisfy the blueprint with all-choice/underspecified-option pools or leave variants sharing the same reading/listening stimuli after filtering.
- Missing/malformed judge evidence or usage cannot be represented as approval or fabricated zero totals.
- The normal full-suite command cannot inherit a developer or real-environment database URL, and teardown remains isolated on success and failure.

---

### Task 1: Enforce English item and candidate blueprint constraints

**Files:**
- Modify: `services/api/src/level-tests-validate.ts`
- Modify: `services/api/tests/level-test-blueprint.test.ts`
- Modify: `services/api/src/level-tests-llm.ts` and focused composition tests if served-form mix must be preserved.

**Interfaces:**
- Consumes: `ExamModelEntry.sections.*` hard `options` and `mix` requirements plus `poolTarget`.
- Produces: Deterministic errors for option-count and quota violations; English served recognition forms honor the scaled blueprint mix where configured.

- [x] Add a valid short recognition item (`Choose a synonym for happy.` / `glad`) and clear non-English control; preserve a non-English baseline.
- [x] Add an A1 pool test showing 24 all-choice items with three options currently pass, then assert the blueprint requires its declared 16 choice / 8 gap mix and four options.
- [x] Add served-form assertions for blueprint-scaled recognition mix.
- [x] Run focused tests and observe the expected RED results.
- [x] Implement English-only validator/composition changes; non-English behavior remains covered by baseline tests.
- [x] Run focused tests, API type-check, and isolated full suite: 681 passed, 0 failed, 0 skipped.
- [x] Work-unit commit: `88b10a8` (`fix(level-tests): close English generation quality gaps`).

### Task 2: Share snapshot sizing contract

**Files:**
- Modify: `services/api/src/level-test-attempts.ts` and `services/api/src/level-tests-llm.ts`; extract a shared snapshot-size contract only if needed.
- Modify: `services/api/tests/level-test-snapshot.test.ts`, `services/api/tests/level-test-attempts.test.ts`, and `services/api/tests/level-tests-llm.test.ts`.

**Interfaces:**
- Consumes: Existing canonical snapshot serialization and sync validation.
- Produces: One exported byte limit used before publication and during sync. Stimulus diversity is covered by Task 1's composition boundary.

- [x] Add a synthetic 58-item C1 snapshot-sync regression whose serialized size exceeds 96,000 bytes but stays below the shared limit and request-body cap; generation uses the same exported bound before persistence.
- [x] Add a judge-filtering test where IDs differ but remaining reading/listening stimulus sets are identical; assert generation fails closed (implemented with Task 1 composition checks).
- [x] Run focused test and observe RED, then GREEN.
- [x] Implement a shared 192,000-byte bound above the observed 103,773-byte C1 case and below the existing 256,000-byte API body bound; validate before persistence and in sync.
- [x] Run focused tests plus isolated PostgreSQL attempts: 14 passed, 0 failed, 0 skipped; full suite 681 passed, 0 failed, 0 skipped.
- [x] Work-unit commit: `88b10a8` (`fix(level-tests): close English generation quality gaps`).

### Task 3: Require judge evidence and preserve exact generation provenance

**Files:**
- Modify: `services/api/src/level-tests-llm.ts`, related admin serialization/tests.
- Create: next numbered SQL migration adding nullable blueprint provenance columns to generated-week rows.
- Modify: migration, generation, admin, and observability tests as applicable.

**Interfaces:**
- Produces: English judge entries with a closed reason code and short observable evidence (no chain-of-thought); generation rows with blueprint version/hash alongside prompt/judge versions; missing token usage represented as unknown in both success and failure results.

- [x] Add tests rejecting invalid/missing/oversized judge evidence and unknown reason codes; preserve valid verdict paths.
- [x] Add tests persisting blueprint version/hash and ensure thrown provider failure does not report `0/0`.
- [x] Confirm migration applies in the disposable PostgreSQL test stack.
- [x] Implement bounded judge parsing and deterministic blueprint hashing; add additive nullable provenance columns without changing historical attempts or grading.
- [x] Make failure paths omit unknown totals and preserve known totals only when the complete usage tuple is available.
- [x] Run focused tests, migration checks, type-check, and isolated full suite.
- [x] Work-unit commits: `88b10a8` (implementation) and `91c000b` (malformed-evidence regressions).

### Task 4: Make isolated PostgreSQL the default full API test path

**Files:**
- Modify: `services/api/package.json`, `services/api/tests/compose.smoke.yaml`, `Makefile`, `.github/workflows/checks.yml`, and API test docs if present.
- Add/modify: test-runner contract tests and a fail-closed test-URL guard only if needed to prove the full path cannot use caller DB configuration.

**Interfaces:**
- Produces: `npm test` runs the complete API suite in the existing disposable PostgreSQL Compose stack; `npm run test:unit` remains an explicitly partial/fast path; the container uses its fixed `app_container_test` URL and tears down its volume by default.

- [x] Verify full command routes to the isolated stack and ignores host database settings; Compose config rendered sentinel host URLs only as `app_container_test`.
- [x] Reuse the non-recursive compiled runner; CI now runs the full suite once.
- [x] Run `npm test`: 681 passed, 0 failed, 0 skipped; isolated C1 sync regression and PostgreSQL attempts tests: 14 passed, 0 skipped.
- [x] Verify teardown removed the Compose volume and network on success; the EXIT/INT/TERM cleanup remains in Makefile.
- [x] Run the full suite and API type-check.
- [x] Work-unit commit: `25aa7e0` (`test(api): use isolated postgres for full suite`).

### Task 5: Reconcile specification and verification evidence

**Files:**
- Create/update: `openspec/changes/english-exam-quality-audit-corrections/` proposal, design, tasks, and capability spec.
- Update: `openspec/changes/english-generated-exam-quality/tasks.md` only to cross-link this corrective change, not to rewrite historical records.
- Update: this plan with observed outcomes and commit IDs.

- [x] Record confirmed acceptance checks without claiming psychometric validity; pilot totals remain teacher-reviewed targets.
- [x] Run `npm test`, `npm run check`, `python3 scripts/export_leveltests.py --check`, and `make check`.
- [x] Verify `git diff --check`, migration numbering, and preserve all pre-existing untracked files.
- [x] Work-unit documentation commit: `cb74704` (`docs(level-tests): record exam quality audit corrections`).

### Task 6: Close final-review gaps in English publication, language detection, and version provenance

**Files:**
- Modify: `services/api/src/level-tests-publication.ts`, `services/api/src/level-tests-llm.ts`, `services/api/src/level-tests-validate.ts`.
- Modify: `services/api/tests/level-tests-policy.test.ts`, `services/api/tests/level-test-blueprint.test.ts`, and generation persistence tests as needed.
- Update: matching OpenSpec capability/tasks and this plan.

**Interfaces:**
- English manual approval and automatic generation must share final form checks for scaled recognition mix and cross-variant reading/listening stimulus diversity.
- English lexical prompts with clear English task vocabulary remain valid, including line breaks and tabs; content without positive English evidence is rejected without introducing support for other languages.
- New English prompt/judge contracts persist distinct versions; non-English version identities remain unchanged.

- [x] Add regressions for manual approval of rehashed but wrong-mix and duplicate-stimulus forms, valid short English lexical prompts, clearly Cyrillic foreign content, and English/non-English persisted version identities.
- [x] Run focused regressions and observe expected RED failures.
- [x] Implement the shared English served-form contract, evidence-based lexical gate, and English-only version bump.
- [x] Run focused tests (70/70), API type-check, isolated full suite (691/691, 0 skipped), `make check`, and level-test export check.
- [x] Independent final review and bounded recheck completed; French cognates were removed as English evidence. Work-unit commits: `6dcc86f` (shared publication gate, English evidence/versioning) and `7635969` (French false-positive and independent stimulus tests).
- [x] Add regressions proving line breaks/tabs are accepted and foreign Latin content without positive English evidence is rejected; observed both tests fail before the fix and pass after it.
- [x] Run focused blueprint tests (24/24), API type-check, isolated full API suite (693/693, zero skipped), `make check` (138 Python tests), level-test export check, and diff check. Existing Python `ResourceWarning`s about unclosed SQLite connections appeared during `make check`; the command passed.
- [x] Work-unit commit: `3d8093e` (`fix(level-tests): tighten English lexical validation`).

### Task 7: Preserve grammatical English evidence without requiring particular task verbs

**Files:**
- Modify: `services/api/src/level-tests-validate.ts`
- Modify: `services/api/tests/level-test-blueprint.test.ts`
- Update: matching OpenSpec spec/design/tasks and this plan.

**Interfaces:**
- Keep `validateGeneratedPool` synchronous and retain its public result shape.
- Add English-only grammatical evidence after the existing script guard, without changing task verbs, legacy stopword counts, non-English validation, dependencies, or judge configuration.
- Tokenize complete Unicode letter/mark sequences; recognize capitalized standalone `I` separately from lowercase foreign `i`.
- Only the prompt supplies new grammatical evidence. Exclude known cross-language collisions (`am`, `he`, `my`, `mine`, `ours`, `we`) from sufficient new evidence.
- Preserve existing foreign-language rejection and newline/tab controls. This remains a bounded language heuristic, not proof of arbitrary English-language identity.

- [x] Add failing public-validator regressions for alternative lexical instructions and fill-in sentences; assert both language errors and retention in the clean pool.
- [x] Cover lowercase Polish `i`, accented/decomposed French `forêt`, and non-Latin content with an English pronoun as rejection controls.
- [x] Observe RED (six valid-English failures), add grammatical English evidence, then observe GREEN. The independent review's four foreign collision regressions also observed RED before correction; corrected focused blueprint/publication tests pass 87/87.
- [x] Run focused tests (87/87), API type-check, final isolated PostgreSQL suite (708/708, zero skipped), contract/export checks (138 Python tests), and independent review plus bounded recheck. The earlier candidate's 704/704 suite is not used as proof of the final correction.
- [x] Record observed verification and work-unit commit: `3b8ac32` (`fix(level-tests): recognize grammatical English task evidence`).

## Log

- L1 (2026-10-24): “implementa los cambios reqqueridos en este último comentario que has compartido”
- L2: The first independent review found three acceptance gaps; implementation shared the final English form gate across generation and manual approval, added positive English lexical evidence plus non-Latin script rejection, and persisted English prompt/judge contract versions 3 while preserving other languages at 2. Regressions observed RED before implementation and GREEN afterward.
- L3: Independent review found shared French cognates could satisfy lexical English evidence and requested separate reading/listening stimulus regressions. Removed `correct` and `phrase` as positive English markers; added a French lexical rejection and separate stimulus-only approval regressions that preserve distinct item sets. Targeted recheck closed both findings.
- L4: Final evidence: 70/70 focused tests, API full suite 691/691 with zero skips on isolated PostgreSQL, type-check, root `make check` (138 Python tests), level-test export check, and `git diff --check` passed. The test stack removed its temporary database volume and network.
- L5 (2026-10-24): “pasa a resolver los 2 problemas detectados”
- L6: The final review identified two reproducible lexical-gate defects: control whitespace was classified as non-Latin, and absence of foreign markers was still accepted as English evidence. Regression tests reproduce both; the fallback now permits whitespace and fails closed without positive English evidence.
- L7: Verification passed: focused 24/24; isolated API 693/693, zero skipped, with the ephemeral PostgreSQL volume and network removed; API type-check; root `make check` (138); export check; diff check. `make check` emitted existing SQLite `ResourceWarning`s but exited successfully. Commit: `3d8093e`.
- L8 (2026-10-24): “resuelvelo”
- L9: Ruling: supplement English lexical evidence with general grammatical evidence instead of adding individual task verbs or restoring the permissive Latin fallback. Unicode tokens avoid interpreting `forêt` as `for`, and capitalized `I` avoids accepting Polish `i`. The optional judge cannot serve as an implicit mandatory language gate, so ambiguous inputs still fail closed. This fixes the reported valid-English patterns without claiming universal language classification.
- L10: Independent review reproduced newly accepted German `am`, Spanish `he`, Polish `my`, and an isolated Norwegian answer `for`. One bounded correction removes colliding sufficient markers and restricts the new evidence to the prompt. Four added regressions failed before that correction and pass afterward; the original valid-English cases remain retained.
- L11: Final verification: 87/87 focused tests; TypeScript passed; 708/708 complete API tests with zero skips on disposable PostgreSQL, with database volume and network removed; `make check` passed 138 Python tests and repository contracts, retaining existing SQLite ResourceWarnings; level-test exporter and diff checks passed. Independent bounded recheck closed all four foreign-collision cases while retaining all four reported English cases.
- L12: Work-unit commit: `3b8ac32`. Risk: none; the change is confined to requested English validation behavior, with no public payload, non-English marker, prompt/judge version, dependency, database, or deployment changes.
