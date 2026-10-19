# Estado del plan de recuperación de exámenes

**Fecha de corte:** 17 de octubre de 2026
**Rama de trabajo:** `feat/weekly-exam-recovery`
**Rama de integración prevista:** `feature/learning-platform` (no `main`)
**Fuente de estado:** `odd/tasks/weekly-exam-recovery.md` y evidencias enlazadas en OpenSpec.
**Propósito:** consolidar qué se planificó, qué está implementado y comprobado, qué falta y qué pruebas están aplazadas.

> **Resumen ejecutivo:** el plan comprende 14 tareas. Las casillas del ledger muestran **8 completadas y 6 abiertas** (T08, T09, T11, T12, T13 y T14). El resumen numérico que aparece en otra sección del ledger dice 7/14 y está desactualizado: la lista de tareas incluye también T10 como completada. No se ha hecho commit, merge, push, PR ni despliegue como parte de este informe. La integración local, si procede tras completar las verificaciones, tiene como destino `feature/learning-platform`.

## 1. Objetivo y alcance funcional

La feature permite que una persona empiece un examen semanal, conserve la instancia exacta que comenzó y pueda continuarla o revisar sus resultados posteriormente, incluso tras cerrar la app, reinstalarla o cambiar de dispositivo. La recuperación no debe reconstruir el examen usando el contenido semanal vigente: debe recuperar la instantánea histórica y sus respuestas/resultados. La sincronización cloud requiere una cuenta; el estado todavía no sincronizado depende del dispositivo original.

La arquitectura planificada es **mixta**:

- El cliente obtiene o crea una instantánea identificable del examen y conserva localmente la instantánea, el intento, el progreso y la cola pendiente de sincronización.
- El backend persiste historial y revisiones con identidad estable del intento/examen, autorización por propietario y operaciones de sincronización compatibles con reintentos y conflictos.
- Otro dispositivo/restauración obtiene del backend los datos históricos por identidad, en vez de pedir un JSON dinámico que represente “el examen de esta semana”.
- El cliente mantiene la capacidad de presentar y corregir la instantánea congelada, sin depender de que el catálogo actual conserve exactamente los mismos ítems.
- Las operaciones de conflicto deben ser explícitas; no se mezclan silenciosamente dos ramas de respuestas.

**Límite importante:** datos aún no sincronizados antes de perder el dispositivo no pueden recuperarse desde el servidor. Para que la promesa de restauración entre dispositivos sea válida, el producto debe mostrar claramente estados pendiente/sincronizado/error/conflicto y no afirmar que los cambios locales ya están protegidos por la nube.

## 2. Estado global de tareas

