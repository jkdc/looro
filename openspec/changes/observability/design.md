# Design: observability

## Intent

Replicate the periodico observability pattern (same images, same network
topology, same configuration decisions) replacing Go instrumentation with its
Node/TypeScript equivalent, while respecting two structural differences of
Palique: there is no separate worker (jobs live in the API process, so
process-down detection rests on a single RED series), and the privacy policy is
stricter (no `user_id`, no IPs in telemetry — `request_id`/`trace_id` are the
correlation keys).

## Decisions (D1–D8)

### D1 — Manual Fastify instrumentation (RED + spans per request)

Instrument with two existing Fastify hooks (`onRequest`/`onResponse`) plus the
error handler. Rejected: `@opentelemetry/auto-instrumentations-node` and
`@fastify/otel` — auto-instrumentations drags ~50 transitive dependencies and
patches the global `http` module, clashing with the repo's minimal style (6
dependencies) and with the fine-grained control the privacy policy demands.
With 2 hooks the code is auditable at a glance.

### D2 — Own JSON logger (~30 lines, allow-list by typing)

A closed API (`LogFields = Record<string, string | number | boolean | null>`)
makes it impossible by construction to log objects (error, body) to stdout.
Rejected: `pino` — the repo already constrains Fastify to `logger: false` for
privacy; an own logger enforces the scalar-only contract through the type
system instead of by discipline.

### D3 — Domain metrics as a re-initializable factory (`initDomainMetrics(meter)`)

Create instruments inside a factory, not at module import. Rejected: module-level
instruments — the classic OTel JS pitfall: instruments created at import time
freeze over the no-op meter if the global provider is installed later, silently
producing no data. The factory makes initialization order irrelevant and tests
deterministic.

### D4 — Alerts on collector-exported RED series

Alert against `http_server_request_duration_seconds_*` coming from the
collector. Rejected: exposing `/metrics` directly from Node — keeping the
OTLP → collector path unifies metric/trace transport and replicates the
topology proven in periodico.

### D5 — Prometheus retention 90d, capped at 2GB

Monthly SLOs need a window ≥ 30d. Rejected: default 15d retention — periodico
documented this as a known carence; Palique fixes it from day one.

### D6 — LLM token counter, not cost

Count tokens (unitary counter, `direction` ∈ `input`/`output`). Rejected:
estimated cost in € per model — token prices live in `/v1/pricing` (nanoUSD)
and are already accounted in the ledger; duplicating the tariff in PromQL
creates a second source of truth. Tokens are enough for anomaly alerts.

### D7 — No `user_id`/IP in labels or logs

Identify nothing personal in telemetry; `request_id`/`trace_id` correlate a
user report with logs and database rows. Rejected: parity with periodico (which
logs `user_id`) — Palique's privacy policy (logs without personal data, local
erasure audit) wins; it also simplifies the RGPD compliance already documented
in the repo.

### D8 — Alert only on actual generation failures

The weekly schedule defaults to Sunday at 04:00 UTC and remains opt-in.
Missing or stale output is not an alert condition. Keep
`palique_leveltest_generation_last_success_unix_seconds` for informational
dashboard use, and alert on actual failed executions using the durable
`palique_leveltest_generation_last_failure_unix_seconds` timestamp. The
failure timestamp is seeded from persisted failed jobs at telemetry-enabled
startup and refreshed from persisted records every minute, so failures from
the one-shot CLI remain exported after that process exits and across API
restarts. API and CLI use fixed, distinct `service.instance.id` role values
because an isolated Collector probe showed that identical resource identity
overwrites the same Prometheus series. A failure that was not persisted
cannot be reconstructed after a restart. The alert expires 24 hours after the
most recent recorded failure.

## Operational lessons inherited from periodico

1. **Counters do not exist before their first event.** A `*_total` series is
   absent until the first `add()`. Rules with `> 0` thresholds anchor with
   `(sum(...) or vector(0))` — without parentheses, PromQL precedence applies
   `or` after the comparison and the rule fires forever.
2. **`absent()` safety nets.** Domain alerts self-resolve if the emitting
   process dies; an `absent()` rule on the RED series closes that blind spot.
   `absent()` rules use `for: 5m` to avoid firing on every redeploy.
3. **Local `for` values.** `for: 1m` is tuned for local verification; moving to
   5–10m in a real deployment is documented in the rule file header.
4. **Series names verified live.** The `alerts.yml` header lists the real
   series scraped from `http://localhost:9090`; labels are verified and
   corrected against the live endpoint before a rule is trusted (semconv
   rename between SDK versions: `http_status_code` →
   `http_response_status_code`).
5. **Pipeline ≠ instrumentation.** Standing up the containers only provides
   transport/storage/visualization; domain metrics exist only when code emits
   them.
6. **Tempo receiver binds `0.0.0.0:4318`.** Without the explicit bind inside
   the container, the collector gets "connection refused" exporting spans.
7. **Honest documentation.** The two measurable SLOs are internal reference
   targets with their limitations written down (retention, no automated
   tracking); weekly generation publication is not represented by the failure
   alert.

## Module boundaries

`log.ts` knows nothing about OTel; `metrics.ts` knows nothing about the DB or
Fastify; `observability.ts` is the only module that installs global providers
and composes the other two. Emission points (`app.ts`, `main.ts`,
`llm-config.ts`, `level-tests-generation-jobs.ts`) consume only `log` and
`paliqueMetrics`.

## Verification

Unit tests with `InMemoryMetricReader` (no DB); integration tests of `app.ts`
against real PG via `TEST_DATABASE_URL` (pattern `tests/admin-usage.test.ts`);
`make test-api` runs the full suite with no observability container (no-op
gate). Config validity is enforced by `promtool`/`amtool` through
`make obs-check` and the CI step in `checks.yml`; this includes behavioral
`promtool test rules` fixtures, not syntax validation alone.
