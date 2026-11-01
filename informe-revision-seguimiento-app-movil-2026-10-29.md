# Revisión final de la rama: seguimiento móvil

**Fecha:** 29 de octubre de 2026.

**Estado revisado:** rama `feature/learning-platform`, HEAD `335f030c1a1712e7b068f710a80447057f2d6842`.

**Referencias:** [seguimiento del 28/10](informe-revision-seguimiento-app-movil-2026-10-28.md), incluida su guía detallada S01–S08; [revisión de implementación del 27/10](informe-revision-implementacion-app-movil-2026-10-27.md), R01–R19; cambios SDD `mobile-review-followup` y `mobile-review-seguimiento-2026-10-28`.

**Alcance:** última pasada sobre las rutas relacionadas con estos documentos en el código actual, no solo sobre el último commit. Solo se genera este informe: no se corrige código ni se hacen commits.

## 1. Resultado ejecutivo

**Quedan seis hallazgos que impiden considerar demostrada toda la aceptación funcional.** Las correcciones recientes sí resuelven varios defectos anteriores, pero no cubren todos los estados históricos ni todas las interacciones entre cuenta, carga y audio.

Las comprobaciones generales pasan: contratos/Python, API con PostgreSQL aislado, tests versionados Android, lint y APK. Esto no contradice los hallazgos: hay escenarios adicionales no cubiertos por esas suites. Dos de ellos se reprodujeron con sondas; los restantes se identificaron por trazado concreto del código y conservan las reservas indicadas.

| ID | Prioridad | Problema pendiente | Evidencia | Relación |
|---|---|---|---|---|
| N01 | P1 | Android puede borrar datos legítimos de B y asignarle el respaldo de A al actualizar desde una migración antigua fallida | Sonda SQLite con los dos ficheros actuales de migración recompilados; no build limpio completo | S01 / R01 |
| N02 | P1 | iOS mantiene importación y confirmación separadas, limpieza por cuenta y conteos globales | Fuente + sonda SQL auxiliar; sin ejecución Swift | R01; misma clase que S01 |
| N03 | P2 | Durante la carga de B, Aprender puede consultar los rollups de A que siguen activos en BD | Ruta estática; UI montada no reproducida | S07 / R04 |
| N04 | P2 | El parche iOS de notificación tardía no inicia B si A todavía ocupa la operación de sync | Transición estática; orden iOS no ejecutado | S08 / R18 |
| N05 | P2 | Mute/unmute con el buffer del saludo lleno impide capturar la frase posterior | Sonda Kotlin reproducida | S03 / R11–R14 |
| N06 | P2 | Tras borrar la cuenta, un fallo de eliminación del Keychain permite recargar la credencial eliminada | Ruta estática condicionada al fallo local | Extensión relacionada con R16 |

P1/P2 son prioridades de trabajo, no CVSS. Las sondas usan datos de prueba. No se ha demostrado una incidencia en producción, una autorización remota indebida ni exposición visible entre cuentas en un dispositivo real.

La imposibilidad de ejecutar iOS en Linux sigue siendo **una limitación de verificación, no una condición para detener su implementación**.

## 2. Método y cobertura

- Tres revisiones independientes: datos/sincronización Android, voz Android e iOS. Se contrastaron requisitos, cambios, rutas actuales y pruebas; los checkboxes del SDD no se tomaron como prueba suficiente.
- Comparación del trabajo reciente desde `b963eda`, incluyendo `dbeca32`, `f464190`, `505c963`, `c8cee4e`, `3012f8e` y `d45100d`. También se inspeccionaron rutas conservadas de las fases anteriores cuando intervienen en los mismos flujos.
- CodeGraph se sincronizó antes de la exploración estructural; se hicieron lecturas acotadas y comprobaciones parentales de las referencias principales.
- Sondas fuera de archivos versionados; no se modificaron fuentes para hacer pasar pruebas. Las limitaciones de los harness se detallan en la sección 5.
- Se preservaron `.playwright-mcp/` y los informes anteriores no versionados. No se hicieron llamadas LLM reales, despliegues, cambios de cuentas reales, push ni merge.

## 3. Hallazgos y resolución detallada

### N01 — La nueva atomicidad no recupera con seguridad las migraciones antiguas sin marcador

**P1 · Android · Reproducido en harness SQLite · Incumplimiento residual de S01, sección 7.1.6 del informe anterior.**

