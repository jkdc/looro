# Revisión de la implementación del informe móvil

**Fecha:** 27 de octubre de 2026  
**Referencia:** [informe original](informe-revision-app-movil-2026-10-25.md), hallazgos M01–M16; especificaciones de `openspec/changes/mobile-audit-remediation/`.  
**Estado revisado:** rama `feature/learning-platform`, `HEAD` `7528e44`, más los cambios locales sin commit de T10 y de la documentación de T16. Este documento no modifica la aplicación.

## 1. Resumen ejecutivo

La remediación ha incorporado aislamiento por identidad, un protocolo de progreso versionado, protección frente a operaciones antiguas y cambios en conversación y pronunciación. **No está lista para considerarse cerrada:** la revisión identifica rutas de contingencia que pueden mezclar datos de cuentas, incompatibilidad del fallback Android con la respuesta real de la API, pérdida de audio en el código local de voz y posibles bloqueos de compilación iOS. Además, siguen faltando verificaciones iOS y de interfaz en dispositivo.

Las prioridades P1/P2 de este documento son prioridades de trabajo, no niveles CVSS. Una conclusión **estática** significa que el código permite deducir el flujo descrito, no que se haya reproducido el comportamiento en un dispositivo. Los puntos etiquetados **riesgo por confirmar** necesitan una prueba de integración antes de concluir que ocurren en la aplicación montada. No hay evidencia aquí de una filtración real, pérdida en producción ni incidencia de seguridad explotada.

### Orden recomendado

1. **Aislamiento y conservación de datos:** R01–R05. Evitar utilizar los modos de contingencia compartidos con distintas cuentas hasta resolver R01; no alterar filas históricas ambiguas sin pruebas de procedencia.
2. **Compilación y ejecución iOS:** R09 y R10, antes de tomar los tests iOS como evidencia.
3. **Voz y continuidad de conversación:** R11–R14; el código de T10 aún no está confirmado en Git.
4. **Flujos iOS, errores de sincronización y riesgos condicionados:** R15–R19.
5. Ejecutar la matriz de aceptación completa al final; un build o una suite unitarios no sustituyen pruebas montadas y de dispositivo.

## 2. Alcance, premisas y método

- Se han contrastado el informe original, el código actual de Android, iOS, API y esquema compartido, y las tareas/especificaciones y pruebas de la remediación. La comparación histórica toma como referencia `0d85a7d48b135ed215933f57eecc4215bdc8d42d`; los hallazgos no se atribuyen automáticamente a la remediación si ya existían.
- Premisas proporcionadas durante la implementación: siempre existe una identidad local activa; los invitados son para pruebas y no se fusionan con miembros; los datos pertenecen a su usuario; se generan en el móvil y se envían al backend; la descarga desde backend corresponde al inicio de sesión en un dispositivo sin datos de ese usuario; las fechas de los eventos las determina el móvil.
- Se hicieron revisiones independientes por área y comprobaciones selectivas de código y contratos. La herramienta CodeGraph no estaba disponible; se usaron lecturas acotadas. Swift/Xcode y dispositivo Android no estaban disponibles, por lo que iOS y las pruebas conectadas se revisaron estáticamente.
- El árbol no estaba limpio: `AppViewModel.kt`, `LocalVoiceTransport.kt` y `LocalVoiceTransportTest.kt` contenían trabajo T10 sin commit; había documentación T16 modificada y archivos no versionados. Las observaciones sobre T10 describen **ese árbol de trabajo**, no solamente `HEAD`. No se han alterado ni confirmado esos cambios.
- El alcance cubre los flujos asociados a M01–M16 y la posterior separación de cuentas S17; no es una auditoría exhaustiva de seguridad, de todo el producto ni de todos los posibles datos históricos.

### Clasificación de evidencia

- **Defecto identificado por código:** existe una ruta concreta incompatible con el contrato; el escenario debe añadirse como regresión ejecutable.
- **Prueba/compilación probable:** la inconsistencia es visible estáticamente, pero no se dispone del compilador o entorno necesario para observar el fallo.
- **Riesgo por confirmar:** hay una combinación plausible de estados o tareas asíncronas cuya posibilidad real debe demostrarse en un flujo montado.
- **Verificación pendiente:** no equivale, por sí sola, a un defecto confirmado.

## 3. Hallazgos priorizados

### R01 — Los almacenamientos de contingencia no aíslan cuentas

