# Verification report — observability

Branch: `feature/observability` (from `feature/learning-platform` @ `707e9b4`).
Verified on Linux with Docker Compose (project `api`), Node 22 inside the
images, PostgreSQL 17.

> **Historical verification:** this report records the original implementation
> and its then-current six-rule design. The 2026-10-23 remediation below
> supersedes generation staleness alerting and appends behavioral evidence;
> historical results are not retroactively treated as proof of the revised
> contract.

## 1. Test suites

| Command | Result |
|---|---|
| `cd services/api && npx tsc --noEmit` | clean |
| `cd services/api && npm test` | 179 pass / 0 fail / 437 skipped (skips need `TEST_DATABASE_URL`) |
| `make test-api` (ephemeral stack) | 616 tests / 616 pass / 0 fail / 0 skipped, exit 0 |
| `docker compose -p looro-smoke ... ps` | only `database`, `migrate` (exited), `api`, `tests` (exited) — no telemetry containers |

The smoke stack never starts the observability services and sets no `OTEL_*`
variables, so the API is a no-op exporter there by construction.

Three test-only fixes were needed to reach a green suite (see §5).

## 2. Live stack

`make up` brings up PostgreSQL, migrations, API, Caddy and the telemetry
services (collector, Tempo + its one-shot `tempo-init`, Prometheus,
Alertmanager, webhook sink, Grafana). All telemetry ports bind to `127.0.0.1`
only.

| Check | Result |
|---|---|
| `curl -sk https://localhost/healthz` / `readyz` | 200 |
| `docker compose logs api` | one JSON line per request: `{"event":"http_request","request_id":"…","trace_id":"…","method":"GET","route":"/healthz","status":200,"duration_ms":4}` |
| `curl -s :9090/api/v1/targets` | `job="otel-collector"` → `up` |
| `curl -s :3200/ready` | 200 (after the `tempo-init` fix) |
| `curl -s :3200/api/search?limit=3` | traces with `rootServiceName="palique-api"` |
| `curl -s :9090/api/v1/rules` | 6 rules, all `health: ok` |
| `make health` | `/healthz` + `/readyz` 200 |

### Real labels (verified, not assumed)

```
http_server_request_duration_seconds_count{
  exported_job="palique-api", http_request_method="GET",
  http_response_status_code="200", http_route="/healthz",
  instance="otel-collector:8889", job="otel-collector"}
```

