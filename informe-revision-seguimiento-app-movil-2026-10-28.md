# Revisión del seguimiento de la remediación móvil

**Fecha:** 28 de octubre de 2026.
**Documento contrastado:** [revisión del 27 de octubre](informe-revision-implementacion-app-movil-2026-10-27.md), R01–R19.
**Estado:** rama `feature/learning-platform`, HEAD `b963edafcab32ceadac38f6ff5bb8d42a1899711`. Comparación con `7528e44` y lectura del código actual.
**Alcance autorizado:** revisión y nuevo informe; no se ha corregido la aplicación ni cambiado el estado de las tareas SDD.

## 1. Resultado ejecutivo

La remediación ha avanzado de forma importante: cuarentena del fallback, contrato v1, filtrado temporal, persistencia de páginas, intención durable de bootstrap, estados de sincronización, cercado por identidad/generación y arbitraje de audio están implementados. Sin embargo, **quedan siete problemas concretos y un riesgo de orden pendiente de demostrar**. No debe confundirse «implementación terminada en este entorno» con «todos los flujos de aceptación verificados».

Tres escenarios Android se reprodujeron mediante sondas: recuperación bloqueada de una migración al cambiar de cuenta y dos pérdidas de voz. En iOS se identificaron defectos por código, sin afirmar compilación ni ejecución XCTest. La falta de Swift/Xcode no se utiliza como bloqueo para continuar implementando: se mantiene como limitación explícita de evidencia.

| ID | Prioridad | Problema | Evidencia | Referencia anterior |
|---|---|---|---|---|
| S01 | P1 | La migración fallida puede quedar irrecuperable al cambiar A→B | SQLite/JVM y fuente | R01 |
| S02 | P1 | iOS rechaza páginas válidas por comparar claves concatenadas | Defecto estático + sonda de orden | R05 |
| S03 | P2 | El buffer lleno durante el saludo descarta toda la intervención manual | Sonda Kotlin | R11/R13 |
| S04 | P2 | Entre 18 y 20 segundos se pierde el final de voz sin aviso | Sonda Kotlin | R12 |
| S05 | P2 | La prueba iOS de 1.001 descargas entrega filas sin propietario al writer | Defecto estático de test | R09/R10 |
| S06 | P2 | La limpieza fallida de conversación no registra recuperación global | Defecto estático, fallo condicionado | R19 |
| S07 | P2 | Aprender conserva indicadores anteriores al bootstrap mientras está montado | Defecto estático; UI no ejecutada | R04/R06 |
| S08 | Riesgo | Una notificación tardía de cuenta puede cancelar el nuevo bootstrap iOS | Orden plausible, no reproducido | R18 |

P1/P2 expresan prioridad de trabajo, no puntuaciones de seguridad. No se ha demostrado exposición entre cuentas ni pérdida de datos en producción.

## 2. Método y reservas

- Tres revisiones independientes: persistencia/sincronización Android, voz Android e iOS. Se revisaron el informe de referencia, SDD, código, cambios y pruebas; los checkboxes no se tomaron como prueba de corrección.
- CodeGraph se sincronizó antes de la exploración estructural; se contrastaron después fuentes y líneas concretas.
- Las sondas se ejecutaron fuera de los archivos versionados. Las pruebas de voz utilizaron un sourceSet externo temporal; la migración utilizó SQLite JDBC y una copia de clases compiladas contrastadas con la fuente actual. Sus limitaciones figuran en la sección 5.
- Se preservaron las modificaciones previas de documentación `mobile-audit-remediation`, `.playwright-mcp/` y los informes no versionados. No se hicieron commits, push, despliegues ni llamadas a proveedores reales.
- Se mantiene la premisa local-first: datos por cuenta, invitado sin fusión, fechas originadas en el móvil y bootstrap solo para el miembro inicialmente elegible. La continuidad de una descarga ya autorizada no debe confundirse con autorizar una descarga nueva después de reset/import.

## 3. Hallazgos y acciones

### S01 — Fallo entre fases de migración y cambio de cuenta bloquean la recuperación

**P1 · Android · Reproducido · Residual: la inconsistencia de ámbitos ya existía en la base.**

`apps/android/app/src/main/java/com/jkdc/app/data/LearningDbStore.kt:327–337` importa las filas antes de confirmar verificación y metadatos. Si falla la segunda fase, quedan filas sin el marcador final de migración. El reintento limpia solo el propietario activo (`:357–365`), pero `verifyCounts` compara tablas completas (`:394–411`).

**Escenario observado:** fixture real `case-basic.json`; migración como A; excepción inyectada en `clock()` antes del metadato final; reintento como B con fuente válida:

