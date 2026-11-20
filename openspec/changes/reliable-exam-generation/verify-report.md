# Verificación: generación fiable de exámenes

Fecha: 2026-11-20. Rama: `feat/daily-practice`. Base: `ea10bc3`.

## Comparación offline de recuperación

Se reutilizaron respuestas sintéticas con el blueprint inglés A1 congelado. No se usaron credenciales externas ni datos de alumnos.

| Caso | Antes | Después |
|---|---|---|
| 7 pasajes válidos + 1 inválido con una pregunta repetida | Reintenta toda la sección; falla | Sin reparación; los siete llegan al juez |
| 5 pasajes válidos; el segundo resultado aporta uno nuevo | Sustituye el lote y falla por tamaño | Pide un reemplazo; los cinco originales y el nuevo llegan al juez |
| El reemplazo duplica un pasaje aceptado | Falla | Falla sin segunda reparación ni publicación |
| Número suficiente, pero faltan tipo y competencia | Regenera todo | Pide un reemplazo y registra tipo, competencia y déficit de tamaño por separado |

La comparación registra resultados y llamadas, no atribuye un coste real al transporte simulado. Para reproducir el resultado actual, tras el build Docker del stack de pruebas, ejecutar:

```sh
docker run --rm --network none app-server-smoke:local node --test \
  --test-name-pattern='preserves valid passages|requests missing kinds|cancellation during the repair' \
  dist/tests/level-tests-policy.test.js
```

## Evidencia

- T1: cinco pruebas RED por pérdida de candidatos o reparación de sección completa; política y cobertura GREEN, 88/88.
- T2: cinco pruebas RED de forma, feedback y versiones; GREEN, 9/9 incluidas las regresiones de recuperación.
- T3: tres pruebas RED de motivo de fin/uso, fases y correlación; pruebas focalizadas finales en Docker: 113 correctas, 0 fallidas; 17 dependen de PostgreSQL y quedaron para la suite completa.
- Revisión independiente: comprobó admisión atómica, deduplicación entre secciones y continuidad del juez/publicación. Cuatro regresiones reprodujeron la cancelación entre checkpoint y envío, la falta de desglose del déficit, la instrucción global de elección aplicada a escritura y el recuento de ítems marcados como rechazados. Las cuatro pasaron tras corregirlas.
- Primera suite completa: 1.010 correctas y una fallida, sin omitidas. La prueba de versiones del blueprint aún esperaba inglés 4 y legado 2; se actualizó para verificar inglés 5 y legado 3, con las versiones del juez intactas. Segunda ejecución final: **1.011/1.011**, sin fallos ni omitidas; Docker eliminó el stack y su volumen efímero.

## Límite

El piloto con un proveedor real queda pendiente de autorización. La comparación técnica no demuestra mejora pedagógica ni de latencia real. No se desplegó el cambio ni se modificaron exámenes existentes.
