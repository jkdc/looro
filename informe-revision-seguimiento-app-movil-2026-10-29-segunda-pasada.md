# Segunda revisión completa del seguimiento móvil

**Fecha:** 29 de octubre de 2026.  
**Rama / HEAD:** `feature/learning-platform` / `5bfda6015fd6f58c13481b0285d0baad334d2ceb`.  
**Referencia anterior:** [informe del 29/10, N01–N06](informe-revision-seguimiento-app-movil-2026-10-29.md), junto con los informes del [28/10](informe-revision-seguimiento-app-movil-2026-10-28.md) y [27/10](informe-revision-implementacion-app-movil-2026-10-27.md).

## 1. Resultado ejecutivo

**Se identifican siete problemas: tres P1 y cuatro P2.** Las correcciones N01–N06 están presentes, pero la nueva protección de procedencia puede saltarse durante el backfill, el cerco Android vuelve a normalizar una identidad ya resuelta y el cambio iOS introduce una declaración de visibilidad incompatible con Swift. La revisión ampliada identifica además errores en activación fallida de cuenta, pronunciación tardía, caché por idioma y concurrencia de locuciones.

| ID | Prioridad | Problema | Evidencia |
|---|---|---|---|
| V01 | P1 | La atribución histórica previa al clasificador permite borrar datos ausentes del respaldo | Android reproducido en harness SQLite; equivalente iOS estático |
| V02 | P1 | El helper de migración iOS expone un tipo menos accesible que la función | Declaración incompatible por análisis estático; compilador no ejecutado |
| V03 | P1 | Si falla activar B en iOS, una escritura posterior de B puede reemplazar datos de A | Ruta Swift estática y reproducción SQL auxiliar |
| V04 | P2 | El doble prefijo `member:` oculta permanentemente rollups recuperados en Android | Composición de helpers actuales ejecutada + caller inspeccionado |
| V05 | P2 | La tarjeta de examen conserva el resultado del idioma anterior | Ruta Compose estática; coincide con observación previa del SDD |
| V06 | P2 | Pronunciación Android puede guardar una finalización de A bajo B | Intercalación identificada por fuente; sin ejecución montada |
| V07 | P2 | Una respuesta escrita que interrumpe el saludo descarta voz manual ya retenida | Sonda JVM de transporte reproducida |

P1/P2 indican prioridad de corrección, no CVSS. No se demuestra una incidencia real en producción ni un bypass de autorización del backend. Los fallos que necesitan condiciones excepcionales se describen con esas condiciones, no como fallos inevitables de todos los usuarios.

**Esta revisión solo crea el informe. No aplica correcciones, no cambia de rama y no realiza commits.** La falta de Swift/Xcode no bloquea implementar las correcciones iOS: únicamente deja pendiente su verificación ejecutada.

## 2. Alcance y método

- Cuatro revisiones independientes y acotadas: datos/proyecciones Android; almacenamiento/sesión/sync iOS; transporte de voz Android; contratos y gates portables.
- Comparación de `335f030..5bfda60` para N01–N06 y lectura de rutas relacionadas conservadas de las fases anteriores. Se revisaron los SDD `mobile-review-followup`, `mobile-review-seguimiento-2026-10-28` y `mobile-review-seguimiento-2026-10-29`.
- Incluye migración, atribución y cambio de cuenta, bootstrap local-first, fechas/cursores, recuperación, publicación de progreso, exámenes por idioma, credenciales y cancelación/audio. No es una auditoría de funcionalidades ajenas a los informes móviles ni una garantía de ausencia de defectos.
- CodeGraph sincronizado antes de explorar; referencias principales contrastadas en fuente. Los resultados verdes no se consideran sustitutos de la cobertura de cada escenario.
- Sondas fuera de archivos versionados. Sin nuevas llamadas a proveedores, cuentas reales, pruebas acústicas, despliegues, push ni merge. Se preservan los informes anteriores y `.playwright-mcp/`.

