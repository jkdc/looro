# Delta Spec: observability-pipeline

## ADDED Requirements

### Requirement: No-op activation gate

The observability pipeline SHALL be inactive unless `OTEL_ENABLED` equals
`"true"`: with any other value (or unset), no provider, exporter, or instrument
SHALL record or export anything, product behavior SHALL be identical, and no
product or test path SHALL require any observability container to be running.

#### Scenario: tests and smoke stack without telemetry

- **WHEN** the unit suite, `make test-api`, or the smoke stack runs with
  `OTEL_ENABLED` unset or not `"true"`
- **THEN** no telemetry is recorded or exported, no observability container is
  required, and all tests pass unchanged.

#### Scenario: collector down does not affect the API

- **WHEN** the OTel collector is unreachable or unhealthy and
  `OTEL_ENABLED="true"`
- **THEN** product endpoints keep working (export failures never fail a
  request) and the API does not hard-depend on the collector.

### Requirement: RED HTTP metrics per request

Every HTTP request SHALL produce RED metrics through the
`http.server.request.duration` histogram (unit `s`, explicit buckets
`[0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10]`), exported via the
collector as `http_server_request_duration_seconds_{count,sum,bucket}` and
labeled with `http.request.method`, `http.route`, and
`http.response.status_code`. `http.route` SHALL be the route template (or
`unmatched`), never a raw URL carrying identifiers.
HTTP server spans SHALL use `SERVER` kind, record only the matched route
template or `unmatched`, and SHALL NOT export `url.path` or any raw request
target. The active span context SHALL remain isolated to its request until the
response completes.

#### Scenario: parameterized route records the template

- **WHEN** a request hits a parameterized route
- **THEN** the recorded `http.route` is the route template without concrete
  identifiers, and method, status code, and duration are recorded on the
  RED series.

#### Scenario: unmatched route

- **WHEN** a request matches no registered route
- **THEN** the metric is still recorded with `http.route="unmatched"`.

### Requirement: 5xx responses carry requestId and never internals

Error responses with status ≥ 500 SHALL include the request's `requestId` in
the body and SHALL NOT include stack traces or internal error details
(non-ServiceError failures report the generic code `service_unavailable`). Each
5xx SHALL also emit the `request_failed` error log with `request_id`, `method`,
`route`, `status`, and `error_code`, and include `X-Request-Id` with the same
request identifier. Direct 5xx responses SHALL preserve their existing body;
when no application error code is available, the log uses `server_error`.

#### Scenario: internal error response

- **WHEN** an unhandled failure produces a 500 response
- **THEN** the body carries the `requestId` (and `service_unavailable` for
  non-ServiceError), contains no stack trace or internals, and the
  `request_failed` log line correlates the same `request_id`.

### Requirement: Structured JSON logs with allow-listed fields

The logger SHALL emit one JSON object per line to stdout whose values are only
`string | number | boolean | null` (objects and arrays are inexpressible by
typing; `undefined` fields are dropped), with common fields `timestamp`
(ISO 8601 UTC), `level` (`info`|`warn`|`error`), and `event`, plus
`request_id`, `trace_id`, and `error_code` when present. No request body,
`Authorization` header, token, transcript, Stripe payload, secret, IP, or
account identifier SHALL ever be logged. The defined events
(`http_request`, `request_failed`, `startup_diagnostic`,
`level_test_generation_job`, `minute_commerce_failure`, `live_startup_failed`,
`retention_cleanup_failed`, `guest_allowance_retry_failed`) SHALL replace the
existing `console.*` calls without changing their payload data.
At runtime, `timestamp`, `level`, and `event` SHALL be reserved against caller
override, and fields whose names indicate raw paths, URLs, query data, headers,
bodies, credentials, or arbitrary exceptions SHALL be discarded.

#### Scenario: request completion log

- **WHEN** a request completes
- **THEN** one `http_request` line is emitted with `request_id`, `method`,
  `route`, `status`, `duration_ms` (and `trace_id` when sampled) and no body,
  header, or personal-data field.

#### Scenario: scalar-only field set

- **WHEN** a caller attempts to pass an object, array, or non-scalar value as
  a log field
- **THEN** the closed `LogFields` type rejects it at compile time, so no such
  line can reach stdout.

### Requirement: Domain counters with contract names

