# Verify report: retirar una semana aprobada

## Qué se verificó

- `retireGeneratedWeek` solo acepta `approved`; guarda `retired_at`,
  `retired_by` y `retired_reason` en la propia semana y registra una única
  entrada `level_test.retire` con el mismo motivo y los datos de la aprobación
  invalidada (`approved_by`, `origin`).
- El detalle de la semana devuelve `retiredAt`, `retiredBy` y `retiredReason`,
  así que la página de revisión explica el motivo sin unir con auditoría.
- Reintentar la retirada devuelve `alreadyRetired: true` y no duplica auditoría.
- Un `draft` responde 409 `level_test_not_retirable`, un id desconocido 404
  `level_test_not_found` y un motivo corto 400 `admin_reason_required`.
- La ruta exige sesión de admin (401 sin cookie) y origen/CSRF válidos (403 con
  origen ajeno).
- El endpoint público `/v1/level-tests/generated` deja de servir la semana
  retirada (404).
- Una semana retirada no se regenera sola: el scheduler y el lanzador de
  faltantes la omiten; el listado ofrece «Regenerar» para la semana actual.

## Evidencia

- Rojo observado antes de implementar: las tres pruebas nuevas fallaron con
  `404 !== 200`, `404 !== 409` y `404 !== 403` (la ruta de retirada no existía).
- Verde: las tres pruebas de retirada pasan; la suite API completa queda en
  **569/569** (0 fallos, 0 saltados) contra PostgreSQL 17 efímero y
  `npx tsc --noEmit` limpio.
- E2E: **44/44** desde pila limpia con rebuild, incluido «retiring an approved
  week stores the motive and stops serving it» (retirada de B1 desde la tabla,
  404 público y motivo visible en la revisión).

## Límite conocido

La retirada no borra contenido ni reescribe los veredictos del juez: el
contenido sigue en la fila `retired` para auditoría, y el reemplazo exige una
regeneración explícita del operador.
