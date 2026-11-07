# Persistencia, recuperación e integración con Words

## 1. Invariantes

1. Una publicación común por idioma/fecha; un intento por owner/idioma/fecha.
2. El intento referencia una versión congelada y no sigue el borrador editorial.
3. Evento, cierre, evaluación e importación son idempotentes en sus respectivos
   ámbitos. No se afirma ejecución exactamente una vez en un proveedor externo.
4. Reservar/reconciliar coste y guardar estado van juntos; no se elimina
   exposición desconocida para desbloquear una pantalla.
5. La importación usa el owner y lenguaje **capturados**, no la selección actual.
6. Words mantiene sus barras, agenda, significado existente y contratos antiguos.

## 2. Modelo PostgreSQL propuesto

Esquema aditivo, con migración numerada usando el siguiente número libre **en el
momento de implementación**. No reservar un número que otro cambio pueda ocupar.
Timestamps `timestamptz`; wire en Unix ms. Constraints y FKs se prueban contra
PostgreSQL real aislado, no solo con mocks.

| Tabla | Columnas principales y constraints |
|---|---|
| `daily_packages` | `id uuid PK`, `language`, `editorial_date date`, `schema_version`, `profile_version`, `state`, `revision bigint`, `public_payload jsonb`, `private_payload jsonb`, `content_hash`, `artifact_hash`, `text_hash`, `audio_id`, `created_at`; inmutabilidad de payload/hash desde ready |
| `daily_publications` | `(language,editorial_date) PK`, `package_id FK`, `revision`, `consumed_at nullable`, `withdrawn_at nullable`, `published_at`; package language/date deben coincidir, una sola publicación |
| `daily_audio` | `id`, `package_id`, `mime_type`, `duration_ms`, `byte_length`, `sha256`, `data bytea`; máximo 3 MiB, nunca URL de descarga arbitraria |
| `daily_attempts` | `id uuid PK`, `account_id FK`, `package_id FK`, `language`, `editorial_date`, `attempt_number`, `explanation_language`, `daily_consent_version`, `state`, `revision`, `started_at`, `stage_state jsonb`, `active_ms`, `stop_reason`, `review_reservation_id`; `attempt_number` and UNIQUE `(account_id,language,editorial_date,attempt_number)`; enforce configurable per-day cap (1 in v1) transactionally |
| `daily_attempt_events` | `(attempt_id,event_id) PK`, `request_hash`, `expected_revision`, `accepted_revision`, `event_type`, `body jsonb`, `response jsonb`, `created_at`; FK attempt con borrado dependiente |
| `daily_voice_segments` | `id`, `attempt_id`, `hosted_session_id UNIQUE FK`, `sequence`, `state`, `consumed_ms`; UNIQUE parcial para un segmento activo por intento, y UNIQUE `(attempt_id,sequence)` |
| `daily_review_inputs` | `attempt_id PK FK`, `input_hash`, `snapshot jsonb`, `frozen_at`; una escritura al cerrar, inmutable mientras se conserva |
| `daily_reviews` | `id`, `attempt_id UNIQUE FK`, `input_hash`, `rubric_version`, `result jsonb`, `created_at`; resultado admitido inmutable |
| `daily_jobs` | `id uuid PK` (mismo valor que `runId`), `kind=content|review`, binding a idioma/fecha o attempt, `state`, `revision`, `lease_owner`, `lease_until`, `config_snapshot`, `created_at`, `updated_at`; un job no terminal por binding |
| `daily_provider_operations` | `id`, `job_id FK`, `phase=generate|judge|tts|audio_check|review`, `dispatch_key UNIQUE`, `state=reserved|sending|succeeded|failed|unknown`, `reservation_id`, `external_reference`, `result_hash`, `usage`, `sent_at`; no prompts crudos ni secretos aquí |
| `daily_cost_reservations` | `id`, `cost_center=content|review`, `budget_date`, `binding_id`, `max_nano_usd bigint`, `settled_nano_usd bigint nullable`, `state=held|settled|released|unknown`; no importes float |
| `daily_request_keys` | `(account_id,operation,idempotency_key) PK`, `request_hash`, `http_status`, `response jsonb`, `created_at`; no respuesta con ownership ajeno |
| `daily_config` | fila versionada, flags, idiomas, presupuestos y referencias de proveedor sin duplicar claves cifradas existentes |

Las garantías cross-table (lenguaje/fecha/package, ownership de voz) se implementan
con transacción y constraints compuestas/trigger cuando una FK simple no basta.
No confiar solo en checks del navegador. `revision` se transmite como entero
seguro ≤2^53−1; rechazar overflow, no convertir BigInt silenciosamente.