```text
A open rejected: injected metadata commit failure
after A: member:A sessions=3
B retry rejected: migration aborted: sessions count 6 != 3
after B: member:A sessions=3
after B: member:B sessions=3
```

Un fallo de confirmación o interrupción entre fases basta; no requiere un respaldo corrupto. Los reintentos posteriores encuentran residuos de la otra identidad y el conteo global vuelve a fallar. **Ambas aperturas se rechazan:** la sonda demuestra bloqueo de recuperación e importaciones parciales, no acceso visible a datos ajenos.

**Acción:** hacer atómica la importación con su verificación/marcador, o introducir un estado pendiente que identifique inequívocamente el propietario de la migración y permita reanudarla. Limpieza y verificación deben compartir ámbito. No resolverlo borrando filas de otras cuentas ni atribuyendo de nuevo el respaldo al último usuario.

**Aceptación:** prueba file-backed con fallo posterior a la importación, A→B, reinicio y reintento; verificar recuperación sin borrado cruzado, exactitud de filas y metadatos y hash/bytes originales del respaldo. Cubrir también fallo antes del commit de contenido y fallo de archivo posterior al commit.

### S02 — iOS conserva la comparación incorrecta de claves en la descarga

**P1 · iOS · Defecto estático · Preexistente, no resuelto por la corrección de subida.**

`apps/ios/App/ProgressClient.swift:159–160` valida el orden de una página con cadenas `kind|itemID|language`. La API ordena columnas separadas en `services/api/src/progress.ts:152`.

La página `(lesson, a, en)`, `(lesson, a-1, en)` está correctamente ordenada por tupla, pero las claves concatenadas invierten ese orden. El cliente devuelve `.failure` y no avanza el bootstrap. La sonda de comparación confirma la inversión; no se presenta como ejecución Swift. Los tests del comparador de cursor de subida no ejercitan este decoder HTTP.

**Acción:** usar una clave estructurada para validar orden y unicidad de filas descargadas, con el mismo contrato de componentes que el servidor. Evitar que el delimitador determine la identidad o el orden. Conservar la validación estricta de páginas malformadas y no avanzar el cursor al rechazarlas.

**Aceptación:** prueba de `fetchPage` con respuesta HTTP válida que incluya `a`, `a-1`, idiomas distintos y límites entre páginas; comprobar aceptación, persistencia y conjunto final exacto. Añadir páginas realmente desordenadas y duplicadas que sí deban rechazarse.

### S03 — Saturar el buffer del saludo elimina toda la voz manual retenida

**P2 · Android · Reproducido · Interacción introducida por el nuevo buffer inicial.**

`apps/android/app/src/main/java/com/jkdc/app/core/voice/LocalVoiceTransport.kt:420–431` llena `openingFrames` y llama a `notifyManualCaptureLimit()`. Esa señal también activa el bloqueo de captura manual que se consulta en `:489`, antes de detectar voz en los frames reproducidos.

**Escenario:** saludo suspendido → introducir 20 segundos de voz manual → completar saludo → pulsar Finalizar. El aviso dice que lo dicho se conserva, pero el indicador de límite descarta los frames retenidos al procesarlos. `voicedInBuffer` queda en cero y no se crea petición de respuesta.

La sonda falla esperando dos peticiones al motor —saludo y respuesta— y observa únicamente una. La regresión existente del orden del saludo sí pasa: no cubre saturación durante ese saludo.

**Acción:** separar saturación del buffer inicial de bloqueo de entrada posterior al límite del turno. El prefijo ya retenido debe consumirse una vez; solo las muestras posteriores deben rechazarse. Mantener el límite de memoria y no enviar automáticamente el turno manual.

**Aceptación:** saludo bloqueado con buffer inicial lleno; después del saludo y del fin explícito, verificar la identidad/contenido de las muestras entregadas a STT, una única petición de respuesta y ausencia de autoenvío antes de Finalizar. Cubrir cancelación y mute durante la espera.

### S04 — El límite STT sigue truncando el final sin informar

**P2 · Android · Reproducido · Defecto residual, no la antigua pérdida del inicio.**

`LocalVoiceTransport.kt:712–717` conserva solo los primeros 18 segundos para STT, mientras la captura llega a 20 segundos y el aviso de límite depende de ese máximo/overflow (`:561–563` y rutas de notificación).

**Escenario:** 19,2 segundos de habla y fin manual. La sonda utiliza 150 frames y un marcador distinguible en los últimos cinco; llegan 288.000 muestras a STT, sin el marcador ni evento `session.capture.limit_reached`. Ahora se conserva el comienzo, pero las palabras finales desaparecen sin aviso.

