# Contratos propuestos: contenido, intentos y administración

**Contrato parcialmente aprobado (2026-11-08).** El usuario aprobó listening con
TTS local y generación/revisión diaria mediante el proveedor LLM ya configurado
para exámenes, bajo presupuesto operativo acotado, sin cobro al alumno y apagado
por defecto. Esto autoriza implementar soporte, pero no llamadas reales, activación
o despliegue. Los endpoints existentes conservan sus contratos. Añadir esquemas
versionados y fixtures en `shared/contracts/daily-practice/` durante la
implementación, no generar modelos por copiar este Markdown sin pruebas.

## 1. Convenciones de wire

- JSON con `schemaVersion: 1`; IDs UUID para versiones/intentos/jobs/eventos.
  IDs editoriales internos (`questionId`, `goalId`) ASCII `[a-z0-9_-]{1,64}`.
- Fechas editoriales ISO `YYYY-MM-DD` UTC, timestamps enteros Unix **milisegundos**,
  duraciones enteras milisegundos. Conversión explícita con el epoch Apple en
  registros locales existentes; no pasar milisegundos a `RecallEngine`.
- Importes nanoUSD en strings decimales no negativos, sin notación exponencial.
- `additionalProperties: false` en bodies y subobjetos. Rechazar enums/IDs
  desconocidos, duplicados, NUL, caracteres de control no permitidos y cuerpos
  sobredimensionados; no normalizar silenciosamente claves de objeto.
- Longitudes humanas se cuentan en codepoints Unicode; límites de transporte
  en bytes UTF-8. Nunca truncar a mitad de codepoint ni evaluar evidencia truncada.
- Errores con el envelope existente `{error:{code}}`; 5xx añaden `requestId`.
  Detalle de conflicto seguro: cabecera `X-Daily-Revision`, no datos de otro owner.
- Bodies privados y resultados: `Cache-Control: private, no-store`. Logs nunca
  incluyen prompts, respuestas, claves de contenido, tokens ni palabras guardadas.
- Las respuestas nuevas añaden campos opcionales solo subiendo el esquema si el
  consumidor es cerrado. Kotlin/Swift usan versiones explícitas y rechazo legible
  de esquemas no soportados, no una pantalla vacía.

## 2. Paquete

### Proyección pública `DailyPackage`

Todos los campos de la tabla son obligatorios. Las listas opcionales en contenido
se representan vacías, no omitiendo claves. Este DTO **no contiene claves de
respuesta, evidencia de juez, credenciales ni instrucciones de administración**.

| Campo | Tipo / límite |
|---|---|
| `schemaVersion` | literal `1` |
| `packageVersionId`, `contentHash` | UUID y SHA-256 hexadecimal de 64 caracteres |
| `editorialDate`, `language`, `profileVersion` | Fecha UTC, `en` habilitado, versión de perfil |
| `topic` | `{id,title,summary}`; title ≤120, summary ≤400 codepoints |
| `objective` | String ≤300 codepoints |
| `challengeReference` | literal `B1-B2`; no cambia por alumno |
| `estimatedMinutes` | `{preparation:2,listening:4,speaking:6,practice:3,review:2,total:17}` informational, never enforced |
| `expressions` | Exactamente 5 `{id,text,meanings,examples}`; text ≤120; meanings/examples con claves `en` y `es`, valores ≤300 |
| `listening` | `{speechLocale,transcript,questions}`; locale BCP-47 del perfil; transcript ≤1.500 codepoints; exactamente 3 preguntas |
| `speaking` | `{role,opening,topicAnchors,closingCue}`; role/opening/closing ≤400 codepoints; up to 3 non-interrogative topic anchors; no fixed turn/question count |
| `practice` | `{questions}`; exactamente 4 preguntas, 2 `single_choice` y 2 `gap_fill` |
| `supportLocales` | `['en','es']` |

`speechLocale` es el locale BCP-47 congelado por el perfil (por ejemplo,
`en-US`). La app sintetiza `transcript` localmente con el TTS de plataforma que
ya utiliza para los listenings. No se incluye ni persiste un archivo de audio,
URL, duración exacta o checksum en el paquete; el mismo contenido y `contentHash`
se conserva entre alumnos, aunque la voz, velocidad y duración audible varíen
entre dispositivos. No usar una voz de otro idioma silenciosamente si falta la
voz solicitada.