## 3. Transacciones y recuperación

### Crear intento

1. Autenticar y comprobar consentimiento/entitlement hospedado existente.
2. Resolver replay por clave y hash antes de comprobar fecha actual.
3. Bloquear publicación, verificar fecha/idioma/estado y slot.
4. Si existe intento del día, devolver su ID canónico sin nueva reserva.
5. Bloquear fila de presupuesto de revisión; reservar la cota acotada.
6. Insertar intento, fijar `consumed_at`, registrar respuesta idempotente y commit.

La reserva es capacidad operativa, no un débito nuevo de saldo del usuario. Todas
las reservas simultáneas compiten sobre presupuesto compartido en BD; contador
en memoria por réplica no es control suficiente.

### Aceptar evento

Bloquear intento propio → verificar replay → comprobar estado/plazo/CAS → validar
el evento completo → guardar evento y efectos → incrementar revisión → guardar
respuesta de replay → commit. Rechazo de cualquiera de estos pasos deja sin
cambios revisión, respuestas, coste y publicación.

### Finalizar y ejecutar revisión

Un mismo commit fija snapshot/inputHash y job queued, sin hacer HTTP al proveedor
dentro de la transacción. Worker reclama lease y persiste operación `sending`
**antes** de enviar. Finaliza resultado validado+estado+coste en otro commit.

Si el proceso cae:

| Punto observado | Recuperación |
|---|---|
| Antes de commit de cierre | Repetir evento con mismo ID |
| Cierre confirmado, job queued | Otro worker lo reclama al recuperar lease |
| Operación reservada sin posibilidad de envío | Reencolar operación sin nueva reserva |
| Operación sending sin resultado durable | unknown; no reenvío automático |
| Resultado durable y cliente perdió HTTP | GET devuelve el mismo reviewId y candidatos |
| Retirada mientras evalúa evidencia previa | Guardar revisión parcial de lo ya observado; no abrir nueva voz |

Separar las operaciones externas del job para no repetir generación porque falló
TTS o repetir una evaluación porque falló su notificación. Si el resultado del
proveedor se recibió pero no llegó a persistirse, también es unknown salvo que
el adaptador tenga recuperación verificable. El job no puede declarar éxito
solamente por recordar una respuesta que ya se perdió.

## 4. Modelo móvil propuesto

Añadir a `shared/db/schema.sql` la siguiente migración disponible y actualizar
los dos consumidores mediante el mecanismo de schema existente. No asumir que
`user_version` sigue siendo el indicado en documentación histórica.

| Tabla local nueva | Identidad y propósito |
|---|---|
| `daily_attempt_cache` | `(owner_key,attempt_id) PK`; package snapshot público, state/revision, etapa local, contadores y generación de restore |
| `daily_outbox` | `(owner_key,event_id) PK`; intención `{schemaVersion,event}` inmutable, hash, expected_revision de envío, attempt, orden y estado; UUID guardado antes de petición |
| `daily_review_cache` | `(owner_key,review_id) PK`; DTO validado y snapshot persistido sin expiry temporal |
| `daily_word_imports` | `(owner_key,origin_key) PK`; language, expression_key, kind `word|expression`, review_id nullable, action `imported|existing|suppressed|pending_capacity`, fecha y versión de importador |
| `daily_word_suppressions` | `(owner_key,language_id,norm_key) PK`; borrado explícito que impide reimportación automática |

El core puro decide acciones; la capa de persistencia ejecuta expresión+recibo
en **una transacción SQLite** dentro del single writer existente. No considerar
éxito una actualización del objeto Archive que todavía falló al guardar.
El upsert diario recibe languageID explícito. No cambiar temporalmente la
preferencia global para reutilizar saveLookup: extraer un helper explícito por
idioma manteniendo la función histórica como wrapper de su comportamiento actual.

La cola se procesa solo con owner cargado y auth correspondiente. Cambiar de
cuenta/idioma/restore invalida tareas de UI mediante la generación existente;
no reasigna registros pendientes al nuevo owner. Una respuesta de A que llega
viendo B no escribe en B, no muestra un banner de A y no expone su texto.

### Conflicto confirmado frente a resultado desconocido

La intención de outbox (respuestas, IDs, activeMs) es inmutable; la precondición
expectedRevision es metadata separada de envío. El hash del servidor excluye esa
precondición. Estados locales: `pending → sending → acknowledged`; timeout pasa
a `outcome_unknown`, 409 confirmado a `revision_conflict`.

