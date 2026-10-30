# API Performance Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended for inline execution) or superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar S1–S6 de la primera fase aprobada, sin cambios distribuidos especulativos.
**Architecture:** Driver y fixtures aislados, telemetría nativa y proxy acotado con intentos durables. Mantener Node/Fastify/pg y contratos previos de éxito, cuota por cuenta y liquidación.
**Tech Stack:** Node >=22.19, TypeScript, node:test/tsx, Fastify, pg, PostgreSQL 17, OTel existente y Docker Compose.
**Spec:** `openspec/changes/api-performance-hardening/design.md` y `specs/api-capacity/spec.md`.

## Global Constraints

- Rama `feat/api-performance-hardening`; sin worktrees, nuevas dependencias, Gentle AI, push ni PR.
- Código/comentarios English; documentación e informes español profesional.
- Sin tokens/PII en logs ni evidencias; sin proveedor real ni red remota.
- Defaults proxy: 10 en vuelo (1–1000), 30000 ms (1–300000), 1048576 bytes (1024–16777216); cuota por cuenta 2 y maxTokens 4000 conservados.
- Driver: 100 en vuelo, 5000 ms HTTP y 35000 ms proxy; errores/empty/late no se contabilizan como éxito.
- RED antes de cada comportamiento nuevo; test de regresión por hallazgo; no declarar verificadas pruebas omitidas.
- No modificar migraciones existentes ni autorizaciones/saldos a partir de caché; ninguna afirmación de capacidad de producción.

## Review Focus

- Cancelación tras usage conocido conserva liquidación, y cancelación previa conserva incertidumbre (T5).
- Error SQL tras envío no produce éxito ni oculta un intento dispatched (T4/T5).
- Llegadas retrasadas del generador no forman cola ilimitada ni sesgan selección de cuenta (T1).
- Una configuración local mal dirigida o redirect no puede alcanzar stack/proveedor persistente (T2).
- Shutdown y reinicio de observabilidad no dejan timers, listeners o callbacks duplicados (T3/T5).

## Mapa de archivos y límites

- `load/driver.mjs`: planificación, validación, reporte y transporte importables; `load/loadtest.mjs`: CLI exclusivamente.
- `load/{compose.load.yaml,seed.mjs,stub.mjs,run.mjs,README.md}`: stack sintético, manifiesto efímero de fixtures, proveedor falso, orquestación y uso. Scripts se ejecutan con Node, no necesitan compilación TypeScript.
- `src/{metrics,observability,token-proxy,app,main}.ts`: señales e integración; `src/proxy-attempts.ts`: persistencia aislada de intentos y finalización; `src/proxy-transport.ts`: lectura limitada y cancelación.
- `migrations/055_proxy_attempts.sql`: siguiente número observado; comprobar que sigue libre al empezar T4.
- `tests/{load-driver,proxy-capacity,proxy-attempts,proxy-cancellation}.test.ts`: pruebas nuevas; extender `observability.test.ts`, `token-proxy.test.ts` y `support` cuando haga falta.
- `docs/api-capacity.md`, `docs/observability.md`, `compose.yaml`, `Dockerfile`, `package.json`, `Makefile`, dashboard existente: solo wiring, contratos y comandos de esta fase.

## T0 — Baseline técnica y estado del change

- [ ] Ejecutar `cd services/api && npx tsc --noEmit` con Node >=22.19; registrar salida en verify-report, incluso si falla antes de cambios.
- [ ] Comprobar rama, estado y numeración SQL; preservar todos los untracked ajenos. Registrar aprobación del diseño y método de ejecución.
- [ ] Preparar carpeta ignorada `openspec/changes/api-performance-hardening/evidence/` para resultados; añadir regla acotada de ignore si falta.

## T1 — S1: driver determinista, corregido y acotado

**Files:** crear `load/driver.mjs`, `tests/load-driver.test.ts`; modificar `load/loadtest.mjs`, Dockerfile y package.json para que tests del driver funcionen en fuente y dist. No copiar tokens.txt al contenedor.
**Interfaces:** `readTask(index, tokens)` → `{method,path,token?}`; `clientHeaders(index, networks, proxyToken)`; `runPhase(options, dependencies)` → PhaseReport; `report(samples, timing)` → PhaseReport; `runCli(env, dependencies)` → exitCode. Dependencias de reloj/sleep/fetch explícitas y transportes retornan `{status, useful, milliseconds, outcome}`. PhaseReport lleva offered/launched/dropped/completedInWindow/drained/useful, statuses/transportFailures, offeringSeconds/elapsedSeconds, offeredRps/usefulRps, percentiles all/success. Resolver tipos como JSDoc en módulo JS.

