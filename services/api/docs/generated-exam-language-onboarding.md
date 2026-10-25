# Add a language to the reviewed generated-exam pipeline

**Reuse the assessment engine; design and verify a pedagogical profile for each language.** The enhanced pipeline currently covers **English A1–C1 only**. Other languages retain their previous generation/publication behavior. Adding or translating a JSON file does not activate all the English guarantees.

This is the repository's technical onboarding guide for future language work. It documents the existing boundary and the required extension work; it does **not** enable additional languages, introduce a profile API, authorize provider calls or establish production readiness.

## Quick path

1. Specify the language, enabled levels and assessment scope in an OpenSpec change.
2. Author and independently LLM-review the language's matrix, task guidance and complete references.
3. Generalize profile loading **and all generation/publication gates**, preserving English and legacy contracts.
4. Prove coverage, linguistic validation and historical compatibility with deterministic tests and isolated PostgreSQL.
5. Record actual provider evaluation separately; authorize a learner pilot and deployment separately.

Start with the [current English guarantees](english-generated-exams.md) and the [implemented coverage design](../../../openspec/changes/english-exam-coverage/design.md). Do not duplicate the generator or create a separate orchestration framework for each language.

## 1. What is shared and what belongs to a language

| Reusable machinery | Language-specific input or policy |
| --- | --- |
| Typed assessment contract and canonical blueprint hashing | Enabled language/level combinations and their can-do anchors |
| Internal `assessedSkill`, pool normalization and final ID-based coverage checks | Permitted/required primary catalog IDs, task interpretation and coverage requirements |
| Bounded deterministic selection with joint kind quotas and whole-passage grouping | Form shape, candidate targets, kind mix and stimulus/task constraints |
| Novelty tracking across previously selected passage sets | A feasible distribution of capabilities across alternative candidates/passages |
| Independent judge call, evidence validation and audit persistence | Target-language instructions, linguistic checks, accepted-answer conventions and rubric versions |
| Reviewed-reference embedding and approval provenance | Full reference passages/questions/keys/evidence in that language |
| Isolated test stack and historical/public-payload regression tests | Realistic language-specific valid, invalid and uncertain fixtures |

CEFR labels do not make grammar targets, passage lengths or acceptable answers interchangeable. Reuse catalog IDs only when their meaning actually fits the task; add documented canonical IDs when needed. Never relabel an unrelated skill just to pass coverage.

An editor-facing `SKILL.md` may help authors follow the workflow. It is **not** a substitute for runtime-loaded rubrics, validated data, judge evidence or executable coverage checks. A separate skill per language is not a prerequisite.

## 2. Current code boundary: inspect these together

Paths below are relative to the repository root. Symbol names, rather than line numbers, are the stable inspection points.

| Location | Current behavior | Required extension consideration |
| --- | --- | --- |
| `shared/leveltests/exam-models.json` and `services/api/src/level-test-blueprint.ts` / `resolveExamBlueprint` | Exact language override first, then wildcard | A strict onboarded profile needs an exact supported language/level; a wildcard must not masquerade as reviewed support. Preserve legacy fallback where it is still intended. |
| `shared/leveltests/en-assessment-design.json` | English matrix, rubric revision, complete references and review provenance | Supply an independently reviewed design for each newly enabled language; do not copy English approval. |
| `scripts/exam_assessment.py` / `embed_assessment` | Reads the English file and attaches metadata only to `languageID == 'en'` models | Generalize validated loading and embedding for explicitly selected profiles; a new filename alone is currently ignored. |
| `scripts/export_leveltests.py` | Embeds assessment through `exam_models()` into the generated API model | Keep generated outputs reproducible and API-only assessment metadata private; never edit generated files directly. |
| `services/api/src/level-test-assessment.ts` | Reusable contract validation, pool/form coverage, bounded DP and generation rubric | Reuse these mechanisms with the new profile; review capacity and search bounds instead of silently relaxing them. |
| `services/api/src/level-tests-llm.ts` / `buildSectionMessages`, `sectionGenerationSpec`, `generationVersionsFor` | Enhanced task prompts/counts and versions are selected explicitly for English; other languages use legacy messages | Route all three from supported profile policy, not just the composition branch. English versions currently 4/5; others remain 2/2. |
| `services/api/src/level-tests-llm.ts` / `generateWeeklyLevelTest` | Complete blueprint freezing, deferred full-field language review and several final gates are English-selected | Freeze the new profile before asynchronous work and carry its recorded policy through generation, filtering and publication. |
| `services/api/src/level-tests-llm.ts` / `judgeSection`, `buildJudgeMessages`, `parseVerdicts` | Strict linguistic evidence/schema selected for English; semantic skill evidence required when assessment exists | Activate linguistic and semantic contracts together for the new profile, with explicit versions and target-language instructions. |
| `services/api/src/level-tests-validate.ts` | Enhanced section/language rules and word ranges have English-specific branches; `assessEnglishLanguage` is not multilingual | Implement/test the new language's field-level policy rather than reusing English word markers or removing language checks. |
| `services/api/src/level-tests-publication.ts` / `canPublish` | Frozen-contract lookup, mandatory clean judge evidence and final-form gate are selected explicitly for English | Enforce the new profile equally on automatic publication and manual approval, using recorded rather than current policy. |
| `services/api/src/level-test-frozen-blueprint.ts` | Validates the frozen blueprint/hash and optional assessment metadata | Preserve complete historical contracts; do not reconstruct missing policy from today's profile. |