**Acción:** armonizar captura y STT o segmentar conservando el orden. Si el contrato del proveedor exige conservar solo un prefijo, emitir un aviso visible también cuando el recorte se decide al finalizar; no limitar la comunicación a la saturación de captura. No basta con escribirlo en el log.

**Aceptación:** 18 segundos exactos, inmediatamente superiores y 19,2 segundos; afirmar audio completo o aviso explícito persistente, sin turno duplicado y sin envío manual prematuro. Conservar los tests de espera silenciosa y de preservación del inicio.

### S05 — La nueva regresión iOS no alcanza el reinicio que pretende probar

**P2 · iOS · Defecto estático de test, no de la reasignación productiva.**

`apps/ios/AppTests/ProgressClientTests.swift:132–133,157–158` pasa `page.rows` directamente a `LearningDbWriter.remoteProgressPageApplied` con propietario `account-1`. `ProgressClient.swift:153` crea esas filas con `ownerAccountID: ""`; `apps/ios/Core/Database/LearningDbWriter.swift:166–168` rechaza la discrepancia.

Por tanto, `testAll1001DownloadsResumeFromPersistedCursorAfterDatabaseRestart` lanzaría `remote progress page owner mismatch` en la primera persistencia, antes del cierre/reapertura. La ruta productiva sí reasigna el propietario en `ConversationCoordinator.swift:312–316`. **No se ejecutó XCTest**; la contradicción se deduce de la construcción y precondición explícitas.

**Acción:** asociar las filas a la identidad autenticada antes de persistir y revisar ambas páginas. Preferir ejercitar el adaptador/orquestación productiva para que el test no reconstruya de forma diferente el contrato de propiedad.

**Aceptación:** ejecutar en macOS el recorrido completo: primera página, cursor durable, cierre/reapertura, segunda página inválida sin mutación, continuación válida y conjunto de 1.001 identidades exacto. Hasta entonces no citar esta prueba como evidencia ejecutada de continuidad.

### S06 — La recuperación global de audio solo cubre pronunciación

**P2 · iOS · Ruta de fallo identificada por código; efecto físico sin verificar.**

`apps/ios/App/LiveTransport.swift:126–139` mantiene el lease cuando falla desactivar el audio o restaurar categoría, pero retorna sin registrar `retainCleanup`. `AudioSessionLeaseOwnership.acquire`, en `PronunciationCheckController.swift:122–126`, solo reintenta limpieza si existe esa closure.

**Escenario condicionado:** terminar conversación → fallo transitorio de limpieza → intentar pronunciación. Aunque la condición transitoria desaparezca, pronunciación solo encuentra el token ocupado: no tiene limpieza registrada que reintentar. Iniciar otra conversación puede recuperarlo porque `connect()` vuelve a llamar a `disconnect()`; esa alternativa no resuelve el recorrido de pronunciación ni garantiza recuperación si desaparece el transporte.

**Acción:** registrar recuperación de la sesión de conversación en el coordinador compartido, independientemente del transporte, con el mismo control de token previo a tocar audio. No liberar el token a ciegas ni desactivar una sesión cuyo propietario ya cambió.

**Aceptación:** lease de conversación → primera limpieza falla → desaparece el propietario original → intento de pronunciación reintenta con éxito → adquisición válida. Comprobar que el objeto antiguo no puede alterar al nuevo propietario. Añadir validación física de rutas/interrupciones cuando haya dispositivo.

### S07 — Los indicadores de Aprender no se recalculan tras restaurar progreso

**P2 · Android · Defecto estático de actualización · Preexistente.**

`apps/android/app/src/main/java/com/jkdc/app/ui/LearnScreen.kt:67` guarda `progressStats()` y `completedLessonIDs()` en `remember` sin claves. El bootstrap persiste páginas y aumenta `ledgerRevision` (`AppViewModel.kt:1230–1236`), pero recomponer no vuelve a ejecutar esa lectura. El conjunto congelado alimenta niveles completos (`LearnScreen.kt:133–135`) y unidades/lecciones (`:276`).

Si Aprender está montado durante el bootstrap, puede seguir indicando lecciones pendientes aunque la BD ya contenga el progreso. Algunas métricas calculadas directamente pueden actualizarse y dejar la pantalla inconsistente. No se ejecutó una prueba Compose montada, por lo que no se afirma una captura visual observada.

**Acción:** derivar esos valores de identidad, idioma y revisión observables, o exponer una proyección de progreso observable. Evitar consultas a BD costosas en cada recomposición y no usar un `remember` permanente para datos asíncronos.

