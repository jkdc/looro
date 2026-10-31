# Capacidad y presupuesto de recursos de la API

**Conclusión:** el perfil de 2 vCPU/8 GiB (`telemetry-db-balanced-v4`) completó una hora con 50 solicitudes sintéticas por segundo a través de TLS interno. Es una referencia de carga reproducible para este fixture, NO una garantía de capacidad de producción ni una medición del servidor de Coolify. La mezcla móvil real y sus bytes quedan pendientes del informe complementario.

## Qué se midió

- API Fastify, PostgreSQL 17, Caddy TLS interno y collector, Prometheus, Tempo, Alertmanager y webhook de pruebas, con cuotas Docker agregadas verificadas.
- Mezcla por escalera: 70% saldo reclamado, 10% escritura de progreso, 10% lectura de progreso y 10% examen. TLS se verificó con una CA privada local; el control HTTP directo se usó para comparar, no para simular el proxy de producción.
- Fixture sintético con 1.201 cuentas, 18.000 registros previos de progreso, sesiones de prueba de 8 horas y stack aislado. Las llamadas externas/proveedores reales no se usaron.
- Criterio exploratorio del runner: ≥99% de oferta útil, ≥98% de solicitudes lanzadas, p95 de éxitos ≤300ms y p99 total ≤1.000ms. Una escalera se detiene en el primer nivel no aprobado. Un soak sólo confirma la tasa que efectivamente sostuvo.

La infraestructura declarada por el usuario es 4 vCPU, 16 GB RAM, 200 GB NVMe y 16 TB de transferencia. No se inspeccionó Coolify ni se verificaron físicamente esos recursos; son límites informados, no observados.

## Resultado principal: perfil recomendado para comenzar

| Perfil de prueba | Límite agregado del servicio | Resultado observado | Interpretación |
|---|---:|---|---|
| `economy` | 1 vCPU / 4 GiB | Los ensayos anteriores del perfil de 1 vCPU no aprobaron 50 RPS (el peor p95 TLS observado fue 2.100ms; otro V3 dio p95 636ms). | No usar como capacidad objetivo para este tráfico de referencia. Puede servir para validar arranque o cargas menores. |
| `standard` V4 | 2 vCPU / 8 GiB | 50 RPS durante 3.600s: 60/60 ventanas aceptadas, 179.981 útiles de 180.000 ofrecidas (19 descartadas; 99,989%), sin fallos de transporte; p95 más alto entre ventanas 26,33ms y p99 más alto 787,82ms. | Mejor punto de partida medido. 100 RPS no se ha probado con V4; versiones anteriores no sustituyen esta prueba. |
| `peak` | 4 vCPU / 12 GiB | Escaleras V2/V3 llegaron a aceptar 400 RPS en algunas corridas TLS/directas, pero otras 400 fallaron y 800 estuvo limitado por el generador. | No declarar 400 RPS como capacidad estable ni extrapolarlo a V4 o producción. Repetir con V4 y generador no limitante antes de reservar este perfil. |

Durante el soak V4, las 19 descartadas se concentraron en algunas ventanas, pero ninguna rebasó la regla de aceptación. Por ventana, el menor throughput útil fue 49,951 RPS. El primer minuto tuvo p99=787,82ms; el resto se mantuvo mucho más bajo. Se reporta el máximo de cada percentil por ventana, no un promedio de percentiles.

La ráfaga V4 separada de primeras concesiones completó 1.000/1.000 solicitudes a 50/s: p50=12,14ms, p95=65,67ms, p99=357,50ms, máximo=593,83ms. Creó 1.000 concesiones sin duplicados. Es una prueba de 20 segundos para ese camino específico, no equivale a una hora de cuentas nuevas.

## Perfil V4 y consumo medido

Los perfiles son techos configurados por contenedor; no reservas de CPU/RAM. La suma de cuotas conserva 1/2/4 vCPU y 4/8/12 GiB. Reparto base `economy`:

| Servicio | CPU | Memoria |
|---|---:|---:|
| API | 0,40 | 1.024 MiB |
| PostgreSQL | 0,25 | 1.536 MiB |
| Caddy | 0,10 | 128 MiB |
| Collector | 0,06 | 256 MiB |
| Prometheus | 0,05 | 256 MiB |
| Tempo | 0,10 | 768 MiB |
| Alertmanager | 0,02 | 64 MiB |
| Webhook de alertas | 0,02 | 64 MiB |

