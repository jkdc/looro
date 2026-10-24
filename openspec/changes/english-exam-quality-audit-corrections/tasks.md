# Tasks

- [x] Enforce English candidate quotas, option counts, short valid lexical content, and post-filter stimulus diversity, including manual publication. Final review's rehashed wrong-mix, duplicate-stimulus, and Cyrillic regressions now fail closed; explicit English lexical prompts pass.
- [x] Share the generation/sync snapshot bound and reject oversized generated forms before persistence.
- [x] Require closed judge reason codes and bounded evidence; persist nullable blueprint version/hash; keep incomplete usage unknown.
- [x] Route default `npm test` through isolated PostgreSQL; retain `test:unit`; remove duplicate CI test execution.
- [x] Run full API suite and contract/type checks after the final-review corrections: 689 passed, 0 failed, 0 skipped; type and repository contract checks passed.

## Final review corrections

- [x] Share final English served-form validation between generation and manual approval.
- [x] Require positive English lexical evidence or reject clear non-English scripts in the English fallback.
- [x] Version the changed English prompt and judge contracts without changing non-English version identities.

## Verification

- `npm test` (isolated PostgreSQL; zero DB-dependent skips)
- `npm run check`
- `python3 scripts/export_leveltests.py --check`
- `make check`
- `git diff --check`