- **Timeout/resultado desconocido:** no rebase, no nuevo ID, no nuevo payload.
  Repetir exactamente el envelope enviado hasta obtener replay/409 o consultar
  el estado. Nunca deducir de una UI desactualizada que la operación fracasó.
- **409 confirmado:** descargar AttemptView actualizado. Si owner/paquete siguen
  iguales, intento abierto, etapa aún no enviada/omitida y fragmentos/marcas no
  colisionan, persistir nueva expectedRevision y reintentar **una vez** el mismo
  eventId/intención. Un cambio independiente como marcar una palabra no bloquea
  para siempre el envío de listening. activeMs permanece igual y solo se suma
  cuando se acepta el evento, nunca durante el rebase.
- Si esa etapa ya se resolvió desde otro dispositivo, no sobrescribir ni crear
  otra entrega. Mostrar «Esta actividad se ha actualizado en otro dispositivo»;
  conservar la respuesta local como borrador de consulta y ofrecer continuar con
  estado del servidor. Evento pasa a `superseded`, no a acknowledged ni saved.
- Segundo conflicto, cierre/caducidad, IDs incompatibles o cambio de contenido:
  `needs_attention`, sin bucle automático. El usuario consulta estado/continúa;
  corregir una intención inválida exige una acción nueva real y su nuevo ID, no
  camuflar una petición antigua como reintento.

El reloj activo local sigue contando la práctica real incluso si un borrador
queda superseded; no se suma al servidor una etapa descartada para inventar
evidencia. El contador remoto es suma de eventos aceptados, no medida antifraude.

## 5. Importación automática a Words

### Qué significa «añadir palabras» en el modelo actual

Las palabras de conversación se derivan de assessments; no hay una tabla `words`
para insertar resultados arbitrarios. Un término de una palabra o una expresión
del diario se guarda en la **colección de expresiones que ya se muestra en
Words**, mediante el upsert existente, con `source="lookup"`.

No fabricar un assessment ni marcar una palabra como dominada por haberla
guardado. No duplicar el mismo término también en la proyección de palabras.
`kind=word|expression` se conserva en el recibo/origen diario para presentación,
sin ampliar el enum de backups de ExpressionState.

### Algoritmo normativo

1. Validar resultado, schema, owner original, idioma, reviewId e inputHash del
   intento cacheado. Un resultado ajeno/desconocido no se importa automáticamente.
2. Seleccionar candidatos `supported` del resultado y las marcas del alumno.
   No importar observaciones inciertas ni las 5 expresiones objetivo solo por
   haber sido presentadas; deben haberse marcado o tener evidencia de dificultad.
3. `expressionKey = languageID + '|' + trim(text).lowercase()` usando exactamente
   las reglas actuales de RecallEngine, no añadir stemming ni colapsar espacios
   interiores. Probar casos Unicode en ambos cores; no cambiar la identidad
   compartida para introducir este modo.
4. `originKey = 'mark:' + markId` para aportes manuales; para candidatos de revisión,
   `'review:' + reviewId + ':' + candidateId`. La revisión devuelve `markId` como
   candidateId de una marca, para conservar el mismo origen al fusionarla.
5. Si ya hay recibo terminal (`imported`, `existing` o `suppressed`), no hacer nada.
   Si hay supresión por clave, guardar recibo suppressed sin resucitar el término.
6. Si existe expresión, conservar barras, attempts, lastSeen, dueAt y significado
   no vacío; rellenar significado solo si vacío. Adoptar legacy null-language
   exactamente como el DAO existente, sin mover una expresión de otro idioma.
7. Si es nueva y hay capacidad (<2.000 expresiones según regla actual), insertar
   con barras/attempts 0 y dueAt inmediato usando epoch local correcto.
8. Si está lleno, no eliminar la más antigua ni mentir sobre guardado. Recibo
   `pending_capacity`, aviso persistente «No se han guardado todos los términos»
   con contador y acción de reintento después de liberar espacio.
9. Commit atómico de cada importación y recibo. La UI muestra guardado real, no
   simplemente «revisión lista». Caída antes de commit permite repetir sin pérdida.

El significado del término importado usa explanationLanguage congelado. Una
entrada existente mantiene su significado incluso si se revisa luego en otro
idioma de ayuda. No hacer traducciones facturables al abrir Words.

### Aportes manuales y borrado

