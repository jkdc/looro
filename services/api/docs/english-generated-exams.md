# English generated exam guarantees

## Publication policy

English exams require an active judge before publication, including manual approval. `verifyMode=off` still generates material, but the result is a private draft even with `publishMode=auto`. An operator cannot approve that draft without complete judge evidence; regenerate with verification enabled. Non-English policy is unchanged.

Every retained English item needs exactly one `ok` verdict, bounded observable evidence (8–240 characters), a closed reason code, and `languageStatus=pass`. The judge examines the question, answers, distractors, explanation and full supplied passage, independently. `unknown`/`fail` language cannot accompany `ok`. Missing, duplicate, malformed, flagged or rejected evidence cannot silently authorize publication.

The deterministic language assessment reports `pass`, `fail` or `unknown` for each field. It rejects clear failures; unknown fragments, names and loan words are deferred to the English judge in the pipeline. Standalone synchronous validation retains conservative prompt rejection unless its caller explicitly enables deferred review. Marker evidence is a bounded heuristic, not a guarantee of language identity. No additional provider call is introduced solely for language review.

## Frozen generation contract

New English generations clone their complete blueprint before asynchronous work. Migration `052_level_test_frozen_blueprint.sql` adds `blueprint_contract`. Format version 2 stores the full contract and its SHA-256 hash using recursively ordered object keys; arrays retain their original order. PostgreSQL JSONB key ordering therefore does not change identity.

English draft approval verifies the contract's version/hash, language and level, then uses its recorded shape and constraints. Changing the current bundled blueprint does not reinterpret a draft. Missing legacy contracts return `level_test_contract_required` (409); regenerate those drafts rather than inventing retrospective contracts. Invalid or mismatched contracts return `level_test_not_approvable` (409). Previously approved snapshots remain readable and approval replay remains idempotent. Neither migration recalculates grades or rewrites snapshots.

## Run consumption

Migration `053_level_test_generation_usage.sql` adds `tokens_in`, `tokens_out` and `usage_state` to each generation run. The status API includes `usage: { state, tokensIn, tokensOut }` for recent and correlated runs.

| State | Meaning |
| --- | --- |
| `complete` | Both counters were explicitly reported for every attempted call. |
| `partial` | Some usage is known, but a call or direction remains unreported/in flight. Amounts are known subtotals, not estimated totals. |
| `unknown` | Neither counter has been reported. Both amounts are null. |

A provider-reported zero is preserved. Invalid/negative/non-integer counters are not known usage. Input and output are accumulated independently. Existing complete-only outcome counters remain absent if any usage is unknown; the separate `usage` object carries known subtotals. Legacy runs remain unknown rather than backfilled with zeros.

The run is checkpointed before and after every provider call, before parsing generated content. Empty responses with reported usage preserve those counters too. Interruptions and failed runs retain durable checkpoints. Final run closure also records the latest returned usage, so recovered persistence can retain counters after a transient checkpoint failure without changing an unknown execution outcome. Unknown provider outcomes remain terminal and automatic retries remain blocked by the existing reconciliation policy.

## LLM-reviewed coverage and runtime rubric v1

`shared/leveltests/en-assessment-design.json` now defines English A1–C1 coverage and twenty complete reference examples (one per level/section), not a new learner bank. Revision 1 was reviewed by a separate `gpt-6-astra` LLM pass, corrected after substantive findings and approved against its canonical SHA-256. Review kind, model, date, rubric version, exact content hash and bounded evidence are recorded. See `openspec/changes/english-exam-coverage/content-review.md` for findings and dispositions.

The exporter rejects unapproved, stale, unknown-catalog or infeasible content before embedding each level in its API blueprint. Updating content requires a new review of its canonical payload, not simply editing the approval hash. Existing catalog IDs are reused; inversion, cleft sentences and ellipsis have explicit catalog entries rather than misleading substitute tags.

Runtime rubric v1 is loaded by the existing generator and judge. English prompt/judge versions are 4/5. Every new covered item has one primary `assessedSkill` from its section's approved list. A tag represents what answering requires, not a skill merely encountered in the passage. The judge must return the same tag plus 8–240 characters of observable `skillEvidence`; missing, uncertain or mismatched evidence cannot authorize the item. Semantic truth still depends on reviewer quality: a catalog-valid label is not by itself proof of task validity.

Every form must include each required capability at least once. This LLM-reviewed minimum-presence rule is an engineering pilot constraint, not an empirically calibrated weighting. A bounded deterministic dynamic program selects whole reading/listening groups and individual recognition/writing items while jointly preserving kind quotas. Covered passages have exactly the frozen questions-per-passage count. Insufficient combinations fail with `insufficient_coverage_capacity`; bounded search exhaustion fails with `coverage_search_limit`. Neither case silently reduces coverage or retries uncertain provider calls. Existing distinct-form and distinct-stimulus gates still apply.

Internal pool/review JSON retains tags and evidence. Learner snapshots project them out before hashing; approval also rejects leaked tags and validates each form's selected IDs against pool coverage. The complete existing frozen blueprint stores coverage, examples and rubric provenance, so no new migration is needed. A draft cannot adopt today's matrix on approval; historical frozen models without assessment metadata retain their original evidence contract. Published snapshots and attempts are not rewritten.

### What automated review does not establish

A mandatory teacher signature is not part of the accepted workflow. A separate LLM may design and review the matrix/examples; its evidence must be explicitly labeled automated. This does **not** establish empirical difficulty, timing, distractor performance or CEFR certification. The existing totals 34/39/44/54/58 remain pilot targets, not validated readiness cutoffs. A real learner pilot requires separate authorization/consent and evaluation/handling criteria. No learner data or production credentials are used by this implementation.

## Verification

Run `cd services/api && npm test` for the full suite. It owns disposable PostgreSQL and teardown, never a caller's real database. `npm run check`, root `make check`, and `python3 scripts/export_leveltests.py --check` cover types and repository contracts. See the matching OpenSpec verification report for observed results, not inferred readiness.