## 3. Hallazgos y resolución detallada

### V01 — El backfill borra la evidencia antes del clasificador de procedencia

**P1 · Android e iOS · Residual de N01/N02.**

**Fuente Android:** `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbStore.kt:278–280,352–382,413–445`; `LearningDbDao.kt:88–99` en el mismo directorio. `open` activa la identidad antes de migrar. Si falta `learner_owner_backfill_v1`, esa activación atribuye filas con propietario vacío al usuario actual y mueve mastery a su namespace. El clasificador posterior observa `OWN`, no el contenido histórico ambiguo original.

**Equivalente iOS:** `apps/ios/Core/Database/LearningDbStore.swift:259–261,358–376` y `LearningDatabaseDao.swift:42–67`. La activación también precede al clasificador. En iOS el clasificador rechaza cualquier mastery existente; por ello, la variante equivalente que puede alcanzar `.own` debe usar filas históricas sin mastery, no copiar sin más toda la fixture Android.

**Escenario reproducido en Android:** actualización desde una BD anterior a ownership, sin backfill/hash/claim. Existe una completion legítima con `owner_key=''` —válido en el esquema— y mastery histórico que no figuran en el JSON. Al abrir como B, primero se atribuyen a B; después la importación los considera residuos reemplazables y los elimina.

```text
before empty-owner rows=1 backfill=null
source marked migrated
quarantined=false sessions=3
legitimate row remaining=0
historical mastery=null owned=null
backfill=1 hash=1d8d68ca...
```

La asignación histórica inicial ya estaba autorizada. **No se reporta esa atribución como error:** el error es usar una atribución sintética como prueba de que puede eliminarse contenido ausente de la fuente. Las pruebas nuevas siembran después de activar propietarios y no cubren esta entrada de actualización.

**Cómo resolverlo:**

1. Inspeccionar el estado original y resolver la procedencia antes de ejecutar backfill, cambiar namespaces o limpiar contenido. Separar selección de identidad de adopción histórica cuando ambas operaciones hoy están unidas.
2. Con respaldo pendiente y datos sin linaje suficiente, preservar BD y fuente y comunicar cuarentena. La decisión debe tomarse sin dejar persistidos cambios que oculten la ambigüedad al siguiente intento.
3. Conservar claims, transacción única y conteos por propietario para migraciones nuevas. La pertenencia actual a una cuenta no demuestra por sí sola que todas sus filas provengan del respaldo.
4. Si se autoriza una reconciliación histórica explícita, operar solo sobre datos de procedencia demostrada; no reemplazar el conjunto de la cuenta para hacer coincidir conteos.

**Regresión requerida:** fixture pre-owner con propietario vacío, sin marcadores y datos distintos del respaldo; abrir mediante la API pública del store, comprobar preservación de filas, mastery, bytes y metadatos, y repetir tras reabrir. En iOS cubrir la variante sin mastery que alcanza `.own` y la preservación cuando mastery provoca rechazo. Mantener los tests actuales de BD fresca, claim ajeno y rollback.

### V02 — La nueva declaración de migración iOS no respeta los niveles de acceso

**P1 · iOS · Regresión de N02 · Evidencia estática, no compilación ejecutada.**

**Fuente:** `apps/ios/Core/Database/LearningDbStore.swift:292,358–359`. `LegacyResidue` se declara `fileprivate`, pero `static func legacyResidueScope(...) -> LegacyResidue` tiene acceso `internal` implícito. Swift no permite que una función exponga un resultado menos accesible que ella.

**Impacto:** es una incompatibilidad de compilación del Core, no un error condicionado a entrar en la migración. No se presenta un mensaje de compilador observado: Swift no está instalado aquí.

**Cómo resolverlo:** declarar el helper `private`, dado que su uso está dentro del store; utilizar `fileprivate` únicamente si hay un consumidor real en el mismo fichero. No ampliar artificialmente la API del enum para evitar el problema. Comprobar que no existan otras firmas que expongan ese tipo.

