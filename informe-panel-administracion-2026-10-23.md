# Panel de administración: diagnóstico y plan de mejora incremental

**Fecha:** 2026-10-23. **Base inspeccionada:** `30990b0`, rama `feature/learning-platform`.
**Estado:** informe de análisis; no se ha implementado ninguna recomendación.
**Alcance autorizado:** revisar el panel, su organización y su diseño, y preparar una propuesta ejecutable sin reescribirlo por apariencia.

## 1. Dictamen ejecutivo

El panel tiene una base aprovechable: dependencias locales, navegación agrupada, servicios de dominio en el backend, controles administrativos y un recorrido E2E existente. **No necesita cambiar de framework ni rehacer su arquitectura completa.** Sí necesita corregir problemas concretos de interacción y de propiedad del estado antes de añadir más funciones.

Las prioridades son:

1. Evitar que una campaña preparada siga aplicando un snapshot anterior después de editar el formulario.
2. Alinear los filtros y la ordenación del explorador con las capacidades que su propia API anuncia.
3. Impedir que una respuesta tardía sustituya otra pantalla y corregir la destrucción de instancias de tablas.
4. Recuperar navegación y salida en pantallas pequeñas, y completar el comportamiento accesible de diálogos y formularios.
5. Hacer inequívocos el estado de las cargas, el alcance de los indicadores y la identidad de una ejecución de generación.

No se ha confirmado una vulnerabilidad crítica, pérdida de datos ni elusión de validación del servidor. Esto **no equivale a una auditoría de seguridad completa** ni a declarar el panel listo para producción en todos los navegadores. Varios hallazgos son defectos del cliente reproducidos con datos sintéticos; otros son riesgos sustentados por código que requieren un test de integración específico.

### Qué conviene conservar

- Grupos actuales de navegación: **Operación / Exámenes / Comercial / Sistema**.
- Separación entre configurar IA, generar/revisar exámenes y consultar resultados.
- Tabler, Tabulator y Chart.js locales mientras sigan resolviendo las necesidades del panel.
- Validación y autorización en el servidor; motivo auditado para las mutaciones que ya lo requieren.
- Configuración LLM efectiva guardada, con pruebas de conexión sobre la configuración persistida.
- Servicios de dominio y módulos de rutas ya extraídos en el trabajo de organización de la API.
- Runner E2E existente: ampliarlo, no sustituirlo por otra batería paralela sin necesidad.

## 2. Método, evidencias y límites

### 2.1 Trabajo realizado

- Inspección de `services/api/admin-ui/`, rutas/servicios administrativos y pruebas relacionadas.
- Contraste con los cambios OpenSpec existentes de panel, T11, operaciones y mejoras de exámenes. No se trata el informe antiguo del 21 de octubre como estado actual.
- Auditoría independiente de organización/contratos y auditoría de UI/UX.
- Renderizado en Chromium local con servidor loopback, llamadas API interceptadas y datos sintéticos. Se revisaron capturas de escritorio y móvil.
- Sondas Node VM sobre funciones reales del cliente para comprobar el registro de tablas y la escritura tardía de resultados.

CodeGraph tenía directorio de índice, pero ni su CLI ni su herramienta MCP estaban disponibles. Se utilizó inspección directa; no se modificó el índice.

Las referencias abreviadas a `admin-ui/`, `src/`, `migrations/` y `tests/` son relativas a `services/api/`. Las líneas corresponden al commit de base y pueden desplazarse durante la implementación.

### 2.2 Clasificación de evidencia

| Marca | Significado |
|---|---|
| **Navegador sintético** | Comportamiento observado en Chromium con respuestas interceptadas; prueba el cliente, no la persistencia ni la integración completa. |
| **Código / sonda** | Sustentado por rutas de ejecución o una función real ejecutada con dependencias controladas. |
| **Riesgo a verificar** | Existe una condición plausible en código; no se reprodujo el escenario completo con API y BD reales. |
| **Propuesta** | Mejora futura; no se presenta como fallo actual ni como requisito ya aprobado. |

### 2.3 Evidencia de esta sesión

El script `/tmp/palique-ui-audit.LjVLEw/probe.cjs` terminó con código 0 y generó `probe.json`, `desktop-overview.png`, `mobile-overview.png`, `desktop-browse.png`, `reason-dialog.png` y `login.png` en el mismo directorio temporal. El script y los resultados originales son **efímeros** y su ruta no es reproducible en otra máquina. Solo contienen datos sintéticos.

Se conservan tres capturas sintéticas como anexos del informe: [Resumen de escritorio](docs/audits/admin-panel-2026-10-23/desktop-overview.png), [Resumen móvil](docs/audits/admin-panel-2026-10-23/mobile-overview.png) y [diálogo de motivo](docs/audits/admin-panel-2026-10-23/reason-dialog.png). Las capturas documentan el estado inspeccionado; no son mockups de la solución propuesta.

Las sondas VM se ejecutaron por stdin y devolvieron:

```json
{"probe":"browse table disposal","created":1,"globalRegistry":0,"nonOrderableGuestHasSorter":"local"}
{"probe":"late internal results render after navigation","newRouteOverwritten":true}
```

La segunda sonda simuló el desmontaje sustituyendo el contenido del contenedor; no ejecutó el router completo ni observó el fallo visual con API real. Es evidencia de escritura sin invalidación, no una prueba E2E de toda la navegación.

### 2.4 No verificado en esta auditoría

