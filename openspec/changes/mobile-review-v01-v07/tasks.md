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

- [ ] T1 / V01–V02: stores de migración y regresiones Android/iOS. Delegado: unidad independiente de datos.
- [ ] T2 / V03: estado de activación/carga y gate de writers iOS, regresiones. Delegado: unidad independiente de app iOS.
- [ ] T3 / V04–V06: proyección, tarjeta y pronunciación Android, regresiones. Delegado: unidad independiente de ViewModel/UI.
- [ ] T4 / V07: drenaje de prefijo frente a TTS concurrente, regresiones. Delegado: unidad independiente de transporte.
- [ ] T5: revisión independiente por superficies, correcciones acotadas y gates integrados. Padre coordina git, SDD y evidencia.

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