- [ ] Escribir tests `anonymous reads never request undefined`, `launch selection does not depend on completion order`, `NETWORKS bounds synthetic population`, `late arrivals and full capacity are counted as dropped`, `drain time is included in useful throughput`, `errors remain visible in latency distributions`, `empty samples produce null percentiles`, `import does not start traffic`; usar fetch falso y reloj manual.
- [ ] Ejecutar `cd services/api && npx tsx --test tests/load-driver.test.ts` y observar RED por defectos/requisitos reales, no imports rotos. Extraer primero seam neutro si hace falta para probar comportamiento actual.
- [ ] Implementar funciones y CLI; validar defaults y rangos driver: NETWORKS 1–100000, en vuelo 1–10000, timeout 1–300000 ms, duración 1–7200 s, tasa 1–100000. Rechazar cadena vacía, NaN y fracciones; presets conservan fases existentes y smoke usa configuración menor explícita.
- [ ] Repetir pruebas y type-check; comprobar ejecución de tests compilados con módulos JS copiados en dist/load. Añadir documentación de columnas/exit codes en load/README.md.
- [ ] Commit `fix(api): make load generation bounded and reproducible`, con pruebas y docs; actualizar tasks/verify-report.

## T2 — S2: stack sintético con preflight y limpieza propia

**Files:** crear `load/{compose.load.yaml,seed.mjs,stub.mjs,run.mjs,README.md}`; modificar driver/tests T1, Makefile y Dockerfile solo para soportar estos ficheros.
**Interfaces:** `node load/run.mjs smoke|baseline` administra un proyecto Compose único por ejecución; manifiesto efímero privado compartido por seed y driver contiene runId y tokens sintéticos; `/fixture/identity` del stub devuelve runId. Ningún secreto se imprime. `make load-api-smoke` y `make load-api-baseline` delegan al runner.

- [ ] Añadir tests `external target and redirects are rejected before workload`, `missing isolation marker fails preflight`, `fixture identity mismatch fails closed`, `cleanup never targets another compose project`, `invalid HTTP payload cannot count as useful`. Ejecutar focused T1 y observar RED.
- [ ] Implementar red Compose internal sin puertos públicos y volúmenes propios, sin env_file/.env heredado; procesos seed/stub/api reciben solo configuración sintética explícita. Usar proyecto distinto de looro-smoke/api persistente y cleanup en SIGINT/SIGTERM/error únicamente del proyecto retenido por esta ejecución.
- [ ] Seed mediante SQL y utilidades existentes de auth/ledger a través de la imagen compilada, con cuentas desbloqueadas, saldo y sesiones sintéticas válidas. Stub ofrece respuesta metered de fixtures y modos fast/slow/hang; identidad/manifest demuestra que API y driver operan sobre esta ejecución. Verificar API autenticada + identidad del stub antes de cualquier fase.
- [ ] Allowlist exacta de BASE: localhost, 127.0.0.1, [::1] y api-load; exigir modo aislado y manifest. Rechazar usuario/password URL, protocolos distintos de http, redirects y hostname ajeno. Fixture marker no crea bypass para hosts externos.
- [ ] Ejecutar tests, `docker compose -f services/api/load/compose.load.yaml config --quiet`, `make load-api-smoke`; documentar que build puede necesitar imágenes/cachés pero runtime no tiene salida externa. Si Docker no disponible, registrar pendiente, no saltar silenciosamente.
- [ ] Commit `test(api): add isolated capacity fixtures and smoke runner`.

## T3 — S3: señales de proceso y contrato de métricas proxy

**Files:** metrics.ts, observability.ts, tests/observability.test.ts, docs/observability.md, telemetry/grafana/dashboards/palique-overview.json.
**Interfaces:** ampliar PaliqueMetrics con processEventLoopDelay, processEventLoopUtilization, processMemory, proxyInFlight, proxyRejected, proxyProviderDuration, proxyUnknownAttempts. `startProcessMetrics(dependencies?)` retorna stop idempotente; dependencia de monitor/memory/clock inyectable, instalación desde initObservability y limpieza en shutdownObservability.

