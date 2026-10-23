# API organization remediation
Branch: `analysis/api-organization`; delivery: work-unit commits on the current branch; runner: `services/api` TypeScript tests + isolated `make test-api`.
Goal: implement all applicable phases/recommendations from `informe-organizacion-api-2026-10-23.md` without changing API/runtime behavior or reorganizing for appearance.

## Specs

### S1 — Implement the user's approved report scope

User request: “procede a implementar los cambios acordados en el documento” and clarification: “no quiero worktrees, hay que hacer todo lo que dice el documento no entiendo por qué dices que acotaste el piloto. sigue en la rama que estamos”.

Scope includes report recommendations H1–H9 and phases 0–4 where applicable; retain the report's explicit non-goals and conditional recommendations. The initial route extraction is not a stopping point. Continue on `analysis/api-organization`, without a worktree. Acceptance: all applicable recommendations are tracked and completed or documented as genuinely not applicable with evidence; no blanket rewrite.

### S2 — Preserve externally observable contracts

User requirement: “asegurandonos que no se rompe nada con este refactor” and “tampoco quiero que refactorices por refactorizar, sólo si es realmente necesario”.

Preserve route/status/body/header/auth/admission/CSRF/limits, security counters, webhook bytes, SQL/transaction/lock/audit semantics, async job launch and CLI/build paths. Acceptance: route/domain tests plus real compiled-server HTTP smoke pass; changes to behavior are not smuggled into route movement.

### S3 — Preserve application and security effects

Admission denials may persist rate-limit counters. Preserve those records while proving denied requests do not invoke unauthorized business/provider operations. Preserve audit and transaction boundaries and verify PostgreSQL state, not just mock calls.

### S4 — Compiled smoke, CI, packaging and documentation

Run HTTP against the compiled hardened API in an isolated smoke stack, with finite readiness retry, a public endpoint, and unauthenticated admin rejection. Use synthetic smoke-only credentials; wire the smoke into CI. Keep resource layout/CLI entrypoints and align README with current errors/build/test behavior.

### S5 — Explicit environment and runtime lifecycle

Handlers use injected env/config from the composition root, preserving defaults and database-backed dynamic LLM configuration. Runtime extraction is import-safe and keeps validation/init/listen/worker/shutdown ordering, partial cleanup and idempotent close behavior.

### S6 — Narrow, incremental organization

Create cohesive route modules for API capabilities, extract only application operations with an actual responsibility boundary, isolate reusable admin audit logic from its CLI, and leave unrelated stable modules where they are. No ORM, general DI, migrations, mass reformat, or broad folder moves.

## Tasks

- [x] T1 — Phase 0 compiled HTTP smoke, CI integration, README baseline; route: inline; status: complete; commit: `13f1be6`.
- [x] T2 — Extract admin attempt read routes; route: inline; status: complete; commit: `159156a`.
- [x] T3 — Extract remaining admin level-test routes and status/draft query operations; route: inline; status: complete; commit: `072512b`.
- [x] T4 — Extract LLM config routes, inject environment, separate audit helper from CLI; route: inline; status: complete; commit: `2674ff0`.
- [x] T5 — Isolate runtime lifecycle and test start/partial-failure/shutdown; route: inline; status: complete; commit: `eb70690`.
- [x] T6 — Smoke cleanup policy, close conditional report items, final documentation and integrated checks; route: inline; status: complete; commits: `ed62393`, `44ffcdb`.

## Log

