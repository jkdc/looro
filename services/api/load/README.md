# Pruebas locales de capacidad

El driver solo se ejecutará mediante el runner aislado de esta fase, no contra el stack persistente. Importar `driver.mjs` o `loadtest.mjs` no ejecuta tráfico. No usar tokens.txt ni proveedores reales.

## Métricas

Cada fase informa ofrecidas, lanzadas, descartadas por falta de capacidad/retraso, completadas en ventana y durante drenaje, útiles, status y fallos de transporte. La tasa útil usa el tiempo real incluido drenaje; los percentiles all y success se separan y una muestra vacía produce null. Un 429/402/403/503 nunca es trabajo útil. Las llegadas perdidas no se recuperan en ráfaga.

Defaults: 100 en vuelo; timeout HTTP 5000 ms y proxy 35000 ms. Límites configurables del runner: NETWORKS 1–100000; en vuelo 1–10000; timeout 1–300000 ms; duración 1–7200 s; tasa 1–100000. Datos sintéticos y resultados son señales locales, no capacidad de producción.

Pruebas sin PostgreSQL: `cd services/api && npm run test:load-driver`.

## Ejecución

Desde la raíz: `make load-api-smoke` o `make load-api-baseline`. Los comandos ejecutan `run.mjs` en un contenedor de herramientas separado que incluye Node, Git y Docker Compose; el repositorio se monta en solo lectura y el único acceso del contenedor al host es el socket Docker local autorizado. La imagen de producción de la API no incluye Git ni Docker. Se necesita Docker local y las imágenes/dependencias de build disponibles; la red del runtime es interna. Cada ejecución genera un proyecto único y elimina solo sus recursos al salir. No hay `KEEP` ni destino remoto. `run.mjs` admite únicamente smoke/baseline, no tokens o URL externa por argv. `make load-api-runner-test` comprueba el aislamiento y el cableado de ambos comandos.

El proveedor del proxy es el stub local: la API resuelve proveedor/modelo/clave por llamada (`resolveLlmConfig`) y su fallback de entorno (`LEVELTEST_LLM_URL`) apunta a `http://127.0.0.1:8081/v1`, destino que la política de salida solo permite por ser loopback. Para que 127.0.0.1 llegue al stub, `api-load` comparte el namespace de red de `stub-load`; por eso la API no tiene nombre DNS propio y el driver la alcanza en `stub-load:8080`.

Seed crea 200 cuentas sintéticas con wallet, unlock y sesión y escribe manifest privado en volumen efímero. Preflight comprueba UUID compartido con stub, lectura autenticada y completion metered de identidad, antes de ofrecer carga. El client ID Google de fixtures es una audiencia sintética de configuración; no se realiza intercambio de identidad ni llamada a Google.

El entrypoint interno admite NETWORKS (200), MAX_IN_FLIGHT (100), TIMEOUT_MS (5000), DURATION_S (smoke2/baseline30) y TARGET_RPS (smoke4/baseline60). Proxy usa deadline35000ms. El runner usa configuración controlada; no reenvía estas variables del host. Fallos de preflight salen2; una fase sin éxitos o con fallos de transporte sale1 tras resumen. Rechazos y dropped siempre aparecen aunque no causen por sí mismos fallo del baseline exploratorio.

El preset mantiene warmup, lecturas, challenge, mixed, pico300 y proxy5/s. La suma es limitada; el pico conserva el máximo en vuelo100 y puede descartar oferta. La telemetría OTLP del baseline se imprime después de fases, sin tokens ni cuerpos. Ejecutar suite funcional separadamente para no distorsionar medición. No usar el ejemplo histórico contra api_default ni `tokens.txt`.

La fase `public-reads` es explícita y no lleva token aunque existan fixtures autenticadas; recorre pricing, productos con provider=stripe y healthz. Escenarios adicionales: `node services/api/load/run.mjs smoke slow` o `smoke hang` (también baseline). El preflight usa proveedor rápido; solo después activa degradación. En ambos escenarios el deadline API es1000ms y el stub tarda2000ms o no responde. Un exit1 por ausencia de completions en la fase proxy es resultado esperado de esa exploración, no evidencia de éxito funcional; comprobar 504, unknown, cupos liberados y lecturas saludables.