| ID | Estado | Resultado / evidencia resumida | Pendiente principal |
|---|---|---|---|
| T01 | ✅ Completada | Contrato e identidad de instantánea definidos e implementados. | Ninguno según el ledger. |
| T02 | ✅ Completada | Reglas de validación, juicio, configuración y publicación incorporadas. | Ninguno según el ledger. |
| T03 | ✅ Completada con limitación de plataforma | Persistencia local de instantánea/intento/resultado/outbox en el modelo Android/esquema compartido. | La implementación Swift se conserva, pero su verificación queda aplazada hasta el cierre de todas las fases. |
| T04 | ✅ Completada | Historial cloud autenticado y limitado al propietario. Commit de trabajo `2542ef0`; suite PostgreSQL focalizada 4/4 y build API. Revisión nativa aprobada/acknowledged; observación no bloqueante R3-001 en `services/api/src/level-test-attempts.ts:119`. | La suite API completa tuvo problemas ambientales/históricos (assets SQL ausentes o prueba alojada de pago bloqueada); no presentar esa suite como totalmente verde. |
| T05 | ✅ Completada | Sincronización de eventos, ACK/CAS y tratamiento de conflicto implementados en unidades `c8b60fc`, `ce37ae0`, `dc9fc19`, `6bc1696`, `8e83b9f`. Pruebas focalizadas Android/API observadas; suites amplias del API con limitaciones de entorno. | Ninguno funcional según el ledger; conservar limitaciones de verificación en el cierre. |
| T06 | ✅ Completada | Descarga/caché remota Android y selección congelada. Commit `96a8384`; suite app 705/705, motor 11/11 y probes independientes. | Lint/APK aplazados por la ausencia del submódulo Whisper. |
| T07 | ✅ Completada | Autoguardado, reanudación, historial/revisión y restauración Android. Commit `11e3cf6`; JVM app 712 y motor 11 pasaron, más comprobaciones aisladas. | Instrumentación UI, lint y APK pendientes para la verificación final. |
| T08 | ⏳ Abierta | Motor Swift de placement/level-test y golden compartidos implementados en `b97ff6b`; exporters, cross-platform, Python 138 tests, API focalizada 4/4 y Android LevelTestEngine BUILD SUCCESSFUL. Ver `openspec/changes/t08-swift-exam-engines/verify-report.md`. | Ejecutar SwiftPM/Core en macOS/Xcode al final. El typecheck/smoke aislado con stubs no prueba CryptoKit/SHA-256 real ni orden golden; Linux falla antes de ejecutar pruebas por CryptoKit y errores de sintaxis preexistentes en `CheckpointsData.swift`. |
| T09 | ⏳ Abierta / en curso | Paridad iOS en Core/Database/App: fábrica de snapshots, almacenamiento, vistas de historial/revisión, cliente de cuenta/sync/restore y fixture UI. | Persistencia/restauración e interfaz aún necesitan pruebas nativas completas en macOS/Xcode. La verificación de fixture detectó y motivó correcciones, pero no hubo una segunda verificación independiente completa; no darlo por aprobado. |
| T10 | ✅ Completada | Scheduler/estado de generación; commits `1aa322f`, `b84be55`. Verificación independiente acotada de concurrencia y resultado LLM desconocido aprobada. | Ninguno según el ledger. |
| T11 | ⏳ Abierta | Operaciones admin, moderación y estado del scheduler; OpenSpec `openspec/changes/t11-admin-operations/`, migración 046. Tareas 1–4 y 6 marcadas; suite PostgreSQL aislada 529/529, política focalizada 34/34, `npm check`/sintaxis y regresiones de Origin 2/2; recheck independiente acotado aprobado. | Tarea 5: E2E de navegador. No hay Playwright/Chrome/Chromium ni imagen de navegador en caché disponible. No se descargaron dependencias. T11 sigue abierta hasta ejecutar ese E2E. |
| T12 | ⏳ Abierta | Fixture de aceptación parcial para dos páginas de examen EN/A1, semana 202641; comprueba filas/semana/puntuación inicial. | Completar prueba de recuperación exacta entre dispositivos/reinstalación: ítems congelados, orden, opciones/pasajes, respuestas, posición y resultado; cubrir también contenido generado retirado/antiguo; ejecutar pruebas nativas. |
| T13 | ⏳ Abierta | Fixture parcial con historial A/B, conflicto HTTP 409, fork/takeover explícitos. | Probar carrera con respuesta asíncrona/account switch, ownership y ledger del servidor, preservación/subida de la rama fork, respuestas/revisión del servidor en takeover y pérdida offline antes de sync; ejecutar pruebas Swift nativas. |
| T14 | ⏳ Abierta | Tarea de cierre: inventario final de comandos, pruebas, fallos y skips; revisión del diff y luego integración local autorizada. | No comenzar integración antes del cierre real de T08/T09/T11/T12/T13 y de las comprobaciones finales. Destino: `feature/learning-platform`; sin push/PR/despliegue. |

## 3. Evidencia de verificación consolidada

### 3.1 Comprobaciones que sí se han observado

En el entorno Docker cacheado `node:22-bookworm`, sin red:

