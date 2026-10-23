# Diseño: corregir las garantías de observabilidad

Este diseño resuelve los defectos confirmados en la revisión de la rama `feature/observability` y mantiene el stack existente. La generación semanal mantiene su calendario optativo, pero la ausencia de una ejecución programada no genera una alerta; las alertas de generación se reservan para fallos reales observados y medidos.

## Propósito

La API ya emite métricas, trazas y logs y los exporta al stack local de OTel/Prometheus/Tempo/Grafana. La revisión encontró que ciertas rutas pueden filtrar valores sensibles, varias reglas PromQL no representan lo que afirman y algunas rutas de error quedan fuera de las mediciones.

Este cambio debe hacer que las garantías de privacidad, cobertura de alertas y correlación estén respaldadas por pruebas observables, además de mantener compatibles los clientes y el modo optativo del worker.

## Objetivos

1. Nunca exportar rutas concretas o datos derivados de la petición en logs, métricas o atributos de spans.
2. Hacer que las alertas de pool, generación, LLM y disponibilidad reflejen su semántica declarada y se prueben con datos temporales simulados.
3. Medir y alertar los fallos reales de generación, incluso si ocurren antes del primer scrape o el servidor se reinicia.
4. Registrar duraciones y resultados también en los caminos de fallo, preservando errores, cuerpos HTTP, ausencia de reintentos y semántica de los jobs no-op.
5. Asegurar que las trazas HTTP representen spans de servidor y clasifiquen correctamente los 5xx.
6. Mantener compatibilidad del panel y los consumidores de respuestas HTTP 502 existentes.
7. Mantener la generación LLM optativa y no añadir migraciones a PostgreSQL.

## No objetivos

- Sustituir Fastify, OpenTelemetry, Prometheus, Tempo o la arquitectura del Collector.
- Habilitar el worker o cambiar configuración de despliegues remotos.
- Ejecutar el stack de producción, usar credenciales, contactar proveedores LLM reales o cambiar sus costes.
- Añadir una tabla o migración para marcar el primer arranque.
- Añadir entrega externa de alertas, instrumentación móvil, propagación distribuida W3C ni soporte de infraestructura multiinstancia.
- Tratar la hipótesis no reproducida de colisión API/CLI como defecto; se probará de forma aislada y solo se modificará esa identidad si la prueba lo demuestra.

## Restricciones confirmadas

- La configuración actual calcula día y hora en UTC. Los días son ISO-8601: `1` lunes y `7` domingo.
- El código y Compose usan ahora lunes (`1`) a las 04:00 UTC como valor predeterminado.
- `LEVELTEST_GENERATE_ENABLED` sigue en `false` si no se configura explícitamente. No se habilita como parte de este cambio.
- La generación procesa las combinaciones de idiomas y niveles configuradas.
- El esquema ya conserva por semana, idioma y nivel el historial de jobs; las salidas semanales también son persistentes. Los registros duraderos de jobs pueden recuperar fallos observados después de un reinicio.
- La fecha y hora UTC configuran cuándo se ejecuta el worker, no una condición para alertar cuando una ejecución está ausente.
- Ninguna ruta, label, clave o mensaje libre controlado por una petición debe añadirse a telemetría.

## Enfoques considerados

### A. Inferir la salud semanal de counters Prometheus y del tiempo desde el arranque

Menos consultas de estado y cambios de esquema, pero no detecta de forma fiable el primer evento de un counter, pierde el calendario exacto y vuelve a iniciar la gracia al reiniciar el proceso. No cumple el requisito.

### B. Guardar una fecha de activación en una migración nueva

Proporciona una referencia duradera para una instalación sin historial, pero duplica información que el calendario semanal y los jobs ya persistidos permiten derivar. Amplía innecesariamente el esquema y contradice la restricción de no crear migraciones.

### C. Alertar por fallos reales de ejecución, no por ausencia de salida — seleccionado

Mantiene el calendario semanal (domingo por defecto, 04:00 UTC) y el interruptor optativo, pero no calcula ni alerta estados de ejecución pendiente u omitida. Los fallos observados se registran en métricas con etiquetas acotadas; Prometheus alerta sobre esos fallos. Los registros duraderos de jobs permiten recuperar el primer fallo tras un reinicio cuando existe un registro persistido.

Esta opción evita alertas por worker deshabilitado, retrasado o no ejecutado y conserva la alerta útil de una ejecución que efectivamente falló. No requiere migración.

