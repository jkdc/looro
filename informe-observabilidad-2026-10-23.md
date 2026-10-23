# Revisión de observabilidad: defectos y plan de corrección

**Fecha:** 23 de octubre de 2026. **Rama:** `feature/observability`.
**Objeto:** implementación de `docs/superpowers/plans/2026-10-22-observability.md`.
**Rango revisado:** `707e9b4..a5b1036`, los 12 commits de observabilidad; no todo el diff respecto a `main`.

> **Resolución de alcance (2026-10-23):** este informe conserva el análisis
> original. El usuario sustituyó el requisito de alertar por generaciones
> ausentes/antiguas: esas recomendaciones (en particular H4 y la alerta
> `LevelTestGenerationStale`) quedan supersedidas. Se mantiene el requisito de
> medir fallos de ejecuciones reales y generar alertas a partir de esas
> métricas (H3); la implementación y las pruebas actualizadas se registran en
> `openspec/changes/observability/verify-report.md`.

## 1. Dictamen

La arquitectura elegida es aprovechable: separa instrumentación, transporte, almacenamiento y visualización; mantiene los servicios de observabilidad fuera del camino crítico de las peticiones; y proporciona un contrato operativo y un stack reproducible. **No recomiendo sustituir el stack ni introducir auto-instrumentación generalizada para resolver los problemas encontrados.**

Sin embargo, no daría por cerrada la feature: hay una fuga demostrada de rutas hacia logs/trazas, alertas que no detectan los incidentes que anuncian y huecos en las métricas de fallo. La validación realizada hasta ahora demuestra funcionamiento de partes del pipeline, no todas las garantías del documento.

Este informe propone correcciones; **no las implementa ni autoriza su despliegue**. Durante esta revisión no se ha cambiado código, configuración ni servicios existentes. El único entregable nuevo es este documento.

### Prioridades

| ID | Prioridad | Resultado observado | Acción |
|---|---|---|---|
| H1 | P1 | Rutas concretas, incluso con datos sensibles, llegan a logs y trazas | Eliminar paths crudos y corregir el contrato del logger |
| H2 | P1 | `DbPoolSaturated` no dispara ni al 100 % de utilización | Corregir emparejamiento PromQL y semántica temporal |
| H3 | P1 | El primer fallo de generación puede no disparar ninguna alerta | Rediseñar la señal de fallo reciente y probarla desde arranque |
| H4 | P2 | Sin ningún éxito histórico no existe señal evaluable de generación atrasada | Definir y emitir estado inicial y recuperar errores de inicialización |
| H5 | P2 | Excepciones de BD/persistencia eluden métricas de ejecución de jobs | Instrumentar rechazos además de resultados devueltos |
| H6 | P2 | Las llamadas LLM fallidas no contribuyen a la latencia | Registrar duración en todas las salidas |
| H7 | P2 | Los spans HTTP son `INTERNAL` y los 500 quedan `UNSET` | Clasificar spans como servidor y marcar errores |
| H8 | P2 | Servicio sano sin 5xx devuelve «sin datos» en ratio/SLO | Diferenciar cero errores, cero tráfico y ausencia de telemetría |
| H9 | P2 | Con OTel desactivado se espera una consulta de inicialización | Restituir no-op y acotar el trabajo de arranque |
| C1 | P2, contrato | Respuestas 502 directas no reciben la correlación prometida | Compatibilizar las respuestas existentes con el contrato nuevo |

P1: corregir antes de confiar en estas garantías en un despliegue real. P2: completar antes de declarar terminado el contrato. No se ha demostrado una caída de producto ni una exposición real de credenciales de usuarios; sí un canal reproducible por el que podrían registrarse valores sensibles introducidos en una ruta.

**Origen importante:** varios defectos están ya en los ejemplos del plan original. Seguir el plan con fidelidad no basta si esos ejemplos son incorrectos. Hay que actualizar también el plan y las especificaciones afectadas, no únicamente el código.

## 2. Alcance y evidencia

Se contrastaron el plan completo, el change `openspec/changes/observability/`, el código de instrumentación, la configuración Docker/telemetría, las alertas, el dashboard, el contrato operativo y el informe de verificación existente.

Hubo dos revisiones independientes y de solo lectura: runtime y operación. Se comparó con el baseline para distinguir cambios nuevos de comportamientos preexistentes. El coordinador hizo lectura de contraste de los puntos señalados y repitió la suite focalizada y el type-check.