- `python3 -m unittest discover -s scripts/tests -t .`: **138 pruebas pasaron**.
- Comprobación de fuentes generadas del esquema DB: al día; 44 sentencias; `user_version` 5.
- `export_android_content.py --check`: pasó.
- `check_cross_platform.py`: pasó.
- Android/JVM: T06 reportó 705 pruebas de app y 11 del motor; T07 reportó 712 de app y 11 del motor; T08 reportó `LevelTestEngineTest` con `BUILD SUCCESSFUL`.
- Backend: T04 4/4 de PostgreSQL focalizadas; T11 529/529 PostgreSQL, suite de políticas 34/34 y pruebas Origin 2/2; T08 API focalizada 4/4.
- `git diff --check`: pasó después de las correcciones documentadas.
- T10 tuvo verificación independiente acotada satisfactoria para las propiedades de scheduler informadas.

Estos resultados son evidencia de sus comandos y revisiones concretas; **no equivalen a una ejecución completa de todas las plataformas ni a una certificación global de la feature**.

### 3.2 Pruebas aplazadas deliberadamente

Por instrucción del usuario, **no se ejecutará la verificación Swift hasta terminar todas las fases de implementación**. Se implementa iOS, pero la prueba se deja para el cierre en un entorno macOS/Xcode. También queda para el final la parte dependiente de Whisper. En Android quedan lint, APK y UI instrumentation; en T11 queda el E2E con navegador.

No se intentará resolver Swift en Linux sustituyendo la implementación real por stubs como si fueran equivalentes: el smoke aislado ya realizado sólo proporciona comprobación limitada de tipos/flujo y no reemplaza CryptoKit, SwiftPM ni las pruebas reales.

### 3.3 Fallos / limitaciones que deben permanecer visibles

- `swift test --package-path apps/ios` en Linux no llegó a ejecutar la suite por falta de CryptoKit y errores de sintaxis en un archivo generado/preexistente (`CheckpointsData.swift`). Resultado: **no verificado**, no “tests Swift pasaron”.
- La suite amplia del API tuvo problemas de entorno ya registrados (assets SQL omitidos y una prueba externa/alojada que puede quedar esperando). Sólo deben citarse como aprobadas las suites focalizadas observadas.
- No hay runner de navegador disponible en caché para T11; no se bajaron imágenes/dependencias y el E2E sigue pendiente.
- No hubo recheck independiente final del fixture de T09 después de arreglar los dos regresiones que detectó el verificador (consulta `?limit=10` y expectativa de `examId` plano frente a snapshot anidado). Hay readback y `git diff --check`, insuficientes para afirmar que el fixture quedó validado.
- La pérdida del dispositivo antes de sincronizar deja los cambios no subidos irrecuperables; el comportamiento debe explicarse en UI y probarse en T13.

## 4. Riesgos y garantías que quedan por demostrar

1. **Identidad histórica, no contenido mutable:** las pruebas de restauración deben demostrar que el examen recuperado es la misma instantánea y no la composición semanal más reciente.
2. **Propiedad y aislamiento de cuenta:** ninguna cuenta debe leer, sobrescribir o restaurar intentos de otra; logout/cambio de cuenta debe invalidar o aislar la operación en vuelo.
3. **Reintentos y concurrencia:** ACK/CAS y conflictos deben evitar pérdida silenciosa, duplicación o regresión de respuestas bajo retries y carreras.
4. **Fork/takeover explícitos:** resolver conflicto debe preservar exactamente la rama elegida y dejar evidencia clara; nunca fusionar respuestas por conveniencia.
5. **Promesa de sincronización honesta:** la app debe distinguir guardado local de respaldo cloud confirmado. No sincronizado no significa recuperable tras perder el dispositivo.
6. **Cobertura de datos históricos:** incluir snapshots de exámenes generados que ya no están publicados y payloads legacy soportados.
7. **Integridad de contenido:** golden interplataforma sólo se considera demostrado con pruebas reales Swift, no con un stub que imita CryptoKit.

