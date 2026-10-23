# Observability Remediation Implementation Plan

> **For agentic workers:** Execute task-by-task using `superpowers:executing-plans`; every behavior change follows `superpowers:test-driven-development`. Steps use checkbox syntax.
> **Delivery:** Work-unit commits remain on `feature/observability`; no push or pull request is authorized.

**Goal:** Close the privacy, alert-correctness, failure-instrumentation and HTTP trace gaps documented by the approved design without changing the observability stack or enabling generation by default.

**Architecture:** Preserve manual OpenTelemetry instrumentation and the existing Prometheus/Tempo stack. Use safe route templates for HTTP telemetry, retain Sunday opt-in scheduling, alert only on actual failed generation executions (not missing/stale output), and verify alert expressions with simulated Prometheus rules.

**Tech Stack:** Node.js 22, TypeScript, Fastify 5, OpenTelemetry JS 3.x, PostgreSQL 17, Prometheus/promtool 2.54.1, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-10-23-observability-remediation-design.md`; review evidence: `informe-observabilidad-2026-10-23.md`.

## Global Constraints

- Keep `LEVELTEST_GENERATE_ENABLED=false` when unspecified.
- Use ISO weekday `7` (Sunday) as the default scheduler day; keep hour `4` UTC unless an explicit config overrides it.
- The earlier four-hour deadline applied to a proposed missing-generation alert and is withdrawn; no alert is emitted for a schedule with no observed execution.
- Do not add a PostgreSQL migration or a durable activation marker.
- Do not log request bodies, query strings, concrete path segments, credentials, IPs, account IDs, provider messages, or arbitrary exception objects.
- Preserve public HTTP status/body compatibility; add correlation only through a backward-compatible surface.
- Keep explicit user-configured day/hour/languages/levels authoritative.
- Preserve `busy`/`exists` no-op semantics, LLM no-retry behavior, and honest error/tokens reporting.
- Do not enable W3C distributed propagation or create an API/CLI identity change without a reproduced Collector collision.
- Do not inspect or change remote deployment configuration, contact real LLM providers, or stop/start user services.
- Tests run with the existing Node test runner (`tsx --test`); do not introduce a test framework or generated artifacts.

## Review Focus

1. A request with PII in a path parameter or unmatched path must not export it to stdout or a span attribute.
2. A log event must not permit caller-provided fields to overwrite reserved fields or add forbidden keys.
3. First generation failure, failure before the first Prometheus scrape, disabled generation, and an API restart must remain distinguishable; an absent scheduled run must not fire a generation alert.
4. A real pool saturation interval and an isolated short spike must follow the documented pending/firing policy.
5. No-5xx traffic, no traffic, direct 502 responses, telemetry-disabled startup, and failed DB seeding must remain distinguishable and compatible.

## File Map

- `services/api/src/log.ts`: logger field contracts and runtime reserved-key safety.
- `services/api/src/app.ts`: request logs, request spans, status/error/context and direct-502 correlation.
- `services/api/src/metrics.ts`, `observability.ts`, `main.ts`: domain metrics and telemetry lifecycle.
- `services/api/src/level-tests-scheduler.ts`: Sunday UTC default; schedule does not imply a missing-run alert.
- `services/api/src/level-tests-generation-jobs.ts`, `llm-config.ts`: result and duration measurements on failures.
- `services/api/telemetry/alerts.yml`, `alerts.test.yml`, `grafana/dashboards/palique-overview.json`: operational conditions and their rule tests.
- `services/api/tests/observability.test.ts`, `level-tests-scheduler.test.ts`, HTTP test files: regression evidence.
- `Makefile`, `.github/workflows/checks.yml`: run behavioral Prometheus rule tests in the existing validation flow.
- Existing observability plan, OpenSpec, operations docs and `verify-report.md`: keep requirements and observed evidence aligned.

## Tasks

### Task 1: Make HTTP observability safe and semantically correct

**Files:** `services/api/src/log.ts`, `services/api/src/app.ts`, `services/api/tests/observability.test.ts`, relevant HTTP tests, and the privacy/span sections of the original plan and OpenSpec.

**Interfaces:** Preserve public `log.info/warn/error` use at current call sites while replacing unbounded fields with event-safe fields. Request log route values remain a matched route template or `unmatched`. HTTP spans remain attached to the request and use the installed OTel 3.x context APIs.

- [x] Add a focused test with unique PII markers in a matched parameter, unmatched path, query, header, body and error; assert no marker occurs in captured logs or completed span attributes/events.
- [x] Run `cd services/api && ./node_modules/.bin/tsx --test tests/observability.test.ts`; observe failure specifically for the concrete `url.path` or unmatched route leak.
- [x] Add tests proving logger inputs cannot overwrite `timestamp`, `level`, `event`, and cannot emit forbidden field names; assert valid events stay JSON scalar records.
- [x] Add tests that captured request spans have kind `SERVER`, status `ERROR` on 5xx and an active request context is correct across two concurrent requests.
- [x] Add a direct provider-test 502 regression asserting existing JSON body/status are unchanged while a documented server-generated correlation header and safe failure event are present.
- [x] Implement only the field filtering, safe route attributes, active request context, span kind/status and compatible 502 correlation required by these tests. Do not attach a raw path template to `url.path`.
- [x] Rerun `cd services/api && ./node_modules/.bin/tsx --test tests/observability.test.ts tests/http.test.ts`; expect zero failures and no PII markers in any captured channel.
- [x] Run `cd services/api && ./node_modules/.bin/tsc --noEmit`; expect exit 0.
- [x] Commit this unit as `fix(observability): protect request telemetry and classify server spans` (`0def7bd`).

### Task 2: Set the Sunday generation default without missed-run alerting

**Files:** `services/api/src/level-tests-scheduler.ts`, Compose defaults, `tests/level-tests-scheduler.test.ts`, scheduler/operations docs.

**Interfaces:** Scheduler continues to accept ISO `day`, UTC `hour`, languages and levels. Its defaults become `day=7`, `hour=4`, and `enabled=false`. Missing or stale scheduled output is not an alert condition; only a real failed generation execution is alertable (Task 3/4).

- [x] Add deterministic tests for default `{enabled:false, day:7, hour:4}`, an explicit override, and Sunday UTC calculation.
- [x] Run `cd services/api && ./node_modules/.bin/tsx --test tests/level-tests-scheduler.test.ts`; observed the current Monday default fail the new Sunday expectation.
- [x] Change only the unspecified weekday default to Sunday; keep enabled opt-in, keep hour 04:00 UTC, and preserve explicit schedule overrides.
- [x] Update scheduler docs and compose defaults; do not add expected/missed-week telemetry.
- [x] Rerun the focused test file and `cd services/api && ./node_modules/.bin/tsc --noEmit`; all passed.
- [x] Commit this unit as `fix(level-tests): default weekly generation to Sunday` (`d356082`).

### Task 3: Measure rejected jobs, LLM latency and disabled-telemetry startup correctly

**Files:** `services/api/src/level-tests-generation-jobs.ts`, `services/api/src/llm-config.ts`, `services/api/src/observability.ts`, `services/api/src/main.ts`, and focused tests/docs for their metric semantics.

**Interfaces:** Preserve existing return/error shapes, `busy`/`exists` behavior, bounded safe error-code labels, token accounting and no automatic LLM retries. `initGenerationMetrics` must be a true no-op when OTel is disabled and must not block API listen when enabled.

- [x] Add tests that connection-acquisition, query and job-finalization rejections preserve the original rejection and record one failed execution plus one duration without setting last-success.
- [x] Add tests proving `busy` and `exists` still record no execution success/failure.
- [x] Add LLM tests for success, non-2xx, fetch rejection, invalid JSON, empty response and cancellation. Assert one duration and correct result on every path; preserve token counts only when known and preserve zero retries.
- [x] Add a telemetry-disabled startup test asserting zero seed queries, and active telemetry tests for query failure/recovery plus a bounded startup path.
- [x] Run focused tests; observed RED for missing failure duration/counter, missing LLM error duration, invented zero token counters, and disabled-mode DB query.
- [x] Instrument the full job operation boundary (including thrown failures) and LLM duration in a single `finally` path; skip all telemetry-only seed work when disabled and keep enabled seed work bounded and recoverable.
- [x] Rerun focused tests and `cd services/api && ./node_modules/.bin/tsc --noEmit`; all passed.
- [x] Commit this unit as `fix(observability): record failure-path metrics without blocking startup` (`a0b6b84`).

### Task 4: Correct alert/dashboard math and test rule behavior in CI

**Files:** `services/api/telemetry/alerts.yml`, new `services/api/telemetry/alerts.test.yml`, `services/api/telemetry/grafana/dashboards/palique-overview.json`, Makefile validation target(s), `.github/workflows/checks.yml`.

**Interfaces:** Keep the existing non-generation alert names, severities and service selectors stable. Remove `LevelTestGenerationStale`, retain `LevelTestGenerationFailed` for observed execution failures. Dashboard JSON remains provisioned and valid. Rule tests use the exact bundled Prometheus/promtool version 2.54.1.

- [x] Add isolated `promtool test rules` cases for every retained alert firing, not firing, and resolving where applicable; include the first failed event, stale/missing generation not alerting, saturation recovery, 200-only traffic, mixed status traffic, zero traffic and metric absence.
- [x] Run `promtool test rules services/api/telemetry/alerts.test.yml`; observed the original pool-label mismatch and first-failure signal counterexample. Added 30-day no-5xx SLO expression coverage.
- [x] Change pool utilization to align capacity and in-use labels for the one-instance deployment and express sustained saturation with a five-minute minimum window plus `for: 1m`.
- [x] Calculate no-5xx ratio as zero only when a positive total request rate exists; preserve no-traffic and no-metrics cases as distinct.
- [x] Remove `LevelTestGenerationStale`; retain `LevelTestGenerationFailed` using the durable last-failure metric, including first-event semantics. Retain independent LLM spike semantics.
- [x] Wire `promtool test rules` into `make obs-check` and the existing CI telemetry job; retain `promtool check rules` and `amtool check-config`.
- [x] Run rule tests, `make obs-check`, and dashboard JSON parsing; all cases pass and five rule syntax checks pass.
- [x] Commit this unit as `fix(observability): test alert behavior and correct PromQL math`.

### Task 5: Reconcile documentation and verify the complete pipeline

**Files:** `docs/superpowers/plans/2026-10-22-observability.md`, `openspec/changes/observability/`, `services/api/docs/observability.md`, `openspec/changes/observability/verify-report.md`, `services/api/src/{observability.ts,main.ts,level-tests-generate.ts}`, and telemetry tests/fixtures. The service identity and refresh code are authorized only because the isolated exporter probe reproduced a collision and one-shot CLI expiry.

**Interfaces:** Documentation must describe implemented behavior and observed proof, not planned or assumed behavior. API and CLI share `service.name=palique-api` but need distinct fixed `service.instance.id` values; the API refreshes durable generation timestamps every minute while OTel is enabled.

- [x] Safe isolated Collector exporter probe reproduced same-identity series overwrite; distinct fixed role IDs produced two separate series without per-run cardinality.
- [x] Added failing resource-identity and timestamp-refresh tests before implementation; role IDs and periodic durable refresh now preserve CLI failure visibility.
- [x] Reconciled plan, OpenSpec requirements/design/tasks, operational runbook and historical report; recorded actual-only failure alerting and functional rule-test commands.
- [ ] Finish full focused TypeScript, typecheck, `make obs-check`, isolated `make test-api`, `make check`, and independent verification after the collision correction.
- [ ] Commit this unit as `docs(observability): align contracts and verification evidence`.

## Interfaces and Pre-flight

Task 1 owns `app.ts` request-log/span changes; no other task modifies that behavior except compatible response correlation in the same unit. Task 2 owns the Sunday scheduler default and no missing-run alert is derived from it. Task 3 owns execution-level failure/duration instruments; Task 4 consumes their stable names and labels and removes stale-generation alerting. Task 5 only documents these settled interfaces.

No source generator or schema migration is needed. The existing branch is already `feature/observability`; do not create a worktree against another branch or alter remote state.

## Verification

- Focused TypeScript tests: `cd services/api && ./node_modules/.bin/tsx --test <relevant test files>`.
- Static typecheck: `cd services/api && ./node_modules/.bin/tsc --noEmit`.
- Prometheus behavior: `promtool test rules services/api/telemetry/alerts.test.yml` with project promtool 2.54.1.
- Ops config: `make obs-check`.
- API integration: `make test-api` (isolated smoke stack only).
- Cross-repository contracts: `make check`.
- No tests may issue a real LLM request, read a deployment credential, or stop a user service.

## Known Environmental Limitations

- `gentle-ai review mode status` cannot run in this workspace (`gentle-ai: command not found`, exit 127). Treat RDD state as unknown and do not claim review approval; retain ordinary tests plus the required independent high-risk verification.
- CodeGraph CLI and MCP are unavailable; the design and audit used the already-reviewed source map.
- The Linux workspace cannot run iOS/Xcode checks.

## Log

- L1 — Original authorized request: “lanza sdd usando superpowers y resuelve todos los problemas que has encontrado y definido en el documento que has generado”.
- L2 — User requirement: “la generación de examenes debería ocurrir los domingos por tanto siempre podemos saber si se han generado o no cada lunes o cada domingo unos horas despues de que corra el worker que los genera, te vale esta respuesta?”
- L3 — User setting: “mantenemos el interruptor optativo”.
- L4 — The earlier design included a four-hour grace for a missing-generation alert; the later user requirement supersedes that alert behavior.
- L5 — User change: “de momento creo que lo mejor es no tener alertas para la generación de examenes, en su defecto lo que sí quiero es que si falla la generación generar métricas y estas generen alertas. los ves acorde y coherente?” Confirmed and authorized for documentation and implementation: “me parece perfecto, lo ideal sería que actualices la documentación con esta nueva requisito y lo implementes”.
- L5 — Baseline is commit `a5b103680feac26b2ac4242d081528e57a7e8a3d` on `feature/observability`; reviewed origin range was `707e9b4..a5b1036`.
- L6 — Fresh builder check of the approved design returned `nothing important left`.
