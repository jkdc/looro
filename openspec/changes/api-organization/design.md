# Design: Incremental API organization

## Context and current seams

The API is a modular monolith, not an unstructured service. `createApp(services)` provides an HTTP composition seam; domain modules own many operations; `db.ts` exposes explicit transaction clients; tests cover PostgreSQL concurrency and recovery. The main pressure point is the size and responsibility mix in `src/app.ts`, plus the process environment read by selected handlers and the runtime lifecycle in `src/main.ts`.

## Approach

Keep Fastify and the existing domain modules. Gradually register cohesive route groups from `src/http/routes/` on the existing Fastify instance. Keep global hooks, raw webhook parser, admission, and shared error handling in the root composition until their scope has dedicated characterization. Pass only the database and narrowly scoped authorizers/configuration each group requires.

Move decisions or SQL out of handlers only where the report identified an application operation. Preserve the exact `PoolClient`, transaction, lock, audit, error, and asynchronous-job boundaries. Route extraction and business-rule changes must not share a commit.

Capture process environment once at the composition root and inject it into app operations that require static generation/provider configuration. Preserve database-backed dynamic LLM configuration and its current precedence. Do not introduce a project-wide configuration object.

Make startup and shutdown callable/testable without executing the server merely by importing its entry module. Retain `main.ts` as the process entry point and preserve the existing order of validation, initialization, listen, worker start, and cleanup.

## Phases and gates

1. **Characterization and runtime smoke:** exercise the compiled, hardened API over HTTP in the isolated PostgreSQL smoke stack; verify readiness, a public route, and an unauthenticated admin rejection. Wire this check into CI. Correct only confirmed README drift.
2. **Route modules:** extract admin exam-attempt reads, remaining admin level-test routes, and the separately identified LLM/configuration route group where its cohesive boundary is confirmed. Preserve root hooks/policies and exact route metadata. Existing integration tests are baseline characterization and must pass before and after; do not invent behavior solely to get a RED result for a pure move.
3. **Application operations/configuration:** extract inline status/draft query/projection work and pass environment dependencies explicitly. Keep transactional mutations and policy checks in their existing business modules. Consider other operations only where the report identifies a concrete responsibility boundary and tests can prove the existing contract.
4. **Runtime lifecycle:** separate construction/start/close sufficiently for deterministic lifecycle tests; no new DI framework or behavior changes.
5. **Support and documentation:** isolate reusable admin audit code from its CLI entry point, make smoke-stack cleanup policy explicit and bounded to that disposable stack, and update the API entry documentation. Apply local readability improvements to changed blocks only; no global formatting sweep or speculative directory moves.

Each applicable phase is implemented; conditional report recommendations are closed with evidence rather than left pending by default. Each phase stops on an unexplained difference. Fixing a pre-existing defect is a separate behavior change and may not be hidden within an organizational extraction.

## Verification strategy

- Preserve the route method/template inventory and test route behavior, including unauthorized, wrong-origin, missing/foreign IDs, pagination/export bounds, response headers, and no-store behavior.
- Run actual HTTP requests against the compiled container with a disposable database and synthetic smoke-only admin admission configuration.
- Test extracted data operations using PostgreSQL and compare persisted/audited state, not just mocks.
- Test environment isolation, defaults, dynamic database-backed settings, and disabled capabilities.
- Test runtime startup, partial startup failure, worker ordering, pending work, and idempotent shutdown with controlled dependencies.
- Run focused tests, `npm run check`, `npm run build`, `make test-api`, `make obs-check`, and `make check` as applicable. A database-dependent skipped test is not proof for that path.
- No real provider, production credentials, user services, or real database are used.

## Rollback

Keep every work unit free of migrations and persisted-format changes. Reverting a route move restores the previous code without data repair. Keep each work unit's integration/build/test wiring adjacent to the behavior it proves.