**Aceptación:** montar Aprender vacío, completar descarga diferida y comprobar marcas sin navegar fuera; repetir al cambiar usuario e idioma. Las marcas de A no deben sobrevivir a B en una instancia retenida.

### S08 — Riesgo de cancelación tardía tras adoptar cuenta en iOS

**Riesgo por confirmar · No clasificado como defecto reproducido.**

El observador en `apps/ios/App/ConversationCoordinator.swift:558–559` encola un `Task` para `accountSessionChanged()`, que invalida sync (`:715–717`). Por separado, `RootView.swift:39–40` activa cuenta y sincroniza (`ConversationCoordinator.swift:731–733`). Si la reacción de SwiftUI precede al task encolado, la notificación puede cancelar el bootstrap recién creado sin reemplazarlo.

**Acción inicial:** prueba de scheduling controlado para ambos órdenes, con invitado→A y A→B, carga y GET suspendidos. Si se reproduce, unificar adopción e invalidación o cercar la notificación por identidad/generación para que una señal antigua no cancele trabajo nuevo. No introducir sleeps para corregirlo.

## 4. Balance frente a R01–R19

| Referencia | Resultado de esta revisión |
|---|---|
| R01 | Cuarentena y retry presentes; recuperación multipropietario incompleta, S01. iOS falla cerrado en la ruta revisada. |
| R02–R03 | Decoder v1, fechas first/last y filtrado por contribución presentes con regresiones; sin otro defecto concreto encontrado en las rutas revisadas. |
| R04/R06–R08 | Activación, intención durable, persistencia por página, feedback y retry implementados; S07 y falta de prueba del montaje real. |
| R05 | Comparador de subida corregido; queda el decoder de descarga iOS, S02. |
| R09–R10 | Cambios estáticos de compilación/fixtures presentes; S05 invalida una nueva regresión. Swift/Xcode no ejecutados. |
| R11–R13 | Mejora del buffer y orden del saludo; S03–S04 reproducen casos restantes. |
| R14 | Mute disponible durante trabajo pendiente; pruebas existentes enfocadas pasan. Falta prueba acústica/proveedor realmente suspendido. |
| R15–R17 | Borrador, rechazo de credenciales e identidad/generación presentes en fuente/tests; sin otro defecto confirmado. No equivalen a XCTest o UI ejecutados. |
| R18 | Adopción explícita añadida; S08 requiere integración controlada. |
| R19 | Arbitraje, retry y protección de token de pronunciación presentes; conversación mantiene S06. |

La fila `[x] F16` de `openspec/changes/mobile-review-followup/tasks.md:20` describe el cierre de trabajo en este entorno, pero **no demuestra cierre de aceptación multiplataforma**. Conviene separar estado de implementación, estado de revisión y estado de verificación. Esto no cambia la instrucción de implementar iOS sin bloquearse por Linux; evita presentar las pruebas pendientes como una validación concluida.

## 5. Comprobaciones realmente realizadas

| Comprobación | Resultado y límites |
|---|---|
| `make check` | Ejecutado en esta revisión: exit 0; 148 pruebas Python; exportación de schema actualizada, 152 sentencias y user_version 7; checkers de progreso, contenido Android y paridad pasaron. Log temporal `/var/tmp/mobile-review-check-2026-10-28.log`. |
| Migración Android | Sonda Java con SQLite JDBC y fixture real: reproduce S01. Copia de clases compiladas, no build limpio de HEAD; SHA256 de `LearningDbStore$Companion.class` coincidente: `2cb95e5d2b9afb84aadda0244030a08815c2ac9c8f1b273aedda08d0e9f7e2ae`. Fuente actual contrastada. Artefactos temporales `/var/tmp/android-review-probe.IeZt8t/`. |
| Voz Android | Dos sondas nuevas ejecutadas y fallidas por S03/S04; dos regresiones existentes de límite manual y mute/TTS pasan; otra del orden del saludo pasa en ejecución previa. SourceSet externo temporal `/tmp/voice-review.zkNLRo/`; no constituye test versionado ni prueba acústica. |
| Orden iOS | Sonda de comparación confirma inversión de tupla frente a cadena con delimitador; evidencia auxiliar, no ejecución Swift. |
| API y build completo Android | No se repitieron en esta revisión. Los 756 tests API y 830 app + 11 motor/lint/APK del verify-report son evidencia histórica de la implementación, no nueva ejecución aquí. |
| iOS/Swift/Xcode | No disponibles para esta revisión. Hallazgos iOS identificados por fuente; no se afirma fallo observado del compilador ni XCTest ejecutado. |
| UI conectada/audio físico/accesibilidad | No ejecutados. No hay prueba de montaje, rutas físicas, AEC, permisos reales ni continuidad acústica. |

