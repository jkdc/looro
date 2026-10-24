# Generated English exam quality

## Requirements

### Requirement: English generation follows the blueprint after review

The system MUST validate candidate-kind quotas and option counts against the English blueprint before judge review. After judge filtering, generation MUST compose forms that meet the served-form mix and MUST fail closed if reading or listening stimulus diversity cannot be preserved across variants. Manual publication MUST apply the same final-form mix and cross-variant item/stimulus diversity checks as automatic generation.

### Requirement: English generation contract versions track prompt and judge changes

When the English generation prompt or judge response contract changes materially, newly generated weeks MUST persist a new English prompt/judge version. Version changes MUST NOT alter the persisted version identity used by other languages.

### Requirement: Generation and synchronization share a snapshot size contract

The system MUST reject a generated snapshot before persistence when its serialized size exceeds 192,000 bytes. Attempt synchronization MUST enforce that same limit. The limit MUST remain below the 256,000-byte request-body ceiling.

### Requirement: English judge approvals carry bounded evidence

Every English judge result MUST contain a recognized closed reason code and non-whitespace evidence of 8–240 characters. Invalid, missing, or unbounded evidence MUST fail closed. Evidence MUST be observable and concise, not hidden chain-of-thought.

### Requirement: English lexical validation requires positive English evidence

English lexical prompts without English function-word markers MUST be accepted when they contain explicit English task vocabulary, including when their formatting uses line breaks or tabs. They MUST be rejected when they contain neither that vocabulary nor other positive English markers; absence of foreign-language markers MUST NOT count as evidence that the content is English. This fallback MUST remain English-only.

### Requirement: Generated exams retain blueprint provenance

Every newly generated week MUST persist blueprint version and SHA-256 hash together. Existing rows MAY retain both fields as NULL; partially populated provenance MUST be rejected.

### Requirement: Token usage remains truthful

Generation MUST expose/persist aggregate input/output counts only when every provider call returned both counters. Any missing counters or thrown provider call makes aggregate usage unknown, not 0/0.

### Requirement: Full API tests use isolated PostgreSQL

`npm test` MUST run all API tests against a dedicated disposable PostgreSQL test database, without forwarding host `DATABASE_URL` or `TEST_DATABASE_URL`. Successful and failed runs MUST tear down the isolated database and volume by default. `npm run test:unit` MAY be used as an explicitly partial fast path that skips database-backed cases.