## Decisiones de diseño

### Calendario y condición de generación

- Cambiar el día predeterminado a domingo (`7`) en el parser de configuración, el Compose local y documentación asociada; conservar la hora predeterminada existente de 04:00 UTC.
- Conservar `LEVELTEST_GENERATE_ENABLED=false` por defecto en todas las superficies. Si está apagado, no se espera ejecución; la ausencia de resultados no genera una alerta.
- Usar la configuración realmente cargada por el proceso: valores explícitos siguen prevaleciendo sobre los defaults. No leer ni cambiar el estado de despliegues remotos.
- Registrar los fallos reales de generación con métricas de resultado; no derivar fallos de semanas sin resultados.
- Mantener `LevelTestGenerationFailed` como alerta de fallos recientes. Un gauge de timestamp del último fallo permite detectar el primer evento sin depender de un delta de counter y, cuando exista un registro duradero, recuperar esa señal tras reinicio. El counter de jobs sigue midiendo volumen; el gauge de último éxito puede seguir alimentando el dashboard, pero no genera una alerta de antigüedad.
- La desactivación del worker suprime cualquier expectativa de ejecución (no hay alerta por ausencia); un fallo histórico observado conserva su ventana normal de alerta.

### Alertas y ventanas

- `DbPoolSaturated`: comparar utilization con la capacidad de la misma instancia (escalar expresamente el máximo para la topología actual de una instancia) y usar un valor sostenido durante cinco minutos más `for: 1m`. No usar `max_over_time` para afirmar continuidad.
- `LevelTestGenerationFailed`: usar una señal que reconstruya el primer fallo, expire a las 24 horas y sobreviva al reinicio cuando existe registro duradero.
- `LevelTestGenerationStale`: eliminarla; el calendario no se utiliza para alertar por una salida semanal ausente o antigua.
- `ApiHttp5xxRatioHigh` y el SLO: devolver 0 errores y SLO 1 cuando hay tráfico medible y no hay 5xx; conservar diferenciación de cero tráfico y ausencia de métricas.
- `ApiMetricsMissing`: mantener heartbeat seguro del proceso y documentar de manera separada caída de la API y caída del Collector; la expiración temporal del Collector forma parte del presupuesto de detección.
- `LlmFailuresSpike`: conservar el umbral de más de cinco fallos por hora, verificando que la primera observación por serie no se pierda.
- Añadir pruebas `promtool test rules` para firing, ausencia de firing, recuperación, primera muestra, ventana expirada y ausencia de tráfico, y ejecutarlas desde el flujo existente `make obs-check`/CI.

### Logs y spans HTTP

- Eliminar `url.path` crudo de los spans de servidor. El template de ruta va en `http.route`; si Fastify no tiene template, la única clasificación exportada es `unmatched`.
- No serializar la URL completa, query, cuerpo, cabeceras, errores arbitrarios ni identificadores de cuenta en logs/trazas.
- Reemplazar la impresión de que `LogFields = Record<string, scalar>` es una allow-list cerrada por tipos de evento/campos permitidos y validación que preserve los campos reservados (`timestamp`, `level`, `event`). Añadir pruebas de salida y de claves reservadas/prohibidas. Los tipos no demuestran por sí solos que un valor escalar no contenga PII.
- Crear spans de request como `SpanKind.SERVER`, mantener atributos semánticos seguros, setear status `ERROR` en 5xx y no registrar descripción, stack ni mensaje que filtre datos.
- Activar el contexto local del span durante toda la operación asíncrona de la request con mecanismos compatibles con las versiones instaladas de OTel. No introducir propagación W3C entrante/saliente.

### Resultados LLM/jobs, startup y respuestas HTTP

- Instrumentar la ejecución del job incluso si falla acquiring conexión, una query o finalización. Conservar error y rechazo público, registrar una duración y una clasificación de cardinalidad cerrada. `busy` y `exists` continúan como no-op si no representan trabajo nuevo.
- Registrar la duración LLM exactamente una vez en `finally`, incluyendo fetch, parseo y validación, tanto en éxito como en todas las rutas de error. No inventar tokens ni reintentar.
- No ejecutar query de seeding específica de telemetría cuando OTel está desactivado. Con OTel activo, la lectura inicial/actualización de estado debe tener error y tiempo de ejecución acotados, cleanup claro y no retrasar el listen indefinidamente.
- Las respuestas 502 directas conservan sus cuerpos y códigos actuales. Añadir correlación no breaking (cabecera segura/documentada) y log estructurado; no cambiar silenciosamente sus objetos JSON ni los contratos del panel.