Los directorios de sondas son evidencia temporal de diagnóstico y no una suite durable. Cada corrección futura debe incorporar la regresión correspondiente al repositorio antes de cambiar la implementación.

## 6. Orden de actuación recomendado

1. **Datos y bootstrap:** resolver S01 y S02; añadir pruebas del recorrido completo, no solo del helper.
2. **Voz manual:** resolver S03 y S04 en una misma revisión del estado de captura, conservando las pruebas previas de saludo, límite y mute.
3. **iOS fuente y pruebas:** corregir S05 y S06 sin esperar a disponer de macOS; dejar explícita la ejecución posterior.
4. **Proyección y lifecycle:** corregir S07 y reproducir o descartar S08 con scheduling controlado.
5. **Validación final:** ejecutar regresiones nuevas y suites disponibles; en macOS/dispositivo ejecutar las pruebas diferidas. Registrar por separado evidencia nueva, histórica y no disponible.

No se propone ninguna nueva política de fusión, atribución histórica o descarga recurrente del backend. No se han aplicado las acciones de este informe.

## 7. Guía detallada de resolución

Esta sección describe **cambios propuestos, todavía no implementados ni verificados**. Los nombres de helpers nuevos son orientativos. Las rutas de los apartados anteriores identifican la evidencia; los siguientes pasos definen cómo intervenir sin debilitar los contratos existentes.

### 7.1. S01 — Una sola confirmación de migración y recuperación de residuos anteriores

**Archivos principales:** `LearningDbStore.kt`, `ArchiveMapper.kt`, los DAO que utiliza la importación y `LearningDbStoreTest.kt`, bajo los directorios Android de datos y tests.

1. **Separar preparación de escritura.** Leer una sola vez la fuente seleccionada, decodificar y validar archivo/tickets y calcular su hash antes de modificar la BD. Preservar la precedencia actual del respaldo y no usar operaciones de lectura que cambien `.bak`/`.new`.
2. **Extraer variantes que trabajen dentro de la transacción del llamador.** La limpieza, importación y verificación no pueden abrir transacciones anidadas. Revisar las operaciones que hoy abren una transacción propia y disponer de una ruta interna `...InTransaction` cuando corresponda. No envolver simplemente el flujo actual en otro `transaction`: el contrato de `SqlDatabase` no es reentrante.
3. **Ejecutar en una única transacción:** comprobar propietario capturado, limpiar únicamente residuos cuya procedencia esté demostrada, importar contenido y tickets, verificar y escribir fecha/hash de migración. Cualquier excepción debe revertir todas esas escrituras, incluido el marcador. La serialización de apertura por propietario existente debe seguir abarcando esta operación.
4. **Verificar el ámbito importado, no toda la instalación.** Para sesiones, lecciones, expresiones y demás tablas compartidas, usar el mismo propietario que la importación; para metadatos, su namespace correspondiente. Si un elemento tiene identidad global, comprobar su colisión explícitamente. La presencia de datos válidos de B no debe hacer fallar el conteo de A ni autorizar borrarlos.
5. **Archivar la fuente solo después del commit.** Si el movimiento del archivo falla, la BD ya confirmada debe reconocerse por su hash en el siguiente arranque y no volver a importar. Conservar la comprobación de colisiones de destino: nunca sobrescribir otro respaldo para completar la limpieza.
6. **Tratar por separado las instalaciones ya afectadas.** La nueva atomicidad evita residuos futuros, pero no elimina los existentes. No asumir que todas las filas sin `migration_hash` son descartables. Solo reconciliar residuos si una procedencia persistida o una comparación inequívoca con la fuente prueba identidad y contenido; la igualdad de conteos no basta. Sin esa prueba, conservar BD y respaldo, mantener la cuarentena y ofrecer recuperación no destructiva, no una reasignación automática a B. Un nuevo registro pendiente, si se necesita, debe incluir propietario y hash de fuente y actualizarse transaccionalmente.

**Pruebas antes del cambio:** reproducir el fallo entre importación y metadatos; después exigir rollback completo. Añadir BD file-backed con datos legítimos de B, A→B durante reintento, cierre/reapertura y fallo al archivar tras commit. Verificar hashes, filas por propietario, marcador final y ausencia de duplicados. Añadir una fixture de residuos anteriores sin atribución suficiente que deba conservarse sin borrado automático.

**Condición de cierre:** un reintento válido termina sin depender de borrar otra cuenta; un caso ambiguo se preserva y se comunica como tal, nunca se presenta como recuperado.

