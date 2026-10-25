# Design

## Publication and language

For English, automatic publication and manual approval require exactly one clean, bounded judge verdict per retained item. Each verdict explicitly resolves the language of the prompt, answers/options, explanation and supplied comprehension passage. Unresolved language cannot be represented by `ok`. Off-mode generation creates drafts only. Human approval does not bypass missing judge evidence.

Deterministic language assessment is three-state, not an infallible classifier. Existing bounded markers provide evidence; they are not expanded indefinitely. Clearly foreign content is rejected, uncertain content remains available to the judge, and names, short answers and loan words are not rejected merely for lacking stopwords. A question cannot establish its passage's or explanation's language. Other languages retain their existing path.

## Frozen contracts

Clone the English model before the first asynchronous operation. Persist the full contract as JSONB with a new contract-format version and a canonical SHA-256 hash (recursive key ordering, not JSONB serialization order). Manual approval validates contract integrity, language and level and then uses that exact contract. Legacy English drafts without a trustworthy complete contract are blocked and must be regenerated. Existing approved snapshots and attempts remain untouched.

## Run usage

Track known input and output totals independently as nullable amounts. A reported zero is known. Classify usage as complete only when every attempted call reported both nonnegative integer counters; partial when any counter is known but some call/direction is unknown; unknown when neither direction is known. Preserve the old complete-only outcome counters for compatibility.

Checkpoint usage on the run before each call (in-flight usage is unknown/partial) and after receiving counters, before parsing content. Interrupted/failed runs retain checkpoints. A bookkeeping failure after a provider call makes the outcome unknown, never a retry instruction. Run state and usage completeness are distinct.

## External pedagogical evidence

No approved per-level assessed-skill matrix or teacher-reviewed example corpus was provided. Do not derive quotas from prompt labels or invent approval. Record the required owner, catalog IDs, per-section/form coverage, version, reviewed complete examples and learner-pilot consent/evaluation criteria before implementing that conditional part. Technical changes do not certify CEFR readiness.

## Follow-up decision — 2026-10-25

The user explicitly accepted LLM ownership and separate automated review of the matrix/examples. `english-exam-coverage` supplies that versioned evidence and implements internal tags, runtime references and coverage. The earlier human-owner prerequisite above describes the state at this change’s original implementation, not a current teacher gate. Empirical learner evidence remains separate.