**Prioridad:** P1 · **Relación:** M01, M03, M05, S17 · **Evidencia:** defecto identificado por código en rutas de contingencia, no en todos los arranques.

**Android:** ante un fallo de apertura o migración con `learning.json`, `LearningDbStore.kt:84–94,203–217,225–249` habilita modo JSON. `AppViewModel.kt:1317–1324` carga el repositorio sin propietario; `:1517–1528,1531–1540` guarda en ese mismo repositorio. En `:453–488`, el cambio de identidad limpia memoria, pero la recarga por cuenta se limita al modo BD. Una secuencia A → fallo de BD → B → guardar/reiniciar puede sobrescribir o presentar el archivo compartido.

**iOS:** `apps/ios/App/Storage.swift:108–127,172–177,966–1007` utiliza un único payload de contingencia y no lo particiona por identidad. La misma clase de secuencia puede mezclar o reemplazar datos entre usuarios.

**Cambio recomendado:** hacer que el modo de contingencia falle de forma segura sin abrir datos de otro propietario, o introducir almacenamiento aislado por identidad con una migración explícita y recuperable. Preservar el respaldo histórico intacto: no atribuirlo arbitrariamente a la siguiente cuenta. Mantener la política existente de invitado sin fusión automática.

**Aceptación:** forzar fallo de migración con respaldo existente, operar como A y después B, reiniciar y volver a ambas identidades; verificar lo mostrado y los bytes persistidos, ausencia de sobrescritura cruzada, recuperación del respaldo y comportamiento del invitado. Repetir en ambas plataformas.

### R02 — El fallback Android v1 no interpreta la respuesta real del backend

**Prioridad:** P1 · **Relación:** M04 · **Evidencia:** incompatibilidad de contrato identificada por código.

`apps/android/app/src/main/java/com/jkdc/app/network/ProgressClient.kt:132–147` exige `at` en cada fila GET v1. El servidor devuelve `firstAt` y `lastAt`, no `at` (`services/api/src/progress.ts:163–167`). Con v2 desactivado y una respuesta v1 no vacía, Android rechaza la descarga; el runner no llega a la subida tras ese fallo. Esto afecta al fallback definido por el contrato, no implica que toda llamada v2 falle.

**Cambio recomendado:** decodificar explícitamente la forma v1 realmente servida, conservar el primer y último instante cuando existan y limitar el downgrade al 503 de versión no disponible ya especificado.

**Aceptación:** prueba HTTP contra el payload v1 real con datos, v2 desactivado y usuario autenticado; comprobar bootstrap, fechas y posterior subida. Cubrir respuesta vacía, datos malformados, 503 distinto y error de red sin fallback indebido.

### R03 — Android clasifica fechas inválidas, pero no las excluye de la subida

**Prioridad:** P1 · **Relación:** M02 · **Evidencia:** defecto identificado por trazado estático.

La recuperación clasifica registros y existen filtros `syncEligible` (`apps/android/app/src/main/java/com/jkdc/app/data/LearningDbDao.kt:568–569,626–627,809–874`), pero la ruta de producción construye el snapshot desde el archivo completo (`AppViewModel.kt:1180–1195`, `core/ProgressEngine.kt:111–125`). `network/ProgressClient.kt:57–67` rechaza el lote completo si una entrada tiene fechas inválidas. Así, una fila contaminada puede impedir la entrega de otras entradas válidas. En iOS el motor sí filtra fechas individualmente (`apps/ios/Core/ProgressEngine.swift:232–245`); no extrapolar este defecto de Android a ese flujo iOS.

**Cambio recomendado:** filtrar la contribución inválida antes de agregar y serializar, sin borrar la fila ni descartar contribuciones válidas que compartan identidad. Mantener diagnóstico acotado y permitir recuperación posterior respaldada por evidencia; no inferir época o unidad a partir de magnitudes.

**Aceptación:** prueba de integración BD → snapshot → transporte con filas válidas e inválidas, incluidas dos contribuciones de la misma identidad. Comprobar que llegan las válidas, la inválida permanece intacta, el reintento es idempotente y el usuario recibe un estado de sincronización apropiado.

### R04 — Android no arranca la restauración al adoptar una cuenta

**Prioridad:** P1 · **Relación:** M03–M05, S17 · **Evidencia:** defecto identificado por código.

