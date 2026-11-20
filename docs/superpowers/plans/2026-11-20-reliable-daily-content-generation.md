# Plan de implementación: fiabilidad de la generación diaria

> Para ejecución: usar Superpowers `executing-plans` en la rama activa. Cada cambio sigue RED → GREEN; no ejecutar llamadas a proveedores reales.

**Objetivo:** Recuperar el paquete diario ante una pregunta defectuosa y distinguir por qué un trabajo no llega a publicarse.

**Arquitectura:** Extender el worker y el validador diario sin cambiar el contrato público. La reparación tiene su propia operación y el paquete final pasa los mismos controles de integridad y publicación.

**Stack:** TypeScript, OpenTelemetry, Node y PostgreSQL efímero en Docker.

**Spec:** [Diseño](../../../openspec/changes/reliable-daily-content-generation/design.md) · [Requisitos](../../../openspec/changes/reliable-daily-content-generation/specs/daily-content-reliability/spec.md).

## Restricciones

No tocar paquetes publicados, migraciones, configuración real, exámenes semanales ni clientes. No cambiar presupuesto, eliminar evidencia del juez ni reintentar resultados desconocidos. Mantener la rama activa y preservar cambios ajenos.

## Casos de revisión

Cancelación durante el checkpoint; pérdida del lease durante una llamada; respuestas arbitrarias y errores con texto no confiable; reparación que cambia ID/tipo, toca contenido ajeno o no supera la segunda validación; evidencia del juez ligada a un hash viejo; reparación con operación o presupuesto indisponibles.

## Task 1: Correlacionar y sanear diagnósticos

**Archivos:** `services/api/src/daily-generation.ts`, `daily-workers.ts`, `services/api/docs/observability.md`; `daily-content-pipeline.test.ts`, `daily-workers.test.ts`.

- [x] RED: comprobar que un marcador dentro de la respuesta y otro dentro de un error no aparecen en logs ni spans. Usar un reloj inyectado para distinguir espera previa, duración del proveedor y publicación manual/automática.
- [x] GREEN: emitir un span de contenido por ID del trabajo; diferenciar cola (creación a reclamación) y reclamación al primer despacho, e instrumentar cada fase con latencia, estado y datos reportados. Sustituir los errores y muestras crudas por códigos de una lista cerrada. Leer el motivo de fin normalizado de la respuesta sin propagar texto libre.
- [x] Docker: ejecutar las pruebas focalizadas y verificar que no cambia ningún estado público.

## Task 2: Impedir envíos tras cancelación

**Archivos:** `services/api/src/daily-llm.ts`, `daily-workers.ts`, `daily-content-worker-scheduler.ts`; `daily-llm.test.ts`, `daily-workers.test.ts`, `daily-content-worker-scheduler.test.ts`.

- [x] RED: abortar durante el checkpoint y comprobar cero envíos. Simular un heartbeat perdido con una llamada en curso; comprobar señal, ausencia de un segundo despacho y ausencia de publicación.
- [x] GREEN: comprobar la señal en ambos lados del checkpoint y propagarla al transporte. El heartbeat cancela el trabajo al perder el lease; las transacciones siguen condicionadas al propietario vigente. Los casos ya despachados con desenlace no conocido conservan `unknown`.
- [x] Docker: ejecutar cancelación, recuperación y uso parcial; comprobar que el flujo sin cancelación no cambia.

## Task 3: Reparar una pregunta y su clave

**Archivos:** `services/api/src/daily-content.ts`, `daily-generation.ts`, `daily-prompts.ts`, `daily-workers.ts`; `daily-content-pipeline.test.ts`, `daily-workers.test.ts`, `daily-prompts.test.ts`.

- [x] RED: una pregunta con tres opciones provoca un único intento de reparación; conservar todas las demás, misma posición e ID, y enviar al juez solo el resultado final. Probar sustitución de ID/tipo, clave errónea, JSON raíz inválido, rechazo del juez y un resultado desconocido de reparación.
- [x] GREEN: representar el fallo local con un descriptor interno de sección/posición/ID/tipo y código seguro, sin inferirlo de texto libre. Permitir una devolución exacta de pregunta/clave con datos mínimos. Volver a validar y calcular hashes antes del juez. Despachar con identidad `job:generate:repair:v1` bajo el mismo lease y reserva; registrar su uso en una operación independiente. Si la estructura o la reserva no permiten reparar, no despachar.
- [x] Docker: verificar con PostgreSQL efímero que solo el par cambia, las dos operaciones de generación quedan separadas, el presupuesto permanece aplicable y el juez conserva la última palabra.

## Task 4: Verificación de entrega

- [x] Comparar offline el mismo paquete dañado antes y después: llamadas iniciales, reparaciones, fases alcanzadas y estado de publicación.
- [x] Revisión independiente de la preservación de datos, hashes, lease y presupuesto. Corregir solo hallazgos demostrados con regresión.
- [x] Ejecutar `make test-api` en el stack efímero y `git diff --check`; registrar resultados y piloto real pendiente en `verify-report.md`.
