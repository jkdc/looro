# Observabilidad (métricas, errores, trazas y alertas) — Implementation Plan

> **Superseded for generation alerting (2026-10-23):** the approved remediation
> in `docs/superpowers/specs/2026-10-23-observability-remediation-design.md`
> replaces the missing/stale-generation alert proposed below. The scheduler
> remains opt-in, defaults to Sunday 04:00 UTC, and alerts only on actual
> failed executions. `last_success` is informational; no scheduled output or
> freshness alone is an alert condition. Historical plan evidence is retained.
> API and the one-shot generation CLI retain the same service name but use
> fixed `service.instance.id` roles (`api` and `leveltest-generate`); API
> refreshes durable generation timestamps every minute while telemetry is on.

> **For agentic workers:** REQUIRED SUB-SKILL: Use zuperpowers:subagent-driven-development (recommended) or zuperpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dotar a la API de Palique del mismo stack de observabilidad que usa el proyecto periodico (Contraste): OpenTelemetry → OTel Collector → Prometheus (métricas y alertas) + Tempo (trazas) + Grafana (dashboards provisionados) + Alertmanager con sumidero local, con instrumentación de dominio (RED HTTP, pool de BD, generación semanal de exámenes, llamadas LLM) y logs estructurados JSON, sin implementar nada fuera de este plan.

**Architecture:** Todo por Docker en `services/api/compose.yaml` (seis servicios nuevos, puertos solo en loopback, misma topología probada en `periodico/infra/compose/docker-compose.yml`). En código, tres módulos nuevos (`log.ts`, `metrics.ts`, `observability.ts`) con activación por `OTEL_ENABLED` (no-op por defecto: tests y stack de humo no cambian). Hooks de Fastify existentes se extienden para emitir RED + logs por request; el pipeline de generación y `chatCompletion` se instrumentan en sus puntos únicos de paso. Cero migraciones SQL.

**Tech Stack:** Node 22 + TypeScript + Fastify 5; `@opentelemetry/*` (API 1.10.0, sdk-trace-node 2.12.0, sdk-metrics 3.0.0, exporters OTLP 0.300.0); imágenes Docker: `otel/opentelemetry-collector-contrib:0.127.0`, `prom/prometheus:v2.54.1`, `prom/alertmanager:v0.27.0`, `grafana/tempo:2.7.1`, `grafana/grafana:10.4.8`, `python:3.12-alpine`.

**Spec:** `openspec/changes/observability/` (se escribe en Task 1; las specs SHALL son la autoridad de los requisitos). Análisis comparativo de origen: sección 2 de este documento.

## Global Constraints

- Código y comentarios en inglés; documentación de producto y de ops en español.
- **Privacidad (regla existente del repo, no negociable):** nunca se loguean request bodies, cabeceras `Authorization`, tokens, transcripts, payloads de Stripe, ni claves. El logger nuevo solo acepta campos escalares en allow-list por construcción. Sin IPs ni `account_id` en logs/métricas (diferencia deliberada con periodico, que sí loguea `user_id`).
- **No nuevas migraciones:** la observabilidad no añade tablas (Prometheus/Tempo persisten en volúmenes Docker).
- `OTEL_ENABLED` distinto de `"true"` ⇒ todo no-op: `make test-api`, `make smoke-up` y los tests unitarios corren sin ningún contenedor de observabilidad. Ningún servicio de producto puede depender (hard-depend) de que el collector esté sano.
- Nombres de métricas de dominio con prefijo `palique_`; service name `palique-api`; labels de baja cardinalidad (route template, nunca URL cruda con IDs, nunca identificadores de usuario).
- Dinero en BigInt nanoUSD (regla existente): las métricas de tokens LLM son contadores unitarios, el coste en € queda fuera de alcance.
- Puertos de observabilidad SIEMPRE publicados en `127.0.0.1` (nunca `0.0.0.0`): en el host de producción solo Caddy expone 80/443; el acceso a Prometheus/Grafana es por túnel SSH.
- Los ficheros `*Data.kt`/`*Data.swift` no se tocan; `apps/` (Android/iOS) fuera de alcance (la telemetría de producto del cliente ya existe: `usage_events`).
- Test harness: tests unitarios con `InMemoryMetricReader` (sin BD); tests de integración de `app.ts` con PG real vía `TEST_DATABASE_URL` (patrón `tests/admin-usage.test.ts`); `make test-api` valida la suite completa.
- Al terminar cada tarea: `cd services/api && npx tsc --noEmit` limpio.

---

## 2. Análisis comparativo: periodico (referencia) → Palique (hoy)

### 2.1 Qué tiene el proyecto periodico (inventario verificado)

Ubicación de referencia: `/home/joaquin/Proyectos/periodico` (servicio `contraste-news`, Go).

| Pieza | Fichero | Contenido |
|---|---|---|
| Contenedores | `infra/compose/docker-compose.yml` | `otel-collector`, `tempo`, `prometheus`, `alertmanager`, `webhook-logger`, `grafana` junto a los de producto; overlay `docker-compose.prod.yml` fuerza `restart: always`; todos los puertos en `127.0.0.1` |
| Collector | `infra/telemetry/otel-collector-config.yaml` | OTLP HTTP `:4318` (receiver) → `otlphttp/tempo` (traces) + `prometheus` `:8889` (metrics), processor `batch` |
| Prometheus | `infra/telemetry/prometheus.yml` | scrape 15s del collector `:8889`, `rule_files: alerts.yml`, alerting → `alertmanager:9093` |
| Alertas | `infra/telemetry/alerts.yml` | 7 reglas con severidad, `for` ajustado a local (1m; 5m para `absent()`), runbook en cada anotación |
| Alertmanager | `infra/telemetry/alertmanager.yml` | route único → `webhook-logger:5001`; decisión documentada: no interpola env, el reenvío externo (Slack/PagerDuty) sería un segundo receiver vía `ALERTMANAGER_WEBHOOK_URL` |
| Sumidero | servicio `webhook-logger` | `python:3.12-alpine` con un `HTTPServer` de 15 líneas que registra cada payload de alerta en stdout: destino siempre verificable en local |
| Tempo | `infra/telemetry/tempo.yaml` | backend local, OTLP HTTP con `endpoint: 0.0.0.0:4318` (sin él, «connection refused» dentro del contenedor — lección verificada) |
| Grafana | `infra/telemetry/grafana/` | datasources provisionados (Tempo default + Prometheus `uid: prometheus`), provider de dashboards por fichero, dashboard `pipeline-overview.json` |
| Código runtime | `apps/api/internal/platform/observability/otel.go` | `Init()` con `sync.Once` + guard `OTEL_ENABLED != "true"` ⇒ no-op; resource `service.name`; TracerProvider + MeterProvider (`PeriodicReader`); shutdown con flush de métricas |
| Métricas de dominio | `apps/worker/internal/observability/metrics.go` | fábrica `NewDomainMetrics(meter)` sobre el meter global: `contraste_queue_depth` (gauge), `contraste_job_failures_total`, `contraste_llm_budget_rejections_total` (counters) |
| RED HTTP | instrumentación estándar `otelhttp` | histograma `http_server_duration_milliseconds_*` con labels de método/status/ruta |
| Contrato docs | `docs/07-ops/02-observability.md` | logs JSON con campos comunes mínimos; política `requestId` en errores; contrato de métricas por superficie; alertas; 4 SLOs «honestos» con queries; presupuesto IA; validación en vivo |
| Skill/review | `skills/observability-otel-api-worker/SKILL.md` | checklist: correlación `request_id`/`job_id`, spans en puntos críticos, métricas mínimas, docs sincronizadas |

Lecciones operativas ya verificadas en periodico que este plan hereda:

1. **OTel no exporta counters sin eventos**: una serie `*_total` no existe hasta el primer evento. Las reglas de alerta se escriben para que «serie ausente = sano», anclando con `(sum(...) or vector(0))` cuando el umbral es `> 0` (ojo a la precedencia de PromQL: sin paréntesis, `or` se evalúa después de la comparación y la regla dispara siempre).
2. **Redes de seguridad `absent()`**: las alertas de dominio se auto-resuelven si el proceso que las emite muere; `absent()` sobre la serie RED cierra ese punto ciego. `for: 5m` en las `absent()` para no disparar en cada redepliegue.
3. **`for` afinado a verificación local (1m)**, a revisar hacia 5–10m en despliegue real (documentado en la cabecera del fichero de reglas).
4. **Nombres de series verificados en vivo**: la cabecera de `alerts.yml` lista las series reales de `http://localhost:9090`; el plan incluye un paso de verificación y corrección de labels antes de dar por buena una regla (las convenciones de semconv cambian entre versiones del SDK: `http_status_code` antiguo vs `http_response_status_code` nuevo).
5. **Separación pipeline ≠ instrumentación**: montar los contenedores solo activa transporte/almacenamiento/visualización; las métricas de dominio no existen hasta que el código las emite.
6. **Tempo necesita `endpoint: 0.0.0.0:4318`** explícito en su receiver o el collector recibe connection refused.
7. **Documentación honesta**: los SLOs se marcan como objetivos internos de referencia, con sus limitaciones escritas (retención, ausencia de tracking automático).

### 2.2 Qué tiene Palique hoy (gap)

