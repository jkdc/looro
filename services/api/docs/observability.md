# Observabilidad de la API

El stack de observabilidad de Palique replica la topología del proyecto de
referencia (periodico): OpenTelemetry → OTel Collector → Prometheus
(métricas y alertas) + Tempo (trazas) + Grafana (dashboards provisionados) +
Alertmanager con sumidero local. Todo corre por Docker en
`services/api/compose.yaml` y **no se activa por defecto**.

`OTEL_ENABLED` distinto de `"true"` deja la instrumentación en no-op: `make
test-api`, el stack de humo y los tests unitarios no arrancan ningún
contenedor de telemetría. Ningún servicio de producto depende de que el
collector esté sano: la exportación es best-effort y con buffer.

Analítica de producto ≠ observabilidad técnica. Las series SQL diarias de
`admin-metrics.ts` (overview/growth/voice/economy/usage/tokens) y
`usage_events` del cliente **no forman parte de este pipeline** y no se
duplican aquí.

## Logs

Un objeto JSON por línea en stdout. Campos comunes: `timestamp` (ISO 8601
UTC), `level` (`info` | `warn` | `error`), `event`, y cuando existan
`request_id`, `trace_id` y `error_code`.

| Evento | Nivel | Campos | Emisor |
|---|---|---|---|
| `api_started` | info | — | `main.ts` (arranque completo) |
| `startup_failed` | error | — | `main.ts` (catch de arranque; sin detalles de configuración) |
| `http_request` | info | `request_id`, `method`, `route`, `status`, `duration_ms`, `trace_id`? | hook `onResponse` de Fastify |
| `request_failed` | error | `request_id`, `method`, `route`, `status`, `error_code` | `setErrorHandler` (solo status ≥ 500) |
| `startup_diagnostic` | warn | `request_id`, `reference`, `operation`, `status`, `reason` | `setErrorHandler` (diagnóstico de voz hospedada) |
| `level_test_generation_job` | warn | `language`, `level`, `week`, `status`, `error_code` | `main.ts` (scheduler semanal) |
| `minute_commerce_failure` | error | `code` | `main.ts` |
| `live_startup_failed` | warn | `reference`, `operation`, `status`, `reason` | `main.ts` |
| `retention_cleanup_failed` | error | — | `main.ts` (prunes de retención) |
| `guest_allowance_retry_failed` | error | — | `main.ts` (reintento de guest links) |

El logger (`src/log.ts`) solo acepta escalares por tipo
(`string | number | boolean | null | undefined`). Es imposible pasarle un
objeto: **nunca** se registran request bodies, cabeceras `Authorization`,
tokens, transcripts, payloads de Stripe, claves, IPs ni `account_id`.
`route` es siempre la plantilla de ruta (`/v1/...`) o la URL sin query en
peticiones que no casan con ninguna ruta.

Nota de migración: el antiguo evento `conversation_request_failed` de
`main.ts` se retiró; el mismo dato viaja ahora como `startup_diagnostic`
emitido en el origen (con `request_id` añadido).

### Política de `requestId`

Toda respuesta 5xx incluye `error.requestId` en el body:

```json
{ "error": { "code": "service_unavailable", "requestId": "<uuid>" } }
```

Ese valor coincide con `request_id` en las líneas `http_request` /
`request_failed` y, cuando aplique, con la cabecera
`X-App-Error-Reference`. Los 4xx conservan exactamente
`{ "error": { "code": "..." } }` sin `requestId`. Los 5xx nunca exponen
mensajes internos ni stack traces en el body.

## Métricas

Instrumentos OTel en `src/metrics.ts`; series Prometheus tal como las expone
el collector (`:8889`) y las scrapea Prometheus.

| Instrumento (nombre OTel) | Tipo | Atributos | Serie Prometheus |
|---|---|---|---|
| `http.server.request.duration` | Histogram (s) | `http.request.method`, `http.route`, `http.response.status_code` | `http_server_request_duration_seconds_{count,sum,bucket}` |
| `palique.requests.rejected` | Counter | `reason` ∈ `rate_limit` \| `trusted_proxy` | `palique_requests_rejected_total` |
| `palique.db.pool.connections` | ObservableGauge | `state` ∈ `idle` \| `in_use` \| `waiting` | `palique_db_pool_connections` |
| `palique.db.pool.max_connections` | Gauge | — | `palique_db_pool_max_connections` |
| `palique.leveltest.generation.jobs` | Counter | `language`, `level`, `status` ∈ `finished` \| `failed` \| `unknown` | `palique_leveltest_generation_jobs_total` |
| `palique.leveltest.generation.job.duration` | Histogram (s) | `language`, `level` | `palique_leveltest_generation_job_duration_seconds_*` |
| `palique.leveltest.generation.last_success_unix_seconds` | Gauge | — | `palique_leveltest_generation_last_success_unix_seconds` |
| `palique.llm.requests` | Counter | `provider`, `status` ∈ `ok` \| `failed`, `error_code` | `palique_llm_requests_total` |
| `palique.llm.request.duration` | Histogram (s) | `provider` | `palique_llm_request_duration_seconds_*` |
| `palique.llm.tokens` | Counter | `provider`, `direction` ∈ `input` \| `output` | `palique_llm_tokens_total` |

