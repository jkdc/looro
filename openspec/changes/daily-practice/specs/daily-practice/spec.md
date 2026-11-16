# Delta Spec: daily-practice

## ADDED Requirements

### Requirement: Shared daily package

El sistema SHALL ofrecer una única publicación por idioma y fecha editorial UTC,
idéntica para todos los alumnos. Solo inglés estará habilitado al lanzamiento.
La generación SHALL excluir información de alumnos y el paquete SHALL contener
topic, objetivo, preparación, listening, briefing de speaking, práctica y ayudas.

#### Scenario: Two learners receive the same package
- **GIVEN** dos cuentas con distinto historial y nivel declarado
- **WHEN** consultan el paquete de inglés en la misma fecha UTC
- **THEN** reciben el mismo packageVersionId, contentHash, preguntas y ayudas
- **AND** ningún historial modifica el paquete ni el briefing de speaking.

#### Scenario: Unsupported language is not silently substituted
- **WHEN** se solicita un idioma válido no habilitado
- **THEN** se devuelve daily_language_not_enabled sin contenido inglés de fallback.

#### Scenario: Local listening voice is unavailable
- **GIVEN** el dispositivo no dispone de una voz para el `speechLocale` publicado
- **WHEN** el alumno inicia la reproducción del transcript
- **THEN** la app no selecciona silenciosamente una voz de otro idioma
- **AND** ofrece reintentar tras habilitar/instalar la voz o saltar listening como parcial con `local_tts_unavailable`.

### Requirement: Frozen and validated publication

La publicación MUST referenciar contenido privado validado y juez independiente
con pass sobre texto/claves exactos y locale de voz conforme al perfil. Un hash desactualizado,
fail, uncertain, job unknown o borrador SHALL NOT ser publicable. Una publicación
consumida SHALL NOT cambiar de versión; retirada impide nueva práctica oral.

#### Scenario: Modified answer invalidates publication evidence
- **GIVEN** un paquete ready con juez pass
- **WHEN** cambia una respuesta aceptada sin repetir revisión
- **THEN** publicar falla y la publicación anterior permanece intacta.

#### Scenario: First attempt freezes the common publication
- **GIVEN** una publicación y un intento admitido
- **WHEN** un operador intenta sustituirla por otro paquete del mismo día
- **THEN** la API rechaza la sustitución sin cambiar el snapshot del alumno.

#### Scenario: Generation outcome is unknown
- **WHEN** se pierde la respuesta del proveedor después de un posible envío
- **THEN** el job queda unknown, conserva exposición y no reenvía automáticamente.

### Requirement: Common challenge with optional scaffolds

El paquete SHALL proponer reto B1–B2 orientativo con ayudas comunes a demanda.
Usar ayudas SHALL NOT sustituir preguntas, cambiar metas o generar temario por
nivel. La revisión MUST distinguir práctica asistida de evidencia independiente.

#### Scenario: Beginner requests a sentence starter
- **WHEN** el alumno abre un inicio de frase de una meta de speaking
- **THEN** recibe la ayuda común, se registra su uso y conserva objetivo y paquete.

#### Scenario: Assisted answer is not certified mastery
- **GIVEN** transcripción o ejemplos consultados antes de responder
- **WHEN** se genera revisión
- **THEN** la sección indica helped y no atribuye certificación ni cambio CEFR.

### Requirement: Bounded daily experience

La sesión SHOULD rondar 15–20 min, sin temporizador, caducidad ni cierre forzado.
Durar más no SHALL interrumpir ni quitar acceso. Se permite guardar en cualquier
etapa, cerrar la app, reanudar el mismo intento y consultar su resultado más
tarde. Duración real se valida con piloto; los límites de conexión son solo
técnicos y no cierran el intento.

#### Scenario: Practice exceeds the estimate
- **WHEN** el alumno supera 20 minutos sin terminar
- **THEN** continúa normalmente, sin aviso de expiración, corte ni cambio de estado.

#### Scenario: Review is not immediately available
- **WHEN** pasan 15 s de espera de revisión
- **THEN** puede salir con aviso pendiente y volver al mismo intento sin otra evaluación.

### Requirement: Daily speaking is bound to the topic