| Superficie | periodico | Palique hoy | Gap |
|---|---|---|---|
| Runtime métricas | OTel SDK + collector + Prometheus + Grafana | nada (`package.json` sin deps de telemetría) | total |
| Trazas | Tempo vía OTLP | nada | total |
| Logs | `slog` JSON con campos estables (`service`, `request_id`, `trace_id`, `job_id`…) | `logger: false` en Fastify (deliberado: sin logging de requests); `console.warn(JSON.stringify({event}))` puntual en 4 sitios; `console.error` en prune/retry | logger estructurado + campos estables |
| Correlación | `request_id` obligatorio + `trace_id` + `error.requestId` en toda respuesta de error | `genReqId: randomUUID()` ya existe; `X-App-Error-Reference` (12 hex) solo en operaciones de voz hospedada | propagar request_id a logs y al body de errores 5xx; `trace_id` nuevo |
| Alertas | 7 reglas + Alertmanager + sumidero | nada | 6 reglas propias + mismo stack |
| Métricas de dominio | `contraste_*` (cola, fallos de job, presupuesto IA) | nada en tiempo real | `palique_*` (pool BD, generación semanal, LLM, rechazos del limitador) |
| Analítica de negocio | panel `/pipeline` | **ya existe y NO se toca**: `admin-metrics.ts` (timeseries SQL diarias: overview/growth/voice/economy/usage/tokens) y `usage_events` del cliente | ninguna: analítica de producto ≠ observabilidad técnica; el plan no la duplica |
| Jobs asíncronos | worker Redis con cola | jobs in-proceso: scheduler semanal de level-tests, runner de minute-commerce, prunes cada 15 min, retry de guest links cada 60 s | instrumentar el scheduler de generación (el único con riesgo real de fallo silencioso) |
| CI/validación | `promtool`/`amtool` documentados | nada | `make obs-check` + paso en `checks.yml` |

Conclusión del análisis: se replica el **patrón completo** de periodico (mismas imágenes, misma topología de red, mismas decisiones de configuración) sustituyendo la instrumentación Go por su equivalente Node/TypeScript, respetando dos diferencias estructurales de Palique: (a) no hay worker separado — los jobs viven en el proceso de la API, así que las alertas de «proceso caído» recaen en una sola serie RED; (b) la política de privacidad es más estricta (sin `user_id`/IP en telemetría), lo que simplifica el cumplimiento RGPD ya documentado en el repo.

### 2.3 Decisiones de diseño

| # | Decisión | Alternativa descartada | Motivo |
|---|---|---|---|
| D1 | Instrumentación manual con hooks de Fastify (RED + spans por request) | `@opentelemetry/auto-instrumentations-node` o `@fastify/otel` | auto-instrumentations arrastra ~50 dependencias transversales y parchea `http` global: choca con el estilo mínimo del repo (6 deps) y con el control fino que exige la política de privacidad. Con 2 hooks el código es auditable de un vistazo. |
| D2 | Logger JSON propio (~30 líneas, campos en allow-list) | `pino` | El repo ya restringe Fastify a `logger: false` por privacidad; un logger con API cerrada (`LogFields = Record<string, string\|number\|boolean\|null>`) imposibilita por tipado filtrar objetos arbitrarios (error, body) a stdout. |
| D3 | Métricas de dominio como fábrica reiniciable (`initDomainMetrics(meter)`) | instrumentos a nivel de módulo | El pitfall clásico de OTel JS: crear instrumentos al importar los congela sobre el meter no-op si el provider global se instala después. La fábrica hace el orden de inicialización irrelevante y los tests deterministas. |
| D4 | Alertas sobre series RED del collector (`http_server_request_duration_seconds_*`) | exponer `/metrics` Prometheus directamente desde Node | Mantener la vía OTLP→collector unifica transporte de métricas/trazas y replica exactamente el patrón probado en periodico. |
| D5 | Prometheus con retención `90d` + cap `2GB` | retención por defecto (15d) | Los SLOs mensuales necesitan ventana ≥ 30d; periodico lo dejó documentado como carencia, aquí se corrige de partida. |
| D6 | Contador de tokens LLM (no coste) | coste estimado en € por modelo | Los precios por token viven en `/v1/pricing` (nanoUSD) y ya se contabilizan en el ledger; duplicar tarifa en PromQL crea una segunda fuente de verdad. El contador de tokens basta para alertas de anomalías. |
| D7 | Sin `user_id`/IP en labels ni logs | paridad con periodico (`user_id` en logs) | Política de privacidad de Palique (logs sin datos personales, auditoría de borrado local). `request_id`/`trace_id` bastan para correlacionar con BD cuando un usuario reporta un incidente. |
| D8 | `generation_last_success` como gauge inicializado al arrancar | solo `absent()` como en periodico | **Histórico, superseded 2026-10-23:** el gauge informa frescura en el dashboard; no alerta por una salida ausente. Los fallos reales usan `generation_last_failure`. |

---

## 3. Estructura de ficheros

```
services/api/
  compose.yaml                      # MODIFICAR: 6 servicios de observabilidad + env OTEL_* de api + volúmenes
  tests/compose.smoke.yaml          # MODIFICAR: comentario explícito OTEL disabled
  package.json                      # MODIFICAR: 7 dependencias @opentelemetry/*
  telemetry/                        # CREAR (análogo a infra/telemetry de periodico)
    otel-collector-config.yaml
    prometheus.yml
    alerts.yml
    alertmanager.yml
    tempo.yaml
    grafana/
      provisioning/datasources/datasource.yaml
      provisioning/dashboards/dashboards.yaml
      dashboards/palique-overview.json
  src/
    log.ts                          # CREAR: logger JSON con campos allow-list
    metrics.ts                      # CREAR: registro de instrumentos palique_*
    observability.ts                # CREAR: init/shutdown OTel + gauge del pool de BD
    app.ts                          # MODIFICAR: hooks onRequest/onResponse, setErrorHandler, contadores del limitador
    main.ts                         # MODIFICAR: init/shutdown, logs estructurados
    llm-config.ts                   # MODIFICAR: instrumentar chatCompletion
    level-tests-generation-jobs.ts  # MODIFICAR: wrapper con métricas de job
  tests/observability.test.ts       # CREAR
  docs/observability.md             # CREAR (español, contrato completo)
Makefile                            # MODIFICAR: target obs-check
.github/workflows/checks.yml        # MODIFICAR: paso de validación de configs
openspec/changes/observability/     # CREAR (Task 1)
AGENTS.md                           # MODIFICAR (Task 11)
```

Responsabilidades: `log.ts` no conoce OTel; `metrics.ts` no conoce la BD ni Fastify; `observability.ts` es el único que instala providers globales y compone los dos anteriores. Los puntos de emisión (app.ts, main.ts, llm-config.ts, level-tests-generation-jobs.ts) solo consumen `log` y `paliqueMetrics`.

---

## 4. Contrato de observabilidad (fuente de verdad para Tasks 5–11)

### 4.1 Métricas (instrumentos OTel → series Prometheus vía collector `:8889`)

| Instrumento (nombre OTel) | Tipo | Atributos (labels) | Serie Prometheus |
|---|---|---|---|
| `http.server.request.duration` (unit `s`) | Histogram, buckets explícitos `[0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10]` | `http.request.method`, `http.route` (template o `unmatched`), `http.response.status_code` | `http_server_request_duration_seconds_{count,sum,bucket}` |
| `palique.requests.rejected` (unit `1`) | Counter | `reason` ∈ `rate_limit` \| `trusted_proxy` | `palique_requests_rejected_total` |
| `palique.db.pool.connections` (unit `1`) | ObservableGauge | `state` ∈ `idle` \| `in_use` \| `waiting` | `palique_db_pool_connections` |
| `palique.db.pool.max_connections` (unit `1`) | Gauge (set 1 vez) | — | `palique_db_pool_max_connections` |
| `palique.leveltest.generation.jobs` (unit `1`) | Counter | `language` (en/es), `level` (A1/A2/B1), `status` ∈ `finished` \| `failed` \| `unknown` | `palique_leveltest_generation_jobs_total` |
| `palique.leveltest.generation.job.duration` (unit `s`) | Histogram, buckets `[1, 5, 15, 30, 60, 120, 300, 600, 900]` | `language`, `level` | `palique_leveltest_generation_job_duration_seconds_*` |
| `palique.leveltest.generation.last_success_unix_seconds` (unit `s`) | Gauge (set al arrancar y en cada éxito) | — | `palique_leveltest_generation_last_success_unix_seconds` |
| `palique.leveltest.generation.last_failure_unix_seconds` (unit `s`) | Gauge (último fallo observado; se siembra desde jobs persistidos con OTel habilitado) | — | `palique_leveltest_generation_last_failure_unix_seconds` |
| `palique.llm.requests` (unit `1`) | Counter | `provider` (openai/glm/opencode), `status` ∈ `ok` \| `failed`, `error_code` (solo en failed) | `palique_llm_requests_total` |
| `palique.llm.request.duration` (unit `s`) | Histogram, buckets `[0.25, 0.5, 1, 2, 5, 10, 30, 60, 120, 300, 600]` | `provider` | `palique_llm_request_duration_seconds_*` |
| `palique.llm.tokens` (unit `1`) | Counter | `provider`, `direction` ∈ `input` \| `output` | `palique_llm_tokens_total` |

Todos los counters llevan `status`/`reason`/`state`/`direction` de cardinalidad cerrada (≤ 3 valores salvo `error_code`, acotado por el enum de códigos `LlmTransportError`). Recordar la lección 1 de §2.1: una serie `_total` no existe hasta el primer `add()`.

