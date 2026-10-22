# Verify report — admin-exam-ux

Fecha: 2026-10-22 · Rama `feature/learning-platform`

## Qué se verificó

### Servidor

- `npx tsc --noEmit` (services/api): limpio.
- `make test-api` (suite completa, PG 17 efímero): **559/559 pass**.
- `tests/level-tests-admin.test.ts`: **17/17**. El detalle de semana añade
  `tokensIn`, `tokensOut`, `createdAt`, `approvedAt`, `approvedBy`,
  `promptVersion` y `judgeVersion` sin una segunda petición.
- Bug de contrato corregido por el camino: `tokens_in`/`tokens_out` son BIGINT y
  `pg` los devolvía como string; `weekRow` ahora los coacciona con `Number()`.
  El test de detalle lo fija (`typeof === 'number'`).

### Panel (sin build; `node --check` OK)

- Grupo de sidebar `Exámenes` con tres entradas: `#exam-weeks`
  (Generar y revisar), `#exam-results` (Resultados), `#ia-config`
  (Configuración de IA). El grupo es dato (`group` en `NAV`), no markup duplicado.
- `#ia-config` contiene el formulario de proveedor completo (catálogo, modelo,
  URL base, clave, publicación, verificación, variantes, prueba de conexión y
  aviso de cambios sin guardar con enlace a los borradores pendientes).
  `#exam-weeks` no hace ninguna llamada a `/v1/admin/llm-config`.
- `#level-tests` redirige con `location.replace` a `#exam-weeks` (sin entrada
  extra en el historial).
- `#exam/<uuid>`: cabecera (modelo, juez, tokens, fechas, versiones), banner de
  ítems marcados, pestañas por variante + pool, secciones en orden de examen
  (reconocimiento → escritura → lectura → escucha), opción aceptada marcada,
  respuestas aceptadas, explicación, veredicto del juez con motivo y acciones
  fijas de aprobar/rechazar. Pasajes de lectura/escritura una vez por
  `passageId`; un `passageId` inexistente deja el ítem suelto, nunca vacío.
  Id desconocido → estado «Examen no encontrado» con vuelta a la lista.
- Toda interpolación de contenido LLM pasa por `escapeHtml()`.

### E2E de navegador (stack Docker aislado + LLM stub + Chromium local)

`services/api/tests/browser/admin-e2e.mjs`: **35/35 checks**, tres tandas
consecutivas desde pila limpia (`down -v` + `seed-generate`; el escenario muta
el estado, así que cada tanda necesita pila nueva). Nuevos checks:

1. El sidebar agrupa las tres superficies de examen y ya no existe el enlace
   `#level-tests`.
2. `#level-tests` aterriza en `#exam-weeks`; allí no hay formulario de proveedor
   y sí lanzador, monitor y tabla.
3. `#ia-config` es autosuficiente: sin lanzador, con el aviso «Dónde se genera».
4. La página de revisión agrupa por secciones en orden de examen y no filtra
   JSON crudo.
5. Marca la opción aceptada (✓), muestra el veredicto del juez y las respuestas
   aceptadas de los ítems de escritura.
6. Cambia de variante sin refetch y muestra el pool.
7. Aprueba desde la página de revisión con motivo obligatorio, refleja el nuevo
   estado y el listado muestra `approved`.
8. Un id inexistente rinde el estado de no encontrado.

Evidencias: `e2e-evidence/t11-browser-e2e/10c-review-page.png`,
`10d-review-approved.png`.

De paso se estabilizó un flake preexistente del E2E de resultados: aplicar un
filtro re-renderiza la barra completa y el `fill` podía perderse entre el
hit-test y el tecleo. Ahora el escenario teclea y pulsa en bucle hasta que la
petición lleva de verdad el valor escrito (`applyFilter`).

## No verificado

- Nada funcional pendiente. La suite completa de la API se ejecuta en CI con
  `make test-api`; el cambio de servidor se limita a `levelTestWeekDetail` y
  `weekRow` (cobertura directa en `level-tests-admin.test.ts`).
- Revisión visual del operador sobre datos reales: pendiente de su visto bueno
  (el E2E usa contenido del stub).

## Posterior

El pulido de estados, el diálogo de error y la agrupación completa del menú
viven en `openspec/changes/admin-exam-polish/`; ese cambio deja el E2E en 37
checks (ver su `verify-report.md`).