**Fuente:** `apps/android/app/src/main/java/com/jkdc/app/data/LearningDbStore.kt:342–358,393–399`. Sin `migration_pending_owner`, la siguiente cuenta reclama el respaldo; la transacción limpia sus tablas e importa la fuente. El marcador nuevo protege fallos futuros, pero no prueba quién era propietario de una importación parcial creada por la versión anterior.

**Escenario:** B tiene una marca legítima `b-legitimate`. La versión anterior importa el archivo como A y termina antes de escribir `migration_hash`, dejando filas de A sin claim pendiente. Se cierra la BD y el código actual la abre como B.

```text
old crash state hash=null pending=null
B archives the source
B load visible sessions=3
B legitimate completion remaining=0
persisted member:A=3
persisted member:B=3
hash=1d8d68ca...
```

El código actual confirma la migración, elimina la marca legítima de B y le importa las sesiones de la fuente ya utilizada por A. **No es solo el antiguo bloqueo de conteos:** la nueva ruta puede aceptar una atribución histórica no demostrada y reemplazar datos de B.

El test `LearningDbStoreTest.kt:291–318` acepta una migración con archivos equivalentes presentes en distintas cuentas. Ese test comprueba separación de ámbitos, pero no demuestra la procedencia que exige la recuperación de residuos históricos.

**Cómo resolverlo:**

1. Separar instalación realmente nueva de BD con contenido previo y sin metadatos de migración. No interpretar «no hay claim» como prueba de que la cuenta activa es la primera propietaria histórica.
2. Antes de crear el claim y ejecutar la limpieza, detectar ese estado ambiguo. Preservar BD y fuente y mantener cuarentena/recuperación explícita cuando no exista linaje suficiente. La coincidencia de conteos o de parte del contenido no autoriza borrar datos ni adjudicar el respaldo.
3. Cuando sí haya procedencia inequívoca, reconciliar únicamente las filas demostradas y con el propietario demostrado. No cambiar el claim al último usuario ni limpiar sus tablas para satisfacer la importación.
4. Conservar la transacción atómica y el claim actual para fallos nuevos; añadir la compatibilidad histórica como una rama separada, no quitar las protecciones ya introducidas.
5. Versionar una fixture de actualización con A parcialmente importado, B con datos propios, fuente preservada y ausencia de ambos marcadores. Tras el intento de B, afirmar datos propios intactos, ausencia de sesiones de A bajo B y bytes de respaldo sin modificar. Cubrir reinicio y reintento del propietario correcto.

**Cierre:** ningún estado sin procedencia suficiente puede borrar datos ni completar silenciosamente una reasignación. Un caso ambiguo conservado y comunicado no debe etiquetarse como recuperación exitosa.

### N02 — iOS conserva el fallo de atomicidad y ámbito de la migración

**P1 · iOS · Defecto estático; la sonda SQL no ejecuta Swift · Residual de R01.**

**Fuente:** `apps/ios/Core/Database/LearningDbStore.swift:276–288` confirma por separado limpieza, importación y metadatos; `:307–315` limpia solo al propietario activo, pero `:333–346` verifica conteos globales. `ArchiveMapper.swift:26–28,51` abre su propia transacción de importación. No hay claim pendiente equivalente al añadido en Android.

**Secuencia:** importación como A → interrupción después de guardar contenido y antes de `migration_hash` → reintento como B. Las filas de A sobreviven a la limpieza de B; se importan las de B y la verificación global encuentra `2N != N`. Reintentar no resuelve el desacuerdo de ámbitos. La apertura falla cerrado; este escenario no demuestra que la UI presente los datos ajenos.

La sonda SQL auxiliar confirmó A=1, B=1 y global=2 frente a esperado=1. Es apoyo al razonamiento de ámbitos, **no reproducción de `LearningDbStore` con XCTest**.

**Cómo resolverlo:**

1. Aplicar en iOS el contrato completo de transacción única: limpieza autorizada, importación, verificación por propietario y metadatos. Extraer una variante del mapper que use la transacción del llamador; no anidar las transacciones actuales.
2. Registrar el propietario/hash de una migración nueva antes de permitir que otra identidad reclame esa fuente. Conservar la política de cuarentena y la preservación del payload SwiftData/respaldo.
3. Tratar residuos anteriores sin claim con la misma cautela de N01: no copiar mecánicamente el parche Android si conserva su defecto de compatibilidad histórica.
4. Marcar la fuente como migrada solo tras el commit. Si falla esa limpieza posterior, reconocer el commit por hash sin reimportar.
5. Añadir tests file-backed de fallo entre fases, A→B→A, rollback, filas válidas de otra cuenta, fuente sin mutación y reinicio. Implementar ahora la fuente y los tests; registrar su ejecución pendiente hasta disponer de macOS.