**Partial activation is unsafe.** `composeVariants` selects the coverage-aware path when `model.assessment` exists, but several prompt, language, freeze and publication branches still depend on English. Attaching assessment metadata to another language without reconciling those branches can produce a mixture of new composition and legacy guarantees.

Do not globally replace every English condition with “any language.” Introduce explicit support routing and review the purpose of each condition. Unsupported languages/levels must not be implicitly certified or silently switched to new policies.

## 3. Prepare the language's reviewed assessment design

### Matrix and constraints

For each enabled level and each of recognition, reading, listening and controlled writing, define:

- A language-appropriate can-do anchor, task family, register and out-of-scope targets.
- Required/permitted primary catalog IDs and guidance explaining what answering must demonstrate. Exposure to a word or structure does not count as assessment.
- Candidate targets, delivered shape, recognition kind quotas, passage grouping and question counts that can satisfy coverage jointly across distinct variants.
- Appropriate stimulus limits, spoken format and answer conventions. English totals and word ranges are existing pilot targets, not multilingual defaults or empirically validated quotas.

The current engine allows at most 12 required skill IDs per section, enforces exact covered questions-per-passage counts, bounds search and rejects infeasible selection. Keep these limits explicit. If a new profile needs another counting unit, richer response format or larger constraints, specify and test that separately rather than disguising it as configuration.

For future languages without reliable whitespace word boundaries, the current `split`-based word metrics need a separate design. This guide records that boundary; it does not add such support now.

### Complete references and review

Use the existing shape as a reference, not as translated ground truth:

- Flat sections: complete task, options where applicable, accepted answers, explanation, primary assessed skill and observable support.
- Comprehension: **full** passage/script plus all required questions/options/keys/support. Do not provide orphan `passageId` anchors.
- A separate LLM review checks task demand, language, answer uniqueness/support, distractor plausibility, genuine skill assessment and feasible coverage. An LLM may own this review; a mandatory teacher signature is not required.
- Record automated review kind, actual model, date, rubric version, verdict, findings/dispositions and the exact canonical payload hash. Review the content again after substantive changes; do not merely update a hash to retain an old approval.

The current source hash uses sorted object keys, UTF-8 JSON without ASCII escaping or spacing, and excludes the root `review` field. Frozen blueprint identity uses its own recursively canonicalized full-contract hash. Preserve both distinct purposes when generalizing; test JSONB key-order round trips. Neither hash proves pedagogical truth by itself.

A possible future filename is `shared/leveltests/<languageID>-assessment-design.json`. This is a **proposed convention**, not an implemented loader or new configuration contract.

## 4. Implementation sequence

### A. Define explicit profile support

Specify the supported language/level pairs and how a single resolved profile supplies the blueprint, reviewed assessment, target-language rubric, language-validation policy and prompt/judge versions. Reuse the existing generator/judge roles. Avoid scattered language lists that can disagree between exporter, generation and approval.

Preserve current English behavior. Do not remove legacy paths for languages or historical contracts outside the change's approved scope. A profile claiming enhanced support must not fall back to legacy *validation* merely because its generated content fails; the existing hybrid serving fallback is a separate endpoint policy, not a bypass for publishing bad generated content.

### B. Generalize authoring/export validation

Load only explicitly supported designs. Reject wrong-language/level bindings, unknown or duplicate skills, infeasible constraints, incomplete references, pending review and stale review hashes. Embed each profile into its exact API blueprint; keep learner payloads unchanged.

If the skill catalog changes, regenerate through `scripts/export_courses.py`; regenerate exam models through `scripts/export_leveltests.py`. Preserve all exporter checks and cross-platform parity. Do not expand learner banks just to enable reviewed generation.

### C. Wire generation, judge and linguistic policy together

Ensure the actual runtime messages include the correct language's complete references and task guidance. Require and retain one allowed primary `assessedSkill` per item through parsing, normalization, filtering and persistence.

Validate prompts, answers, options, explanations and full passages independently. Distinguish clear failures from legitimate names, loans, brief answers and uncertain cases; do not let English-looking questions establish a passage's language. Do not grow language word lists indefinitely. Require the judge to resolve uncertainty before publication under the enabled policy.

