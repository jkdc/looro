# API Palique: capacidad ante picos y plan de mejora

**Fecha:** 30 de octubre de 2026. **Base analizada:** `3337294`.
**Alcance:** revisión estática del backend y su despliegue versionado; recomendaciones, sin implementar cambios de runtime.

## 1. Conclusión ejecutiva

**No hay evidencia suficiente para afirmar cuántos usuarios o peticiones simultáneas soporta hoy la API.** No se han ejecutado benchmarks en esta revisión ni inspeccionado producción. Dar una cifra sería inventarla. Sí hay límites verificables que requieren atención antes de prometer alta carga:

1. **La voz hospedada no escala añadiendo réplicas del proceso actual.** Exige un líder único con estado local y conexión PostgreSQL dedicada.
2. **Un pool de diez conexiones sirve a clientes, panel y trabajos internos.** Voz y generación pueden retener conexiones durante mucho tiempo; un panel con doce agregaciones compite con las peticiones de usuarios.
3. **El proxy de tokens carece de deadline explícito y admisión global.** Un proveedor lento puede acumular peticiones de cuentas diferentes.
4. **La supervisión de voz recorre sesiones secuencialmente.** Una llamada lenta puede retrasar cierres de otras sesiones.
5. **El generador de carga existente contiene defectos.** Sus resultados no deben usarse sin corregir y validar el propio instrumento.

**Recomendación:** conservar Node/Fastify/PostgreSQL; primero hacer fiable la medición y limitar trabajo pendiente. Después aislar workers y panel. Introducir cachés, réplicas o pooling externo solo cuando las métricas demuestren su necesidad. No se justifica una reescritura en otro lenguaje ni adoptar microservicios/Kubernetes por anticipación.

### Decisión práctica

| Situación | Acción proporcionada |
|---|---|
| Tráfico bajo, latencias estables y sin colas | Medir, corregir el instrumento de carga y preparar límites; no añadir infraestructura distribuida |
| Se anuncia campaña o pico de actividad | Ensayar la mezcla real, incluido login y voz, con margen y recuperación; limitar admisión al sobre validado |
| Cola PostgreSQL con jobs o panel activos | Aislar pools/procesos, optimizar las consultas que expliquen el coste y revisar presupuesto de conexiones |
| CPU/event loop saturado con BD saludable | Eliminar trabajo síncrono medido y escalar HTTP una vez separados los componentes stateful |
| Voz incumple plazos de cierre | Corregir watchdog y aislar propietario; particionar sesiones solo si un worker medido resulta insuficiente |
| Proveedor saturado | Rechazo temprano, cuotas y deadlines; más réplicas locales no aumentan la cuota externa |

## 2. Qué se ha comprobado y qué falta

Se revisaron arranque, rutas, pool, proxy, voz, helpers, generación, autenticación, agregados del panel, progreso/uso, migraciones relevantes, Compose, Caddy, observabilidad y driver de carga. Se utilizó CodeGraph para orientación y lectura directa para contrastar hallazgos.

**No se ejecutaron:** carga HTTP, suite de API, E2E, EXPLAIN sobre datos reales, fallos de infraestructura ni llamadas a proveedores. No se contactó ningún servidor remoto ni se leyeron credenciales. Los ficheros de tokens del driver no se utilizaron. No se modificó código ni configuración; este documento es el único artefacto nuevo de esta tarea.

No conocemos hardware/cuotas CPU y RAM efectivos, IOPS, latencia API↔BD, configuración PostgreSQL aplicada, tamaños y distribuciones de tablas, mezcla de tráfico, número de sesiones largas, cuotas de proveedores, overrides de despliegue ni SLO comercial. Compose es evidencia de la topología versionada, **no una descripción comprobada de producción**.

Las referencias `ruta:línea` corresponden a la base indicada; los impactos se clasifican como mecanismos observados e hipótesis de saturación, no como pérdidas de rendimiento medidas.

## 3. Arquitectura y límites actuales

### 3.1 Presupuesto PostgreSQL e interferencia entre cargas

**Evidencia:** `services/api/src/db.ts:4–18`: `max: 10`, adquisición hasta 5 s, `statement_timeout: 10 s`; transacciones con liberación en `finally`. `main.ts:76–80,117–176` distribuye el mismo pool a servicios y telemetría.

