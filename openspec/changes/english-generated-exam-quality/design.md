# Design: English Generated Exam Quality and Length

See `docs/superpowers/specs/2026-10-24-english-generated-exam-quality-design.md` for the approved scope, approaches, constraints, target counts, and acceptance criteria.

## System design

The canonical English exam model resolves into one typed blueprint consumed by generation, deterministic validation, judge prompts, composition, and publication. The blueprint carries separate candidate-pool targets and per-student form shape. Prompt/judge/blueprint versions identify generated material without changing grading or snapshot schemas.

Each section follows one bounded cycle: generate, parse, validate against its blueprint, and (only for a known repairable invalid response) issue one corrective request containing sanitized validation feedback. Cross-section invariants and diversity are checked after all sections are assembled. Unknown provider outcomes are not retried.

The judge returns server-correlated item IDs, a closed verdict/reason code, and brief evidence where applicable. English `flagged` material is not treated as clean or auto-approved; existing human-review machinery remains authoritative for unresolved content. Publication continues to validate the frozen generation contract and does not rewrite published snapshots.

## Compatibility

The change alters the number of English items in newly generated forms but not the item schema. Check API composition bounds, persisted snapshots, Android/iOS item consumption, and request/response body limits. Existing published forms and historical attempts remain immutable. Non-English models, prompts, and validation retain current behavior.

## Verification

Follow RED → GREEN for deterministic regression tests, then focused API tests, type-check, exporter `--check` when source JSON changes, and API integration tests in the ephemeral PostgreSQL stack when available. No automated test can establish CEFR or psychometric validity; document that as a pilot requirement.
