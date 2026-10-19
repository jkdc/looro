# Propuesta: ciclo local recuperable de exámenes Android

## Problema

La pantalla semanal conserva respuestas y resultado solo en estado Compose y LevelTestHistoryStore guarda únicamente el último resumen. T03 ya introdujo snapshots inmutables, intentos, respuestas y outbox transaccionales en SQLite, pero la pantalla aún no los utiliza; una muerte del proceso pierde el intento visible y no existe historial completo/revisión.

## Resultado

Conectar el flujo Android con el almacenamiento durable existente: crear el intento antes de mostrar preguntas, guardar respuestas y posición por ID, reanudar por attemptId, entregar una sola vez y revisar snapshot/respuestas/resultado originales. Hacer explícitos fallos de almacenamiento sin descartar la respuesta que permanece en pantalla.

## Fuera de alcance

Cambios de esquema/backend, sincronización/restauración de otra instalación (T12), iOS, selección/caché de catálogo (T06), migración de resúmenes legacy a intentos completos y llamadas LLM reales.