### 7.2. S02 — Comparador estructurado y compatible con el servidor

**Archivos:** `apps/ios/App/ProgressClient.swift` y `apps/ios/AppTests/ProgressClientTests.swift`. El contrato de referencia es `services/api/src/progress.ts`; no hace falta cambiar la API para resolver este hallazgo.

1. Representar la identidad de fila mediante tres componentes independientes: `kind`, `itemID` y `language`. No formar una cadena delimitada para ordenar ni para detectar duplicados.
2. Implementar comparación lexicográfica por componente, en ese orden. En la descarga, reproducir la collation `C` del servidor: para texto UTF-8 válido, comparar bytes UTF-8 sin reglas regionales, plegado de mayúsculas ni normalización implícita. No sustituir el problema por una comparación dependiente del idioma.
3. Mantener una identidad/hash coherente con esa igualdad. Para valores de idioma ausentes, seguir la normalización permitida por el contrato; no inventar un valor que fusione identidades distintas. Revisar si el helper de subida puede reutilizarse; hacerlo solo si comparte exactamente las mismas reglas.
4. Recorrer cada par consecutivo y exigir orden estrictamente creciente. Esto rechaza tanto duplicados como páginas desordenadas sin depender del carácter `|`. Conservar el resto de validaciones de fechas, tamaño, `complete` y cursor.
5. No interpretar ni regenerar el cursor opaco del servidor usando la nueva clave. Ante rechazo de una página, no aplicar filas ni modificar el cursor confirmado de la página anterior.

**Regresiones:** páginas con `a`/`a-1`, caracteres delimitadores cuando estén autorizados por contrato, idiomas diferentes, duplicados exactos y orden invertido. Incluir los valores Unicode admitidos para fijar el orden de bytes y una continuación file-backed. Una página válida debe superar el decoder real, no solo el comparador aislado.

### 7.3. S03 — Separar capacidad del buffer inicial y estado del turno

**Archivos:** `LocalVoiceTransport.kt` y `LocalVoiceTransportTest.kt`.

1. Separar tres conceptos hoy acoplados: capacidad agotada del buffer de apertura, captura del turno cerrada a muestras nuevas y aviso ya emitido. Mostrar un aviso no debe alterar por sí mismo la posibilidad de consumir audio previamente aceptado.
2. Durante el saludo, añadir frames hasta el máximo establecido y marcar únicamente la saturación de entrada. Descartar muestras nuevas que excedan ese máximo, pero conservar orden, contenido y epoch de las ya admitidas.
3. Al finalizar el saludo, drenar una sola vez el buffer retenido hacia la detección/captura de voz. El bloqueo de entrada nueva no puede saltarse esos frames. Evitar mantener simultáneamente copias completas del mismo audio en dos buffers: el límite debe cubrir el total retenido.
4. Si el usuario pulsó Finalizar durante el saludo, conservar esa intención y aplicarla después de consumir el audio retenido; no terminar con `voicedInBuffer == 0` únicamente porque todavía no se había drenado el buffer. No crear petición antes de que termine el saludo ni por alcanzar el máximo.
5. En cancelación, cambio de epoch o fin de sesión, vaciar los buffers y la intención pendiente. En mute, mantener la política explícita de qué audio previo se conserva, sin reintroducir muestras de otra captura al desmutear.

**Regresión principal:** suspender el saludo mediante una barrera determinista, introducir muestras identificables hasta llenar el buffer, finalizar el saludo y solicitar fin manual. Afirmar una petición de saludo y una sola de respuesta, las muestras esperadas en STT y ningún autoenvío. Repetir con Finalizar antes del drenaje, cancelación y mute. No usar un `sleep` para suponer que terminó el saludo.

### 7.4. S04 — Aviso cuando realmente se descarta audio

**Archivos:** `LocalVoiceTransport.kt`, `LocalVoiceTransportTest.kt` y recursos Android EN/ES si cambia el mensaje visible.

**Resolución mínima recomendada:** mantener el límite técnico actual de STT y hacer visible el recorte al finalizar. No es necesario introducir segmentación de reconocimiento para corregir la pérdida silenciosa; segmentar sería un cambio mayor que exige tratar orden, resultados parciales y errores entre segmentos.

