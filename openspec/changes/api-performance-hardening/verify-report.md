# Verificación: primera fase de capacidad API

## Resultado

Primera fase implementada y comprobada en `feat/api-performance-hardening`. No se han ejecutado llamadas a proveedores reales, despliegues, push o PR. No se certifica capacidad de producción ni se afirma una mejora porcentual frente al runtime anterior.

### Entregas

| Entrega | Commit |
|---|---|
| Driver importable, planificación/reporte acotados, fixtures y runner aislados | c255b03 |
| Métricas de proceso/proxy y dashboard | a58ab51 |
| Migración055, intentos y eventos inmutables, settlement atómico/idempotente | aa5a67e |
| Cupos, deadline, bytes, cancelación y shutdown del proxy | 30105cf |
| Desconexión antes de auth y fase pública explícita | 5a5126b |
| Población de fixtures estable e integridad observable | 21e2395 |
| Caracterización adicional: deadline agotado durante preparación SQL | 60d161d |

Configuración:10 operaciones simultáneas de proxy por proceso, deadline30000ms y cuerpo1048576bytes, con rangos documentados. Cuota histórica por cuenta2, no-streaming, grace y metering de éxito conservados. `proxy_busy`503 lleva Retry-After:1; provider_timeout504 no implica ausencia de coste. No hay reenvíos automáticos ni cargos inventados ante unknown.

## Pruebas observadas

| Comando / prueba | Resultado |
|---|---|
| `cd services/api && npx tsc --noEmit` inicial y tras cambios | PASS, Node22.23.2 |
| Suite API tras primera entrega del driver, PG efímero | 763 PASS,0 skipped |
| `make test-api` sobre30105cf | 782 PASS,0 skipped,297.7s |
| `make test-api` tras correcciones y runner de población | **785 PASS,0 skipped,296.7s** |
| Driver final en fuente, `node --import tsx tests/load-driver.test.ts` | **15 PASS,0 skipped** |
| Métricas nuevas con reader OTel real | 2 PASS |
| Attempts/capacity/historical token-proxy, PG sintético | 15 PASS,0 skipped |
| Cancelación HTTP real tras corrección, PG sintético | 5 PASS,0 skipped |
| Comprobación adicional de config/deadline en fuente, después de suite | 2 PASS; el grupo PG de ese archivo se omite en esa ejecución local sin DSN, ya cubierto por suite completa |
| `make obs-check` | PASS: validación collector/Prometheus/Alertmanager y pruebas de reglas |
| Smoke aislado corregido | exit0; redes/volúmenes eliminados |
| Baseline fast final | exit0;200 cuentas verificadas y contabilidad íntegra |
| Smoke slow final y hang | exit1 esperado:10 respuestas504,0 completions de carga; el fallo NO se cuenta como trabajo útil |

La última suite completa antecede a la adición del test unitario de deadline y al fortalecimiento de la aserción de identidad de fixtures; esas pruebas se ejecutaron aparte después, sin modificar runtime. No se presenta una suite de786 como ejecutada.

## Evidencia RED → GREEN y correcciones

- Driver: URLs undefined, NETWORKS ignorado, selección por completadas, drenaje/percentiles y capacidad. Regresiones adicionales: productos exige provider=stripe, token de sesión43 caracteres base64url y challenge exige objeto JSON vacío.
- Config/global cap/deadline/oversize/stop: seis fallos observados contra implementación anterior; luego PASS con PostgreSQL real y transportes falsos.
- Persistencia: RED por ausencia de tabla durable; GREEN con dos finalizaciones concurrentes que producen un único cargo/usage, rechazo de estado terminal y rollback SQL sin cargo parcial.
- Revisión independiente reprodujo envío tras desconexión durante autenticación y ausencia total de lecturas públicas en CLI; ambas regresiones fallaron antes de corregir y pasaron después.
- Las pruebas ampliadas de sockets/usage conocido y deadline durante SQL son caracterización postimplementación: no se afirma que se escribieran antes del código.
- Un fixture de lock bloqueaba también la FK del intento; se corrigió a FOR NO KEY UPDATE para permitir preparación y bloquear realmente settlement. No se cambió el runtime para satisfacer una prueba defectuosa.
- Primera baseline descartada: Compose run reiniciaba seed y producía400 cuentas, aunque se anunciaban200. Log mostró dos arranques de seed. Runner ahora usa --no-deps tras up --wait y verifica count(accounts)=200. La baseline publicada abajo es la repetición válida.

## Revisión independiente

Revisión del rango15bc16e..30105cf desde scratch separado, sin editar workspace. No encontró regresiones nuevas bloqueantes, pero identificó dos huecos del diseño que se aceptaron y corrigieron. Recheck acotado sobre5a5126b: ambos corregidos;14 pruebas driver y5 HTTP/PG PASS. El arreglo posterior del seed se contrastó mediante población real de200 cuentas y regresión del comando de lanzamiento, sin abrir una segunda revisión general.

## Baseline local válida

Código de referencia60d161d. Host Linux x64, Node22.23.2,12 CPUs visibles,32.7GB RAM de host; sin cuotas CPU/RAM explícitas por contenedor. Un único proceso API y PostgreSQL17 en red interna,200 cuentas sintéticas, proveedor falso rápido. Type-check adicional coincidió únicamente con preparación/warmup; la suite API no se ejecutó en paralelo con las fases medidas. No hay equivalencia garantizada con recursos de producción.

