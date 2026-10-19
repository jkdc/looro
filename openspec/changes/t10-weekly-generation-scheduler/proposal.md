# Proposal: Safe weekly level-test generation scheduler

## Problem
Weekly generated level-tests currently rely on a manually invoked CLI. The planned scheduler must remain disabled by default and cannot issue duplicate billable requests across API replicas and the CLI.

## Scope
Add an in-process, UTC one-shot scheduler wired into the API lifecycle, a durable per-language/level/week generation-job ledger, and equivalent database exclusion for scheduler and CLI. Complete the API Compose environment with explicit defaults while leaving generation disabled.

## Non-goals
No live configuration reload, scheduler admin UI, real provider calls, deployment activation, or historical-week backfill. Draft approval remains a separate T11 task.