CodeGraph no estaba disponible como CLI ni herramienta MCP; se recurrió a lectura directa del código y del diff. No se inspeccionaron credenciales ni se ejecutaron operaciones remotas. Las pruebas de reglas usaron una imagen ya disponible, sin red, con el repositorio de solo lectura y fixtures temporales externos al workspace.

### Comprobaciones observadas en esta revisión

| Comprobación | Resultado y alcance |
|---|---|
| `cd services/api && ./node_modules/.bin/tsx --test tests/observability.test.ts` | 13 tests: 11 pasan, 2 omitidos, 0 fallos; repetido por el coordinador |
| `cd services/api && ./node_modules/.bin/tsc --noEmit` | Exit 0; repetido por el coordinador |
| Compose `config -q`, con variables ficticias explícitas | Configuración válida; no se arrancó el stack |
| `promtool check rules`, versión 2.54.1 | Seis reglas sintácticamente válidas |
| `amtool check-config` | Configuración válida |
| `promtool test rules` con fixtures aislados | Reproduce H2, H3 y H8; un control positivo confirma la corrección escalar de H2 |
| Probes en memoria de Fastify, exportación de spans y lectura de métricas | Reproducen H1, H5, H6, H7 y H9 |
| Lectura de inicialización y tests de ausencia de historial | Confirma la ausencia de gauge de H4; no se esperaron ocho días reales |

**No ejecutado aquí:** `make test-api`, suite completa de integración con PostgreSQL, nuevo ensayo de extremo a extremo con Collector/Tempo/Grafana, apagados/rearranques de servicios, carga sostenida ni llamadas reales a proveedores LLM. Los dos tests omitidos no cuentan como prueba de integración superada. Los resultados históricos de `verify-report.md` son evidencia previa, no nuevas ejecuciones de esta revisión.

## 3. Hallazgos y correcciones necesarias

### H1. Datos de rutas en logs y trazas

**Evidencia:** `services/api/src/app.ts:119–122,229–232`; `services/api/src/log.ts:1–8`. El mismo patrón aparece en el plan, líneas 516 y 535.

La instrumentación conserva una ruta de baja cardinalidad para las métricas, pero añade el pathname concreto como `url.path` a cada span. Además, en peticiones sin ruta reconocida, sustituye `unmatched` por ese pathname en el log.

El probe sobre `/alice@example.com/reset-secret` produjo un `http_request` con ese valor literal como `route`, y el mismo valor en el span. En una ruta parametrizada de prueba también se exportó el valor concreto del parámetro. Quitar la query string no elimina los datos personales o secretos que estén en el path.

Hay una segunda garantía incorrecta: `Record<string, string | number | boolean | null | undefined>` restringe el tipo de los valores, **no las claves ni su procedencia**. Acepta un token o correo porque son strings. También permite sobrescribir campos reservados del registro mediante las claves de `fields`. Esto es una debilidad demostrable de la API del logger, no evidencia de que todos sus consumidores actuales estén filtrando secretos.

**Cambios propuestos:**

1. Mantener en logs y métricas únicamente `http.route`/`route` con la plantilla reconocida o `unmatched`.
2. Omitir `url.path` cuando no se pueda garantizar su seguridad. No poner una plantilla en `url.path`: la plantilla corresponde a `http.route`.
3. Mantener fuera de spans nombres, URLs o mensajes de error derivados de entradas sensibles. Auditar todas las salidas, no solo stdout.
4. Definir campos admitidos por evento y aplicar filtrado de claves en runtime. Proteger `timestamp`, `level` y `event`. Evitar aceptar objetos de petición o errores arbitrarios.
5. No prometer que los tipos detectan cualquier secreto: incluso una clave permitida puede llevar un string indebido. Usar valores controlados y probar su procedencia.
6. Corregir las afirmaciones de seguridad del plan, del logger y del informe anterior.

**Aceptación:** peticiones con marcadores sintéticos distintos en parámetro, path desconocido, query, cabeceras y body. Ningún marcador aparece en stdout/stderr, nombres/atributos/eventos de spans ni atributos de métricas. Sí permanecen la plantilla segura, el método, el estado y un identificador de correlación generado por el servidor. Los tests negativos de tipos rechazan claves prohibidas; los tests de runtime comprueban filtrado y campos reservados.

Si ya hubo un despliegue con datos reales, evaluar acceso y retención de logs/trazas conforme al procedimiento de seguridad existente. **No borrar volúmenes ni asumir que hubo una filtración real sin evidencia.**