## Presupuestos conjuntos (ensayo, no configuración aplicada en Coolify)

`resource-profiles.mjs` define economy (1 vCPU/4 GiB), standard (2/8) y peak (4/12). El reparto actual `telemetry-db-balanced-v4` asigna en economy CPU API0,40; PostgreSQL0,25; Caddy0,10; collector0,06; Prometheus0,05; Tempo0,10; Alertmanager0,02; webhook0,02. La memoria es 1024/1536/128/256/256/768/64/64 MiB (4096 MiB); standard multiplica ambos por2 y peak multiplica CPUpor4 y RAMpor3. Son límites duros por componente, no reservas, préstamo automático ni disponibilidad garantizada del servidor compartido.

`compose.resources.yaml` se superpone al Compose sintético existente e incorpora Caddy/TLS interno y observabilidad real, sin puertos públicos. Grafana y panel estático están fuera del ensayo; no hay contenedor admin independiente que apagar. Driver/stub son carga externa y se contabilizan aparte. Migración/seed terminan antes de medir. El volumen temporal de Tempo usa root solo dentro de este fixture para inicializar su almacenamiento; no modifica permisos ni configuración de producción.

El sampler valida proyecto/servicio y cuotas efectivas. Si un contenedor distroless no permite leer `cpu.stat`, reporta throttling no disponible, no cero; los demás datos provienen de Docker. Los valores observados no certifican NVMe, ancho de banda ni vCPU del servidor destino. Retención Prometheus de referencia: 90 días/2 GB; su crecimiento real se mide antes de dimensionar producción.

TLS: el contenedor transitorio `trust-load` copia exclusivamente la CA pública a `load_trust`, montada en solo lectura por el driver. El driver no monta el almacenamiento privado de Caddy, no necesita root y no desactiva verificación de certificados. Ese contenedor debe terminar antes de empezar la fase medida.

Cohortes resource: 1201 cuentas por defecto (probe1, libres180, guest10, activos10, nuevas1000); el modo anterior sigue creando200 cuentas. Preflight usa probe y no consume nuevas. `workloadTask('new',i,manifest)` no recicla al agotarse; preparar otro fixture para otra ráfaga inicial. Progreso V1 exige HTTP202/merged correcto y examen híbrido usa contenido real; rechazos/errores no son útiles. Bytes agregados son solo cuerpos. Las sesiones resource duran8h para soportar ensayo prolongado, no son ajustes a sesiones productivas.

Construcción: `api-load` solo refiere una imagen. El servicio con `build` es `migrate-load`: construir ese servicio y registrar digest; un exit0 de `compose build api-load` no demuestra una imagen actualizada. El probe de integración está en `tests/support/load-resource-probe.mjs` y se ejecuta dentro del driver sintético tras inicializar la confianza pública TLS.

## Runner mantenido por presupuesto

Desde `services/api`: `npm run load:resources -- economy smoke`, `standard steps`, `peak smoke` o `standard faults`. Ayuda: `node load/resource-run.mjs --help`. Soak: `node load/resource-run.mjs PROFILE soak RATE`; RATE1–1000 es explícita y debe elegirse tras la escalera con margen, no copiar un supuesto200/s. Son 60 ventanas de60s, una sola preparación/warmup y sin retener todas las muestras de una hora ni promediar percentiles.

Para validar rutas del cliente móvil en un flujo sintético acotado: `npm run load:resources -- standard mobile-smoke`. Envía 50 RPS durante15s a un ciclo de seis requests: providers, perfil autenticado, saldo, upload/lectura de progreso y catálogo híbrido. Son pesos iguales por acción dentro de un escenario hipotético, no frecuencias observadas ni capacidad móvil certificada. El preset comprueba contratos de respuesta y contabilidad con PostgreSQL real; no reemplaza el soak de una hora ni debe usarse para extrapolar usuarios simultáneos.