Pregunta pública: `{id,kind,prompt,options,hints}`. Prompt ≤400; `options` contiene
4 `{id,text}` para elección y `[]` para hueco; text ≤200; hints `{en,es}` ≤300.
Cada meta: `{id,task,hints,sentenceStarters,exampleResponses}`; task ≤300,
ayudas por locale ≤400. El ejemplo enseña, no se cuenta como producción propia.

Artefacto privado v1 tiene `listening` y `practice`, ambos arrays con exactamente una clave por pregunta pública. Cada clave lleva `questionId`, `explanation:{en,es}` y `targetExpressionIds`; una pregunta `single_choice` lleva solo `acceptedOptionId` que exista en sus opciones, y una `gap_fill` lleva solo `acceptedAnswers` con 1–8 alternativas no vacías de ≤120 codepoints. Los IDs de pregunta no se repiten ni se omiten; los IDs objetivo deben existir en `expressions`. La API valida estas relaciones antes de juzgar y antes de publicar.

Proyección pública ≤64 KiB y artefacto privado ≤96 KiB. El script se limita por
perfil para una duración aproximada de listening a velocidad de referencia; la
duración real depende del TTS y las preferencias locales, y nunca es un timeout.

### Endpoints públicos

| Método y ruta | Resultado |
|---|---|
| `GET /v1/daily-practice/current?language=en` | `200 {schemaVersion:1,serverNow,editorialDate,package:DailyPackage}` |
| `GET /v1/daily-practice/packages/:packageVersionId` | `200 DailyPackage` solo si fue publicado y no retirado |

Errores: idioma inválido `400 invalid_language`; conocido pero no habilitado
`404 daily_language_not_enabled`; sin publicación válida `404 daily_not_available`;
versión retirada `410 daily_content_withdrawn`; borrador privado/ID desconocido
`404 not_found`; lectura desactivada `503 daily_unavailable`.

`current` usa ETag y cache público máximo 30 s; versiones también 30 s para
limitar exposición tras retirada. La admisión de intentos/voz consulta siempre
estado transaccional, no cache. Incorporar rutas al inventario real de admisión
pública; mantener rate limiting y no desactivar trusted-proxy globalmente.

## 3. Identidad y creación de intentos

Rutas privadas requieren bearer válido, incluyendo cuenta invitada cuando la
política hospedada existente lo permita. Resolver owner desde autenticación,
nunca desde body/query. Account A no puede distinguir si existe un intento de B:
lectura/mutación de ID ajeno → `404 not_found`.

`POST /v1/daily-practice/attempts`, con `Idempotency-Key: <uuid>`:

```json
{
  "schemaVersion": 1,
  "attemptId": "11111111-1111-4111-8111-111111111111",
  "packageVersionId": "22222222-2222-4222-8222-222222222222",
  "explanationLanguage": "es",
  "dailyConsentVersion": 1
}
```

Exige paquete publicado de hoy, consentimiento IA vigente y configuración
hospedada disponible. Reserva revisión y crea intento en una sola transacción;
`dailyConsentVersion=1` confirma además el aviso diario de retención mostrado
previamente; ausente/obsoleto produce consent_required sin crear intento.
no llamar al proveedor. `201 AttemptView`, o `200` del intento ya existente por
owner/idioma/fecha. El servidor devuelve su ID canónico; el cliente remapea su
outbox si otra instalación creó primero el intento.

`maxAttemptsPerDay` is configurable and equals `1` in v1. When increased later,
`attemptNumber` is allocated under a lock and counts toward that configured cap.
Reopening/resuming an existing attempt never consumes another slot. A completed
attempt remains reviewable; later practice does not rewrite its result.

Repetir la misma clave y body devuelve el status/body original sin nuevos efectos;
misma clave con hash de body diferente → `409 idempotency_conflict`. La primera
respuesta puede tener revisión antigua: después de replay el cliente hace GET
para obtener el estado vivo. Buscar replay antes de validar «hoy», de modo que
un timeout que cruza medianoche no cree otro intento. Retener claves mientras exista el intento; los intentos/revisiones no expiran temporalmente.

### `AttemptView`

