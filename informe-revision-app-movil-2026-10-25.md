# Revisión de la aplicación móvil y plan de acción

**Fecha:** 25 de octubre de 2026 · **Producto:** Palique / Looro · **Plataformas:** Android e iOS.
**Referencia:** rama `feature/learning-platform`, commit `0d85a7d48b135ed215933f57eecc4215bdc8d42d`.
**Estado:** diagnóstico y propuesta de actuación. No se han aplicado correcciones a la aplicación.

## 1. Conclusión ejecutiva

La prioridad no debería ser añadir pantallas o funcionalidades, sino reforzar tres fundamentos: **aislamiento entre cuentas, conservación/convergencia del progreso y ciclo de vida de la conversación por voz**.

Esta revisión recoge **16 acciones**: defectos observables en el código, limitaciones funcionales y una mejora de control del micrófono. No todas tienen la misma gravedad ni el mismo nivel de prueba. Los problemas de progreso ya tienen una propuesta técnica en `openspec/changes/audit-progress-sync/`; deben retomarse y reconciliarse con el estado actual, no crear una implementación paralela.

### Por dónde empezar

1. Corregir el historial Android que consulta resultados sin filtrar la cuenta activa: **M01**.
2. Hacer seguras las fechas y la invalidación de sincronizaciones antiguas: **M02–M03**.
3. No activar la evaluación beta de pronunciación iOS sin resolver permiso y garantía local: **M11–M13**.
4. Corregir confirmación de cambio de tema y crecimiento del búfer de voz Android: **M06–M07**.
5. Completar sincronización y recuperación; después mejorar continuidad y recuperación de errores en conversación.

No hay evidencia suficiente para declarar un incidente en producción, una pérdida irreversible de datos, un OOM observado o una transferencia efectiva de audio a terceros. Sí hay razones concretas para corregir los flujos descritos antes de considerarlos verificados.

### Inventario priorizado

| ID | Prioridad | Plataforma | Tipo de cambio | Resultado buscado |
|---|---|---|---|---|
| M01 | P1 | Android | Consulta, identidad y estado de UI | Un usuario no ve resúmenes de exámenes de otro |
| M02 | P1 | Ambas + API | Contrato temporal y recuperación de datos | El progreso descargado puede volver a subirse sin corromper fechas |
| M03 | P1 | Ambas | Concurrencia e identidad de operación | Una respuesta antigua no escribe sobre una cuenta o BD nueva |
| M04 | P1 | Ambas + API | Paginación y continuación durable | Se sincroniza todo el conjunto, no siempre los primeros 200/1.000 |
| M05 | P2 | Ambas | Proyección y convergencia | Se recuperan mejores resultados sin inventar actividad |
| M06 | P1 | Android | Transición de sesión y confirmación | Cancelar no modifica el tema; confirmar inicia la nueva conversación |
| M07 | P1 | Android, voz local | Memoria y captura | El silencio inicial no acumula audio sin límite |
| M08 | P2 | Android, voz local | Concurrencia de audio | La voz del usuario puede interrumpir una respuesta hablada normal |
| M09 | P2 | Android, voz local | Contexto del tutor | El siguiente turno incluye el saludo que el usuario acaba de oír |
| M10 | P2 | Android | Mejora de UX y accesibilidad | El modo manual mantiene una acción explícita para silenciar entrada |
| M11 | P1, antes de activar beta | iOS | Configuración de permisos | Solicitar reconocimiento de voz no termina la aplicación |
| M12 | P2 | iOS | Estados, cancelación y recursos | Pronunciación no queda bloqueada ni completa operaciones obsoletas |
| M13 | P1, antes de activar beta | iOS | Privacidad y capacidades | La evaluación de pronunciación garantiza procesamiento local |
| M14 | P2 | iOS | Envío escrito y reintento | Un fallo conserva el borrador y permite reintentar sin duplicar |
| M15 | P2 | iOS | Capacidades de motor y UI | No se ofrece una búsqueda que ese motor no puede completar |
| M16 | P2 | iOS | Notificación de autenticación | La adopción de sesión no depende de que cargue el perfil |

**P1:** atender antes de ampliar el uso del flujo afectado; combina impacto y posibilidad de ocurrencia, no una severidad CVSS. **P2:** mejora funcional importante o defecto acotado. No se asigna P0 con la evidencia obtenida. Las prioridades condicionales de pronunciación afectan a una opción beta, no a todas las conversaciones iOS.

## 2. Alcance, método y límites

### Qué se ha revisado

- Android: transporte de voz local, contexto conversacional, cambio de tema, controles manuales, historial de exámenes y sincronización de progreso.
- iOS: pronunciación, permisos, recursos de audio, respuesta escrita, búsqueda de temas, adopción de sesión y sincronización de progreso.
- API y persistencia solo cuando forman parte del contrato consumido por las aplicaciones.
- Tests y especificaciones vecinas para distinguir una corrección pendiente de algo ya implementado.
- Tres revisiones de lectura independientes por dominio, seguidas de contraste de fuentes y sondas focalizadas. CodeGraph se sincronizó antes de consultar la estructura del proyecto.

### Cómo interpretar las pruebas

- **Sonda ejecutada:** comportamiento comprobado con funciones reales o SQL extraído del código, usando fixtures locales. Se especifica qué capa no se ejecutó.
- **Verificado por código:** recorrido alcanzable respaldado por condiciones, llamadas y estados actuales. No equivale a una reproducción en móvil.
- **Mejora propuesta:** carencia de experiencia o límite conocido, no vulnerabilidad ni incumplimiento demostrado.
- Las reproducciones de cada ficha son **procedimientos de regresión pendientes**, salvo donde se indica explícitamente que se ejecutó una sonda.

No hubo dispositivo ni emulador conectado (`adb devices -l` vacío). No están disponibles Swift ni Xcode en este entorno Linux. No se hicieron llamadas a proveedores LLM, compras, login real, cambios de cuenta reales, despliegues ni pruebas contra datos de un usuario. No se instalaron dependencias.

No es una auditoría exhaustiva de seguridad, una certificación de accesibilidad, una evaluación pedagógica del contenido ni una medición de rendimiento/batería. No se han validado todos los motores de conversación ni todos los idiomas. Las pantallas y los efectos acústicos necesitan pruebas en los dispositivos indicados al final.

### Condiciones de implementación

