# Observability implementation tasks

Mirror of Tasks 2–12 of `docs/superpowers/plans/2026-10-22-observability.md`.
The specs in `specs/observability-pipeline/spec.md` are the authority for the
requirements; the plan is the authority for the step-by-step details.

## Task 2 — Structured logger `src/log.ts`

- [ ] `LogFields = Record<string, string | number | boolean | null | undefined>` and
      `log.{info,warn,error}(event, fields?)` emitting one JSON line per call
      (`timestamp` ISO 8601 UTC, `level`, `event`; `undefined` dropped).
- [ ] Unit test: one line per call, scalars preserved, `undefined` dropped, `null` kept.

## Task 3 — Instrument registry `src/metrics.ts`

- [ ] Install exact `@opentelemetry/*` versions; verify a single `@opentelemetry/api` copy.
- [ ] `PaliqueMetrics` interface with the ten contract instruments, live-binding
      `paliqueMetrics`, re-initializable `initDomainMetrics(meter)`.
- [ ] Contract test collecting sums/histograms via `InMemoryMetricReader`.

## Task 4 — OTel initialization `src/observability.ts`

- [ ] No-op guard: `OTEL_ENABLED != "true"` ⇒ nothing installed.
- [ ] Resource `service.name=palique-api`; TracerProvider + MeterProvider
      (PeriodicReader, explicit bucket boundaries); shutdown flushes metrics.
- [ ] DB pool observable gauges (`state` ∈ idle/in_use/waiting, max connections).

## Task 5 — RED HTTP, request logs, requestId in 5xx (`app.ts` + `main.ts`)

- [ ] `onRequest`/`onResponse` hooks: RED histogram with method / route
      template / status labels; `http_request` log per response.
- [ ] Error handler: `request_failed` log and `requestId` in 5xx bodies, never
      internals; limiter rejection counter (`rate_limit` | `trusted_proxy`).
- [ ] `main.ts`: init/shutdown wired; structured replacements for the existing
      `console.*` events (`level_test_generation_job`, `minute_commerce_failure`,
      `live_startup_failed`, `retention_cleanup_failed`,
      `guest_allowance_retry_failed`).
- [ ] Integration test with `createApp` against PG (`TEST_DATABASE_URL`).

## Task 6 — Pipeline instrumentation (generation jobs + `chatCompletion`)

- [ ] Wrap `runLevelTestGenerationJob`: jobs counter (`language`, `level`,
      `status`), duration histogram, last-success gauge on success.
- [ ] Instrument `chatCompletion`: requests counter (`provider`, `status`,
      `error_code` on failure), duration histogram, tokens counter
      (`input`/`output`).
- [ ] Tests with the in-memory reader.

## Task 7 — Telemetry configuration `services/api/telemetry/`

- [ ] `otel-collector-config.yaml` (OTLP :4318 → Tempo + Prometheus :8889, batch).
- [ ] `tempo.yaml` (receiver bound to `0.0.0.0:4318` inside the container).
- [ ] `prometheus.yml` (scrape 15s, retention 90d / 2GB cap, `rule_files:
      alerts.yml`, alerting → alertmanager:9093).
- [ ] `alerts.yml`: the six contract rules with severity, runbook annotation,
      `for: 1m` (5m for `absent()`), header documenting real series names.
- [ ] `alertmanager.yml` (single route → `webhook-logger:5001`).
- [ ] Grafana provisioning: datasources (Tempo default, Prometheus `uid:
      prometheus`) + dashboard provider.

## Task 8 — Docker containers (`compose.yaml`) and Make targets

- [ ] Six new services with ports only on `127.0.0.1` (4318, 3200, 9090, 9093,
      5001, 3003); `OTEL_*` env on the `api` service; named volumes.
- [ ] `tests/compose.smoke.yaml`: explicit comment that telemetry is absent.
- [ ] Makefile: `obs-check` (`promtool`/`amtool` in Docker) + `.PHONY`.

## Task 9 — Grafana dashboard `palique-overview.json`

- [ ] Provisioned dashboard: RED panels, DB pool, generation freshness, LLM
      counters, alert panels.

## Task 10 — CI (`checks.yml`)

- [ ] Server job step running `make obs-check` (config validation).

## Task 11 — Documentation

- [ ] `services/api/docs/observability.md` (Spanish): full contract — metrics,
      logs, alerts, runbooks, SLOs, privacy policy, port table.
- [ ] `AGENTS.md`: command table, file map, metrics convention.
- [ ] `services/api/README.md`: Observability section with the port table.

## Task 12 — End-to-end verification and close

- [ ] `npx tsc --noEmit && npm test` PASS; `make test-api` PASS with no
      observability container running.
- [ ] Live stack: targets up in Prometheus, Tempo ready, webhook-logger
      receiving, `make health` unaffected.
- [ ] Privacy regression: aggressive grep over `docker compose logs api` finds
      no secrets/tokens/authorization values.
- [ ] `verify-report.md` with commands, outputs, confirmed real label names,
      and honest pending items.