- `hosted-voice.ts:64–70` conserva un cliente para el advisory lock de líder. Con voz activa quedan como máximo nueve conexiones para otros consumidores.
- `level-tests-generation-jobs.ts:47–111` conserva otro cliente mientras ejecuta generación. No implica una transacción SQL abierta durante toda la llamada LLM, pero sí una conexión ocupada.
- `admin-metrics.ts:123–144` lanza doce agregaciones en paralelo por snapshot. No son doce conexiones permanentes: son doce operaciones compitiendo por el pool.
- `main.ts:178–185` dispara varias limpiezas cada quince minutos, sin un guard general de no solape. El reintento de guest links sí tiene guard propio (`:187–193`).

**Riesgo:** espera en cola, timeouts y degradación de endpoints baratos por trabajo ajeno. Aumentar el pool puede empeorar CPU/I/O/locks de PostgreSQL; diez no es por sí mismo una configuración incorrecta.

### 3.2 Escalado horizontal de voz

**Evidencia:** `hosted-voice.ts:65–85`: `pg_try_advisory_lock` de sesión con clave `app-hosted-voice-worker`; si ya existe líder, `start()` falla. `main.ts:127–130` espera ese arranque y el catch de `:197–200` trata el fallo como fallo de startup.

**Consecuencia:** replicar sin cambios el proceso configurado con voz puede impedir que arranquen las nuevas instancias. El estado de conexiones del proveedor tampoco se vuelve distribuido por añadir un balanceador. La recuperación actual prioriza cerrar sesiones facturadas y no abrir reemplazos automáticos: hay que conservar esa protección.

### 3.3 Watchdog y exclusión global

**Evidencia:** `hosted-voice.ts:88,408–439` ejecuta un tick periódico, consulta sesiones no cerradas y las procesa secuencialmente; el guard evita solape. `live-provider.ts:150–151` permite que un hangup espere hasta diez segundos.

**Hipótesis:** con muchas sesiones o un proveedor lento, la frecuencia nominal de un segundo no garantiza supervisión cada segundo. Medir retraso real de acción respecto del deadline, no únicamente duración de HTTP.

`hosted-voice.ts:109,217,252,303` y `hosted-helpers.ts:141,190,289,323` usan el lock transaccional global `app-hosted-funding-cap`: serializa tramos entre cuentas distintas. Es una protección financiera legítima, no un lock que deba eliminarse sin sustituto. Su coste depende de duración y frecuencia de las secciones críticas.

### 3.4 Proxy y dependencias lentas

**Evidencia:** `token-proxy.ts:63,80–87` limita concurrencia por cuenta mediante `Map` local; `:102–111` hace fetch y consume respuesta sin deadline explícito de aplicación ni máximo explícito de bytes en este camino. La cuota no es global ni compartida entre réplicas.

**Consecuencia:** muchas cuentas pueden mantener llamadas en vuelo y respuestas JSON en memoria. `requestTimeout` de Fastify (`app.ts:108–109`) no demuestra cancelación del fetch del handler. Este proxy **no hace streaming** y rechaza `stream: true` (`token-proxy.ts:73–77`); no deben confundirse sus necesidades con las de voz.

Helpers ya tiene timeout y límites: `main.ts:122–123` configura dos solicitudes por sesión y diez globales en su servicio. El proveedor de voz también tiene deadlines. No extender el hallazgo del proxy indiscriminadamente a todas las integraciones.

### 3.5 CPU, consultas y cachés

