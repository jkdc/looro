# Resource Envelope and Mobile API Demand Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Ejecución directa ya elegida; una revisión independiente final, sin escritores por tarea.

**Goal:** Medir capacidad útil y consumo del conjunto bajo presupuestos compartidos y relacionarlo después con el tráfico móvil verificable.
**Architecture:** Extender el harness aislado, manteniendo driver/runner antiguos compatibles. Separar perfiles, cargas, muestreo y fallos; mantener cambios de producto mínimos y basados en resultados.
**Tech Stack:** Node >=22.19, node:test/tsx, Fastify/pg existentes, PostgreSQL17, Docker Compose, Caddy y stack OTel existentes.
**Spec:** `design.md` aprobado; `specs/resource-capacity/spec.md` S1–S6.

## Global Constraints
Rama `feat/api-performance-hardening`, sin worktrees/Gentle AI/remoto/push/PR/merge/despliegue.
Fixtures/proveedores sintéticos; ninguna credencial real ni `.env` heredado; red interna y limpieza solo del proyecto propio.
Presupuestos agregados 1 vCPU/4 GB, 2 vCPU/8 GB, 4 vCPU/12 GB; no promesa de autoscaling o CPU exclusiva.
Código/tests/comentarios English; SDD/informes español; evidencia raw ignorada en este change.
Aplicar RED→GREEN a comportamiento determinista nuevo y regresiones previas de helpers compartidos. Documentación y medición no tienen RED artificial.
Runner local sin instalar dependencias nuevas por anticipación. Herramientas faltantes o pruebas incompletas se reportan, no se inventa PASS.

## Review Focus
- Cuota por contenedor no es cuota total: servicios activos omitidos o migraciones simultáneas no deben falsear el perfil (T1).
- Preflight y warmup no deben reclamar grants de la cohorte nueva; replays no equivalen a primeras concesiones (T2).
- HTTP202 de progreso es útil solo con contrato correcto; un200 malformed o redirect nunca lo es; TLS no desactiva verificación global (T2).
- Soak no debe alargar varias fases ni acumular una hora de muestras en RAM; señal/cancelación siempre limpia solo sus recursos (T3).
- Reinicio y timeout pueden dejar incertidumbre legítima: reconciliar sin inventar settled, usage o débito; fallo previo no se atribuye al candidato (T3/T4).

---
## Task 1: Presupuestos reproducibles y stack representativo (S1/S5)

**Files:** crear `services/api/load/resource-profiles.mjs`, `compose.resources.yaml`, `Caddyfile.load`, `telemetry/*`, `resource-sampler.mjs`; tests `services/api/tests/load-resource-profiles.test.ts`, `load-resource-sampler.test.ts`. Modificar `.gitignore`, `services/api/load/README.md`.
**Interfaces:** `resourceProfile(name)` devuelve `{name,cpuLimit,memoryLimitBytes,services}` con nombres cerrados economy/standard/peak; cada servicio `{cpus,memoryBytes}`. `validateResourceBudget(profile,activeServices)` rechaza desconocidos/omisos/sumas excedidas. `summarizeContainer(inspect,stats,cgroup)` devuelve cuotas/uso/OOM/restarts y null explícito para dato no disponible, nunca cero ficticio.
- [ ] Type-check inicial `cd services/api && npx tsc --noEmit` y registrar estado/base `2b61585`.
- [ ] RED: nombres inválidos, CPU/RAM excedidas, servicio omitido, resourceLimit efectivo distinto y métrica ausente no pasan como perfil verificado. Verdes históricos no sustituyen estas pruebas.
- [ ] Implementar catálogo y reparto explícito en memoria/CPU, validado por suma. La asignación interna se fija en el código y se documenta, sin autorizar otro presupuesto. Servicios transitorios se ejecutan antes de temporizar y se enumeran aparte; no solaparlos con carga.
- [ ] Implementar sampler de identidades de contenedores del proyecto propio, con exec inyectado; registrar host y driver/stub separados. Sin escaneo de otros proyectos ni modificación global Docker. Prueba rechazo de IDs ajenos y unidades correctas CPU/bytes.
- [ ] Compose de ensayo añade edge/TLS interno y collector/Prometheus/Tempo/alertas reales con cuotas; dashboards opcionales apagados enumerados, no admin-service ficticio. Sin puertos publicados ni ACME/red externa. Reutilizar versiones/configs actuales adaptadas a nombres load; registrar retención.
- [ ] GREEN: `cd services/api && node --import tsx --test tests/load-resource-profiles.test.ts tests/load-resource-sampler.test.ts`; validar Compose renderizado sin secretos y límites Docker inspect en arranque efímero. Commit `feat(load): add aggregate resource budget profiles`.

