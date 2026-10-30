# Requisitos: drip gratuito en lecturas

## S1 — Evitar trabajo sin elegibilidad configurada
Si drip está desactivado o la cantidad es0, runDripForAccount SHALL devolver0 tras leer configuración fresca, sin transacción explícita de concesión y sin cambiar grants, entradas o saldo.

## S2 — Concesión gratuita ya realizada
Una fila FREE de la misma cuenta y periodo UTC SHALL permitir devolver0 sin transacción de concesión. Una fila pagada, otra cuenta o periodo distinto SHALL NOT suprimir el recorrido anterior.

## S3 — Conservar garantías existentes
En ausencia del marcador positivo SHALL ejecutarse la transacción anterior con todos los guards, presupuesto e INSERT ON CONFLICT. Firma, errores de configuración, saldo/ledger y contratos HTTP SHALL conservarse. Dos primeros intentos concurrentes SHALL producir una sola concesión. No introducir caché ni asumir que los marcadores son inmutables.

## S4 — Evidencia
SHALL ejecutar RED/GREEN, PostgreSQL real, suite completa y revisión independiente. Comparación repetida antes/después SHALL separar consulta evitada de límites del generador y no prometer capacidad de producción.
