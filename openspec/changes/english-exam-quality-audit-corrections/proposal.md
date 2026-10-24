# Proposal: Close English Generated Exam Quality Audit Gaps

Align English exam generation, publication, and learner sync with the approved quality design. Make the default API test command run the complete suite against an isolated disposable PostgreSQL database.

## Motivation

The audit found mismatches between generation and sync limits, blueprint composition and validation gaps, insufficient judge evidence, missing blueprint provenance, and a default test path that skipped database-backed tests.

## Scope

- Fix English-only quality and form-composition checks without changing other languages.
- Use one bounded serialized snapshot contract for generation and sync.
- Store additive nullable blueprint provenance and preserve unknown token usage as unknown.
- Make `npm test` invoke the isolated disposable PostgreSQL suite; retain `test:unit` as an explicitly partial fast path.

Teacher review remains required; pilot item totals are not a psychometric or CEFR-readiness claim.