- No se ejecutó la suite E2E completa sobre el stack real, ni de nuevo la suite Docker/PostgreSQL de la API.
- No se usaron cuentas, sesiones, claves, datos personales ni proveedores reales.
- No se comprobaron Firefox/WebKit, lector de pantalla real, contraste normativo, zoom completo, rendimiento de campo ni consumo acumulado de memoria.
- No se certifica conformidad WCAG, seguridad integral ni accesibilidad por una captura o una comprobación automatizada.
- No se revisaron vulnerabilidades publicadas de los vendors. Las versiones se identificaron desde cabeceras locales, no desde un inventario de seguridad actualizado.

## 3. Mapa actual y problemas de organización

| Pieza | Responsabilidad actual | Valoración |
|---|---|---|
| `admin-ui/index.html` | Shell, navegación, cabecera y contenedores de modales. | Adecuado como entrada estática; incompleto en móvil y semántica de diálogos. |
| `admin-ui/app.js` | Transporte, formato, navegación, recursos, gráficos, tablas, exámenes y formularios comerciales. Aproximadamente 1.328 líneas. | El problema no es el número de líneas: mezcla responsabilidades y ciclos de vida con fallos demostrados. |
| `admin-ui/app.css` | Ajustes visuales sobre Tabler y componentes del panel. | Conservar; formalizar pocas reglas compartidas antes de añadir CSS por pantalla. |
| `admin-ui/login.html` / `login.js` | Acceso con credenciales y bearer. | Dos caminos con manejo de error desigual. |
| `admin-ui/vendor/` | Tabler, Tabulator y Chart.js locales. | Evita depender de CDNs; no cambiar por estética. |
| `src/app.ts` | Administración general, autenticación, browse, métricas y comercio. | No mover más rutas en esta iniciativa salvo necesidad probada por una corrección. |
| `src/http/routes/admin-level-*.ts` | Rutas administrativas de exámenes y configuración. | Frontera ya creada; mantenerla. |
| `src/admin-browse.ts` y servicios de dominio | Capacidades de consulta, reglas, transacciones y datos. | Deben seguir siendo autoridad; el cliente debe consumir sus contratos. |
| `tests/browser/admin-e2e.mjs` | Recorrido real del panel en stack aislado. | Base útil, pero no prueba todos los estados adversos, viewports o interacciones accesibles. |

### Responsabilidad que falta explicitar

Cada pantalla debe tener un propietario para **sus solicitudes, su estado, sus recursos y su desmontaje**. Hoy un render puede continuar cuando su pantalla ya no es actual; algunas recargas saltan el coordinador del router; una tabla se registra en una variable local equivocada. Dividir el archivo sin resolver ese contrato solo repartiría los mismos problemas entre varios archivos.

## 4. Registro priorizado de hallazgos

Las prioridades reflejan impacto y evidencia. P2 no significa cosmético: varias incidencias afectan a acciones operativas importantes. No se asigna P1 global a móvil o teclado sin acordar antes los entornos soportados; si son necesarios para operar, esos defectos bloquean esa modalidad de uso.

| ID | Prioridad | Evidencia | Problema |
|---|---|---|---|
| H01 | P2, alta | Código | Preparación de campaña no invalidada al editar. |
| H02 | P2 | Código / sonda | Controles browse incompatibles con API. |
| H03 | P2 | Código / sonda | Respuesta tardía puede pintar sobre otra pantalla. |
| H04 | P2 | Código / sonda | Tabulator no entra en el registro que se destruye al navegar. |
| H05 | P2 | Código | Recargas async sin propietario de errores. |
| H06 | P2 | Navegador sintético | Sin navegación ni salida en viewport móvil. |
| H07 | P2 | Navegador sintético | Diálogos sin ciclo de foco, Escape ni semántica completa. |
| H08 | P2 | Navegador sintético | Valores inválidos enviados desde formularios aparentes. |
| H09 | P2 | Navegador / código | Controles sin nombre accesible y acciones ocultas tras clic derecho. |
| H10 | P2 | Navegador sintético | Fallo de red silencioso en acceso por bearer. |
| H11 | P2 | Riesgo a verificar | Seguimiento de generación sin identidad de ejecución. |
| H12 | P2/P3 según uso | Código | KPI globales junto a resultados filtrados de un alumno. |
| H13 | P2 | Código | Feedback efímero, polling silencioso y badge API estático. |
| H14 | P3 | Código / captura | Resumen sin jerarquía operativa y gráficos sin equivalente textual. |
| H15 | P3 | Código | Evidencia/pruebas y mensajes de interfaz parcialmente desalineados. |

### H01. Campaña preparada distinta del formulario visible

**Dónde:** `services/api/admin-ui/app.js:1147-1161`.

**Escenario:** preparar una campaña para todos los usuarios; después cambiar audiencia, cuenta o minutos; pulsar Aplicar. El handler conserva `summary.campaignID` y `summary.confirmation` de la preparación anterior. El resumen anterior sigue visible, pero no identifica de forma completa la discrepancia ni impide continuar.

**Impacto:** el operador puede creer que aplica los valores visibles y ejecutar el snapshot preparado antes. No se ejecutó una concesión real ni se confirmó perjuicio financiero en esta auditoría.

**Corrección propuesta:**

- Estado explícito: `editing → preparing → prepared → applying → applied`, con estado separado para resultado incierto.
- Cualquier cambio que altere el comando o su intención invalida la preparación; Aplicar queda deshabilitado hasta preparar de nuevo.
- Confirmación final con audiencia, cuenta o cantidad de destinatarios, minutos por destinatario y total. No basta un contador de destinatarios.
- El intento conserva su identidad si falla la red después de enviar. No recomendar preparar/aplicar una campaña nueva como reintento automático.
- Mantener el snapshot y las garantías del servidor; la invalidación visual no sustituye idempotencia ni autorización.

