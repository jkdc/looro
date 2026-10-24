# Verify report — admin-exam-operations

Fecha: 2026-10-22 · Rama `feature/learning-platform`

## Verificado

- `npx tsc --noEmit` (services/api): limpio.
- `make test-api` (suite completa con PG 17 efímero): **559/559 pass**, 0 fallos. Incluye:
  - `tests/level-tests-admin.test.ts` (17 tests, incluye el lanzador on-demand:
    omisión de claves aprobadas, nivel único, validaciones 400/401 y auditoría): listado de semanas con filtros status/language/level/rango de semanas/rango de fechas, orden con whitelist, paginación con total estable, 400 en query malformada, 401/403 (auth + Origin), `no-store`; detalle con pool/variants/review y 404/400; regeneración (202 + auditoría, 409 en clave aprobada sin auditoría, 400 en semana pasada/razón corta/claves inválidas, CSRF 403, 401 sin sesión); `jobs[]` en `/status` conservando `latestJob`.
  - `tests/level-tests-attempts.test.ts` (9 tests): listado de resultados con joins snapshot/alumno, filtros account/email/language/level/state/passed/rangos, orden por overall, paginación; detalle con answers + result y 404 cross-account; summary con KPIs y desglose por semana y por nivel; export CSV con cabecera según spec, escape de fórmulas (`'=`) y tope 10 000 (413 `export_too_large`).
  - Suite preexistente completa en verde.
- `node --check services/api/admin-ui/app.js`: OK (UI sin build).

## No verificado (requiere humano)

- Nada de lo relativo a este change: los flujos de panel que antes quedaban
  fuera (listado con filtros, moderación con motivo, regeneración con monitor
  de jobs, resultados con filtros, detalle, vista por alumno) están cubiertos
  ahora por el E2E de navegador real (`services/api/tests/browser/admin-e2e.mjs`,
  26/26 checks con stack Docker aislado + LLM stub + Chromium local, incluyendo
  el lanzador de generación desde el panel y el watcher de progreso: línea de
  estado en vivo, estado final por clave y refresco automático de la tabla).
  4 tandas completas consecutivas en verde tras el endurecimiento del watcher.

### Diagnóstico del lanzamiento "sin peticiones" (2026-10-22)

Reproducido contra el stack local del operador: el fallo no era la petición sino
el motivo. Con el motivo vacío o de menos de 3 caracteres, `ask()` resolvía y el
manejador abandonaba el flujo con un toast efímero («Motivo obligatorio»), sin
request, sin línea de estado y sin rastro en Network. Correcciones:
- Confirmar deshabilitado hasta que el motivo tenga ≥3 caracteres, contador en
  vivo («faltan N»), autofoco en el textarea y objetivo marcado como solo lectura.
- `Cache-Control: no-cache` para `*.js`/`*.css` en Caddy (el `?v=` estaba
  congelado en 5 y el navegador podía reutilizar un `app.js` viejo) + bump a `?v=6`.
- Regresión cubierta en el E2E: «confirm stays disabled until the reason is real»
  y «launch confirm is disabled until a reason is typed».
- Regeneración concurrente real (advisory lock contra CLI/scheduler a la vez):
  cubierta indirectamente por los tests del módulo de jobs, no reproducida en
  paralelo real aquí.

## Notas

- Se repararon en el mismo change dos tests preexistentes con la semana ISO congelada (`202642`), que rompían al cambiar de semana natural; ahora derivan de `weekIndex(now)`.
- Revisión final de rama por revisor independiente (subagent fresh): 3 hallazgos Important corregidos en un solo fix pass (casts seguros sobre `result` jsonb sincronizado por clientes; guard de advisory lock en `approveGeneratedWeek` → 409 `level_test_regeneration_in_progress` durante una regeneración; filtros por alumno en la UI). 6 minor diferidos (documentados en el ledger del plan).

## Corrección de la revisión de rama (2026-10-24)

La revisión señaló cuatro defectos `Important` y un minor. Todos están
corregidos en `services/api/admin-ui/app.js`, con la lógica pura extraída a
módulos cubiertos por tests rápidos y por el E2E de navegador.

### Hallazgos corregidos

1. **Una respuesta tardía de Resultados podía pintar sobre otra pantalla o
   publicar un error de filtro obsoleto.**
   `renderExamAttempts` ahora recibe el contexto de la vista: registra el
   `AbortController` de sus dos lecturas, aborta al navegar y descarta la
   pintura si la vista dejó de ser la actual (`context.isCurrent()`), además del
   epoch que ya existía. El mismo epoch gobierna ahora fallos y reintentos: una
   recarga antigua no puede mostrar un aviso obsoleto sobre una consulta más
   reciente que ya terminó.
2. **Los filtros y la paginación de exámenes no manejaban errores.** Las
   recargas de la tabla de semanas pasan por `renderExamWeeksSafely`: conservan
   el listado visible, muestran `#weeks-reload-error` con el código real y
   «Reintentar», y el error de ruta sigue usando el genérico de la vista. Cubre
   también el refresco posterior a un job terminado.