`{schemaVersion,attemptId,attemptNumber,packageVersionId,language,editorialDate,
explanationLanguage,revision,state,startedAt,completedStages,activeMs,stopReason,
reviewState,reviewId,inputHash}`. `stopReason`, `reviewId`, `inputHash` are
explicitly nullable. There is no resumeUntil or duration-based expiresAt. The
attempt/review remains accessible until explicit account/data deletion per the
approved privacy lifecycle. `revision` empieza en 0 y aumenta una vez por evento nuevo.
`completedStages` lista de `{stage,status,activeMs}`; status `completed|skipped`.
Incluye `sectionResults` solo de etapas ya enviadas; sus claves no pueden
descubrir claves de preguntas de otra etapa aún no enviada.
Su forma es `{stage,items:[{questionId,status,acceptedAnswer,explanation}]}`;
status `correct|incorrect|skipped`, acceptedAnswer string de la clave y explicación
en explanationLanguage. No incluye el texto libre original del alumno. Eventos
aceptados devuelven este AttemptView, nunca un eco del body de evidencia.

| Método y ruta | Resultado |
|---|---|
| `GET /v1/daily-practice/attempts?language=en&limit=20&cursor=…` | `{items:AttemptView[],nextCursor:string|null}`; máximo limit 50, cursor opaco ligado al owner y lenguaje |
| `GET /v1/daily-practice/attempts/:attemptId` | `200 AttemptView` |
| `GET /v1/daily-practice/attempts/:attemptId/package` | Snapshot público del intento propio, incluso de día anterior; retirada →410 |
| `GET /v1/daily-practice/attempts/:attemptId/review` | `200 DailyReview` si ready; `202 {state,retryAfterMs}` si queued/running; `409 review_failed` o `review_unknown` |

`retryAfterMs=5000` para polling foreground, máximo 60 s seguidos. después botón de consulta; no polling oculto infinito ni petición LLM al leer.

## 4. Eventos y cierre

`POST /v1/daily-practice/attempts/:attemptId/events`:

```json
{
  "schemaVersion": 1,
  "eventId": "33333333-3333-4333-8333-333333333333",
  "expectedRevision": 0,
  "event": {
    "type": "stage_submitted",
    "stage": "listening",
    "activeMs": 180000,
    "answers": [
      {"questionId": "listen_1", "value": "option_b"},
      {"questionId": "listen_2", "value": null},
      {"questionId": "listen_3", "value": "option_a"}
    ],
    "helpUsed": ["transcript"]
  }
}
```

Evento es unión cerrada:

| `type` | Campos y efecto |
|---|---|
| `preparation_completed` | `activeMs`, `helpUsed` opcional (IDs de ayuda consultados en la preparación); fija primera etapa. Las ayudas por meta consultadas aquí (pista, inicio de frase, ejemplo) son andamiaje del speaking, así que el servidor las guarda bajo `helpUsed.speaking` y la revisión marca esa sección como asistida |
| `stage_submitted` | `stage=listening|practice`, `activeMs`, todas las preguntas de esa etapa exactamente una vez, valor string ≤120 o null, `helpUsed:string[]` IDs válidos; corrección cerrada server |
| `speaking_segment_saved` | `segmentId` UUID asociado a voz propia, `activeMs`, `fragments[]`, `helpUsed[]`; agrega IDs nuevos y guarda el turno completo |
| `stage_skipped` | `stage=preparation|listening|speaking|practice`, `activeMs`, `reason=learner_choice|microphone_denied|network_error|funding_unavailable|local_tts_unavailable`; registra parcialidad, nunca time_limit |
| `word_marked` | `markId` UUID, `text` ≤120, `meaning` ≤300, `kind=word|expression`, `sourceQuestionId` o null; máximo 12 marcas por intento |
| `speaking_closed` | Cierra conversación oral tras elección del alumno y preserva todos los fragmentos |
| `finished` | Fija snapshot/job después de etapas resueltas y cierre oral; `stopReason=learner_choice|natural_close` |

Los `activeMs` de etapa son incrementos no negativos de tiempo activo, no valores
totales reemplazables. No existe un límite máximo de duración; la UI no duplica el
incremento al repetir evento. No es prueba antifraude de tiempo. Tras `finished`
no se aceptan nuevos eventos de respuesta; las aportaciones manuales posteriores
se guardan localmente en Words, sin cambiar evaluación congelada.

