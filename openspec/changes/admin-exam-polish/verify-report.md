# Verify report — admin-exam-polish

Fecha: 2026-10-22 · Rama `feature/learning-platform`

## Qué se verificó

### Servidor

- `npx tsc --noEmit` (services/api): limpio.
- `make test-api` (suite completa, PG 17 efímero): **560/560 pass** (+1: test
  puro del saneador).
- `tests/level-tests-generation-jobs.test.ts` + `tests/level-tests-admin.test.ts`:
  **20/20**. El test del saneador fija las tres propiedades que importan:
  conserva los mensajes propios (`reading: only 2 passages (needed 4)`), descarta
  cuerpos de proveedor, stack traces y texto con mayúsculas/no ASCII, y cae al
  código grueso cuando no queda nada (máximo 10 entradas).
- Test del endpoint de estado: un job `failed` devuelve
  `errorCode: "generation_failed"` **y** `errors` con el motivo saneado. Sin ese
  campo, el rojo no tendría nada que explicar.
- Migración `047_level_test_generation_job_error_details.sql`: columna `jsonb`
  con `DEFAULT '[]'` y constraint de forma (array, ≤ 10). Idempotente dentro del
  runner de migraciones; el stack de humo la aplica desde cero.

### Panel (sin build; servido por Caddy, `node --check` OK)

- **Causa raíz encontrada y corregida**: este Tabler no define `.badge-success`
  ni `.badge-danger`; solo `.badge` (gris neutro) y las utilidades
  `bg-*-lt`. Comprobado en el navegador: `badge-danger` y `badge-success`
  calculaban exactamente `color: rgb(107, 114, 128)` sobre
  `background: rgb(249, 250, 251)` — el mismo gris para todo. El gris no venía
  del mapeo de tonos, venía de clases inexistentes. Todos los badges de tono
  (estados, veredictos del juez, respuestas aceptadas, intentos) pasan ahora a
  `bg-<tono>-lt`, que pinta color saturado sobre tinte al 10 %.
- Semanas: `draft` ámbar, `approved` verde, `rejected` rojo, `retired` gris.
  Jobs: `running` azul, `finished` verde, `failed` rojo, `unknown` ámbar. Una
  sola fuente de verdad (`BADGE_COLORS`): se eliminó `EXAM_STATUS_TONES`, que
  duplicaba el mapeo de semanas.
- El código de error del monitor y el de «Último job» son botones: abren un
  diálogo con el estado, el código grueso, la explicación por patrón, el
  contexto (idioma, nivel, semana, intentos, fechas) y la lista literal de
  motivos saneados. `detail()` sigue escribiendo `textContent`; el nuevo
  `detailHtml()` solo se usa para este payload estructurado y todo su contenido
  pasa por `escapeHtml()`.
- Menú: cuatro secciones (`Operación`, `Exámenes`, `Comercial`, `Sistema`) con
  cabeceras separadoras (`pointer-events: none`, nunca enlaces) y entrada activa
  restringida a `#nav a.nav-link`. `Tablas` y `Política bienvenida` salen de la
  zona de exámenes y pasan a `Sistema`.
- `docs/admin-panel.md`: tabla del sidebar, diálogo de error y corrección de dos
  rutas que documentaban `#tablas` y `#política` (la real es `#browse` y
  `#policy`).

### E2E de navegador (stack Docker aislado + LLM stub + Chromium local)

`services/api/tests/browser/admin-e2e.mjs`: **41/41 checks**, dos tandas
consecutivas desde pila limpia con el código final (el escenario muta estado,
así que cada tanda exige `down -v`). Los 37 primeros cubren el pulido visual y
el diálogo; los cuatro últimos, la vigilancia de generación (abajo). Nuevos
checks:

1. El menú declara las cuatro secciones y cada enlace pertenece a la suya
   (comparación de orden en el DOM: `#exam-weeks`/`#exam-results`/`#ia-config`
   entre `Exámenes` y `Comercial`; `#plans`/`#campaigns` entre `Comercial` y
   `Sistema`; `#audit` después de `Sistema`).
2. Colores por severidad comprobados sobre el **estilo calculado**, no sobre la
   clase: el texto de `failed` debe dominar en rojo y el de `finished` en verde.
   Es el test que habría cazado el fallo de `.badge-success` inexistente.
3. El diálogo de error se abre desde el monitor y muestra el código, el motivo
   por sección (`reading: only 2 passages (needed 4)`, `writing: empty
   response`) y el bloque de detalle.