### 4.2 Logs (JSON en stdout, un objeto por línea)

Campos comunes: `timestamp` (ISO 8601 UTC), `level` (`info`|`warn`|`error`), `event`, y cuando existan `request_id`, `trace_id`, `error_code`. Eventos definidos:

| Evento | Nivel | Campos | Emisor |
|---|---|---|---|
| `http_request` | info | `request_id`, `method`, `route`, `status`, `duration_ms`, `trace_id`? | hook onResponse |
| `request_failed` | error | `request_id`, `method`, `route`, `status`, `error_code` | setErrorHandler (solo status ≥ 500) |
| `startup_diagnostic` | warn | `request_id`, `reference`, `operation`, `status`, `reason` | setErrorHandler (patrón existente de voz hospedada) |
| `level_test_generation_job` | warn | `language`, `level`, `week`, `status`, `error_code` | main.ts (sustituye al `console.warn` actual, mismos datos) |
| `minute_commerce_failure` | error | `code` | main.ts (sustituye al `console.error` actual) |
| `live_startup_failed` | warn | campos del diagnostic existente | main.ts (sustituye al `console.warn` actual) |
| `retention_cleanup_failed` / `guest_allowance_retry_failed` | error | — | main.ts (sustituye a los `console.error` de los intervalos) |

Prohibido por tipo: cualquier valor que no sea `string | number | boolean | null`. Sin stack traces: los 5xx llevan `error_code` (`service_unavailable` genérico para no-ServiceError) y `request_id`.

### 4.3 Alertas (`telemetry/alerts.yml`, evaluadas cada 30s)

Todas filtran `job="otel-collector", exported_job="palique-api"` y llevan `runbook` apuntando a `docs/observability.md#runbooks`. Ventanas `for` locales: 1m continuo / 5m para `absent()` (lecciones 2 y 3 de §2.1).

| Alerta | Expr (resumen) | Umbral | Severidad |
|---|---|---|---|
| `ApiHttp5xxRatioHigh` | ratio 5xx sobre count RED en 5m | > 5 % | critical |
| `ApiMetricsMissing` | `absent()` de la serie RED | > 5m | critical |
| `LevelTestGenerationFailed` | `time() - max(palique_leveltest_generation_last_failure_unix_seconds) < 86400` | fallo real en las últimas 24h | warning |
| ~~`LevelTestGenerationStale`~~ | Eliminada en la remediación 2026-10-23 | salida programada ausente/antigua no alerta | — |
| `LlmFailuresSpike` | `(sum(increase(palique_llm_requests_total{status="failed"}[1h])) or vector(0)) > 5` | > 5 fallos/h | warning |
| `DbPoolSaturated` | utilización mínima del pool durante 5m completos, al menos 20 muestras de `in_use`, agrupado por `job, exported_job` | > 90 % del pool durante 1m | warning |

### 4.4 SLOs internos (honestos, como periodico)

| # | Objetivo | Query de cumplimiento | Limitación documentada |
|---|---|---|---|
| 1 | ≥ 99,5 % de peticiones sin 5xx al mes (presupuesto ≈ 3,6 h) | `1 - (sum(rate(http_server_request_duration_seconds_count{...,http_response_status_code=~"5.."}[30d])) or vector(0)) / sum(rate(http_server_request_duration_seconds_count{...}[30d]))` | evaluación manual; la retención 90d de D5 la hace posible; requiere tráfico en la ventana |
| 2 | p95 de GET < 500 ms | `histogram_quantile(0.95, sum by (le) (rate(http_server_request_duration_seconds_bucket{...,http_request_method="GET"}[5m])))` | sin alerta de latencia en esta fase |
| 3 | semana de exámenes generada y publicada a tiempo | no medida por estas alertas | comprobar publicación aparte; ausencia de fallos no demuestra generación correcta |

---

### Task 1: Documentación SDD del change (openspec)

**Files:**
- Create: `openspec/changes/observability/proposal.md`
- Create: `openspec/changes/observability/design.md`
- Create: `openspec/changes/observability/tasks.md`
- Create: `openspec/changes/observability/specs/observability-pipeline/spec.md`

**Interfaces:**
- Produces: las specs SHALL de las que argumentan las Tasks 2–12 (pipeline de telemetría, contrato de métricas/logs, alertas, privacidad).

- [ ] **Step 1: Escribir `proposal.md`** siguiendo el formato de `openspec/changes/t11-admin-operations/proposal.md` (Intent / Problem / Approach / Out of scope, en inglés). Approach = resumen de las secciones 2.3, 3 y 4 de este plan. Out of scope: apps nativas, coste LLM en €, analítica de producto (admin-metrics), alertas externas (Slack/PagerDuty, evolución vía `ALERTMANAGER_WEBHOOK_URL`), SLO tracking automático.
- [ ] **Step 2: Escribir `design.md`** — decisiones D1–D8 de §2.3 con sus alternativas descartadas, y las lecciones operativas §2.1 (counters sin eventos, `absent()`, `for` locales, tempo `0.0.0.0`).
- [ ] **Step 3: Escribir `tasks.md`** — checklist espejo de las Tasks 2–12 de este plan.
- [ ] **Step 4: Escribir `specs/observability-pipeline/spec.md`** con bloques `Requirement:`/`Scenario:` SHALL/WHEN-THEN cubriendo: (R1) no-op con `OTEL_ENABLED != "true"`; (R2) RED HTTP por request con route template; (R3) respuesta 5xx incluye `requestId` y nunca internals; (R4) logs JSON con campos allow-list y sin secretos; (R5) contadores de dominio con los nombres de §4.1; (R6) alertas de §4.3 cargadas en Prometheus; (R7) puertos de observabilidad solo en loopback.
- [ ] **Step 5: Verificar estructura** — `ls openspec/changes/observability/` muestra los 4 ficheros; la spec tiene ≥ 5 Requirement.
- [ ] **Step 6: Commit** — `docs(observability): openspec change spec`

### Task 2: Logger estructurado `src/log.ts`

**Files:**
- Create: `services/api/src/log.ts`
- Test: `services/api/tests/observability.test.ts` (primera sección)

**Interfaces:**
- Produces: `export type LogFields = Record<string, string | number | boolean | null | undefined>;` y `export const log: { info(event: string, fields?: LogFields): void; warn(...): void; error(...): void }`.

- [ ] **Step 1: Escribir el test que falla** (primera parte de `tests/observability.test.ts`):

```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';

test('log emits one JSON line per call with allow-listed scalar fields only', () => {
  const original = console.info;
  const lines: string[] = [];
  console.info = (value: string) => { lines.push(value); };
  try {
    const { log } = await import('../src/log.js');
    log.info('http_request', { request_id: 'abc', status: 200, duration_ms: 12.4, optional: undefined, nothing: null });
    assert.equal(lines.length, 1);
    const parsed = JSON.parse(lines[0]!) as Record<string, unknown>;
    assert.equal(parsed['event'], 'http_request');
    assert.equal(parsed['level'], 'info');
    assert.equal(parsed['request_id'], 'abc');
    assert.equal(parsed['status'], 200);
    assert.equal(parsed['duration_ms'], 12.4); // numbers preserved, never stringified
    assert.equal('optional' in parsed, false); // undefined dropped
    assert.equal(parsed['nothing'], null);
    assert.ok(typeof parsed['timestamp'] === 'string' && !Number.isNaN(Date.parse(parsed['timestamp'] as string)));
  } finally { console.info = original; }
});
```

(Ajustar a top-level `await import` dentro del test o import estático arriba; el patrón exacto lo marca el resto del fichero de tests.)

- [ ] **Step 2: Ejecutar y verificar que falla** — `cd services/api && npx tsx --test tests/observability.test.ts` → FAIL (`Cannot find module '../src/log.js'`).
- [ ] **Step 3: Implementación mínima** (`src/log.ts`):

```ts
// Structured JSON logging to stdout with a closed, scalar-only field set.
// No request bodies, Authorization headers, tokens, transcripts, or secrets can
// reach stdout through this module: the field type admits no objects or arrays.
export type LogFields = Record<string, string | number | boolean | null | undefined>;

const write = (level: 'info' | 'warn' | 'error', event: string, fields?: LogFields): void => {
  const line: Record<string, unknown> = { timestamp: new Date().toISOString(), level, event };
  for (const [key, value] of Object.entries(fields ?? {})) if (value !== undefined) line[key] = value;
  const text = JSON.stringify(line);
  if (level === 'error') console.error(text);
  else if (level === 'warn') console.warn(text);
  else console.info(text);
};

export const log = {
  info: (event: string, fields?: LogFields) => write('info', event, fields),
  warn: (event: string, fields?: LogFields) => write('warn', event, fields),
  error: (event: string, fields?: LogFields) => write('error', event, fields),
};
```

- [ ] **Step 4: Ejecutar y verificar PASS.**
- [ ] **Step 5: Commit** — `feat(observability): structured JSON logger with scalar-only fields`

### Task 3: Registro de instrumentos `src/metrics.ts`

**Files:**
- Create: `services/api/src/metrics.ts`
- Test: `services/api/tests/observability.test.ts`