**Prueba de aceptación:** editar por separado cada campo relevante después de preparar impide aplicar; la confirmación corresponde al snapshot; doble clic y timeout no originan una segunda concesión inadvertida. Probar el resultado en BD aislada cuando se implemente.

### H02. Browse anuncia filtros y ordenaciones que su API rechaza

**Dónde:** `admin-ui/app.js:263-305`; `src/admin-browse.ts:24-33,74-85,271-279,327,355-356`.

**Casos concretos:** `action` en auditoría genera `contains` aunque admite `eq`; `kind` en ledger genera `contains` aunque admite `eq`/`in`; `is_guest` en cuentas aparece ordenable aunque no pertenece a `orderable`.

**Impacto:** petición rechazada como `400 invalid_request`; el control parece válido, pero la tabla puede conservar datos anteriores o un orden local distinto del contrato remoto.

**Corrección:** derivar controles de `spec.operators`, `schema.orderable` y `defaultOrder`. No habilitar un filtro solo porque el tipo sea texto o una ordenación solo porque la columna no sea JSON. Elegir una única autoridad remota para filtrar y ordenar el conjunto paginado; no aplicar después una semántica local diferente sobre una página.

**Aceptación:** matriz de catálogo donde todos los controles visibles generan queries válidas; regresión específica de los tres casos; cambiar filtro reinicia página y listado/contador/exportación mantienen el mismo criterio cuando la operación lo soporte.

### H03. Una recarga pendiente puede sustituir la pantalla nueva

**Dónde:** `admin-ui/app.js:971-985,1023-1030,1279-1307`; recargas adicionales en `589-590,699-707,1069,1124`.

**Escenario:** filtrar resultados con una respuesta lenta y navegar a Planes antes de completarse. El epoch de resultados no se invalida al abandonar la vista; las recargas internas no pasan necesariamente por la serialización inicial del router.

**Evidencia:** la función real, ejecutada en VM con respuesta retenida, escribió después de sustituir el contenido del contenedor. Falta la reproducción completa de este escenario mediante E2E con router real.

**Corrección:** contexto de montaje con identidad vigente, cancelación y `dispose`. Después de cada `await`, comprobar que el contexto sigue vigente antes de pintar, mostrar mensajes o registrar efectos. Toda recarga recibe el contexto; no basta añadir otro mutex al hash.

**Aceptación:** invertir deliberadamente el orden de respuestas, navegar durante filtro/paginación y resolver después. Deben mantenerse hash, título, contenido y handlers de la ruta actual; una petición cancelada no muestra un toast de fallo operativo.

### H04. El registro de Tabulator queda ocultado

**Dónde:** `admin-ui/app.js:24,228,277-282,1302-1303`.

`renderBrowse` declara `const tables = Object.keys(catalog.tables)`, ocultando el registro global. `tables.push(table)` agrega la instancia al array local de nombres. El router intenta limpiar el registro global, que no contiene esa tabla.

**Evidencia:** sonda con una instancia creada y cero entradas en el registro global. Se confirma la omisión de la destrucción prevista; no se cuantificó una fuga de memoria ni su crecimiento en producción.

**Corrección:** distinguir `tableNames` de recursos montados y asegurar destrucción una sola vez. Integrar después con el contexto de H03, sin convertirlo en un registro global más complejo.

**Aceptación:** entrar/salir varias veces de browse/auditoría destruye cada instancia exactamente una vez; ningún listener o respuesta anterior modifica la nueva tabla.

### H05. Recargas internas y fallos posteriores al guardado sin dueño

**Dónde:** `admin-ui/app.js:699-707,1023-1030,1069,1124`; el catch de entrada inicial está en `1306-1307`.

Los callbacks disparan renders async sin esperar/capturar localmente el resultado. El catch del router no cubre todo lo que la vista ejecuta después.

**Escenario:** API caída al filtrar/paginar; o una escritura se confirma y falla su recarga posterior. Puede quedar información antigua y un rechazo no gestionado, o un éxito aparente sin datos actualizados.

**Corrección:** carga de vista con `pending/error/retry`, mensajes persistentes y datos anteriores marcados como desactualizados. Separar “guardado confirmado” de “no se pudo refrescar”. Reintentar la lectura, no repetir a ciegas la mutación.

**Aceptación:** 400, 500, desconexión y respuesta tardía en cada recarga no generan `unhandledrejection`; conservan contexto, ofrecen reintento y no duplican escrituras.

### H06. El panel no tiene navegación utilizable en móvil

**Dónde:** `admin-ui/index.html:15-26,30-43`.

**Observado a 390×844:** menú colapsado sin control para abrirlo, y encabezado/salida ocultos. Se conserva el contenido de Resumen, pero no una vía visible para cambiar de sección o salir. La captura sintética muestra además las 14 tarjetas apiladas antes del primer gráfico.

**Corrección:** cabecera móvil persistente con título, menú accesible y salida disponible; mantener navegación de escritorio. Usar un único controlador de menú, no duplicar estados de navegación.

**Aceptación:** a 390, 768 y 1024 px se puede abrir/cerrar menú, visitar las 13 secciones y salir con teclado y táctil. El foco vuelve al botón al cerrar. Añadir 320 px/zoom como prueba de reflow, sin exigir que una tabla compleja elimine todo scroll interno.

### H07. Modales visuales sin comportamiento modal completo