La transición de cuenta desde `MainActivity.kt:60–62` llama a `setExamOwner`; `AppViewModel.kt:453–488` limpia y carga el propietario pero no inicia `syncProgress()` tras completar esa carga. Las llamadas de producción encontradas se realizan al arrancar o al finalizar una conversación (`AppViewModel.kt:1393,2473`). Al entrar como miembro después de haber arrancado como invitado, un dispositivo sin datos puede no restaurar el progreso hasta otro evento posterior.

**Cambio recomendado:** orquestar una única transición: activar identidad y cargar su estado, comprobar que sigue vigente y solicitar bootstrap/continuación; invalidar la operación anterior sin permitir cruces de cuenta. Evitar disparar la descarga en cada cambio de pantalla.

**Aceptación:** con backend simulado, arrancar como invitado, iniciar sesión miembro sin cerrar conversación y verificar la descarga y su aplicación una sola vez. Probar además A → B durante la carga, fallo de red y reintento, y dispositivo que ya tiene datos del miembro.

### R05 — El orden del cursor de subida no coincide con el orden de los lotes

**Prioridad:** P1 · **Relación:** M04 · **Evidencia:** contradicción determinista en ambos clientes.

Las entradas se ordenan por tupla `(kind, itemID, language)`, pero el filtro de reanudación compara cadenas separadas por `|`: Android `AppViewModel.kt:243–252,1195` e iOS `apps/ios/App/ConversationCoordinator.swift:283–287,305`. Para `(lesson, a, en)` y `(lesson, a-1, en)`, el primer orden coloca `a` antes de `a-1`, mientras que `lesson|a|en` queda **después** de `lesson|a-1|en`. Una reanudación tras un ack en ese límite puede omitir temporalmente una identidad; una pasada completa posterior podría recuperarla.

**Cambio recomendado:** representar el cursor con componentes estructurados y comparar con el mismo comparador por campos que forma los lotes; mantener compatibilidad y una migración segura de cursores ya persistidos. No confiar en un delimitador cuyo orden varíe frente a caracteres válidos de los IDs.

**Aceptación:** más de 200 entradas con IDs que sean prefijos y contengan `-`, interrupción después del primer ack, reinicio y comprobación de la igualdad del conjunto en el backend. Repetir en Android e iOS, incluyendo idiomas distintos y replay.

### R06 — Android no confirma cada página durante la descarga

**Prioridad:** P2 · **Relación:** M04 · **Evidencia:** defecto identificado por código.

`ProgressClient.kt:102–129` acumula todas las páginas en memoria y solo las entrega al completar el recorrido; `AppViewModel.kt:1048–1061` las persiste después. Si la página 1 llega y falla la 2 o termina el proceso antes del retorno, la página 1 y su cursor no se han confirmado. El siguiente intento empieza desde el cursor previamente guardado; no se deduce corrupción, pero sí reanudación ineficaz y riesgo de no progresar en redes inestables. iOS sí aplica cada página antes de pedir la siguiente (`apps/ios/App/ConversationCoordinator.swift:229–250`).

**Cambio recomendado:** recibir, validar y aplicar cada página y su cursor en una transacción antes de solicitar la siguiente, conservando las guardas de propietario/generación y la atomicidad ante página inválida.

**Aceptación:** servidor que devuelve 1.001 o más registros y falla en la segunda página; verificar BD y cursor tras la primera, reiniciar, continuar desde ese cursor y comprobar conjunto final exacto y ausencia de mezcla de cuentas.

### R07 — La decisión de dispositivo sin datos no cubre todo el propietario

**Prioridad:** P2 · **Relación:** M05, S17 · **Evidencia:** defecto de cobertura del criterio identificado por código.

Android solo comprueba lecciones con intentos y marcas (`AppViewModel.kt:1043–1046`); iOS utiliza el criterio equivalente (`apps/ios/App/Storage.swift:144–146`). No se consideran, por ejemplo, sesiones, expresiones o exámenes de ese usuario. Puede iniciarse una descarga cuando ya existe otro dato local. Al omitirla tampoco queda necesariamente registrada una decisión durable, de modo que podría reevaluarse más tarde.

**Cambio recomendado:** definir y documentar un inventario inequívoco de datos del propietario que cuenta como dispositivo con datos; distinguir valores de configuración iniciales de datos creados por el usuario. Persistir por identidad la decisión de bootstrap de manera transaccional y respetar la excepción de recuperación autorizada, sin inventar actividad.