**Interfaces:**
- Consumes: `@opentelemetry/api` (Meter, instrumentos).
- Produces: `export interface PaliqueMetrics { requestDuration: Histogram; requestsRejected: Counter; dbPoolConnections: ObservableGauge; dbPoolMaxConnections: Gauge; generationJobs: Counter; generationJobDuration: Histogram; generationLastSuccess: Gauge; generationLastFailure: Gauge; llmRequests: Counter; llmRequestDuration: Histogram; llmTokens: Counter; }`, `export let paliqueMetrics: PaliqueMetrics` (live binding) y `export function initDomainMetrics(meter: Meter): void`.

- [ ] **Step 1: Instalar dependencias** — `cd services/api && npm install --save-exact @opentelemetry/api@1.10.0 @opentelemetry/sdk-trace-node@2.12.0 @opentelemetry/sdk-metrics@3.0.0 @opentelemetry/resources@3.0.0 @opentelemetry/semantic-conventions@1.43.0 @opentelemetry/exporter-trace-otlp-http@0.300.0 @opentelemetry/exporter-metrics-otlp-http@0.300.0`. Verificar versión única de la API: `npm ls @opentelemetry/api` debe resolver **una sola** copia (1.10.0); duplicados = métricas no-op silenciosas (pitfall clásico de OTel JS). Si npm marca conflicto de peers, ajustar a la línea 0.30x/3.0.x más reciente que resuelva limpio y dejar la versión exacta fijada en `package.json`.
- [ ] **Step 2: Escribir el test de contrato** (segunda sección de `tests/observability.test.ts`):

```ts
import { metrics } from '@opentelemetry/api';
import { InMemoryMetricReader, MeterProvider } from '@opentelemetry/sdk-metrics';

async function collectSum(reader: InMemoryMetricReader, name: string): Promise<Map<string, number>> {
  const { resourceMetrics } = await reader.collect();
  const out = new Map<string, number>();
  for (const scope of resourceMetrics.scopeMetrics)
    for (const metric of scope.metrics) if (metric.name === name)
      for (const point of metric.dataPoints ?? []) {
        const labels = Object.entries(point.attributes).map(([k, v]) => `${k}=${String(v)}`).sort().join(',');
        out.set(labels, (out.get(labels) ?? 0) + Number(point.value));
      }
  return out;
}

test('domain metrics record through the instruments with contract attributes', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  const { initDomainMetrics, paliqueMetrics } = await import('../src/metrics.js');
  initDomainMetrics(provider.getMeter('test'));
  paliqueMetrics.requestsRejected.add(1, { reason: 'rate_limit' });
  paliqueMetrics.generationJobs.add(1, { language: 'en', level: 'A1', status: 'finished' });
  paliqueMetrics.generationLastSuccess.record(1_700_000_000);
  paliqueMetrics.llmTokens.add(12, { provider: 'glm', direction: 'output' });
  const rejected = await collectSum(reader, 'palique.requests.rejected');
  assert.equal(rejected.get('reason=rate_limit'), 1);
  const jobs = await collectSum(reader, 'palique.leveltest.generation.jobs');
  assert.equal(jobs.get('language=en,level=A1,status=finished'), 1);
  const tokens = await collectSum(reader, 'palique.llm.tokens');
  assert.equal(tokens.get('direction=output,provider=glm'), 12);
});
```

- [ ] **Step 3: Ejecutar y verificar FAIL** (no existe el módulo).
- [ ] **Step 4: Implementar** (`src/metrics.ts`) — todos los nombres/descripciones/unidades según §4.1:

```ts
import { metrics } from '@opentelemetry/api';
import type { Counter, Gauge, Histogram, Meter, ObservableGauge } from '@opentelemetry/api';

export interface PaliqueMetrics {
  requestDuration: Histogram;
  requestsRejected: Counter;
  dbPoolConnections: ObservableGauge;
  dbPoolMaxConnections: Gauge;
  generationJobs: Counter;
  generationJobDuration: Histogram;
  generationLastSuccess: Gauge;
  generationLastFailure: Gauge;
  llmRequests: Counter;
  llmRequestDuration: Histogram;
  llmTokens: Counter;
}

function build(meter: Meter): PaliqueMetrics {
  return {
    // RED of every HTTP request. Prometheus series:
    // http_server_request_duration_seconds_{count,sum,bucket}.
    requestDuration: meter.createHistogram('http.server.request.duration', { unit: 's',
      advice: { explicitBucketBoundaries: [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10] },
      description: 'End-to-end request duration by method, route template and status.' }),
    requestsRejected: meter.createCounter('palique.requests.rejected', { description: 'Requests refused before routing (rate limiter, trusted proxy).' }),
    dbPoolConnections: meter.createObservableGauge('palique.db.pool.connections', { description: 'PostgreSQL pool connections by state.' }),
    dbPoolMaxConnections: meter.createGauge('palique.db.pool.max_connections', { description: 'Configured pool size (pg Pool max).' }),
    generationJobs: meter.createCounter('palique.leveltest.generation.jobs', { description: 'Weekly level-test generation job outcomes.' }),
    generationJobDuration: meter.createHistogram('palique.leveltest.generation.job.duration', { unit: 's',
      advice: { explicitBucketBoundaries: [1, 5, 15, 30, 60, 120, 300, 600, 900] },
      description: 'Wall-clock duration of level-test generation jobs.' }),
    generationLastSuccess: meter.createGauge('palique.leveltest.generation.last_success_unix_seconds',
      { description: 'Unix seconds of the last finished generation job; initialized at boot.' }),
    generationLastFailure: meter.createGauge('palique.leveltest.generation.last_failure_unix_seconds',
      { description: 'Unix seconds of the last failed generation execution.' }),
    llmRequests: meter.createCounter('palique.llm.requests', { description: 'LLM chat completions by provider and outcome.' }),
    llmRequestDuration: meter.createHistogram('palique.llm.request.duration', { unit: 's',
      advice: { explicitBucketBoundaries: [0.25, 0.5, 1, 2, 5, 10, 30, 60, 120, 300, 600] },
      description: 'LLM completion latency by provider.' }),
    llmTokens: meter.createCounter('palique.llm.tokens', { description: 'Provider-reported tokens by direction.' }),
  };
}

// Live binding: consumers importing { paliqueMetrics } always see the latest
// instruments, so the provider installation order never freezes a no-op meter.
// Until initObservability installs the real provider this records nothing.
export let paliqueMetrics: PaliqueMetrics = build(metrics.getMeter('palique-api'));

export function initDomainMetrics(meter: Meter): void { paliqueMetrics = build(meter); }
```

- [ ] **Step 5: Ejecutar y verificar PASS** (`npx tsx --test tests/observability.test.ts`).
- [ ] **Step 6: Commit** — `feat(observability): palique domain metric registry`

### Task 4: Inicialización OTel `src/observability.ts`

**Files:**
- Create: `services/api/src/observability.ts`
- Modify: `services/api/src/metrics.ts` (nada; referencia de tipos)
- Test: `services/api/tests/observability.test.ts`

**Interfaces:**
- Consumes: `initDomainMetrics` de `./metrics.js`, `Database` de `./db.js` (type-only; `Pool` con `options.max`, `idleCount`, `totalCount`, `waitingCount`).
- Produces: `export function initObservability(env?: NodeJS.ProcessEnv): void`; `export function shutdownObservability(): Promise<void>` (no-op hasta init); `export function addDatabasePoolObservability(db: Database): void`.

- [ ] **Step 1: Escribir tests** (tercera sección):

```ts
test('observability stays no-op unless OTEL_ENABLED is true', async () => {
  const { initObservability, shutdownObservability } = await import('../src/observability.js');
  initObservability({});                       // disabled
  initObservability({ OTEL_ENABLED: 'false' }); // disabled
  await shutdownObservability();               // resolves without providers
});

test('pool observability publishes max and per-state connections', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  const { initDomainMetrics, paliqueMetrics } = await import('../src/metrics.js');
  const { addDatabasePoolObservability } = await import('../src/observability.js');
  initDomainMetrics(provider.getMeter('test'));
  const fakePool = { options: { max: 10 }, idleCount: 3, totalCount: 7, waitingCount: 1 } as never;
  addDatabasePoolObservability(fakePool);
  const pool = await collectSum(reader, 'palique.db.pool.connections');
  assert.equal(pool.get('state=idle'), 3);
  assert.equal(pool.get('state=in_use'), 4);
  assert.equal(pool.get('state=waiting'), 1);
  const max = await collectSum(reader, 'palique.db.pool.max_connections');
  assert.equal([...max.values()].reduce((a, b) => a + b, 0), 10);
});
```

- [ ] **Step 2: Ejecutar y verificar FAIL.**
- [ ] **Step 3: Implementar:**