## 5. Secuencia recomendada para cerrar el plan

1. **Cerrar T09**: terminar implementación/paridad iOS y completar pruebas del fixture/casos de revisión local, sin ejecutar Swift todavía según la instrucción vigente; registrar claramente qué queda reservado para macOS.
2. **Cerrar T12**: ampliar aceptación de restauración exacta y datos legacy/generados retirados, incluyendo atributos congelados de examen y resultado.
3. **Cerrar T13**: probar pérdida offline, estados de sincronización, aislamiento y carreras de cuenta, fork y takeover; revisar la evidencia después de las correcciones del fixture.
4. **Cerrar T11**: obtener un runner de navegador reproducible y ejecutar el E2E pendiente. Hasta entonces queda abierta, no sustituirlo por pruebas de política/API.
5. **Completar T08** en la verificación final macOS/Xcode junto con las pruebas Swift de T09/T12/T13. Mantener el informe de verificación distinguiendo ejecución real y simulaciones limitadas.
6. **Cierre T14**: correr el inventario final autorizado; documentar resultados reales, fallos, skips y límites. Ejecutar pruebas Android pendientes y los checks de contratos/API aplicables en Docker; completar las comprobaciones de Swift en macOS cuando exista el entorno permitido; dejar Whisper para el cierre final.
7. **Revisar el diff y el estado de rama**; sólo cuando todos los gates requeridos estén satisfechos, realizar la integración local prevista sobre `feature/learning-platform` conforme al alcance ya autorizado. No integrar a `main`, ni publicar o desplegar.

El orden puede ajustarse por dependencias de implementación, pero no debe ocultar tareas pendientes ni convertir una prueba no ejecutada en una aprobada.

## 6. Restricciones operativas acordadas

- Los comandos de compilación y pruebas del proyecto se ejecutan dentro de Docker; conservar cachés persistentes cuando corresponda. No instalar JDK, Gradle ni Android SDK en el host.
- La app Gentle AI corre fuera de Docker; Docker se usa para comandos de ejecución del proyecto, no para lanzar Gentle AI.
- Swift se implementa ahora y se prueba al final, cuando hayan acabado las fases de desarrollo. No bloquear fases por la falta actual de Xcode/macOS.
- Whisper puede posponerse hasta terminar las fases; no es un bloqueo para continuar ahora.
- No hacer commit, merge, push, PR ni despliegue como parte de este informe. El trabajo existente permanece según su estado actual; este documento no afirma que el árbol esté limpio.
- Si se autoriza la integración después de T14, el destino es la rama base de trabajo `feature/learning-platform`, nunca `main`.

## 7. Referencias del repositorio

- `odd/tasks/weekly-exam-recovery.md` — ledger principal T01–T14, estado, ruta y log de trabajo.
- `informe-plan-pendientes-2026-10-13.md` — informe de planificación/origen; permanece en el checkout base y no se ha editado con este informe.
- `openspec/changes/t08-swift-exam-engines/verify-report.md` — evidencias y limitaciones de T08.
- `openspec/changes/t11-admin-operations/` — diseño, tareas y evidencias operativas/admin.
- `openspec/changes/t09-ios-exam-history/` — implementación iOS e historial asociado (si aplica al estado del árbol).
- `openspec/changes/t12-cross-device-acceptance/` — fixture/contrato de restauración y aceptación.
- `openspec/changes/t13-sync-conflict-acceptance/` — fixture de sincronización, aislamiento y conflicto.

## 8. Criterio de finalización

El plan no debe declararse terminado hasta que todas las tareas requeridas estén cerradas con evidencia verificable, los skips estén resueltos o aceptados explícitamente, la revisión final del diff no deje bloqueadores y T14 registre el estado real de pruebas y la integración local prevista. En particular, T08/T09/T12/T13 necesitan sus pruebas Swift reales al final, T11 necesita el E2E browser y T07/T06 conservan pruebas Android de cierre pendientes.
