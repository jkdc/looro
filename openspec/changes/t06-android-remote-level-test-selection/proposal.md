# Propuesta: selección remota segura de exámenes Android

## Problema

Android solo utiliza el pool bundled y recompone el examen semanal en memoria. La API ya publica una representación híbrida con ETag, pero consumirla sin validar su estructura, acotar la respuesta y congelar la selección podría introducir preguntas inconsistentes o cambiar el examen mientras el alumno responde.

## Resultado

Añadir un cliente público de catálogo Android con deadline total de cinco segundos, validación estricta y caché descargable separada del historial durable. Un intento selecciona una única representación validada (remota o bundled) y la conserva aunque termine una descarga posterior.

## Fuera de alcance

Autosave, reanudación, history/review UI, restore, cambios de API o de esquema SQL, iOS y activación de generación. Esos puntos permanecen en T07 y fases posteriores.
