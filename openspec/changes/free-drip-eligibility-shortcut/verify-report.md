# Verificación: exclusiones positivas de drip

Fecha: 2026-10-30. Rama `feat/api-performance-hardening`; base `aa51066522c5f4ef33dbe8897159d75151861b37`. Solo fixtures locales; sin despliegue, proveedores reales o nueva garantía de capacidad.

## Resultado funcional observado

- RED sobre la implementación anterior: 15 pruebas, 12 PASS y 3 FAIL por adquirir una transacción para guest/trialing/active. Fallo esperado: 1 adquisición frente a 0.
- GREEN final: `node --import tsx --test --test-concurrency=1 tests/free-drip-fast-path.test.ts tests/subscriptions.test.ts` contra PostgreSQL aislado: **29 PASS, 0 FAIL, 0 omitidas**. Incluye los estados excluidos, past_due elegible y pérdida de elegibilidad después del precheck sin crédito nuevo. Esta última es caracterización de seguridad, no un nuevo RED.
- `npx tsc --noEmit`: PASS.
- EXPLAIN ANALYZE de la consulta exacta con un grant real del fixture: `subscription_grants_period_idx` ejecutado una vez; subplanes de accounts/subscriptions con `Actual Loops = 0`. No se añadió índice.
- Revisión independiente de S1–S3: sin bloqueantes; scratch nuevo, 16 pruebas PASS, contraste baseline con 3 RED esperados y probes adicionales PASS en ambos. SQL y finanzas cotejados de forma independiente. S4 completado con la suite observada abajo.
- Suite completa `make test-api`: **802 PASS, 0 FAIL, 0 omitidas**, 319,34 s; limpieza efímera observada. La imagen incluye los 16 tests específicos iniciales, no las dos caracterizaciones incorporadas después.
- Focused final con los probes retenidos: **31 PASS, 0 FAIL, 0 omitidas** (18 específicos + 13 históricos); type-check final PASS. Primera adaptación del probe de cuenta ausente falló en el helper `minuteBalance` antes de invocar el método bajo prueba; se corrigió el fixture para leer tablas directamente y se reejecutó toda la selección. No se cambió producción.
- `git diff --check` y checker de comparación: PASS. Sin checks requeridos fallidos o pendientes; la ráfaga de altas nuevas y la capacidad productiva siguen fuera de la prueba.

## Comparación reproducible

Imágenes congeladas antes/después de modificar el código de ejecución:

- before `palique-elig-before:local`: `sha256:479dc543dc8209256f8227ccc703ba6c99440d399b844eee9887b52e144c45d3`.
- after `palique-elig-after:local`: `sha256:01f46d650233895bb880c6990b71665f0fe924764bd6942ec7d6fe06ae7158e0`.

Dos stacks efímeros, una sola carga emisora activa; orden B1/A1/A2/B2/B3/A3. Mismos 200 usuarios sintéticos: 180 libres, 10 guest, 10 activos; mezcla libre 70 % saldo autenticado / 30 % contenido público. Warmup de 500 lecturas antes de medir. Cada repetición: 400/s y 800/s durante 15 s cada una; guest y suscrito a 100/s durante 10 s cada uno. OTel activo, sin profiler CPU, cuotas CPU/memoria, TLS o Caddy. No se ejecutaron suites durante la medición.

### SQL: mejora de trabajo, no promesa de latencia

Recorrido repetido de `GET /v1/minutes`, contando las sentencias de aplicación y control transaccional (no los trabajos periódicos):

| Perfil | Antes | Después | Transacciones de drip después |
|---|---:|---:|---:|
| Libre con marcador | 7 | 7 | 0 |
| Guest | 10 | 7 | 0 |
| Suscripción activa | 11 | 7 | 0 |

`pg_stat_statements` confirma BEGIN por cada guest/suscrito antes y ninguno después; tampoco INSERT de grant en fases repetidas. Los totales brutos contienen unas pocas consultas de jobs periódicos, por lo que no son exactamente N multiplicado por la tabla. El checker local verifica todas las fases y la contabilidad; el desglose raw permite contrastar consultas y llamadas.

### Latencias p95, milisegundos; tres repeticiones sin promediarlas

| Perfil | Antes B1 / B2 / B3 | Después A1 / A2 / A3 |
|---|---|---|
| Libre mezcla 400/s | 6,74 / 6,30 / 5,67 | 6,92 / 7,46 / 6,01 |
| Libre mezcla 800/s | 6,78 / 7,66 / 7,04 | 9,79 / 13,90 / 7,19 |
| Guest 100/s | 8,47 / 14,88 / 7,87 | 11,38 / 11,46 / 7,87 |
| Activo 100/s | 10,63 / 13,90 / 9,92 | 12,37 / 10,63 / 8,50 |

**No hay evidencia de mejora universal de latencia o RPS.** En particular, la mezcla libre a 800/s tiene p95 mayor después en las tres repeticiones; el CASE más complejo puede tener coste de planificación aun sin ejecutar sus subplanes posteriores. La reducción demostrada es de roundtrips y transacciones de guest/suscritos. Mantener este tradeoff visible y reevaluarlo si el tráfico real está dominado por libres ya reclamados.

A 800/s ofrecidos, el throughput útil fue 726,70 / 728,05 / 732,10 antes y 723,05 / 709,00 / 729,87 después; hubo 1.016–1.339 solicitudes por fase no lanzadas por el driver. A 400/s también hubo 26–44 no lanzadas. Todas las solicitudes lanzadas terminaron en HTTP 200; cero fallos de transporte. No confundir un descarte del generador con un error API, ni los RPS ofrecidos con capacidad soportada. Una fase suscrita antes y otra después descartaron 1 solicitud cada una.

### Integridad y límites

Ambos stacks: 200 cuentas, 180 grants, saldo y diario de minutos iguales a 54.000.000 ms; saldo monetario y ledger iguales a 199.999.999.991 nanoUSD; 1 uso y 1 intento settled, 0 pendientes/unknown. Limpieza de ambos stacks/volúmenes observada.

El candidato libre aún no reclamado conserva el fallback completo y paga la lectura extra con sus subplanes: **no se ha medido una ráfaga de altas nuevas**. Una transición guest→miembro o active→canceled tras una negativa ya observada puede esperar a la siguiente lectura oportunista; ninguna negativa autoriza crédito. No se cambia el aislamiento ni se soluciona una carrera previa de elegibilidad posterior a los guards transaccionales.

## Revisión independiente

Además de revisar el diff frente a `aa51066`, se verificaron registro guest→miembro, active→past_due/canceled en la siguiente llamada, huella financiera intacta en negativas y error `23503` para cuenta inexistente. Sin bloqueantes ni cambios de runtime posteriores a la revisión. Se incorporaron dos pruebas de caracterización con estos probes después de construir la imagen de la suite; se ejecutan por separado y no se suman artificialmente al contador de la suite completa. Un primer intento del verificador sin dependencias dev no pudo cargar `tsx`; se repitió correctamente con dependencias existentes montadas de solo lectura. No se verificó producción ni altas nuevas bajo carga.

## Evidencia local

`evidence/` está ignorado: comparación raw, resumen validado, harness/override/runner, checker, EXPLAIN y RED/GREEN. Puede contener metadatos operativos sintéticos y no se versiona. Reportes históricos mantienen sus mediciones anteriores, sin reemplazarlas por esta prueba.

Risk: items 1, 3, 4 — concesiones financieras, recorrido compartido y concurrencia; fallback de escritura intacto. Revertir este commit restaura el precheck anterior sin migrar datos.
