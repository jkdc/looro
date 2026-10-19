# Proposal: level-test-screen-usability

## Why

El examen de comprobación semanal (LevelTestScreen) es funcionalmente correcto
pero el primer uso real en un teléfono físico (Pixel 9, sesión del 18-19 de
octubre) destapó once problemas de usabilidad que bloquean o confunden al
examinando: entradas de texto invisibles, ruido de estado por cada respuesta,
opciones sin affordance visual, ausencia de instrucciones por sección, un
"reproductor" de escucha que permite solaparse y no informa del progreso, el
teclado ocultando el input activo, y un botón de entrega deshabilitado hasta
responder TODO sin marcar qué falta.

## What Changes

- Entradas de texto (gap/written) con contenedor visible: borde, placeholder
  y alto mínimo; el hueco `__` del enunciado deja de ser el único indicio.
- Opciones de elección con borde siempre visible; la seleccionada mantiene su
  relleno; se añade icono de check. Estado "sin responder" marcado en rojo al
  intentar entregar.
- Entrega en dos pasos: el botón se habilita con ≥1 respuesta; si quedan sin
  responder, se marcan en rojo (borde + chip con el contador) y el botón pide
  confirmación explícita antes de enviar. Las respuestas en blanco cuentan
  como incorrectas (regla ya documentada).
- Los rótulos de autoguardado ("Guardado en este dispositivo") desaparecen del
  examen activo; solo los fallos de guardado son visibles (con reintento).
- Cada cabecera de sección lleva un botón de información que despliega una
  explicación corta de qué se espera en esa sección, colapsable.
- Reproductor de escucha por pasaje: botón único play/stop (imposible
  solapar), barra de progreso con estimación de duración y de transcurso,
  rebobinado/avance por fracción (reinicia el habla desde el punto elegido);
  el botón reaparece en estado de reposo al terminar.
- Teclado: `imePadding` en la pantalla para que el input enfocado nunca quede
  bajo el teclado; scroll automático al primer sin responder al intentar
  entregar.
- Rediseño visual de la tarjeta de pregunta (número, chip de sección, borde)
  para diferenciar claramente cada ejercicio, siguiendo el mockup aprobado.

## Impact

- Apps: Android (LevelTestScreen, AppViewModel, strings EN/ES). iOS queda
  como tarea de paridad posterior.
- Sin cambios de contrato de API ni de BD; el grading y la persistencia no
  cambian (la entrega sigue siendo idempotente).
- Riesgo bajo: cambios de presentación y de estado de UI; el flujo de
  persistencia (autosave, complete) se mantiene.
