# Requisitos

## S1 — Exclusiones positivas sin transacción
Guest y suscripción trialing/active SHALL devolver0 sin adquisición transaccional de concesión ni cambios de grants/entradas/saldo. Configuración y evidencia se leen frescas, sin caché.

## S2 — Mantener el atajo reclamado
Una concesión FREE de misma cuenta/periodo SHALL seguir devolviendo0 sin transacción; no ejecutar subplanes de guest/suscripción cuando ese primer CASE ya resuelve el resultado.

## S3 — Mantener escrituras seguras
Un candidato no excluido SHALL usar el fallback íntegro con guards, presupuesto e INSERT ON CONFLICT. Paid-marker/otra cuenta/periodo distinto/past_due/canceled SHALL NOT suprimir crédito elegible. Concurrencia inicial, reservas/rollover, configuración/error y retirada de marcador SHALL conservarse.

## S4 — Evidencia por perfil
SHALL ejecutar RED/GREEN y suite PG, revisión independiente, y comparar el recorrido libre y los controles guest/suscrito. No afirmar mejora universal ni capacidad de producción sin medición representativa.