`exported_job="palique-api"` comes from `service.name` (Prometheus renames the
exporter's own `job` because the scrape adds its own). The three
`palique_db_pool_connections{state=idle|in_use|waiting}` series and
`palique_db_pool_max_connections` are present. `alerts.yml` and the dashboard
use exactly these names, so no post-verification correction of label names was
needed.

### Alerts

- Delivered path proven end to end: Prometheus rule → Alertmanager →
  `webhook-logger`. Observed payloads:
  `firing ApiMetricsMissing endsAt: 0001-01-01T00:00:00Z` and, after the
  condition cleared, `resolved ApiMetricsMissing endsAt: 2026-10-23T08:03:24Z`.
- The first firing also exposed a false positive: the original expression
  anchored `absent()` on `http_server_request_duration_seconds_count`, which
  only exists after the first request. A freshly recreated, idle API therefore
  paged with no pipeline problem. Re-anchored to
  `palique_db_pool_connections` (exported every cycle while the process lives)
  and re-validated with `promtool` (6 rules, SUCCESS).
- Outage detection with the new anchor: see §4.

### LLM metrics

A controlled failure (`chatCompletion` against `http://127.0.0.1:1`, inside
the API image with `OTEL_ENABLED=true`) produced:

```
palique_llm_requests_total{provider="glm", status="failed",
                           error_code="llm_outcome_unknown",
                           exported_job="palique-api"} = 1
```

The successful counter (`provider="opencode", status="ok"`) was emitted by the
generation CLI process, which proves the new `initObservability()` wiring in
`src/level-tests-generate.ts` exports through the same pipeline.

Note: `resolveLlmConfig` prefers the active row in `llm_provider_configs` over
`LEVELTEST_LLM_*` env vars, so the plan's "invalid `LEVELTEST_LLM_URL`" recipe
does not force a failure on a database with an active provider. The failure
was injected at the instrumented call directly to keep the verification
deterministic and avoid spending real tokens.

## 3. Privacy regression

```
docker compose logs api | grep -icE 'authorization|bearer|api[_-]?key|stripe|account_id|transcript'
0
```

No request bodies, Authorization headers, tokens, transcripts, Stripe payloads,
keys, IPs or `account_id` appear in the API logs. `src/log.ts` only accepts
scalar fields, so passing an object is a type error, not a runtime leak.

## 4. Outage detection with the new anchor

With the API healthy and idle, `otel-collector` was stopped at `08:11:08Z`.
Prometheus marked the pool gauge stale, `absent()` became true and the rule
transitioned pending → firing after its 5m `for`. The webhook sink recorded:

```
firing   ApiMetricsMissing  endsAt: 0001-01-01T00:00:00Z
resolved ApiMetricsMissing  endsAt: 2026-10-23T08:19:24.899Z
```

The resolution was organic (metrics returned), not a Prometheus restart: the
firing payload carries the new "not exporting metrics" description, so it
belongs to the re-anchored rule. The outage was shorter than the planned ten
minutes because `docker compose up -d api` also starts the collector through
`depends_on`; it still exceeded the staleness window plus `for`.

Boot seeding was verified live too: after recreating the API, the first scrape
returned

```
palique_leveltest_generation_last_success_unix_seconds{exported_job="palique-api"}
  = 1792678571.174  →  2026-10-22T14:16:11.174Z
```

which is exactly `max(COALESCE(finished_at, updated_at))` for `state='finished'`
in the dev database. Without the fix the series would be absent after every
restart.

## 5. Deviations and findings

1. **`tempo-init` service.** Tempo runs as uid 10001 and crash-loops on a
   fresh root-owned named volume (`mkdir /tmp/tempo/blocks: permission
   denied`). The reference project chowned the volume by hand once; a one-shot
   Alpine container in `compose.yaml` makes `make up` reproducible.
2. **CLI did not initialize OTel.** Only `main.ts` called
   `initObservability()`, so `level-tests-generate` recorded metrics into a
   no-op meter. Wired init/shutdown (still disabled unless
   `OTEL_ENABLED=true`).
3. **5xx body contract test.** `tests/hosted-http.test.ts` asserted the exact
   `{error:{code}}` object for a 502. The documented contract now adds
   `error.requestId` to every 5xx; the assertion was updated (4xx stays
   exact). This test only runs with a real database, so `npm test` did not
   catch it — `make test-api` did.
   **Superseded detail:** direct upstream 502 responses now preserve their
   existing JSON body and expose correlation through `X-Request-Id`; they do
   not add `error.requestId` to that body.
4. **`make test-api` could never be green.** The smoke image is a production
   build (`npm ci --omit=dev`) and four operator-CLI tests spawn `tsx` from
   `node_modules/.bin`, which is absent there. The invoker now uses `tsx` when
   present and `node dist/src/<cli>.js` otherwise.
5. **`ApiMetricsMissing` false positive** (see §2, Alerts).
6. **Last-success gauge not seeded at boot.** Design decision D8 and §4.1 of
   the plan require
   `palique_leveltest_generation_last_success_unix_seconds` to be set "at boot
   and on every success". Only the success path was implemented, so after a
   restart the series did not exist and `LevelTestGenerationStale` could never
   fire — exactly the blind spot the gauge was introduced to close. Added
   `initGenerationMetrics(db)` (reads the last `finished_at` at startup, never
   blocks boot) and called it from `main.ts`; three unit tests cover the
   seeded, absent and query-failure cases.
7. **CI step needs compose variables.** `docker compose config -q` interpolates
   `POSTGRES_PASSWORD` and `API_DOMAIN` (both `:?` required) and CI has no
   `.env`; the workflow step sets them inline. Nothing starts in CI.

## 6. Pending (explicitly out of scope)

- External alert delivery (Slack/PagerDuty): a second Alertmanager receiver
  fed from an environment-provided URL.
- SLO tracking automation: the two measurable internal SLOs are documented queries,
  evaluated manually.
- Latency alert (p95), weekly generation publication measurement and end-to-end
  freshness (generated → served → app).
- LLM cost in €: tokens are counted here; money is audited in the ledger and
  `/v1/pricing`, not in this pipeline.
- NDJSON framing for container log drivers, W3C propagator for cross-service
  traces, and `trace_id` present but zero when `OTEL_ENABLED` is off are
  accepted minors recorded in the execution ledger.

## 7. Remediation verification (2026-10-23)

The current contract keeps generation opt-in, defaults the unspecified schedule
to Sunday at 04:00 UTC, and does not alert on missing or stale output. Actual
failed executions emit `palique_leveltest_generation_last_failure_unix_seconds`
and alert for 24 hours; persisted failures seed the gauge on telemetry-enabled
startup. The operational runbook documents that an unpersisted failure cannot
be recovered after a restart. `last_success` remains informational.

`DbPoolSaturated` now requires a complete five-minute window with at least 20
observations at the configured 15-second scrape interval. Behavioral
`promtool test rules` coverage verifies alert firing, resolution, no-fire on
missing/stale generation output, and no-fire for a short startup pool spike.
The original six-rule syntax check above is historical; the current rule set
contains five rules.

An isolated Collector 0.127.0 exporter probe also reproduced the API/CLI
resource-identity collision. Two process-style exporters with the same
`service.name` and metric attributes emitted counter values 1 and 2, but the
Collector exposed only the latter series/value. Repeating with fixed
`service.instance.id=api` and `service.instance.id=leveltest-generate` exposed
two distinct series, with no random per-process label. API now refreshes the
durable latest success/failure timestamps every minute while telemetry is
enabled, so metrics from the one-shot CLI remain represented after process
exit. Unit coverage verifies the fixed role attributes and that disabled
telemetry does not start the database refresh timer.

The final verification commands/results for this remediation are recorded in
the work-unit task document and commit history; this appendix intentionally
does not rewrite the original live-stack evidence above.

### Final remediation checks (2026-10-23)

- Focused API tests: `tsx --test tests/observability.test.ts tests/http.test.ts tests/level-tests-scheduler.test.ts` — 38 passed, 2 PostgreSQL-only skipped.
- API typecheck: `tsc --noEmit` — PASS.
- Observability configuration and behavioral alert tests: `make obs-check` — PASS.
- Isolated API integration stack: `make test-api` — exit 0 (625 tests; disposable stack cleaned up).
- Cross-platform/data contracts: `make check` — PASS (138 Python tests and contract/exporter checks).
- Independent verification: PASS. Scratch probes confirmed distinct API/CLI Prometheus series with stable role identities, 60-second refresh of durable success/failure timestamps, no timer/database work with telemetry disabled, and generation failure alert behavior. Pool short-startup-spike blocker was corrected and separately rechecked.
- Environment limitation: native `gentle-ai` RDD status is unavailable in this workspace; no native review approval is claimed. PostgreSQL-only cases in the local focused test invocation were skipped, while the isolated integration suite passed.