`standard` duplica las cuotas base; `peak` multiplica CPU por cuatro y RAM por tres. El V4 estándar usa 0,8 CPU/2 GiB API, 0,5 CPU/3 GiB PostgreSQL, 0,2 CPU/256 MiB Caddy, 0,12 CPU/512 MiB collector, 0,1 CPU/512 MiB Prometheus, 0,2 CPU/1.536 MiB Tempo y 0,04 CPU/128 MiB para cada servicio de alertas.

Máximos observados en los 104 muestreos del soak V4: API 83,3 MiB; PostgreSQL 61,1 MiB; Caddy 17,2 MiB; collector 58,4 MiB; Prometheus 25,2 MiB; Tempo 1.269,8 MiB; Alertmanager 14,6 MiB; webhook 11,9 MiB. No hubo OOM ni reinicios registrados. Son máximos muestreados, no picos instantáneos garantizados. En Tempo se usó cerca del 83% de su cuota de 1.536 MiB: seguir vigilando crecimiento y retención.

El `CPUPerc` de Docker se conserva como dato de diagnóstico, pero no se usa como una medición del host Coolify. El collector distroless no expone `cpu.stat`; su throttling queda como no disponible, no como cero. No se corrieron suites ni perfiles de CPU durante el soak.

## Contabilidad, disco y red

- Integridad al cierre del soak: 1.201 cuentas; dinero y diario 1.200.999.999.991 nanoUSD; saldo/minutos y diario 54.300.000 ms; 1 uso asentado; 0 unknown, rechazados o pendientes; 0 wallets/minutos inconsistentes y 0 concesiones duplicadas.
- Telemetría real: Prometheus listo, collector activo y 28 series de dominio. El estado de telemetría es parte del resultado; no se sustituyó por un stub.
- En el fixture de una hora: PostgreSQL ocupó 72.892 KiB en volumen, Tempo 23.000 KiB, Prometheus 348 KiB y Caddy 72 KiB. La base de datos informó 17.110.707 bytes. Estos tamaños dependen de la retención y fixture de prueba; no dimensionan 200 GB productivos ni una política de backups.
- El runner registra bytes de red de contenedor y cuerpos HTTP, pero no mide ancho de banda facturable de Coolify: incluye tráfico interno, doble dirección/capas y excluye cabeceras/TLS y transferencias incompletas. No hay evidencia para proyectar 16 TB/mes.
- Limpieza de los contenedores y volúmenes del ensayo: PASS.

## CASE, consultas y Redis

Se compararon tres pares intercalados entre el precheck histórico (`aa51066`) y la propuesta (`2b61585`), con imagen, cuotas y fixture congelados. El precheck nuevo redujo consultas en algunos estados, pero la latencia no mejoró de forma reproducible; en elegibles, el p95 resultó peor en las tres repeticiones. Menos SQL no demostró menos tiempo ni mayor capacidad. Se restauró el precheck positivo sencillo del grant existente, manteniendo las guardas y transacción para cuentas potencialmente elegibles. Las pruebas conservan la protección financiera y reflejan que guest/activo pasan por la ruta transaccional.

No se incorpora Redis: no se aisló un cuello dominante cacheable que justifique otra dependencia. En particular, saldo, elegibilidad y concesiones financieras deben seguir protegidos por sus transacciones/diarios y no convertirse en datos autorizantes de caché. Si perfiles reales muestran lecturas repetidas caras, primero medir query plans, frecuencia, bytes y reglas de invalidación; proponer un diseño separado antes de añadir Redis.

## Recomendaciones de despliegue futuro (no aplicadas)

