# T07 — Implementación

- [x] **T07.1 (S1,S3,S5)** Codec versionado de snapshot y round-trip remote/bundled; conserva JSON remoto y prueba hash API canónico; repository reconstruye examen/respuestas desde intento.
- [x] **T07.2 (S1–S4,S6)** ViewModel conecta start/autosave/retry/complete al FIFO IO y transacciones existentes; Compose solo muestra éxito durable.
- [x] **T07.3 (S3–S6)** Learn muestra intentos activos, historial, legacy summary-only y navegación de reanudar/revisar; UI localized EN/ES.
- [x] **T07.4 (S1–S6)** Verificar reopen, week/bundle rollover, fallo/reintento e idempotencia; suite/checks Android en Docker; actualizar informe/tracker/mirror.

## Evidencia de cierre

- Prueba focalizada: `:app:testDebugUnitTest --tests com.jkdc.app.data.LevelTestAttemptStoreTest --tests com.jkdc.app.network.LevelTestSnapshotAdapterTest` — exit 0.
- Suite JVM Android completa: `:app:testDebugUnitTest :core:engine-local:testDebugUnitTest` — 712 tests de app y 11 de engine, exit 0.
- Verificación independiente en copia scratch: las regresiones de hash API, fallo/rollback, reintento idempotente sin cambios de revisión/outbox, y reopen/file-backed con review histórico pasan; `git diff --check` limpio.
- `lintDebug`, APK y pruebas Android instrumentadas quedan aplazadas por decisión del usuario hasta completar las demás fases; el entorno todavía carece de `whisper.cpp/CMakeLists.txt`. No se intentó obtenerlo.
- La UI Compose de historial/revisión fue inspeccionada por el verificador independiente; no se afirma una ejecución instrumentada de los flujos UI.
- Riesgo: item 1 (persistencia durable de exámenes/results), item 4 (orden de escrituras y estado de Compose).
- Commit de trabajo: `11e3cf6` (`feat(android): persist and recover weekly exam attempts`).