- [ ] Tests RED: monitores ausentes con OTEL off; enable/disable una vez; callbacks no duplicados tras restart; convertir delay nanosegundos a segundos; memoria bytes, utilización 0..1; instance id estable dentro de proceso, distinto entre procesos, override respetado; labels cerrados sin datos de cuenta.
- [ ] Usar monitorEventLoopDelay/performance.eventLoopUtilization/process.memoryUsage nativos. Nombres OTel: `palique.process.event_loop.delay` (s), `palique.process.event_loop.utilization`, `palique.process.memory` (By, state=rss|heap_used|heap_total); `palique.proxy.in_flight`, `palique.proxy.rejected` (reason=account|global), `palique.proxy.provider.duration` (s,outcome cerrado del diseño), `palique.proxy.unknown_attempts`. No cambiar métricas actuales.
- [ ] Medir delay p99 en callback y reset por export; CPU/event loop no se interpreta como latencia de proveedor. Documentar traducción Prometheus y comprobar representación de instance id en export real del stack cuando se ejecute baseline.
- [ ] Ejecutar `npx tsx --test tests/observability.test.ts`, type-check y `make obs-check` desde raíz; añadir paneles conservando los actuales. Alertas nuevas solo si tienen prueba de reglas y semántica clara, sin umbrales comerciales inventados.
- [ ] Commit `feat(api): expose capacity and process saturation signals`.

## T4 — S5: intentos durables y finalización idempotente

**Files:** nueva migrations/055_proxy_attempts.sql, src/proxy-attempts.ts, tests/proxy-attempts.test.ts; documentación docs/api-capacity.md.
**Interfaces:** `prepareProxyAttempt(db,{id,accountID,rateVersionID})`, `markProxyDispatched(db,id)`, `finishProxyAttempt(db,id,outcome)` para rejected/unknown y `settleProxyAttempt(db,{id,usage,estimateNano,costNano,settledNano})`. Liquidación usa tarifa persistida y referencia `proxy:<attempt UUID>`; devuelve metering previo o actual sin repetir appendEntry. Validar propietario/estado con fila bloqueada en transacción.

- [ ] Tests PostgreSQL RED para prepared→dispatched→settled, rejected/unknown, unicidad y transiciones inválidas; dos finalizaciones concurrentes producen una entrada ledger y proxy_usage; fallo SQL revierte también estado final; intento dispatched queda visible tras fallo postenvío. Añadir prueba migración sobre esquema histórico sin alterar filas de uso existentes.
- [ ] Añadir tabla con UUID PK, FKs cuenta/tarifa, estados CHECK y timestamps; índice de revisión para estados pendientes. Relacionar proxy_usage mediante attempt_id nullable UNIQUE para preservar filas históricas y ledger estable por UUID. No borrar intentos para corregir errores ni modificar ledger; permisos/retención siguen política financiera existente.
- [ ] Implementar actualizaciones condicionadas/row lock y finalización atómica con ledger+usage. Estados settled/rejected/unknown no se reabren automáticamente. prepared sin envío cancelado puede rechazarse; dispatched sin evidencia suficiente se conserva unknown o pendiente si falla persistencia.
- [ ] Ejecutar focused con PostgreSQL aislado mediante imagen/runner existente, type-check y suite `make test-api`. No dar GREEN si faltó TEST_DATABASE_URL. Documentar consulta de intentos antiguos y revisión manual sin autocharge ni retransmisión.
- [ ] Commit `feat(api): track durable proxy attempts and atomic settlement`.

## T5 — S4/S5: límites, cancelación, transporte y lifecycle

**Files:** token-proxy.ts, nuevo proxy-transport.ts, app.ts, main.ts, compose.yaml, tests/{proxy-capacity,proxy-cancellation}.test.ts y regresiones token-proxy.test.ts; docs/api-capacity.md y contrato proxy existente localizado antes del cambio.
**Interfaces:** extender config con maxInFlight/timeoutMs/maxResponseBytes opcionales en consumidores internos; `relay(accountID,body,{signal?}={})`; `TokenProxy.stop(): Promise<void>` deja de admitir, aborta transportes pendientes y espera liquidaciones; `readBoundedJSON(response,{signal,maxBytes})` consume stream limitado con reader.cancel en exceso/error.