1. **Empezar con presupuesto standard, no economy.** En un host declarado de 4 vCPU/16 GB, standard limita este conjunto a 2 vCPU/8 GiB. Eso deja teóricamente recursos para sistema, Coolify, proxy y otros servicios, pero al ser cuotas —no reservas— hay que medir contención del host y cargas vecinas.
2. **No activar peak por anticipado.** El perfil peak limita el conjunto a 4 vCPU/12 GiB; en el host declarado deja sólo 4 GB nominales para sistema/otros servicios y puede competir por los cuatro núcleos. V4 aún no tiene escalera peak validada. Preparar el override y ensayarlo en staging con el proxy real antes de elevar cuotas.
3. **Aplicar límites de manera coordinada.** Coolify/Docker aplican límites por contenedor; verificar suma de todos los contenedores residentes y límites efectivos tras recrear. El ensayo no determina la configuración exacta del Compose de producción ni su versión Coolify. Seguir la documentación de [límites de recursos de Coolify](https://coolify.io/docs/applications/configuration/resource-limits), [servicios Compose](https://docs.docker.com/reference/compose-file/services/) y [Compose en Coolify](https://coolify.io/docs/applications/builds/docker-compose); luego comprobar `docker inspect`, salud, logs y métricas con tráfico controlado.
4. **Administración bajo demanda.** El stack de prueba no tiene un contenedor admin separado: el panel no se puede apagar deteniendo un servicio exclusivo sin comprobar primero cómo se sirve en producción. No apagar API ni Caddy, que también llevan tráfico de clientes. Revisar posibilidad de proteger o retirar la ruta/assets admin mediante configuración de build/ingress en cambio independiente, probar acceso administrativo y público, y medir el ahorro antes de adoptarlo.
5. **Escalar por evidencia.** Antes de una campaña, mirar p95/p99, tasa útil, cola/conexiones de BD, CPU throttling, memoria/OOM, crecimiento de Tempo/Prometheus, reinicios y tráfico externo. Si 50 RPS deja de cumplir el SLO, hacer una escalera con el mismo mix real y TLS del proxy; subir un límite cada vez y verificar salud/integridad. No se recomienda autoscaling ni Redis por estas pruebas.
6. **Dimensionar disco/retención con datos del host.** La prueba de una hora no emula 200 GB, backups, logs ni retención productiva. Estimar crecimiento diario por BD, WAL, backups, métricas y trazas por separado; poner retención/cuotas, alerta de espacio y prueba de restauración antes de reservar almacenamiento.

### Mejoras futuras priorizadas

| Prioridad | Mejora | Implementación cuando exista evidencia |
|---|---|---|
| P0 | Instrumentar demanda real sin PII | Medir RPS por plantilla de ruta, p95/p99, códigos, bytes y tiempos de BD; labels cerradas y datos agregados. Capturar una ventana representativa antes de cambiar recursos. |
| P1 | Repetir escalera bajo V4 y proxy de producción | Mantener fixture y checks financieros; incluir calentamiento/cuentas nuevas, TLS, errores y overhead compartido. Verificar que el generador no limite; informar nivel aprobado y primer nivel fallido. |
| P1 | Establecer budget operativo | Configurar cuotas coordinadas para API/BD/telemetría; alertar sobre saturación/OOM/espacio y validar tras recrear contenedores. Reservar host para servicios vecinos. |
| P2 | Reducir carga observabilidad con evidencia | Revisar cardinalidad, retención y volumen de traces; ajustar sampling/retención si los datos muestran crecimiento. Tempo ya se acercó al 83% de su límite de memoria durante una hora. |
| P2 | Cachear sólo lecturas no autoritativas si perfilan como cuello | Medir repetición y frescura requerida; diseñar TTL, invalidación, comportamiento en caída y pruebas de coherencia. No cachear saldos/permisos/concesiones. Redis es opcional, no requisito. |
| P3 | Operación admin separable | Determinar si la UI estática puede compilarse/servirse de forma separada sin quitar API pública; documentar operación segura de apagar/encender y probar rutas antes/después. |

## Límites de la conclusión

El ensayo es sintético, aislado y usa cuotas Docker, TLS de Caddy interno y un driver que corre fuera del presupuesto del servicio. No observa dispositivos, conectividad móvil, proxy público de Coolify, competencia con otros contenedores, vCPU física, SSD, bandwidth facturado, picos estacionales ni volumen de sesiones. El análisis de llamadas móviles en `informe-uso-api-movil-2026-10-30.md` está pendiente; por tanto, todavía no se estima cuántos usuarios simultáneos o activos soportará la aplicación.

Evidencia reproducible (logs privados ignorados por Git): `openspec/changes/api-resource-envelope/evidence/standard-soak-v4-50-tls.log`, `standard-v4-burst-50.log`, `standard-v3-steps-{tls,direct}.log`, `peak-v3-steps-{tls,direct}.log`, `standard-steps-{tls,direct}.log` y registros `*-v3-burst-50*.log`. La comparación CASE está en los registros `case-*` del mismo directorio.