```ts
import { metrics } from '@opentelemetry/api';
import { OTLPMetricExporter } from '@opentelemetry/exporter-metrics-otlp-http';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { MeterProvider, PeriodicExportingMetricReader } from '@opentelemetry/sdk-metrics';
import { BatchSpanProcessor, NodeTracerProvider } from '@opentelemetry/sdk-trace-node';
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions';
import type { Database } from './db.js';
import { initDomainMetrics, paliqueMetrics } from './metrics.js';

let shutdown: (() => Promise<void>) | undefined;

// No-op unless OTEL_ENABLED=true (tests, smoke stack and CI never start the
// telemetry stack). Idempotent: a second call keeps the first providers.
export function initObservability(env: NodeJS.ProcessEnv = process.env): void {
  if (env.OTEL_ENABLED !== 'true' || shutdown) return;
  const endpoint = (env.OTEL_EXPORTER_OTLP_ENDPOINT ?? 'http://otel-collector:4318').replace(/\/$/, '');
  const resource = resourceFromAttributes({ [ATTR_SERVICE_NAME]: env.OTEL_SERVICE_NAME ?? 'palique-api' });
  const tracerProvider = new NodeTracerProvider({ resource,
    spanProcessors: [new BatchSpanProcessor(new OTLPTraceExporter({ url: `${endpoint}/v1/traces` }))] });
  tracerProvider.register();
  const meterProvider = new MeterProvider({ resource,
    readers: [new PeriodicExportingMetricReader({ exporter: new OTLPMetricExporter({ url: `${endpoint}/v1/metrics` }),
      exportIntervalMillis: 15_000 })] });
  metrics.setMeterProvider(meterProvider);
  initDomainMetrics(metrics.getMeter('palique-api'));
  shutdown = () => Promise.allSettled([tracerProvider.shutdown(), meterProvider.shutdown()]).then(() => {});
}

export function shutdownObservability(): Promise<void> { return shutdown?.() ?? Promise.resolve(); }

// Gauges are recomputed at every export from live pool counters. Registered
// AFTER initDomainMetrics so the callback binds to the real instruments.
export function addDatabasePoolObservability(db: Database): void {
  paliqueMetrics.dbPoolMaxConnections.record(db.options.max ?? 10);
  paliqueMetrics.dbPoolConnections.addCallback(observer => {
    observer.observe(db.idleCount, { state: 'idle' });
    observer.observe(db.totalCount - db.idleCount, { state: 'in_use' });
    observer.observe(db.waitingCount, { state: 'waiting' });
  });
}
```

- [ ] **Step 4: `npx tsc --noEmit` limpio y tests PASS.** Si el constructor de la 2.x/3.0 rechaza alguna opción (los nombres `spanProcessors`/`readers` son de la línea 2.x/3.x), corregir contra la guía de migración del SDK y dejar el resto intacto.
- [ ] **Step 5: Commit** — `feat(observability): OTel init/shutdown and pool gauges`

### Task 5: RED HTTP, logs por request y requestId en errores 5xx (`app.ts` + `main.ts`)

**Files:**
- Modify: `services/api/src/app.ts` (hook onRequest existente ~línea 111, setErrorHandler ~línea 183, nuevo hook onResponse)
- Modify: `services/api/src/main.ts` (init/shutdown, logs estructurados)
- Test: `services/api/tests/observability.test.ts` (integración con `createApp`)

**Interfaces:**
- Consumes: `log` (Task 2), `paliqueMetrics` (Task 3), `initObservability`/`shutdownObservability`/`addDatabasePoolObservability` (Task 4).
- Produces: por cada request → span OTel (`<METHOD> <route>`), punto de datos del histograma RED, línea `http_request`; errores ≥ 500 → body `{ error: { code, requestId } }` (los 4xx conservan el body exacto `{ error: { code } }`).

- [ ] **Step 1: Escribir tests de integración** (cuarta sección; harness PG real igual que `tests/admin-usage.test.ts`; `createApp({ db })` con el mínimo de servicios):

```ts
test('health request emits RED data point and structured log line', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  const { initDomainMetrics } = await import('../src/metrics.js');
  initDomainMetrics(provider.getMeter('test'));
  const { createApp } = await import('../src/app.js');
  const app = createApp({ db }); // harness db de TEST_DATABASE_URL
  const original = console.info;
  const lines: string[] = [];
  console.info = (value: string) => { lines.push(value); };
  try {
    const response = await app.inject({ method: 'GET', url: '/healthz' });
    assert.equal(response.statusCode, 200);
    const durations = await collectSum(reader, 'http_server_request_duration_seconds_count');
    assert.ok([...durations.keys()].some(labels => labels.includes('http.route=/healthz') && labels.includes('http.response.status_code=200')));
    const logged = lines.map(line => JSON.parse(line) as Record<string, unknown>).find(entry => entry['event'] === 'http_request');
    assert.ok(logged && logged['route'] === '/healthz' && typeof logged['request_id'] === 'string');
  } finally { console.info = original; await app.close(); }
});

test('unexpected 5xx responses include requestId and never internals', async () => {
  // app con un route que lanza un Error crudo (handler de test temporal o
  // petición a una ruta con handler que fuerza el fallo; el body debe ser
  // { error: { code: 'service_unavailable', requestId: <uuid> } }, sin stack,
  // sin message del error original, y debe existir la línea request_failed.
});
```

(El segundo test concretará el lanzamiento del error: registrar en `app` de test una ruta provisional con `app.route({ method: 'GET', url: '/__boom', handler: async () => { throw new Error('secret-internal-detail'); } })` tras `createApp`, inyectar la petición y afirmar sobre el body y los logs.)

- [ ] **Step 2: Ejecutar y verificar FAIL.**
- [ ] **Step 3: Implementar en `app.ts`:**

Imports nuevos arriba: `import { log } from './log.js'; import { paliqueMetrics } from './metrics.js'; import { trace, type Span } from '@opentelemetry/api';`

a) Constante de módulo para colgar el span del request: `const REQUEST_SPAN = Symbol('request-span');` y helper `const requestRoute = (request: FastifyRequest): string => request.routeOptions.url ?? 'unmatched';`

b) Al **inicio** del hook `onRequest` existente (línea ~111, antes de las comprobaciones de admission), abrir el span:

```ts
const span = trace.getTracer('palique-api').startSpan(`${request.method} ${requestRoute(request)}`, {
  attributes: { 'http.request.method': request.method, 'http.route': requestRoute(request),
    'url.path': request.url.split('?')[0]!, 'palique.request_id': request.id },
});
(request as unknown as Record<symbol, Span | undefined>)[REQUEST_SPAN] = span;
```

> **Corrección de seguridad (2026-10-23):** la propuesta anterior de exportar
> `url.path` queda invalidada: Fastify puede devolver ahí segmentos concretos
> con identificadores. Los spans actuales son `SERVER`, exportan únicamente la
> plantilla `http.route` o `unmatched`, propagan el span activo durante la
> petición y marcan los 5xx como `ERROR`. El logger también protege su sobre
> reservado y descarta claves sensibles en tiempo de ejecución. Véase la
> implementación aprobada en `docs/superpowers/specs/2026-10-23-observability-remediation-design.md`.

c) En los tres puntos de rechazo del limitador, antes del `throw`: `paliqueMetrics.requestsRejected.add(1, { reason: 'rate_limit' });` (las dos comprobaciones de `rate_limit`) y `{ reason: 'trusted_proxy' }` en el `catch { throw new ServiceError('trusted_proxy_required', 503); }`.

d) Nuevo hook después de `setErrorHandler`:

```ts
app.addHook('onResponse', async (request, reply) => {
  const route = requestRoute(request);
  const durationSeconds = reply.elapsedTime / 1000;
  const span = (request as unknown as Record<symbol, Span | undefined>)[REQUEST_SPAN];
  if (span) { span.setAttribute('http.response.status_code', reply.statusCode); span.end(); }
  paliqueMetrics.requestDuration.record(durationSeconds, {
    'http.request.method': request.method, 'http.route': route, 'http.response.status_code': String(reply.statusCode) });
  log.info('http_request', { request_id: request.id,
    trace_id: span?.spanContext().traceId ?? undefined,
    method: request.method, route: route === 'unmatched' ? request.url.split('?')[0]! : route,
    status: reply.statusCode, duration_ms: Math.round(reply.elapsedTime) });
});
```

e) En `setErrorHandler`: dentro del bloque `if (diagnostic)` añadir la línea `log.warn('startup_diagnostic', { request_id: request.id, reference: diagnostic.reference, operation: diagnostic.operation, status: diagnostic.status, reason: diagnostic.reason });`; para los 5xx añadir log y `requestId`:

```ts
if (status >= 500) log.error('request_failed', { request_id: request.id,
  method: request.method, route: requestRoute(request), status, error_code: code });
```

y la respuesta final pasa de `reply.code(status).send({ error: { code } });` a:

```ts
reply.code(status).send({ error: { code, ...(status >= 500 ? { requestId: request.id } : {}) } });
```

(El retorno temprano de `HelperSessionLimitError` queda como está: 4xx, sin `requestId`.)

- [ ] **Step 4: Implementar en `main.ts`:**
  - Tras la comprobación de `DATABASE_URL`: `initObservability();` y tras `connectDatabase`: `addDatabasePoolObservability(db);`.
  - En `close()`: `await shutdownObservability();` antes de `db.end()` (flush del reader periódico).
  - Sustituir los `console.*` por `log.*` según la tabla §4.2 (`minute_commerce_failure`, `live_startup_failed`, `level_test_generation_job` ×2, `retention_cleanup_failed` ×5, `guest_allowance_retry_failed`). Mantener el mensaje final `console.info('Palique API is running.')` → `log.info('api_started')`.
  - En el `catch` de arranque: `log.error('startup_failed')` (sin detalles: la regla actual de no filtrar configuración se mantiene) y `await shutdownObservability().catch(() => {});` en la limpieza.
- [ ] **Step 5: `npx tsc --noEmit` limpio; tests PASS; revisar que ninguna línea de log nueva contiene body/headers (los tests ya lo aseguran por tipo).**
- [ ] **Step 6: Commit** — `feat(observability): RED metrics, request logs and requestId on 5xx`

