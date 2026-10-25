# English coverage implementation verification — 2026-10-25

## Outcome

Implemented English A1–C1 LLM-reviewed capability coverage, complete prompt references, private assessed tags, semantic judge evidence and bounded coverage/diversity-aware composition. Automatic publication and manual approval use the generation's frozen assessment contract. Historical contracts, public/mobile snapshots and existing totals 34/39/44/54/58 remain compatible.

## Observed checks

| Check | Final observed result |
| --- | --- |
| `make test-api` | **748/748 passed, zero failed, zero skipped**; PostgreSQL 17 isolated Compose project `looro-smoke`; containers/network/test volume removed on completion. |
| Focused assessment/blueprint/snapshot/policy tests | **132/132 passed, zero skipped** after the review correction. |
| `npm run check --prefix services/api` | Passed, no TypeScript errors. |
| `make check` | Passed: 141 Python tests, exporters, database schema and cross-platform contracts. |
| `python3 scripts/export_courses.py --check` | Passed. |
| `python3 scripts/export_leveltests.py --check` | Passed; approved canonical content hash independently recomputed and matched. |
| `git diff --check` | Passed. |
| Separate pedagogical LLM review | Initial rejection, corrected reference hash approved; see `content-review.md`. |
| Independent whole-feature runtime review | One Important finding fixed with a reproducing regression and final green suite; see `runtime-review.md`. No Critical or deferred Minor findings. |

Existing Python SQLite `ResourceWarning` messages are non-failing. An initial integration-image build was stopped by test-only TypeScript nullability errors; those were corrected before the successful full runs. Intermediate suites passed 744 and 747 tests; the final **748-test** run is the post-correction evidence, not those earlier snapshots.

## Regressions and failure guarantees

- Exporter activation tests observed RED before implementation (seven assertions failed), then GREEN (three tests).
- Initial runtime assessment tests failed on the missing implementation, then passed. Additional focused regressions cover every enabled level, whole groups, joint kind constraints, infeasibility, bounded search and deterministic output.
- Internal-tag leakage into a rehashed snapshot reproduced a missing expected rejection; the approval gate now rejects it.
- The independent review's uneven-capability example reproduced a repeated stimulus set despite an available alternative. Tracking novelty in DP state makes that regression pass without dropping kind/coverage constraints. Existing infeasible-diversity error contracts still pass.
- Missing/unknown/cross-section/exposure-only tags, missing/oversized/mismatched semantic evidence, relabeled pools, individually uncovered rehashed forms and current-matrix changes cannot bypass the frozen contract.
- Legacy frozen models without assessment metadata remain approvable under their original evidence schema. Published content and attempts are not rewritten.

## Boundaries and next step

No configured generation provider, real API credentials, production database, deployment, push or PR was used. LLM-generated transport fixtures verify engineering behavior, not actual item difficulty. The separate automated pedagogical review is genuine recorded review but is not a teacher signature, empirical calibration or CEFR certification.

Native Android/iOS builds were not run: this change has no native runtime edits; catalog outputs were regenerated and checked through the shared exporters/contracts. Actual provider quality/cost observation and an authorized learner pilot remain next steps. A real learner pilot needs consent and evaluation/data-handling criteria; simulated LLM learners cannot replace it. Production access/deployment requires separate authorization.
