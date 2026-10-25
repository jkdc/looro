# English Exam Coverage and Runtime Rubrics Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` and `superpowers:test-driven-development` on the active branch. No worktrees, provider credentials, deployment, push or PR.

**Goal:** Make LLM-reviewed English capability coverage and complete reference examples executable constraints of generation and composition.
**Architecture:** Author one versioned canonical assessment design, reviewed in a separate LLM pass. The exporter embeds it into each English blueprint; the frozen contract therefore owns the exact coverage, reference examples and review provenance. Keep assessed metadata private to the pool/review, and project the unchanged learner snapshot. Use bounded deterministic selection and the existing generator/judge roles, not a new agent framework.
**Tech Stack:** Existing Python exporters, JSON skill catalog, TypeScript API, PostgreSQL isolated test stack.
**Spec:** `openspec/changes/english-exam-coverage/design.md`; user approval of runtime-rubric architecture and “adelante con la implementación”.

## Constraints and Review Focus
- English A1–C1 only; existing form lengths, public/mobile shapes and historical snapshots unchanged.
- LLM review is automated evidence, not a teacher signature, learner pilot or CEFR certification.
- Matrix is authored/reviewed once per revision, not regenerated each week. Every newly composed form must cover its agreed capabilities.
- Only catalog IDs count as assessed skills; exposure metadata never satisfies coverage. A label is a semantic claim checked by the judge, not truth merely because it exists.
- Existing kind quotas and passage grouping remain binding alongside coverage. Fail with explicit capacity errors rather than silently lowering constraints; bound selection work.
- Complete comprehension examples include the full source and all keys/support evidence, not orphan passage IDs. Unreviewed artifacts cannot activate.
- Legacy frozen models without assessment metadata retain their recorded contract and judge-evidence schema.

### Task 1: Canonical design and automated review
- [x] Author per-level/section coverage and complete examples using existing anchors; add missing canonical capability IDs only where needed.
- [x] Obtain and record a separate LLM pedagogical review with model, rubric version, findings and exact content hash. Correct substantive findings before activation.
- [x] Add exporter validation tests (RED), implement validated API-only embedding and regenerate (GREEN). Do not edit generated files manually.

### Task 2: Runtime metadata, rubrics and candidate gates
- [x] Add RED tests for reviewed references in actual generation messages, valid/unknown/missing/wrong-section assessed IDs, semantic tag evidence and exposure-only claims.
- [x] Implement typed assessment contract and reusable versioned design/generation/judge instructions. Preserve internal `assessedSkill` through normalization and persistence, excluding it from public snapshots.
- [x] Validate catalog-bound coverage candidates and strengthen judge evidence for assessed-skill alignment; preserve legacy evidence for frozen legacy models.

### Task 3: Composition and publication
- [x] Add RED tests for coverage-aware deterministic flat/passage selection with kind constraints, infeasible coverage and bounded search; all levels and variants.
- [x] Implement bounded deterministic coverage selection, then final per-variant coverage validation against persisted pool tags in both auto and manual publication.
- [x] Prove tag loss, judge filtering, forged hashes, current matrix changes and historical frozen models cannot bypass the intended contract.

### Task 4: Verification and delivery
- [x] Run focused tests, full isolated PostgreSQL suite (zero DB skips), types, exporters and root contracts.
- [x] Independent whole-change review; one bounded correction pass with RED/GREEN for important findings.
- [x] Update policy/docs/previous pending tasks to reflect LLM approval. Actual authorized learner pilot and deployment remain pending.
- [x] Conventional work-unit commits on current branch; preserve original untracked artifacts.

## Log
- Baseline `eb911af`, branch `feat/exam-generation-readiness`. The user approved LLM-designed/reviewed coverage and runtime-loaded rubrics, then explicitly authorized implementation.
- Ruling: This document formalizes the already-approved architecture; no further teacher-ownership or framework-choice question is needed. LLM review must be labeled automated and recorded, not fictional human approval.
- Ruling: Minimum presence of each agreed capability is an engineering pilot constraint, reviewed by a separate LLM; not an empirically validated weighting or cut score. Existing delivered totals remain unchanged.

- Task 1 evidence: independent initial review required changes; targeted correction readback approved hash `12709148…9fe9cbc`. Exporter tests observed RED (7 failures) then GREEN (3 tests). Both exporters `--check` pass; root `make check` passes 141 Python tests and cross-platform contracts (pre-existing non-failing SQLite ResourceWarnings).
- Task 2/3 in progress: initial runtime RED missing assessment module; coverage/metadata implementation and 131 focused regression tests now pass. Internal-tag snapshot leakage reproduced RED and fixed GREEN. Full isolated PostgreSQL suite is running; final source verification and independent review remain.

- Independent runtime review: one Important uneven-capability stimulus-selection defect. Regression observed RED (`stimulus_diversity` despite an available distinct set); novelty-mask DP correction GREEN, focused132/132. No Critical or deferred Minor findings. Final isolated PostgreSQL suite748/748, zero skipped; `make check`141 Python tests plus exporters/contracts, types and `git diff --check` pass.

- Completed work units: `26993ba` reviewed canonical design/exporter; `a61493a` runtime rubrics, metadata, coverage/diversity composition, approval gates, regressions and verification docs. Technical implementation complete. No configured-provider execution, native app build, learner pilot or deployment claimed. Active branch retained; no push/PR/merge.
