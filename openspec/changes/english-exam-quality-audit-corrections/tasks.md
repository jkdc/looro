# Tasks

- [x] Enforce English candidate quotas, option counts, short valid lexical content, and post-filter stimulus diversity, including manual publication.
- [x] Share the generation/sync snapshot bound and reject oversized generated forms before persistence.
- [x] Require closed judge reason codes and bounded evidence; persist nullable blueprint version/hash; keep incomplete usage unknown.
- [x] Route default `npm test` through isolated PostgreSQL; retain `test:unit`; remove duplicate CI test execution.
- [x] Run full API suite and contract/type checks; 681 passed, 0 failed, 0 skipped.

## Verification

- `npm test` (isolated PostgreSQL; zero DB-dependent skips)
- `npm run check`
- `python3 scripts/export_leveltests.py --check`
- `make check`
- `git diff --check`