**Verificación requerida:** revisión estática inmediata de accesibilidad; compilar LearningCore y ejecutar `LearningDbStoreTests` en el entorno compatible cuando esté disponible. La corrección de fuente no necesita esperar a ese entorno.

### V03 — Una activación iOS fallida deja identidad visible y propietario persistido diferentes

**P1 · iOS · Defecto del flujo relacionado preexistente, no regresión demostrada de N04.**

**Fuente:** `apps/ios/App/Storage.swift:183–196` publica B, avanza generación y vacía el archivo antes de confirmar `LearnerOwnershipDao.activate`. Su catch solo publica un error. Solicitar B otra vez retorna por igualdad (`:185`), aunque B no llegó a cargarse. `recordCompletion` (`:439–446`) y `write` (`:999–1003`) no comprueban ese estado fallido. `apps/ios/Core/Database/LearningDatabaseDao.swift:705–721` reemplaza completions según el owner persistido; el trigger de `shared/db/schema.sql:753–755` atribuye la nueva fila a ese owner.

**Secuencia:** A activo → cambio a B → activación falla por bloqueo SQLite temporal → se desestima el aviso → desaparece el bloqueo → B completa una actividad. La BD sigue activa como A. La sustitución del ledger vacío de B elimina marcas de A y guarda la marca nueva como A.

**Sonda SQL auxiliar ejecutada**, con esquema versionado, BD file-backed y segunda conexión con `BEGIN IMMEDIATE`:

```text
activation: database is locked
visible identity: member:B database identity: member:A
persisted completions: [('member:A', 'b-new')]
```

Esta sonda verifica el bloqueo y las consecuencias del SQL; **no ejecuta el store ni la UI Swift**. La pérdida de la marca legítima de A es un resultado de datos de prueba.

**Cómo resolverlo:**

1. Separar identidad solicitada, identidad cargada y estado listo/fallido. Invalidar la proyección anterior sin afirmar que B está listo.
2. Confirmar activación y carga de B antes de publicar disponibilidad. Si falla cualquier fase, bloquear mutaciones y sync dependiente de esa carga; mostrar reintento persistente.
3. Cercar todas las escrituras con la identidad/generación capturada y validar su correspondencia con el propietario persistido. Revisar también preferencias, exámenes, usage y writers directos, no solo completions.
4. El early-return por igualdad debe exigir una carga satisfactoria. Reintentar B después del error tiene que volver a activar/cargar B.

**Regresión requerida:** adaptador/BD que falle una activación; intentar escribir durante el error y comprobar A intacto y ausencia de marcas cruzadas; reintentar B, completar y reabrir A/B. Añadir el caso en que la activación tiene éxito pero falla la carga posterior. Sin sleeps ni fallos de disco reales.

### V04 — El cerco N03 añade `member:` a una identidad ya resuelta

**P2 · Android · Regresión de N03.**

**Fuente:** `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt:458–462` guarda `member:account-a` en `examOwnerIdentity.value.ownerAccountID`. `:854–855` vuelve a pasarlo a `LocalLearnerIdentity.resolve`; `core/LocalLearnerIdentity.kt:8–12` antepone otro `member:`. El gate de `AppViewModel.kt:861–864` nunca coincide con el propietario correcto.

**Composición ejecutada con helpers actuales:**

```text
setExamOwner=member:account-a
remoteRequested=member:member:account-a
gate=false
```

Con carga y propietario SQLite correctos, los rollups descargados siguen excluidos de la proyección de progreso/nivel. Es especialmente visible después del bootstrap en un dispositivo nuevo. No demuestra pérdida de las filas descargadas ni exige cambiar la política de sincronización.

**Cómo resolverlo:** usar directamente la identidad ya resuelta del estado solicitado y conservar la comparación solicitado/cargado/consultado. No convertir `resolve` en un normalizador permisivo para ocultar el contrato incorrecto del caller. Distinguir accountID sin namespace de identidad local resuelta en nombres o tipos cuando resulte proporcionado.