### N03 — Las claves observables de Aprender no cercan el propietario consultado

**P2 · Android · Ruta identificada por código; sin reproducción Compose de esta ventana.**

**Fuente:** `AppViewModel.kt:465–482` vacía el archivo y pone `loadedLearningIdentity = null`, pero activa B más tarde en IO. `remoteProgressForLanguage()` (`:852–856`) consulta la identidad todavía activa en BD, que puede ser A. `LearnScreen.kt:71–73` recalcula su `remember` con la identidad cargada nula y vuelve a llamar a esos helpers. `setExamOwner()` no activa el gate de carga inicial para ocultar esta ventana.

**Escenario:** A tiene marcas remotas; Aprender sigue montado; se selecciona B y se suspende IO antes de activar B. La proyección local está vacía, pero la consulta remota sigue seleccionando A. La corrección evita el antiguo caché permanente, no esta selección transitoria del propietario anterior. No se afirma escritura cruzada ni una captura UI observada.

**Cómo resolverlo:**

1. Capturar la identidad solicitada y exigir que coincidan identidad solicitada, identidad cargada y propietario consultado. Si falta cualquiera de esas condiciones, devolver proyección vacía/en carga.
2. No consultar el progreso usando únicamente el «propietario activo» mutable de la BD. Pasar propietario explícito y cercar la lectura/publicación contra cambios, usando el mecanismo de identidad existente sin introducir bloqueos del hilo UI.
3. Mantener claves de revisión e idioma y publicar una proyección coherente; ninguna métrica de la misma pantalla debe recurrir por su cuenta a una identidad diferente.
4. Añadir test montado con la activación suspendida: comprobar que desaparecen las marcas de A antes de mostrar B, que no reaparecen durante la espera y que una respuesta tardía de A no sustituye la proyección de B. Cubrir error y reintento de la carga.

### N04 — S08 protege una tarea nueva solo si consiguió iniciarse

**P2 · iOS · Transición defectuosa identificada estáticamente; orden de ejecución montado pendiente.**

**Fuente:** `ConversationCoordinator.swift:754–756` adopta B y llama a sync sin invalidar antes la operación de A. `:268` y `Core/ProgressSyncOperation.swift:31–36` rechazan `begin(B)` mientras existe una operación activa. El handler tardío de cuenta (`ConversationCoordinator.swift:738–740`) cancela A al observar B, pero no programa B. El final de A (`:285–286`) tampoco inicia un sustituto. El estado de A queda oculto a B (`:401–411`), por lo que no necesariamente habrá retry visible.

**Orden relevante:** A mantiene GET suspendido → adopción B antes del handler de notificación → `begin(B)` devuelve nil → notificación invalida A → no se inicia GET de B. La protección nueva evita cancelar B si B ya estaba activo, pero no garantiza que B llegue a estarlo.

**Cómo resolverlo:**

1. Unificar invalidación, adopción/carga y admisión de sync en una transición idempotente por identidad/generación; invalidar A antes de intentar iniciar B.
2. Si no se puede admitir inmediatamente B, registrar la sustitución requerida y programarla al liberar A, verificando que B sigue siendo vigente. No perder la solicitud al devolver nil ni introducir un sleep.
3. Los dos puntos de entrada —notificación y cambio observado por la vista— deben converger en esa misma transición, sin cancelar trabajo nuevo por una señal antigua.
4. Distinguir cambio de cuenta, actualización de perfil y sustitución de credencial. La comparación nueva de accountID tampoco es una prueba suficiente de que una tarea use el bearer vigente; la variante de misma cuenta/token nuevo necesita su caso propio. No se presenta aquí como rotación automática reproducida, porque el flujo normal de sign-in exige sesión nula.
5. Añadir scheduling controlado para ambos órdenes con GET de A suspendido. Exigir GET de B, ausencia de escrituras de A y número de intentos coherente, también tras error/reintento. La ejecución iOS queda pendiente sin bloquear la escritura de estas pruebas.

### N05 — El buffer de apertura queda lleno tras cambiar el epoch de mute

**P2 · Android · Reproducido mediante sonda Kotlin · Interacción residual de S03.**

**Fuente:** `LocalVoiceTransport.kt:407–409` reinicia la captura ordinaria al cambiar epoch, pero no reinicia `openingFrames`, `openingBufferedSamples` ni `openingCaptureFull`. `:424–439` rechaza la frase nueva porque el buffer sigue lleno. Después, `:466–468` descarta los frames antiguos por pertenecer al epoch previo.