- Este informe **no autoriza a ejecutar todas las propuestas**. La petición autoriza revisar y generar este documento.
- Para implementar, usar Superpowers, la rama activa y SDD en `openspec/changes/`; no crear worktrees, cambiar de rama, hacer push ni abrir PR sin petición explícita.
- Código, identificadores y comentarios en inglés; informe/documentación de producto en español. Android: mantener strings EN y ES y los `testTag` existentes.
- No editar fuentes generadas. Cambiar contrato/esquema fuente y regenerar mediante el exporter correspondiente.
- Las correcciones de persistencia no deben borrar progreso, reapropiarse de registros anónimos ni reutilizar migraciones aplicadas.
- Test-first: observar el fallo correcto, aplicar la corrección mínima y comprobar el resultado y la ausencia de efectos secundarios. Una suite histórica verde no sustituye la regresión específica.

## 3. Progreso, persistencia y separación de cuentas

### M01. Historial de exámenes sin aislamiento por cuenta

**Prioridad:** P1 · **Tipo:** defecto de aislamiento local · **Evidencia:** código y SQL ejecutado en SQLite en memoria.

**Dónde:** `apps/android/app/src/main/java/com/jkdc/app/data/LevelTestAttemptStore.kt:119–127`; `AppViewModel.kt:282–296` en ese mismo paquete base; `ui/LearnScreen.kt:115–123,225–248`.

**Qué ocurre.** `list(languageID, level)` filtra idioma/nivel, pero no `owner_account_id`. Después carga cada resultado con el propietario leído de la propia fila. Ese valor no representa la identidad que está solicitando el historial. La pantalla muestra puntuación, fecha y estado de sincronización de los resultados obtenidos.

La sonda ejecutó el SQL extraído de esa función con registros de A, B y un invitado, todos del mismo idioma/nivel: devolvió los tres. **No se demuestra acceso al detalle ajeno:** la ruta de carga individual sí utiliza `examOwnerID`. La exposición identificada es la lista de resúmenes.

**Cómo reproducir en regresión:** A completa un examen; sale de su cuenta; B o un invitado abre Aprender en el mismo idioma/nivel. Añadir también el cambio de cuenta mientras Aprender permanece montado: sus claves actuales de recarga no incluyen la identidad.

**Cómo realizar el cambio:**

1. Exigir propietario explícito en `list`, con una política definida para `NULL` anónimo.
2. Añadirlo al predicado SQL y pasar el propietario capturado por el solicitante a `load`; nunca autorizar una fila usando su propio propietario.
3. Propagar identidad/revisión de cuenta desde `AppViewModel` a la carga de historial.
4. Vaciar inmediatamente la lista en memoria al cambiar identidad; recargar y descartar respuestas de una identidad anterior.
5. Mantener separados los intentos de invitado. No convertirlos automáticamente en propiedad de la siguiente cuenta.

**Pruebas de aceptación:** ampliar `apps/android/app/src/test/java/com/jkdc/app/data/LevelTestAttemptStoreTest.kt` con A/B/NULL. Cada identidad solo obtiene sus filas. En Compose, A→B y A→sin sesión eliminan el resumen anterior y una carga tardía de A no lo repone. Conservar las pruebas que impiden acceder al detalle de otro propietario.

**Alcance/riesgo:** consulta + modelo de identidad + UI; no requiere cambiar el contenido del examen. Riesgo de permisos y orden asíncrono: revisión independiente al implementar.

### M02. Fechas incompatibles que contaminan y bloquean el progreso

**Prioridad:** P1 · **Tipo:** contrato temporal y recuperación · **Evidencia:** código y parser real ejecutado offline.

**Dónde:** `services/api/src/progress.ts:44–47,75–79`; Android `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt:812–817` y `core/ProgressEngine.kt:113–116,149–154`; iOS `apps/ios/App/ProgressClient.swift:68–70`, `apps/ios/Core/ProgressEngine.swift:162–169,185–192` y `apps/ios/App/Storage.swift:318–332`.

**Qué ocurre.** La API intercambia milisegundos Unix. El almacenamiento/motor nativo trabaja con segundos desde la época Apple. Los clientes introducen `lastAt` remoto sin convertirlo; al volver a subirlo aplican otra conversión. Además, iOS escribe algunos intentos de lección con `timeIntervalSince1970` mientras las marcas de finalización usan `timeIntervalSinceReferenceDate`.

**Prueba ejecutada.** Para `2026-10-25T12:00:00Z`, el parser real acepta `1792929600000`. Reaplicar la conversión de subida al remoto sin decodificar produce `1793907907200000`: rechazo `invalid_progress_batch`. El origen local iOS en segundos Unix produce `2771236800000`, también rechazado. La respuesta real de `listProgress` con una BD simulada devuelve milisegundos Unix. No se ejecutaron los motores Kotlin/Swift en esta sonda.

**Impacto:** restauración y sincronización dejan de converger; una entrada inválida puede impedir aceptar su lote. Cambiar únicamente el decoder no recupera valores que ya se hayan guardado mal.

**Cómo realizar el cambio:**

1. Retomar contrato y política de recuperación de `openspec/changes/audit-progress-sync/`.
2. Mantener una representación explícita en transporte y otra en dominio; convertir una sola vez en cada frontera.
3. Corregir el escritor local iOS, no solamente los clientes HTTP.
4. Añadir recuperación versionada e idempotente, respaldada por evidencia de unidad/época. Aislar valores ambiguos; no dividir cualquier número grande ni truncarlo a «ahora».
5. Registrar diagnósticos acotados sin tokens, payloads completos ni datos personales; permitir que una entrada ambigua no bloquee indefinidamente todo el progreso válido.

**Pruebas de aceptación:** fixture compartida API→nativo→API del mismo instante; lección recién completada en iOS; fechas correctas existentes; valores contaminados identificables y ambiguos; límites temporales; recuperación aplicada dos veces sin cambios adicionales. Verificar persistencia real, no solo un helper de conversión.

**Dependencia:** revisar primero la versión actual del esquema; el plan antiguo contiene instrucciones de migración desactualizadas, detalladas en la sección 6.

### M03. Sincronizaciones antiguas pueden actuar sobre datos nuevos

**Prioridad:** P1 · **Tipo:** concurrencia, identidad y sustitución de datos · **Evidencia:** flujo de código; carrera pendiente de prueba nativa controlada.

