# Informe de verificación — T06

Fecha: 2026-10-16. Work-unit commit: 96a8384dea8d6fc6703988122b36698f3ebf8bdf. Todas las invocaciones Gradle y Git se ejecutaron dentro de Docker; no se ejecutó Swift.

## Resultado funcional

| Comprobación | Resultado |
|---|---|
| Pruebas focalizadas de adaptador, cliente y repositorio | BUILD SUCCESSFUL; parser, variante fija, ETag, timeout, cancelación, body máximo, 304 y selección congelada. |
| Pruebas focalizadas añadidas | Opciones/respuestas inválidas, campos requeridos, rollover ISO 2026/2027 y selector sin I/O de disco: BUILD SUCCESSFUL; la nueva prueba no-I/O falló con el selector antiguo (1 lectura inesperada) y pasó con el mapa en memoria. |
| ./gradlew --no-daemon :app:testDebugUnitTest :core:engine-local:testDebugUnitTest | BUILD SUCCESSFUL; informes XML: app 705 tests, 0 skipped, 0 failures; engine-local 11 tests, 0 skipped, 0 failures. |
| git diff --check | Exit 0. |

## Verificaciones bloqueadas por el entorno

| Comprobación | Resultado observado |
|---|---|
| ./gradlew --no-daemon :app:lintDebug | Falla antes del análisis de lint en :core:engine-local:configureCMakeRelease[arm64-v8a]. CMake indica que apps/android/core/engine-local/src/main/cpp/third_party/whisper.cpp no contiene CMakeLists.txt. |
| ./gradlew --no-daemon :app:assembleDebug | Falla con la misma tarea y el mismo directorio nativo ausente. |
| Verificación independiente | Docker scratch: S1–S5 aprobadas, S6 parcial; JVM app 705/705, engine-local 11/11 y 6 probes independientes sin fallos. |

La ausencia de Whisper pertenece al checkout de trabajo y no fue causada por T06. No se intentó descargar ni inicializar una dependencia Git remota; el trabajo permanece local y no se afirma que lint/APK estén validados. Se necesita restaurar la dependencia nativa autorizadamente y repetir ambas comprobaciones. No se ejecutó iOS/Swift en Linux.

## Verificación independiente y bloqueo restante

El verificador independiente revisó S1–S6 en un scratch aislado creado con Docker
tar (sin modificar el worktree ni usar Git). Comprobó invalidación antes de cache,
cancelación/red, reutilización 304 válida, rollover ISO, captura inmutable y ausencia
de acceso síncrono al store. Suite completa: app 705/705 y engine-local 11/11;
seis probes independientes: 6/6. No se reprodujeron defectos introducidos por T06.
S6 queda parcial porque lint y APK no pudieron llegar a sus tareas por la ausencia
del árbol de Whisper indicado arriba. Parent spot-check: las tres clases de red
pasaron 14/14 en Docker. T06 permanece abierta hasta restaurar Whisper por vía
autorizada y ejecutar lint/assemble; no se afirma validación Android completa.
