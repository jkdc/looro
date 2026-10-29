# Plan de implementación V01–V07

**Goal:** resolver los siete casos del informe de segunda pasada del 29/10.
**Architecture:** correcciones acotadas a stores, contexto de UI y transporte; sin cambios de esquema ni políticas.
**Tech Stack:** Kotlin/Compose, Swift/SwiftUI, SQLite y pruebas JVM/XCTest.
**Spec:** `specs/mobile-integrity/spec.md`; diseño `design.md`; informe de autoridad en la raíz.

## Restricciones globales

Rama activa `feature/learning-platform`, base `5bfda6015fd6f58c13481b0285d0baad334d2ceb`. Superpowers, no Gentle AI. Sin modelos alternativos, worktrees, proveedores reales ni push. TDD determinista donde hay runner; Swift no ejecutable en Linux. Gradle serializado con `flock /var/tmp/looro-v01-v07-gradle.lock`.

## Review Focus

- Actualización pre-owner conserva datos y marcadores tras rechazo/reinicio (T1).
- Activación correcta pero carga fallida también cierra escrituras (T2).
- Identidad/idioma cambian mientras STT está suspendido (T3).
- Saludo finaliza antes que otra locución, con End ya solicitado (T4).
- Mute/cancelación no reenvían prefijos ni filtran eco (T4).

## Tareas

- [x] T1 / V01–V02: stores de migración y regresiones Android/iOS. Delegado: unidad independiente de datos. Commit `99c92da`; GREEN Android 25 tests, revisión independiente conforme; Swift pendiente de ejecución.
- [x] T2 / V03: estado de activación/carga y gate de writers iOS, regresiones. Delegado: unidad independiente de app iOS. Commit `743fe4d`; revisión independiente estática conforme; ejecución macOS pendiente explícita.
- [x] T3 / V04–V06: proyección, tarjeta y pronunciación Android, regresiones. Delegado: unidad independiente de ViewModel/UI. Commit `f7f454a`; RED observado; GREEN 9 pruebas de integración en emulador, revisión independiente conforme.
- [x] T4 / V07: drenaje de prefijo frente a TTS concurrente, regresiones. Delegado: unidad independiente de transporte. Commit `15e8278`; RED esperado 2/real 1, GREEN final 56 tests ejecutados; revisión independiente conforme.
- [x] T5: revisión independiente por superficies y final sin bloqueadores, gates integrados aprobados. Padre coordina git, SDD y evidencia; ejecución iOS pendiente de macOS por limitación aceptada.

## Pasos por unidad

1. Leer especificación e informe, verificar raíz en código antes de aplicar recomendaciones.
2. Añadir regresiones con los efectos observables especificados; ejecutar RED por el motivo correcto cuando hay runner.
3. Implementar corrección mínima y ejecutar GREEN de la clase afectada. Refactorizar solo con evidencia conservada.
4. Autorrevisar superficie y registrar comandos/resultados exactos, limitaciones y riesgos. Padre revisa y crea commit convencional de la unidad.
5. Para iOS sin runner, escribir pruebas y revisar fuente, sin inventar RED/GREEN ni bloquear el desarrollo.

## Verificación integrada

`make check`; `JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/var/tmp' make android-build` con tests ejecutados explícitamente, no atribuir UP-TO-DATE a ejecución fresca. API aislada si se afecta contrato; no previsto cambio de BE. Revisión independiente de todo el diff contra V01–V07. `verify-report.md` mantiene la evidencia final y pendientes de plataforma.

## Log

- Autorización: «resuelve los casos que has anotado en el documento».
- Se conserva el diseño detallado del informe; no hay decisión de producto abierta. Implementación continua autorizada por las instrucciones del usuario, sin nueva ronda de preguntas.
- Preflight: cuatro superficies de código disjuntas; git y SDD pertenecen al padre. Disco disponible inicial ~3,1 GiB; sin instalaciones ni builds redundantes concurrentes.
- T1: RED de fixture pre-owner observado (no lanzaba cuarentena); GREEN 25 tests del store ejecutados. Revisión independiente conforme, sin bloqueadores.
- T2: carga solicitada/cargada/persistida y gate antes de mutar archivo/BD; 4 regresiones en fichero XCTest ya registrado. Revisión independiente sin bloqueadores; `swift`/`xcodebuild` no disponibles (exit 127), no se afirma ejecución.
- T3: GREEN de 9 pruebas instrumentadas reales (ViewModel/SQLite/Compose) con recorder/STT falsos, sin proveedor ni micrófono real. Incluye A→B, EN→ES→EN, marks/outboxes, cancelación y liberación. La fixture existente se independizó de idioma heredado mediante propietario nuevo y espera de carga EN.
- Revisión final independiente de los siete casos: conforme en fuente, sin bloqueadores concretos. No sustituye resultados de los gates ni ejecución Swift pendiente.
- T4: sustituida espera fija nueva por diagnóstico observable de muestras retenidas; espejo reiniciado en `finally`. Ejecución intermedia falló por diagnóstico obsoleto, corregido y probado con rerun explícito de los bytes finales (56/56).
- 30/10: `make check` del gate integrado y contraste parental aprobados (148 tests cada uno); Android build exit 0, 844 tests app ejecutados, cero fallos/errores/omisiones; motor 11 tests ejecutados mediante `--rerun` específico después del gate que reutilizó caché. Lint 0 errores, 73 warnings y 22 hints; APK generado. Sin cambios adicionales de fuente.
- Cierre: implementados V01–V07, conservada rama activa y sin push. No se repitió API (BE/contratos sin cambios). Pendiente solo ejecución Swift/XCTest/UI iOS en entorno compatible y prueba acústica física no realizada, sin detener implementación.