## Task 2: Cohortes, contratos HTTP y TLS de carga (S2/S3)

**Files:** crear `services/api/load/workloads.mjs`, tests `services/api/tests/load-workloads.test.ts`; modificar `driver.mjs`, `seed.mjs`, tests `load-driver.test.ts`, README.
**Interfaces:** `workloadTask(kind,index,manifest)` devuelve `{method,path,token?,body?}` para perfiles cerrados claimed/new/guest/active/progress/exams; manifest mantiene tokens/runId/proxyToken previos y añade cohortes/expectativas explícitas. `usefulResult(task,status,payload)` valida método/ruta/status/shape; `usefulPayload` previo conserva contrato de callers existentes.
- [ ] RED: sincronización POST202 válida, malformed202 no útil, GET200 progreso/examen con shape real, redirects/badshape/HTTP errors no útiles. Preservar todos los paths históricos. Basar payload V1 progreso en `progress-http.test.ts` y contrato real; no activar V2 para el benchmark.
- [ ] RED: preflight usa cuenta dedicada; new sin grant no aparece en warmup; cada fase new usa cohorte fresca y expectativas por manifest, no count200 fijo. Datos se preparan fuera de fase; no emitir tokens al log.
- [ ] Implementar cohortes registradas/sesiones con vida suficiente y datos deterministas; definir protocolo de creación para ráfaga de clientes ya autenticados sin confundirlo con login real Google/Apple. Añadir cohortes/plataformas de intento de examen solo después de leer sus contratos; contenido público/hybrid usa fallback real, no mock del handler.
- [ ] Añadir únicamente `https://caddy-load` a destino de ensayo; conservar allowlist HTTP anterior, impedir credenciales/path/redirect remoto. Inyectar CA de Caddy al proceso driver, sin `NODE_TLS_REJECT_UNAUTHORIZED=0`; preflight de identidad/proveedor permanece obligatorio. Añadir test negativa de CA no confiable y positiva con CA del fixture.
- [ ] Comprobar progreso persistido por cuenta, readback y grants únicos/saldo=diario. GREEN `cd services/api && node --import tsx --test tests/load-driver.test.ts tests/load-workloads.test.ts` y smoke HTTP/TLS con PostgreSQL real. Commit `feat(load): model fresh grants and mobile API flows`.

## Task 3: Runner acotado, soak y fallos controlados (S1/S4)

**Files:** modificar `services/api/src/db.ts`, crear `services/api/tests/database-recovery.test.ts` (hallazgo reproducido S4; ver ledger); crear `services/api/load/resource-run.mjs`, `resource-workload.mjs`, `faults.mjs`, tests `services/api/tests/load-resource-runner.test.ts`, `load-faults.test.ts`; modificar README/package.json solo para comando de harness documentado, sin dependencias nuevas.
**Interfaces:** CLI `node load/resource-run.mjs <economy|standard|peak> <smoke|steps|soak|faults>`. `parseResourceRun(args)` devuelve perfil/preset cerrado y configuración; `faultAction(kind,ownedContainers)` solo slow/hang/http-error/restart-api/restart-db. `resource-workload.mjs` consume manifest y perfiles de T1/T2, emite ventanas y resumen de aceptación, no inicia Docker al importarse.
- [ ] RED: CLI inválida no ejecuta Docker; no hereda secretos/destinos del host; SIGINT/fallo startup/fallo integridad/fallo cleanup terminan no-cero sin eliminar recursos ajenos. Tests por exec inyectado, no sleeps reales.
- [ ] RED: soak es una fase lógica de3600 s compuesta por60 ventanas de 60 s, sin warmup repetido ni retener muestras entre ventanas. No promediar percentiles como p95 global; presentar ventanas y totales sumables. Fallo de ventana no desaparece de resumen.
- [ ] RED: faults rechaza contenedores ajenos/nombres desconocidos y señales fuera de dominio. Proveedor HTTP error no se cuenta como útil; restart no seed/migra otra vez inadvertidamente. Fijar timeout de recuperación y conservar estado incierto.
- [ ] Implementar runner separado para preservar smoke/baseline antiguo; saneamiento env y proyecto único. Configuración de tasa/duración controlada sí llega a driver; step ladder50/100/200/400/800 con parada al primer incumplimiento exploratorio previo (≥99 % útiles/oferta,≥98 % lanzadas,p95 éxito≤300ms,p99 total≤1000ms), rechazo de política separado de saturación. Soak al último escalón que cumpla con margen, no asumir 200/s.
- [ ] GREEN `cd services/api && node --import tsx --test tests/load-resource-*.test.ts tests/load-workloads.test.ts tests/load-faults.test.ts tests/load-driver.test.ts`; integración smoke por perfil con límites/limpieza/contabilidad observadas. Documentar coste estimado y commit `feat(load): add bounded endurance and recovery runs`.

