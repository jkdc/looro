# Verificación de capacidad por recursos

Estado: ejecución directa del plan aprobado; T1–T3 completadas; T4–T6 pendientes. Sin despliegue ni certificación de capacidad todavía.

## T1 — Presupuestos y observabilidad real

- Type-check inicial/final PASS. Dos módulos nuevos: perfiles conjuntos cerrados y sampler con identidad/cuotas efectivas y métricas ausentes explícitas.
- RED por módulos ausentes; pruebas de reglas ejecutadas directamente con Node: 4 perfiles/config + 2 sampler PASS. Regresión de clave heredada observada 2 PASS/1 FAIL antes de corregir catálogo; confianza TLS observada 3 PASS/1 FAIL antes de separar la CA pública.
- Stack economy real: 8 residentes con suma de CPU = 1 vCPU y límites RAM = 3840 MiB (presupuesto 4096 MiB); Docker inspect contrastado con catálogo. API+PostgreSQL+Caddy+collector+Prometheus+Tempo+Alertmanager+sink activos, sin Grafana/panel. Driver/stub fuera del presupuesto de servicio.
- Fetch HTTPS con CA interna confiada por Node PASS; sin desactivar verificación TLS. El driver solo recibe el certificado público por volumen distinto al de las claves privadas. Trust/migración/seed son transitorios, no carga residente medida.
- Primer smoke falló sin diagnóstico completo; segundo localizó Permission denied al montar almacenamiento privado de Caddy. Se corrigió compartiendo solo la CA pública mediante transitorio; smoke final PASS y limpieza de todos sus recursos observada. No afecta configuración productiva.
- Collector distroless no ofrece cat/cpu.stat: throttling null, no cero. OOM false y reinicios 0 en la muestra. Son observaciones de arranque/health, NO rendimiento bajo carga.
- El runner host `node --test` mostró wrappers de archivo en vez de nombres de assertions; se ejecutaron tests TS directamente para observar cada prueba real. La suite compilada en Docker se verificará en el cierre.

Evidencia raw sintética ignorada: `evidence/t1-smoke*.log`; las mediciones temporales futuras deben ejecutarse sin suites/profilers concurrentes.

## T2 — Cohortes y flujos útiles

- RED inicial: driver 15 PASS/2 FAIL (TLS cerrado y POST progreso202), módulo nuevo ausente. El test con examen compuesto real detectó que weekId es numérico, no string; corrección validada 2 PASS/1 FAIL→3 PASS. Focused final: 3 workloads + 17 driver PASS; type-check PASS.
- Fixture resource de 1201 cuentas: probe1, reclamables180, guest10, activos10, nuevas1000. Preflight usa probe y no modifica grants nuevos; modo previo mantiene200 cuentas y sesiones4h, resource8h. Cohorte nueva se agota explícitamente, no recicla créditos reclamados.
- Integración contra API/PG reales: HTTPS confiado PASS, CA no confiable rechazada, preflight sin grants nuevos, 20 primeras concesiones, guest/activo sin crédito, POST202 de progreso con readback y examen híbrido determinista real. Saldos y diarios comprobados por probe; limpieza PASS. El probe versionado conserva assertions, no imprime tokens.
- Diagnóstico de empaquetado: `compose build api-load` devolvía0 sin construir porque ese servicio solo define imagen; el build está en migrate-load. Se corrigió el comando y construyó el código real. El smoke anterior de T1 validaba cuotas/TLS con una imagen previa, no el nuevo workload ni rendimiento.
- Un arranque con migrador CLI falló con mensaje genérico; diagnóstico de migración aislada PASS y arranque completo con comando diagnóstico de la misma función PASS, sin modificar migraciones ni timeout. No se afirma una causa raíz no capturada; el runner deberá registrar build/digest y validar arranque de nuevo. Ningún fallo o intento anterior se presenta como carga exitosa.
- Bytes del driver son cuerpos de petición/respuesta, no cabeceras/TLS ni cuota de transferencia total.

Sigue pendiente escalera por presupuesto, soak, fallos, comparación CASE/Redis y análisis móvil.

## T3 — Runner, resistencia y recuperación