The judge must independently verify keys, distractors, level/task fit, language and skill claims, returning matching bounded observable evidence rather than hidden reasoning. Preserve missing/duplicate ID rejection and exclusion of unresolved items. Do not add provider retries or reinterpret unknown execution outcomes as part of onboarding; existing durable usage/failure guarantees remain binding.

### D. Freeze, compose and publish consistently

Freeze the complete generation contract before the first asynchronous operation. Any newly introduced policy identifiers/versions needed for future approval must be recorded recoverably, not looked up only from mutable current configuration.

Use the existing bounded selector for skill presence, kind quotas and whole groups; retain novelty against prior passage sets. Revalidate coverage after judge filtering and on every selected form. Pool-wide coverage alone is insufficient. Preserve the final diversity checks and explicit capacity/exhaustion errors.

Automatic publication and manual approval must apply the **same recorded contract and evidence requirements**. Verification disabled must not accidentally become a publication bypass for the newly enhanced profile. Strip internal assessed metadata before learner-snapshot hashing and reject leaked tags on approval.

Existing complete frozen contracts without assessment metadata retain their original semantics. For older drafts missing newly required recoverable contracts, specify an explicit block/regenerate policy in the language change; never invent historical matrices. Do not rewrite published snapshots or regrade attempts. Add a new migration only if an actual new persisted requirement cannot fit the existing contract; never edit applied migrations.

## 5. Verification and acceptance

For deterministic behavior changes, observe a failing test before the fix, then a passing test. Reuse the existing API tests and exporter tests instead of introducing a parallel test framework.

### Required regressions

- New profile receives the correct anchors, references, counts, rubric versions and target-language messages; wrong language/level bindings cannot reuse another profile's approval.
- Missing, unknown, cross-section and exposure-only tags fail; valid tags survive parsing, normalization, persistence and judge filtering.
- Missing, malformed, mismatched or unresolved linguistic/skill evidence cannot publish, automatically **or** manually.
- Every enabled level/form satisfies joint coverage/kind/group constraints with deterministic results; test **uneven capability distributions**, not only passages containing every capability.
- Feasible distinct sets are found; impossible sets and exhausted bounded searches fail explicitly without constraint relaxation or publishing duplicates.
- Current profile changes cannot reinterpret an older frozen draft. Retained historical English/other-language contracts keep their recorded behavior.
- Rehashed but uncovered/tampered forms are rejected. Public snapshots contain no private tags and existing mobile/exporter contracts remain unchanged.
- Existing English, untouched legacy languages, published snapshots, run usage and unknown-provider-outcome behavior remain covered.
- Language-specific valid/invalid/uncertain content is tested, including brief questions, names, loans and accepted-answer variants. Review normalization explicitly for the language's accents, punctuation and orthography; do not broaden shared answer equivalence without compatibility tests.

### Commands

From the repository root:

```sh
npm run check --prefix services/api
make test-api
make check
python3 scripts/export_courses.py --check
python3 scripts/export_leveltests.py --check
```

`make test-api` (also `cd services/api && npm test`) owns an isolated PostgreSQL stack and teardown. Do not omit DB-dependent tests or point them at a real environment. Host `test:unit` is not a substitute for this acceptance run. Run native tests too if the language change actually modifies native behavior.

Before closing, obtain an independent implementation review and record commands, observed results, failures/skips and residual limitations in the language change's verification report. Do not copy the previous English run's test count as new proof.

## 6. Activation and evidence boundary

- [ ] Supported levels, reviewed matrix/references and actual runtime policies agree.
- [ ] Exporter, generation, composition and both publication routes are activated consistently.
- [ ] English, legacy behavior, historical contracts and public payloads remain compatible.
- [ ] Isolated DB, types, exporters/contracts and independent review are complete.
- [ ] Any configured-provider quality/cost evaluation has separately authorized credentials and a recorded outcome.
- [ ] Any real learner pilot has consent, evaluation criteria and data-handling rules; any deployment has separate authorization.

Automated pedagogical review can approve the matrix/examples. It does not establish empirical difficulty, timing, distractor performance, readiness cutoffs or CEFR certification. Simulated LLM learners are not a real learner pilot. Keep technical activation, observed provider quality and learner evidence distinct.

## References

- [English guarantees and runtime rubric](english-generated-exams.md)
- [Coverage proposal](../../../openspec/changes/english-exam-coverage/proposal.md), [design](../../../openspec/changes/english-exam-coverage/design.md) and [specification](../../../openspec/changes/english-exam-coverage/specs/generated-exam-coverage/spec.md)
- [Recorded pedagogical review](../../../openspec/changes/english-exam-coverage/content-review.md), [runtime review](../../../openspec/changes/english-exam-coverage/runtime-review.md) and [verification](../../../openspec/changes/english-exam-coverage/verify-report.md)
- [Canonical English assessment design](../../../shared/leveltests/en-assessment-design.json) and [exam blueprints](../../../shared/leveltests/exam-models.json)
