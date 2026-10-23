# Proposal: observability

## Intent

Equip the Palique API (`services/api`) with the observability stack proven in
the periodico reference project: OpenTelemetry → OTel Collector → Prometheus
(metrics and alerts) + Tempo (traces) + Grafana (provisioned dashboards) +
Alertmanager with a local alert sink. Instrumentation covers RED HTTP per
request, the DB pool, the weekly level-test generation job, and LLM calls
(`chatCompletion`), with structured JSON logs on stdout. Everything is
activated by `OTEL_ENABLED`: with any value other than `"true"` the whole
pipeline is a no-op, so unit tests and the smoke stack run unchanged and no
product path hard-depends on the collector being healthy.

## Problem

The API has no runtime telemetry today: `package.json` has no observability
dependencies, Fastify runs with `logger: false`, four spots emit bare
`console.warn`/`console.error` lines, the existing `genReqId: randomUUID()`
never reaches logs or error bodies, and there are no metrics, traces, or
alerts. The weekly exam-generation scheduler runs in-process; a silent failure
would go unnoticed until learners miss their weekly test. Incidents can only
be debugged by reading unstructured container output with no request
correlation.

## Approach

Three new code modules with strict responsibilities: `src/log.ts` (structured
JSON logger whose field type admits only scalars, so bodies, headers, tokens,
and secrets cannot reach stdout), `src/metrics.ts` (re-initializable factory of
`palique_*` domain instruments over the OTel meter), and `src/observability.ts`
(the only module installing global providers; init/shutdown with metric flush).
Emission points stay minimal: Fastify hooks in `app.ts` (RED histogram +
`http_request`/`request_failed` logs + `requestId` in 5xx bodies), `main.ts`
(structured replacements for the current `console.*` calls), `llm-config.ts`
(`chatCompletion` counters/histogram/tokens), and
`level-tests-generation-jobs.ts` (job counter, duration, last-success gauge).

Six new Docker services in `services/api/compose.yaml`: `otel-collector`
(OTLP HTTP :4318 → Tempo + Prometheus exporter), `tempo` (:3200),
`prometheus` (:9090, scrape 15s, 90d retention capped at 2GB, rule files),
`alertmanager` (:9093, routed to the local sink), `webhook-logger` (:5001,
python:3.12-alpine — alerts always verifiable locally), and `grafana`
(:3003, provisioned datasources + `palique-overview` dashboard). Every port is
published on `127.0.0.1` only; production access is via SSH tunnel while Caddy
keeps 80/443.

The observability contract (source of truth for implementation):

- **Metrics**: `http.server.request.duration` (explicit buckets) exported as
  `http_server_request_duration_seconds_*`; domain series
  `palique_requests_rejected_total`, `palique_db_pool_connections`,
  `palique_db_pool_max_connections`, `palique_leveltest_generation_jobs_total`,
  `palique_leveltest_generation_job_duration_seconds_*`,
  `palique_leveltest_generation_last_success_unix_seconds`,
  `palique_llm_requests_total`, `palique_llm_request_duration_seconds_*`,
  `palique_llm_tokens_total` — all with closed-cardinality labels (route
  template, never raw URLs; no user identifiers).
- **Logs**: one JSON object per line; common fields `timestamp` (ISO 8601
  UTC), `level`, `event`, plus `request_id`/`trace_id`/`error_code` when
  present; defined events replace today's `console.*` calls one-for-one.
- **Alerts**: `ApiHttp5xxRatioHigh`, `ApiMetricsMissing`,
  `LevelTestGenerationFailed`, `LevelTestGenerationStale`,
  `LlmFailuresSpike`, `DbPoolSaturated` — severity, runbook annotation,
  `for: 1m` (5m for `absent()`), validated by `promtool`/`amtool` via
  `make obs-check` and a CI step.
- **SLOs**: three honest internal targets (≥ 99.5% non-5xx per month, p95 GET
  < 500 ms, weekly generation on time) documented with their queries and
  limitations.

No SQL migrations: Prometheus and Tempo persist in Docker volumes. Tests use
`InMemoryMetricReader` (unit) and the existing PG-backed integration pattern.

## Out of scope (this change)

- Native apps (`apps/android`, `apps/ios`): client product telemetry already
  exists (`usage_events`); no `*Data.kt`/`*Data.swift` is touched.
- LLM cost in €: token counters only; pricing stays in `/v1/pricing` and the
  ledger (no second source of truth in PromQL).
- Product analytics: `admin-metrics.ts` and `usage_events` remain untouched —
  product analytics is not technical observability.
- External alert delivery (Slack/PagerDuty): local `webhook-logger` sink only;
  future evolution would add a second receiver via `ALERTMANAGER_WEBHOOK_URL`.
- Automated SLO tracking: SLOs are documented queries evaluated manually.
- Latency alerts (SLO 2 stays documentation-only in this phase).
- Any new database table or migration.