**Dónde:** `admin-ui/index.html:54-80`; `admin-ui/app.js:85-88,102-128`.

El foco inicial entra en el motivo, pero `Shift+Tab` alcanza `#page-size` detrás del modal y Escape no cierra. Faltan rol, nombre y estado modal adecuados.

**Corrección:** elegir una implementación única para motivo y detalle: `<dialog>` nativo, si encaja con el soporte requerido, o controlador accesible sobre el marcado actual. Debe contener el foco, dejar el fondo inerte, permitir cancelar con Escape y devolver foco al disparador. El cierre no debe interpretarse como confirmación. Mantener el motivo obligatorio y los guards de servidor.

**Aceptación:** Tab/Shift+Tab no salen del diálogo; Escape cancela sin petición; el cierre devuelve foco; título y descripción son identificables por tecnología asistiva. Revisar también contenido largo y acciones sensibles. Referencia de comportamiento: [WAI-ARIA APG, diálogo modal](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/).

### H08. Los atributos de validación no evitan envíos inválidos

**Dónde:** `admin-ui/app.js:1057-1070`; revisar patrones similares en `1074-1126,1130-1167,535,584-591`.

**Observado:** poner `-1` en minutos y dejar motivo vacío produce `checkValidity() === false`, pero Guardar envía `welcomeMinutes:-1` y `reason:""`. Los inputs tienen restricciones, pero la acción va por `onclick` fuera de un formulario que active validación.

No se comprobó que el servidor acepte esos valores. El hallazgo es el envío inválido y la experiencia incoherente, no una elusión de seguridad.

**Corrección:** usar formularios y submit con restricciones pertinentes o ejecutar validación explícita antes de cualquier petición. Asociar errores al campo, conservarlos hasta corregir y enfocar el primer fallo. Validar motivo y conversiones numéricas según contrato. El servidor sigue siendo autoridad.

**Aceptación:** cero peticiones con rango inválido, motivo insuficiente o número no representable; errores visibles y asociados; no perder valores introducidos al fallar una petición válida.

### H09. Controles sin nombre accesible y acciones poco descubribles

**Dónde:** `admin-ui/app.js:232,243-249,667-674,1057-1060,1079-1105,1136-1143,1214-1249`; `index.html:45`.

Se observaron labels sin asociación real, paginadores solo con iconos y acciones de cuenta expuestas mediante `rowContextMenu` en `app.js:280`. No se demostró que toda alternativa de teclado sea imposible; sí la falta de un botón visible de acciones en la tabla inspeccionada.

**Corrección:** `label for`, nombres de botones, `<main>`, `aria-current` en navegación y columna Acciones con botón etiquetado por fila. Mantener clic derecho como atajo, no como vía principal. Representar booleanos y estados con texto además de icono/color.

**Aceptación:** selectores E2E por rol/nombre, recorrido completo con teclado, apertura táctil de acciones y acceso a detalle/resultados sin conocer gestos ocultos.

### H10. El acceso por bearer no muestra el fallo de red

**Dónde:** `admin-ui/login.js:31-38`; comparar el catch del acceso por credenciales en `22-29`.

**Observado:** con token sintético de 43 caracteres y petición abortada, `#error` queda vacío y aparece `Failed to fetch` sin gestionar.

**Corrección:** estado de envío y error recuperable, con `try/catch/finally`, reactivación del control y anuncio accesible. No convertir toda respuesta fallida en “token inválido”: distinguir red, sesión expirada y restricción temporal según respuesta, sin revelar información sensible. Revisar `autocomplete` de usuario/contraseña y no persistir bearer en almacenamiento del navegador.

**Aceptación:** red caída/timeout muestra mensaje persistente; no hay excepción no gestionada; botón recuperable; el token no aparece en URL, logs o captura de diagnóstico.

### H11. El watcher puede confundir ejecuciones de la misma clave

**Dónde:** `admin-ui/app.js:433-454,480-489`; `src/level-tests-admin.ts:183-193`; `src/http/routes/admin-level-tests.ts:70-73`.

El cliente correlaciona idioma/nivel/semana, mientras status entrega diez jobs recientes sin ID y el `202` llega antes de que necesariamente aparezca el nuevo registro.

**Casos a reproducir:**

1. Regenerar después de un fallo: la primera lectura conserva el fallo anterior y el watcher lo interpreta como terminal de la petición nueva.
2. Aparcar una ejecución y volver después de más de diez jobs ajenos: el resultado esperado ya no aparece y la clave permanece en cola.

**Modelo ya disponible:** `migrations/049_level_test_generation_runs.sql:6-16` define UUID por ejecución y ordinal `attempts` por clave. `src/level-tests-generation-jobs.ts:54-57,81-82,116-121` asigna ordinal e ID bajo el mecanismo existente. No se necesita una migración para exponer esos campos. `runLevelTestGenerationJob` no retorna actualmente ese ID y puede terminar sin crear ejecución, por ejemplo por `busy` o `exists`.

**Corrección:** distinguir log reciente, solicitud aceptada y ejecución registrada. Exponer el UUID de forma aditiva y consultar la última ejecución de las claves observadas sin depender del límite global de diez. El `202` debe conservar su compatibilidad y no prometer un `runId` que aún no existe.

Como mitigación acotada, el servidor puede devolver un baseline de ordinal por clave y el cliente ignorar terminales anteriores. Por sí solo no resuelve truncamiento ni demuestra que una ejecución concurrente pertenezca a esa solicitud. Para correlación fuerte, especificar cómo se registra/reserva una ejecución bajo los locks existentes y cómo se resuelven aceptación sin fila, rechazos y múltiples destinos. No crear otro modelo de jobs ni una cola nueva sin demostrar esa necesidad.

