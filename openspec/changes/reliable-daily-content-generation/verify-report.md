# Verificación: fiabilidad de la generación diaria

Fecha: 2026-11-20. Rama: `feat/daily-practice`. Base: `ea10bc3`.

## Comparación offline

La comparación usa la misma práctica sintética con una pregunta de comprensión oral a la que le falta una opción. No se usaron datos de alumnos ni credenciales.

| Caso | Antes | Después |
|---|---|---|
| Una pregunta inválida con clave identificable | Una generación; rechazo antes del juez | Una generación y una reparación; el resto se conserva y el juez recibe el paquete final |
| ID cambiado o clave de reemplazo incompatible | No había reparación | El paquete falla antes del juez; no se solicita una segunda reparación |
| Falta un bloque entero de claves | Rechazo | Rechazo antes de solicitar una reparación inútil |
| Cancelación antes de enviar | Podía abrir un envío o dejar el coste como desconocido | Cero envíos y se libera la reserva si ningún despacho empezó |
| Se pierde el lease durante el envío | Continúa la llamada sin señal | El transporte recibe cancelación; no se vuelve a enviar ni se publica |

Para reproducir la comparación con la imagen compilada en Docker:

```sh
docker run --rm --network none -v "$PWD/shared:/shared:ro" app-server-smoke:local node --test \
  --test-name-pattern='daily question repair|daily content worker traces|heartbeat can no longer' \
  dist/tests/daily-content-pipeline.test.js dist/tests/daily-workers.test.js
```

## Evidencia

- Reproducción inicial: 7 regresiones fallaron de 8; la ambigüedad ya impedía continuar. El primer paso GREEN tuvo 31 pruebas focalizadas sin fallos.
- La revisión independiente señaló cuatro casos: reparación sin reserva activa, cancelación inequívoca antes del envío, falta de claves fuera de la pregunta objetivo y pérdida de hitos del revisor de intentos. Las cuatro regresiones fallaron antes de la corrección y pasaron después.
- Verificación focalizada final: 35 correctas, 0 fallidas, 4 omitidas por requerir PostgreSQL; quedan incluidas en la suite completa.
- En la repetición de `make test-api` pasaron los cuatro casos con PostgreSQL (reparación válida, rechazo del juez, resultado desconocido y reserva liberada) y la prueba de pérdida del lease. Resultado final: **1.026/1.026**, sin fallos ni omitidas. Docker eliminó los contenedores, la red y el volumen efímero.

## Límites

La prueba no estima fiabilidad, coste, latencia ni calidad pedagógica con un proveedor real. No se desplegó el cambio ni se modificaron paquetes existentes.