### H2. La alerta de pool saturado devuelve un vector vacío

**Evidencia:** `services/api/telemetry/alerts.yml:114–117,125`.

El numerador conserva etiquetas como `job`, `exported_job`, `state` e `instance`. El `max(...)` del denominador devuelve un vector sin esas etiquetas. Una división entre vectores no convierte automáticamente un vector de un elemento en escalar: necesita series emparejables.

Con `in_use=10` y `max_connections=10` durante ocho minutos, la expresión actual devuelve `[]` y no hay alerta. Cambiar únicamente el denominador a `scalar(max(...))` devuelve `1` en el control positivo. Es un error nuevo de esta feature y ya estaba en el diseño original. Véase el [emparejamiento de vectores de Prometheus](https://prometheus.io/docs/prometheus/latest/querying/operators/).

**Corrección mínima para una sola API:** convertir expresamente ese denominador en escalar. No aplicar esa solución sin más a una topología multiinstancia: allí hay que conservar identidad y emparejar utilización y capacidad de la misma instancia.

También hay que resolver una discrepancia temporal: `max_over_time(...[5m])` recuerda un pico durante cinco minutos; **no significa saturación sostenida durante cinco minutos**, como afirma la descripción. Para cumplir la descripción, evaluar utilización actual y usar `for: 5m`. Si se desea alertar por un pico reciente, mantener esa política y cambiar el texto, con su test correspondiente. Recomiendo saturación sostenida para el contrato actualmente escrito.

**Aceptación:** por debajo del 90 % no dispara; al 100 % durante el intervalo acordado dispara; un pico menor que ese intervalo no dispara si se adopta saturación sostenida; al recuperar se resuelve. Incluir denominador ausente y, si se declara soporte multiinstancia, capacidades distintas por instancia. Usar exactamente `promtool` 2.54.1, no sintaxis de versiones posteriores.

### H3. El primer fallo de generación puede no detectarse

**Evidencia:** `services/api/telemetry/alerts.yml:68–71`; requisito de fallo de generación en `openspec/changes/observability/specs/observability-pipeline/spec.md:127–129`.

El counter nace al primer evento. Si la primera muestra observada es `1` y no vuelve a cambiar, `increase(...[24h])` observa una constante: cero incremento. El `or vector(0)` cubre ausencia de serie, pero no reconstruye eventos anteriores a la primera muestra.

El fixture sin serie durante un minuto y con valor constante `1` después no dispara `LevelTestGenerationFailed` a los ocho minutos. En un scheduler semanal ese único fallo puede ser todo el incidente. El patrón también subcuenta los eventos iniciales de series LLM recién creadas por proveedor/código. No implica que `LlmFailuresSpike` sea siempre inservible: sí que una ráfaga inicial puede quedar fuera de su cálculo.

**Corrección recomendada:** mantener counters para tasas y volumen, pero no usarlos como única fuente del contrato «hubo algún fallo de generación en las últimas 24 horas». Derivar una señal periódica de los jobs ya persistidos, por ejemplo un gauge de fallos registrados en esa ventana, emitiendo también cero cuando la consulta válida no encuentra ninguno. No hace falta una nueva tabla para los fallos que ya constan en los jobs.

Condiciones del diseño:

- La señal se reconstruye al arrancar y se refresca; no se congela en una foto inicial.
- La consulta fallida se representa como estado desconocido/error de actualización, no como cero fallos.
- La ventana expira realmente a las 24 horas. No cambiar simplemente a `counter > 0`: mantendría la alerta mientras viva el proceso.
- Inicializar counters con cero mejora su uso, pero no garantiza observar un fallo anterior al primer scrape.
- Un fallo de infraestructura que impide escribir el propio job no puede reconstruirse leyendo esa tabla. H5 debe cubrirlo con telemetría de ejecución y la operación debe distinguir «historial consultable sin fallos» de «historial inaccesible». No prometer persistencia de esos eventos a través de reinicios sin una fuente duradera que realmente la garantice.

**Aceptación:** primer fallo tras arranque, primer fallo de otra combinación idioma/nivel, fallo anterior al primer scrape, reinicio con fallo persistido, expiración a las 24 horas y consulta de estado inaccesible. Ninguna prueba debe depender de un segundo fallo para detectar el primero.

Fuente: comportamiento de [`increase()`](https://prometheus.io/docs/prometheus/latest/querying/functions/#increase) y reproducción con la versión de Prometheus del proyecto.

### H4. Generación nunca exitosa o inicialización fallida queda sin señal

**Evidencia:** `services/api/src/observability.ts:45–55`; regla `LevelTestGenerationStale` en `alerts.yml:83–86`.

Cuando la consulta devuelve `null` no se registra `generationLastSuccess`. Tampoco se registra si falla la consulta. La resta de `time()` contra una serie inexistente no dispara por antigüedad. El test actual de historial vacío afirma precisamente esa ausencia.

La lectura al arrancar resuelve el caso «hay un éxito histórico», pero no «nunca funcionó». Un error transitorio en esa única lectura también deja el hueco hasta un éxito en proceso o un nuevo arranque.

**Cambios propuestos:**

1. Definir cuándo se espera generación automática, reutilizando la configuración real del scheduler.
2. Distinguir `disabled`, `never_succeeded`, `known_success` y estado desconocido de lectura. Son estados conceptuales: los nombres exactos deben quedar en el contrato antes de implementarlos.
3. Emitir una señal evaluable para cada estado aplicable, sin inventar una fecha de éxito.
4. Definir una gracia de bootstrap documentada para `never_succeeded`; no dar ocho días ficticios de salud en cada reinicio.
5. Refrescar/reintentar la lectura de forma acotada para recuperar errores transitorios y recoger éxitos de otros procesos.

**Aceptación:** sin filas terminadas y generación habilitada, el reloj simulado supera la gracia y se cumple una condición de alerta; con generación deshabilitada no hay falsa alarma; con un éxito antiguo se mantiene el umbral de ocho días; la lectura se recupera tras un fallo sin necesitar reinicio ni generación exitosa nueva.

**Decisión funcional que sigue abierta:** duración y punto de partida de la gracia inicial. Debe fijarse antes de implementar esta corrección; no se ha asumido un valor nuevo en esta revisión.

### H5. Las excepciones de ejecución de jobs no se cuentan

**Evidencia:** `services/api/src/level-tests-generation-jobs.ts:17–29,38`.

Las métricas se emiten después de que `runLevelTestGenerationJobOnce()` resuelva. Si rechaza la adquisición de conexión, una consulta o la escritura final del job, no se emite ni resultado ni duración. El probe de `db.connect()` rechazando confirmó rechazo público y ausencia de métrica de generación.

**Corrección:** rodear la ejecución completa con manejo explícito de rechazo y duración. Mantener el error original y el comportamiento de no reintento. Registrar exactamente un resultado aplicable, con clasificación de cardinalidad cerrada; nunca poner el mensaje de BD como label.

Separar validaciones de entrada de fallos reales de ejecución cuando corresponda. Conservar conscientemente `busy` y `exists` como no-op: no contarlos como trabajos ejecutados o éxitos. No convertir indiscriminadamente cualquier retorno en fallo.

**Aceptación:** conexión rechazada, consulta rechazada y fallo de finalización producen el rechazo original, un resultado de fallo de ejecución y una duración; nunca actualizan último éxito. Probar también que `busy`, `exists` y los resultados existentes mantienen sus efectos y no duplican contadores.

La elección de una clasificación nueva o la reutilización de `failed` debe documentarse sin romper la lectura de las series ya publicadas.

### H6. La latencia LLM solo incluye éxitos

**Evidencia:** `services/api/src/llm-config.ts:135–143`; ejemplo original del plan en la tarea 6.

Un fetch de prueba rechazado incrementa `llm.requests{status="failed"}`, pero no registra `llm.request.duration`. Los timeouts lentos y errores del proveedor desaparecen de la distribución de latencias.

**Corrección:** registrar la duración una sola vez en `finally`, abarcando transporte, lectura/parseo y validación de la respuesta; retirar el registro duplicable de la rama de éxito. Conservar contadores de resultado, tokens realmente conocidos, error público y ausencia de reintentos automáticos. No atribuir tokens inventados a una llamada de resultado desconocido.

**Aceptación:** éxito, HTTP no satisfactorio, rechazo de fetch, JSON inválido, contenido vacío y cancelación producen una observación de duración y el resultado apropiado. No debe haber dos observaciones en éxito ni modificación del error original. El test cuyo nombre menciona duración debe comprobarla explícitamente.

### H7. Spans HTTP sin clasificación ni estado de error

**Evidencia:** `services/api/src/app.ts:119–122,226`; configuración global en `observability.ts:22–30`.

El exporter en memoria recibe para un HTTP 500 `kind=INTERNAL` y `status=UNSET`, aunque existe el atributo de código HTTP. El span no se crea con `SpanKind.SERVER` ni se marca como error al terminar. Los filtros estándar de servidor/error pueden omitirlo.

**Corrección mínima:** tipo servidor, estado `ERROR` para 5xx y cierre único del span. Para 2xx mantener la semántica normal; no marcar automáticamente todos los 4xx como error del servidor. No registrar mensajes internos, cuerpos ni stacks sensibles para conseguirlo. Las [convenciones HTTP de OpenTelemetry](https://opentelemetry.io/docs/specs/semconv/http/http-spans/) especifican la clasificación de servidor y el tratamiento de 5xx.

**Aceptación:** spans capturados de 200, 4xx y 500, con tipo correcto, estado esperado, atributos seguros y correlación conservada. Verificar peticiones concurrentes y que no se cierren spans ajenos.

**Limitación adicional comprobada:** dentro del handler, `trace.getActiveSpan()` devuelve `undefined`. Crear el span y guardarlo en un símbolo de la petición no lo activa en el contexto asíncrono. Si se quiere correlación automática con operaciones internas, se necesita un context manager compatible con el SDK instalado y activar el contexto durante la ejecución real de la petición, no solo dentro de un callback efímero del hook.

La propagación local asíncrona es distinta de habilitar propagación W3C entre servicios, diferida expresamente en el alcance actual. No recomiendo incorporar esta última como requisito incidental. Para la propagación local, probar dos peticiones concurrentes con operaciones internas y ausencia de contaminación cruzada. Fuente: [contexto de OpenTelemetry para JavaScript](https://opentelemetry.io/docs/languages/js/context/).

### H8. Ratio 5xx y SLO sin datos precisamente cuando no hay errores

**Evidencia:** `services/api/telemetry/grafana/dashboards/palique-overview.json:52`; `services/api/docs/observability.md:138`; patrón equivalente en `alerts.yml:35–37`.

Con tráfico 200 y ninguna serie 5xx, el numerador no existe. La división devuelve vacío; el SLO tampoco devuelve disponibilidad uno. El fixture con únicamente serie 200 creciente devuelve `[]`. La alerta no genera por ello un falso positivo, pero el dashboard y la evaluación manual del objetivo quedan ambiguos.

**Corrección:** completar el numerador con cero cuando haya tráfico medible y mantener estados distintos para ausencia de tráfico y de telemetría. Para la consulta global actual, este es un patrón de ratio orientativo, que debe probarse con las etiquetas reales:

```promql
(
  (
    sum(rate(http_server_request_duration_seconds_count{
      job="otel-collector", exported_job="palique-api",
      http_response_status_code=~"5.."
    }[5m])) or vector(0)
  )
  /
  sum(rate(http_server_request_duration_seconds_count{
    job="otel-collector", exported_job="palique-api"
  }[5m]))
)
and
(
  sum(rate(http_server_request_duration_seconds_count{
    job="otel-collector", exported_job="palique-api"
  }[5m])) > 0
)
```

No se presenta como consulta ya aplicada. Al calcular el SLO, usar su ventana contractual, no copiar automáticamente `[5m]`. Si se agrupa por ruta/instancia, la estrategia de completar con cero debe preservar esas etiquetas; `vector(0)` global no basta.

**Aceptación:** solo 200 con tráfico → ratio cero y SLO uno; mezcla 200/500 → fracción correcta; tasa total cero → «sin tráfico»; series ausentes → «telemetría no disponible» cuando así lo indique el estado del pipeline. No presentar un 100 % ficticio cuando no hay medición.

### H9. Inicialización de telemetría altera el arranque incluso desactivada

**Evidencia:** `services/api/src/main.ts:28–31`; `observability.ts:48–55`.

`initGenerationMetrics(db)` se espera siempre, con independencia de `OTEL_ENABLED`. El probe con telemetría desactivada observó una consulta. Capturar su rechazo no evita que una promesa lenta retrase el arranque; el comentario «never block boot» no describe lo que hace ese `await`.

**Corrección:** omitir trabajo específico de observabilidad cuando está desactivada. Cuando esté activa, acotar su inicialización y desacoplar la disponibilidad del servicio de una consulta de telemetría. Un timeout superficial con `Promise.race` no cancela por sí solo una consulta ni libera recursos: usar mecanismos compatibles con el cliente/pool y observar el trabajo pendiente.

**Aceptación:** con OTel desactivado no se hace ninguna consulta específica de telemetría. Con una lectura de inicialización lenta o fallida, el arranque y el cierre respetan el límite documentado, sin promesas rechazadas sin tratar ni tareas huérfanas. No se fija un tiempo arbitrario en este informe: el límite debe acordarse y probarse.

### C1. El contrato de correlación no cubre todas las respuestas 5xx

**Evidencia:** `services/api/src/app.ts:739,744`, respuestas directas 502 en la prueba de conexión del proveedor.

Estas respuestas no pasan por `setErrorHandler`, de modo que no reciben `error.requestId` ni el evento `request_failed` introducidos allí. Esas respuestas ya existían en el baseline: **no es una regresión de respuesta causada por la rama**, pero sí cobertura incompleta de la nueva promesa «cada 5xx».

No intentar corregirlo convirtiendo silenciosamente `error: string` en un objeto: eso alteraría el contrato que consume el panel. Antes de escribir, comprobar consumidores y acordar una adición compatible, por ejemplo correlación en un campo/cabecera documentado manteniendo los campos actuales, o acotar expresamente el contrato global y justificar la excepción. Recomiendo cobertura compatible de todos los 5xx, no una excepción accidental.

**Aceptación:** provocar `llm_test_timeout` y `llm_unreachable` con transporte simulado; mantener estado y campos existentes; comprobar correlación utilizable por el consumidor y un registro seguro de fallo. Conservar el test de 5xx lanzado mediante excepción y el comportamiento exacto de los 4xx que no se cambian.

## 4. Aspectos que requieren validación, no defectos demostrados

### API y CLI comparten identidad de exportación

La API y `level-tests-generate` inicializan recursos con el mismo `service.name` y sin `service.instance.id` en `observability.ts:21`. Es razonable investigar interferencias entre counters acumulativos y gauges, pero **no se reprodujo una colisión en Collector** y no se cuenta como hallazgo confirmado.

Sí existe una limitación observable del diseño: el gauge de éxito de la API se carga al arrancar y se actualiza por éxitos en ese proceso; no vuelve a consultar automáticamente un éxito generado por otro proceso.

**Ensayo pendiente, aislado:** API con éxito histórico antiguo → ejecución CLI exitosa → varios ciclos de exportación de ambos → consultar identidad, timestamps y counters → cerrar CLI → observar estabilidad. Si falla, separar identidad/rol del emisor o hacer de la API el exportador del estado compartido persistido. Dar una identidad aleatoria en cada ejecución CLI aumenta cardinalidad; no adoptarlo a ciegas.

### Caída de la API no equivale a caída del Collector

El informe anterior probó la parada del Collector, no el caso API caída con Collector sano. En la versión 0.127.0, el exporter Prometheus conserva métricas sin actualizar durante cinco minutos por defecto. Después todavía intervienen scrape, evaluación, `for` y agrupación de notificaciones. Fuente: [configuración de la versión utilizada](https://raw.githubusercontent.com/open-telemetry/opentelemetry-collector-contrib/v0.127.0/exporter/prometheusexporter/README.md).

Por ello no se debe prometer detección total a los cinco minutos de morir la API. Probar ambos fallos por separado y documentar el presupuesto completo. Si se reduce `metric_expiration`, equilibrarlo con los intervalos de exportación y reinicios para no introducir falsas alarmas.

### Capacidad y retención

La combinación de retención temporal y límite de tamaño de Prometheus no garantiza conservar 90 días bajo cualquier ingestión. Comprobar ocupación real y disponibilidad de la ventana mensual antes de confiar en el SLO mensual. No se midieron ingestión ni necesidad de dimensionamiento en esta revisión.

## 5. Lo que conservaría y lo que no ampliaría ahora

Conservaría la instrumentación explícita y el reparto `log.ts` / `metrics.ts` / `observability.ts`, los labels de dominio cerrados, los puertos de observabilidad en loopback, el provisioning y el sumidero local verificable. Las correcciones propuestas no requieren sustituir Fastify, añadir un nuevo backend de telemetría ni introducir migraciones para consultar los jobs que ya existen.

No convertiría en requisitos bloqueantes las siguientes ampliaciones, porque no son fallos demostrados del alcance acordado: entrega externa a Slack/PagerDuty, nueva instrumentación móvil, coste monetario calculado desde tokens, propagación distribuida W3C, auditoría general de todos los jobs del producto o infraestructura multiinstancia completa.

Rotación de logs, límites de recursos, backups y revisión periódica de imágenes/dependencias merecen un trabajo operativo propio. No se ha hecho aquí un análisis de vulnerabilidades de imágenes ni se afirma que las versiones fijadas estén libres de vulnerabilidades. Evitar actualizaciones masivas sin verificar compatibilidad como respuesta a esta revisión.

## 6. Plan de trabajo para una implementación posterior

Orden sugerido: corregir primero privacidad y alertas falsas por ausencia de señal; después completar instrumentación y contrato; finalmente renovar la prueba integral. Las unidades siguientes son propuestas, no trabajo ya autorizado o completado.

| Unidad | Alcance | Superficies previstas | Condición de cierre |
|---|---|---|---|
| U1 | H1: datos seguros en todas las salidas y logger honesto | `src/app.ts`, `src/log.ts`, tests de observabilidad | Marcadores sensibles ausentes; plantilla y correlación presentes |
| U2 | H2, H3, H8: semántica de alertas y consultas | `telemetry/alerts.yml`, dashboard, tests PromQL; runtime si H3 necesita gauge derivado | Casos de primer evento, saturación, recuperación y falta de tráfico |
| U3 | H4, H9: ciclo de vida de señales derivadas de BD | `src/observability.ts`, `src/main.ts`, inicialización CLI si corresponde, tests | Estado inicial explícito; recuperación; no-op real; arranque acotado |
| U4 | H5, H6: instrumentación de todas las salidas | `src/level-tests-generation-jobs.ts`, `src/llm-config.ts`, tests | Un resultado/duración por operación aplicable, sin alterar errores/reintentos |
| U5 | H7, C1: trazas y correlación HTTP compatibles | `src/app.ts`, wiring de contexto si se incluye, tests HTTP | Spans de servidor y 5xx; respuestas previas compatibles |
| U6 | Prueba de reglas permanente y cierre operativo | Makefile, workflow de checks, fixtures, docs y verify-report | Validación funcional en CI y evidencia integral actualizada |

Las rutas `src/` de la tabla son relativas a `services/api/`. No lanzar writers en paralelo sobre `app.ts` o `observability.ts` sin resolver su propiedad: U1/U5 y U2/U3 tienen dependencias reales.

### Decisiones que deben quedar escritas antes de cambiar comportamiento

- Gracia de generación nunca exitosa y tratamiento de scheduler deshabilitado.
- Saturación sostenida frente a pico reciente; recomiendo alinear la regla con «sostenida».
- Representación compatible de correlación para los 502 del panel.
- Identidad y autoridad de exportación del estado API/CLI después del ensayo aislado.

No hace falta una nueva plataforma para resolverlas. Sí evitar que el código elija accidentalmente una política de operación.

## 7. Matriz mínima de regresión

Usar tests deterministas en memoria cuando sea posible; PostgreSQL efímero donde intervenga historial real. Para reglas, añadir un fichero propuesto `services/api/telemetry/alerts.test.yml`, no un test que se limite a parsear YAML. La herramienta oficial permite comprobar expresiones y alertas en instantes simulados: [unit tests de reglas](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/).

| Área | Casos mínimos | Efectos que comprobar |
|---|---|---|
| Privacidad | Parámetro, path desconocido, query, cabecera, body con marcadores | Ausencia en logs y spans/métricas; metadatos seguros presentes |
| Logger | Claves prohibidas, reservadas y campos válidos | Rechazo/filtrado real; no sobrescribir metadatos; JSON válido |
| Pool | Bajo umbral, sostenido, pico, recuperación | Valor de expresión y pending/firing/resolved correctos |
| Fallo de generación | Primero, nueva combinación, pre-scrape, reinicio, 24h | Primer incidente detectable y expiración correcta |
| Frescura | Sin éxito, antiguo, reciente, deshabilitado, fallo de lectura | Estado honesto y alerta conforme a gracia/configuración |
| Job rechazado | Conexión, consulta, persistencia final | Error público conservado; una métrica de fallo/duración; no éxito |
| Job no-op | `busy`, `exists` | Efectos anteriores conservados; no éxito ficticio |
| LLM | Éxito y cinco salidas de fallo de H6 | Una duración y resultado; tokens solo conocidos; cero reintentos nuevos |
| HTTP | 200, 4xx, excepción 500, 502 directo | Estado/body compatibles, correlación y span correctos |
| Contexto, si se completa | Dos peticiones concurrentes | Span activo correcto y ausencia de contaminación |
| Ratio/SLO | Solo éxitos, mezcla, sin tráfico, sin pipeline | Cero errores no significa «sin datos»; sin medición no significa 100 % |
| Arranque | OTel apagado, BD lenta/error | Cero consultas de telemetría apagada; límite de arranque y cleanup |
| API/CLI | Exportaciones simultáneas y salida de CLI | Estado compartido estable e identidad sin colisiones demostrables |

Para las otras reglas existentes, probar además umbral y resolución de `ApiHttp5xxRatioHigh`, expiración de señal en `ApiMetricsMissing` y series nuevas/ráfagas para `LlmFailuresSpike`. No declarar cubiertas las seis alertas por haber probado una.

### Comandos propuestos para el cierre, después de implementar

```sh
# Desde la raíz: pruebas del backend en el entorno efímero del proyecto.
make test-api

# Validadores existentes; añadirles la prueba funcional de reglas.
make obs-check

# Desde services/api: checks focalizados y estáticos.
./node_modules/.bin/tsx --test tests/observability.test.ts
./node_modules/.bin/tsc --noEmit

# Con promtool de la misma versión que el stack, desde services/api/telemetry.
# alerts.test.yml es el fixture a crear; aún no existe como entregable aquí.
promtool test rules alerts.test.yml
```

Mantener el runner Docker del proyecto en CI; el comando `promtool` expresa la comprobación, no exige instalar otro binario en el host. Añadir la prueba funcional al mismo flujo que hoy ejecuta `check rules` y `amtool check-config`. La suite completa de integración y el ensayo del stack deben ejecutarse solo en un entorno efímero o de prueba expresamente seleccionado, sin interrumpir la instancia existente.

## 8. Actualización documental necesaria

1. **Plan original:** corregir ejemplos de paths crudos, supuesta allow-list, división PromQL, primer evento, duración solo en éxito y semántica de frescura. Actualizar la descripción de dependencias a las realmente instaladas; el plan no debe seguir siendo una receta incompatible con el SDK utilizado.
2. **OpenSpec:** registrar semántica inicial, errores de lectura, saturación temporal y cobertura compatible de 5xx; no ampliar alcance incidentalmente.
3. **Contrato operativo:** sincronizar nombres de series, queries, estados sin datos, presupuesto real de detección y procedimiento API/CLI.
4. **`verify-report.md`:** conservar la evidencia histórica, pero añadir correcciones y nueva evidencia fechada. Sustituir «sin datos sensibles» basado únicamente en grep por el alcance exacto demostrado por tests con marcadores.
5. **CI/Make:** documentar que `check rules` comprueba sintaxis y `test rules` comportamiento. La primera comprobación no sustituye a la segunda.

No sobrescribir los resultados anteriores como si estas nuevas pruebas ya se hubieran realizado. Tampoco marcar resueltos los defectos porque exista este informe.

## 9. Criterio final de cierre

- [ ] H1–H9 corregidos con regresiones que fallen antes del arreglo y pasen después cuando haya un runner determinista aplicable.
- [ ] C1 resuelto de forma compatible o excepción contractual deliberada y documentada.
- [ ] Las seis alertas tienen casos funcionales de disparo/no disparo/resolución en CI.
- [ ] Un primer fallo y una instalación sin ningún éxito histórico tienen comportamiento observable y documentado.
- [ ] No-op de observabilidad, errores públicos y política de no reintento se conservan.
- [ ] Se ejecutó y documentó el ensayo API/CLI; la hipótesis de colisión queda confirmada o descartada, no insinuada como hecho.
- [ ] Se diferencia caída del emisor y caída del Collector, con tiempos de detección medidos.
- [ ] Integración con PostgreSQL y prueba integral del stack verificadas en entorno aislado, o pendientes señalados expresamente.
- [ ] Plan, OpenSpec, docs, dashboard y verify-report describen el mismo comportamiento comprobado.

**Siguiente paso recomendado:** implementar U1 y el conjunto funcional de alertas U2; fijar después las decisiones de estado inicial/correlación antes de U3 y U5. No hace falta rehacer la feature, pero sí corregir las garantías que ahora no cumple.
