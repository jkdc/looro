# Plan de implementación: generación fiable de exámenes

> Para ejecución: usar Superpowers `executing-plans` en la rama activa. Cada cambio de comportamiento empieza con una prueba RED y termina con su prueba GREEN.

**Objetivo:** Recuperar exámenes válidos ante errores aislados y hacer visible la fase exacta de un fallo.

**Arquitectura:** Mantener el pipeline y el blueprint congelado. Separar los descartes de candidatos de los requisitos del banco, reparar solo los déficits y correlacionar fases con el ID de ejecución existente.

**Stack:** TypeScript, Node y OpenTelemetry; pruebas API en Docker con PostgreSQL efímero.

**Especificación:** [OpenSpec](../../../openspec/changes/reliable-exam-generation/specs/generated-exam-reliability/spec.md) · [Diseño](../../../openspec/changes/reliable-exam-generation/design.md).

## Restricciones

Trabajar en la rama activa. No modificar contratos públicos, exámenes existentes ni política del juez/publicación. No instalar dependencias, llamar a proveedores reales ni usar la BD normal para pruebas. No reintentar resultados de proveedor desconocidos.

## Casos que deben quedar cubiertos

- Un pasaje inválido no reserva IDs ni huellas de preguntas para un pasaje posterior.
- Un reemplazo que duplica una unidad preservada no satisface el déficit.
- Cuotas de tipo y competencias que no se satisfacen solo con alcanzar el número.
- Cancelación con resultado de proveedor desconocido durante reparación, sin reenvío.
- Final de respuesta inesperado y uso parcial: evento saneado, sin convertir lo desconocido en cero ni registrar contenido.

## Task 1: Preservar candidatos y reparar déficits

**Archivos:** `services/api/src/level-tests-llm.ts`, `level-tests-validate.ts`, `level-test-assessment.ts` y pruebas `level-tests-llm.test.ts` / `level-tests-policy.test.ts`.

- [x] RED: extender la prueba con transporte inyectado para comprobar 7 válidos + 1 malformado (sin reparación), 5 válidos + 1 reemplazo (una llamada, los cinco originales llegan al juez), reemplazo duplicado (sin persistencia), huecos de tipo y competencia y deduplicación no contaminada. Comprobar señal durante reparación.
- [x] GREEN: hacer atómica la admisión del pasaje para generación reforzada. Separar descartes de errores agregados e introducir un déficit interno por sección con `missingCount`, `missingKinds` y `missingSkills`. Validar candidatos nuevos contra los preservados y repetir los controles del banco sobre la unión antes de juzgar.
- [x] Refactorizar solo lo necesario y ejecutar las regresiones en Docker. Mantener intactas las pruebas de composición, publicación y juez.

## Task 2: Versionar las instrucciones de salida

**Archivos:** `services/api/src/level-tests-llm.ts`, pruebas de prompts y `services/api/docs/english-generated-exams.md`.

- [x] RED: comprobar forma por tipo, preguntas y opciones del blueprint, y que un reintento legado con feedback saneado ya no genera los mismos mensajes que el intento inicial. Fijar el comportamiento legado sin feedback salvo el cambio de la versión registrada.
- [x] GREEN: utilizar forma de ejemplo por tipo y feedback de déficit en el prompt reforzado. Añadir feedback al prompt legado solo en reparación. Subir versiones de generación de ambos caminos; no alterar la versión del juez.
- [x] Repetir al menos los dos replays A1 del diagnóstico con respuestas controladas. Comparar intentos, llamadas de reparación y candidatos conservados; no estimar tokens que un proveedor simulado no haya reportado.

## Task 3: Diagnosticar por fase

**Archivos:** `services/api/src/llm-config.ts`, `level-tests-generation-jobs.ts`, `level-tests-llm.ts`, pruebas de transporte y ejecución, `services/api/docs/observability.md`.

- [x] RED: simular final por longitud, final ausente y valores arbitrarios; comprobar conteos por fase y correlación de trabajo, separación de borrador/aprobado y ausencia de texto crudo.
- [x] GREEN: extender de forma aditiva la respuesta interna del transporte con motivo de final normalizado, sin exponer el payload. Correlacionar un span por trabajo y eventos de fase mediante el ID existente; mantener los checkpoints/estados y filtrar todos los valores de proveedor. La telemetría no debe cambiar el resultado funcional ni las reglas de reenvío.
- [x] Ejecutar pruebas focalizadas de transporte y ejecución, incluida la senda de transporte desconocido.

## Task 4: Verificar y registrar

- [x] Ejecutar `make test-api` en el stack efímero, leer totales y confirmar la limpieza. Ejecutar `git diff --check`.
- [x] Registrar RED/GREEN, replay y suite en `verify-report.md`; dejar el piloto con proveedor real como pendiente, no como una prueba ejecutada.