- **Login admin:** `admin-auth.ts:59,68,85–86` usa `scryptSync`; bloquea el event loop compartido. Un usuario inexistente también sigue trabajo criptográfico. Impacto a medir bajo intentos de login, incluso con rate limiting.
- **Panel:** las agregaciones globales del snapshot crecen con las tablas históricas; un índice no convierte un `count(*)` global exacto en una operación constante.
- **Contenido:** `app.ts:274–278` compone y calcula ETag antes de responder 304; `:291–315` consulta BD para generated/hybrid antes de 304. ETag ahorra transferencia, no necesariamente trabajo de servidor. La variante omitida puede seleccionarse aleatoriamente (`:281–285`).
- **Progreso y uso ya tienen optimizaciones:** UPSERT por lote y cursor keyset en `progress.ts:104–120,145–159`; ingestión de uso por lotes de hasta 100, con idempotencia (`usage.ts:5,101–115`). No hay motivo aquí para proponer reemplazar un supuesto N+1 no observado.
- **Índices existentes:** `migrations/030_user_progress.sql:18`, `028_usage_events.sql:19–21`, `003_hosted_sessions.sql:19` y `012_hosted_helpers.sql:37,108–109`. Verificar esquema final y planes antes de añadir duplicados. La paginación de progreso usa `COLLATE "C"`: un índice debe corresponder al filtro y orden reales.

### 3.6 Despliegue y observabilidad

`services/api/compose.yaml` declara una API, una BD y telemetría en el mismo stack, sin presupuestos CPU/RAM explícitos por servicio. `Caddyfile:1–9` envía el dominio API a `api:8080`; no hay una topología multiinstancia explícita validada en este análisis. Un host o PostgreSQL único sigue siendo un punto de fallo aunque la API gane throughput.

Ya existe RED HTTP y gauges del pool (`metrics.ts:20–27`, `observability.ts:104–110`), y alertas de errores/saturación. Son una base útil. Faltan en los instrumentos revisados adquisición de conexión, retraso de event loop, backlog/lag de voz y cola de admisión.

`observability.ts:22` utiliza `service.instance.id = api` por defecto: para réplicas se necesitan identidades únicas y comprobar que sobreviven al pipeline hasta Prometheus. `telemetry/otel-collector-config.yaml:7–25` solo declara batch como procesador; no un limitador de memoria. El volumen de trazas, logs por petición (`app.ts:244–247`) y almacenamiento de telemetría debe entrar en el presupuesto del host.

## 4. Qué significa «soportar alta carga»

No equivale a muchos usuarios registrados ni a devolver 429 rápidamente. Hay que publicar, por mezcla de tráfico y hardware, **throughput de operaciones útiles**, latencia, rechazos y corrección de datos.

- `λ_HTTP = usuarios activos × operaciones por usuario y segundo`, usando el comportamiento de la app, no toda la base registrada.
- `concurrencia media ≈ λ × duración media` en régimen estable. Ejemplo meramente aritmético: 20 llamadas/s a proveedor con media de 5 s implican unas 100 en vuelo, no 100 conexiones PostgreSQL permanentes.
- `demanda media de conexiones ≈ Σ(λ_operación × tiempo total de ocupación SQL por operación) + conexiones retenidas`. No usar tiempo HTTP completo como tiempo SQL salvo que realmente conserve cliente.
- Para un lock serial, `1 / duración media de sección crítica` da un techo idealizado, no la capacidad garantizada del sistema.
- Sesiones de voz requieren medir simultáneamente conexiones, eventos por sesión, llamadas de supervisión, ancho de banda, memoria y plazos financieros.

**Criterio:** encontrar el menor límite entre CPU/event loop, BD/locks/I/O, memoria, red y proveedor; mantener margen por debajo del punto donde p95/p99 y colas dejan de ser estables. Más CPU no arregla un lock global y más réplicas no arreglan una cuota externa.

## 5. Plan de mejoras e implementación

Prioridad propuesta: **P0 antes de certificar capacidad; P1 cuando haya saturación o antes de una campaña relevante; P2 solo tras demostrar que lo anterior no basta.** Son propuestas, no cambios ya autorizados ni implementados.

### M1 — P0: hacer fiable la prueba de carga

**Dónde:** `services/api/load/loadtest.mjs`, nuevas pruebas del driver y documentación de ejecución aislada.

Defectos observados:

1. `READS` contiene arrays de un solo path, pero `readTask` extrae `[m,p]` y devuelve `GET,p` (`:121–125`): sin tokens el path resulta `undefined`. Esa rama no prueba las lecturas anunciadas.
2. `NETWORKS` se declara pero no gobierna la rotación (`:29,35–37`). No permite reproducir la población de redes solicitada.
3. La selección de tareas usa `results.length` —completadas— en vez del número de lanzamientos (`:73`): varias pendientes repiten índice y sesgan cuentas/mezcla.
4. No hay límite explícito de en vuelo ni deadline por petición (`:43–50,69–84`). El generador puede saturarse y la fase puede tardar indefinidamente en drenar desde la perspectiva de aplicación.
5. El RPS divide por duración nominal sin incluir drenaje; los percentiles solo incluyen éxitos (`:55–61,83–84`). Los fallos lentos pueden quedar ocultos.

**Implementación:** separar planificación, transporte y reporte en funciones importables sin autoejecutar; corregir tuples/índice independiente; validar parámetros; distinguir tasa ofrecida, lanzada, completada en ventana y drenada, con duración real. Publicar latencias por clase de resultado y rechazos por causa; fijar deadline y límite del generador y reportar trabajo no lanzado, sin convertirlo en éxito. Validar respuestas y precondiciones, no solo status <400. Añadir proveedor falso local con latencia y errores configurables, fixtures de cuentas, saldos y tokens efímeros; impedir por defecto destinos/proveedores externos.

**Pruebas:** primero RED para cada defecto anterior; reloj y fetch inyectados; asegurar URLs concretas, distribución de cuentas/redes, conteo durante drenaje, timeout y reporte de errores. No ejecutar el ejemplo del encabezado contra el stack persistente por comodidad.

**Alternativa:** adoptar k6 con `constant-arrival-rate` o rampas de llegada y `dropped_iterations`; aporta planificación y métricas mantenidas, pero introduce una herramienta más. No basta con migrar el script sin corregir escenarios y assertions. [Modelo de llegadas k6](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/) y [métricas](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/).

### M2 — P0/P1: admisión acotada y deadlines por dependencia

**Cuándo:** antes de exponer el proxy a picos significativos; urgente si aumentan en vuelo o memoria mientras el proveedor se ralentiza.

**Dónde:** `token-proxy.ts`, integración de rutas en `app.ts`, configuración en `main.ts` y Compose.

- Añadir cupos globales y por proveedor además del de cuenta; adquirir antes de llamar al proveedor y liberar en `finally`. Cola cero o pequeña/acotada con deadline; no acumular peticiones sin límite.
- Configurar deadline total y máximo de respuesta. Enlazar cancelación de cliente con señal de salida y deadline; limitar lectura real del cuerpo, no confiar solo en Content-Length.
- Conservar códigos existentes; documentar cualquier nuevo código de sobrecarga y `Retry-After`. No cambiar contratos ni reintentar operaciones facturables a ciegas.
- Si se replica, emplear admisión distribuida con lease/expiración o un propietario por partición. Un `Map` por proceso multiplica la cuota al multiplicar procesos.
- Una desconexión no demuestra ausencia de coste externo: separar cancelación del transporte de la conciliación durable. Para reintentos, registrar operación/idempotencia y estado incierto; no duplicar liquidaciones ni liberar reservas prematuramente.

**Aceptación:** proveedor colgado/429/5xx/cuerpo excesivo/usage inválido, cliente desconectado y respuesta tardía no dejan slots fugados ni cobros duplicados; memoria y cola permanecen acotadas, y las rutas baratas conservan su SLO. Medir rechazos de capacidad separadamente de 5xx.

### M3 — P1: aislar HTTP, workers y panel; presupuestar conexiones

**Dónde:** `main.ts`, `db.ts`, entradas de proceso nuevas y Compose. Extraer primero funciones de construcción sin cambiar reglas de negocio.

1. Separar generación, retención y voz del proceso HTTP; iniciar solo los roles configurados. Pool dedicado para líder/locks de sesión y pools de trabajo presupuestados.
2. Parametrizar tamaño y plazos con validación de enteros/rangos y defaults conservadores; definir presupuesto de conexiones para el máximo número de réplicas, no solo las actuales.
3. Aplicar `N_HTTP × pool_HTTP + Σ pools_workers + panel + operaciones/migraciones + reserva < max_connections efectivo`. Contabilizar conexiones de líder dentro del pool correspondiente, sin sumarlas dos veces.
4. Mantener plazos SQL y añadir medición de adquisición y clasificación segura de timeout. `statement_timeout` limita una sentencia, no toda la transacción ni una operación externa.
5. Desplegar primero un singleton de voz independiente. Las rutas HTTP deben enviar comandos durables al propietario y recibir estado: desactivar voz en réplicas sin sustituir esa comunicación dejaría funcionalidad ausente.