**Aceptación:** casos independientes con solo sesión, expresión, examen, lección o marca; prueba de cuenta distinta, reinicio, eliminación de datos, migración y recuperación. Verificar que una cuenta vacía sí restaura y una no vacía no descarga contra la premisa acordada.

### R08 — Las operaciones de sincronización fallidas no tienen feedback observable

**Prioridad:** P2 · **Relación:** M02–M05 · **Evidencia:** trazado estático.

En Android, `AppViewModel.kt:1028–1030,1053,1208–1218` registra o descarta fallos sin exponer un resultado; iOS retorna en errores de descarga o subida (`apps/ios/App/ConversationCoordinator.swift:268–270,302,318`). El dato local puede permanecer disponible, pero el usuario no sabe que el envío o la recuperación sigue pendiente.

**Cambio recomendado:** exponer por cuenta un estado persistente u observable de pendiente, error recuperable y sincronizado; ofrecer reintento explícito y no presentar cancelación por cambio de cuenta como error del nuevo usuario. No registrar credenciales ni payloads.

**Aceptación:** pruebas con red ausente, HTTP no compatible, respuesta inválida, cancelación por cambio de cuenta, reinicio y reintento exitoso; comprobar estado visible, accesible y asociado a la identidad correcta.

### R09 — Posible bloqueo de compilación del target iOS App

**Prioridad:** P1 · **Relación:** M12–M13 · **Evidencia:** probable error estático; compilación no ejecutada.

`apps/ios/App/PronunciationCheckController.swift:1–4,290,322–323` utiliza `PronunciationEngine` y `ProgressEngine`, declarados en el módulo `LearningCore` (`apps/ios/Package.swift`, `Core/PronunciationEngine.swift:7`, `Core/ProgressEngine.swift:30`), sin importar ese módulo en el archivo. Es una discrepancia que previsiblemente impide resolver esos símbolos; falta confirmarla con Xcode.

**Cambio recomendado:** importar explícitamente `LearningCore` en el archivo y comprobar el enlace del producto al target, sin sustituir una compilación real por inspección del proyecto.

**Aceptación:** compilar App para simulador y ejecutar AppTests en macOS/Xcode; verificar también la ruta de pronunciación local y las claves efectivas del bundle. Conservar el error del compilador como evidencia si aparece antes de la corrección.

### R10 — Dos problemas en pruebas iOS de capacidad y continuación

**Prioridad:** P1 para recuperar la capacidad de verificar · **Relación:** M04, M15 · **Evidencia:** inconsistencias estáticas; no se ejecutó Swift.

1. `apps/ios/AppTests/TopicSearchCapabilityTests.swift:55` accede a `ConversationCoordinator.topicSearchLimitation` como miembro de tipo, pero la declaración es una propiedad de instancia (`ConversationCoordinator.swift:1425–1426`). Es un probable fallo de compilación de AppTests.
2. `apps/ios/AppTests/ProgressClientTests.swift:25–36` ordena lexicográficamente `lesson-0` … `lesson-200` y espera que la identidad restante sea `lesson-200`; con ese orden la última es `lesson-99`. Se contrastó el ejemplo de orden con una sonda Python, no con Swift. Además, ese test no demuestra por sí solo persistencia real ni reinicio.

**Cambio recomendado:** usar la instancia correspondiente para la capacidad y corregir la fixture o calcular el esperado a partir del contrato de orden, sin debilitar la comprobación. Añadir pruebas del coordinador con BD real y recuperación de cursor.

**Aceptación:** observar los fallos con AppTests en macOS antes de la corrección, después ejecutar el target completo; incluir 201 subidas y 1.001 descargas con interrupción, persistencia y reinicio.

### R11 — La captura manual puede descartar una frase sin avisar

**Prioridad:** P1 · **Relación:** M07 · **Evidencia:** defecto identificado en el trabajo T10 sin commit.

`LocalVoiceTransport.kt:426–433` contabiliza el tiempo de la ventana manual desde el inicio, incluido silencio, y al alcanzar 20 segundos reinicia el buffer sin notificación visible. Si una persona espera unos 19 segundos y comienza a hablar, puede perderse la frase al cruzar el límite; `:521–531` también reinicia al llegar al límite con voz. El log no informa al usuario de que debe repetirla.

**Cambio recomendado:** separar el límite de espera silenciosa del límite de una intervención activa; definir una política explícita y segura para voz larga, por ejemplo cierre o segmentación con feedback accesible, preservando límites de memoria y evitando turnos fantasma. La política exacta debe probarse con las capacidades reales del motor.

