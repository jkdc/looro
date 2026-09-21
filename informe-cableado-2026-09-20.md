# Informe de cableado y completitud — Mural / looro

**Fecha:** 2026-09-20 · **Rama:** `feature/switchable-conversation-engine` (HEAD `70a3186`)
**Alcance:** funcionalidades incompletas, mal cableadas o muertas en ambas apps, los contratos cliente↔backend y las promesas de la documentación, sobre el estado actual (post-remediación del 17/09 y post-`gentle-ai`).
**Método:** cuatro exploraciones en profundidad en paralelo (Android, iOS, contratos con `services/api`, docs-vs-código), cada una verificando llamadores y consumidores reales en lugar de solo definiciones. Las rutas citadas están verificadas sobre el árbol actual.

---

## 1. Resumen ejecutivo

El código está saneado tras la remediación del 17/09 (los 10 bugs de entonces están resueltos) y el change `gentle-ai` está correctamente cableado en lo esencial (preferencias aplicadas en ambos motores, listening sin fuga a sesiones escritas, paridad de prompts y archive). Pero el barrido encuentra **fallos funcionales reales**, tres de ellos en zonas de producto visibles:

1. 🔴 **El TTS on-device habla todos los idiomas con voz `Locale.US`** — en builds deepseek/glm cada respuesta hablada en español, mandarín, etc. usa una voz inglés-EUA. El ejercicio de comprensión auditiva (K9) queda acústicamente roto en ese pipeline.
2. 🔴 **En el build por defecto (`openai`), dos controles de Ajustes están muertos**: el interruptor "turno manual" vuelve a off solo y el slider de silencio ajusta un valor que nada consume; el botón "He terminado" nunca puede aparecer.
3. 🔴 **El modo comprensión auditiva no ciega el transcript**: la historia de ~90 palabras se muestra como caption normal, palabra a palabra consultable — el alumno puede leer el ejercicio en vez de escucharlo; solo los subtítulos de significado están suprimidos.
4. ~~iOS no puede activar cuentas jamás~~ — **CORREGIDO EN LA VERIFICACIÓN**: el hallazgo era falso. `Signing.xcconfig` ya incluye `ManagedAccounts.xcconfig` y las 4 configuraciones del proyecto lo referencian como `baseConfigurationReference`; el mecanismo funciona (las cuentas están apagadas por los valores por defecto, no por cableado roto).
5. 🟠 **Tres huecos de mi propio `gentle-ai`** (partial obsoleta tras turno descartado; stats mezclando idiomas bajo un copy que promete progreso por idioma; la casilla 4.2 del registro SDD que afirmaba tests iOS inexistentes — ya corregida).

El resto: delegación inalcanzable en motores no-OpenAI con prompt que la promete, saludo fallido tragado en silencio, un caso de guest-link que deja al cliente atascado en "LINKING" para siempre, superficie backend huérfana, y una capa de documentación de usuario entera (READMEs, conteos de tests, `conversation-minutes.md`) que quedó una release por detrás del código.

---

## 2. Android

### 2.1 Funcionales

**[Alta · producto] TTS local con `Locale.US` fijo** — `core/engine-local/.../LocalTtsEngine.kt:89`
`engine.setLanguage(Locale.US)` nunca recibe el idioma de sesión; `LocalVoiceTransport` pasa el idioma solo a whisper (`LocalVoiceTransport.kt:404`) y `SpeechSink` no tiene `setLanguage`. Con deepseek/glm, el español sale con fonética inglesa, el mandarín es irreconocible y K9 (escuchar una historia) pierde su sentido. **Es el hallazgo de mayor impacto directo del informe.**

**[Alta · visibilidad] Controles de voz local muertos en el build `openai`** — `ui/SettingsScreen.kt:124-144` + `MuralViewModel.kt:724-725`
El interruptor "turno manual" y el slider de silencio se renderizan sin gate de `BuildConfig.CONVERSATION_ENGINE`, pero `voiceManualTurn` es `false` permanente en `openai` y `LiveTransport` no consume `silenceEndMs`. En el build shippable por defecto: el switch rebota a off, el slider no hace nada, y "He terminado" (`TalkScreen.kt:252-255`) es inalcanzable. Falta gatear la sección por motor o wire los controles en WebRTC (lo previsto en `voice-turn-settings-themes`).

**[Media-alta · feature propia] `partialUserTranscript` se queda obsoleta** — `LocalVoiceTransport.kt:396-415` + `MuralViewModel.kt:928`
Los turnos descartados (ruido <250 ms o STT vacío) terminan **sin** emitir delta final, y el ViewModel solo limpia la parcial al recibir un delta de usuario o al terminar la sesión: el texto provisional sigue en pantalla el resto de la sesión activa. (Mío — corregible emitiendo una parcial vacía al descartar el turno.)