**Reproducción controlada:** saludo suspendido → introducir 20 segundos hasta llenar el buffer → mute/unmute → frase nueva identificable → pulsar Finalizar → completar saludo. No se crea petición de respuesta. La sonda final falla en la aserción de respuesta ausente, tras comprobar correctamente la saturación inicial.

**Cómo resolverlo:**

1. Al adoptar un nuevo epoch de captura, invalidar conjuntamente cola de apertura, contador de muestras y bandera de saturación, además del buffer ordinario.
2. Mantener alineados los estados de capacidad y los datos que realmente pueden procesarse: si todos los frames antiguos son inválidos, no deben seguir consumiendo capacidad del epoch nuevo.
3. Conservar la política de Finalizar pendiente y el límite de memoria. No reproducir audio anterior al mute ni capturado mientras estaba silenciado; no enviar automáticamente por resetear el buffer.
4. Versionar el escenario con barreras y muestras identificables. Afirmar exactamente saludo + una respuesta, STT con la frase posterior al unmute y sin muestras antiguas. Añadir mute antes de saturación y toggles repetidos.

La sonda inicial que enviaba frames en ráfaga falló su precondición por overflow artificial del handoff; fue descartada. El hallazgo se apoya en la sonda corregida con entrega acompasada, no en aquel fallo inicial.

### N06 — El borrado remoto de cuenta no inutiliza durablemente su credencial si falla Keychain

**P2 · iOS · Ruta estática condicionada a fallo de eliminación · Extensión relacionada con R16, no regresión del último parche.**

**Fuente:** `ManagedAccountStore.swift:363–369` confirma el borrado remoto, pone la sesión en memoria a nil y anuncia el cambio antes de eliminar Keychain. Si `remove()` falla, no aplica `markCredentialRejected`. `ManagedAccountClient.swift:601–613` puede seguir cargando el registro si es usable. En contraste, el manejo del 401 sí marca rechazo antes de intentar borrar (`ManagedAccountStore.swift:397–405`).

**Efecto condicionado:** un consumidor independiente o el siguiente proceso puede readoptar la sesión de una cuenta ya borrada hasta que el backend la rechace de nuevo. No demuestra acceso remoto autorizado ni que el servidor acepte el bearer eliminado.

**Cómo resolverlo:**

1. Tras confirmar éxito del borrado remoto y antes de anunciar la transición, marcar durablemente la credencial como inutilizable en el scope correspondiente, reutilizando el mecanismo fail-closed existente o uno equivalente.
2. Intentar después eliminar Keychain. Si falla, mantener sesión en memoria vacía y el marcador activo; comunicar la limpieza pendiente sin permitir re-adopción.
3. Borrar el marcador solo cuando corresponda a una nueva autenticación guardada con éxito, no al reconstruir el store ni por desestimar el aviso.
4. Añadir prueba con DELETE remoto exitoso y `remove()` fallido: comprobar notificación, lectura independiente, reconstrucción del store y rechazo de la credencial anterior. Un fallo remoto del DELETE no debe invalidar por error una cuenta que sigue existiendo.

## 4. Estado frente a los hallazgos anteriores

| Área | Balance actual |
|---|---|
| S01 | Atomicidad Android, rollback nuevo y claim por propietario presentes; N01 muestra la laguna histórica. iOS conserva N02. |
| S02 / R05 | Comparación de descarga iOS por componentes/bytes corrige `a`/`a-1`; no apareció otro defecto dentro del dominio válido del contrato. Los slugs/kinds/idiomas admitidos por la API son ASCII: un contraejemplo Unicode fuera de ese dominio no se elevó a hallazgo. |
| S03–S04 | Drenaje del prefijo y aviso de truncamiento corregidos; 50 tests de voz existentes pasan. Queda N05 al combinar saturación y mute. |
| S05 / R09–R10 | Ambas páginas del test iOS de 1.001 filas asignan owner; producción también usa el helper. Fuente corregida, XCTest no ejecutado. |
| S06 / R19 | Conversación registra limpieza global y comprueba token antes de tocar audio; no se encontró otro defecto concreto en ese parche ni en la recuperación de pronunciación. Faltan ejecución iOS y audio físico. |
| S07 | La revisión observable corrige el caché indefinido; N03 afecta la transición entre cuentas. |
| S08 / R18 | El cerco añadido no cubre la admisión rechazada de B, N04. Falta scheduling controlado por los puntos de entrada reales. |
| R02–R03/R06–R08 | Decoder v1, filtrado temporal, cursor de subida, intención durable, commit por página y feedback revisados; no surgió otro defecto concreto en esas rutas. Esto no convierte pruebas de helpers en prueba montada de todo el flujo. |
| R15–R17 | Borrador escrito, callback por identidad/generación, permisos y bloqueo de credencial rechazada revisados sin otro defecto confirmado; N06 amplía el recorrido de invalidación al borrado de cuenta. |