**Aceptación:** tests deterministas de silencio prolongado → frase que cruza el límite, frase larga, pulsación durante silencio y overflow. Inspeccionar audio entregado a STT, eventos y aviso visible, además de medir la cota real de memoria; ejecutar posteriormente en dispositivo.

### R12 — El límite de STT puede eliminar el comienzo de una intervención

**Prioridad:** P2 · **Relación:** M07 · **Evidencia:** defecto identificado por código; ya existía en la base original, no se atribuye como regresión nueva a T10.

Se permite una captura de hasta 20 s (`LocalVoiceTransport.kt:940–950`), pero `:674–686` entrega a STT solo los **últimos** 18 s tras recortar silencios. Si la parte hablada supera ese límite, se pierden las primeras palabras, contraviniendo la conservación del inicio y preroll de S7.

**Cambio recomendado:** armonizar el máximo de captura y el de entrada del reconocedor o segmentar sin descartar el inicio; especificar cómo se comunica al usuario cualquier truncamiento inevitable.

**Aceptación:** entrada con un prefijo reconocible y voz de más de 18 s; verificar las muestras que recibe STT y el texto/estado, no solo la longitud del buffer. Cubrir límites exactos y memoria acotada.

### R13 — El primer turno puede adelantarse al saludo

**Prioridad:** P2 · **Relación:** M09 · **Evidencia:** carrera permitida por código, no reproducida en dispositivo.

`LocalVoiceTransport.kt:224–247` inicia la escucha antes de lanzar la generación del saludo en otra coroutine; el turno construye su contexto y llama al motor en `:717–732` sin esperar a que ese saludo se incorpore al historial. Si el usuario habla de inmediato y la generación inicial tarda, el turno puede ejecutarse sin la apertura y su respuesta adelantarse. La prueba existente espera el saludo antes de introducir voz (`LocalVoiceTransportTest.kt:444–462`), por lo que no cubre esta intercalación.

**Cambio recomendado:** introducir una barrera o secuencia explícita para la apertura y los turnos aceptados, con cancelación y tratamiento de error; no basar el orden en un `delay`.

**Aceptación:** bloquear el proveedor del saludo, introducir voz inmediata y liberar en un orden controlado; verificar orden de peticiones, eventos y contexto exacto, sin duplicados ni bloqueo tras fallo/cancelación.

### R14 — El mute manual puede deshabilitarse mientras el micrófono sigue activo

**Prioridad:** P2 · **Relación:** M10 · **Evidencia:** ruta estática; no se afirma transmisión efectiva de audio.

`apps/android/app/src/main/java/com/jkdc/app/ui/TalkScreen.kt:264–275` deshabilita el control de mute cuando `vm.working` es verdadero. Una respuesta escrita mantiene ese estado durante la espera al proveedor (`AppViewModel.kt:2944–2960`) sin que de ello se deduzca que el micrófono esté apagado. En ese intervalo el usuario puede perder el control explícito de entrada. El test Compose existente no cubre la espera real de un proveedor ni la continuidad real del audio del tutor.

**Cambio recomendado:** mantener el control de entrada disponible durante la sesión de voz cuando la operación pendiente no exige deshabilitarlo; conservar etiquetas y estado accesibles EN/ES y no detener el audio de salida al silenciar entrada.

**Aceptación:** UI montada con proveedor suspendido, `working=true` y micrófono activo; accionar mute y verificar semántica/estado, ausencia de captura durante el silencio y continuidad de la salida. Ejecutar en dispositivo o emulador y realizar comprobación acústica física cuando corresponda.

### R15 — El editor iOS puede perder una edición posterior o quedar bloqueado en silencio

**Prioridad:** P2 · **Relación:** M14 · **Evidencia:** rutas identificadas por código; UI montada no ejecutada.

1. `apps/ios/App/RootView.swift:269,275–284` permite editar mientras se espera el envío. `ConversationCoordinator.swift:1310–1317` captura el texto enviado; si se envía A, se escribe B mientras la petición está suspendida y A termina bien, el editor puede cerrarse y descartar B.
2. `ConversationCoordinator.swift:1313,1319–1324,1361–1367` mantiene la operación anterior en vuelo hasta su terminación. Si se cierra y se reabre el editor antes de resolverla, un envío nuevo puede devolver `.inFlight`; el estado del editor no ofrece una explicación específica. La prueba `apps/ios/AppTests/TypedReplyTests.swift:84–104` resuelve la petición anterior antes de reabrir y no cubre este orden.