## Task 4: Comparación, carga prolongada y decisión sobre CASE/Redis (S2/S4/S5)

**Files:** evidencia ignorada `openspec/changes/api-resource-envelope/evidence/*`; crear `informe-capacidad-recursos-api-2026-10-30.md`, `verify-report.md`; modificar `services/api/src/subscriptions.ts` y tests `free-drip-fast-path.test.ts` solo si la decisión medida exige ajuste/reversión.
**Consumes:** harness T1–T3, baseline `aa51066`, candidato `2b61585`; imágenes congeladas y misma fixture. **Produces:** tabla por presupuesto y decisión explícita de runtime, evidencia financiera y Redis.
- [ ] Congelar variantes sin checkout/worktree ni copiar artefactos generados sobre workspace: construir contexto temporal de la revisión seleccionada y registrar SHA/digest. Tres pares intercalados para reclaimed/guest/active/new bajo perfilstandard. Sin suite/profiler concurrente.
- [ ] Evaluar ahorro y regresión por perfil, no por SQL solo. Si la ganancia no se reproduce, restaurar precheck anterior conservando tests financieros; tests de no-Tx guest describen el tradeoff elegido y se actualizan conscientemente, no se silencian fallos. Cualquier alternativa nueva requiere diseño técnico acotado/TDD, no optimización especulativa.
- [ ] Ejecutar escalera por perfil con TLS y control directo; luego un soak ≥1h con margen en perfil recomendado. Dar antes pronóstico aproximado1–2 h de carga total más builds/suites; medir uso/disco/red, expiraciónsesiones/crecimiento y generador. Ventanas fuera de criterio se reportan; no repetir ensayo hasta que parezca limpio.
- [ ] Ejecutar faults en stack separado con lecturas concurrentes y recuperación a baja tasa; comprobar grants/entradas/saldos, intentos/usage y incertidumbre. Documentar error externo legítimo sin inventar liquidación.
- [ ] Redis: revisar consultas/bytes/latencia cacheable. Si no hay cuello dominante demostrado, concluir no incorporarlo. Si lo hay, presentar diseño específico antes de dependencia de producción; mantener evidencia/pending explícitos, no bloquear informe de perfiles por no instalar cache.
- [ ] Verificar typecheck, focused y `make test-api` si cambió runtime o harness incluido en suite; `make obs-check` para configs de observabilidad modificadas. No correrlas durante medición. Registrar fallos/preexistentes y commit con tests/docs del resultado.

## Task 5: Demanda móvil verificable y contraste (S6)

**Files:** crear `informe-uso-api-movil-2026-10-30.md`; modificar `workloads.mjs`/sus tests solo si hace falta representar mezcla descubierta, no código móvil.
**Consumes:** resultadosT4; entrada CodeGraph Android APIClient/HostedAPIClient/ProgressClient/GuestMinuteClient/AppViewModel y iOS APIClient/ProgressClient. **Produces:** inventario con path:line y escenarios explícitos, no telemetría inventada.
- [ ] Después de T4, mapear llamadas y callers Android/iOS, estado local/caché/disparadores, arranque/login/sync/exámenes/voz/helpers/pagos y jobs. Contrastar diferencias de plataformas y rutas no usadas; documentación de intención no prueba de llamada actual.
- [ ] Contar peticiones por acción/sesión y batching/reintento/polling, respuestas/payloads sin PII. Crear escenarios ligero/típico/intensivo con supuestos visibles; bytes porfixture cuando medibles, límites del código cuando no. Distinguir registro/activos/simultáneos; fórmulas demandantes y rangos, no usuarioNormal exacto sin observación.
- [ ] Comparar mezcla del ensayo con inventario; si difiere significativamente añadir testRED y perfil mínimoGREEN, ejecutar solo smoke+validación temporal acotada por presupuesto relevante. No repetir soak completo por defecto.
- [ ] Documentar candidatos de ahorro por frecuencia y datos, no modificar app sin petición específica; commit `docs(performance): model mobile API demand from client flows`.