**Aceptación:** respuesta anterior terminal inmediatamente después del `202`, once jobs ajenos y recarga del panel: todos alcanzan el resultado correcto sin falso éxito, falso fallo ni espera infinita.

**Límite:** riesgo respaldado por código; falta prueba integrada de temporización. No modificar el scheduler ni reintroducir alertas por ausencia de exámenes como solución a un problema de correlación visual.

### H12. Resumen global bajo un contexto de resultados filtrado

**Dónde:** `admin-ui/app.js:982-983,1009-1015`; `src/http/routes/admin-level-test-attempts.ts:30-40`.

El listado aplica filtros; el resumen se consulta sin ellos. En la vista de alumno los KPI siguen siendo globales. El backend de summary tampoco admite todos los filtros del listado.

**Solución mínima:** rotular “Resumen global” y separar visualmente su ámbito del listado. Es preferible a ampliar inmediatamente la API si el operador solo necesita un contexto general.

**Evolución opcional:** KPI de la selección, compartiendo parser y predicados con listado/exportación para los filtros declarados compatibles. Requiere decisión de producto y prueba con dos alumnos distintos, no copiar SQL parecido en cada endpoint.

**Aceptación:** cada KPI declara su ámbito y periodo; al filtrar un alumno no se le atribuyen datos globales.

### H13. Estados de API y errores que parecen desaparecer

**Dónde:** `admin-ui/app.js:77-82,475,1305-1307`; `index.html:24`.

Los toasts se eliminan a los cuatro segundos sin región de anuncio; fallos del polling se silencian; la etiqueta verde “API” es estática, no una medida de conectividad.

**Corrección:** estados distintos para cargando, datos actualizados, datos antiguos y error. Mostrar fecha del último éxito cuando haya polling. Reservar toasts para confirmaciones no críticas; errores operativos permanecen con reintento y contexto. No presentar un badge verde como health-check si no se ha medido.

Evitar polling duplicado para cada indicador: reutilizar resultados de las lecturas necesarias. Una señal de conexión del panel no sustituye `/readyz` ni la observabilidad del backend. Los estados dinámicos deberían anunciarse sin robar foco; véase [W3C, mensajes de estado](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html).

**Aceptación:** desconexión, 403/429/500 y fallo de polling muestran estado honesto; “sin datos” no se confunde con “no se pudieron cargar”; no se repiten anuncios cada cinco segundos.

### H14. Jerarquía visual y legibilidad operacional

**Dónde:** `admin-ui/app.js:151-173,185-200,217-218,264`; captura de Resumen.

Catorce tarjetas reciben peso similar, aunque combinan stock, actividad, costes y contadores sin igual relevancia. En móvil empujan los gráficos muy abajo. Los nombres técnicos, UUID recortados y canvas sin equivalente textual aumentan el esfuerzo de interpretación.

**Propuesta:** cuatro o seis indicadores principales como máximo en la cabecera de Resumen; agrupar los demás por negocio y periodo. No decidir cuáles son “principales” sin observar las tareas de los operadores. Añadir resumen/tablas accesibles a gráficos y copiar identificadores completos mediante acciones explícitas.

**Aceptación:** las tareas más frecuentes y los fallos registrados se localizan sin recorrer todos los KPI; cada número tiene unidad/periodo/alcance; un gráfico tiene interpretación equivalente sin depender de color o visión.

### H15. Mensajes y pruebas que inducen una interpretación incorrecta

**Dónde:** comentario inicial de `tests/browser/admin-e2e.mjs` frente a sus aserciones actuales en `170-172`; aviso de cambios sin guardar en `admin-ui/app.js:572`; footer de `index.html:48-49`.

- El comentario E2E describe deshabilitar el test de conexión con formulario sucio; el comportamiento actual permite probar la configuración guardada. Corregir la descripción, no restaurar automáticamente el comportamiento antiguo.
- El aviso de cambios sin guardar navega a otra sección; no es un botón de Guardar. Aclarar qué hace y qué configuración se está probando.
- “Todas las acciones quedan auditadas” es demasiado amplio: hay lecturas sin auditoría. Ajustar el mensaje al alcance real, sin añadir auditoría masiva solo para justificar el footer.

**Aceptación:** ayuda, textos y tests describen el mismo comportamiento; el operador entiende “guardado” frente a “introducido pero no guardado”.

## 5. Propuesta de diseño e información

Esta sección es una dirección conceptual basada en el panel actual, **no un prototipo validado ni un rediseño visual implementado**.

### 5.1 Mantener el mapa, mejorar el contexto

Conservar los cuatro grupos y los hashes actuales. Añadir título contextual siempre visible, acciones principales estables y alcance/fecha cerca de los datos. No introducir un buscador global hasta demostrar que reduce una tarea frecuente; empezar por hacer visible la búsqueda de cuenta y sus acciones.

```text
Escritorio
┌ Navegación actual ┬ Título / contexto              Operador · Salir ┐
│ Operación         │ Periodo · última actualización · refrescar       │
│ Exámenes          ├ Indicadores principales, con unidad y alcance ───┤
│ Comercial         │ Incidencias registradas / tareas disponibles      │
│ Sistema           │ Vista de trabajo: tabla, revisión o formulario   │
└───────────────────┴─────────────────────────────────────────────────┘

Móvil
┌ Menú · Título de sección                      Cuenta / Salir ┐
│ Contexto y estado de carga                                   │
│ Acción principal                                            │
│ Indicadores prioritarios                                    │
│ Lista o tabla con scroll interno señalado                    │
└─────────────────────────────────────────────────────────────┘
```