| Fase | Oferta | Útiles/ofrecidas | p95 éxito ms | p99 total ms |
|---|---|---:|---:|---:|
| Warmup |20/s durante10s |200/200 |30.80 |34.87 |
| Lecturas autenticadas |60/s durante30s |1800/1800 |15.09 |16.75 |
| Lecturas públicas |60/s durante30s |1800/1800 |3.80 |4.55 |
| Challenge |25/s durante30s |750/750 |13.68 |15.87 |
| Mixta |60/s durante30s |1800/1800 |13.78 |16.77 |
| Pico |300/s durante1s |300/300 |19.93 |46.84 |
| Proxy local |5/s durante30s |150/150 |40.47 |47.96 |

Cero llegadas descartadas en esta ejecución, cero fallos de transporte y todos los status200. Una única ejecución válida: **no** es el techo sostenible ni una promesa de300req/s sostenidos.

OTLP recibido: identidad de instancia única, RSS máximo muestreado129683456bytes (~124MiB), heap usado43.5MB, event-loop utilization máximo0.214 y delay p99 máximo muestreado21.9ms. Pool: waiting0, máximo in_use muestreado1; el muestreo cada15s puede omitir picos breves y no sustituye tiempo de adquisición. El gauge en vuelo puede muestrear0 entre llamadas rápidas; su cota se prueba con transporte retenido en tests, no se infiere de esta muestra.

Persistencia final:200 cuentas;151 intentos settled (150 de carga+1 de preflight),151 proxy_usage; suma de wallets y journal ambas199999998641nanoUSD. Ningún intento prepared/dispatched/unknown al finalizar fast.

### Fallos controlados

- Slow: stub2000ms con deadline API1000ms,10 respuestas504, p99≈1021.7ms;10 unknown y únicamente1 settled/usage del preflight. Las lecturas de otras fases completan. Población200, saldo agregado199999999991nanoUSD igual al journal.
- Hang: stub sin respuesta con deadline1000ms,10 respuestas504, p99≈1024.4ms;10 unknown y1 settled/usage. Población200, saldo/journal199999999991nanoUSD. No se cargó usage inventado por las diez operaciones inciertas.
- Ambos escenarios salen1 porque no hubo completions útiles de carga; es evidencia de rechazo/cancelación acotados, no de disponibilidad del proveedor. No se extrapola a proveedores reales.

## Evidencias y límites

Logs en `openspec/changes/api-performance-hardening/evidence/` (ignorado): baseline-final, slow-final, hang, suite-corrected, RED/GREEN y obs-check. No contienen tokens de acceso de fixtures; el manifest privado se eliminó con su volumen. Se limpiaron también contenedor/red PostgreSQL de pruebas independientes; scratchs de revisión quedan en /tmp, fuera del repo.

No ejecutados: pruebas de producción, proveedores reales, soak prolongado, escalera hasta saturación, benchmark de voz/watchdog, prueba HA/multiinstancia, comparación cuantitativa con runtime baseline usando el driver corregido y validación en vivo del dashboard Prometheus completo. Se verificaron emisión OTLP y configuración/reglas, no se afirma haber abierto Grafana. No hay cambios UI que requieran E2E del panel en esta fase.

La muestra no justifica activar ahora Redis, PgBouncer, réplicas, caché/CDN, índices nuevos ni distribución de voz. Siguen vigentes las limitaciones arquitectónicas del informe, especialmente líder único de voz, pool compartido y locks financieros globales. Siguientes inversiones requieren carga representativa y métricas adicionales; la instancia global del proxy no es una cuota distribuida.

**Risk: items1,3,4,5 — migración/persistencia financiera, errores HTTP añadidos, concurrencia/cancelación y wiring de entorno.** Verificación independiente y pruebas locales no equivalen a aprobación de despliegue.

## Seguimiento: rango de carga medido

Tras cerrar fase1 se ejecutó una escalera y cinco minutos sostenidos, sin cambiar runtime. Ver [informe de capacidad](../../../informe-capacidad-api-2026-10-30.md):200 ofrecidas/s durante5min,59983/60000útiles;400/s30s cumple criterio exploratorio;800/s no aprobado por llegadas perdidas y presión event loop/pool. Proxy con proveedor2s completa3/s sin rechazos,5/s ya produce9%busy. NAT125 lecturas produce120x200+5x429. Integridad272settled/usage,saldos=journal y0unresolved; cleanup observado. El listado de pendientes de fase1 anterior es histórico: este seguimiento cubre solo escalera de lecturas y sostenido corto, no soak de horas/voz/producción.

## Seguimiento: perfil de CPU/SQL

Comparación local de0577ace con CPU profiler y pg_stat_statements, OTEL on/off: ambos experimentos exit0 y cleanup. Con OTEL,24028 intentos de grant insertaron140filas;99.42%noops. Pila PG en40.5% de muestras no-idle inclusivas; no se atribuye al servidorSQL ni se desactivaOTEL. Ver [diagnóstico y pruebas de la mejora candidata](../../../informe-diagnostico-cpu-sql-api-2026-10-30.md). Sin cambios de runtime ni fix aplicado todavía.
