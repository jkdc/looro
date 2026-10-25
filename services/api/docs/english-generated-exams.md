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

## Pedagogical gate — not completed by code

No approved skill matrix or reviewed reference corpus was supplied with this change. Existing free-text blueprint skill labels are not an approved assessment matrix and do not prove coverage. Do not invent quotas, approve generated examples automatically, or claim that engineering tests establish CEFR readiness.

The next pedagogical input must include:

1. A named accountable reviewer, approval date and immutable matrix version.
2. Per-level/section assessed capabilities mapped to existing `shared/skills/skill-catalog.json` IDs; explicit distinction between assessed and exposure-only skills.
3. Agreed form-level coverage requirements and the rule for insufficient candidate coverage, including infeasible combinations. These decisions determine validated internal item tags and composition constraints; do not modify public/mobile exam payloads to carry them without a separate compatibility design.
4. A reviewed/versioned example corpus for enabled English cells. Each comprehension example needs its full passage, all questions/options, accepted solutions, answer-support evidence and review provenance. It must be suitable for prompt inclusion, not an orphan `passageId`.
5. Teacher pilot criteria for level/task fit, ambiguous keys, distractor plausibility, difficulty and timing. Then explicit authorization/consent and handling rules for a learner pilot, before collecting learner evidence.

Only after those inputs are approved should retained catalog-validated skill metadata, coverage-aware composition and curated prompt references be implemented. The existing form totals 34/39/44/54/58 remain pilot targets, not validated readiness cutoffs.

## Verification

Run `cd services/api && npm test` for the full suite. It owns disposable PostgreSQL and teardown, never a caller's real database. `npm run check`, root `make check`, and `python3 scripts/export_leveltests.py --check` cover types and repository contracts. See the matching OpenSpec verification report for observed results, not inferred readiness.
