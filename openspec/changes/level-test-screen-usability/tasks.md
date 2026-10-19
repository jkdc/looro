# Tasks: level-test-screen-usability

1. [ ] Afordance de preguntas: tarjetas con número y borde; opciones con borde
   y selección destacada; inputs de texto con borde y placeholder; chip rojo
   de sin responder. (LevelTestScreen.kt, strings EN/ES)
2. [ ] Entrega en dos pasos: gating por ≥1 respuesta, marcado en rojo de
   huecos, confirmación con contador, scroll al primer hueco.
3. [ ] Silenciar autoguardado en el examen activo (solo fallo visible).
4. [ ] Instrucciones por sección con botón de información colapsable.
5. [ ] Reproductor de escucha: play/stop único, barra de progreso estimada,
   seek por fracción; `playListeningFrom` en AppViewModel.
6. [ ] Teclado: imePadding en la pantalla; verificación manual con input al
   final del examen.
7. [ ] Verificación: build + lint + suites JVM; prueba manual en emulador y
   teléfono físico; capturas de evidencia.
8. [ ] Paridad iOS de la misma pantalla (posterior a esta pasada Android).