**[Media-alta · diseño K9] El modo listening no oculta la historia** — `TalkScreen.kt:90-92,166-175`
`isListening` solo se consulta para el prompt y para suprimir meanings; el caption del asistente sigue mostrando y enlazando cada palabra. El ejercicio se puede leer en vez de escuchar. Necesita decisión: ocultar transcript del asistente hasta el final del turno/historia, o reformular la copy de la feature.

**[Media] Delegación prometida pero inalcanzable en deepseek/glm** — `MuralViewModel.kt:931-934` + `TeachingPolicy.kt:12`
El prompt dice al modelo que delegue eventos actuales al cliente, pero solo el servidor Realtime OpenAI emite `session.delegation.created` y `OpenAICompatibleEngine` nunca devuelve fuentes: en voz deepseek/glm la delegación es un no-op silencioso (el tema de actualidad del tab Topics sí funciona).

**[Media] Fallo del saludo inicial tragado** — `LocalVoiceTransport.kt:199-203`
El fallo del `engine.respond` del saludo solo loguea ("degrades quietly") mientras el equivalente en `processTurn` (`:438-441`) llama a `onFailure`: la sesión queda "activa" con el asistente mudo y sin feedback (solo el pre-check de clave se detecta antes).

**[Media] Outcome `member_deleted` no manejado → atasco permanente** — `guest-minutes.ts:66-105` vs `core/GuestMinutes.kt:52`
El servidor puede devolver `outcome:'member_deleted'` en el traspaso guest→miembro; el cliente solo acepta `transferred|member_trial_already_claimed|pending` → `InvalidResponse` y el guest queda en "LINKING" indefinidamente (el resultado es durable en el servidor). Edge case, pero irrecuperable para ese usuario.

### 2.2 Cableado menor y código muerto

- **Constantes muertas:** `VAD_SPEECH_RMS` y `SILENCE_END_MS` (`LocalVoiceTransport.kt:598,600`) quedaron sin llamadores tras `AdaptiveVad`; `DEFAULT_SILENCE_MS` duplica el default del campo.
- **Propiedades públicas sin llamador:** `started`/`isMuted` en ambos transports (`LocalVoiceTransport.kt:135-136`, `LiveTransport.kt:79-80`).
- **17 strings/recursos huérfanos verificados** de las 19 propuestas (el barrido marcó dos falsos positivos: `hosted_sign_in` y `mural_cream` sí se usan): p. ej. `error_topic_unsourced`, `settings_section_usage`, `hosted_not_ready`, `talk_lookup_button`… Y lo notable: **las dos de accesibilidad del orbe (`talk_orb_*_desc`) existen pero `MuralOrb` no pone `contentDescription`** (`ui/Design.kt:176`) — el orbe principal de la app queda sin etiqueta para TalkBack con los strings ya escritos.
- **Stats con doble alcance:** `stats()` cuenta sesiones de todos los idiomas (y los registros de topic solos cuentan como "conversaciones") mientras palabras/capacidades son solo del idioma activo (`MuralViewModel.kt:123` vs `LearningEngine.kt:52`) — mismo comportamiento en iOS; hay que decidir si es intencional y ajustar el copy.
- **Sin cobertura instrumentada de lo nuevo:** ningún androidTest referencia los testTags nuevos (`settings-slow-speech`, `words-practice`, `practice-answer`, `partial-transcript`, `settings-progress`…).
- **Verificado sano:** slowSpeech aplicado en ambos motores; listeningPractice sin fuga a sesiones escritas; drill con edge cases guardados; vocabulario de eventos transport→`handle()` completo; 252/252 claves EN/ES idénticas en los 8 ficheros.

---

## 3. iOS

### 3.1 Funcionales

**RETRACTADO tras verificación:** `Signing.xcconfig` línea 2 hace `#include "ManagedAccounts.xcconfig"` y las 4 configuraciones usan `Signing.xcconfig` como `baseConfigurationReference` (verificado en el pbxproj). El mecanismo de encendido funciona; las cuentas están apagadas por valores (`MURAL_MANAGED_ACCOUNTS_ENABLED = NO`), que es la decisión documentada. La cifra "0 menciones en el pbxproj" era técnicamente cierta pero irrelevante por la cadena de includes.

**[Media · release] `--preview` sin gate `#if DEBUG`** — `App/MuralApp.swift:12` + `App/RootView.swift:46`
A diferencia de todos los demás gates de preview (que sí están en `#if DEBUG`), un build release lanzado con `--preview` arranca con store **en memoria**: una sesión completa de aprendizaje no se persiste jamás. Baja probabilidad, clase data-loss.

**[Media · copy] Footer de backup con promesa falsa** — `App/LibraryViews.swift:381`
"Import adds conversations with new IDs" es falso: `Archive.merging` (`Core/Models.swift:201-215`) conserva los IDs originales y deduplica; el import es idempotente y no re-IDea nada.