**Dónde:** Android `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt:772–785`; iOS `apps/ios/App/ConversationCoordinator.swift:67–83,353–355,578–586`. Referencia de un patrón ya existente: `apps/ios/App/ManagedAccountStore.swift:119–126,199–234` para recuperación de exámenes.

**Qué ocurre.** Se captura la sesión/bearer antes del fetch. Tras esperar la red, se aplica el resultado al almacén vigente y se construye el POST desde su estado vigente. No se conserva un trabajo cancelable ni se compara cuenta/generación antes de cada efecto. En iOS, importar una BD invalida `dataGeneration`, pero esta sincronización no lo consulta.

**Impacto:** una respuesta iniciada antes de una importación puede alterar la nueva BD. Tras cambiar identidad, el trabajo antiguo conserva la credencial anterior y puede combinarla con un snapshot posterior. El efecto remoto depende de que esa credencial siga siendo válida; no se ha demostrado una subida cruzada real ni se presupone que el servidor acepte una sesión revocada.

**Cómo realizar el cambio:**

1. Dar al coordinador propiedad de la tarea y serializar las pasadas.
2. Capturar un token de operación con cuenta y generación de datos, además de la credencial.
3. Cancelar e invalidar al cambiar cuenta o sustituir la BD. La cancelación por sí sola no basta: comprobar vigencia después de cada `await` y justo antes de escribir o enviar.
4. Tomar el snapshot de subida bajo esa misma identidad/generación.
5. Reutilizar el patrón de protección existente en recuperación de exámenes. No reintroducir controles de borrado/exportación que se retiraron deliberadamente.

**Pruebas de aceptación:** fetch suspendido→importar BD→liberar respuesta: no cambia memoria ni BD importada. A→B→liberar respuesta A: no aparece contenido de A ni sale un POST con snapshot B y credencial A. Una pasada nueva de B funciona. Dos sincronizaciones simultáneas no producen escritores competidores. No convertir cancelación en un error genérico reintentable.

### M04. Los topes de lote no permiten recorrer todo el progreso

**Prioridad:** P1 · **Tipo:** protocolo y continuación durable · **Evidencia:** código, función API real y sonda algorítmica.

**Dónde:** Android `apps/android/app/src/main/java/com/jkdc/app/core/ProgressEngine.kt:113–116` y `AppViewModel.kt:777–779`; iOS `apps/ios/Core/ProgressEngine.swift:162–169` y `apps/ios/App/ConversationCoordinator.swift:74–82`; API `services/api/src/progress.ts:76–77`.

**Qué ocurre.** Cada subida selecciona los 200 elementos más recientes del conjunto seleccionado. El ack no cambia el selector ni mantiene avance. La descarga devuelve los 1.000 más recientes de la cuenta, sin continuación. Repetir la operación no significa pasar al siguiente lote.

**Prueba ejecutada:** con 201 identidades de fechas distintas y diez pasadas exitosas del selector, solo aparecen 200; la más antigua nunca se elige. Es una sonda del algoritmo, no una ejecución de Kotlin/Swift. La función real de la API conserva `LIMIT 1000`. La descarga global también hace competir idiomas por ese límite.

**Cómo realizar el cambio:**

1. Implementar el protocolo v2 ya diseñado: orden estable por identidad `(kind, itemID, language)`, no por una fecha que puede cambiar.
2. GET paginado con continuación y validación de cursor; POST con lotes de como máximo 200.
3. Persistir avance por cuenta y dirección; avanzar solo tras ack. La página aplicada y su cursor deben confirmarse atómicamente.
4. Repetir de forma segura una operación con ack incierto y arrancar otra pasada completa al terminar, para converger ante cambios concurrentes.
5. Mantener compatibilidad explícita con clientes/servidores anteriores; no usar fallback silencioso ante cualquier error.

**Pruebas de aceptación:** 201 subidas, 1.001 descargas, idiomas distintos, empates de fecha, interrupción entre envío/ack y ack/commit, fallo de página intermedia, replay y nueva pasada. Afirmar igualdad del conjunto final y ausencia de cruces de cuenta, no únicamente contar peticiones.

### M05. La restauración no converge en puntuaciones y mezcla agregados con eventos

**Prioridad:** P2 · **Tipo:** corrección de merge y mejora de un límite v1 conocido · **Evidencia:** código y aserciones actuales.

**Dónde:** Android `apps/android/app/src/main/java/com/jkdc/app/core/ProgressEngine.kt:61–76,142–155`; iOS `apps/ios/Core/ProgressEngine.swift:77–97,185–194`; tests `apps/ios/Tests/ProgressEngineTests.swift:120–131` y `apps/android/app/src/test/java/com/jkdc/app/core/ProgressEngineTest.kt`.

**Qué ocurre.** Si ya existe una marca listening/reading/ejercicio con 40, recibir un mejor resultado remoto de 90 no la mejora: solo se inserta cuando falta. Otras categorías subidas no se recuperan en ese switch. Para identidades nuevas se crea una marca local desde un agregado remoto, y los cálculos de actividad utilizan esas marcas.

El límite de historial diario de v1 está reconocido en comentarios; no se presenta como una nueva promesa incumplida. El problema práctico es que mejores resultados no convergen y un agregado no permite reconstruir sesiones o días de actividad reales. El test con 40/90 solo verifica que no se duplique la marca, no que el resultado final sea 90.

**Cómo realizar el cambio:** separar rollups remotos por cuenta/idioma/identidad de los eventos locales. Proyectar `min(firstAt)` y `max(lastAt, attempts, bestScore)` para puntuaciones/progreso, sin fabricar `CompletionMark` o sesiones. Aplicar la proyección a lectores de nivel/mastery donde corresponda, conservando actividad/rachas basadas en eventos.

**Pruebas de aceptación:** 40 local +90 remoto y orden inverso→90; replay idempotente; todos los tipos soportados; identidad homónima en otro idioma; recuentos de eventos/sesiones y rachas sin actividad inventada. Los contadores agregados usan el contrato monotónico existente: no sumar intentos de dispositivos si el protocolo solo puede garantizar máximos.

## 4. Conversación y experiencia Android

Las rutas de esta sección parten de `apps/android/app/src/main/java/com/jkdc/app/`, salvo que se indique otra raíz.

### M06. El tema cambia antes de confirmar y el reinicio se solicita demasiado pronto

**Prioridad:** P1 · **Tipo:** confirmación y transición de sesión · **Evidencia:** código.