Para una mezcla adicional derivada del flujo Android: `npm run load:resources -- standard android-session-smoke`. Envía 50 RPS durante15s a un ciclo de siete acciones asumidas: providers, perfil, saldo, una página de historial, subida de progreso pendiente, examen híbrido y un lote de uso de un evento. El smoke valida shape y persistencia del evento en el stack PostgreSQL/TLS aislado. Una llamada por acción es una mezcla de rutas elegida para la prueba, no frecuencia observada por sesión ni un usuario típico; no incorpora historial no vacío, compras, guest, helpers alojados ni polling de cierre. El preset `mobile-smoke` y sus resultados históricos se conservan sin cambios.

La escalera mezcla70% saldo reclamado,10%POSTprogreso,10%GETprogreso y10%examen; comienza50/100/200/400/800por segundo y se detiene en la primera ventana que no cumple los criterios exploratorios (99%oferta útil,98%emitida,p95éxito≤300ms,p99total≤1000ms). `steps` sale0 tras informar una frontera no aprobada: NO significa que esa tasa se soporte. Smoke/soak sí fallan si no cumplen. La CLI rechaza destinos y flags arbitrarios y no hereda secretos del entorno.

Los residentes arrancan con `--wait`; el job transitorio de CA se arranca sin esa bandera y se espera su exit0 explícitamente. Tratar un job ya terminado como residente hace fallar Compose de forma dependiente de su velocidad. El sampler tolera que desaparezca un driver efímero, marcándolo unavailable, pero NO oculta fallos de muestreo de residentes. `RestartCount` es el contador del motor Docker, no una auditoría de reinicios manuales.

`faults` usa proveedor2s, colgado y HTTP429 (respuestas API504/504/502) con lecturas concurrentes, y reinicios propios de API/BD con tráfico de progreso. La disponibilidad durante reinicio se observa, no se presenta como SLO cumplido; la recuperación posterior y la contabilidad sí se exigen. El fixture espera2intentos unknown y1rejected, ninguno pendiente, solo el preflight liquidado; compara cada wallet con su diario y todo débito con usage settled. Diagnósticos de fallo son categorías cerradas, sin volcar objetos de conexión.

Los bytes del driver excluyen cabeceras/TLS y cuerpos incompletos en fallos de transporte; no son ancho de banda externo facturado. Ninguno de estos comandos toca Coolify ni certifica recursos físicos del destino.

Reparto `telemetry-headroom-v3`: el soak de 50/s hizo que Tempo alcanzara 512 MiB y terminara por OOM (`exit 137`) a los ~32 minutos, mientras Prometheus se mantuvo por debajo de 24 MiB. V3 aumenta Tempo a 0,10CPU/768MiB (economy), 0,20CPU/1536MiB (standard) y 0,40CPU/2304MiB (peak). La cuota CPU procede de PostgreSQL y Prometheus; la memoria procede de ambos y del margen agregado ya disponible, manteniendo techos conjuntos de 1/2/4CPU y 4/8/12GiB. Con V3 se completó un soak TLS sintético de una hora a 50/s en standard: 60/60 ventanas aceptadas, Tempo llegó a 1293MiB sin OOM/reinicios y la telemetría real permaneció disponible. El repartoV2 queda como antecedente, no se mezclan sus mediciones con V3.

El primer uso de minutos con 1000 cuentas a 50/s expuso otra diferencia: V2 había dado p95=35ms; V3 dio p95=404ms y 378ms en dos repeticiones, por encima de p95≤300ms. V4 devuelve PostgreSQL a 0,25CPU/1536MiB en economy y baja Caddy a 0,10CPU/128MiB; conserva el margen V3 de Tempo. Bajo V4, la misma ráfaga completó 1000/1000 concesiones: p95=65,67ms, p99=357,50ms, sin drops y con integridad financiera PASS. Además, V4 completó una hora a 50/s en standard (60/60 ventanas aceptadas); ver `informe-capacidad-recursos-api-2026-10-30.md` para tasas, límites y cautelas. Estos datos son del harness sintético, no una garantía de producción ni una escalera V4 por encima de 50/s.
