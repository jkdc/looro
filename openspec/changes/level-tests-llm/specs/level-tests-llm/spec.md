# Delta Spec: level-tests-llm

## ADDED Requirements

### Requirement: LLM-generated weekly pools

The backend SHALL generate, for each configured language and level with an
exam model, an oversized item pool via a selectable LLM provider, validated
by deterministic rules before storage, and composed into K weekly exam
variants with the shared deterministic rotation.

#### Scenario: weekly generation

- **WHEN** the weekly generation runs for a language and level
- **THEN** a validated pool is produced (after at most one directed retry
  per section), K variant exams are composed, and the week's row is stored
  as a draft without overwriting an approved week.

#### Scenario: generation failure

- **WHEN** the provider fails or the pool cannot be validated
- **THEN** no week row is approved for that level, the deterministic
  endpoints are unaffected, and the failure is reported by the CLI.

### Requirement: Three-gate validation

Generated content SHALL pass (1) a deterministic validator (schema, exactly
one correct choice answer among its options, passage word ranges, disjoint
reading/listening passages, in-generation dedupe, section minimums), (2) an
LLM judge emitting per-item ok/flagged/reject verdicts whose behaviour is
configured as annotate (flag) or enforce (strict), and (3) human approval
when the publish mode is review.

#### Scenario: judge flags under strict mode

- **WHEN** the judge flags or rejects items and the verify mode is strict
- **THEN** rejected items are removed, flagged sections are retried once,
  and the exam proceeds only if the section minimums still hold.

### Requirement: Separated serving endpoints with precedence

`GET /v1/level-tests/generated` SHALL serve only an approved generated week
(answer 404 `level_test_not_generated` otherwise), and
`GET /v1/level-tests/hybrid` SHALL fall back to the deterministic
composition when no generated week exists. The existing deterministic
endpoint SHALL be unchanged. Every route SHALL support `?variant=` and ETag
revalidation per variant, and SHALL answer 400 for missing parameters.

#### Scenario: hybrid fallback

- **WHEN** no approved generated week exists for the requested level
- **THEN** the hybrid endpoint serves the deterministic composition and the
  generated endpoint answers 404.

### Requirement: Encrypted provider configuration

LLM provider, base URL, model and API key SHALL be configurable from the
admin surface and the environment, with API keys from the admin surface
stored encrypted (AES-256-GCM under an env master key), never returned by
any API, and with every configuration mutation audited. Generation SHALL
fail closed (falling back to deterministic serving) when no usable
configuration exists.

#### Scenario: key secrecy

- **WHEN** an administrator reads the LLM configuration
- **THEN** the stored API key is never included in any response (only a
  masked hint), and its encrypted form cannot be decrypted without the env
  master key.
