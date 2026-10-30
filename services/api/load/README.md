# Pruebas locales de capacidad

El driver solo se ejecutará mediante el runner aislado de esta fase, no contra el stack persistente. Importar `driver.mjs` o `loadtest.mjs` no ejecuta tráfico. No usar tokens.txt ni proveedores reales.

## Métricas

Cada fase informa ofrecidas, lanzadas, descartadas por falta de capacidad/retraso, completadas en ventana y durante drenaje, útiles, status y fallos de transporte. La tasa útil usa el tiempo real incluido drenaje; los percentiles all y success se separan y una muestra vacía produce null. Un 429/402/403/503 nunca es trabajo útil. Las llegadas perdidas no se recuperan en ráfaga.

Defaults: 100 en vuelo; timeout HTTP 5000 ms y proxy 35000 ms. Límites configurables del runner: NETWORKS 1–100000; en vuelo 1–10000; timeout 1–300000 ms; duración 1–7200 s; tasa 1–100000. Datos sintéticos y resultados son señales locales, no capacidad de producción.

Pruebas sin PostgreSQL: `cd services/api && npm run test:load-driver`.

## Ejecución

Desde la raíz: `make load-api-smoke` o `make load-api-baseline`. Se necesita Docker local y las imágenes/dependencias de build disponibles; la red del runtime es interna. Cada ejecución genera un proyecto único y elimina solo sus recursos al salir. No hay `KEEP` ni destino remoto. `run.mjs` admite únicamente smoke/baseline, no tokens o URL externa por argv.

Seed crea 200 cuentas sintéticas con wallet, unlock y sesión y escribe manifest privado en volumen efímero. Preflight comprueba UUID compartido con stub, lectura autenticada y completion metered de identidad, antes de ofrecer carga. El client ID Google de fixtures es una audiencia sintética de configuración; no se realiza intercambio de identidad ni llamada a Google.

El entrypoint interno admite NETWORKS (200), MAX_IN_FLIGHT (100), TIMEOUT_MS (5000), DURATION_S (smoke2/baseline30) y TARGET_RPS (smoke4/baseline60). Proxy usa deadline35000ms. El runner usa configuración controlada; no reenvía estas variables del host. Fallos de preflight salen2; una fase sin éxitos o con fallos de transporte sale1 tras resumen. Rechazos y dropped siempre aparecen aunque no causen por sí mismos fallo del baseline exploratorio.

El preset mantiene warmup, lecturas, challenge, mixed, pico300 y proxy5/s. La suma es limitada; el pico conserva el máximo en vuelo100 y puede descartar oferta. La telemetría OTLP del baseline se imprime después de fases, sin tokens ni cuerpos. Ejecutar suite funcional separadamente para no distorsionar medición. No usar el ejemplo histórico contra api_default ni `tokens.txt`.

La fase `public-reads` es explícita y no lleva token aunque existan fixtures autenticadas; recorre pricing, productos con provider=stripe y healthz. Escenarios adicionales: `node services/api/load/run.mjs smoke slow` o `smoke hang` (también baseline). El preflight usa proveedor rápido; solo después activa degradación. En ambos escenarios el deadline API es1000ms y el stub tarda2000ms o no responde. Un exit1 por ausencia de completions en la fase proxy es resultado esperado de esa exploración, no evidencia de éxito funcional; comprobar 504, unknown, cupos liberados y lecturas saludables.

## Presupuestos conjuntos (ensayo, no configuración aplicada en Coolify)

`resource-profiles.mjs` define economy (1 vCPU/4 GiB), standard (2/8) y peak (4/12). CPU economy: API0,40; PG0,35; Caddy0,05; collector0,06; Prometheus0,07; Tempo0,03; Alertmanager0,02; sink0,02. Memoria economy respectivamente 1024/1536/128/256/512/256/64/64 MiB (3840 MiB; 256 MiB sin asignar). Standard multiplica ambos por2; peak multiplica CPUpor4 y RAMpor3. Son límites duros por componente, no préstamo automático de recursos ni disponibilidad garantizada del servidor compartido.

`compose.resources.yaml` se superpone al Compose sintético existente e incorpora Caddy/TLS interno y observabilidad real, sin puertos públicos. Grafana y panel estático están fuera del ensayo; no hay contenedor admin independiente que apagar. Driver/stub son carga externa y se contabilizan aparte. Migración/seed terminan antes de medir. El volumen temporal de Tempo usa root solo dentro de este fixture para inicializar su almacenamiento; no modifica permisos ni configuración de producción.

El sampler valida proyecto/servicio y cuotas efectivas. Si un contenedor distroless no permite leer `cpu.stat`, reporta throttling no disponible, no cero; los demás datos provienen de Docker. Los valores observados no certifican NVMe, ancho de banda ni vCPU del servidor destino. Retención Prometheus de referencia: 90 días/2 GB; su crecimiento real se mide antes de dimensionar producción.

TLS: el contenedor transitorio `trust-load` copia exclusivamente la CA pública a `load_trust`, montada en solo lectura por el driver. El driver no monta el almacenamiento privado de Caddy, no necesita root y no desactiva verificación de certificados. Ese contenedor debe terminar antes de empezar la fase medida.