**Regresión requerida:** probar la composición de producción con cuenta miembro, carga finalizada y rollup remoto conocido: debe mostrarse al terminar la carga, ocultarse durante A→B y rechazar respuestas tardías. El test que recibe tres strings iguales solo prueba el helper, no esta integración. Mantener también el caso invitado.

### V05 — El caché de la tarjeta de examen omite el idioma

**P2 · Android · Hallazgo ampliado; observación previa ya anotada en el SDD.**

**Fuente:** `apps/android/app/src/main/java/com/jkdc/app/ui/LearnScreen.kt:211–229` usa `remember(selectedLevel, attemptHistoryIdentity, attemptHistoryRefresh)` sin idioma. `AppViewModel.kt:402–405` sí filtra por idioma, pero esa lambda no se reejecuta. `selectLanguage` (`:2095–2099`) no cambia las otras claves. La lista de intentos sí se recarga por idioma (`LearnScreen.kt:128–132`).

**Escenario:** mismo usuario y nivel A1, resultado solo EN, cambio EN→ES manteniendo la tarjeta compuesta. La tarjeta reutiliza la puntuación EN mientras la lista ES está vacía; puede aparecer el aviso «summary only». El análisis coincide con la observación del recorrido anterior documentada en `mobile-review-seguimiento-2026-10-29/tasks.md`; **no se ha repetido el emulador en esta pasada**.

**Cómo resolverlo:** incluir `vm.language.id` entre las claves o eliminar este caché derivado barato. Mantener el filtro correcto existente. No alterar la atribución ni borrar intentos para corregir un error de presentación.

**Regresión requerida:** test Compose con resultado solo EN y mismo A1: EN→ES no muestra puntuación EN; ES→EN la recupera. Repetir con resultados propios en ambos idiomas y comprobar coherencia de tarjeta, lista y aviso.

### V06 — Pronunciación puede registrar en B un resultado iniciado por A

**P2 · Android · Ruta asíncrona identificada estáticamente.**

**Fuente:** entrada UI en `LearnScreen.kt:596`; `AppViewModel.kt:820–847` no captura owner/generación/idioma antes de suspender la transcripción. `setExamOwner` (`:455–497`) no cancela este trabajo. Al regresar, `:843` llama a `recordCompletion` (`:798–806`), que usa archivo e idioma actuales; `persist` captura la identidad en el momento de encolar (`:1623–1624`), no al iniciar la práctica.

**Secuencia:** A inicia pronunciación → STT queda pendiente → cambio y carga de B → STT devuelve aprobado → se publica el resultado y se añade la completion al archivo de B, encolada como B. El bloqueo `isRunning` se refiere a conversación y no protege esta operación. Un cambio de idioma puede etiquetar de manera equivalente la finalización con el idioma nuevo.

No se ejecutó esta intercalación con micrófono, proveedor o UI; no se afirma que se haya observado en un dispositivo.

**Cómo resolverlo:**

1. Capturar identidad, generación, idioma y contexto de la lección al iniciar. Retener un Job cancelable para la práctica.
2. Invalidar/cancelar al cambiar cuenta o contexto; cercar también resultado visible, avisos y persistencia con la captura original. La cancelación por sí sola no evita una respuesta que ya compite con ella.
3. No transformar `CancellationException` en un error de usuario mediante `runCatching`. Liberar recorder/recursos en `finally`, incluso si se cancela durante la captura.
4. Mantener el camino estable: un aprobado del mismo contexto registra exactamente una completion, con fecha del dispositivo y usuario capturado.

**Regresión requerida:** STT/recorder inyectados con barrera, iniciar A, cargar B y liberar éxito A; archivo, BD, outbox y resultado de B no cambian. Cubrir idioma, cancelación, fallo y éxito estable sin reloj real ni proveedor.

### V07 — Otra locución descarta el prefijo oral retenido durante el saludo

