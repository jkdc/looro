# Diseño: recuperación local de exámenes semanales Android

## Contexto y objetivo

T07 conecta la pantalla existente a la persistencia de T03. El objetivo autorizado es reanudar y revisar el mismo examen tras cerrar o perder el proceso. El historial completo queda en SQLite; SharedPreferences conserva el resumen legacy y no se migra a un intento ficticio.

## Evidencia y decisión

ExamHistoryDao.start, saveAnswer, complete y load ya persisten snapshot, attemptId, respuesta por questionId, posición, resultado y outbox transaccional. LearningDbWriter expone los tres writes. AppViewModel mantiene un FIFO de writes en Dispatchers.IO y persistAndWait; la UI actual no invoca los DAO y sus respuestas/resultado son estado Compose. El adaptador del catálogo solo convierte el wire payload a examen; no existe conversión a contentJSON ni decoder histórico. El servidor vuelve a calcular contentHash/contentId sobre payload canónico (claves ordenadas, arrays intactos); examId LLM liga generationId y variant.

Se reutilizan esos contratos locales con una representación versionada y un repositorio de ciclo de vida. La remota retiene JSON validado y metadatos; la bundled calcula el hash canónico API, source=bundled y examId=contentId. Rechazar hash/identity inconsistentes. Se descarta una base paralela en preferencias porque duplicaría la fuente de verdad. Se descarta volver a pedir el examen por semana o examId al reabrir porque puede haber expirado o regenerado.

## Flujo

1. Capturar una selección validada y codificar nivel, orden de preguntas, pasajes, opciones, claves, metadatos de identidad/grading/semana.
2. En IO/FIFO persistir snapshot, intento y evento created en una transacción; solo después exponerlo a la pantalla.
3. Guardar respuesta por questionId y posición; indicar guardado tras commit. Ante fallo, mantener respuesta/posición en memoria, mostrar error y permitir reintento; no marcar sync ni entregar.
4. Reanudar por attemptId leyendo snapshot y respuestas vigentes. No recomponer con el catálogo actual.
5. Entregar una vez resultado original con veredictos por pregunta y resumen, completion y outbox atómicos; mostrar resultado tras confirmación durable. Retry idéntico no duplica completion.
6. Historial lista intentos y estado de sync; revisión usa snapshot, respuestas y resultado originales. Legacy permanece solo-resumen.

## Límites, riesgo y verificación

No restaurar una instalación distinta desde el servidor (T12), cambiar ownership/CAS/outbox de T05 ni añadir migración salvo evidencia. Riesgo alto: item 1 (persistencia/inmutabilidad) e item 4 (orden de autosaves/doble entrega). TDD JVM para round-trip, reopen, fallo/reintento, respuestas por ID, rollover ISO, history/review e idempotencia. Suite/lint/APK solo dentro de Docker; reportar bloqueos de Whisper. Swift queda fuera en Linux.
