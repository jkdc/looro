# Design: English Exam Audit Corrections

## Quality boundary

The English blueprint remains the single source of exam candidate and served-form quotas. Validate candidate structure before judge and validate the composed form after judge filtering. Generation and manual approval call the same final-form validator for recognition kind ratios, unique item sets, and distinct reading/listening stimuli across variants. These constraints do not affect non-English generation or publication.

English lexical fallback accepts explicit English task vocabulary and rejects non-Latin writing-system content rather than treating absence of foreign markers as proof of English. Material prompt/judge contract changes increment English-only persisted versions; existing non-English version identities remain unchanged.

## Snapshot contract

Generation and attempt sync share a 192,000-byte serialized snapshot ceiling. This is above the observed 103,773-byte C1 snapshot and below the 256,000-byte sync request ceiling. Generation checks before persistence; sync checks the same contract at ingestion.

## Judge, provenance, and usage

English judge output requires a closed reason code and short observable evidence. It must not request or persist hidden chain-of-thought. Each generated week stores a nullable blueprint version and SHA-256 hash in additive columns; historical rows remain readable. Token totals are exposed only when every provider call returned both counters.

## Test isolation

`npm test` delegates to root `make test-api`, which builds and runs the suite in Compose against fixed `app_container_test` and removes its volume on success or failure. Host `DATABASE_URL` and `TEST_DATABASE_URL` are not passed through Compose. `npm run test:unit` is explicitly partial and may skip DB tests. CI runs the full path once.