**Aceptación:** una generación lenta y una consulta del panel no agotan el presupuesto HTTP; shutdown/reinicio libera clientes, no pierde comandos y conserva idempotencia. Probar dos instancias con PostgreSQL aislado y el proveedor falso.

**PgBouncer:** considerarlo solo por presión de conexiones. Los advisory locks de sesión del líder y generación no deben atravesar transaction pooling sin rediseño; usar conexiones directas dedicadas o pooling de sesión para ellos. No aumenta por sí mismo la capacidad de ejecutar consultas. [Dimensionamiento node-postgres](https://node-postgres.com/guides/pool-sizing).

### M4 — P1: watchdog de voz con trabajo acotado

**Dónde:** `hosted-voice.ts:408–439`, consultas de selección e integración de hangup.

Seleccionar sesiones que requieren acción con orden estable y lotes limitados; verificar índices con el predicado real. Separar cálculo de plazos y actualización durable de las llamadas externas. Ejecutar estas últimas con concurrencia limitada y timeout independiente, preservando orden por sesión e idempotencia. Usar tiempo autoritativo consistente para deadlines y registrar lag, duración del tick y backlog. No crear un `Promise.all` ilimitado sobre todas las sesiones.

**Aceptación:** un hangup lento no retrasa otras sesiones; bajo el máximo de sesiones validado se cumplen plazos. Simular caída entre actualización SQL y llamada externa, reintento, respuesta tardía y reinicio; una sesión no se cierra/liquida dos veces y el saldo reservado sigue siendo coherente.

### M5 — P1: reducir interferencia de CPU y panel

**Login:** sustituir `scryptSync` por `scrypt` asíncrono conservando formato, parámetros y comparación constante; limitar concurrencia y mantener trabajo uniforme para usuario inexistente mediante hash ficticio compatible. El pool de trabajo asíncrono también puede saturarse: medirlo, no aumentar hilos sin presupuesto. Probar hashes existentes, error, login concurrente y latencia de `/healthz` durante la carga.

**Panel:** cache de snapshot con TTL corto y single-flight, mostrando fecha de actualización; coalescer refrescos y limitar consultas concurrentes. Si las agregaciones dominan después, pasar a resúmenes incrementales reconciliables o vistas materializadas actualizadas fuera del hot path. No usar datos potencialmente atrasados para autorizar gasto ni ejecutar acciones financieras. Verificar expiración, error/reintento y que refrescos simultáneos no disparan doce consultas por usuario.

### M6 — P1/P2: SQL, retención y caché de contenido basada en evidencia

- Capturar consultas dominantes y planes con `pg_stat_statements` si se habilita expresamente, y `EXPLAIN (ANALYZE, BUFFERS)` en una copia con datos representativos. ANALYZE ejecuta la consulta: nunca aplicar escrituras de prueba sobre datos reales. Revisar filas leídas/devueltas, sorts, buffers, locks, bloat y autovacuum.
- Añadir solo índices que sirvan al filtro/orden medido, con migración nueva. Si se necesita `CREATE INDEX CONCURRENTLY`, comprobar primero compatibilidad con el runner transaccional y usar procedimiento específico; no meterlo sin más en una transacción. Verificar también coste de escritura y almacenamiento.
- Limpiar datos expirados por lotes estables y pausas en worker único, con guard de no solape, backlog y WAL observables; conservar política de retención y ledgers inmutables. Prueba con gran backlog, inserciones concurrentes y reinicio.
- Para catálogo público, cache acotada de payload serializado+ETag por idioma, nivel, semana, versión y variante. Invalidar publicación, retirada y cambio de semana; single-flight para misses y TTL de seguridad. Preservar selección aleatoria cuando se omite variante: resolver variante antes de seleccionar entrada o usar una URL explícita con contrato acordado, nunca fijar una variante por accidente.
- CDN solo para representaciones realmente públicas/versionadas; excluir cookies, sesiones, saldos y respuestas privadas. Medir hit ratio y trabajo evitado; probar 200/304, cambio de semana, publicación/retirada e invalidación multiinstancia. Un TTL sin retirada coherente puede seguir sirviendo un examen retirado.

### M7 — P2: quitar serialización global o particionar voz

**Disparador:** espera del lock global o lag de un worker sigue dominando tras acortar operaciones y aislar cargas.

Primero medir duración/espera por operación y sacar E/S y cálculos independientes de secciones críticas. Mantener locks por cuenta/sesión. Si sigue siendo insuficiente, sustituir la protección global por reserva atómica condicionada de presupuesto o asignaciones limitadas a particiones con reconciliación durable. Nunca autorizar gasto usando una caché de saldo.

Para voz distribuida: propietario persistido por sesión, lease con expiración, fencing token monotónico, comandos con clave idempotente y recuperación de propietario caído. Antes de un efecto externo comprobar propiedad vigente; tratar respuestas del propietario anterior como potencialmente obsoletas. El proveedor puede no soportar fencing: diseñar conciliación y cierre seguro, no prometer exactly-once externo por tener token SQL.

**Aceptación:** pruebas PostgreSQL concurrentes de cuentas distintas y misma cuenta, crash tras reserva, lease vencido, propietario antiguo que responde tarde y redelivery de comandos. Preservar límites globales y liquidación única. No quitar el advisory lock como supuesto arreglo rápido. [Semántica de locks PostgreSQL](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS).

### M8 — P1/P2: despliegue, señales y recuperación

Definir presupuestos CPU/RAM y retención de logs/telemetría; separar BD o almacenamiento de telemetría si se demuestra contención. Incorporar event-loop delay/utilization, RSS/heap/GC, CPU, adquisición de pool, tiempo de transacción/locks, conexiones activas, sesiones y lag, colas y latencia/cupo del proveedor. Labels de cardinalidad cerrada y sin PII.

Asignar identidad única a cada réplica y comprobar agregación de métricas. Añadir alertas de p95/p99 por clase, cola/lag y rechazos; la alarma actual de pool sostenido cinco minutos (`telemetry/alerts.yml:98–107`) no detecta por sí sola todos los picos breves. Configurar protección de memoria/buffers de telemetría acorde a la versión desplegada y muestreo de trazas si el volumen lo justifica; verificar que una caída del collector no degrada la API.

Después de extraer estado, balancear réplicas HTTP con readiness útil, drenaje y presupuesto de conexiones. Comprobar cancelación y terminación ordenada; no retirar una instancia que aún posee sesiones sin política explícita. HA de PostgreSQL, backups y restauración probada son una decisión adicional de disponibilidad, no una optimización automática de rendimiento. No enviar lecturas de saldo/revocación a una réplica con lag sin un contrato de consistencia.

## 6. Prueba de capacidad que permitirá responder con cifras

### Preparación reproducible

Entorno local/aislado o staging expresamente autorizado, con BD y credenciales sintéticas propias, proveedor falso sin salida externa y volúmenes desechables. No reutilizar `tokens.txt` ni datos persistentes existentes. Registrar SHA, hardware/cuotas, imágenes, configuración sin secretos, tamaño/edad de tablas, índices, cuentas y mezcla de escenarios. Ejecutar generador fuera del host de la API si se quiere aislar su coste, solo en un destino autorizado.

Medir primero endpoints individuales; después una mezcla con proporciones derivadas de telemetría o explícitamente etiquetadas como hipótesis. Evitar que `/healthz` o rechazos de autenticación dominen la muestra y oculten el coste real.

| Escenario | Qué debe demostrar |
|---|---|
| Lecturas autenticadas de minutos y progreso | Éxito útil, revocación vigente y latencia con población realista |
| Catálogo frío/caliente, ETag y rollover | Ahorro real de BD/CPU sin contaminación entre variantes |
| Login/challenge y redes compartidas | Coste criptográfico/SQL y admisión justa; separar límite por red de capacidad |
| Escrituras de progreso/uso y webhooks duplicados | Idempotencia, consistencia y throughput de escritura |
| Proxy con varias cuentas | En vuelo acotado con proveedor rápido, lento, 429 y colgado |
| Voz y helpers simultáneos | Lag de supervisión, cierre seguro, reservas y memoria por sesión |
| HTTP + generación + panel + retención | Aislamiento ante vecinos ruidosos y conexiones retenidas |
| Reinicio/cancelación/proveedor caído | Recuperación, ausencia de dobles cargos y backlog que vuelve a cero |

### Ejecución propuesta, pendiente

1. Smoke funcional del instrumento; confirmar datos/status esperados y stub activo.
2. Warmup de cinco minutos; incluir una ejecución separada en frío.
3. Escalones de llegada, inicialmente 10/25/50/100/200 req/s, diez minutos por escalón, **solo ejemplos de prueba, no capacidad estimada**. Detener al violar criterios; ajustar según hardware y mezcla.
4. Pico de 3× la carga validada durante 30–60 s y retorno al nivel anterior; comprobar recuperación de colas y latencias, no solo supervivencia del proceso.
5. Soak de 1–2 h al nivel sostenible elegido, más fallos controlados. Repetir el punto de operación al menos tres veces; registrar variabilidad.

Estas ejecuciones consumen CPU, disco y escrituras: antes de lanzarlas hay que reservar el entorno y acotar volúmenes/duración; no forman parte de esta revisión documental.

### Criterios de aprobación propuestos, no SLO vigentes

Acordar con producto y contrastar con el contrato de observabilidad existente antes de fijarlos: para HTTP interactivo sin espera LLM, p95 ≤300 ms, p99 ≤1 s y fallos inesperados <1%; para llamadas externas, SLO separado acorde al deadline y proveedor. Los 429 de capacidad cuentan como trabajo no servido, no como éxito; separar rechazos deliberados por política. Para voz, fijar explícitamente lag máximo tolerable de cierre desde las garantías financieras; no derivarlo del SLO HTTP.

Aprobar solo si se cumplen además: ninguna duplicación/corrupción contable, colas acotadas, sin crecimiento sostenido de memoria, vuelta al nivel previo tras pico y margen disponible de CPU/BD/conexiones. Como punto de partida conservador, operar un 30% por debajo del último escalón estable y revalidar en el entorno objetivo; no es garantía de capacidad.

**Resultado que debe conservarse:** ofrecidas/completadas/útiles/rechazadas por ruta y causa, p50/p95/p99 incluyendo errores aparte, CPU/RSS/event loop, pool/locks/IO/WAL, sesiones y lag, cuota externa, duración real y datos persistidos antes/después. Incluir artefactos y comando/configuración sin secretos. Una suite funcional verde no sustituye esta evidencia.

## 7. Orden de ejecución recomendado

| Orden | Entrega | Condición de salida |
|---|---|---|
| 1 | M1 + instrumentación mínima M8 | Driver con regresiones deterministas y métricas interpretables |
| 2 | Baseline aislado y M2 | Cuello identificado, deadlines/admisión probados y estados inciertos conciliables |
| 3 | M3, M4 y M5 según escenarios fallidos | Jobs/panel no degradan HTTP y voz cumple plazos |
| 4 | M6 si SQL/catálogo domina | Planes/hit ratio y prueba comparativa justifican la mejora |
| 5 | Réplicas HTTP y presupuesto global | Dos instancias conservan límites, contratos y recuperación |
| 6 | M7/HA solo si hace falta | Partición/failover probado sin perder invariantes financieras |

Cada implementación futura debe tener su change SDD, pruebas-first aplicables, comparación contra la misma baseline y rollback. Desplegar por flags/configuración conservadora cuando sea viable; probar convivencia de versiones antes de migrar propiedad de sesiones o persistencia. No hacer rollback de un esquema/ledger de forma destructiva; para cambios de datos usar migraciones compatibles y correcciones hacia delante.

## 8. Veredicto

**La base tecnológica puede evolucionar para cargas mayores, pero el servicio actual no tiene demostrada una capacidad concreta ni un escalado horizontal transparente de voz.** La prioridad no es cambiar de framework: es obtener una medición fiable, acotar trabajo pendiente, aislar cargas y conservar la corrección financiera bajo concurrencia y fallos. Las inversiones posteriores deben activarse por métricas, no por intuición.