**Dónde:** `ui/LibraryScreens.kt:131–135`; `AppViewModel.kt:179,1511–1519,1788–1789,1884–1893`; `ui/AppRoot.kt:163–169,197–213`.

**Qué ocurre.** Al tocar un tema se ejecuta `chooseTheme` antes de abrir la confirmación. Con conversación activa, ya cambia registro/título e instrucciones. «Seguir con la actual» solo cierra el diálogo. Confirmar llama a `end()` e intenta iniciar enseguida, pero `isRunning` incluye `closing` y `start()` retorna en ese estado.

**Cómo reproducir:** conversación activa→otro tema→cancelar: inspeccionar tema, título e instrucciones. Repetir confirmando con un transporte fake cuyo cierre tarde: comprobar que se promete iniciar pero no se inicia la nueva sesión.

**Cómo realizar el cambio:**

1. Conservar un `pendingTheme` separado del tema aplicado; no mutar la sesión al abrir el diálogo.
2. Cancelar/dismiss descarta exclusivamente ese pendiente.
3. Confirmar dispara una transición única: cerrar→esperar cierre y liquidación aplicable→aplicar tema→iniciar.
4. Invalidar la transición si cambian cuenta, navegación o intención del usuario. Tratar timeout como estado real, no como excusa para iniciar dos veces.
5. No usar un `delay` fijo para «dar tiempo» al cierre.

**Pruebas de aceptación:** cancelación conserva tema/título/historial y no envía instrucciones; confirmación crea exactamente una sesión; cierre inmediato, tardío y timeout; doble confirmación; navegación/cambio de cuenta. Añadir prueba del coordinador y del diálogo Compose completo.

### M07. Silencio inicial sin límite de memoria

**Prioridad:** P1 · **Tipo:** memoria/rendimiento en captura local · **Evidencia:** código; no se midió un OOM.

**Dónde:** `core/voice/LocalVoiceTransport.kt:310–374`; `AppViewModel.kt:2172–2180`; `apps/android/core/engine-local/src/main/java/com/jkdc/app/engines/local/MicRecorder.kt:105–110,170–173`.

**Qué ocurre.** Cada frame se añade a `speech`. En automático, el límite de 20 segundos solo se aplica si `speechMs > 0`; antes de detectar voz, el silencio se acumula sin alcanzar esa condición. Son aproximadamente **3,84 MB de muestras por minuto** a 16 kHz, mono y float de cuatro bytes, sin overhead. El cierre convierte además el conjunto mediante colecciones intermedias. No se atribuye ese número al consumo total medido de la app.

**Cómo realizar el cambio:** extraer un búfer con límite de muestras, mantener solo preroll acotado antes de voz y limitar el turno activo. Producir el array de STT sin listas intermedias innecesarias. Definir también un límite seguro del modo manual sin convertir ruido/silencio en turnos ficticios.

**Pruebas de aceptación:** extender `apps/android/app/src/test/java/com/jkdc/app/LocalVoiceTransportTest.kt`. El caso actual de silencio comprueba que no hay llamadas al motor, pero no memoria. Inyectar varios minutos de frames nulos/silenciosos y verificar límite retenido; hablar después conserva el comienzo de frase. Cubrir cambio manual/automático, mute y cancelación.

**Verificación física pendiente:** sesión silenciosa prolongada y posterior frase, con perfil de memoria y dispositivo de recursos modestos. No hace falta usar un LLM real para probar el búfer.

### M08. La interrupción por voz no se detecta en la respuesta hablada habitual

**Prioridad:** P2 · **Tipo:** concurrencia y audio · **Evidencia:** estructura de corutinas y cobertura del test existente.

**Dónde:** `core/voice/LocalVoiceTransport.kt:292–335,369–373,491–511,594–616,643–648`; `apps/android/app/src/test/java/com/jkdc/app/LocalVoiceTransportTest.kt:960–998`.

**Qué ocurre.** El mismo bucle que lee micrófono y detecta barge-in espera a `processTurn`, que espera LLM y reproducción TTS. Dejar el micrófono abierto no implica leerlo concurrentemente. El test existente activa la reproducción mediante un comentario escrito, lanzado en otra corutina; no representa voz→respuesta→interrupción.

**Cómo realizar el cambio:** separar captura/detección continua del procesador secuencial de turnos. Una sola autoridad de estado debe decidir cuándo aceptar voz, cancelar reproducción y descartar eco. Mantener trabajos hijos cancelables por sesión y respetar el comportamiento de dispositivos sin AEC. No lanzar una corutina sin límite por frame o por turno.

**Pruebas de aceptación:** usuario habla→fake LLM responde→fake TTS queda bloqueado. Inyectar voz interruptora: `tts.stop()` debe ocurrir antes de liberar artificialmente TTS. Silencio no interrumpe; la voz interruptora no aparece como un turno posterior ficticio; sin AEC se mantiene el comportamiento seguro. Mantener el caso escrito existente como regresión adicional.

**Dependencias:** comparte transporte con M07 y M09. Diseñarlos juntos, con pruebas y entregables separados; no hacer tres refactorizaciones incompatibles del mismo bucle.

### M09. Falta el saludo exacto en el contexto del siguiente turno

**Prioridad:** P2 · **Tipo:** continuidad del tutor · **Evidencia:** omisión comprobada en el historial de requests; no respuesta LLM real.

**Dónde:** `AppViewModel.kt:400–405`; `core/voice/LocalVoiceTransport.kt:213–237,580–590,604–607,643–648`.

**Qué ocurre.** El saludo se genera, muestra y reproduce, pero no se añade a `rollingTurns`. El siguiente prompt incorpora historial inicial y esos turnos, no la pregunta exacta de apertura. El tema general sí se conserva. También hay respuestas de comentarios escritos que no pasan por ese historial.

**Impacto:** «sí», «dos» o «el segundo» pueden llegar al motor sin su antecedente. Repetición de saludo o incoherencia son consecuencias plausibles, no respuestas observadas de un proveedor en esta revisión.

**Cómo realizar el cambio:** una única memoria de contexto para todas las intervenciones aceptadas: apertura, voz y escrito. Añadir cada intervención una vez, con rol explícito si el contrato lo admite, y conservar el límite de memoria. Distinguir el historial visible/persistido del contexto enviado sin permitir que diverjan accidentalmente.