### Task 6: Instrumentación del pipeline (jobs de generación + `chatCompletion`)

**Files:**
- Modify: `services/api/src/level-tests-generation-jobs.ts` (envolver `runLevelTestGenerationJob`)
- Modify: `services/api/src/llm-config.ts` (instrumentar `chatCompletion`, líneas ~103–131)
- Test: `services/api/tests/observability.test.ts`

**Interfaces:**
- Consumes: `paliqueMetrics` (Task 3).
- Produces: mismas firmas públicas (ningún cambio de tipo): `runLevelTestGenerationJob(db, env, options): Promise<GenerationJobResult>` y `chatCompletion(config, messages, fetchFn?, signal?): Promise<ChatReply>`.

- [ ] **Step 1: Escribir tests:**

```ts
test('chatCompletion records ok outcome with tokens and duration', async () => {
  const reader = new InMemoryMetricReader();
  const provider = new MeterProvider({ readers: [reader] });
  const { initDomainMetrics } = await import('../src/metrics.js');
  const { chatCompletion } = await import('../src/llm-config.js');
  initDomainMetrics(provider.getMeter('test'));
  const reply = await chatCompletion(
    { provider: 'glm', baseUrl: 'http://llm.test', model: 'm', apiKey: 'k' },
    [{ role: 'user', content: 'hi' }],
    async () => new Response(JSON.stringify({ choices: [{ message: { content: 'ok' } }],
      usage: { prompt_tokens: 10, completion_tokens: 5 } }), { status: 200 }));
  assert.equal(reply.tokensIn, 10);
  const requests = await collectSum(reader, 'palique.llm.requests');
  assert.equal(requests.get('provider=glm,status=ok'), 1);
  const tokens = await collectSum(reader, 'palique.llm.tokens');
  assert.equal(tokens.get('direction=input,provider=glm'), 10);
});

test('chatCompletion records failed outcome with the transport error code', async () => {
  // mismo setup; fetchFn que lanza -> LlmTransportError('llm_outcome_unknown')
  // assert rejects + palique_llm_requests_total{provider=glm,status=failed,error_code=llm_outcome_unknown} === 1
});
```

(Construir el objeto `ResolvedLlmConfig` solo con los campos que `chatCompletion` lee: `provider`, `baseUrl`, `model`, `apiKey`; el tipo completo admite `sessionID?` opcional. Si el tipo exige más campos, el test usa un cast parcial documentado.)

- [ ] **Step 2: Ejecutar y verificar FAIL.**
- [ ] **Step 3: Instrumentar `chatCompletion`** — envolver el cuerpo entre `const started = performance.now();` y el retorno; en el camino de éxito:

```ts
const outcome: ChatReply = { content, tokensIn: payload.usage?.prompt_tokens ?? 0, tokensOut: payload.usage?.completion_tokens ?? 0 };
paliqueMetrics.llmRequests.add(1, { provider: config.provider, status: 'ok' });
paliqueMetrics.llmRequestDuration.record((performance.now() - started) / 1000, { provider: config.provider });
paliqueMetrics.llmTokens.add(outcome.tokensIn, { provider: config.provider, direction: 'input' });
paliqueMetrics.llmTokens.add(outcome.tokensOut, { provider: config.provider, direction: 'output' });
return outcome;
```

y en un único `catch (error)` alrededor de todo el cuerpo (fetch + parse):

```ts
catch (error) {
  paliqueMetrics.llmRequests.add(1, { provider: config.provider, status: 'failed',
    error_code: error instanceof LlmTransportError ? error.code : 'llm_unknown_error' });
  throw error;
}
```

Los tres `throw new LlmTransportError(...)` internos se quedan como están (el catch los contabiliza); `LlmTransportError` se re-lanza tal cual (contrato de no-reintento intacto).

- [ ] **Step 4: Instrumentar `runLevelTestGenerationJob`** — renombrar la función actual a `runLevelTestGenerationJobOnce` (sin export) y exportar el wrapper:

```ts
export async function runLevelTestGenerationJob(db: Database, env: NodeJS.ProcessEnv,
    options: GenerateOptions): Promise<GenerationJobResult> {
  const startedAt = performance.now();
  const result = await runLevelTestGenerationJobOnce(db, env, options);
  const status = result.status === 'approved' || result.status === 'draft' ? 'finished' :
    result.status === 'failed' ? 'failed' : result.status === 'unknown' ? 'unknown' : null;
  if (status) {
    paliqueMetrics.generationJobs.add(1, { language: options.languageID, level: options.level, status });
    paliqueMetrics.generationJobDuration.record((performance.now() - startedAt) / 1000,
      { language: options.languageID, level: options.level });
    if (status === 'finished') paliqueMetrics.generationLastSuccess.record(Date.now() / 1000);
  }
  return result; // 'busy' y 'exists' no se contabilizan: son no-ops idempotentes
}
```

- [ ] **Step 5: `npx tsc --noEmit`; tests PASS (incluye el caso busy→sin serie).**
- [ ] **Step 6: Commit** — `feat(observability): level-test generation and LLM call metrics`

### Task 7: Configuración de telemetría (`services/api/telemetry/`)

**Files:**
- Create: `services/api/telemetry/otel-collector-config.yaml`
- Create: `services/api/telemetry/tempo.yaml`
- Create: `services/api/telemetry/prometheus.yml`
- Create: `services/api/telemetry/alertmanager.yml`
- Create: `services/api/telemetry/alerts.yml`
- Create: `services/api/telemetry/grafana/provisioning/datasources/datasource.yaml`
- Create: `services/api/telemetry/grafana/provisioning/dashboards/dashboards.yaml`

**Interfaces:**
- Produces: configs que consumen los servicios Docker de Task 8; nombres de serie de §4.1 usados por `alerts.yml` (Task 7) y el dashboard (Task 9).

- [ ] **Step 1: `otel-collector-config.yaml`** (idéntico al de periodico, probado):

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch: {}

exporters:
  otlphttp/tempo:
    endpoint: http://tempo:4318
  prometheus:
    endpoint: 0.0.0.0:8889

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp/tempo]
    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [prometheus]
```

- [ ] **Step 2: `tempo.yaml`** (con el bind 0.0.0.0 documentado como lección §2.1.6):

```yaml
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        http:
          # Without an explicit endpoint Tempo binds localhost inside its own
          # container and the collector gets "connection refused" exporting spans.
          endpoint: 0.0.0.0:4318

storage:
  trace:
    backend: local
    wal:
      path: /tmp/tempo/wal
    local:
      path: /tmp/tempo/blocks
```

- [ ] **Step 3: `prometheus.yml`:**

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - /etc/prometheus/alerts.yml

alerting:
  alertmanagers:
    - static_configs:
        - targets:
            - alertmanager:9093

scrape_configs:
  - job_name: otel-collector
    static_configs:
      - targets:
          - otel-collector:8889
```

- [ ] **Step 4: `alertmanager.yml`** (route único → sumidero local; el reenvío externo queda documentado como evolución en `docs/observability.md`):

```yaml
global:
  resolve_timeout: 5m

route:
  receiver: webhook
  group_by: ["alertname", "severity"]
  group_wait: 10s
  group_interval: 5m
  repeat_interval: 4h

receivers:
  - name: webhook
    webhook_configs:
      - url: http://webhook-logger:5001/alert
        send_resolved: true
        max_alerts: 0
```

- [ ] **Step 5: `alerts.yml`** — cabecera de comentarios listando las series esperadas (para verificar en vivo en Task 12) y las 6 reglas de §4.3, cada una con `labels` (`severity`, `component`) y `annotations` (`summary`, `description`, `runbook: docs/observability.md#runbooks`). Plantilla de una regla (las demás siguen el mismo molde):

```yaml
groups:
  - name: palique-api
    interval: 30s
    rules:
      - alert: ApiHttp5xxRatioHigh
        expr: |
          sum(rate(http_server_request_duration_seconds_count{job="otel-collector", exported_job="palique-api", http_response_status_code=~"5.."}[5m]))
            /
          sum(rate(http_server_request_duration_seconds_count{job="otel-collector", exported_job="palique-api"}[5m]))
            > 0.05
        for: 1m
        labels:
          severity: critical
          component: api
        annotations:
          summary: "5xx ratio above 5% ({{ $value | humanizePercentage }})"
          description: >-
            More than 5% of requests ended in 5xx over a 5m window. Inspect
            request_failed logs by requestId and the database pool before restarting.
          runbook: docs/observability.md#5xx-alto-en-la-api
      # ... LevelTestGenerationFailed, LlmFailuresSpike, DbPoolSaturated (warning),
      #     ApiMetricsMissing (absent(), for: 5m); no hay alerta por generación ausente
```

Nota dePromQL: los counters que pueden no existir aún (fallos) usan la forma `(sum(increase(...[24h])) or vector(0)) > 0` con paréntesis (lección §2.1.1). Los labels de semconv (`http_response_status_code` vs el antiguo `http_status_code`) se verifican en vivo en Task 12 y se corrigen aquí si difieren — así lo hacía periodico con su cabecera «VERIFICADOS en vivo».

- [ ] **Step 6: Provisioning de Grafana:**

`datasources/datasource.yaml`:
```yaml
apiVersion: 1

datasources:
  - name: Tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
    isDefault: true
    editable: false
  - name: Prometheus
    type: prometheus
    access: proxy
    uid: prometheus
    url: http://prometheus:9090
    isDefault: false
    editable: false
```

