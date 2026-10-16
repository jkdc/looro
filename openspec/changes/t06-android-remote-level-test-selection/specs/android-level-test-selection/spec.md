# Especificación: selección de level-test Android

## S1 — Consumo híbrido acotado

El cliente Android solicitará `/v1/level-tests/hybrid` con idioma, nivel y una variante explícita y estable. La operación completa tendrá un deadline máximo de cinco segundos y el tamaño de respuesta estará limitado antes de parsear JSON. No enviará credenciales, cookies, datos del alumno ni respuestas. No bloqueará el hilo principal ni aplicará retries de UI.

## S2 — Validación antes del uso

Solo se aceptará un snapshot de schema soportado cuyo idioma, nivel, semana ISO UTC y variante coincidan con la solicitud. Se validarán campos requeridos, IDs únicos, secciones/kinds soportados, referencias de lectura/escucha, opciones y respuestas, conteos y límites de bytes. Un snapshot inválido no se guardará ni mostrará; el error público será tipado y no incluirá contenido del examen.

## S3 — Cache revalidable y desechable

El cliente almacenará únicamente snapshots validados, separados de los snapshots históricos de intentos. En 304 reutilizará solo el body válido asociado a la misma identidad y variante. Con cache ausente o inválida, no inventará contenido; hará fallback validado a bundled dentro del deadline. La caducidad/limpieza del catálogo no eliminará snapshots referenciados por intentos ni impedirá su revisión/restauración.

## S4 — Fallback seguro

Ante 404, error de red, timeout o JSON/schema inválido, una nueva selección podrá usar el examen bundled validado. `CancellationException` no se convertirá en fallback/error ordinario y cancelará la petición de red.

## S5 — Selección congelada

Al abrir un intento se capturará exactamente una representación validada y su orden de preguntas. Una respuesta remota/caché que llegue después no sustituirá el examen ni alterará preguntas visibles; solo podrá quedar disponible para un intento posterior.

## S6 — Verificación

Los tests JVM deben cubrir los casos nombrados en T06, incluyendo ETag 304, cambio de semana ISO/año y una respuesta tardía posterior a contestar. Ejecutar suite, lint y build Android dentro de Docker; no ejecutar Swift en Linux.
