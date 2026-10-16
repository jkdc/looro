# Informe de verificación — T07

## Resultado

T07 verificada para el flujo Android local y lista para avanzar a T08. El snapshot
remoto conserva su JSON e identidad y la verificación de hash aplica las exclusiones
del contrato API, incluido `generationId`. La finalización guarda respuestas
definitivas, posición, resultado original y eventos de sincronización en una única
transacción; un retry idéntico no crea revisiones/eventos nuevos. El historial usa
datos persistidos y conserva verdictos/explicaciones originales, fecha y señalización
summary-only para resultados legacy.

## Comprobaciones ejecutadas en Docker

- Focalizadas — `:app:testDebugUnitTest --tests com.jkdc.app.data.LevelTestAttemptStoreTest --tests com.jkdc.app.network.LevelTestSnapshotAdapterTest`: **PASS**.
- Suite JVM — `:app:testDebugUnitTest :core:engine-local:testDebugUnitTest`: **PASS**, 712 app + 11 engine; cero fallos.
- Verificación independiente contra copia scratch: **PASS** en S1–S6 para hash
  canónico, fallo/rollback, retry sin mutación de revisión/outbox, reopen por ID,
  snapshot histórico y review original.
- `git diff --check`: **PASS**.
- Work-unit commit: `11e3cf6` — `feat(android): persist and recover weekly exam attempts`.

## Límites aplazados

Por instrucción del usuario, `:app:lintDebug`, `:app:assembleDebug` y pruebas UI
instrumentadas se ejecutarán en la verificación final tras las fases de desarrollo,
no en T07. El intento previo de lint/APK falló al configurar CMake porque falta el
submódulo `whisper.cpp/CMakeLists.txt`; no se descargará ni intentará restaurar ahora.
No se afirma prueba nativa de proceso/Compose ni recuperación entre dispositivos
para T07; la recuperación entre dispositivos corresponde a T12.

**Risk:** item 1 (durable persisted exam results) and item 4 (asynchronous write
ordering and retry idempotence).
