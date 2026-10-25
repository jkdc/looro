# Generated English exam publication

## Requirement: English publication has judge evidence

Both automatic and manual English publication MUST require one clean verdict per retained item, a closed reason code, 8–240 characters of observable evidence and an explicit `languageStatus=pass`. An `ok` verdict with unknown/failed language MUST NOT authorize publication. Other-language policy MUST remain unchanged.

### Scenario: Verification disabled with automatic publishing configured
Given English generation with `verifyMode=off` and `publishMode=auto`, the result MUST be a private draft and MUST NOT be manually approvable without active judge evidence. Regeneration with verification enabled is required.

## Requirement: Independently assess all content fields

English validation MUST distinguish acceptable, clearly incorrect and uncertain language evidence in prompts, individual accepted answers, individual options, explanations and comprehension passages. The pipeline MUST retain uncertain short text, legitimate names and loan words for judging; uncertainty MUST NOT become publication approval. Clear failures MUST trigger existing bounded repair/failure behavior. A question MUST NOT establish its passage's or explanation's language. Uncertain alternatives MUST NOT be concatenated into spurious foreign-language evidence.

### Scenario: Spanish comprehension content with English questions
A structurally/quota-valid pool containing Spanish passages or explanations and English questions MUST be rejected or retained privately for resolution, never auto-published without resolved language.

### Scenario: City-name alternatives
The options `Los Angeles`, `Las Vegas`, `La Paz`, `El Paso` on `Which city is in California?` MUST remain reviewable rather than rejected because their combined text contains several foreign-looking articles.

## Requirement: Approve against the frozen generation contract

New English generations MUST clone their complete blueprint before asynchronous work and persist it with a format version and canonical SHA-256 hash. Approval MUST verify integrity, language and level, then use that recorded model. JSONB object-key order MUST NOT affect its hash. Missing legacy English contracts MUST block approval with an explicit regeneration requirement; no historical contract may be guessed. Already-published snapshots and grading history MUST NOT be rewritten.

### Scenario: Current blueprint changes
Changing the live model after a draft was generated MUST NOT change approval of that unchanged draft with a verified complete frozen contract.

## Requirement: Durably preserve reported run consumption

Runs MUST independently preserve nullable known input/output subtotals and a complete/partial/unknown usage state, regardless of the run outcome. Explicit zero MUST remain known; absent or invalid counters MUST NOT become zero. Checkpoints before/after calls MUST preserve interrupted consumption, and final closure MUST retain the latest returned usage if persistence recovers after a transient checkpoint rejection or zero-row update. Unknown execution outcomes MUST remain terminal, even when usage itself is complete.

### Scenario: Transport failure after one reported call
After a known 2-input/3-output call followed by an unknown call outcome, the durable run MUST retain 2/3 as partial usage and MUST NOT automatically repeat the call.

### Scenario: Post-call checkpoint failure and recovery
After a call reports 17 input and 4 output, a failed post-call checkpoint followed by successful final closure MUST retain 17/4 complete usage on an unknown run, without another provider call.

## Requirement: Separate technical proof from pedagogical evidence

An approved, versioned per-level matrix referencing the existing catalog and reviewed complete examples MUST precede coverage/tagging and curated-example implementation. No arbitrary quotas or synthetic teacher approval may replace those inputs. Technical checks MUST include isolated PostgreSQL with no DB skips, types, contracts, exporters, historical compatibility and independent review. A separate LLM may own matrix/example review when explicitly labeled automated; no mandatory teacher gate is imposed. Authorized learner pilots remain separate external evidence gates; engineering tests MUST NOT be represented as CEFR readiness proof.