`dashboards/dashboards.yaml`:
```yaml
apiVersion: 1

providers:
  - name: palique-dashboards
    orgId: 1
    folder: "Palique"
    type: file
    disableDeletion: false
    updateIntervalSeconds: 10
    options:
      path: /etc/grafana/dashboards
```

- [ ] **Step 7: Validar sintaxis** (necesita Task 8 para los contenedores, o con imágenes sueltas):

```bash
docker run --rm -v "$PWD/services/api/telemetry:/telemetry:ro" --entrypoint promtool prom/prometheus:v2.54.1 check rules /telemetry/alerts.yml
docker run --rm -v "$PWD/services/api/telemetry:/telemetry:ro" --entrypoint amtool prom/alertmanager:v0.27.0 check-config /telemetry/alertmanager.yml
```

Expected: ambas salidas `SUCCESS` / `OK`.
- [ ] **Step 8: Commit** — `feat(observability): telemetry configs and alert rules`

### Task 8: Contenedores Docker (`compose.yaml`) y targets de Make

**Files:**
- Modify: `services/api/compose.yaml` (6 servicios nuevos + env de `api` + volúmenes)
- Modify: `services/api/tests/compose.smoke.yaml` (comentario)
- Modify: `Makefile` (`obs-check` + `.PHONY`)

**Interfaces:**
- Consumes: configs de Task 7 (rutas `./telemetry/...` relativas a `services/api/`).
- Produces: red Docker con `otel-collector`, `tempo`, `prometheus`, `alertmanager`, `webhook-logger`, `grafana`; la API exporta OTLP a `http://otel-collector:4318`.

- [ ] **Step 1: Añadir al `compose.yaml`** (después del servicio `api`, antes de `caddy`; todos los puertos en loopback):

```yaml
  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.127.0
    command: ["--config=/etc/otelcol-contrib/config.yaml"]
    volumes:
      - ./telemetry/otel-collector-config.yaml:/etc/otelcol-contrib/config.yaml:ro
    ports:
      - "127.0.0.1:4318:4318"
    depends_on: [tempo]
    restart: unless-stopped
  tempo:
    image: grafana/tempo:2.7.1
    command: ["-config.file=/etc/tempo/tempo.yaml"]
    volumes:
      - ./telemetry/tempo.yaml:/etc/tempo/tempo.yaml:ro
      - tempo_data:/tmp/tempo
    ports:
      - "127.0.0.1:3200:3200"
    restart: unless-stopped
  prometheus:
    image: prom/prometheus:v2.54.1
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      - --storage.tsdb.retention.time=90d
      - --storage.tsdb.retention.size=2GB
    volumes:
      - ./telemetry/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./telemetry/alerts.yml:/etc/prometheus/alerts.yml:ro
      - prometheus_data:/prometheus
    ports:
      - "127.0.0.1:9090:9090"
    depends_on: [otel-collector, alertmanager]
    restart: unless-stopped
  alertmanager:
    image: prom/alertmanager:v0.27.0
    volumes:
      - ./telemetry/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
    ports:
      - "127.0.0.1:9093:9093"
    depends_on: [webhook-logger]
    restart: unless-stopped
  # Local sink for Alertmanager: every alert payload is logged to stdout
  # (docker compose logs webhook-logger) so the route always has a verifiable
  # destination. External delivery (Slack/PagerDuty) stays documented evolution.
  webhook-logger:
    image: python:3.12-alpine
    command:
      - python
      - -c
      - |
        import json
        from http.server import BaseHTTPRequestHandler, HTTPServer

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length).decode("utf-8", "replace")
                try:
                    pretty = json.dumps(json.loads(body), ensure_ascii=False, sort_keys=True)
                except ValueError:
                    pretty = body
                print(f"ALERT POST {self.path}: {pretty}", flush=True)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"ok":true}')

            def log_message(self, *args):
                pass

        HTTPServer(("0.0.0.0", 5001), Handler).serve_forever()
    ports:
      - "127.0.0.1:5001:5001"
    restart: unless-stopped
  grafana:
    image: grafana/grafana:10.4.8
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD:-palique-local}
    volumes:
      - ./telemetry/grafana/provisioning/datasources:/etc/grafana/provisioning/datasources:ro
      - ./telemetry/grafana/provisioning/dashboards:/etc/grafana/provisioning/dashboards:ro
      - ./telemetry/grafana/dashboards:/etc/grafana/dashboards:ro
      - grafana_data:/var/lib/grafana
    ports:
      - "127.0.0.1:3003:3000"
    depends_on: [tempo, prometheus]
    restart: unless-stopped
```

y en `volumes:` añadir `tempo_data:`, `prometheus_data:`, `grafana_data:`.

- [ ] **Step 2: Env de la API en `compose.yaml`** (servicio `api`, junto al resto de env):

```yaml
      OTEL_ENABLED: "true"
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
      OTEL_SERVICE_NAME: palique-api
```

y en `depends_on` del servicio `api` añadir (solo orden de arranque; el collector caído NO tira la API):

```yaml
      otel-collector:
        condition: service_started
```

- [ ] **Step 3: Comentario en `tests/compose.smoke.yaml`** — bajo la cabecera existente, añadir: `# Telemetry is intentionally absent here: the API is a no-op exporter unless OTEL_ENABLED=true, and no OTEL_* env is set in this stack.`
- [ ] **Step 4: Makefile** — tras `health:`:

```make
obs-check: ## Valida la configuración de observabilidad (compose config, promtool, amtool)
	docker compose --project-directory $(CURDIR)/services/api -f $(CURDIR)/services/api/compose.yaml config -q
	docker run --rm -v $(CURDIR)/services/api/telemetry:/telemetry:ro --entrypoint promtool prom/prometheus:v2.54.1 check rules /telemetry/alerts.yml
	docker run --rm -v $(CURDIR)/services/api/telemetry:/telemetry:ro --entrypoint amtool prom/alertmanager:v0.27.0 check-config /telemetry/alertmanager.yml
```

y añadir `obs-check` a la lista `.PHONY`.
- [ ] **Step 5: Verificar** — comprobar antes que los puertos están libres (`ss -ltn | grep -E '4318|3200|9090|9093|5001|3003'` → vacío), luego `make obs-check` → silencioso (ok), `make up`, y en ~30s: `curl -sf http://localhost:9090/-/ready`, `curl -sf http://localhost:3200/ready`, `curl -s http://localhost:9090/api/v1/targets | grep -c otel-collector` → up; `docker compose -f services/api/compose.yaml logs webhook-logger` sin errores; `make health` sigue respondiendo.
- [ ] **Step 6: Commit** — `feat(observability): telemetry stack containers in compose`

### Task 9: Dashboard de Grafana `palique-overview.json`

**Files:**
- Create: `services/api/telemetry/grafana/dashboards/palique-overview.json`

**Interfaces:**
- Consumes: series de §4.1 y datasource `uid: prometheus` (Task 7).

- [ ] **Step 1: Crear el dashboard** con 8 paneles en 2 filas (gridPos w=6 h=8), datasource `{ "type": "prometheus", "uid": "prometheus" }` en cada target, `time: { from: "now-6h", to: "now" }`, `uid: "palique-overview"`, `schemaVersion: 39`. Targets (refId A..):

| # | Tipo | Título | Unidad | PromQL |
|---|---|---|---|---|
| 1 | timeseries | Request rate (qps) | reqps | `sum(rate(http_server_request_duration_seconds_count{exported_job="palique-api"}[$__rate_interval]))` |
| 2 | timeseries | 5xx ratio | percentunit | `sum(rate(http_server_request_duration_seconds_count{exported_job="palique-api",http_response_status_code=~"5.."}[5m])) / sum(rate(http_server_request_duration_seconds_count{exported_job="palique-api"}[5m]))` |
| 3 | timeseries | p95 latency | s | `histogram_quantile(0.95, sum by (le) (rate(http_server_request_duration_seconds_bucket{exported_job="palique-api"}[$__rate_interval])))` |
| 4 | timeseries | DB pool by state | short | `palique_db_pool_connections{exported_job="palique-api"}` (legend `{{state}}`) |
| 5 | stat | Last generation success age (informational) | s | `time() - palique_leveltest_generation_last_success_unix_seconds{exported_job="palique-api"}` (thresholds are informational; no alert on age) |
| 6 | timeseries | Generation jobs (24h) | short | `sum by (status) (increase(palique_leveltest_generation_jobs_total{exported_job="palique-api"}[24h]))` (legend `{{status}}`) |
| 7 | timeseries | LLM requests (1h) | short | `sum by (status) (increase(palique_llm_requests_total{exported_job="palique-api"}[1h]))` (legend `{{status}}`) |
| 8 | timeseries | LLM tokens (24h) | short | `sum by (direction) (increase(palique_llm_tokens_total{exported_job="palique-api"}[24h]))` (legend `{{direction}}`) |

- [ ] **Step 2: Validar JSON** — `node -e "JSON.parse(require('fs').readFileSync('services/api/telemetry/grafana/dashboards/palique-overview.json','utf8'))"` → silencioso.
- [ ] **Step 3: Verificar provisioning en vivo** — `make restart` (o `make up`), abrir `http://localhost:3003` (admin / `GRAFANA_PASSWORD` o `palique-local`), carpeta «Palique», dashboard con paneles renderizados (paneles 5–8 pueden ir vacíos hasta que haya generación; 1–4 con datos tras cualquier request, p. ej. `make health`).
- [ ] **Step 4: Commit** — `feat(observability): provisioned palique overview dashboard`