“Incidencias” significa fallos realmente registrados y enlaces para investigarlos, **no alertas nuevas por no haberse generado un examen**. Conservar el worker optativo y la distinción entre configuración y actividad real.

### 5.2 Reglas visuales pequeñas y consistentes

| Área | Regla propuesta |
|---|---|
| Espaciado | Escala reducida reutilizable; no añadir márgenes distintos para cada vista. |
| Jerarquía | Un título de sección; subtítulos de bloques; metadatos secundarios, no todos como tarjetas principales. |
| Color | Estados con texto además de color. Rojo para fallo/destrucción, no para destacar cualquier dato. |
| Acciones | Una acción principal por bloque; acciones destructivas separadas y con objetivo explícito. |
| Formularios | Label asociado, ayuda estable, error por campo y resumen del fallo si procede. |
| Tablas | Filtros compatibles, orden remoto claro, rango/total, estado de carga y acción visible por fila. |
| Fechas y dinero | Unidad y periodo explícitos; conservar precisión/formato del contrato financiero. |
| Gráficos | Título, periodo, explicación breve y tabla o resumen de los mismos datos. |
| Vacíos | Distinguir sin registros, filtro sin coincidencias, permiso insuficiente y carga fallida. |
| Responsive | Shell utilizable sin sidebar fijo; scroll de tabla dentro de la tabla, no de toda la página. |

La revisión normativa de contraste, zoom y tecnologías asistivas se hará con mediciones. No inventar ratios, puntuaciones Lighthouse o conformidad que esta auditoría no ha medido.

## 6. Organización propuesta del frontend

### 6.1 Antes de extraer archivos

1. Reproducir H01–H05 con tests focalizados.
2. Corregir contrato de consultas, invalidación de preparación y disposición de recursos.
3. Definir el contexto de vista y aplicarlo a browse, semanas y resultados, que tienen fallos demostrados.
4. Solo entonces extraer responsabilidades para que la separación sea mecánica y verificable.

### 6.2 Estructura orientativa, no objetivo rígido

```text
admin-ui/
  index.html, login.html, app.css
  app.js                         # bootstrap y registro de rutas
  core/
    api.js                       # transporte y normalización de errores
    view-context.js              # identidad, cancelación y dispose
    router.js                    # hash actual y transiciones
  ui/
    dialogs.js                   # motivo/detalle con ciclo accesible
    feedback.js                  # carga, errores persistentes y anuncios
    format.js                    # formatos compartidos que ya se reutilizan
  features/
    browse.js
    exams/                       # separar generación, resultados y config si lo justifican
    commercial/                  # políticas/planes/campañas según responsabilidad real
    dashboards.js
  vendor/                        # dependencias locales actuales
```

No crear toda esta estructura vacía. Extraer cada módulo cuando tenga propietario de estado y usos reales; una función pequeña sin reutilización no exige un archivo. Mantener ES modules y assets estáticos sin añadir bundler solo por disponer de varios archivos. Al cambiar el tipo de script o el orden de carga, verificar CSP, rutas servidas por Caddy y disponibilidad de los vendors antes del bootstrap.

### 6.3 Contrato mínimo de una vista

- `mount`: crea la vista y sus recursos dentro del contenedor asignado.
- `load`: conserva identidad, estado de carga y criterios de consulta de esa vista.
- `isCurrent`: evita escribir tras desmontaje o al recibir respuestas antiguas.
- `dispose`: cancela lecturas, timers y listeners, y destruye Tabulator/Chart una vez.
- `mutate`: presenta objetivo y motivo, bloquea duplicados visuales y conserva el resultado conocido.

Abortar un fetch **no deshace** una campaña, publicación o job ya aceptado por el servidor. El contexto de vista solo controla el cliente; las operaciones duraderas necesitan identidad e idempotencia del lado servidor.

## 7. Plan de ejecución por fases

Los tamaños son orientativos, no promesas de calendario. Cada unidad debe poder revisarse y revertirse sin mezclar cambios financieros, navegación y rediseño visual. No usar un cambio de framework como dependencia de estas correcciones.

### Fase 0 — Baseline y casos reproducibles

**Objetivo:** disponer de evidencia repetible antes de tocar comportamiento.

- Recuperar el stack E2E efímero ya documentado en `services/api/tests/browser/README.md`, leyendo sus comandos y dependencias exactas.
- Convertir las sondas relevantes en regresiones mantenidas: no depender de los scripts temporales de esta auditoría.
- Fixtures mínimas: dos alumnos con resultados distintos; auditoría con varios `action`; ledger con varios `kind`; campaña preparada; job fallido anterior y una ejecución nueva de la misma clave.
- Capturas sintéticas de resumen, browse, exámenes, campaña y diálogo a 390 y escritorio.
- Establecer viewports/navegadores operativos soportados. El baseline inicial en Chromium no implica soporte validado de Safari/Firefox.

**Salida:** los nuevos tests reproducen los defectos relevantes; se conoce el resultado del E2E real actual. Si falla el baseline, registrar su causa antes de atribuirlo al refactor.

### Fase 1 — Integridad de la intención y corrección operativa

**Unidades:**