El usuario puede marcar una expresión objetivo o escribir un término durante la
sesión. Una entrada manual exige text y meaning no vacíos; si no conoce meaning,
puede marcar una expresión ya preparada o pedir la ayuda existente bajo sus
gates, pero no se inventa una traducción. Guardar localmente al confirmar y
encolar `word_marked` si el intento sigue abierto; no esperar a la IA para
conservar un aporte manual. El mismo markId une recibo local y candidato final.

Eliminar una expresión en Words añade supresión para la clave **en la misma
transacción** que el borrado. Releer review, reintentar red o recibirlo en otra
pantalla no la recupera. Una acción manual explícita «Guardar de nuevo» puede
limpiar la supresión; la importación automática nunca puede hacerlo.

En otro dispositivo del mismo owner puede importarse el resultado allí: no hay
recibo global que impida que llegue a su Words local. El borrado del dispositivo
A no se sincroniza a B porque la sincronización general de Words sigue fuera de
alcance. Explicar esta limitación, no prometer una biblioteca cloud nueva.

### Backup y restauración

Extender Archive de forma aditiva y opcional con `dailyImportMetadata` versionado:
recibos y supresiones de procedencia diaria, sujetos al owner y sin secretos.
Snapshots de resultados/transcripciones no van al backup por defecto. Archivos
antiguos sin metadata siguen decodificando; campos opcionales espejo Kotlin/Swift
y fixture nueva prueban ida/vuelta y el backup histórico.

Los recibos se conservan 91 días; las supresiones hasta guardado manual explícito
o borrado de los datos del owner. No reabrir autoimportados tras borrado explícito mediante backup histórico; resultados siguen disponibles por el historial del intento. Validar cap de 4.096 recibos por
owner y 2.000 supresiones; si se alcanza el de supresiones, pausar futuras
importaciones automáticas con aviso, nunca olvidar un borrado silenciosamente.

Al restaurar backup sin metadata, no aplicar automáticamente resultados
históricos ya completados: ofrecer aplicación explícita con preview. Al restaurar
uno con metadata del mismo owner, recuperar recibos y supresiones antes de
procesar resultados. No trasladar metadata de otro owner por inferencia; seguir
la política explícita de importación de backups existente. Las expresiones del
backup conservan su comportamiento previo.

## 6. Privacidad y retención propuestas

Este diseño introduce almacenamiento de texto que antes no existía en API.
Debe aparecer en el aviso de consentimiento del modo diario **antes** del primer
envío: finalidad formativa, envío a proveedor configurado y plazos. No asumir que
el consentimiento de voz previo cubre automáticamente la nueva retención.

| Datos | Política propuesta |
|---|---|
| Audio del alumno | No se persiste en API diaria; transporte hospedado conserva su política vigente |
| Intento activo | Persistir estado, texto y evidencia para retomar sin caducidad temporal hasta finalización o borrado de cuenta/datos, sujeto al aviso de privacidad aprobado. |
| Review, candidatos e historial legible | Consultables tras finalizar, sin caducidad temporal; se eliminan en borrado de cuenta/datos conforme al ciclo de vida aprobado. |
| Cache/outbox móvil | Conservar el único estado necesario para reanudar hasta sincronización confirmada; no borrar si la aplicación se cierra. |
| Words guardado | Conservación local existente hasta borrado del usuario; no se elimina al expirar la revisión |
| Paquete público | Retener al menos mientras lo referencien intentos conservados; audio sin referencias puede purgarse tras 365 días |
| Auditoría/costes | Solo metadatos operativos mínimos bajo política vigente, sin transcript ni contenido personal |

La retención detallada de transcript, intentos y revisiones debe ser coherente
con la promesa de poder retomarlos y consultarlos. No establecer expiración
automática por duración o fecha. Confirmar los plazos en el aviso y purgar por
borrado de cuenta/datos o según la política de privacidad aprobada, preservando
separada la evidencia financiera mínima requerida por la política existente.

La revisión puede citar partes del transcript; no anunciar plazos incompatibles
entre el intento y la revisión. Las supresiones/recibos locales también
se borran al borrar datos del owner. Eliminar cuenta revoca jobs pendientes,
impide nuevas llamadas y borra datos diarios privados; los costes auditables
retienen solo referencia operacional no identificable según el patrón existente.
Un worker que vuelve tras borrado no puede recrear el owner ni su resultado.

El panel editorial no muestra estos datos privados. El operador puede ver estado
de job y referencias opacas para reconciliar, nunca prompts/respuestas crudas en
una tabla genérica. Revisar también el table browser admin: tablas de evidencia
diaria deben quedar fuera de su inventario visible, no solo fuera de la nueva UI.
