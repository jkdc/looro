# Verificación V01–V07

Base: `5bfda6015fd6f58c13481b0285d0baad334d2ceb`. Rama activa; sin push ni worktrees. Cierre de comprobaciones: 30 de octubre de 2026.

**Resultado:** implementados los siete casos y revisión independiente sin bloqueadores concretos. Gates disponibles aprobados. iOS implementado/revisado estáticamente, no compilado ni ejecutado por falta de Swift/Xcode en Linux.

## Evidencia por caso

| Caso | Estado | Evidencia |
|---|---|---|
| V01 | Implementado y revisado | Commit `99c92da`; RED Android observado: no se lanzaba cuarentena; GREEN 25 tests del store ejecutados. Revisión independiente conforme. Swift: pruebas escritas, no ejecutadas. |
| V02 | Fuente corregida y revisada | Commit `99c92da`; helper Swift privado; compilación/XCTest pendientes de macOS. |
| V03 | Implementado y revisado estáticamente | Commit `743fe4d`; cuatro regresiones de store, gates de writers y reintento persistente. Revisor independiente sin bloqueadores concretos. Swift/XCTest no ejecutados. |
| V04–V06 | Implementados y revisados | Commit `f7f454a`; RED 7 regresiones nuevas por los motivos esperados; GREEN 9/9 pruebas en APK `.uitest` aislado. ViewModel/Compose y SQLite/outboxes reales, recorder/STT falsos; 0 fallos/omisiones. |
| V07 | Implementado y revisado | Commit `15e8278`; RED: filtro commentary, 5 tests/1 fallo esperado 2 peticiones, real 1. GREEN final exact-source: 56/56 tests de voz, 0 fallos/omisiones, con `--rerun`. |

## Gates integrados

Revisión independiente de migración/voz, otra de iOS y una final de los siete casos: conformes en fuente, sin bloqueadores concretos. No se presentan como ejecución funcional.

| Gate | Resultado fresco |
|---|---|
| `make check` | Exit 0; 148 tests Python y exportadores/paridad/esquema correctos. 4,09 s; contraste parental repetido, también 148 tests/exit 0. |
| `JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/var/tmp' make android-build` | Exit 0 en 412,21 s. App: 844 tests/95 suites ejecutados, cero fallos/errores/omisiones. Motor UP-TO-DATE en este comando. |
| `JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/var/tmp' ./gradlew :core:engine-local:testDebugUnitTest --rerun` | Exit 0 en 3,87 s; 11 tests/2 suites del motor ejecutados, cero fallos/errores/omisiones. Reejecución solo de esa tarea para no atribuir caché a ejecución fresca. |
| Lint / APK | 0 errores, 73 warnings y 22 hints; APK debug generado (76.955.297 bytes). No se presenta lint como libre de advertencias. |

No se observó fallo flaky en la ejecución completa final. La compilación no cambia la conclusión sobre pruebas Swift no ejecutadas.

Logs: `/var/tmp/v01-v07-contracts.log`, `/var/tmp/v01-v07-parent-check.log`, `/var/tmp/v01-v07-android-build.log`, `/var/tmp/v01-v07-engine-fresh.log`. Resumen XML conservado justo al terminar el gate completo: `/var/tmp/v01-v07-full-gate-xml-summary.json` (motor todavía cached en ese snapshot; la ejecución fresca posterior está en su log).

No hay cambios de BE ni de contrato compartido; no se repite API aislada en esta implementación. La ejecución de la revisión anterior es evidencia histórica, no un resultado fresco de este change.

## Comandos enfocados y evidencia

Todos los comandos Gradle usan `JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/var/tmp'` y se serializan mediante `flock /var/tmp/looro-v01-v07-gradle.lock`.

- V01: `:app:testDebugUnitTest --offline --tests com.jkdc.app.data.LearningDbStoreTest.preOwnerRowsAreQuarantinedBeforeBackfillAndRemainUnchangedAfterRestart` → RED por ausencia de cuarentena. Suite `--tests com.jkdc.app.data.LearningDbStoreTest` → 25 tests ejecutados, sin fallos/errores/omisiones. Logs `/var/tmp/v01-{red,green}.log`.
- V04–V06: `:app:connectedUiTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jkdc.app.PronunciationIdentityTest,com.jkdc.app.MobileAuditHistoryTest` → GREEN 9/9 en `emulator-5554`. RED incluyó siete regresiones nuevas correctas y un fallo adicional de fixture antigua dependiente del idioma; se aisló el fixture con cuenta nueva y espera de carga EN. Logs/XML `/var/tmp/v04-v06-{red,green}*`.
- V07: `:app:testDebugUnitTest --tests com.jkdc.app.LocalVoiceTransportTest --rerun` → 56/56 en el resultado final. XML/log `/var/tmp/v07-green-final.{xml,log}`; RED conservado `/var/tmp/v07-red.{xml,log}`. La ejecución intermedia fallida del diagnóstico no se presenta como verde.

## Cobertura de los escenarios

- Migración: propietario vacío válido, ausencia de marcadores, datos distintos del respaldo, fuente/metadatos/mastery intactos, cierre/reapertura; iOS variantes con/sin mastery.
- Cuenta iOS: fallo de activación, fallo de carga después de activación correcta, discrepancia del owner persistido, reintento de la misma cuenta y A/B preservados; mutaciones rechazadas no modifican SQLite (`total_changes()`).
- Proyección/UI Android: miembro con rollup cargado, transición y vuelta a invitado; tarjetas EN→ES→EN con el mismo nivel y resultados propios de ambos idiomas.
- Pronunciación: STT tardío tras cambio de usuario/idioma, archivo/SQLite/outboxes sin escritura cruzada, éxito una vez, cancelación y error con liberación de recorder y reintento.
- Voz: ambas finalizaciones de locución, muestras y orden del prefijo, exclusión de eco, End retenido, mute y desconexión. Barreras observables, no espera fija nueva para admisión.

## Limitaciones

Swift/Xcode no disponibles. Las pruebas iOS se implementan y revisan estáticamente; ejecución pendiente explícita, no bloqueo de implementación. No se hacen llamadas reales a proveedores ni pruebas acústicas físicas.

## Entrega y reversión

| Unidad | Commit | Límite de reversión |
|---|---|---|
| Diseño/plan | `04c5868` | SDD nuevo de esta ronda, sin comportamiento. |
| V03 | `743fe4d` | Estado de carga/writers de Storage y gates/UI coordinados, con sus tests. No revertir solo el gate dejando la publicación prematura. |
| V01–V02 | `99c92da` | Preflight de ambos stores y regresiones, más visibilidad del helper Swift. No toca esquema ni datos mediante scripts. |
| V04–V06 | `f7f454a` | Proyección/tarjeta y contexto de pronunciación, con pruebas instrumentadas. |
| V07 | `15e8278` | Gate de drenaje y diagnóstico/pruebas del transporte. |

Los informes históricos y `.playwright-mcp/` preexistentes quedan intactos y sin incorporar a estos commits. No se ha publicado ni integrado la rama en otra.

**Risk: items 1, 2, 4** — preservación de datos existentes, aislamiento de identidad y orden asíncrono; revisión independiente realizada y limitaciones de plataforma registradas.