- F1.1: invalidar preparación de campañas y hacer explícita la confirmación (H01).
- F1.2: construir filtros/ordenación desde catálogo y eliminar doble semántica local/remota (H02).
- F1.3: validar formularios antes de enviar y mejorar errores por campo (H08).
- F1.4: capturar errores de recarga/login y diferenciar mutación confirmada de refresh fallido (H05/H10).
- F1.5: declarar claramente alcance global de KPI, sin ampliar aún la API (H12).

**Dependencias:** baseline; no requiere nueva estructura de carpetas.
**Criterio de cierre:** regresiones de cada caso pasan; mutaciones inválidas no salen del navegador; API sigue validando; no hay escrituras duplicadas en el stack aislado.

### Fase 2 — Propiedad del estado y ciclo de vida

- F2.1: corregir sombreado del registro de tablas con test de destroy (H04).
- F2.2: contexto de vista y descarte/cancelación de respuestas; empezar por resultados, semanas y browse (H03).
- F2.3: unificar propiedad de polling y recursos; callbacks inactivos no modifican DOM ajeno.
- F2.4: loading/error/retry y frescura de datos; eliminar badge de conectividad ficticio o conectarlo a una señal real (H13).

**Criterio de cierre:** pruebas con retraso, inversión de respuestas, cambios rápidos de ruta y error de polling pasan; cada recurso se destruye una vez. Una sonda de memoria puede complementar, no sustituir, la comprobación determinista.

### Fase 3 — Shell y accesibilidad funcional

- F3.1: menú/cabecera/salida responsive (H06).
- F3.2: diálogo accesible compartido para motivo y detalle (H07).
- F3.3: nombres accesibles, landmarks, navegación activa y acciones visibles por fila (H09).
- F3.4: feedback anunciado sin robar foco y alternativas textuales de gráficos (H13/H14).

**Criterio de cierre:** completar con teclado la búsqueda de cuenta, apertura/cancelación de acción, revisión de examen y formulario comercial; móvil puede navegar y salir; lector de pantalla y zoom se verifican manualmente antes de declarar conformidad.

No esperar a un rediseño visual total para recuperar navegación móvil o corregir foco: son unidades pequeñas y verificables.

### Fase 4 — Seguimiento durable de generación

- F4.1: reproducir las dos carreras de H11 en el stack real.
- F4.2: exponer UUID y ordinal persistidos de forma aditiva; especificar aceptación frente a registro efectivo y no prometer identidad antes de que exista. Mantener clientes actuales mientras se adopta.
- F4.3: consulta por identidad observada, separada de los diez eventos recientes.
- F4.4: recuperación al recargar y estado desconocido explícito cuando no pueda confirmarse el resultado.

**Criterio de cierre:** historial desplazado, fallo anterior, doble petición concurrente y recarga no confunden intentos. Mantener locks, auditoría, publicación y significado actual del `202`. No prometer que `202` es ejecución finalizada.

Esta fase tiene riesgo contractual/concurrente: requiere revisión independiente y pruebas PostgreSQL además del E2E. Confirmar el modelo existente antes de añadir un endpoint o migración.

### Fase 5 — Extracción incremental y diseño operativo

- F5.1: extraer core/transporte/diálogos y las vistas ya estabilizadas; conservar hashes y contratos.
- F5.2: reorganizar Resumen con operadores, priorizando tareas reales; evitar otro dashboard genérico de métricas.
- F5.3: nombres comprensibles, unidades, periodos, densidad de tablas y estados vacíos consistentes.
- F5.4: sincronizar comentarios E2E, ayuda, footer y documentación (H15).

**Criterio de cierre:** no cambia el inventario de rutas/acciones ni los resultados de consultas por el simple movimiento de código; assets y CSP funcionan; capturas nuevas aprobadas como cambio intencional, no como actualización automática de snapshots.

### Fase 6 — Cierre y despliegue controlado

- E2E real completo del panel en stack efímero, además de los casos nuevos.
- Tests de API afectados y `make test-api` si se cambian contratos/backend. No asumir que ese comando ejecuta el runner de navegador.
- Type-check del backend si se toca TypeScript; comprobación de sintaxis y pruebas específicas del JS del panel. `tsc --noEmit` por sí solo no acredita las vistas JS.
- Verificación de assets/CSP y smoke autenticado con operador sintético; nunca compartir cookies o claves reales en evidencias.
- Rollback por unidad de trabajo, con compatibilidad aditiva cliente/servidor cuando corresponda.
- Tras publicar, vigilar errores JS, fallos de recarga, latencia de las lecturas y operaciones inciertas, sin enviar PII ni secretos a telemetría.

**Salida:** informe con comandos ejecutados, resultados, capturas sintéticas persistidas en el sistema de artefactos del proyecto y límites reales. No declarar éxito por haber marcado checkboxes.

## 8. Matriz de aceptación mínima

| Flujo | Prueba decisiva | Lo que debe preservarse |
|---|---|---|
| Login | Red caída, credenciales rechazadas, sesión recuperable. | Sesión/cookies y controles de origen actuales; no registrar secretos. |
| Navegación | Cambio de ruta durante peticiones retenidas. | Hash/título/contenido de la ruta nueva. |
| Menú móvil | Abrir/cerrar, recorrer secciones y salir. | Misma autorización y mismas acciones que escritorio. |
| Browse | Operadores permitidos, sort permitido y paginación. | Whitelist, límites, redacción de columnas y consulta remota. |
| Campañas | Editar tras preparar, doble clic y respuesta incierta. | Snapshot confirmado, identidad y concesión auditable sin duplicados. |
| Política/planes | Valores inválidos y refresh fallido tras guardado. | Validación de servidor y distinción entre guardar y refrescar. |
| Generación | Terminal anterior, >10 jobs ajenos y recarga. | Locks, política de regeneración y estado real del job. |
| Revisión | Abrir diálogo, cancelar por Escape, confirmar con motivo. | Motivo auditado y reglas de publicación/retirada. |
| Resultados | Dos alumnos, filtros y resumen de ámbito explícito. | Datos autoritativos, privacidad y contrato de exportación. |
| Recursos | Varias entradas/salidas de tabla y gráficos. | Destrucción única; nada escribe sobre vistas desmontadas. |
| Feedback | 403/429/500/offline en carga y polling. | Mensaje recuperable; no inventar vacío, éxito o estado saludable. |