**Pruebas de aceptación:** fake devuelve una pregunta única en el saludo; el siguiente request la contiene exactamente una vez. Repetir escrito→voz, cancelación y truncado de historial. Afirmar contenido/orden/roles, no solo el número de requests.

### M10. El modo manual debería conservar un control explícito de micrófono

**Prioridad:** P2 · **Tipo:** mejora de UX/accesibilidad, no vulnerabilidad demostrada.

**Dónde:** `ui/TalkScreen.kt:263–277,312–331`; `AppViewModel.kt:1712–1716`.

**Qué se puede mejorar.** `beginnerSession` oculta el botón flotante que llama a `toggleMute()`. Quedan controles para terminar turno y sesión, pero no una acción equivalente y visible de silenciar entrada. Existe `toggleVoicePause`, sin caller de UI encontrado; no debe presentarse como una función ya disponible.

**Cómo realizar el cambio:** añadir mute/reanudar independiente en modo manual, conservando sesión y su contexto. Definir y comunicar que «silenciar micrófono» no detiene necesariamente el audio del tutor ni cancela una respuesta pendiente. Una pausa integral sería otra decisión de producto, no un sinónimo a implementar silenciosamente.

**Pruebas de aceptación:** control presente y accesible en manual; estado de mute observable; audio silenciado no llega a STT/LLM ni se envía al reanudar. Strings EN/ES, TalkBack, foco, texto grande y área táctil suficiente; verificar objetivo mínimo de 48 dp para este control.

## 5. Pronunciación, errores y capacidades iOS

Las rutas de esta sección parten de `apps/ios/`.

### M11. Falta el permiso declarado que exige el reconocimiento de voz

**Prioridad:** P1 antes de activar beta · **Tipo:** configuración y terminación de proceso · **Evidencia:** plist fuente, llamada alcanzable y contrato oficial.

**Dónde:** `App/CoursesViews.swift:624–641`; `App/PronunciationCheckController.swift:17–24`; `App/Info.plist:3–30`. La configuración Xcode utiliza ese plist explícito; no se encontró override de la clave Speech.