**P2 · Android · Reproducido en JVM; distinto del mute corregido por N05.**

**Fuente:** `apps/android/app/src/main/java/com/jkdc/app/core/voice/LocalVoiceTransport.kt:408–409` drena `openingFrames` al terminar la apertura, pero `:479–481` descarta frames si continúa `speaking`. Una respuesta escrita puede iniciar otra locución (`:852–858`, recorrido `AppViewModel.kt:3057–3095`); `apps/android/core/engine-local/src/main/java/com/jkdc/app/engines/local/LocalTtsEngine.kt:130` utiliza `QUEUE_FLUSH` para interrumpir la anterior.

**Intercalación reproducida:** generación del saludo suspendida → diez frames identificables de voz manual → Finalizar → comienza TTS del saludo → llega respuesta escrita y su TTS interrumpe el saludo → termina la coroutine de apertura mientras esa segunda locución sigue activa → se extrae y descarta el prefijo → termina la segunda locución, sin procesar la intervención oral.

La sonda modela explícitamente la liberación de la primera locución por `QUEUE_FLUSH`, manteniendo pendiente la segunda. Falla en la aserción final: **dos peticiones al motor esperadas, una observada**. No falla una precondición ni se usa una ráfaga artificial para provocar overflow. No es una prueba acústica ni de UI montada.

**Cómo resolverlo:**

1. No consumir el prefijo admitido mientras otra locución mantenga cerrada su ventana de procesamiento. Esperar un cambio de estado/señal, sin busy-loop.
2. Diferenciar audio válido retenido antes del TTS de audio nuevo capturado durante TTS. Seguir excluyendo el segundo para evitar eco, sin descartar el primero.
3. Mantener orden, epoch, límite e intención de Finalizar hasta procesar o cancelar explícitamente ese prefijo. Mute/cancelación deben seguir invalidándolo cuando corresponda.

**Regresión requerida:** versionar la intercalación de la sonda; comprobar muestras STT, una única respuesta oral y ausencia de eco/reenvío. Cubrir orden inverso de finalización de locuciones, mute y cancelación. Conservar los 52 tests existentes, incluidos los nuevos de N05.

## 4. Balance de las correcciones anteriores

| Hallazgo previo | Estado observado en esta pasada |
|---|---|
| N01 | El caso mixto A+B ya atribuido se rechaza; permanece V01 en la entrada histórica anterior al backfill. |
| N02 | Transacción única, mapper transaction-aware, counts por owner y marca postcommit presentes. V01 y V02 impiden dar el recorrido por cerrado. Swift no ejecutado. |
| N03 | Cerco de transición presente, pero V04 rompe la proyección estable de miembros. |
| N04 | Invalidación previa y programación del sustituto presentes. No apareció otra pérdida concreta de admisión en esa secuencia. Falta ejecución de scheduling por las entradas reales; V03 es un fallo distinto de activación local. |
| N05 | Limpieza de prefijo/contador/saturación por epoch presente; sus dos escenarios pasan entre los 52 tests de voz ejecutados. V07 combina otra locución con el drenaje del prefijo. |
| N06 | Marca durable tras DELETE exitoso y antes de anuncio/eliminación presente; carga independiente la consulta. XCTest añadido, no ejecutado aquí. |
| R02–R03/R05–R10 y S02/S05 | Contratos v1/v2, fechas, keyset por componentes, cursores, intención durable y commits por página revisados sin otro defecto confirmado en ese alcance. No se eleva un caso fuera del dominio público de contrato. |
| R15–R19 y S06 | Revisados borrador escrito, callbacks cercados de conversación, rechazo de credencial y limpieza global de audio; sin otro defecto concreto en esos parches. V06 afecta el recorrido independiente de pronunciación. |

Este balance no equivale a una prueba ejecutada completa de cada flujo. Las limitaciones de cuentas deshabilitadas en el stack local y de audio físico no se convierten en una aprobación implícita.

## 5. Comprobaciones y límites de la evidencia