El recorrido manual de emulador registrado por `335f030` es útil para navegación, permisos y examen local. **No prueba S07 durante bootstrap/cambio de miembro:** el propio log SDD indica que las cuentas estaban deshabilitadas y que no se ejercitó member sign-in/progress sync. Tampoco demuestra voz con proveedor suspendido ni continuidad acústica. Es una reserva de evidencia, no un séptimo defecto de ejecución.

## 5. Verificación de esta pasada

| Comprobación | Resultado real |
|---|---|
| `make check` | Exit 0; 148 tests Python y exportadores/paridad. Se registraron 76 advertencias `ResourceWarning` de conexiones SQLite sin cerrar; no hicieron fallar el gate. |
| `make test-api` | Exit 0; 756 aprobadas, cero fallos/omisiones/cancelaciones; 337,2 s de tests. Stack PostgreSQL efímero retirado y ausencia de recursos smoke comprobada. |
| `JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/var/tmp' make android-build` | Ejecutado una vez sin sourceSets de sondas: exit 0, 3m40s. App: 835 tests nuevos, cero fallos/errores/omisiones. Motor: 11 resultados XML sin fallos, pero tarea UP-TO-DATE, no reejecutada. Lint: 0 errores, 73 warnings y 22 hints; APK debug generado. |
| Voz enfocada | 50/50 tests existentes ejecutados y aprobados. Sonda adicional acompasada falla por N05, en la aserción de respuesta ausente. Artefactos temporales `/tmp/voice-final-probe/paced.log` y `src/FinalVoiceProbeTest.kt:1319`. |
| Migración/proyección/fechas Android | 39 tests aprobados en harness scratch recompilando `LearningDbStore.kt` y `ArchiveMapper.kt` actuales con dependencias existentes. No es build limpio completo. Sondas file-backed de actualización reproducen N01, incluida la desaparición de la marca legítima de B. |
| iOS | Lectura de fuente y sonda SQL auxiliar para N02. Swift/Xcode no disponibles; no se afirma compilación, XCTest ni UI ejecutados. |
| Dispositivo/proveedores | No se realizaron nuevas pruebas conectadas/acústicas ni llamadas a proveedores reales en esta revisión. |

**Trazabilidad de las sondas:** `/var/tmp/mobile-data-final.Ubyyc1/LegacyResidueAndLocalProbe.java`, `legacy-and-local2-current.log` y `compile.log`; logs de gates en `/tmp/looro-final-portable-{check,api}.log` y `/var/tmp/final-android-build-versioned.log`. Son diagnósticos temporales, no sustitutos de regresiones versionadas.

Durante la preparación del harness se detectaron clases históricas de la variante `uiTest`. Sus fallos iniciales mezclando versiones se descartaron y **no se atribuyen a HEAD**. La evidencia de N01 procede de recompilar los dos archivos actuales que controlan esa ruta, con la limitación de dependencias declarada arriba. La ejecución normal de 835 tests es independiente de ese harness.

## 6. Plan de acción y criterio de cierre

1. **Prioridad de datos:** resolver N01 y N02 preservando respaldo y registros legítimos. La atomicidad nueva no basta como estrategia de actualización desde estados ya existentes.
2. **Aislamiento de proyección y admisión:** resolver N03 y reproducir N04 con scheduling controlado; comprobar tanto ausencia de datos de A como arranque efectivo de B.
3. **Voz e invalidación:** incorporar las regresiones de N05 y N06 antes de sus cambios y conservar los tests de saludo, límite, mute, leases y credenciales ya añadidos.
4. **Consolidar evidencia:** convertir sondas en tests versionados y repetir gates aplicables. Separar implementación, revisión estática y verificación en plataforma. Escribir los cambios iOS sin esperar a macOS y dejar identificada su ejecución posterior.

No se exige cambiar la política local-first, fusionar invitado con miembro, inferir fechas históricas ni habilitar descargas recurrentes. Este informe no autoriza ni realiza limpieza destructiva de datos. No se han aplicado las correcciones aquí propuestas.