Fragmento: `{id,role,text,voiceSegmentId,sequence,transcriptionUncertain}`;
id UUID, role `learner|tutor`, text ≤1.000 codepoints, sequence ascendente por
segmento. Máximo de bytes por request 24 KiB UTF-8 para texto+ayudas del
intento. El límite se valida antes de guardar: exceso →413, sin truncamiento.
El cliente conserva el fragmento pendiente localmente y explica el problema;
finalizar solo evalúa evidencia ya aceptada y declara lo no incluido.

Orden permitido: preparación, listening, speaking, práctica. Un primer segmento
oral se admite tras resolver listening (enviado u omitido) y puede continuar hasta
que el alumno envíe `speaking_closed`. Práctica se completa después sin hacer que
el tutor termine la conversación automáticamente. El intento solo finaliza mediante
`finished` explícito, con speaking cerrado u omitido. No hay cierre por tiempo. `word_marked` se permite antes
de cierre en cualquier etapa sin cambiar orden. No se edita una etapa enviada.

**Semántica transaccional:** comprobar `(owner,attempt,eventId)` antes de CAS.
El hash de intención cubre `{schemaVersion,event}`; excluye `eventId` (que es la
clave) y `expectedRevision` (precondición del transporte). Mismo ID/intención →
devolver respuesta original aunque `expectedRevision` sea antigua; intención
distinta →409. Evento nuevo con revisión antigua →
`409 revision_conflict` sin efectos; cliente descarga y reconcilia. No cambia la
precondición sin comprobar compatibilidad ni sobrescribe etapas resueltas. Etapa fuera de orden →
`409 invalid_stage_transition`. Respuestas de otro paquete/IDs repetidos →400.

Cierre devuelve `202 AttemptView` con `reviewState=queued`. Sin evidencia alguna,
no gasta proveedor: guarda resultado `partial`, `not_observed` y candidatos
manuales válidos. Con evidencia parcial pide revisión de lo observado. Repetir
cierre no congela otro snapshot, no consume otro presupuesto ni abre otro job.

## 5. Resultado `DailyReview`

```typescript
type DailyReview = {
  schemaVersion: 1;
  reviewId: string; attemptId: string; packageVersionId: string;
  language: string; explanationLanguage: "en" | "es";
  inputHash: string; rubricVersion: string; createdAt: number;
  outcome: "completed" | "partial";
  sections: Array<{
    stage: "listening" | "speaking" | "practice";
    evidence: "sufficient" | "limited" | "not_observed";
    correct: number | null; total: number | null;
    helped: boolean; explanation: string;
  }>;
  strengths: string[]; priorities: string[];
  errors: Array<{
    id: string; kind: "grammar" | "vocabulary" | "comprehension" | "task";
    quote: string; correction: string; explanation: string;
    fragmentIds: string[]; questionId: string | null;
    confidence: "supported" | "uncertain";
  }>;
  wordCandidates: Array<{
    candidateId: string; kind: "word" | "expression";
    text: string; meaning: string;
    origin: "learner_mark" | "review";
    confidence: "supported";
    evidenceIds: string[];
  }>;
};
```

Exactamente 3 `sections`, sin duplicados. Correct/total null para speaking;
listening/práctica incluyen omitidas en total y ninguna omitida suma correct.
`helped` lo recalcula el servidor desde el `helpUsed` validado del snapshot:
listening y práctica con las ayudas de su propia etapa; speaking incluye
también las ayudas por meta consultadas durante la preparación (son andamiaje
del speaking; ver evento `preparation_completed`). Errores de comprensión/práctica citan texto exacto de respuesta enviada y
`questionId`; errores orales citan solo fragmentos learner. No concatenar textos
de varios fragmentos para fabricar una cita que nunca se pronunció seguida.
`uncertain` se muestra como observación, no como error confirmado, y no se importa
automáticamente a Words. No incluir categorías acústicas sin audio evaluado.

Límites: resultado ≤32 KiB; strengths/priorities ≤3 de ≤300; errors ≤6 con
quote/correction ≤300 y explanation ≤500; candidatos automáticos ≤8 más hasta
12 marcas manuales. El servidor asigna candidate IDs estables dentro del resultado
congelado, no a partir del orden que cambie al recargar. Deduplicar por clave de
expresión y conservar origen/evidencias combinados; marcas del alumno prevalecen.

La salida del proveedor se admite una vez por `(attemptId,inputHash,rubricVersion)`;
leer y aplicar un resultado no genera otra evaluación. `reviewId` permanece fijo
después de admitirse. Un operador no puede editarla in-place.

