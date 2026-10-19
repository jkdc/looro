# T06 — Plan de implementación

- [x] **T06.1 (S1–S2)** DTO, adaptador y validador; regresiones de identidad, esquema, IDs, referencias, opciones y campos obligatorios.
- [x] **T06.2 (S1–S4)** Cliente OkHttp con deadline total, cancelación, límite de body, ETag/cache y fallback; pruebas MockWebServer.
- [x] **T06.3 (S3,S5)** Selección inmutable y cache desechable integrada en ViewModel/screen; una respuesta tardía queda para el siguiente intento.
- [ ] **T06.4 (S6, parcial)** Suite JVM (app 705/705; engine-local 11/11) y verificación independiente (6/6 probes) aprobadas; parent spot-check 14/14. Lint y assemble Android siguen bloqueados antes de sus tareas por falta del árbol fuente de Whisper (third_party/whisper.cpp/CMakeLists.txt); no se descargó ni inicializó una dependencia remota. Mantener T06 abierto hasta restauración autorizada y rerun.