### Reapertura acotada de T5 — perfil de sesión Android

**Motivo:** el usuario autorizó derivar la mezcla desde las rutas Android durante la fase de desarrollo, en lugar de esperar telemetría real. El informe ya documenta una sesión asumida de siete llamadas, pero el harness actual solo recorre seis rutas y omite historial y lote de uso.

**Files:** modificar `services/api/load/workloads.mjs`, `resource-workload.mjs`, `resource-run.mjs`, `tests/load-workloads.test.ts`, `tests/load-resource-runner.test.ts`, `tests/support/load-resource-probe.mjs`, `services/api/load/README.md` y `informe-uso-api-movil-2026-10-30.md`.
- [x] RED: probar la secuencia de siete rutas: `GET /v1/auth/providers`, `GET /v1/account`, `GET /v1/minutes`, `GET /v1/level-test-attempts?limit=10`, `POST /v1/progress`, `GET /v1/level-tests/hybrid?language=en&level=A1`, `POST /v1/usage/events`; exigir contratos válidos para lista vacía de historial y lote de uso aceptado.
- [x] GREEN: añadir `android-session-smoke` separado de `mobile-smoke`, 15 s a 50 RPS por defecto, tareas cíclicas con una llamada por acción, payload sintético estable, cuenta de fixture y validación de respuesta.
- [x] Integración: ejecutar el probe de PostgreSQL/TLS sobre las siete acciones y verificar un evento usage persistido. RED detectó que el probe preexistente no se invocaba y que el path publicado no existía en la imagen final; corregidos llamada, override HTTPS y ruta `dist/tests/support`. No incluir compra/guest/voz opcionales; no inferir frecuencias de producción.
- [x] Documentación: conservar medidas históricas de `mobile-smoke`; documentar perfil y resultados asumidos de Android. Tests focalizados 18/18, typecheck, smoke TLS 750/750 y probe PASS; `make test-api` 839/839 PASS. Commit `a96d81f` (`feat(load): add Android session route profile`).

## Task 6: Revisión independiente y cierre operativo (S1–S6)

**Files:** `tasks.md`, `verify-report.md`, informesT4/T5, referencias históricas en informe-capacidad/diagnostico y `api-performance-hardening/tasks.md`; guía `services/api/docs/api-capacity.md` si corresponde.
- [ ] Reconciliar referencias históricas que dicen pendiente conservando sus resultados/base; tabla componentes encendidos/apagados y configuración Coolify solo orientativa no aplicada. No afirmar ahorro de panel estático ni cap automático sin prueba.
- [ ] Revisión independiente de todo el change sobre baseline `2b61585`: cuotas agregadas/seguridad del runner, TLS, cohortes nuevas, efectos financieros, datos del soak, recuperación, trazabilidad móvil. Probes aislados fuera de workspace; no usar Gentle AI. Una corrección acotada con regresiones por hallazgo y recheck dirigido.
- [ ] Verificación final de comandos aplicables, `git diff --check`, lectura estructural de reportes y gitstatus. Reportar omitidos/fallos honestamente; sin más cambios tras la prueba final. Archivar ledger propio en evidencia y limpiar solo scratch y recursos creados.
- [ ] Commit local cierre y resumen Engram; dejar rama tal cual. Riesgo: infraestructura/configuración y concurrencia; también financiero si se toca concesión. Sin push/despliegue ni integración remota.

## Autorrevisión del plan
Cobertura: T1 S1/S5; T2 S2/S3; T3 S1/S4; T4 S2/S4/S5; T5 S6; T6 todos. Interfaces y destinos cerrados coherentes; runners antiguos conservados. Lectura móvil se programa después de medición como pidió el usuario. Tests deterministas antes de cambios; medición/documentación con excepción RED explícita. Coste de soak se anuncia antes de ejecutar. Redis permanece condicional; no es requisito automático de instalación.

## Log

- L1: «continua» (2026-10-31). Reanuda el trabajo a partir del perfil sintético Android propuesto.
- L2: «estamos en fase de desarrollo por lo que tendremos métricas de prueba, si quieres saber qué rutas usan la app moviles puedes analizar el proyecto android y ver a qué rutas de la api llama y para qué y de ahí sacar lo que necesites» (2026-10-31). Se deriva un perfil desde el flujo de código, sin esperar datos de producción; las frecuencias se mantienen explícitamente como supuesto de prueba.