Recordatorios operativos:

- Montar el stack **no inventa** métricas de dominio: solo existen las que el
  código emite. Una serie `_total` no aparece hasta el primer evento.
- Labels de cardinalidad cerrada: plantilla de ruta, nunca URL cruda con IDs;
  nunca identificadores de usuario ni IPs.
- Los jobs `busy` y `exists` de generación son no-ops idempotentes y no se
  contabilizan a propósito.
- El coste LLM en € queda fuera de este pipeline: los precios viven en
  `/v1/pricing` (nanoUSD) y en el ledger. Aquí solo se cuentan tokens.

## Alertas

`services/api/telemetry/alerts.yml`, evaluadas cada 30 s por Prometheus.
Todas filtran `job="otel-collector", exported_job="palique-api"`.

| Alerta | Expresión (resumen) | Umbral | `for` | Severidad |
|---|---|---|---|---|
| `ApiHttp5xxRatioHigh` | ratio 5xx sobre el total RED en 5 m | > 5 % | 1m | critical |
| `ApiMetricsMissing` | `absent()` de la serie de latido (gauge del pool) | ausente | 5m | critical |
| `LevelTestGenerationFailed` | `(sum(increase(...{status="failed"}[24h])) or vector(0)) > 0` | cualquier fallo en 24 h | 1m | warning |
| `LevelTestGenerationStale` | `time() - ...last_success_unix_seconds` | > 691200 s (8 días) | 1m | warning |
| `LlmFailuresSpike` | `(sum(increase(...{status="failed"}[1h])) or vector(0)) > 5` | > 5 fallos/h | 1m | warning |
| `DbPoolSaturated` | `max_over_time(...{state="in_use"}[5m]) / max(...max_connections)` | > 90 % del pool | 1m | warning |

Lecciones incorporadas:

- Los counters sin eventos no existen como serie: por eso las reglas que
  pueden disparar con «cualquier evento» se anclan con
  `(sum(...) or vector(0))` **con paréntesis** (sin ellos `or` se evalúa
  después de la comparación y la regla dispara siempre).
- `ApiMetricsMissing` usa `absent()` con `for: 5m` para no disparar en cada
  redepliegue y para cubrir el punto ciego de un proceso caído (el resto de
  alertas de dominio se auto-resuelven si el proceso muere). Se ancla en
  `palique_db_pool_connections`, que se exporta en cada ciclo mientras el
  proceso viva: la serie RED solo existe tras la primera petición, así que
  anclarla ahí daba un falso positivo con una API recién desplegada e inactiva
  (verificado en vivo).
- Las ventanas `for` están afinadas para verificación local (1m); en un
  despliegue real conviene subirlas a 5–10m.

Validación:

```bash
promtool check rules services/api/telemetry/alerts.yml   # o make obs-check
curl -s localhost:9090/api/v1/rules | jq '.data.groups[].rules[].name'
curl -s localhost:9090/api/v1/alerts | jq '.data.alerts'
docker compose -f services/api/compose.yaml logs webhook-logger
```

## SLOs internos

Objetivos de referencia, no medidas automáticas. La evaluación es manual y
la retención de Prometheus (90 días, 2 GB) la hace posible.

| # | Objetivo | Query de cumplimiento | Limitación |
|---|---|---|---|
| 1 | ≥ 99,5 % de peticiones sin 5xx al mes (presupuesto ≈ 3,6 h) | `1 - sum(rate(http_server_request_duration_seconds_count{exported_job="palique-api",http_response_status_code=~"5.."}[30d])) / sum(rate(http_server_request_duration_seconds_count{exported_job="palique-api"}[30d]))` | evaluación manual; la alerta 5xx es táctica, no medición de SLO |
| 2 | p95 de GET < 500 ms | `histogram_quantile(0.95, sum by (le) (rate(http_server_request_duration_seconds_bucket{exported_job="palique-api",http_request_method="GET"}[5m])))` | sin alerta de latencia en esta fase |
| 3 | semana de exámenes generada y publicada a tiempo | proxy: `LevelTestGenerationStale` no dispara | la frescura end-to-end (evento → app) queda como evolución |

