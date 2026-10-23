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

- [x] T1 — Phase 0 compiled HTTP smoke, CI integration, README baseline; route: inline; status: complete; commit: `7a597a2`.
- [ ] T2 — Extract admin attempt read routes; route: inline; status: pending; commit: —
- [ ] T3 — Extract remaining admin level-test and applicable LLM/config routes plus status/draft operations; route: inline; status: pending; commit: —
- [ ] T4 — Inject env dependencies, extract justified application operations, and separate admin audit from CLI; route: inline; status: pending; commit: —
- [ ] T5 — Isolate runtime lifecycle and test start/partial-failure/shutdown; route: inline; status: pending; commit: —
- [ ] T6 — Smoke cleanup policy, close conditional report items, final documentation and integrated checks; route: inline; status: pending; commit: —

## Log

- L1 — Original audit request: “me gustaría que crees una nueva rama y hagas un analisis sobre la api para ver qué tal consideras que está organizada si se siguen buenas prácticas y si podemos mejorarla para seguir buenas prácticas y organización del proyecto, no busco que cambies todo el código, pero sí qie el proyecto esté bien organizado. genera un documento que sea un informe que detalle todo lo que se podría mejorar y cómo, estructurandolo en fases y asegurandonos que no se rompe nada con este refactor. tampoco quiero que refactorices por refactorizar, sólo si es realmente necesario”
- L2 — Implementation authorization: “procede a implementar los cambios acordados en el documento”.
- L3 — Scope/workspace clarification: “no quiero worktrees, hay que hacer todo lo que dice el documento no entiendo por qué dices que acotaste el piloto. sigue en la rama que estamos”. This overrides the previous assistant narrowing to phases 0–1; use the current branch and implement all applicable report phases.
- L4 — Baseline `a0997ae` on `feature/learning-platform`; implementation branch `analysis/api-organization` starts at report commit `a87f3ef`. Do not touch unrelated pre-existing untracked user paths.
- L5 — T1 proof: `npm run check` passed; `make test-api` built and started the compiled hardened API in the isolated Compose stack and passed 628 tests (0 failed, 0 skipped), including the new TCP smoke and persisted denied-admin admission counter. The first attempted Compose config command used a path relative to the wrong working directory and did not test the file; `make test-api` subsequently parsed and exercised the Compose file successfully.