La conversación diaria MUST resolverse desde el intento propio y paquete
congelado. SHALL usar role, anchors, apertura y expresiones comunes, sin importar
focus, reto ni errores personales de otros modos. Speaking SHALL sentirse como conversación bidireccional natural centrada en el topic,
con inicio orientador y cierre elegible, sin guion de preguntas prefijadas,
conteo de turnos ni corte por duración. No corregir interrumpiendo; guardar
evidencia y dar feedback al final. Renovar transporte no crea intento nuevo.

#### Scenario: User starts the prepared conversation
- **GIVEN** listening enviado y speaking hospedado permitido
- **WHEN** crea sesión con dailyAttemptId propio
- **THEN** la API construye contexto del topic y aplica gates de consentimiento y consumo existentes.

#### Scenario: Arbitrary context is supplied with a daily binding
- **WHEN** se añade instructions o history de cliente a una creación diaria
- **THEN** se rechaza invalid_daily_context y no hay llamada al proveedor.

#### Scenario: Existing conversation has no daily binding
- **WHEN** se crea una conversación histórica sin dailyAttemptId
- **THEN** conserva su validación, contexto, consumo y resultados previos.

### Requirement: Durable private attempts

La API SHALL identificar owner desde bearer, admitir invitados conforme a gates
hospedados existentes y aislar datos entre cuentas. Intentos SHALL fijar paquete, idioma, explicación, cupo e historial; no llevan deadline ni caducidad temporal. Escrituras SHALL usar eventos idempotentes
y CAS; todo rechazo SHALL dejar los datos y reservas sin cambios.

#### Scenario: Duplicate close after a timeout
- **WHEN** se repite el mismo evento finished con el mismo body
- **THEN** se devuelve la respuesta original y existe un solo snapshot/job/reserva.

#### Scenario: Same ID with different payload
- **WHEN** se repite un eventId con respuestas diferentes
- **THEN** se rechaza idempotency_conflict sin mutación.

#### Scenario: Daily attempt cap is one but configurable
- **GIVEN** maxAttemptsPerDay es 1 en v1
- **WHEN** el usuario solicita otro intento para ese día
- **THEN** se recupera el intento existente o se informa que su cupo ya está usado; no se reinicia.
- **AND** aumentar el límite futuro permite attemptNumber sucesivos sin sustituir los registros anteriores.

#### Scenario: Two devices race to save a stage
- **GIVEN** ambos conocen la misma revision
- **WHEN** envían eventos distintos para avanzar una etapa
- **THEN** uno avanza y el otro recibe revision_conflict sin sobrescribirlo.

#### Scenario: Independent word mark advances the revision
- **GIVEN** listening aún abierto y un 409 confirmado porque otro dispositivo marcó una palabra
- **WHEN** se descarga el estado y verifica compatibilidad
- **THEN** se reenvía una vez la misma intención/eventId con nueva precondición, sin duplicar activeMs
- **AND** un timeout de resultado desconocido no permite ese rebase.

#### Scenario: Learner chooses to skip practice after speaking
- **GIVEN** hay segmentos de speaking aceptados y el alumno elige omitir práctica
- **WHEN** se omite práctica y se finaliza
- **THEN** speaking queda sellado con su evidencia, práctica omitida y resultado parcial.

#### Scenario: Another account guesses an attempt ID
- **WHEN** intenta leerlo o enviar eventos
- **THEN** recibe not_found, sin texto, estado, review ni efectos del owner original.

#### Scenario: Unexpected application close
- **WHEN** el alumno reabre la aplicación después de enviar una respuesta o mensaje
- **THEN** recupera el mismo intento, topic, conversación y última respuesta confirmada.
- **AND** no pierde progreso, duplica eventos ni consume un segundo intento.

### Requirement: Evidence-based persistent review

La revisión SHALL combinar corrección cerrada de listening/práctica con análisis
IA de evidencia enviada. SHALL persistirse y ser recuperable sin depender de la
ventana de 120 s de helpers. Citas MUST existir exactamente en las fuentes del
alumno. Transcripción dudosa SHALL NOT justificar error firme ni evaluación
acústica. Completed no significa aprobado ni dominio CEFR.

#### Scenario: Evaluator invents a learner quote
- **WHEN** devuelve una cita inexistente o solo pronunciada por el tutor
- **THEN** se descarta esa observación y sus candidatos dependientes sin fabricar evidencia.