## Stack y puertos

Todos los puertos se publican en `127.0.0.1` (nunca `0.0.0.0`). En
producción solo Caddy expone 80/443; el acceso a estas UIs es por túnel SSH.

| Puerto | Servicio | Uso |
|---|---|---|
| 4318 | `otel-collector` | OTLP HTTP (entrada de métricas y trazas) |
| 3200 | `tempo` | API/UI de trazas (Grafana lo usa como datasource) |
| 9090 | `prometheus` | métricas, reglas y alertas |
| 9093 | `alertmanager` | enrutado de alertas |
| 5001 | `webhook-logger` | sumidero local: cada payload de alerta va a stdout |
| 3003 | `grafana` | dashboards (admin / `GRAFANA_PASSWORD` o `palique-local`) |

El volumen `tempo_data` se chown-ea a `10001` una vez mediante el servicio
`tempo-init` (contenedor efímero), porque Tempo no puede crear su block store
en un volumen nuevo propiedad de root. `make up` lo hace solo.

## Runbooks

<a id="5xx-alto-en-la-api"></a>
### 5xx alto en la API

1. `curl -s localhost:9090/api/v1/alerts` para confirmar `ApiHttp5xxRatioHigh`.
2. `docker compose -f services/api/compose.yaml logs api | grep '"event":"request_failed"'`
   y agrupar por `error_code`; cada línea trae `request_id` para cruzar con
   una incidencia reportada.
3. Revisar el pool (`palique_db_pool_connections`) y las queries lentas antes
   de reiniciar. Un reinicio sin diagnóstico borra la evidencia.

<a id="silencio-de-metricas"></a>
### Silencio de métricas

Orden de comprobación: `make status` (¿está vivo el proceso? ¿y el
collector?) → targets de Prometheus en `localhost:9090/targets` → logs del
collector (`docker compose logs otel-collector`) → endpoint OTLP
(`OTEL_EXPORTER_OTLP_ENDPOINT`). Si el collector está caído, la API sigue
funcionando: es un fallo de observabilidad, no de producto.

<a id="generacion-fallando"></a>
### Generación fallando

Revisar `level_test_generation_jobs` y `level_test_weeks` en PostgreSQL.
Códigos habituales: `llm_outcome_unknown` (transporte tras invocar al
proveedor: no se reintenta automáticamente), `attempt_limit_reached`
(2 intentos automáticos) y `generation_failed`. Las líneas
`level_test_generation_job` dan `language`/`level`/`week`/`status`.

<a id="pool-de-bd-saturado"></a>
### Pool de BD saturado

Buscar queries largas y `statement_timeout` en PostgreSQL, conexiones en
espera (`state="waiting"`) y picos de tráfico. El máximo del pool es
`palique_db_pool_max_connections`; el uso se mide con
`palique_db_pool_connections`.

<a id="coste-llm-anomalo"></a>
### Fallos y coste LLM anómalo

1. `palique_llm_requests_total{status="failed"}` con el desglose por
   `error_code` (spike > 5/h dispara `LlmFailuresSpike`).
2. Tokens por dirección (`palique_llm_tokens_total{direction=...}`) para
   detectar respuestas anormalmente largas.
3. Cruzar con la configuración de proveedores (`llm-config-admin`) y el
   estado del proveedor. El gasto real se audita en el ledger, no aquí.

## Diferencias con la referencia (periodico)

- Sin `user_id` ni IPs en telemetría (política de privacidad de Palique);
  `request_id`/`trace_id` bastan para correlacionar con la BD.
- No hay worker separado: los jobs viven en el proceso de la API, así que las
  alertas de proceso caído recaen en la serie RED.
- Retención de Prometheus de 90 días / 2 GB (la referencia dejaba la ventana
  por defecto, insuficiente para SLOs mensuales).
- Sin presupuesto IA por techo: el corte de gasto vive en el ledger.
- Sin entrega externa de alertas: `webhook-logger` es el destino verificable;
  Slack/PagerDuty sería un segundo receiver vía `ALERTMANAGER_WEBHOOK_URL`.