**Cambio recomendado:** decidir una única semántica de edición mientras se envía: deshabilitarla de forma accesible o conservar el texto nuevo sin cerrar el editor; al cerrar/reabrir, cancelar realmente la petición cuando sea seguro o mostrar claramente el envío todavía activo y permitir recuperación sin duplicados.

**Aceptación:** vista montada con proveedor controlado: A → editar B → éxito/fallo de A; cerrar → reabrir antes del fin; doble pulsación, reintento repetido y respuesta tardía. Comprobar texto, foco, error persistente, número de peticiones y mensajes guardados.

### R16 — La invalidación iOS puede quedar incompleta si falla Keychain

**Prioridad:** P1 · **Relación:** M16, S17 · **Evidencia:** rama de error identificada por código.

Ante `sign_in_required`, `apps/ios/App/ManagedAccountStore.swift:388–391` limpia sesión/perfil en memoria, pero si `keychain.remove()` falla retorna antes de anunciar el cambio. El observador que actualiza el helper (`ConversationCoordinator.swift:468–469,613–631`) puede conservar el bearer anterior; además, el borrado fallido deja una credencial persistida. La prueba `SessionAdoptionTests.swift:205–227` cubre el borrado correcto, no ese error. No se demuestra aquí que el servidor acepte el token rechazado.

**Cambio recomendado:** invalidar de forma cerrada el helper y las operaciones en memoria aunque falle el borrado; no volver a adoptar automáticamente el bearer rechazado desde Keychain. Comunicar el error recuperable y definir cómo se reintenta la limpieza sin registrar secretos.

**Aceptación:** Keychain simulado que falle al eliminar tras un 401; comprobar sesión, helper, llamadas posteriores, notificación, mensaje y reintento. Añadir éxito posterior y cambio de cuenta, manteniendo la verificación de sesión adoptada antes de cargar perfil.

### R17 — Una evaluación de pronunciación podría terminar bajo otra identidad

**Prioridad:** P1 condicional · **Relación:** M12, S17 · **Evidencia:** riesgo por confirmar en UI montada.

El intento guarda una referencia al `LearningStore` mutable, pero no propietario/generación (`apps/ios/App/PronunciationCheckController.swift:156–169,204–206`). Al recibir un resultado aprobado escribe con el estado activo de ese store (`:319–325`; `Storage.swift:172–181,422–429`). Si A inicia la evaluación, se adopta B y la vista permanece montada hasta la respuesta tardía, el resultado podría atribuirse a B. Debe confirmarse que esa navegación/intercalación sea alcanzable; no se ha reproducido una escritura cruzada.

**Cambio recomendado:** capturar identidad y generación al iniciar, invalidar/cancelar al cambiarlas y comprobarlas de nuevo justo antes de persistir y ejecutar callbacks; no confiar solo en que desaparezca la vista.

**Aceptación:** reconocimiento suspendido con A → B → liberar resultado; comparar BD y estado de B, verificar que no se ejecuta el callback antiguo y que una nueva evaluación de B sí funciona. Añadir importación/restauración, cancelación y navegación tardía.

### R18 — Posible pérdida del disparo de bootstrap iOS al adoptar cuenta

**Prioridad:** P2 · **Relación:** M03–M05, S17 · **Evidencia:** riesgo por confirmar con orden controlado.

La notificación de adopción inicia una tarea que llama a `syncProgress()` (`apps/ios/App/ManagedAccountStore.swift:135–136`; `ConversationCoordinator.swift:468–469,624–629`). Por separado, `RootView.swift:39–40` activa la identidad del store; la sincronización exige que ya coincida con el miembro (`ConversationCoordinator.swift:203–207`). Si la tarea corre antes de esa activación, la guarda puede rechazarla y no se observa otro disparo inmediato desde esa activación. No se ha demostrado que SwiftUI programe siempre ese orden.

**Cambio recomendado:** hacer explícita la secuencia de adopción → activación/carga → sincronización, con comprobación de generación y cancelación de la cuenta anterior, en vez de depender del orden entre observadores.

**Aceptación:** prueba montada o de coordinador con la notificación y la activación deliberadamente reordenadas; verificar que una cuenta sin datos inicia un solo bootstrap y que un cambio rápido A → B nunca aplica A a B.

