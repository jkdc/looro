# Verificación de garantías de publicación — 2026-10-25

## Resultado

Implementadas y verificadas las garantías técnicas de los puntos 1–4 del plan solicitado. La matriz pedagógica aprobada, el corpus de ejemplos revisados y el piloto docente/con alumnos siguen pendientes de evidencia humana. No se presentan como completados ni como requisitos satisfechos por pruebas de software.

Rama: `feat/exam-generation-readiness`. Base de esta intervención: `495689b`.

Commit técnico: `8ee3450` — `fix(level-tests): enforce English publication guarantees`.

## Comportamiento comprobado

- Inglés con verificación desactivada genera únicamente borradores privados, incluso con publicación automática configurada. La aprobación manual tampoco permite saltarse el juez.
- Preguntas, respuestas/opciones individuales, explicaciones y pasajes reciben evaluación de idioma independiente. Las dudas se conservan para el juez; una duda no permite publicar. El pool español reproducido queda bloqueado.
- Nombres propios, préstamos y alternativas como Los Angeles / Las Vegas / La Paz / El Paso permanecen revisables, sin acumular falsos indicios al concatenarlas.
- El juez inglés usa contrato versión 4, evidencia acotada y `languageStatus` explícito. Las identidades de otros idiomas no cambian.
- El blueprint inglés completo se clona antes de cualquier espera y se guarda como contrato formato 2 con hash canónico. Cambios posteriores del modelo y reordenación de claves JSONB no reinterpretan el borrador.
- Los borradores ingleses legacy sin contrato completo requieren regeneración. Los snapshots publicados y la calificación histórica no se reescriben.
- Los runs conservan consumo conocido por dirección y estado completo/parcial/desconocido, incluidos ceros explícitos, fallos deterministas, respuestas vacías y llamadas de resultado incierto. No se repiten automáticamente llamadas inciertas.
- Si falla transitoriamente un checkpoint tras la llamada, el cierre recuperado conserva las cifras recibidas sin convertir la ejecución incierta en exitosa.

## Pruebas observadas

| Comprobación | Resultado final |
| --- | --- |
| Suite API completa: `cd services/api && npm test` | **733/733**, 0 fallidas, **0 omitidas**; PostgreSQL 17 efímero |
| Focalizadas: blueprint, snapshots, política y transporte | **123/123**, 0 omitidas |
| Correcciones de revisión: dos fallos de checkpoint y opciones de ciudades | **3/3**, con PostgreSQL real aislado y pipeline público |
| `npm run check` | Correcto |
| `make check` | Correcto; **138 pruebas Python** y contratos multiplataforma/schema |
| `python3 scripts/export_leveltests.py --check` | Correcto; sin drift |
| `git diff --check` | Correcto |

Las migraciones nuevas `052` y `053` se aplicaron en PostgreSQL aislado. Se confirmó la eliminación del volumen `looro-smoke_smoke_data` y la red `looro-smoke_default`. No se usaron bases reales ni proveedores externos: las pruebas de transporte usan respuestas controladas o un servidor HTTP local.

Se observaron fallos RED antes de implementar las reglas de publicación, idioma, contrato congelado y consumo. La revisión independiente encontró dos casos adicionales concretos; ambos se reprodujeron en RED y se corrigieron en una única pasada, seguida de pruebas dirigidas y la suite completa final. El resultado anterior de 730/730 no se utiliza como prueba de esos últimos cambios.

## Revisión independiente

Sin hallazgos críticos. Dos hallazgos importantes corregidos:

1. Pérdida de cifras conocidas si fallaba el checkpoint posterior a la llamada y luego se recuperaba el cierre. Ahora se persiste el consumo del resultado en el cierre final; las regresiones cubren tanto excepción como actualización de cero filas, sin repetir al proveedor.
2. Falso rechazo de idioma al juntar opciones que eran nombres propios. Ahora cada respuesta/opción se evalúa por separado.

La revisión no certifica aprobación docente, calibración CEFR ni resultados de piloto. Esas evidencias no existen todavía.

## Incidencias del entorno y límites

- `make check` conserva los avisos preexistentes `ResourceWarning` de conexiones SQLite sin cerrar; terminó correctamente.
- Una compilación auxiliar en el host no pudo sobrescribir archivos existentes de `services/api/dist/` por permisos. No se alteraron propietarios/permisos. La compilación Docker del código final y el typecheck sin emisión sí terminaron correctamente.
- No se ejecutó ni desplegó nada en producción. Antes de desplegar esta API deben aplicarse las migraciones aditivas nuevas. No hubo push ni PR ni merge.

## Pendiente para completar el plan íntegro

Hace falta un responsable pedagógico que apruebe y versione una matriz por nivel/sección, con IDs del catálogo existente, reglas de cobertura acordadas y ejemplos completos revisados con soluciones y evidencia. Solo entonces se implementará la conservación/validación de etiquetas, composición por cobertura y uso de ejemplos curados; no se han introducido cuotas ni aprobaciones arbitrarias.

Después se requiere un piloto docente y otro con alumnos autorizados para medir dificultad, duración, ambigüedades y distractores. Los detalles de entrada y los límites están en `services/api/docs/english-generated-exams.md`.

Risk: items 1 and 4 — additive persisted schema and asynchronous usage checkpoints; independent review and isolated PostgreSQL verification completed.

## Actualización 2026-10-25 — decisión del usuario

El usuario confirmó que un LLM puede diseñar y revisar la matriz y los ejemplos. La referencia anterior a evidencia humana obligatoria queda sustituida por `english-exam-coverage`, que registra revisión automatizada independiente y su hash. Esto no equivale a validación empírica con alumnos ni a certificación CEFR.
