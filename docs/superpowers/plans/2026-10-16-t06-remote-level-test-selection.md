# Plan de implementación: T06 selección remota de level-tests Android

Objetivo: consumir el endpoint híbrido sin que contenido inválido o una descarga tardía cambie un examen iniciado.
Spec: `openspec/changes/t06-android-remote-level-test-selection/`; ejecutar por unidades con TDD y Docker.

## Tareas

1. **DTO/adaptador/validador.** Inspeccionar los modelos Android y el contrato del snapshot. Añadir pruebas que rechacen schema desconocido, identidad/semana/variante distinta, IDs repetidos, referencias de pasaje ausentes, opciones/respuestas inválidas y límites excedidos. Ejecutar focalizadas y observar RED antes del adaptador. Producir un `LevelTestEngine.Exam` que conserve orden e identidad del servidor; no re-componer el contenido.
2. **Fetch/cache.** Cliente OkHttp injected por `HttpUrl` y `Call.Factory`, sin auth/cookies/redirects/retries. `callTimeout(5s)`, tamaño máximo antes de parseo, cancelación coroutine→Call. Cache desechable bajo cacheDir con escritura atómica y clave de idioma/nivel/variant; ETag condicional solo sobre cache validada. Probar 200, 304 válido/ausente/corrupto, errores, timeout y cancelación con MockWebServer.
3. **Selección congelada e integración.** Al entrar en el examen, capturar una selección validada de caché fresca o bundled; respuesta de refresh posterior solo actualiza la caché. Mantener visible el examen capturado aunque cambie estado observado; al siguiente intento se puede usar la nueva candidata. Probar con fake clock/cache y estado de intento ya contestado.
4. **Normalización y verificación.** Revisar el diff, ejecutar tests Android completos, lint y assemble dentro de Docker. Reportar fallos ambientales honestamente. Actualizar SDD, ledger y el mirror Engram antes de cerrar.

## Comandos de verificación autorizados

```sh
./gradlew --no-daemon :app:testDebugUnitTest :core:engine-local:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug :app:assembleDebug
```

Ejecutar ambos mediante la imagen Docker Android ya aprobada y su volumen Gradle persistente. No instalar SDK/JDK/Gradle en el host.