## 9. Mejoras nuevas que sí podrían aportar valor

Estas propuestas **no son obligaciones de este informe ni fallos confirmados**. Se deben priorizar observando tareas reales, no por completar un catálogo de funcionalidades.

| Mejora | Valor esperado | Condición para hacerla | Condición para descartarla |
|---|---|---|---|
| Entrada visible de búsqueda de alumno | Reduce saltos por tablas técnicas. | Soporte busca cuentas con frecuencia y ya existe consulta segura reutilizable. | El flujo actual es ocasional y se resuelve con acciones visibles. |
| Bandeja de tareas/incidencias registradas | Revisión de drafts o fallos concretos con enlace contextual. | Existe fuente fiable y una acción clara por elemento. | Solo añade números redundantes o infiere fallos por falta de generación. |
| Filtros compartibles mediante URL | Reproducir una vista de trabajo y volver a ella. | Codificación/validación clara y sin PII innecesaria en URL. | Requiere exponer emails, tokens o datos sensibles en historial/logs. |
| Preferencia de densidad de tablas | Mejora escritorio con listados grandes. | Se demuestra que la densidad actual ralentiza el trabajo. | Solo añade controles y CSS sin tarea beneficiada. |
| KPI realmente filtrados | Evita consultas manuales de un alumno o cohorte. | Operadores requieren analítica contextual, no solo listado. | Basta rotular y separar resumen global. |
| Permisos por función | Reduce superficie operativa de distintos roles. | Hay operadores con responsabilidades distintas y política definida. | Solo existe un rol y no hay un problema de autorización demostrado. |

No convertir permisos por función, buscador global o analítica nueva en dependencias de arreglar campañas, errores, móvil y foco.

## 10. Invariantes y estrategia para no romper nada

1. **Servidor como autoridad:** origen/CSRF, sesión, autorización, rangos, transacciones y motivo auditado no se trasladan al navegador.
2. **Datos y efectos:** no editar migraciones aplicadas, no reescribir ledger ni inventar compensaciones para una prueba. Fixtures solo en BD efímera.
3. **Navegación:** conservar hashes/deep links y filtros existentes; restaurar estado solo si sigue siendo válido y no contiene secretos.
4. **Configuración LLM:** distinguir valores introducidos de configuración guardada; no declarar que una prueba de conexión usa cambios sin guardar.
5. **Generación:** worker optativo; no considerar ausencia de exámenes como alerta nueva. Las alertas existentes de fallos de generación no se sustituyen por un badge del panel.
6. **Cliente estático:** no añadir CDNs ni relajar CSP para facilitar la modularización. Preservar assets, rutas, caché y orden de inicialización necesarios.
7. **Compatibilidad aditiva:** si se añade identidad de ejecución o filtros summary, probar cliente anterior y nuevo contra la transición, sin eliminar campos consumidos.
8. **Rollback:** cada corrección/refactor en unidad separable. Revertir UI no deshace campañas o publicaciones ya ejecutadas; nunca usar un rollback de código como plan de recuperación financiera.

### Riesgo por tipo de trabajo

- Copys, labels y presentación: bajo, con regresión de accesibilidad/selección.
- Contexto de vista y cancelación: medio/alto por orden asíncrono; pruebas de carreras y revisión independiente.
- Campañas: alto por efectos económicos; probar intención, idempotencia y BD aislada, aunque el cambio inicial sea visual.
- Identidad de jobs y contratos backend: alto por concurrencia/compatibilidad; implementación aditiva y verificación independiente.
- Extracción de archivos: no es “sin riesgo” por ser movimiento; verificar globals, handlers, paths y CSP.

## 11. Decisiones abiertas antes de implementar

No bloquean las correcciones demostradas. Deben resolverse solo al entrar en la fase correspondiente:

- ¿Qué dispositivos y navegadores son operativos obligatorios? Hasta acordarlo, no afirmar soporte validado de móvil/Safari.
- ¿Resumen global o KPI de selección? Empezar con ámbito explícito evita ampliar la API por suposición.
- ¿Qué tres tareas hacen más los operadores? Sirve para jerarquizar Resumen; no hace falta preguntar por toda la arquitectura.
- ¿Se necesitan permisos distintos por función? No diseñar RBAC sin un modelo de responsabilidades real.

## 12. Orden recomendado para empezar

**Primera entrega:** H01/H02/H05/H08/H10/H12, separando campañas del resto por riesgo. **Segunda:** H03/H04 y estados de carga. **En paralelo solo si no hay superficies compartidas:** navegación móvil y accesibilidad de diálogos. Después, tracking durable y extracción de módulos estabilizados.

La recomendación final es **corregir primero los fallos de intención, contratos y estado; mejorar después accesibilidad y diseño operativo; reorganizar solo las responsabilidades que ya han demostrado necesitar una frontera**. Así el panel mejora de verdad sin convertir una auditoría en una reescritura.
