# Observability implementation tasks

Mirror of Tasks 2–12 of `docs/superpowers/plans/2026-10-22-observability.md`.
The specs in `specs/observability-pipeline/spec.md` are the authority for the
requirements; the plan is the authority for the step-by-step details.

Status: every task is complete and verified. The commands, outputs, confirmed
label names, deviations discovered during live verification and the explicit
pending items are recorded in `verify-report.md`.

## Task 2 — Structured logger `src/log.ts`

- [x] `LogFields = Record<string, string | number | boolean | null | undefined>` and
      `log.{info,warn,error}(event, fields?)` emitting one JSON line per call
      (`timestamp` ISO 8601 UTC, `level`, `event`; `undefined` dropped).
- [x] Unit test: one line per call, scalars preserved, `undefined` dropped, `null` kept.

## Task 3 — Instrument registry `src/metrics.ts`

- [x] Install exact `@opentelemetry/*` versions; verify a single `@opentelemetry/api` copy.
- [x] `PaliqueMetrics` interface with the ten contract instruments, live-binding
      `paliqueMetrics`, re-initializable `initDomainMetrics(meter)`.
- [x] Contract test collecting sums/histograms via `InMemoryMetricReader`.

## Task 4 — OTel initialization `src/observability.ts`

- [x] No-op guard: `OTEL_ENABLED != "true"` ⇒ nothing installed.
- [x] Resource `service.name=palique-api`; TracerProvider + MeterProvider
      (PeriodicReader, explicit bucket boundaries); shutdown flushes metrics.
- [x] DB pool observable gauges (`state` ∈ idle/in_use/waiting, max connections).

## Task 5 — RED HTTP, request logs, requestId in 5xx (`app.ts` + `main.ts`)

- [x] `onRequest`/`onResponse` hooks: RED histogram with method / route
      template / status labels; `http_request` log per response.
- [x] Error handler: `request_failed` log and `requestId` in 5xx bodies, never
      internals; limiter rejection counter (`rate_limit` | `trusted_proxy`).
- [x] `main.ts`: init/shutdown wired; structured replacements for the existing
      `console.*` events (`level_test_generation_job`, `minute_commerce_failure`,
      `live_startup_failed`, `retention_cleanup_failed`,
      `guest_allowance_retry_failed`).
- [x] Integration test with `createApp` against PG (`TEST_DATABASE_URL`).

## Task 6 — Pipeline instrumentation (generation jobs + `chatCompletion`)

- [x] Wrap `runLevelTestGenerationJob`: jobs counter (`language`, `level`,
      `status`), duration histogram, last-success gauge on success.
- [x] Instrument `chatCompletion`: requests counter (`provider`, `status`,
      `error_code` on failure), duration histogram, tokens counter
      (`input`/`output`).
- [x] Tests with the in-memory reader.

## Task 7 — Telemetry configuration `services/api/telemetry/`

- [x] `otel-collector-config.yaml` (OTLP :4318 → Tempo + Prometheus :8889, batch).
- [x] `tempo.yaml` (receiver bound to `0.0.0.0:4318` inside the container).
- [x] `prometheus.yml` (scrape 15s, retention 90d / 2GB cap, `rule_files:
      alerts.yml`, alerting → alertmanager:9093).
- [x] `alerts.yml`: the six contract rules with severity, runbook annotation,
      `for: 1m` (5m for `absent()`), header documenting real series names.
- [x] `alertmanager.yml` (single route → `webhook-logger:5001`).
- [x] Grafana provisioning: datasources (Tempo default, Prometheus `uid:
      prometheus`) + dashboard provider.

## Task 8 — Docker containers (`compose.yaml`) and Make targets

- [x] Six new services with ports only on `127.0.0.1` (4318, 3200, 9090, 9093,
      5001, 3003); `OTEL_*` env on the `api` service; named volumes.
      A seventh one-shot container (`tempo-init`) chowns the Tempo volume; see
      `verify-report.md` §5.1.
- [x] `tests/compose.smoke.yaml`: explicit comment that telemetry is absent.
- [x] Makefile: `obs-check` (`promtool`/`amtool` in Docker) + `.PHONY`.

## Task 9 — Grafana dashboard `palique-overview.json`

- [x] Provisioned dashboard: RED panels, DB pool, generation freshness, LLM
      counters, alert panels.

## Task 10 — CI (`checks.yml`)

- [x] Server job step running `make obs-check` (config validation). The step
      sets `POSTGRES_PASSWORD` and `API_DOMAIN` inline because CI has no `.env`
      and `compose config` interpolates both; see `verify-report.md` §5.7.

## Task 11 — Documentation

- [x] `services/api/docs/observability.md` (Spanish): full contract — metrics,
      logs, alerts, runbooks, SLOs, privacy policy, port table.
- [x] `AGENTS.md`: command table, file map, metrics convention.
- [x] `services/api/README.md`: Observability section with the port table.

## Task 12 — End-to-end verification and close

- [x] `npx tsc --noEmit && npm test` PASS; `make test-api` PASS with no
      observability container running.
- [x] Live stack: targets up in Prometheus, Tempo ready, webhook-logger
      receiving, `make health` unaffected.
- [x] Privacy regression: aggressive grep over `docker compose logs api` finds
      no secrets/tokens/authorization values.
- [x] `verify-report.md` with commands, outputs, confirmed real label names,
      and honest pending items.

## Remediation follow-up (2026-10-23)

The original task completion above is historical evidence; the following
requirements and checks supersede the original generation alert design.

- [x] Scheduler defaults to Sunday at 04:00 UTC; generation remains opt-in.
- [x] Removed the missing/stale-generation alert; added an actual-failure
      timestamp gauge with durable startup seeding when records exist.
- [x] Retained five alert rules and added `promtool test rules` behavioral
      coverage to `make obs-check` and CI.
- [x] Added a full-window guard for pool saturation and a startup-spike
      no-fire regression.
- [x] Isolated Collector probe reproduced API/CLI counter-series collision;
      fixed resource identity with stable role IDs and refreshed durable
      failure timestamps every minute from API.
- [x] Reconciled current plan, OpenSpec, runbook and historical audit/report
      references with the user-approved actual-failure-only alert requirement.
- [ ] Record final full API/integration verification in `verify-report.md`.