1. Centralizar el cálculo del máximo de muestras aceptadas por STT; evitar constantes divergentes entre captura, recorte y aviso.
2. Tras el recorte de silencio y antes de llamar al reconocedor, comparar la longitud real con ese máximo. Si se descartan muestras con posible habla, emitir una señal de recorte visible aunque la captura no haya llegado al límite de 20 segundos.
3. Separar el evento de comunicación del bloqueo de captura de S03. La señal debe informar, no provocar el descarte del prefijo válido que todavía debe reconocerse.
4. Deduplicar el aviso por intento/turno. El mensaje debe indicar que se conserva solo el inicio, no prometer que se conserva todo. Mantenerlo el tiempo suficiente para que el usuario pueda comprenderlo y repetir una frase más corta; no limitarlo a un log.
5. Conservar el fin manual y la petición única. No reintentar automáticamente el mismo audio ni añadir una transcripción inventada para el tramo perdido.

**Pruebas:** 18 segundos exactos sin recorte, una muestra por encima del límite y 19,2 segundos con marcador final. Comprobar muestras entregadas, evento y mensaje EN/ES, ausencia de duplicados, cancelación y que el aviso no impide reconocer el prefijo. Si se elige reducir también la captura a 18 segundos, probar la nueva política de límite con los mismos casos; nunca subir el límite de STT sin comprobar el contrato del motor.

### 7.5. S05 — La prueba debe recorrer la asignación de propietario real

**Archivos:** `apps/ios/AppTests/ProgressClientTests.swift`; solo si mejora la reutilización, el adaptador de progreso de `ConversationCoordinator.swift`.

1. Mantener la guarda de propietario del writer: no debilitarla para que el test pase ni rellenar propietarios arbitrarios en el decoder HTTP.
2. Tras recibir cada página, transformar sus filas al propietario autenticado capturado, tal como hace producción. Si se extrae un helper compartido, recibir el propietario como argumento explícito y comprobar que la operación sigue vigente antes de persistir.
3. Aplicar esa transformación en las dos persistencias del test. La segunda página debe utilizar la misma cuenta que el estado reabierto, no una cuenta obtenida tardíamente de un estado global.
4. Después de la primera escritura, comprobar que hay exactamente 1.000 filas del propietario esperado, ninguna de otro propietario y el cursor previsto. Cerrar realmente la conexión y abrir la misma BD en disco.
5. Presentar la página inválida y comprobar igualdad de filas y cursor con el estado anterior. Continuar con la página válida, comprobar las 1.001 identidades, `cursor == nil` y generación final. Añadir por separado que un propietario incorrecto siga siendo rechazado sin mutación.

**Comprobación de confianza:** ejecutar el test completo en AppTests sobre macOS/Xcode. Escribir y revisar esta corrección en Linux es válido; mientras falte el runner, registrarla como implementada en fuente, no como XCTest aprobado.

### 7.6. S06 — Recuperación simétrica para cualquier propietario del audio

**Archivos:** `apps/ios/App/LiveTransport.swift`, `PronunciationCheckController.swift` —coordinador de leases— y tests de propiedad/limpieza de audio en AppTests.

1. Capturar al adquirir el lease de conversación su token y la configuración que habrá que restaurar. No depender de leer después campos mutables de otro intento de conexión.
2. Al desconectar, consultar propiedad antes de desactivar o restaurar el audio. Si el token ya es antiguo, limpiar únicamente referencias locales; no tocar la sesión compartida.
3. Extraer una operación de limpieza síncrona en el actor/cola adecuados. Debe adquirir y liberar el lock de `RTCAudioSession` también al lanzar una excepción, desactivar y restaurar configuración, y devolver éxito solo si ambas fases terminan correctamente.
4. Si falla, conservar el token y registrar en `AudioSessionLeaseOwnership` una closure de recuperación con token/configuración capturados. La closure no debe depender de que siga vivo `LiveTransport` ni capturarlo fuertemente formando un ciclo. Puede conservar los recursos mínimos necesarios para limpiar.
5. El siguiente `acquire`, sea de conversación o pronunciación, debe intentar esa limpieza. Si vuelve a fallar, responder ocupado sin conceder otro token; si tiene éxito, liberar solo el token esperado y conceder el nuevo. Evitar callbacks o `await` dentro del tramo crítico que permitan cambiar de propietario entre comprobación y limpieza.
6. Una llamada tardía de `disconnect()` del transporte original debe reconocer que ya no es propietario y no desactivar la nueva pronunciación. Limpiar el callback retenido cuando su token se libere correctamente.

**Pruebas con audio inyectado:** fallar desactivación y restauración por separado; soltar el transporte original; adquirir desde pronunciación con primer retry fallido y segundo exitoso; comprobar locks equilibrados, una sola restauración exitosa y que el token antiguo no toca al nuevo propietario. El test no debe necesitar WebRTC ni hardware real para validar la máquina de estados. Rutas físicas, TTS e interrupciones quedan como comprobación adicional de dispositivo.

