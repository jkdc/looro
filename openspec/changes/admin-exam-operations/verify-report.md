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
  25/25 checks con stack Docker aislado + LLM stub + Chromium local, incluyendo
  el lanzador de generación desde el panel y el watcher de progreso: línea de
  estado en vivo, estado final por clave y refresco automático de la tabla).
  4 tandas completas consecutivas en verde tras el endurecimiento del watcher.
- Regeneración concurrente real (advisory lock contra CLI/scheduler a la vez):
  cubierta indirectamente por los tests del módulo de jobs, no reproducida en
  paralelo real aquí.

## Notas

- Se repararon en el mismo change dos tests preexistentes con la semana ISO congelada (`202642`), que rompían al cambiar de semana natural; ahora derivan de `weekIndex(now)`.
- Revisión final de rama por revisor independiente (subagent fresh): 3 hallazgos Important corregidos en un solo fix pass (casts seguros sobre `result` jsonb sincronizado por clientes; guard de advisory lock en `approveGeneratedWeek` → 409 `level_test_regeneration_in_progress` durante una regeneración; filtros por alumno en la UI). 6 minor diferidos (documentados en el ledger del plan).