#### Scenario: Not enough oral evidence
- **GIVEN** menos de dos fragmentos learner no vacíos
- **WHEN** se finaliza
- **THEN** speaking queda not_observed y solo se revisa lo efectivamente observado.

#### Scenario: Server restarts after evaluation dispatch
- **WHEN** no puede demostrar resultado persistido o fallo pre-envío
- **THEN** muestra review_unknown y no repite proveedor ni declara éxito.

### Requirement: Daily vocabulary reaches existing Words

Los candidatos con evidencia suficiente y aportes manuales SHALL guardarse en
Words mediante expresiones existentes con source lookup. La importación MUST ser
atómica e idempotente, preservar progreso/significado y respetar owner/idioma.
Eliminar un término SHALL impedir su resurrección automática por replay.

#### Scenario: Review is fetched multiple times
- **WHEN** el mismo reviewId/candidateId se procesa repetidamente
- **THEN** hay una sola expresión, un recibo terminal y no cambian barras ni dueAt previos.

#### Scenario: Saved expression already exists
- **GIVEN** misma clave con significado y progreso
- **WHEN** un candidato diario coincide
- **THEN** no duplica ni reinicia repaso ni reemplaza el significado no vacío.

#### Scenario: Notebook is full
- **GIVEN** 2.000 expresiones guardadas
- **WHEN** llega un candidato nuevo
- **THEN** no se elimina ninguna; queda pending_capacity con aviso y reintento explícito.

#### Scenario: A deleted expression appears in a replayed result
- **WHEN** se vuelve a procesar el candidato eliminado
- **THEN** el tombstone impide reinsertarlo; solo un guardado manual explícito puede recuperarlo.

#### Scenario: User switches account while review returns
- **WHEN** una respuesta de A llega después de cargar B
- **THEN** no se muestra ni escribe en B, ni se toma el idioma de B para el resultado de A.

#### Scenario: Old backup is restored
- **GIVEN** un archivo sin metadata diaria
- **WHEN** se restaura y consulta historial remoto
- **THEN** decodifica sin error y no reimporta resultados históricos automáticamente.

### Requirement: Safe editorial administration

El panel SHALL permitir configurar, generar, observar run propio, previsualizar,
publicar/rechazar y retirar, con auth/auditoría existentes. SHALL mostrar errores
persistentes y proteger contra respuestas tardías y acciones sobre cambios no
guardados. Los datos personales nuevos SHALL quedar fuera del table browser.

#### Scenario: Operator changes date while a job response is late
- **WHEN** llega respuesta de un run de la fecha anterior
- **THEN** no reemplaza la vista actual ni su estado; se conserva asociación con runId.

#### Scenario: Publication fails over HTTP
- **WHEN** la API devuelve error o timeout al publicar
- **THEN** el panel conserva preview, muestra error junto a acción y reconcilia por clave antes de repetir.

### Requirement: Cost, privacy and lifecycle containment

El sistema MUST reservar presupuesto antes de enviar a proveedores, separar gasto
común de revisión y respetar las tarifas/gates existentes de voz. SHALL advertir
la nueva retención de texto, purgar según política propuesta y no registrar PII.
Apagar nuevos intentos SHALL NOT impedir recuperar los ya admitidos.

#### Scenario: Budget is exhausted
- **WHEN** dos admisiones compiten por la última reserva disponible
- **THEN** solo cabe la permitida por presupuesto; el rechazo no deja intento ni cargo huérfano.

#### Scenario: Account is deleted during a running review
- **WHEN** vuelve la respuesta del proveedor
- **THEN** no recrea cuenta ni contenido, y se reconcilia únicamente metadato operativo de coste.

#### Scenario: Learner returns weeks after completion
- **WHEN** vuelve al mismo intento semanas después
- **THEN** consulta el mismo resultado sin un nuevo intento o llamada al proveedor.

### Requirement: Explicit language onboarding

El motor SHALL consumir perfiles versionados sin reglas inglesas globales.
Activar idioma nuevo MUST exigir perfil, validación/juez, ayudas, locale BCP-47
compatible con síntesis local y pruebas
propias; un idioma no habilitado SHALL NOT entrar por fallback al inglés.

#### Scenario: Profile exists but language is not enabled
- **WHEN** un operador o cliente solicita publicarlo sin activación validada
- **THEN** generación/publicación rechazan la combinación sin afectar inglés ni exámenes históricos.