**Qué ocurre.** El botón «Check» de pronunciación beta solicita autorización de `SFSpeechRecognizer`, pero el plist no contiene `NSSpeechRecognitionUsageDescription`. Sí declara micrófono; son permisos distintos. Apple documenta que la llamada sin la clave de Speech termina la aplicación. No se ejecutó aquí un crash iOS. [Requisito oficial de Apple](https://developer.apple.com/documentation/Speech/asking-permission-to-use-speech-recognition).

**Cómo realizar el cambio:** añadir descripción precisa y localizada, coherente con el procesamiento realmente ofrecido; validar también el plist del bundle construido. Mantener desactivada la evaluación beta hasta probar las rutas de permiso. No afirmar que la falta de permiso afecta a todos los motores de conversación.

**Pruebas de aceptación:** primero test estructural de las claves requeridas; después App/UI en instalación limpia: «Check» muestra la autorización y no termina el proceso. Concedido, denegado, restringido y cambio posterior desde Ajustes. El test de plist fuente no sustituye el bundle final.

### M12. Pronunciación puede quedar bloqueada y no tiene una salida de recursos completa

**Prioridad:** P2 · **Tipo:** máquina de estados, errores, cancelación y audio · **Evidencia:** código; efectos acústicos pendientes de dispositivo.

**Dónde:** `App/PronunciationCheckController.swift:19–24,40–56,66–92`; `App/CoursesViews.swift:571,635–647,687–690`.

**Qué ocurre.** El callback ignora error y retorna sin limpiar si no llega un resultado final. `listeningTarget` impide nuevas evaluaciones mientras tanto. La autorización de Speech se solicita pero no se comprueba su resultado. Además, no se conserva la tarea de reconocimiento ni existe cancelación al salir; se activa `AVAudioSession` sin salida simétrica y se reutiliza un archivo temporal fijo.

**Impacto:** «Listening…» puede persistir sin reintento útil. Navegar puede dejar una operación viva o permitir un resultado fuera de la pantalla. No se ha medido cuánto permanece el indicador de micrófono ni un conflicto acústico concreto.

**Cómo realizar el cambio:**

1. Estados explícitos idle/requestingPermission/recording/recognizing/result/error/unavailable y un identificador por intento.
2. No grabar sin autorización válida; procesar errores y ausencia de resultado final con timeout inyectable.
3. Conservar tareas de grabación/reconocimiento y añadir `cancel()` idempotente.
4. Un único cierre terminal detiene recorder, cancela temporizador/tarea, invalida callbacks y borra el archivo único del intento.
5. Liberar audio solo si el controlador lo adquirió, coordinándose con conversación y TTS. No desactivar unilateralmente una sesión compartida que otro flujo usa.
6. Cancelar en desaparición/background/desactivación; mostrar fallo persistente y reintento, no puntuar un error técnico como si fuera pronunciación incorrecta.

**Pruebas de aceptación:** `(nil,error)`, permiso rechazado, timeout, fallo al crear/grabar archivo, cancelación antes de cuatro segundos, callback tardío y dos intentos sucesivos. Siempre se abandona busy, no se registra un aprobado espurio y la limpieza ocurre una vez. En dispositivo: «Check»→atrás, background y alternancia con conversación/TTS.

**Cobertura necesaria:** los tests actuales de WER/umbral del paquete Core no ejercitan este controlador de App. Extraer estado/decisión puros para tests deterministas e integrar adapters de App mediante un target que realmente los compile.

### M13. No se garantiza la transcripción local prometida

**Prioridad:** P1 antes de activar beta · **Tipo:** privacidad, capacidad y degradación · **Evidencia:** contrato del módulo y configuración ausente; no captura de tráfico.

**Dónde:** `openspec/changes/pronunciation-module/proposal.md`, sección Approach; `App/PronunciationCheckController.swift:5–8,66–74`.

**Qué ocurre.** La propuesta y el comentario dicen on-device. El código solo verifica `isAvailable`; no exige `supportsOnDeviceRecognition` ni configura `requiresOnDeviceRecognition`. El framework dispone de reconocimiento con red, por lo que el código actual no garantiza la propiedad local. **Esto no demuestra que durante esta revisión se haya enviado audio.** [Restricción de procesamiento local](https://developer.apple.com/documentation/speech/sfspeechrecognitionrequest/requiresondevicerecognition), [soporte local del reconocedor](https://developer.apple.com/documentation/speech/sfspeechrecognizer/supportsondevicerecognition).

**Cómo realizar el cambio:** comprobar soporte local y exigirlo en la petición; si no existe o falta un modelo compatible, explicar indisponibilidad y conservar escuchar/repetir sin evaluación. No introducir fallback a nube silencioso. Coordinar el texto del permiso de M11 con esta garantía y el estado unavailable de M12.

**Pruebas de aceptación:** sin soporte local→cero solicitudes de reconocimiento; con soporte→toda solicitud exige modo local; modelo no disponible→salida recuperable y recursos limpios. Verificación física en modo avión con idioma/modelo compatible y comprobación específica de que no se usa fallback remoto.

### M14. El editor escrito se cierra aunque falle el envío

**Prioridad:** P2 · **Tipo:** recuperación de errores y consistencia de mensajes · **Evidencia:** código.

**Dónde:** `App/RootView.swift:256–273`; `App/ConversationCoordinator.swift:1027–1049`.

**Qué ocurre.** La vista espera a `sendTyped` y luego ejecuta `dismiss()` incondicionalmente. El coordinador guarda la intervención antes de pedir respuesta y captura el error internamente, devolviendo `Void`. La UI no puede distinguir éxito de fallo. El borrador deja de estar editable en esa hoja y repetirlo puede crear otra intervención.

**Cómo realizar el cambio:** devolver un resultado tipado o propagar el fallo; mantener texto, foco y hoja ante error. Representar la intervención como pendiente/fallida y reintentar la misma identidad sin volver a insertarla. Conservar tratamiento de autenticación/saldo; vincular operación a la sesión y definir cancelación al cerrar voluntariamente.

**Pruebas de aceptación:** éxito→una intervención y cierre; red/HTTP→hoja abierta, texto intacto, mensaje persistente; reintento→sin duplicados; respuesta antigua después de terminar sesión→sin éxito aparente. Añadir UI para teclado, foco, doble toque y cierre durante petición. No basta probar que el router arroja una excepción.

### M15. Se ofrece búsqueda de temas sin la capacidad de fuentes requerida

**Prioridad:** P2 · **Tipo:** contrato de capacidades y UX · **Evidencia:** código; caso condicionado al motor de tokens y caché vacía.

**Dónde:** `App/LibraryViews.swift:40–42,90,139–171`; `App/HostedSessions.swift:131–141`; `App/ConversationCoordinator.swift:1062–1090`.

**Qué ocurre.** La UI ofrece buscar, pero esa ruta del motor de tokens devuelve siempre `sources: []`; el coordinador rechaza temas nuevos sin fuentes. Se presenta un reintento que sugiere precisar la consulta, aunque cambiar el texto no corrige la capacidad ausente. Una respuesta obtenida puede descartarse por ese motivo; no se ha medido un coste facturado.

**Cómo realizar el cambio:** exponer capacidades del motor y no lanzar búsqueda online sin soporte de fuentes. Explicar el límite y conservar el acceso a temas cacheados válidos. No cambiar a otra credencial/motor de pago ni modificar la seguridad/facturación del proxy para resolver un problema de UI. Implementar búsqueda con fuentes sería una ampliación separada que requiere autorización.

**Pruebas de aceptación:** motor sin fuentes+caché vacía→explicación y cero llamadas; caché válida→acceso conservado; motor con fuentes→flujo existente; error de red en un motor compatible→reintento real, no mensaje de incapacidad.

### M16. La sesión se adopta antes de notificarla, dependiendo de una carga posterior

**Prioridad:** P2 · **Tipo:** consistencia de autenticación · **Evidencia:** código, con mitigación identificada.

**Dónde:** `App/ManagedAccountStore.swift:94–105,279–285`; `App/ConversationCoordinator.swift:255–258,353–355`; `App/HostedSessions.swift:108–118`.

**Qué ocurre.** Exchange y Keychain terminan correctamente, se asigna `session` y después se consulta el perfil. La notificación de cambio solo se emite tras ese fetch. Si `/account` falla, los consumidores de la notificación no reciben la nueva credencial. Iniciar conversación vuelve a adjuntar el bearer y mitiga ese recorrido; el problema no es que todo login o toda conversación fallen.

**Cómo realizar el cambio:** anunciar la adopción después de persistir y asignar la sesión, independientemente del perfil. Tratar perfil como recurso recuperable. Si corresponde invalidar una credencial rechazada, emitir la transición simétrica; un error 500 del perfil no debe equivaler automáticamente a logout.

**Pruebas de aceptación:** exchange correcto+perfil 500→sesión persistida, una adopción notificada, consumidores actualizados y fallo recuperable. Refresh posterior no duplica adopción. Perfil con credencial realmente inválida sigue la política de invalidación y notifica salida. Probar un helper dependiente de bearer antes de comenzar conversación, para no esconder el defecto tras su mitigación.

## 6. Plan de ejecución y dependencias

### 6.1. Reconciliar los planes existentes antes de escribir migraciones

`openspec/changes/audit-progress-sync/` ya describe las causas de M02–M05 y parte de la coordinación de M03. Sus tareas continúan pendientes y el código inspeccionado conserva el mecanismo legacy. Eso no convierte todas sus instrucciones en actuales.

**Desajuste comprobado:** las tareas proponen migrar esquema nativo 2→3 y comprobar «schema-v3», mientras `scripts/check_db_schema.py` y `make check` informan **`user_version 5`** en el checkout actual. No copiar esos números ni crear una migración que retroceda versión.

Antes de ejecutar:

1. Leer proposal/design/specs y contrastar cada tarea con los cambios de recuperación de exámenes ya integrados.
2. Actualizar solo instrucciones obsoletas: versión/estructura actual, siguiente migración disponible, fixtures y archivos reales. Preservar intención de aislamiento, convergencia y no fabricación de eventos.
3. Añadir tablas/campos necesarios sobre el esquema actual y verificar actualización desde todas las versiones todavía soportadas. Regenerar fuentes; no editarlas a mano.
4. Revisar la coordinación con `audit-local-erasure` sin reabrir automáticamente UI de borrado/exportación retirada.
5. Mantener compatibilidad del API público y no asumir que API v2 y `user_version` de SQLite deban tener el mismo número.

### 6.2. Unidades de trabajo recomendadas

Los IDs siguientes son una secuencia propuesta, no tareas ejecutadas. Cada unidad debe tener RED→GREEN con sus fixtures y un resultado verificable antes de continuar. Los cambios significativos mantienen SDD y `verify-report.md`. Los commits, cuando se autorice implementación, serán convencionales, pequeños y sin atribución de IA; no se ha creado ninguno en esta revisión.

| Unidad | Acciones | Cómo abordarla | Dependencias y salida |
|---|---|---|---|
| U1. Historial privado | M01 | Store owner-scoped, VM y refresco Compose | Independiente; fixture A/B/invitado y UI sin restos |
| U2. Frontera temporal | M02 | Reconciliar SDD, contrato, escritores y recuperación | Antes de ejecutar restauraciones masivas sobre datos reales |
| U3. Coordinador vigente | M03 | Tarea única, cuenta+generación, cancelación y comprobaciones | Aplicar antes de ampliar transferencias; conserva recuperación de exámenes |
| U4. Transferencia completa | M04 | API compatible, páginas/lotes y avance durable | U2/U3 y esquema reconciliado; conjunto completo tras interrupciones |
| U5. Proyección convergente | M05 | Rollups separados y lectores de puntuación | Coordinar esquema con U2/U4; no eventos sintéticos |
| U6. Cambio de tema | M06 | Intención pendiente y transición awaitable | Independiente de sync; probar cierre y settlement |
| U7. Captura y voz local | M07–M09 | Búfer acotado, captura concurrente, contexto único | Misma superficie; pruebas independientes y regresiones de voz/escrito |
| U8. Control manual | M10 | Mute visible, semántica y accesibilidad | Mantener garantías de U7; no confundir con pausa integral |
| U9. Pronunciación segura | M11–M13 | Permisos, estados, cancelación y capacidad local | Completar antes de activar beta; verificar App y dispositivo iOS |
| U10. Respuesta recuperable | M14 | Identidad de mensaje, estado y reintento | Añadir pruebas App/UI; conservar saldo y autenticación |
| U11. Capacidades explícitas | M15 | UI y router evitan operaciones imposibles | No requiere ampliar el proxy ni cambiar motor facturable |
| U12. Adopción de sesión | M16 | Separar sesión adoptada de perfil cargado | Coordinar con invalidación U3; probar fallo parcial de login |

**Paralelismo razonable:** Android tema/voz, iOS pronunciación y contrato de progreso pueden prepararse por separado. Dentro de progreso, esquema, API y clientes necesitan un único contrato y orden de compatibilidad. Evitar varios escritores sobre `AppViewModel`, `ConversationCoordinator` o el mismo transporte al mismo tiempo.

**Tamaño/esfuerzo:** U1 y la parte de plist de U9 son acotadas; U2–U5 y el rediseño concurrente de U7 son los bloques de mayor riesgo. No se fija una fecha global: faltan pruebas nuevas, validación de migraciones y dispositivos iOS/Android. La corrección del plist por sí sola no cierra U9.

### 6.3. Verificación durante la implementación

**Orden común:** crear regresión específica→observar RED correcto→cambio mínimo→GREEN→regresiones del flujo→suite aplicable→UI/dispositivo cuando el requisito lo necesita→actualizar evidencia SDD.

Comandos existentes, desde la raíz salvo donde se indique:

```sh
# Contratos, exporters y esquema compartido
make check
python3 scripts/check_db_schema.py

# Android: selección de una regresión ya incorporada al test correspondiente
cd apps/android
./gradlew :app:testDebugUnitTest --tests '*LevelTestAttemptStoreTest'
./gradlew :app:testDebugUnitTest --tests '*LocalVoiceTransportTest'
./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug
cd ../..

# Android UI: solo con dispositivo/emulador de pruebas y datos aislados
cd apps/android && ./gradlew :app:connectedUiTestAndroidTest
cd ../..

# API: cuando cambien contrato/persistencia del progreso, PostgreSQL efímero
make test-api

# iOS: Core con Swift disponible; App/UI en macOS con Xcode y simulador
swift test --package-path apps/ios
make ios-test
```

`--offline` es apropiado cuando las dependencias ya están en caché, como se intentó en esta revisión. No hay que interpretar un fallo de entorno como prueba funcional. Las pruebas Core de Swift no compilan automáticamente `App/PronunciationCheckController.swift`, `ManagedAccountStore` o las hojas SwiftUI: requieren cobertura de App o lógica extraída realmente incluida en un target de tests.

Para las migraciones: fixtures de BD sintéticas, copia aislada, hash/consultas antes y después, prueba de rollback de la transacción y reejecución idempotente. Nunca experimentar con una BD de aprendizaje real.

Para cambios de permisos, persistencia y concurrencia: verificación independiente posterior a las pruebas del implementador, con resultados por requisito. No exigir una ceremonia pesada a un ajuste pasivo de documentación.

## 7. Matriz de pruebas funcionales pendientes

| Flujo | Casos que deben ejecutarse | Evidencia mínima |
|---|---|---|
| Cuenta e historial | A→B, A→invitado, respuesta tardía y reinicio | Consultas/estado solo del propietario y captura de UI sin datos ajenos |
| Progreso | Fechas, más de 200/1.000, replay, varios idiomas | Igualdad del conjunto final y fixture temporal compartida |
| Sustitución de BD | Fetch suspendido durante importación | BD nueva intacta tras liberar respuesta antigua |
| Tema activo | Cancelar, confirmar, cierre lento y timeout | Tema intacto al cancelar; una sola nueva sesión al confirmar |
| Voz local | Silencio largo, voz baja, modo manual/auto, mute | Memoria acotada y ningún turno ficticio |
| Interrupción TTS | Respuesta originada en voz, con/sin AEC | Stop observado antes del fin TTS; sin eco convertido en mensaje |
| Contexto | Saludo→respuesta breve y escrito→voz | Request fake contiene antecedentes una vez y en orden |
| Pronunciación | Permisos, error, timeout, salir, background | Estado recuperable, recursos liberados y ningún aprobado técnico |
| Procesamiento local | Modelo compatible/no compatible, modo avión | Ningún fallback remoto de reconocimiento |
| Respuesta escrita | HTTP/red, reintento, teclado, navegar, doble toque | Borrador conservado, mensaje único y resultado de sesión vigente |
| Capacidades | Tokens sin fuentes, caché, motor con fuentes | Cero calls imposibles y reintento solo cuando tiene sentido |
| Login parcial | Exchange válido y perfil 500/credencial inválida | Adopción e invalidación notificadas sin depender del perfil |

Guardar nuevas evidencias E2E dentro del directorio ignorado del change mediante `E2E_EVIDENCE_DIR`, sin sobrescribir capturas versionadas salvo actualización intencional. Usar cuentas/fixtures sintéticas. No guardar tokens, audio identificable ni transcripciones privadas en informes o logs.

La revisión visual/accesible completa sigue pendiente: TalkBack/VoiceOver, texto al 200 %, contraste, foco, teclado, pantalla pequeña, orientación, safe areas y avisos persistentes. Se recomienda hacerla sobre esos flujos concretos; no se afirma que cada una de esas categorías tenga un defecto actual.

## 8. Comprobaciones realizadas y resultado real

| Comprobación | Resultado | Qué demuestra / qué no |
|---|---|---|
| CodeGraph sync y status | Correctos; índice actualizado | Mapa de fuentes actual antes de inspección; no prueba comportamiento |
| `python3 scripts/check_cross_platform.py` | Exit 0 | Los chequeos de paridad existentes no detectan drift; no prueba todos los flujos |
| `python3 scripts/check_db_schema.py` | Exit 0; `user_version 5`, 17 tablas | Consistencia actual del esquema; no valida una futura migración |
| `make check` | Exit 0; 141 tests Python, exporters y paridad | Conserva contratos existentes; aparecieron `ResourceWarning` de conexiones SQLite no cerradas en tests |
| Sonda Node con `parseProgressBatch`/`listProgress` reales | Reproduce rechazos temporales y respuesta Unix-ms | Parser y serialización reales con BD simulada; no API HTTP/PostgreSQL ni clientes nativos |
| Sonda SQLite del SQL extraído de `LevelTestAttemptStore.list` | Devuelve A, B y NULL para el mismo idioma/nivel | Falta de filtro de cuenta en la consulta actual; no interacción UI ejecutada |
| Sonda del selector de 200 elementos | 200/201 tras diez pasadas | Omisión del algoritmo; no ejecución de los motores Kotlin/Swift |
| Python `plistlib` sobre `App/Info.plist` | Clave Speech ausente; micrófono presente | Configuración fuente incorrecta para esa API; bundle construido pendiente |
| Android `:app:testDebugUnitTest` offline | 712 tests, 89 suites; 0 fallos, errores u omitidos | Tests de la app ejecutados en esta revisión; no incluyen aún las nuevas regresiones propuestas |
| Android `:core:engine-local:testDebugUnitTest` offline | `UP-TO-DATE`; XML existente: 11 tests sin fallos | Gradle aceptó la salida incremental; no se presenta como 11 tests reejecutados |
| Android `:app:lintDebug` offline | Completado; 63 warnings y 21 hints en XML | No hay error bloqueante de lint; no equivale a «sin avisos» ni a ausencia de defectos |
| Android instrumentado/emulador | No ejecutado: no hay dispositivo conectado | Flujos de UI, accesibilidad y audio real pendientes |
| Swift Core, build iOS y UI | No ejecutados: sin Swift/Xcode/macOS | Las conclusiones iOS son estáticas/estructurales, no una suite nativa verde |
| Integración API con PostgreSQL | No ejecutada en esta revisión | Las sondas no sustituyen `make test-api` |

Comando Android completado, desde `apps/android`, con JDK 21 configurado localmente y sin descargas; `BUILD SUCCESSFUL in 5m 47s`:

```sh
./gradlew --offline --console=plain \
  :app:testDebugUnitTest :core:engine-local:testDebugUnitTest :app:lintDebug
```

Los `ResourceWarning` de `make check` no se cuentan como fallo de producto móvil. Conviene corregir el cierre de conexiones en los tests para que futuros avisos sean útiles, pero no desplazan las prioridades P1 de este informe.

El lint también tiene deuda existente: entre los avisos figuran seis `ApplySharedPref`, tres `InlinedApi`, un `InsecureBaseConfiguration` y advertencias de plurales. Es un inventario de herramienta, **no otros 63 defectos confirmados**: comprobar alcance, build variant, guards y supresiones antes de promover cada caso a trabajo. Los warnings de compilación incluyen APIs obsoletas y `createTempDir` en tests. No se creó un APK ni se ejecutó UI instrumentada con este comando.

Artefactos locales de Android: `apps/android/app/build/test-results/testDebugUnitTest/`, `apps/android/core/engine-local/build/test-results/testDebugUnitTest/` y `apps/android/app/build/reports/lint-results-debug.html`. Son salidas ignoradas y no se incorporan a Git. Los logs de la auditoría se guardaron temporalmente como `/tmp/looro-mobile-audit-gradle-20261025.log` y `/tmp/looro-mobile-audit-check-20261025.log`; no son evidencias durables versionadas.

### Aspectos que no deben volver a presentarse como ausentes

- Existe recuperación de exámenes iOS y utiliza comprobaciones de cuenta/operación. El problema de M03 es la sincronización de progreso, no toda la red de iOS.
- La carga de detalle de examen Android ya recibe propietario; M01 no demuestra acceso arbitrario al contenido de intentos ajenos.
- El SDK Android está instalado aunque no haya variables de entorno SDK exportadas; Gradle tiene un JDK configurado. La limitación de UI es que no hay dispositivo conectado.
- La política de datos local y las opciones de borrado/exportación han tenido decisiones previas: este informe no propone reintroducirlas ni repetir hallazgos históricos sin contraste actual.

## 9. Criterio de cierre

Una acción solo se cerrará cuando tenga: **regresión que fallaba antes, corrección observada, comprobación de persistencia/efectos y evidencia de UI cuando corresponda**. Marcar la tarea SDD o pasar la suite existente no basta.

Siguiente paso recomendado: autorizar U1 y la reconciliación de U2/U3, dejando pronunciación beta sin ampliar hasta U9. Priorizar protección y recuperación de lo que el usuario ya ha aprendido antes de añadir nuevas capacidades.

## Key Learnings:

1. La identidad que solicita un historial debe formar parte del predicado SQL y de la invalidación de su estado visible.
2. La convergencia exige representar explícitamente época temporal, avance confirmado y agregados separados de los eventos locales.
3. Un micrófono abierto no permite interrupción si la corutina que lo lee está esperando a que termine TTS.
4. Las pruebas del paquete Swift Core no sustituyen permisos, lifecycle y cobertura de los controladores de App.