### Task 10: CI (`checks.yml`)

**Files:**
- Modify: `.github/workflows/checks.yml` (job `server`)

**Interfaces:**
- Consumes: `make obs-check` (Task 8).

- [ ] **Step 1: Añadir un step al final del job `server`** (tras los steps existentes de test; el job ya corre en `ubuntu-24.04` con Docker y `defaults.run.working-directory: services/api`, así que las rutas son relativas a `services/api/`):

```yaml
      - name: Validate observability configs
        run: |
          docker compose --project-directory . -f compose.yaml config -q
          docker run --rm -v "$PWD/telemetry:/telemetry:ro" --entrypoint promtool prom/prometheus:v2.54.1 check rules /telemetry/alerts.yml
          docker run --rm -v "$PWD/telemetry:/telemetry:ro" --entrypoint amtool prom/alertmanager:v0.27.0 check-config /telemetry/alertmanager.yml
```

(Usar la segunda forma; `make obs-check` local y este step comparten la misma validación, el step evita la dependencia de la ruta absoluta `$(CURDIR)` del Makefile en CI.)

- [ ] **Step 2: Verificar en local el equivalente** (`make obs-check`) y confirmar que la sintaxis YAML del workflow es válida (`python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/checks.yml'))"`).
- [ ] **Step 3: Commit** — `ci(observability): validate telemetry configs in checks workflow`

### Task 11: Documentación (`docs/observability.md`, AGENTS.md, README de la API)

**Files:**
- Create: `services/api/docs/observability.md` (español)
- Modify: `AGENTS.md` (tabla de comandos + mapa de ficheros + convención de métricas)
- Modify: `services/api/README.md` (sección Observabilidad con tabla de puertos)

**Interfaces:**
- Consumes: contrato §4 (se copia/adapta, es la fuente).

- [ ] **Step 1: Escribir `services/api/docs/observability.md`** en español, estructura espejo de `periodico/docs/07-ops/02-observability.md`:
  1. **Logs** — contrato §4.2 (tabla de eventos), reglas de no-logging existentes citadas, política `requestId`: toda respuesta 5xx incluye `error.requestId`, coincide con `request_id` en logs y con la cabecera `X-App-Error-Reference` cuando aplica.
  2. **Métricas** — tabla §4.1 completa (instrumento → serie Prometheus), la advertencia de que montar el stack no inventa métricas de dominio (solo existen las que el código emite) y la aclaración analítica de negocio (`admin-metrics.ts` y `usage_events`) no es parte de este pipeline.
  3. **Alertas** — tabla §4.3 con umbral/severidad/`for`, lecciones de counters sin eventos y `absent()`, y cómo validar: `promtool check rules`, `curl localhost:9090/api/v1/rules`, `curl localhost:9090/api/v1/alerts`, `docker compose logs webhook-logger`.
  4. **SLOs internos** — tabla §4.4 con queries y limitaciones honestas (evaluación manual; la alerta 5xx es táctica, no medición de SLO).
  5. **Stack y puertos** — tabla: 4318 collector, 3200 tempo, 9090 prometheus, 9093 alertmanager, 5001 webhook-logger, 3003 grafana; todos `127.0.0.1`; en producción acceso por túnel SSH; `GRAFANA_PASSWORD` por env (nunca en git).
  6. **Runbooks** (secciones ancladas que usan las reglas de alerta): «5xx-alto-en-la-api», «silencio-de-metricas» (orden: `make status` → targets de :9090 → logs del collector), «generacion-fallando» (revisar `level_test_generation_jobs` y `level_test_weeks`, códigos `llm_outcome_unknown`), «pool-de-bd-saturado» (queries largas, `statement_timeout` existente), «coste-llm-anomalo» (tokens por dirección, cruce con `llm-config-admin`).
  7. **Diferencias con la referencia** (periodico): sin `user_id`/IP en telemetría, sin worker separado, retención Prometheus 90d, sin presupuesto IA por techo (el corte de gasto de Palique vive en el ledger).
- [ ] **Step 2: Actualizar `AGENTS.md`:**
  - Tabla de comandos: fila `make obs-check`.
  - Mapa de ficheros clave: filas `services/api/src/observability.ts` (init OTel + pool), `services/api/src/metrics.ts` (instrumentos `palique_*`), `services/api/src/log.ts` (logger JSON), `services/api/telemetry/` (configs del stack), `services/api/docs/observability.md` (contrato).
  - Convención nueva en «Convenciones del repositorio»: métricas de dominio con prefijo `palique_`, labels de cardinalidad cerrada, nunca PII en telemetría; al añadir rutas nuevas no se requiere nada (RED es automático), al añadir jobs asíncronos sí contadores de dominio.
- [ ] **Step 3: Actualizar `services/api/README.md`** — sección breve «Observabilidad» con los puertos y el enlace a `docs/observability.md`.
- [ ] **Step 4: Revisar consistencia** — cada `runbook:` de `alerts.yml` tiene su ancla en el doc; los nombres de evento de §4.2 coinciden con el código final.
- [ ] **Step 5: Commit** — `docs(observability): telemetry contract, runbooks and repo guidance`

### Task 12: Verificación end-to-end y cierre

**Files:**
- Create: `openspec/changes/observability/verify-report.md`
- Modify (si aplica): `services/api/telemetry/alerts.yml` (corrección de labels tras verificación en vivo)

- [ ] **Step 1: Suite completa** — `cd services/api && npx tsc --noEmit && npm test` PASS; `make test-api` PASS (el stack de humo NO arranca contenedores de observabilidad: verificar con `docker compose -p looro-smoke ps` que solo están database/migrate/api/tests).
- [ ] **Step 2: Stack vivo** — `make up` y esperar ~45s:
  - `curl -sk https://localhost/healthz` → 200 y en `docker compose -f services/api/compose.yaml logs api` aparece una línea `http_request` JSON por petición.
  - `curl -s http://localhost:9090/api/v1/series --data-urlencode 'match[]=http_server_request_duration_seconds_count' | jq '.data[].http_route'` → lista las rutas golpeadas.
  - **Verificar labels reales** (lección §2.1.4): confirmar que los labels son `http_response_status_code` / `http_request_method` / `http_route` y que `exported_job="palique-api"` existe; si difieren, corregir `alerts.yml` + dashboard y revalidar con promtool.
  - `curl -s http://localhost:9090/api/v1/rules | jq '.data.groups[].rules[].name'` → las 6 reglas cargadas, ninguna en estado `err`.
  - Disparar una alerta de prueba: `docker compose -f services/api/compose.yaml stop otel-collector` → a los ~5–6 min `ApiMetricsMissing` firing; `docker compose logs webhook-logger` muestra `ALERT POST /alert: {...}`; `start otel-collector` → resolved también registrado.
  - Generar fallo LLM controlado (configurar un `LEVELTEST_LLM_URL` inválido en local y lanzar `npm run leveltests-generate`) → serie `palique_llm_requests_total{status="failed"}` visible en :9090.
  - Trazas: `curl -s 'http://localhost:3200/api/search?q=http' | head` (o UI de Grafana → Explore → Tempo) devuelve traces del servicio `palique-api`.
- [ ] **Step 3: Regresión de privacidad** — sobre los logs de la API en marcha (`docker compose logs api`), grep agresivo: `docker compose logs api | grep -iE 'authorization|bearer|apikey|api_key|stripe|token=' | grep -v '"level"'` → vacío (los únicos hits permitidos son eventos propios cuyo texto controlamos).
- [ ] **Step 4: Escribir `verify-report.md`** — qué se verificó (con comandos y salida resumida), labels reales confirmados, y qué queda pendiente (alertas externas, SLO tracking, alerta de latencia, freshness end-to-end, coste LLM en €).
- [ ] **Step 5: Commit final** — `chore(observability): verify end-to-end pipeline and report`

---

## Self-review

1. **Cobertura del pedido**: contenedores Docker (Tasks 7–9), lógica de instrumentación con buenas prácticas (Tasks 2–6: módulos con responsabilidad única, inyección por parámetros, no-op por defecto, allow-list de logs, cardinalidad controlada, shutdown con flush), comparativa con periodico (§2), alertas/SLOs/runbooks/docs (§4, Tasks 7, 11), validación CI (Task 10) y verificación honesta (Task 12). ✔
2. **Sin placeholders**: todos los steps tienen comando esperado o contenido completo; las dos zonas con bifurcación documentada (versiones peers de OTel en Task 3.1 y nombres de labels de semconv en Task 7.5/12.2) incluyen el método exacto de resolución, no un «TBD». ✔
3. **Consistencia de tipos/nombres**: `log.info/warn/error(event, LogFields)`, `paliqueMetrics.*`, `initDomainMetrics(meter)`, `initObservability(env?)`, `shutdownObservability()`, `addDatabasePoolObservability(db)` se usan con la misma firma en las Tasks 4–6; los nombres de instrumentos de §4.1 son los mismos en `metrics.ts`, `alerts.yml`, dashboard y docs. ✔
4. **Riesgos declarados**: cambio de body en errores 5xx (solo se añade `requestId`, los 4xx intactos), versión del API del SDK 2.x/3.x (`spanProcessors`/`readers`/`resourceFromAttributes`), nombres de series dependientes del exporter del collector. ✔