- [ ] RED unitario sin BD para config: defaults/rangos/empty/fractions/maxTokens; cuota cuenta y global, adquisición síncrona, sin cupo fugado por excepción y precedencia account_busy. Tests con servicio/DB inyectados según seams mínimos necesarios, sin simular prueba de integridad SQL.
- [ ] RED integración HTTP+PG+stub: global entre cuentas; 503 proxy_busy+Retry-After:1, cuota429 previa, 400stream,402saldo, éxito/metering histórico; deadlines antes de enviar, headers/body colgados; error HTTP/transport/JSON/usage; sobrelímite sin Content-Length y payload tardío. Verificar saldo/ledger/usage/intento en cada resultado relevante.
- [ ] RED cancelación sobre socket HTTP real: cierre después de body entrante no cancela respuesta normal; disconnect antes de usage queda unknown; después de usage completa settlement; stop aborta transporte pero espera escrituras antes de db.end; ninguna respuesta/log filtra credenciales.
- [ ] Implementar cupos global/cuenta sin cola y señal combinada deadline/cliente/shutdown; comprobar abort alrededor de preparación SQL, no cancelar liquidación con usage conocido. Normalizar error externo502 y timeout504, limpiar timers/listeners/readers en finally. Invocar T4 y emitir métricas T3.
- [ ] Integrar respuesta close/aborted correctamente, Retry-After solo proxy_busy; conservar errores históricos. Configurar instancia proxy una vez en main y detener admisión/transporte ANTES de esperar app.close y antes de cerrar DB, evitando dependencia circular con handlers activos. Registrar lifecycle test del orden.
- [ ] Config Compose/docs/contrato: nuevas variables, errores, límite solo por proceso, SQL puede agotar sus propios plazos y unknown no garantiza recuperar coste. Preservar flujo no-streaming y políticas actuales de grace.
- [ ] Ejecutar todos focused, `npx tsc --noEmit`, `make test-api` y `make load-api-smoke`; guardar resultados. Commit `feat(api): bound proxy work and preserve uncertain outcomes`.

## T6 — S6: baseline, verificación de fase y documentación final

**Files:** tasks.md, verify-report.md, load/README.md, docs/api-capacity.md; evidencias ignoradas de esta fase.

- [ ] Baseline local acotada: warmup10s, lecturas30s, mixed30s y proxy20s; llegadas20/60/120 por segundo para HTTP, proxy5/s y pico300; repetir con proveedor lento y timeout. Tasas son estímulos del preset previo, no SLO ni capacidad estimada. Guardar señales de proceso/pool/proxy y metadatos sin secretos; registrar dropped y rechazos como trabajo no servido.
- [ ] Revisar baseline antes/después con mismo hardware y fixtures cuando sea técnicamente comparable. Para driver antiguo defectuoso usar medición corregida sobre runtime baseline en copia aislada, no comparar porcentajes del reporte roto; si no se dispone de esa comparación declararlo explícitamente.
- [ ] Ejecutar suite API completa y obs-check; separar de medición de carga para no competir por CPU/BD. Cualquier fallo del entorno se registra con comando y estado pendiente, sin inventar PASS.
- [ ] Verificador independiente lee diseño+spec+plan, baseline7160b65 y diff completo, verifica S1–S6, contratos previos, persistencia y limpieza. Probes en copia temporal fuera del workspace; sin providers remotos. Hallazgo reproducible incorpora regresión RED→GREEN y recheck acotado.
- [ ] Actualizar informe: qué pasó/no pasó, resultados locales, límites conocidos, recomendación de siguiente fase solo por evidencia y Risk: concurrencia/contrato/contabilidad. Commit `docs(api): record capacity validation and remaining limits`.

## Autorrevisión del plan

Cobertura: S1 T1; S2 T2; S3 T3+T5; S4 T5; S5 T4+T5; S6 T0+T6. Cinco condiciones de Review Focus tienen pruebas asignadas. Sin dependencias nuevas; migración finalización antes de activar transporte acotado. Señales T3 y driver T1 son entregas independientes; T5 depende de T3/T4 y T6 del conjunto. No hay aprobación de capacidad por aprobar este plan.

## Estado de ejecución

Plan aprobado mediante «si continua»; ejecución directa con revisor final independiente. T0–T5 implementados en commits c255b03, a58ab51, aa5a67e, 30105cf y corrección5a5126b. Evidencias y estado de validación final en tasks.md y verify-report.md; las casillas específicas se cierran solo con prueba observada.