### 7.7. S07 — Proyección observable de progreso para Aprender

**Archivos:** `apps/android/app/src/main/java/com/jkdc/app/ui/LearnScreen.kt`, `AppViewModel.kt` y pruebas Compose del flujo de aprendizaje.

1. Identificar las lecturas que alimentan marcas de lección, unidades y nivel; todas deben utilizar la misma versión de la proyección de progreso.
2. Exponer una revisión observable y la identidad efectivamente cargada —no únicamente la identidad solicitada— junto con el idioma. La revisión debe avanzar tras confirmar una página o escritura que modifique progreso; no antes de un commit que pueda fallar.
3. Sustituir el `remember` sin claves. Si los cálculos son puros y baratos, usar `remember(loadedIdentity, languageID, ledgerRevision)`; si consultan BD o son costosos, construir una proyección inmutable fuera de Compose y publicarla como estado. No trasladar IO al hilo de composición.
4. Durante cambio A→B, vaciar la proyección visible o mostrar carga hasta que los datos de B estén listos. No conservar las marcas de A mientras el identificador ya muestra B. Cercar también cualquier cálculo asíncrono que termine tarde.
5. Verificar que no queda otro indicador leyendo un conjunto cacheado distinto. Una recomposición debe presentar una versión coherente de unidades, lecciones y nivel.

**Pruebas:** montar Aprender antes de aceptar una página; confirmar progreso sin navegar y observar cambio de marcas. Cambiar idioma y usuario con cargas suspendidas, completar tarde la carga de A y afirmar que no sustituye a B. Repetir error/reintento y página rechazada, donde la proyección no debe fingir progreso nuevo.

### 7.8. S08 — Primero demostrar el orden; después unificar la transición si procede

**Archivos a investigar:** `apps/ios/App/ConversationCoordinator.swift`, `RootView.swift` y AppTests del ciclo de sesión/sincronización.

1. Introducir un límite de scheduling inyectable o una cola controlada para el callback de notificación; no usar esperas temporales para intentar producir la carrera.
2. Ejecutar ambos órdenes: notificación→adopción y adopción→notificación encolada. Mantener suspendidos carga/GET con barreras y contar invalidaciones, operaciones iniciadas y páginas aplicadas. Cubrir invitado→A, A→B y actualización de perfil/credencial de A.
3. Si se reproduce, hacer que una única operación idempotente gobierne la transición: detectar cambio relevante, invalidar el trabajo anterior, adoptar/cargar propietario y arrancar una sincronización para la identidad vigente. Ambos puntos de entrada pueden invocarla, pero una señal repetida de la misma transición no debe cancelar la operación que acaba de iniciar el otro.
4. Distinguir identidad de cuenta de generación de credencial: un cambio de perfil no debe reiniciar bootstrap; una credencial rechazada o sustituida sí debe impedir que continúe el bearer antiguo. No resolver la carrera ignorando todas las notificaciones de la misma cuenta.
5. Comprobar de nuevo identidad/generación antes de publicar o persistir cada respuesta. Una señal antigua no debe forzar otro bootstrap ni adoptar una cuenta que ya no está activa.

**Cierre de la investigación:** si el orden no es alcanzable por garantías reales del framework y del código, documentar esas garantías con una prueba que pase por los puntos de entrada productivos. No implementar una reestructuración especulativa ni marcar el riesgo como reproducido sin esa evidencia.

### 7.9. Entrega, pruebas y documentación por corrección

- Convertir estas recetas en especificación/tareas SDD antes de una implementación significativa; conservar los IDs S01–S08 para relacionar requisito, test, cambio y resultado.
- Añadir primero la regresión que falle por el comportamiento descrito. Corregir lo mínimo, ejecutar el test enfocado y la suite pertinente; las sondas temporales deben convertirse en pruebas versionadas.
- En Android, terminar con tests de app/motor, lint y build; en contratos compartidos, `make check`; si cambia backend, pruebas con PostgreSQL aislado. No modificar la API para esconder un fallo de decoder móvil.
- Implementar fuente y pruebas iOS sin detenerse por Linux. Registrar por separado revisión estática y ejecución futura de Swift/AppTests/UI en macOS; nunca inventar un resultado RED/GREEN.
- Actualizar el verify-report con comandos, resultados y límites concretos. Separar «implementado», «revisado» y «verificado en plataforma/dispositivo»; no cerrar la aceptación por el número total de pruebas verdes.
- Mantener commits convencionales por unidad cuando se autorice la implementación, sin publicar, fusionar ni borrar datos como parte implícita de estas correcciones.
