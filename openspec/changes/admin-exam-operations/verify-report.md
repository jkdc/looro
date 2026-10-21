# Verify report — admin-exam-operations

Fecha: 2026-10-22 · Rama `feature/learning-platform`

## Verificado

- `npx tsc --noEmit` (services/api): limpio.
- `make test-api` (suite completa con PG 17 efímero): **556/556 pass**, 0 fallos. Incluye:
  - `tests/level-tests-admin.test.ts` (13 tests): listado de semanas con filtros status/language/level/rango de semanas/rango de fechas, orden con whitelist, paginación con total estable, 400 en query malformada, 401/403 (auth + Origin), `no-store`; detalle con pool/variants/review y 404/400; regeneración (202 + auditoría, 409 en clave aprobada sin auditoría, 400 en semana pasada/razón corta/claves inválidas, CSRF 403, 401 sin sesión); `jobs[]` en `/status` conservando `latestJob`.
  - `tests/level-tests-attempts.test.ts` (9 tests): listado de resultados con joins snapshot/alumno, filtros account/email/language/level/state/passed/rangos, orden por overall, paginación; detalle con answers + result y 404 cross-account; summary con KPIs y desglose por semana y por nivel; export CSV con cabecera según spec, escape de fórmulas (`'=`) y tope 10 000 (413 `export_too_large`).
  - Suite preexistente completa en verde.
- `node --check services/api/admin-ui/app.js`: OK (UI sin build).

## No verificado (requiere humano)

- Flujos manuales del panel en navegador: tarjetas "Exámenes generados" y "Resultados de exámenes", modal de detalle, botón Regenerar con polling de jobs, descarga CSV y acción "Ver exámenes" en la tabla accounts. La lógica server de todos esos endpoints está cubierta por tests; la interacción DOM no tiene harness en este repo.
- Regeneración concurrente real (advisory lock contra CLI/scheduler a la vez): cubierta indirectamente por los tests del módulo de jobs (`runLevelTestGenerationJob`), no reproducida en paralelo real aquí.

## Notas

- Se repararon en el mismo change dos tests preexistentes con la semana ISO congelada (`202642`), que rompían al cambiar de semana natural; ahora derivan de `weekIndex(now)`.
- Revisión final de rama por revisor independiente (subagent fresh): 3 hallazgos Important corregidos en un solo fix pass (casts seguros sobre `result` jsonb sincronizado por clientes; guard de advisory lock en `approveGeneratedWeek` → 409 `level_test_regeneration_in_progress` durante una regeneración; filtros por alumno en la UI). 6 minor diferidos (documentados en el ledger del plan).