### R19 — La propiedad de la sesión de audio iOS no está demostrada

**Prioridad:** P2 · **Relación:** M12 · **Evidencia:** riesgo de gestión de recursos; efecto acústico no verificado.

La evaluación activa `AVAudioSession` (`PronunciationCheckController.swift:120–125`), pero la liberación restaura categoría, modo y opciones sin registrar ni coordinar la propiedad de activación (`:132–137`). El transporte de conversación también configura el audio (`apps/ios/App/LiveTransport.swift:41–47,115–118`). La lectura no demuestra una fuga perceptible ni permite desactivar globalmente una sesión que aún necesite otro componente.

**Cambio recomendado:** definir propiedad y arbitraje del audio compartido; liberar o restaurar la activación únicamente cuando corresponda, con errores controlados y sin interrumpir una conversación activa.

**Aceptación:** en dispositivo, alternar conversación, pronunciación, cancelación, segundo plano, interrupción y TTS; comprobar rutas, permisos, audio activo y recuperación. Complementar con tests de lease simulado y secuencias de adquisición/liberación.

## 4. Estado frente a los 16 hallazgos originales

| Original | Estado actual de la implementación revisada | Evidencia / reserva principal |
|---|---|---|
| **M01** Historial | Filtro y guardas por propietario implementados; aislamiento global aún incompleto. | Tests Android de propietario existen; R01 afecta contingencia en ambas plataformas. Falta UI conectada e iOS ejecutado. |
| **M02** Fechas | Conversores y recuperación por procedencia implementados parcialmente. | R03 en subida Android; las filas legacy ambiguas siguen sin reparación por falta de evidencia. iOS sin ejecución. |
| **M03** Sincronización antigua | Operaciones cercadas por identidad/generación presentes. | No se ha demostrado todo el flujo montado de importación/cambio de cuenta; R04 y R18 afectan el disparo. |
| **M04** Límites de lote | API v2 y continuación implementadas; incumplimientos de cliente pendientes. | R02, R05 y R06; iOS confirma cada página pero le falta prueba integral de fallo/reinicio. |
| **M05** Convergencia | Rollups por propietario/idioma y máximos/mínimos presentes sin crear eventos. | R04 y R07; pruebas reales de proyección iOS pendientes. |
| **M06** Cambio de tema Android | Selección pendiente y espera de cierre implementadas. | Tests de gate/diálogo no prueban por sí solos `AppRoot`/`AppViewModel` montados; falta UI conectada. |
| **M07** Memoria de voz | Cola, preroll y captura acotados en el árbol local T10. | R11–R12; no basta medir la entrada final a STT para demostrar la memoria máxima del sistema. |
| **M08** Interrupción de voz | Captura y procesamiento separados; hay pruebas con TTS simulado. | T10 sin commit; la prueba de ausencia de turnos fantasma mantiene una carrera documentada. Falta audio físico con y sin AEC. |
| **M09** Contexto del tutor | El saludo se añade al historial. | R13: falta verificar el turno temprano y su orden; T10 sin commit. |
| **M10** Mute manual | Control separado, 52 dp, etiquetas y pruebas presentes. | R14; no hay ejecución conectada ni prueba de comportamiento durante `working=true`. |
| **M11** Permiso iOS | Claves de permisos y prueba de bundle presentes en fuente. | Bundle, permisos y target no ejecutados; R09 bloquea la confianza en compilación. |
| **M12** Ciclo de pronunciación | Estados, timeout, cancelación y limpieza implementados en fuente. | R09, R17 y R19; sin Xcode ni prueba física. |
| **M13** Reconocimiento local | Comprobación de disponibilidad y modo local presentes en fuente. | R09; falta compilación y prueba offline real en dispositivo. |
| **M14** Envío escrito iOS | Estado tipado, continuidad y protección frente a respuestas obsoletas mejorados. | R15; falta prueba de UI montada con edición/reapertura durante espera. |
| **M15** Capacidad de búsqueda | Guardia de capacidad y aviso presentes en fuente. | R10; falta test del flujo real configurado y ejecución del target iOS. |
| **M16** Adopción de sesión | Notificación antes de la carga de perfil implementada en el camino principal. | R16 y riesgo de orden R18; no se ejecutaron pruebas iOS. |

## 5. Verificaciones realizadas y límites