3. **`is-null`/`not-null` no se enviaban y su operando seguía editable.** El
   servidor ya aceptaba `q_campo=is-null:`; el panel descartaba el filtro sin
   operando y permitía escribir un valor que el servidor rechazaba. Ahora
   `normalizeBrowseFilters`/`browseFilterQuery`
   (`admin-ui/features/browse-filters.js`) conservan los unarios sin operando,
   descartan los demás sin valor y validan campo/operador contra el catálogo; el
   `<select>` deshabilita y limpia el input del operando.
4. **Los fallos de sondeo del watcher eran silenciosos.** Un poll fallido deja
   de parecer confirmado: `watchFailureStatus`
   (`admin-ui/features/generation-watch.js`) pinta «Sin confirmación del estado
   de generación desde las HH:MM:SS…» en `#launch-status` (tono warning,
   `aria-live`), marca `#jobs-monitor[data-stale]` y mantiene el reintento cada
   5 s; el siguiente poll correcto limpia el aviso.
5. **Foco al cancelar el diálogo de motivo.** `ask()` conserva el invocador
   explícito cuando lo recibe y, si no, captura el elemento activo antes de
   abrir el diálogo. Cancelar devuelve el foco tanto para moderación como para
   los lanzamientos/regeneraciones genéricos.
6. **Recargas solapadas del listado de semanas.** Error y `aria-busy` están
   ligados a la identidad del render más reciente: una respuesta fallida
   antigua no puede limpiar el estado de carga ni instalar un reintento para
   filtros obsoletos.

### Pruebas añadidas

- `services/api/tests/admin-ui-state.test.ts` — 7 tests Node sin Docker:
  unarios con operando vacío, descarte de operandos vacíos, campos/operadores no
  válidos, serialización `is-null:`/`not-null:` y el copy de pérdida de
  confirmación con y sin hora previa.
- `services/api/tests/browser/admin-e2e.mjs` — checks nuevos: filtros unarios
  del explorador (petición `is-null:`/`not-null:`, operando deshabilitado,
  listado intacto), respuesta antigua que no pisa la nueva, error de lectura
  persistente con filas antiguas y reintento, recarga de semanas fallida con
  reintento, aviso de poll perdido y recuperación, resultados tardíos que no
  pisan otra vista, orden de fallo/éxito en filtros solapados de semanas y
  resultados, y foco devuelto al cancelar diálogos de revisión y generación.
- El E2E pasa de 26 a **87 checks**.
- `AGENTS.md` incorpora una política de pruebas obligatoria (cobertura por flujo,
  regresión por hallazgo, lógica pura extraída y E2E para los flujos montados).

### Verificación ejecutada (2026-10-24)

- `node --check` de `admin-ui/app.js`, los dos módulos de `features/` y
  `tests/browser/admin-e2e.mjs`: OK.
- `npx tsc --noEmit` (services/api): limpio.
- `npx tsx --test tests/admin-ui-state.test.ts`: **7/7 pass**.
- `make test-api` (stack efímero, PG 17, imagen compilada): **651/651 pass**, 0
  fallos; el stack se limpió al terminar.
- E2E de navegador real sobre un stack aislado recién creado (Docker +
  PostgreSQL + LLM stub + Chromium local): **87/87 checks**, 0 fallos. El run
  final fue `E2E_EVIDENCE_DIR=.superpowers/sdd/2026-10-24-admin-panel-hardening/evidence-review-final3 node services/api/tests/browser/admin-e2e.mjs`.
- Revisión independiente final del diff y probes de orden de respuesta: sin
  bloqueantes; confirmó guardas de epoch en errores/reintentos de Resultados,
  propiedad del render en Semanas, y recuperación del foco en diálogos.
- `npm test` en host sin `TEST_DATABASE_URL`: 651 tests, 209 pass, 442 skipped
  (suites que requieren BD); la verificación real es `make test-api`.

### Notas de la verificación

- La primera pasada de `make test-api` falló con `ERR_MODULE_NOT_FOUND` en
  `dist/tests/admin-ui-state.test.js`: el stack de humo ejecuta
  `dist/tests/*.test.js` y los módulos de `admin-ui/features` no existían bajo
  `dist`. El Dockerfile ahora copia `admin-ui/features` a
  `dist/admin-ui/features`, de modo que el test compilado importa el mismo
  fichero que sirve el panel.
- Esa pasada también destapó un error del propio E2E: la limpieza del check
  unario intentaba volver a `eq` en una columna `timestamptz`, que solo ofrece
  `gte/lte/is-null/not-null`. Ahora vuelve a `gte` y el helper `selectOption`
  incluye las opciones reales cuando la selección no existe, para que el fallo
  no vuelva a confundirse con un defecto del panel.
- Un run browser intermedio terminó en timeout esperando la respuesta de
  generación; se corrigió el setup para recrear el stack y esperar a que
  terminen bootstrap y seed antes del navegador. El run final limpio pasó.
- No se ejecutaron suites de Android ni iOS: el cambio es solo del panel web.