| Comprobación de esta pasada | Resultado observado |
|---|---|
| `make check` | Exit 0; 148 tests Python y exportadores/paridad/esquema correctos. 38 diagnósticos de conexión SQLite sin cerrar, con 76 líneas que contienen `ResourceWarning` al contar las indicaciones auxiliares. |
| `make test-api` | Exit 0; 756 aprobados, cero fallos/cancelados/omitidos/todo; 471,513 s de tests. Retirada de contenedores, red y volumen smoke comprobada. |
| `JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/var/tmp' make android-build` | Exit 0, 7m52s; APK generado; lint 0 errores, 73 warnings y 22 hints. Tests app 839/motor 11 reutilizados como `UP-TO-DATE`: **no son una nueva ejecución completa**. |
| Voz enfocada con sonda externa | Ejecución fresca de los 52 tests versionados: todos pasan. Una sonda adicional ejecutada y fallida por V07; por ello el comando que incluye la sonda no está verde. |
| Identidad y migración Android | Sonda con cuatro fuentes actuales recompiladas en override scratch y dependencias existentes: reproduce V01 y V04. No equivale a build limpio integral. El intento inicial con NULL violaba NOT NULL y se descartó; el resultado definitivo usa propietario vacío válido. |
| iOS | Revisión estática y sonda SQL auxiliar de V03. No hay Swift instalado: no se ejecutaron compilación, XCTest, simulador ni UI. |
| Dispositivo / proveedores | Sin nueva pasada conectada, prueba acústica ni llamada a proveedor real. El dato histórico de V05 se identifica expresamente como anterior. |

**Trazabilidad temporal** — archivos scratch no versionados, no sustituyen regresiones permanentes:

- Datos: `/var/tmp/mobile-round4-data.024leX/IdentityAndBackfillProbe.java`, `probe-final.log`, `compile.log`. Recompilados `LearningDbStore.kt`, `ArchiveMapper.kt`, `ProgressProjection.kt` y `LocalLearnerIdentity.kt`; DAO dependiente sin cambios respecto a `335f030`.
- Activación iOS auxiliar: `/tmp/ios-owner-switch-tgtrnxax/learning.db`.
- Voz: `/var/tmp/round4-voice-probe/src/Round4VoiceProbeTest.kt:1787–1847`, fallo en `:1841`; `probe-result.xml`, `run.log` e `init.gradle` del mismo directorio. El init añade únicamente la sonda externa al sourceSet de tests. Los XML actuales de Gradle pertenecen a esta ejecución enfocada.
- Gates: `/tmp/looro-round4-check.log`, `/tmp/looro-round4-api.log`, `/var/tmp/round4-android-build.log`.
- Contraste parental: `make check` repetido y aprobado (148 tests; `/tmp/looro-round4-parent-check.log`); contrastados el resultado XML fallido de V07, la salida de las sondas V01/V04 y las referencias principales de fuente. Lectura estructural del informe: siete hallazgos, sin cambios en archivos versionados.

## 6. Orden de acción recomendado

1. **Proteger datos y compilación:** V01, V02 y V03. Añadir las fixtures históricas y el fallo transitorio de activación antes de modificar comportamiento.
2. **Restaurar proyecciones y atribución:** V04 y V06, conservando la separación de cuentas y el origen local de fechas/resultados.
3. **Corregir presentación y audio:** V05 y V07 con pruebas montadas/de transporte correspondientes, sin debilitar anti-eco, mute ni límites.
4. Versionar las regresiones útiles de las sondas, repetir gates aplicables y registrar por separado fuente implementada, revisión estática y ejecución en plataforma. La ejecución iOS puede quedar al final; sus correcciones no necesitan esperar a macOS.

No se propone fusionar invitado con miembro, descargar periódicamente progreso del BE, inferir fechas/orígenes históricos ni limpiar cuentas para superar una migración. La premisa local-first y la atribución a la sesión vigente se mantienen.
