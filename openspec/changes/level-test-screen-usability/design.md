# Design: level-test-screen-usability

## Estados nuevos en LevelTestScreen

- `unansweredMarked: Boolean` — true tras el primer toque de entrega con
  huecos; dibuja borde rojo y chip "sin responder" en cada pregunta vacía y
  cambia el botón a confirmación. Un cambio en `attempts` que rellene la
  última vacía no lo limpia (el recuento se recalcula al vuelo); entregar
  o editar rellena el estado.
- `confirmingSubmit: Boolean` — el botón muestra "Entregar (N sin
  responder)" en tono de error; segundo toque envía. Se limpia al editar
  cualquier respuesta.
- Submit gating: `enabled = !savingResult && attempts.any { it.isNotBlank() }`.
  Al tocar con N > 0 sin responder → unansweredMarked + scroll al primer
  sin responder; con 0 sin responder → envío directo.

## Jugador de escucha (TTS)

El TTS no soporta seek nativo; el progreso se estima (caracteres/segundo
según la velocidad efectiva) y el seek reinicia el habla desde la palabra
más cercana a la fracción elegida:

- `AppViewModel.playListeningFrom(text, startFraction)` — corta el texto en
  el límite de palabra más próximo y habla el resto; `listeningPlaying`
  refleja el estado (ya existía para la escucha de estudio).
- La pantalla muestra Slider (fracción) + tiempos estimados transcurrido/
  total; el botón único alterna play/stop usando `stopListeningText` /
  `playListeningFrom`. Al terminar (listeningPlaying=false por el finally
  del job) el botón vuelve a reposo — imposible solapar reproducciones.

## Afordance de preguntas

- Elección: Surface con `border(1.dp, Secondary.copy(alpha=.35))` + Cream de
  fondo; seleccionada: fondo Secondary, texto Cream, icono check.
- Escrita: Surface con borde + placeholder de strings + minLines 2; el
  contenido nunca desaparece bajo el teclado (imePadding + ajuste).
- Sin responder (tras intentar entregar): border 1.5dp color error + chip
  "Sin responder" rojo.
- Rótulos de autoguardado eliminados del examen activo; fallo de guardado
  mantiene su mensaje inline con reintento.

## Instrucciones por sección

Strings por sección (recognition/reading/listening/writing) en EN y ES.
Cabecera: título + IconButton de información → AnimatedVisibility del texto
explicativo (mismo estilo que el pasaje). Estado abierto/cerrado por sección
en un remember map, arrancando cerrado.

## Teclado

La raíz de la pantalla añade `Modifier.imePadding()`; el manifiesto ya usa
adjustResize. El LazyColumn mantiene su contentPadding inferior.

## Strings

Nuevas claves EN+ES: leveltest_info_recognition/reading/listening/writing,
leveltest_info_toggle (content-description), leveltest_answer_placeholder,
leveltest_unanswered_chip (+plural), leveltest_confirm_submit,
leveltest_listen_stop, leveltest_listen_elapsed. Se conservan las existentes.