4. `approved`/`rejected` en el listado llevan tinte verde/rojo.

El fixture del job fallido vive en `bootstrap.mjs` (en/B2, semana anterior,
`generation_failed`, dos motivos): determinista, sin depender de que el stub
falle. Evidencias: `e2e-evidence/t11-browser-e2e/10-regeneration-monitor.png`
(estados) y `10e-job-error.png` (diálogo).

## No verificado

- Navegación del diálogo hacia `Configuración de IA`: el texto lo indica, pero
  el modal no enlaza (evita dejar un modal abierto sobre otra vista). Si se
  quiere enlace directo, se añade con cierre explícito del modal.
- Contraste en tema claro del panel (el sidebar es oscuro por `data-bs-theme`):
  revisado en captura, no con una auditoría de contraste formal.
- Revisión visual del operador sobre datos reales: pendiente de su visto bueno.


## Vigilancia de generación (5 s)

La cadencia pasó del literal inline de 2 s a `GENERATION_POLL_MS = 5_000`, con
arranque también para generaciones ya en `running` al montar la vista. Cuatro
checks E2E (el endpoint de estado se enmascara con `page.route` porque el stub
termina en milisegundos y no habría hueco de 5 s que medir; el mock reenvía la
respuesta real para conservar el `weekId`):

1. **Cadencia y resiliencia**: con un 500 en el primer tick, los huecos entre
   sondeo y sondeo quedan en 4-9 s (medidos 5,0 s) y la vigilancia continúa.
2. **Sin fuga de temporizador**: un tick directo que ve el job terminado no
   deja vivo el temporizador programado; el watch nuevo mantiene un único bucle.
3. **Aparcamiento**: al salir de la vista como mucho corre el tick ya lanzado y
   no queda ningún temporizador en segundo plano.
4. **Reanudación**: al volver, la vigilancia retoma, resuelve las claves
   pendientes y vuelve a parar.

### Defectos que encontraron los tests (y se corrigieron)

- **Router no reentrante**: dos `hashchange` seguidos lanzaban dos renders
  concurrentes; el más lento borraba la vista nueva y armaba el watcher dos
  veces. Ahora los renders se serializan y se coalescen (`route()` → un render
  en vuelo, gana el hash final) y los enlaces profundos siguen intactos.
- **Fuga del temporizador entre ejecuciones**: el handle vivía en el objeto del
  watch; al terminar, el tick directo dejaba armado el temporizador antiguo y,
  al dispararse, ponía a `null` el handle del watch **nuevo**, con lo que dos
  bucles de 5 s corrían en paralelo (huecos de 5 s + 300 ms). El handle es ahora
  módulo-global, `cancelGenerationTimer()` se llama al reemplazar y al cerrar, y
  un solo tick puede estar en vuelo (`generationTicking`).
- **CSP bloqueaba `white-space: pre-wrap`**: el `style` inline de `#detail-body`
  lo rechazaba `style-src 'self'`, así que el JSON de `detail()` perdía la
  indentación. La regla vive en `app.css`; verificado por estilo calculado
  (`pre-wrap`) y cero violaciones de consola al abrir el diálogo de error.

## Lanzar sobre una semana existente

Antes: `draft` y `rejected` se encolaban y el runner salía con `exists`, dejando
el job en `finished`; el panel pintaba la fila en verde y decía «generación
completada» sin haber generado nada. El planificador ahora marca
`skipped_existing` (los aprobados siguen como `skipped_approved`), no crea job y
el panel separa ambos casos, avisa en tono `warning` y apunta a «Regenerar».

- Test de integración nuevo: con A1 en `draft`, A2 en `rejected` y B1 ausente, la
  respuesta marca dos `skipped_existing` y un `started`, y la tabla de jobs solo
  contiene B1. Se escribió **antes** del cambio y se vio fallar
  (`'started' !== 'skipped_existing'`).
- Suite API completa: **561/561** (0 fallos, 0 saltados) contra PostgreSQL 17
  efímero; `npx tsc --noEmit` limpio.
- E2E: el chequeo nuevo falló primero con el panel antiguo (toast de éxito
  «aprobados omitidos», sin aviso) y pasa tras el arreglo. **42/42** desde pila
  limpia. Evidencia: `e2e-evidence/t11-browser-e2e/10g-launch-skipped-existing.png`.
