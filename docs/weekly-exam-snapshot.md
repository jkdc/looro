# Weekly exam snapshot contract

Weekly selection is not content identity. `/v1/level-tests/current`, `generated`,
and `hybrid` retain their existing selection behavior and legacy fields, and add
snapshot metadata for deterministic exams and newly generated variants.

| Field | Meaning |
|---|---|
| `schemaVersion` | Wire shape version; existing fields stay compatible with version 1. |
| `version` / `contentRevision` | Content revision, separate from schema and evaluator revisions. |
| `gradingVersion` | Grading contract revision; currently 1. |
| `contentHash` | Lowercase SHA-256 of canonical snapshot content. |
| `contentId` | `sha256:<contentHash>`; exact-content deduplication identity. |
| `examId` | Deterministic: contentId. Generated: `<generationId>:v<variant>`. |
| `generationId` | Stored generation UUID; absent for deterministic composition. |
| `source` | `deterministic` or `llm`; provenance is part of hashed content. |
| `weekId` | ISO week-based year × 100 + week, NOT an immutable resource ID. |
| `variant` | Present for generated variants; deterministic exams have no variant. |

Canonical content includes the legacy payload (questions/options/accepted answers,
explanations, unit references, passages, section IDs, thresholds, language/level,
week/expiry and optional variant), content/grading revisions and source. Object
keys are recursively sorted by codepoint order; array order remains unchanged.
Undefined object fields are omitted, matching JSON serialization. Identity metadata
(examId/contentId/contentHash/generationId) is excluded from hashing. Re-identifying
JSON-restored content therefore gives the same hash, regardless of publication UUID.

The composer detaches snapshots from mutable bundled pools. New generated variants
persist the entire composed snapshot in `level_test_weeks.variants`, including their
resolved thresholds and versions. Serving returns that stored variant exactly;
updating the bundled bank does not reinterpret its grading. Force regeneration
gets a new UUID and retires the prior row without rewriting its variants.

Older generated rows never stored complete grading/type metadata. They remain
available with their original legacy wire content and `legacy: true`, plus a
UUID/variant examId. They do NOT claim a contentHash or frozen grading values:
missing original values are not reconstructed from today's bundled content.
A future migration/recovery policy must distinguish these legacy generations.

ETag remains HTTP representation revalidation, not historical lookup. This change
adds no by-ID retrieval, account attempts, cloud sync, or client persistence;
those are subsequent work units. Existing deterministic golden vectors
and HTTP selection/404/304 behavior remain covered by the API tests.

## Generation and publication policy

Structural validation runs even when semantic judging is off. The pool must meet
the model's section and passage-question minimums, choice-answer rules, supported
kinds, deduplication and passage constraints. The judge receives the exact source
passage for comprehension questions. Rejected questions are removed before variant
composition; the surviving pool is validated again. In strict mode, transport/schema
errors, missing/duplicate/unrequested verdict IDs, or a post-removal minimum shortfall
prevent persistence/publication. Complete `flagged` verdicts remain advisory in strict
mode too. In flag mode a failed/incomplete judge is advisory and is recorded in review;
it never bypasses structural minimums. Off disables only semantic judging.

`publishMode=review` stores private drafts; public selectors only select approved rows.
An authenticated administrator can call
`POST /v1/admin/level-tests/<generationId>/approve` with `{ "reason": "Reviewed content" }`.
The existing cookie and Origin policy applies. Approval owns the entire generation
and all its variants, not one variant. It locks the draft, checks the full snapshot
against its validated pool and hash/identity, then transitions and audits atomically.
An already-approved retry is idempotent and does not add a second audit action.
Old incomplete drafts are not silently upgraded to the full snapshot contract.
`publishMode=auto` publishes only after the same generation-time structural/judge
gates; it does not bypass strict failures.

Admin configuration GET and generation share DB-first effective settings. Partial
PUT preserves omitted settings/provider/URL/model/credential, including strict → flag
→ off transitions. Provider/settings changes commit together under a configuration
lock. Switching an existing provider requires an explicit key, URL and model rather
than forwarding its credential implicitly. Secrets remain encrypted and GET only
returns a hint. Empty optional environment URL/model fields use provider defaults.
The existing connection-test route tests the saved effective config, not pending form
values; form changes and review UI are outside this work unit.

## Checking and rollback

Run the focused exam suites, `npm run check`, `npm run build`, `npm test` in
`services/api`, and `python3 scripts/check_cross_platform.py` from the repository
root. PostgreSQL integration tests require TEST_DATABASE_URL; skipped tests are
not database proof. No real provider calls are required.

Rollback removes the snapshot helper/composer integration and corresponding tests
and documentation as one unit. No database migration is introduced. Already stored
new variants retain extra JSON fields that the former serving code ignores;
rolling back serving would lose their frozen-grading behavior until restored.

For the publication-policy work unit, revert the generator/validator/admin settings,
approval service and route together with their policy tests and this section. No
database migration was added. Reverting code must not delete or silently unapprove
already-published rows or their audit records. Fake SQL tests do not prove PostgreSQL
locks, transaction isolation or audit atomicity; those require the DB integration gate.