- CLI cerrada con ayuda y soak de tasa explícita, 60 ventanas de60s, sin muestras globales ni promediar percentiles. Entorno saneado, proyecto único, cleanup propio en error/cancelación. Escalera exploratoria se detiene en frontera no aprobada sin llamarla capacidad.
- Smokes economy/standard/peak: cada uno24/24 útiles y0rechazos/fallos, PostgreSQL/TLS reales y ocho residentes con cuotas verificadas; progresos202 y exámenes reales. Prueba reforzada:1201cuentas, dinero/ledger1200999999991nanoUSD, minutos/diario55500000ms, uso=settled1, unknown/rejected/pending0, wallets porcuenta y grants duplicados0; cleanup PASS. Son smokes a4/s porflujo, no capacidad de cada perfil.
- Carreras corregidas con RED/GREEN: CAtransitoria aún en ejecución rechazada correctamente por sampler; `up --wait` también fallaba si el mismo job terminaba0 demasiado pronto. Separamos arranque de residentes y job con espera explícita de exit0. Se mantiene el guard de pertenencia. Driver efímero desaparecido se marca unavailable, no cero, mientras fallos de residentes siguen siendo error.
- Fallos standard: proveedor2s/colgado/429 devuelve504/504/502, con lecturas útiles concurrentes. ReinicioAPI recupera; reinicioBD falló dos veces antes de corrección, con categorías saneadas `unhandled_client_error` y `postgres_connection_terminated`.
- Raíz confirmada en `pg-pool/index.js:51–62`: pg retira el cliente idle roto y emite Pool.error; sin listener el EventEmitter termina el proceso. `connectDatabase` carecía de listener, también en main. Regresión nueva1RED/1preservaciónPASS; tras listener con evento cerrado sin objetos de conexión,2PASS. No cambia pool, timeouts, retries o manejo de queries activas.
- Repetición real después de corregir: reiniciosAPI yBD recuperan, proveedor degradado conserva2unknown/1rejected/0pendientes, solo1settled y1usage; dinero/ledger1200999999991 y minutos/diario55500000; mismatches porcuenta/duplicados0. Limpieza PASS. No se reinicia API automáticamente para esconder el fallo de BD.
- La ayuda CLI usa un subproceso Node: el sandbox lo bloqueó con EPERM; pruebas escaladas observan el comportamiento real. El scratch sin rama help devuelve2, ayuda actual0. El EPERM no se cuenta como RED de comportamiento.

## T4 — Comparación y capacidad observada

- Comparación de tres pares intercalados con imagen, cuotas y fixture congelados: menos consultas para algunos estados no produjo mejora de latencia reproducible; el p95 de elegibles fue peor en las tres repeticiones. Se restauró el precheck grant-only anterior y se preservaron las guardas transaccionales. No se añade Redis: no hay cuello cacheable demostrado y el saldo/elegibilidad continúan autoritativos en PostgreSQL.
- V2 no completó el primer soak de 50/s: Tempo alcanzó OOM en 512 MiB. V3 completó 60/60 ventanas pero dos ráfagas de primeras concesiones excedieron el p95 objetivo (404ms/378ms). V4 redistribuyó cuotas dentro de los mismos techos agregados; prueba de 1000 primeras concesiones a 50/s: 1000 útiles, p95=65,67ms, p99=357,50ms, integridad PASS.
- Soak V4 TLS interno, standard 2 vCPU/8 GiB: 60/60 ventanas aceptadas durante3600s; 179.981 útiles de180.000 ofrecidas, 19 dropped,0 fallos de transporte; máximo p95 por ventana26,33ms, máximo p99 por ventana787,82ms. Integridad: dinero y diario coinciden, minutos y diario coinciden, 1 uso asentado, unknown/rejected/pending=0, mismatches/duplicados=0. CollectorUp=1, 28 series, cleanup PASS.
- Máxima memoria observada: API83,3MiB; BD61,1MiB; Caddy17,2MiB; collector58,4MiB; Prometheus25,2MiB; Tempo1269,8MiB; Alertmanager14,6MiB; webhook11,9MiB; sin OOM ni reinicios muestreados. Volúmenes finales: BD72.892KiB, Tempo23.000KiB, Prometheus348KiB, Caddy72KiB. No extrapolar estos tamaños a producción ni a NVMe/transferencia declarados.
- En V3, standard pasó50/100 y falló200 en TLS y directo; peak V3 aceptó TLS hasta200, falló400, directo aceptó400 y 800 quedó limitado por el generador. Son datos de perfiles/versiones anteriores y no acreditan esas tasas para V4. El informe declara como única referencia sostenida actual 50 RPS sintéticos en standard V4.
- Informe: `informe-capacidad-recursos-api-2026-10-30.md`; datos reproducibles privados en `evidence/standard-soak-v4-50-tls.log`, `standard-v4-burst-50.log` y demás logs por perfil. Runner README actualizado. Suites enfocadas: perfiles6/6 y runner8/8; `make test-api`:832/832 PASS en stack efímero; `npx tsc --noEmit`:PASS; `make obs-check`:PASS; `git diff --check`:PASS. Un intento sandboxed de test y `obs-check` dio EPERM de Docker/subproceso, se repitieron con autorización y pasaron.

Pendiente T5: inventario CodeGraph de uso móvil y su informe. Pendiente T6: revisión independiente completa y cierre final. No se ejecutó despliegue, Redis, ni medición en Coolify.
