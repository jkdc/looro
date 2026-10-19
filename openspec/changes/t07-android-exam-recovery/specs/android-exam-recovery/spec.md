# Especificación: recuperación local de exámenes Android

## S1 — Snapshot durable antes de mostrar el examen

Al iniciar un intento, Android captura la selección validada bundled o remota y persiste snapshot inmutable, intento con attemptId estable y evento inicial en una transacción antes de presentar preguntas. Si no se confirma el commit, no muestra que exista un intento durable y conserva la selección para reintentar. El snapshot registra schema/content/grading revisions, origen, idioma/nivel, semana ISO UTC, identidad y contenido exacto en orden. La remota conserva JSON/identity validados; bundled calcula SHA-256 como API (objetos canónicos, arrays intactos; examId=contentId). Nunca persiste hash inconsistente con el payload del outbox.

## S2 — Autosave por identidad de pregunta

Cada cambio guarda attemptId, questionId, respuesta y posición visible mediante el write queue de IO. La pantalla indica guardado solo tras commit. Si falla, conserva respuesta/posición en memoria, muestra error y permite reintentar; no reporta sync ni permite entregar cambios no durables. Las respuestas no se relacionan por índice mutable.

## S3 — Reanudar desde intento existente

Al reabrir, carga por attemptId el snapshot, respuestas vigentes y posición. No solicita ni recompone el catálogo de la semana actual. Descarga tardía, rollover ISO o bundle actualizado afecta intentos nuevos; los históricos mantienen contenido, orden y grading originales.

## S4 — Entrega inmutable e idempotente

La entrega persiste en una transacción resultado original, veredicto por pregunta, completion y evento sync. La UI presenta resultado solo tras éxito durable. Retry idéntico no-op; resultado diferente o mutación posterior se rechaza sin crear otra completion.

## S5 — Historial y revisión exacta

Learn expone intentos completos por fecha/nivel y estado local/sync. Revisar muestra pregunta, pasaje, respuesta propia, solución, veredicto y resultado original desde snapshot/BD, sin catálogo actual ni recalificación silenciosa. Resúmenes anteriores a T03 se muestran como «solo resumen»; no se fabrican respuestas.

## S6 — Recuperación local y fallos visibles

Cerrar/recrear proceso, reabrir BD o importar backup compatible conserva intentos y permite reanudar/revisar por ID tras cambiar semana o bundle. Un fallo de inicio/autosave/entrega nunca se presenta como guardado y permite reintentar sin descartar respuestas. T07 no afirma restauración en otro dispositivo; esa prueba es T12.