## 6. Errores privados adicionales

| HTTP / código | Efecto y recuperación |
|---|---|
| 401 / auth existente | Reautenticar; outbox queda bajo owner original |
| 403 / consent_required o gate hospedado existente | Mostrar acción correspondiente; sin proveedor ni intento parcial creado |
| 409 / package_changed | Recargar antes de comenzar; nunca sustituir un intento ya iniciado |
| 409 / revision_conflict | Descargar intento y reconciliar por eventos |
| 409 / daily_voice_active | Esperar cierre/reconciliar voz; no abrir segunda conexión |

| 410 / daily_content_withdrawn | Impedir nuevo intento, conservar el snapshot ya admitido y finalizar según estado |
| cliente / local_tts_unavailable | No reproducir una voz de otro idioma; permitir reintentar tras habilitar la voz local o saltar listening explícitamente, registrándolo como parcial |
| 413 / daily_payload_too_large | Reducir nuevo envío sin truncar evidencia ya aceptada |
| 429 / rate_limit existente | Respetar Retry-After; repetir operación idempotente |
| 503 / daily_budget_unavailable | Sin nueva reserva/llamada; conservar actividades libres |
| 503 / review_unavailable | Intento guardado; aviso persistente, consulta posterior |

Timeout HTTP no significa rollback: repetir solo con el mismo ID/envelope o
consultar. No generar nuevos IDs «para que funcione». Persistir primero en outbox.
Un `409 revision_conflict` **confirmado** permite el procedimiento acotado de
data-and-sync.md: releer estado y, solo si la intención sigue siendo compatible,
reenviar el mismo eventId/payload con una precondición nueva. No se cambia el
contenido de respuestas ni se usa esta regla ante timeout o outcome desconocido.

## 7. Administración

Todas bajo `/v1/admin/daily-practice`, con autenticación estructural existente,
Origin/cookie existentes, cuerpos cerrados y `reason` de 10–500 codepoints para
mutaciones. Configuración y acciones con efectos usan CAS + Idempotency-Key.

| Método / sufijo | Body o query | Resultado |
|---|---|---|
| `GET /config` | — | config efectiva, revision, capacidades y claves enmascaradas |
| `PUT /config` | `{expectedRevision,reason,config}` | config persistida efectiva; no devuelve secretos |
| `GET /packages` | language,dateFrom,dateTo,limit,cursor | lista paginada; ventana ≤31 días |
| `GET /packages/:id` | — | artefacto editorial privado + evidencias sin secretos |
| `POST /generation-runs` | `{language,editorialDate,reason}` | 202 `{runId,state}` o 200 replay; no genera dentro del request |
| `GET /generation-runs/:runId` | — | estado del run pedido, fase, reasonCode seguro y gasto/reserva |
| `POST /publications` | `{packageVersionId,artifactHash,expectedRevision,reason}` | publicación del hash revisado; rechaza slot consumido distinto |
| `POST /packages/:id/reject` | `{expectedRevision,reason}` | estado rejected; solo borrador |
| `POST /packages/:id/withdraw` | `{expectedRevision,reason}` | retirada y stop para nuevos turnos |
| `POST /jobs/:runId/reconcile` | `{expectedRevision,reason,outcome,externalReference}` | `outcome=confirmed_not_sent|confirmed_failed|confirmed_succeeded|unrecoverable`; exige evidencia de proveedor, no guess |
| `POST /attempts/:id/review-retry` | `{expectedRevision,reason}` | solo fallo definitivo conocido y cota de 2; sin acceso al transcript desde panel |

`confirmed_succeeded` solo se admite si el adaptador puede recuperar el resultado
correspondiente y verificar hash/binding. Si no, `unrecoverable`; no inventar un
resultado ni liberar exposición desconocida como si fuera coste cero.

Config: flags de design.md, perfil/versiones y referencias de proveedor LLM para
generación y juicio,
`generationDailyBudgetNanoUsd`, `reviewDailyBudgetNanoUsd`,
`maxReviewReservationNanoUsd`, todos importes positivos al habilitar la operación.
El presupuesto de contenido incluye generación+juez; TTS es local y no crea gasto
de proveedor en servidor. Un cambio de configuración
afecta solo jobs nuevos; el snapshot de los ya enviados permanece.