**[Baja] Rama de UI inalcanzable en el drill** — `WritingPracticeView.swift:18-19`: el estado "no words due" es defensivo (el botón solo aparece con `dueCount > 0`). Inofensivo.
**[Baja] Warning de test** — `Tests/LearningStatsTests.swift:70`: `?? -1` sobre no-opcional (subíndice); compila con warning.

### 3.2 Código muerto y huecos de paridad

- **Sin llamador:** `ManagedAccountClient.wallet()` (`ManagedAccountClient.swift:86-89`), `ManagedWallet.availableDollars` (`Core/ManagedAccountProtocol.swift:141`), `LearnerState.levelLabel` (`Core/LearningEngine.swift:29`), `WordPractice.normalize` público sin uso externo (escrito para un test que nunca se escribió).
- **Paridad pendiente:** iOS no maneja `session.input_transcript.partial` (Android sí) — el transcript del usuario aparece solo al delta final; no existe cliente ni UI de reporte de IA (`/v1/feedback`) en iOS (en Android es feature viva); sin UI de minutos/wallet en la cuenta iOS aunque `wallet()` esté implementado.
- **Revisión del código nuevo:** sin errores de compilación detectables — inits memberwise, labels, imports, colores, proyecto regenerado (fileRefs de ambas vistas nuevas presentes) y fixtures cross-platform verificados contra los campos opcionales nuevos.

---

## 4. Contratos cliente ↔ backend

**Sólido en Android:** las ~20 rutas que los clientes Android llaman (auth, cuenta, minutos, guest, commerce, hosted live + helpers, feedback) existen con método y forma de campos correctos, incluidas las partes delicadas (quotes AI-value con desglose, bindings Play HMAC, estados hosted con `fundingMode`), y los códigos de error que Android compara existen todos en el servidor.

Hallazgos:

1. **[Media, revisado] iOS: integración backend parcial** — el encendido por xcconfig funciona (ver 3.1); lo real es que el único cliente iOS sirve auth/wallet, `wallet()` jamás se invoca y no hay UI de minutos, feedback ni hosted. Paridad pendiente, no cableado roto.
2. **[Media] Modo legacy `nanoUSD` hosted inconsumible:** el servidor aún lo acepta (`main.ts:52-54`) pero sus payloads carecen de los campos `billingBasis/reservedMilliseconds` que `HostedAPIClient.kt:118-119` exige → cualquier cliente recibiría `InvalidResponse`. Solo `milliseconds` y `ai-value` son consumibles; el modo legacy es superficie muerta.
3. **[Media] Códigos de servidor que Android degrada a genérico:** `provider_reconciliation_required`, `minute_balance_reconciliation_required`, `live_session_cancelled`, `helper_provider_limit_exceeded`, `cash_balance_reconciliation_required`, `ai_pricing_changed_review_quote`, `finish_guest_conversation_first`… nunca fallan el parseo, pero muestran "request failed" genérico donde el servidor tenía un mensaje accionable.
4. **[Baja] Endpoints huérfanos:** `POST /v1/minutes/welcome` (sin llamador móvil — pendiente de atestación), `POST /v1/trial/eligibility`, `POST /v1/checkout` + `/payment-return` (sandbox legacy), `GET /v1/pricing` (informativa), `/v1/wallet` (huérfano en la práctica).
5. **[Baja] Asimetría de gates sin fallo duro:** la UI de guest minutes aparece en cuanto hay `mural.apiOrigin` aunque el servidor no tenga `GUEST_MINUTES_ENABLED` (degrada a "unavailable"); compras con doble gate cliente+servidor (catálogo vacío u hoja oculta, sin error); iOS decide proveedores con config local sin preguntar `/v1/auth/providers` (Android sí).

---

## 5. Promesas vs realidad (docs)

