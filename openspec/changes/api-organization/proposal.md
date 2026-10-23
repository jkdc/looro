# Proposal: Incrementally organize the API without changing its contracts

## Problem

`services/api/src/app.ts` combines route registration, shared HTTP policy, and application operations across several capabilities. Other parts of the API already have useful seams: injected `createApp`, domain modules, explicit PostgreSQL transactions, and integration tests. A wholesale rewrite would discard these strengths and introduce avoidable risk.

## User requirements

The user requested implementation of all recommendations in `informe-organizacion-api-2026-10-23.md`, including its phases and preservation safeguards. The user also clarified: “no quiero worktrees” and “sigue en la rama que estamos”.

## Scope

Implement all recommendations in the report that are applicable after checking their stated conditions: a compiled-server smoke check and route-policy characterization; cohesive HTTP route modules for the identified admin exam/LLM capabilities; extraction of application operations that currently live in handlers where a real responsibility boundary exists; explicit environment dependencies at the HTTP composition boundary; a testable runtime lifecycle; documentation/build/test-harness alignment; and the identified support cleanups. Do not stop after the first route group merely because it was called a pilot in the report.

The work remains on `analysis/api-organization`. Keep public routes, methods, JSON, status codes, headers, authentication/admission, CSRF, rate limiting, webhook bytes, transaction boundaries, audit behavior, concurrency, async launch behavior, CLI entry points, and compiled resource paths unchanged. No database migration or data rewrite is in scope.

## Non-goals

- No rewrite of the API, database layer, or framework.
- No blanket Clean/Hexagonal layering, repository framework, ORM, microservices, or generalized dependency-injection container.
- No bulk file moves, generated-source edits, public API redesign, or unrelated formatting/dependency upgrades.
- Do not remove security counters or audit writes when rejecting requests.

## Delivery

Use one coherent, testable work unit per applicable phase. Each route extraction is behavior-preserving and gated by its existing PostgreSQL-backed HTTP tests plus the compiled-container smoke check. Wider operation/configuration/runtime changes each retain their own focused tests and rollback boundary. Where the report makes a recommendation conditional, record evidence for applicability or non-applicability; do not substitute a blanket “pilot only” scope.