### API/CLI y límites

- Ejecutar probe aislado con API y CLI compartiendo Collector, primero con API sola y luego CLI, para verificar recursos, counters acumulativos y gauge de última generación.
- No cambiar `service.name`, asignar IDs aleatorios ni aumentar cardinalidad sin reproducción concreta. Si existe colisión demostrada, asignar identidad estable distinta por rol y actualizar queries, dashboard, alertas y pruebas conjuntamente.
- No iniciar ni detener instancias existentes ni leer configuración de despliegue remota.

## Superficies que se espera tocar

| Ruta | Responsabilidad |
|---|---|
| `services/api/src/log.ts` | contrato seguro de campos y metadatos reservados |
| `services/api/src/app.ts` | logs request, atributos/status/contexto de spans y 502 compatibles |
| `services/api/src/metrics.ts` | métricas derivadas de estado semanal y etiquetas controladas |
| `services/api/src/observability.ts` | polling acotado/seed de estado persistido y no-op cuando deshabilitado |
| `services/api/src/main.ts` | lifecycle de estado y wiring con config optativa |
| `services/api/src/level-tests-scheduler.ts` | default Sunday UTC sin alterar el contrato de enabled optativo |
| `services/api/src/level-tests-generation-jobs.ts` | finalización, resultados y medición de rechazos |
| `services/api/src/llm-config.ts` | duración en todas las salidas |
| `services/api/telemetry/alerts.yml` | expresiones/ventanas alineadas con estados reales |
| `services/api/telemetry/grafana/dashboards/palique-overview.json` | ratio/SLO sin confundir cero errores, cero tráfico y datos ausentes |
| `services/api/telemetry/otel-collector-config.yaml` | solo si la evidencia y presupuesto de expiración requieren un cambio |
| `services/api/telemetry/alerts.test.yml` | escenarios temporales funcionales de reglas |
| `services/api/tests/observability.test.ts`, `tests/level-tests-scheduler.test.ts` y pruebas HTTP pertinentes | regresiones unitarias/contrato |
| `services/api/Makefile` o Makefile raíz, `.github/workflows/checks.yml` | ejecutar validaciones funcionales junto a checks sintácticos |
| `services/api/docs/observability.md`, OpenSpec y plan fechado original | contratos, runbooks y ejemplos alineados con comportamiento verificado |
| `openspec/changes/observability/verify-report.md` | evidencia nueva, sin sobrescribir pruebas históricas |

La lista es el límite de partida, no una orden de modificar cada fichero. El plan puede reducirse si una superficie no requiere cambio; cualquier archivo adicional debe justificar su contrato y pertenecer a una de estas unidades.

## Verificación y aceptación

Cada unidad escribe primero una prueba focalizada que reproduzca la conducta indicada. La prueba debe fallar por la razón esperada antes del cambio y pasar después. No añadir fixtures de reglas que solo validen YAML ni tests de PII que solo inspeccionen logs mientras ignoran spans.

Al cierre se ejecutarán los tests focalizados, `npx tsc --noEmit`, pruebas de reglas en la versión de `promtool` del stack, `make obs-check`, `make test-api` y `make check` cuando corresponda al alcance. Cualquier entorno Docker/DB será efímero; no reiniciar servicios del usuario ni realizar llamadas reales a proveedores.

La CI comprobará condiciones firing/no-firing/resolved para las cinco reglas conservadas, incluyendo saturación sostenida y recuperación, fallo real de generación, ausencia de salida semanal sin alerta, ausencia de tráfico y fallo de métricas. Las pruebas de startup comprobarán OTel desactivado (cero queries de telemetría) y lectura activa acotada.

El review final del branch debe confirmar cada H1–H9 y la brecha C1 de la revisión, más que la presencia de los archivos/tareas. La hipótesis API/CLI solo se cierra como confirmada o descartada con el probe reproducible.

## Revisión del diseño

- En esta etapa solo se crea y revisa este documento; no se cambia source, tests, configuración ni documentos de OpenSpec.
- El plan de implementación y el documento de tareas ODD se preparan después de que el usuario apruebe este diseño.
- Cualquier decisión del builder checker que afecte el comportamiento observable vuelve al usuario antes de planificar código.
