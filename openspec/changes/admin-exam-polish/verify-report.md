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

`services/api/tests/browser/admin-e2e.mjs`: **37/37 checks**, dos tandas
consecutivas desde pila limpia con el código final (el escenario muta estado,
así que cada tanda exige `down -v`). Nuevos checks:

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