1. **READMEs de cara a usuario desactualizados:** el "What works today" del README raíz (`README.md:76-84`) no menciona ninguna de las 6 features nuevas (ritmo lento, listening, progreso/rachas, drill de escritura, VAD adaptativo/parciales/barge-in); igual `apps/android/README.md:3`.
2. **Tres conteos de tests backend contradictorios:** README raíz dice 79, `services/api/README.md:144` dice 42, `android-release-progress.md:21` dice 303. **Verificado ejecutándolo** (Node 22 + PostgreSQL 17 en docker): **358/358, 0 fallos, 0 skips**. El "70 core tests" de iOS son ahora **88** (recuento estático, gate macOS pendiente). `verification/android-validation.md` (referenciado desde el README) dice "67 JVM tests / 215 keys" frente a **385** y **250** reales.
3. **`docs/conversation-minutes.md:31` falso:** dice que la voz hospedada "aún no" usa reservas de minutos — el código ya reserva y liquida (`hosted-voice.ts:103-106`) y el propio registro de progreso lo verificó en vivo.
4. **`docs/add-language.md:26` con comandos rotos:** rutas pre-reestructuración (`swift test --package-path Mural`, `Mural/scripts/...`); el paso 5 falla literalmente.
5. **Tres posturas contradictorias sobre hosted/cuentas:** README ("not active / in progress") vs `docs/android-accounts.md` ("implements") vs `docs/android/guest-onboarding.md` ("starts with Mural-hosted conversations" por defecto) vs `docs/android/design.md` (todo "in progress/disabled").
6. **Búsqueda con fuentes prometida sin condición** (`README.md:80`) cuando en deepseek/glm se responde sin búsqueda ni fuentes (documentado solo en `conversation-engines.md:106-111`).
7. **Lista de Future-work de `post-analysis-remediation` con estados no actualizados:** siguen abiertos y verificados en código: `error_topic_unsourced` muerta, sort aapt2 sin filtrar previews (`capture_android_play.py:24-25`), inclusión condicional de `:core:engine-local`, `confirmingErase` sin reset (`MuralApp.swift:15-19`); el rate-limit durable sigue abierto y correctamente documentado. (Hecho ya, con el fichero marcado superseded: "mejor VAD + barge-in".)

---

## 6. Apagado deliberado (inventario, no hallazgos)

Purchases Play/Stripe (`minutePurchasesEnabled=false` + gates de servidor), voz hospedada y helpers (gates `HOSTED_*` + allowlist), cuentas gestionadas iOS (ver hallazgo 3.1: además con el mecanismo roto), claves deepseek/glm solo por adb-push, inactivity/duration limits desactivados para motores sin coste, assessments por turno omitidos en pipeline local, intentos del drill sin persistir. Todos documentados; se listan una sola vez.

---

## 7. Correcciones ya aplicadas con este informe

- `gentle-ai/tasks.md` 4.2 y `design.md`: la afirmación "unit tests both platforms" de WordPractice ajustada a la realidad (7 casos JVM en Android; iOS bajo el gate macOS) — commit `70a3186`.
- `gentle-ai/verify-report.md`: la cita al informe de verificación reformulada para no depender de un fichero no trackeado.

---

## 8. Plan de acción recomendado

**Antes del próximo build de prueba (rápidos y de alto valor):**
1. TTS por idioma: `setLanguage(locale)` en `SpeechSink` + `LocalTtsEngine`, alimentado desde `sessionLanguage` (con fallback a `Locale.US` cuando el idioma no esté instalado). — Hallazgo 2.1-1.
2. Gatear "turno manual"/slider (y "He terminado") a `BuildConfig.CONVERSATION_ENGINE != "openai"`, o wire-los en WebRTC (lo que `voice-turn-settings-themes` ya contempla). — 2.1-2.
3. Limpiar la parcial al descartar turno (emitir parcial vacía o limpiar en VM). — 2.1-3, mío.
4. Cablear `ManagedAccounts.xcconfig` al proyecto Xcode (o eliminar el mecanismo y documentar que iOS es BYOK-sin-cuentas). — 3.1-1.
5. `#if DEBUG` en los dos checks de `--preview`. — 3.1-2.

**Siguiente tanda (producto/robustez):**
6. Decidir K9: ocultar transcript del asistente durante la historia (toggle "modo escucha estricta") o reformular la copy. — 2.1-4.
7. Superficiar el fallo del saludo (`onFailure` en vez del log silencioso). — 2.1-6.
8. Manejar `member_deleted` en `GuestLinkResult`. — 2.1-8.
9. Añadir `contentDescription` al orbe con los strings ya existentes; purgar las 19 strings huérfanas y las constantes muertas. — 2.2.
10. Delegación en motores compatibles (o quitar la promesa del prompt en esas builds). — 2.1-5.

**Docs (una pasada coherente):**
11. Actualizar "What works today" + ambos READMEs con las 6 features; sincronizar los 4 conteos de tests con la realidad (81/385/90/54); corregir `conversation-minutes.md` y `add-language.md`; unificar la postura hosted/cuentas en un solo sitio y referenciarla desde los demás.

**Parked confirmado (sin cambios):** rate-limit durable, sort aapt2, inclusión condicional de `:core:engine-local`, `confirmingErase`, tests instrumentados de la UI nueva, tests iOS de WordPractice (gate macOS).

---

*Informe generado a partir de cuatro exploraciones en paralelo sobre el árbol de la rama (Android, iOS, contratos con `services/api`, docs-vs-código); los hallazgos citan fichero:línea verificados por las exploraciones. El informe anterior de análisis es `informe-analisis-2026-09-17.md` (remediado); este no lo sustituye: cubre cableado/completitud sobre el estado actual.*