| Comprobación | Resultado observado en esta revisión |
|---|---|
| `make check` | **Exit 0:** 148 pruebas Python; exporters/contratos y esquema actualizados, 152 sentencias y `user_version` 7. Se emitieron advertencias `ResourceWarning` por conexiones SQLite sin cerrar. |
| `make test-api` | **Exit 0:** 756 pruebas, 756 aprobadas, 0 fallos, cancelaciones u omisiones. Ejecutado en el stack PostgreSQL efímero de pruebas, retirado al finalizar. |
| `cd apps/android && ./gradlew --offline --no-parallel --max-workers=1 :app:testDebugUnitTest :core:engine-local:testDebugUnitTest --rerun-tasks` | **Exit 0:** tareas realmente ejecutadas; XML: 793 pruebas de app + 11 del motor, sin fallos, errores ni omisiones. No prueba los escenarios faltantes descritos arriba. |
| Orden de cursores y fixture iOS | Una sonda Python comprobó que el orden de tuplas y de cadenas con `|` se invierte para `a`/`a-1` y que `lesson-99` es el último de los 201 IDs lexicográficos. No es una ejecución de Kotlin ni Swift. |
| Android conectado / audio físico | `adb devices -l` no mostró dispositivos. No se ha ejecutado UI conectada ni validación acústica en esta revisión. |
| Swift Core, App y AppTests | `swift` y `xcodebuild` no estaban disponibles. Ninguna prueba o compilación iOS se presenta como ejecutada. |
| Otros proveedores, producción y redes externas | No se ejecutaron llamadas a proveedores reales, despliegues ni pruebas sobre datos reales de usuarios. |

El informe SDD `openspec/changes/mobile-audit-remediation/verify-report.md` contiene resultados anteriores y el estado previo de T16. Sus pruebas siguen siendo evidencia histórica; este documento añade una revisión de las rutas y limitaciones, sin convertir tareas pendientes en cerradas.

## 6. Plan de corrección y criterio de cierre

1. **Cerrar la separación de cuentas y la sincronización:** resolver R01–R08 con pruebas de integración por plataforma, empezando por contingencia, contrato v1 y fechas; mantener backfill y recuperación histórica sujetos a evidencia de procedencia. La solución de R05 debe validar cursores persistidos existentes antes de cambiar su formato.
2. **Restaurar capacidad de verificación iOS:** confirmar R09–R10 en macOS, corregir y ejecutar Swift Core, AppTests y App/UI. No marcar como corregida una sospecha de compilación sin observar el compilador.
3. **Corregir voz y UX:** resolver R11–R16 con pruebas de regresión que fallen por el comportamiento descrito, seguidas de pruebas montadas y de dispositivo. Conservar el trabajo T10 sin commit hasta revisarlo y verificarlo; no confundir una prueba intermitente con un resultado estable.
4. **Investigar y resolver según evidencia:** reproducir R17–R19 con orden y límites controlados, y registrar la decisión si una intercalación no es alcanzable. Cualquier cambio de política de datos o recuperación que altere la premisa del usuario requiere acordarse explícitamente.
5. **Cerrar por flujo, no por número de tests:** comparar cada requisito M01–M16 y S17 contra evidencia de persistencia, HTTP, UI y plataforma; incluir red/HTTP, cancelación, respuestas tardías, reintento, cuenta A/B/invitado, reinicio y estado vacío donde aplique. Ejecutar `make check`, `make test-api`, suite/lint/build Android, UI conectada y Swift/Xcode; añadir pruebas físicas de audio y accesibilidad. Registrar comandos, versión/entorno, conteos, fallos y omisiones.

**Criterio de cierre:** no dar por cerrada T16 ni lista la remediación para ampliar su uso mientras existan fallos P1 reproducibles, mientras el aislamiento de contingencia no esté demostrado o mientras falten las pruebas de iOS y dispositivo exigidas por los flujos afectados. Si el entorno no permite una comprobación, dejarla explícitamente pendiente en lugar de asumirla aprobada.

## Key Learnings:

1. Un almacenamiento de contingencia debe conservar la misma separación de cuentas que la base principal.
2. El comparador de un cursor durable debe coincidir con el orden que forma los lotes.
3. Una suite verde no demuestra que el test de usuario o la integración entre capas esté cubierto.
4. La compilación de iOS y las pruebas de audio necesitan evidencia del entorno y dispositivo correspondientes.