- L1 — Original audit request: “me gustaría que crees una nueva rama y hagas un analisis sobre la api para ver qué tal consideras que está organizada si se siguen buenas prácticas y si podemos mejorarla para seguir buenas prácticas y organización del proyecto, no busco que cambies todo el código, pero sí qie el proyecto esté bien organizado. genera un documento que sea un informe que detalle todo lo que se podría mejorar y cómo, estructurandolo en fases y asegurandonos que no se rompe nada con este refactor. tampoco quiero que refactorices por refactorizar, sólo si es realmente necesario”
- L2 — Implementation authorization: “procede a implementar los cambios acordados en el documento”.
- L3 — Scope/workspace clarification: “no quiero worktrees, hay que hacer todo lo que dice el documento no entiendo por qué dices que acotaste el piloto. sigue en la rama que estamos”. This overrides the previous assistant narrowing to phases 0–1; use the current branch and implement all applicable report phases.
- L4 — Baseline `a0997ae` on `feature/learning-platform`; implementation branch `analysis/api-organization` starts at report commit `a87f3ef`. Do not touch unrelated pre-existing untracked user paths.
- L5 — T1 proof: `npm run check` passed; `make test-api` built and started the compiled hardened API in the isolated Compose stack and passed 628 tests (0 failed, 0 skipped), including the new TCP smoke and persisted denied-admin admission counter. The first attempted Compose config command used a path relative to the wrong working directory and did not test the file; `make test-api` subsequently parsed and exercised the Compose file successfully.
- L6 — T2 proof: `npm run check` passed; rebuilt the compiled container and ran `dist/tests/level-tests-attempts.test.js` against isolated PostgreSQL: 10 passed, 0 failed, 0 skipped. The four admin routes now register through `src/http/routes/admin-level-test-attempts.ts` on the root Fastify instance; origin-before-session order, DB operations, route metadata, cache headers and response mapping are unchanged.
- L7 — Task split ruling: keep the level-test generation/review route group and the LLM-config routes in separate work units because they call different operations and preserve different response/audit/failure contracts. This avoids turning “complete scope” into one mixed-purpose refactor.
- L8 — T3 proof: `npm run check` passed; compiled PostgreSQL integration tests `level-tests-admin.test.js` and `level-tests-policy.test.js` passed 56/56 with no skips. Generation and review routes remain on root Fastify with prior route order; status and draft SQL/projection now live in `level-tests-admin.ts`.
- L9 — T4 RED/GREEN proof: the new two-app isolated-environment test first failed because both HTTP handlers read the process-global provider (`openai`) rather than the injected app setting (`glm`). After injection and route extraction, `npm run check` passed and compiled `level-tests-policy`, `admin-routes`, and `admins-admin` integration files passed 43/43 with no skips. The two LLM config instances returned independent environment defaults and left `process.env` unchanged; audit writes and CLI tests stayed green.
- L10 — T5 RED/GREEN proof: the import test first failed because importing `main.ts` printed `DATABASE_URL is required` and exited 1. The refactored source now imports cleanly. `npm run check` passed; compiled Docker build and real HTTP smoke plus lifecycle/import tests passed 6/6 with no skips. Host `npm run build` could not overwrite pre-existing root-owned `services/api/dist` (permission denied); no attempt was made to delete or change those files, and the official Docker build compiled successfully. A first compiled test run exposed that production omits `tsx`; the test now selects compiled JS when available and source TS under tsx.
- L11 — T6 RED/GREEN proof: fake Docker baseline reproduced the cleanup defect (`make test-api` failed at tests and issued no `down --volumes`). After the trap-based cleanup, fake runs showed default failure cleanup (3 calls, including only the named `looro-smoke` teardown), explicit `KEEP_SMOKE_STACK=1` retention (2 calls, no teardown), normal cleanup (3 calls), teardown failure returned nonzero, and an invalid retention value failed before any Docker call. The full real `make test-api` then built/started the compiled stack, passed 634/634 tests with 0 skips and ran the HTTP smoke, and removed the smoke network/containers/volume. `npm --prefix services/api run check`, `make check` (138 tests; exit 0, with existing Python sqlite ResourceWarnings), and `make obs-check` passed. A source route declaration comparison between baseline `a87f3ef` and current app plus extracted route modules matched 78/78 method/path expressions. Conditional progress/content grouping is not justified: progress has only GET/POST handlers and calls the existing `learning-progress` operations; account/purchase/session refactors and broad directory moves remain separate only-if-needed work. `gentle-ai` is unavailable here, so no native RDD review was claimed.
- L12 — Independent whole-feature review (`a87f3ef..387ecab`) passed S1–S6 with non-blocking advisories. It found the smoke DB assertion could pass from a stale unrelated admission row and that the README did not state the repository-root cwd for Make. Reproduced the first issue in disposable `looro-smoke`: without sending the HTTP request, a seeded unrelated row returned `hits=77`, satisfying the old `>=1` assertion. Correction now snapshots the sum of network hits before and after the single denied request and expects exactly +1; focused compiled smoke passed 1/1 against the seeded stale row and TypeScript check passed. README now documents direct Compose cwd, uses the same `looro-smoke` project name, and shows `make -C ../.. test-api` from `services/api`. Independent probes also compared 126 runtime route registrations, 48 rejected requests and persisted admission effects, and five startup composition failure scenarios across baseline/candidate scratch archives. Minor deferred: preserve the five composition probes as committed main-wiring regression tests when that entrypoint is next changed; current lifecycle tests cover the extracted helper and scratch probes passed. No worktree was created. Risk: none (test assertion and documentation only; production runtime behavior unchanged).