Domain instruments SHALL use the `palique_` prefix and export exactly these
Prometheus series with closed-cardinality labels:
`palique_requests_rejected_total{reason=rate_limit|trusted_proxy}`,
`palique_db_pool_connections{state=idle|in_use|waiting}`,
`palique_db_pool_max_connections`,
`palique_leveltest_generation_jobs_total{language,level,status=finished|failed|unknown}`,
`palique_leveltest_generation_job_duration_seconds_*{language,level}`,
`palique_leveltest_generation_last_success_unix_seconds`,
`palique_leveltest_generation_last_failure_unix_seconds`,
`palique_llm_requests_total{provider,status=ok|failed,error_code (failed only)}`,
`palique_llm_request_duration_seconds_*{provider}`, and
`palique_llm_tokens_total{provider,direction=input|output}`, with resource
`service.name=palique-api` and no user identifier in any label. The API and
generation CLI SHALL use distinct fixed `service.instance.id` values (`api`
and `leveltest-generate`) so simultaneous exporters do not overwrite identical
Prometheus series; these role IDs SHALL not vary across process restarts.
Counters SHALL be created via a re-initializable factory over the OTel meter.

#### Scenario: counter series absent until first event

- **WHEN** no LLM request has been made since process start
- **THEN** `palique_llm_requests_total` does not exist yet (OTel exports no
  empty counter series), and alert rules treat the absent series as healthy.

#### Scenario: generation job outcome

- **WHEN** a weekly generation job finishes or fails
- **THEN** `palique_leveltest_generation_jobs_total` is incremented with the
  job's `language`, `level`, and resulting `status`, and a successful run
  updates `palique_leveltest_generation_last_success_unix_seconds`.
- **AND** an actual failed execution updates
  `palique_leveltest_generation_last_failure_unix_seconds`, including when
  database access or job finalization throws.

### Requirement: Alerts loaded in Prometheus

`telemetry/alerts.yml` SHALL define these five rules, all filtering
`job="otel-collector", exported_job="palique-api"` and annotated with a
`runbook` pointing at `docs/observability.md#runbooks`, evaluated every 30s
with `for: 1m` (5m for `absent()` rules):

- `ApiHttp5xxRatioHigh`: 5xx ratio over RED count in 5m > 5 % (critical).
- `ApiMetricsMissing`: `absent()` on the RED series > 5m (critical).
- `LevelTestGenerationFailed`: an actual recorded failure during the last 24h,
  expressed as `time() - max(palique_leveltest_generation_last_failure_unix_seconds)
  < 86400` (warning). Missing or stale output alone SHALL NOT fire this rule.
- `LlmFailuresSpike`: more than 5 failed LLM requests in 1h, anchored with
  `(sum(increase(palique_llm_requests_total{status="failed"}[1h]))
  or vector(0)) > 5` (warning).
- `DbPoolSaturated`: `in_use` connections remain > 90 % of
  `palique_db_pool_max_connections` for a complete 5m sample window, with at
  least 20 observations at the 15s scrape interval (warning).

Prometheus SHALL load the file via `rule_files` and route firing alerts to
Alertmanager (single route to the local `webhook-logger` sink). Both configs
SHALL pass syntax validation and behavioral fixtures: `promtool check rules`,
`promtool test rules telemetry/alerts.test.yml`, and `amtool check-config`,
through `make obs-check` and CI. Fixtures SHALL cover firing, resolution,
healthy/no-data conditions, and the short startup pool-spike case.

#### Scenario: missing weekly output is not a generation failure

- **WHEN** a scheduled run or its output is missing or old, and no execution
  has failed
- **THEN** no generation alert fires.

#### Scenario: actual generation failure is alertable

- **WHEN** a generation execution fails
- **THEN** its failure timestamp is exported and `LevelTestGenerationFailed`
  fires while that timestamp is less than 24 hours old.
- **AND** the alert resolves after 24 hours without a newer failure.

#### Scenario: persisted failure survives restart

- **WHEN** OTel is enabled at startup and durable failed job records exist
- **THEN** the last failure timestamp is seeded and remains alertable.
- **AND** the API refreshes the durable success/failure timestamps at least
  once per minute while telemetry is enabled, so a one-shot generation CLI
  export remains represented after the CLI exits.
- **AND** the API performs no telemetry database query for this refresh when
  OTel is disabled.

#### Scenario: opt-in weekly schedule default

- **WHEN** no schedule override is configured
- **THEN** generation remains disabled unless explicitly enabled and defaults
  to Sunday at 04:00 UTC.

#### Scenario: metric series missing

- **WHEN** the RED series disappears for more than 5 minutes (API down)
- **THEN** `ApiMetricsMissing` fires (critical) instead of the domain alerts
  silently self-resolving.

### Requirement: Observability ports on loopback only

All observability services SHALL publish their ports exclusively bound to
`127.0.0.1` (never `0.0.0.0`): OTel collector OTLP `4318`, Tempo `3200`,
Prometheus `9090`, Alertmanager `9093`, webhook-logger `5001`, and Grafana
`3003`. On the production host only Caddy SHALL expose 80/443; Prometheus and
Grafana access is via SSH tunnel.

#### Scenario: stack binding check

- **WHEN** the observability stack is up and the compose port mappings are
  inspected
- **THEN** every observability port is bound to `127.0.0.1` and none is
  reachable from external interfaces.
