# Tareas de implementación — daily-practice

**Estado: implementación en curso en `feat/daily-practice`; tareas abiertas y
verificación incompleta.** El usuario aprobó continuar con la implementación en
esta rama. El plan ejecutable y la evidencia incremental están en
`docs/superpowers/plans/2026-11-07-daily-practice.md` y
`.superpowers/sdd/2026-11-07-daily-practice/progress.md`.

## Reglas de ejecución

- Rama nueva, si hay cambios locales sin aplicar antes de empezar para y comunicar al usuario para ver como actuar.
- Antes de backend: `cd services/api && npm run check`. Antes de Android:
  `cd apps/android && ./gradlew :app:compileDebugKotlin`.
- Cada regla nueva tiene prueba determinista RED observada por motivo correcto,
  mínima corrección GREEN y refactor con pruebas verdes. Los defaults históricos
  de `openspec/config.yaml` no anulan la política test-first de AGENTS.md.
- Pruebas con clock, proveedor de texto y red inyectados; ninguna llamada real sin
  autorización específica. Integración SQL en PostgreSQL efímero, no omitirla
  porque necesita BD.
- Una tarea no se cierra por suite verde sin prueba del flujo nuevo. Registrar
  comandos y resultados observados, no expectativas, en verify-report.md.
- Cada unidad mantiene tests/contratos/docs junto a comportamiento. Commits
  convencionales pequeños en inglés cuando se autorice su ejecución, sin
  atribución IA. No se hace commit durante esta fase documental.

## Mapa de trazabilidad

| Tarea | Requisitos del usuario | Entrega |
|---|---|---|
| T1 | U1,U6,U7,U8,U10,U11 | Contratos/perfil común y fixtures |
| T2 | U1,U2,U4,U6 | Persistencia, jobs, reservas y ownership |
| T3 | U1,U6,U7,U8,U10 | Generación/juez/publicación; TTS local en apps |
| T4 | U1,U4,U5,U6,U11 | Intentos, eventos y recuperación |
| T5 | U1,U5,U8,U11 | Speaking hospedado ligado al paquete |
| T6 | U2,U3,U5,U11 | Revisión durable y verificable |
| T7 | U3,U9 | Importación Words y compatibilidad local |
| T8 | U1–U11 | Recorrido Android/iOS |
| T9 | U4 | Panel editorial/operativo |
| T10 | U4,U5,U7,U10,U11 | Prueba integrada, contención y piloto |

U# se define en [proposal.md](proposal.md); requisitos normativos y escenarios en
[specs/daily-practice/spec.md](specs/daily-practice/spec.md). T1→T2→T3/T4;
T4→T5/T6; T1→T7; T4+T5+T6+T7→T8; T3+T6→T9; todo→T10. Ninguna unidad requiere
mezclar esta funcionalidad con cambios históricos de exámenes.

## T1 — Contratos y perfil inglés

**Ficheros nuevos:** `shared/contracts/daily-practice/{package,attempt,event,review}.schema.json`,
`shared/contracts/daily-practice/fixtures/`, `services/api/src/daily-content.ts`,
`services/api/src/daily-language-profiles.ts`, `services/api/tests/daily-content.test.ts`,
`apps/android/app/src/main/java/com/jkdc/app/core/DailyPractice.kt`,
`apps/ios/Core/DailyPractice.swift`; tests espejo en sus carpetas de core.

**Interfaces:** `DailyLanguageProfile`, `validateDailyPackage`,
`projectPublicDailyPackage`, `hashDailyText`, `hashDailyArtifact`,
`gradeDailyClosedAnswers`. Consumen los campos/límites de contracts.md y devuelven
errores cerrados o DTO validados; no hacen IO ni consultan un alumno.

- [ ] RED: `same_package_for_different_learners`, `english_disabled_profile_is_rejected`,
  `public_projection_contains_no_answer_keys`, `jsonb_roundtrip_keeps_hash`,
  `duplicate_question_or_option_id_is_rejected`, `english_contraction_is_not_stripped`.
- [ ] Definir fixture completa de un día, 5 expresiones, 3 preguntas de listening,
  3 metas y 4 ejercicios. Fixture didáctica no significa contenido publicado.
- [ ] Validar tamaños UTF-8, codepoints, duraciones y normalización del perfil;
  `supported_language_without_profile` rechaza sin fallback. Validar que el
  paquete expone el `speechLocale` BCP-47 fijado por el perfil.
- [ ] GREEN y paridad de fixture en Kotlin/Swift; ejecutar `make check` después de
  extender los checkers necesarios, sin editar código generado manualmente.

## T2 — Datos, presupuesto y jobs

**Ficheros:** siguiente migración libre en `services/api/migrations/`,
`services/api/src/daily-jobs.ts`, `daily-budget.ts`, tests `daily-jobs.test.ts`,
`daily-budget.test.ts`, wiring de ciclo de vida en `services/api/src/main.ts`.
La interfaz `reserveDailyCost(tx,binding,costCenter,maxNanoUsd)` devuelve reserva
durable o `daily_budget_unavailable`; `claimDailyJob` devuelve lease o nada.

- [ ] RED SQL: `concurrent_reservations_never_exceed_budget`,
  `zero_budget_disables_dispatch`, `job_reclaim_before_send_is_safe`,
  `expired_sending_lease_becomes_unknown`, `two_replicas_claim_only_one_job`.
- [ ] Crear constraints/FKs de data-and-sync.md y reloj inyectado.
- [ ] Registrar operación antes del envío, resultado y uso de tokens como en
  exámenes; la exposición desconocida no se reenvía automáticamente. Revisar más
  adelante tarifas y liquidación monetaria por proveedor/modelo antes de habilitar
  límites de gasto propios. Persistir checkpoints `complete|partial|unknown` y
  conteos sin prompts, respuestas ni datos personales; no usar tokens para cuotas,
  acceso ni disponibilidad. `shutdown_does_not_resend_provider_operation`.
- [ ] Probar rollback real tras fallo de commit y reinicio simulado, no solo mocks.
- [ ] GREEN con PostgreSQL aislado; métricas de jobs con labels cerrados.

## T3 — Paquete diario publicado

**Ficheros:** `daily-generation.ts`, `daily-scheduler.ts`, `daily-admin.ts` (solo
operaciones editoriales), nuevos tests `daily-generation.test.ts`,
`daily-publication.test.ts`, `daily-scheduler.test.ts`, admisión pública en `app.ts`.
`ensureDailyPackage(language,editorialDate)` solo encola; `publishDailyPackage(tx,
packageVersionId,artifactHash,expectedRevision)` publica el binding validado.

- [ ] RED: `judge_failure_cannot_publish`, `uncertain_language_cannot_publish`,
  `edited_key_invalidates_judge`, `speech_locale_must_match_profile`, and malformed/missing/duplicate private question-key bindings.
- [ ] Generador y juez en invocación distinta usando adaptadores inyectados. El juez debe aportar evidencia pass/fail/uncertain completa y no contradictoria para cada ID namespaced; evidencia incompleta o incierta nunca permite `ready`. Congelar las configuraciones antes de enviar; sintetizar listening localmente
  en cada app, sin proveedor TTS ni artefacto de audio servidor.
- [ ] RED: `startup_ensures_today_and_tomorrow_once`, `utc_boundary_is_shared`,
  `unknown_job_does_not_auto_retry`, `published_consumed_slot_cannot_be_replaced`.
- [ ] Servir endpoints públicos sin claves privadas; cache/ETag/retirada según
  contrato, tests de header y de admisión existentes sin regresión.
- [ ] GREEN: no cambiar comportamiento de los schedulers/generación de exámenes.

## T4 — Intentos y eventos privados

**Ficheros:** `daily-attempts.ts`, rutas nuevas en `app.ts`,
`services/api/tests/daily-attempts.test.ts` y fixtures SQL.
Interfaces: `createDailyAttempt(principal,body,idempotencyKey)`,
`appendDailyEvent(principal,attemptId,event)`, `getDailyAttempt(principal,id)`;
principal es el account autenticado, no un accountId que mande el body.

- [ ] RED: `guest_hosted_owner_can_start_under_existing_policy`,
  `other_owner_gets_not_found`, `two_devices_start_one_attempt`,
  `creation_replay_crossing_midnight_returns_original_attempt`,
  `daily_attempt_cap_is_configurable_and_defaults_to_one`,
  `resume_after_any_elapsed_time_keeps_same_attempt`.
- [ ] RED: `rejected_event_changes_no_data_or_reservation`,
  `duplicate_event_replays_before_revision_check`, `same_id_new_body_conflicts`,
  `late_stage_cannot_overwrite_submitted_answers`.
- [ ] RED: `confirmed_conflict_rebases_same_intent_after_independent_word_mark`,
  `unknown_outcome_never_rebases`, `already_submitted_stage_keeps_local_draft_only`,
  `skipping_practice_seals_accepted_speaking_without_losing_fragments`,
  `local_tts_unavailable_skip_is_partial_and_resumable`.
- [ ] Persistir etapas, ayudas, fragmentos y aportes manuales, CAS y cierre
  inmutable con job; `empty_attempt_has_partial_result_without_llm`.
- [ ] Caducidad/retirada/cambio de día y recuperación de outbox sin nuevos IDs.
  Probar límites de bytes y exceso sin truncar respuestas ni fingir guardado.
- [ ] GREEN con SQL real y regresiones de auth/progreso existentes.

## T5 — Speaking preparado e integración hospedada

**Ficheros:** `services/api/src/hosted-voice.ts`, `live-provider.ts`, `app.ts`,
`daily-voice.ts` nuevo; `services/api/tests/daily-voice.test.ts`; políticas de
enseñanza y clientes hospedados Kotlin/Swift donde crean el body de sesión.

`resolveDailyVoiceContext(principal,attemptId)` produce instrucciones comunes,
historia aceptada del intento y presupuesto restante, o error cerrado.
`dailyAttemptId` es campo opcional nuevo; ausencia conserva la ruta anterior.

- [ ] RED: `daily_context_ignores_no_fields_silently` (body arbitrario se rechaza),
  `daily_prompt_has_topic_roles_goals_but_no_personal_history`,
  `legacy_context_without_daily_binding_is_unchanged`.
- [ ] RED: `one_active_voice_connection_per_attempt`, `no_turn_count_limit`,
  `conversation_starts_with_topic_opening`, `user_can_close_after_any_number_of_turns`,
  `disconnect_reconnect_preserves_same_conversation`, `student_can_close_speaking_explicitly`,
  `tutor_does_not_turn_topic_into_fixed_interview`, `feedback_does_not_interrupt_speech`, `withdrawal_blocks_new_turns`,
  `out_of_order_close_does_not_open_second_session`.
- [ ] Aplicar gates actuales, fencing por sesión/restore y cierre confirmado.
  Denegación de micrófono conserva progreso y conduce a omisión parcial.
- [ ] GREEN con proveedor stub y pruebas de consumo hospedado/billing existentes;
  no usar `scenario_review` para prometer persistencia nueva.

## T6 — Revisión durable

**Ficheros:** `daily-review.ts`, `services/api/tests/daily-review.test.ts`,
`daily-review-jobs.test.ts`; adapter LLM actual únicamente si necesita extraer
una función compartida sin cambiar su contrato histórico.
`freezeDailyReviewInput(tx,attemptId)` produce inputHash;
`validateDailyReview(raw,snapshot,profile)` devuelve resultado admitido o fallo.

- [ ] RED: `tutor_quote_cannot_be_learner_error`, `noncontiguous_quote_is_rejected`,
  `unknown_source_removes_dependent_words`, `uncertain_transcription_is_not_firm_error`,
  `transcript_cannot_claim_acoustic_pronunciation`, `two_passage_minimum_is_honest`.
- [ ] LLM sintetiza secciones/errores sobre snapshot; claves cerradas las corrige
  servidor. Validar estructura y fuentes antes de persistir, no texto en logs.
- [ ] RED: `result_survives_restart_after_120_seconds`, `poll_does_not_call_llm`,
  `finish_replay_creates_one_review`, `unknown_dispatch_keeps_reservation`,
  `deleted_account_is_not_recreated_by_late_result`.
- [ ] Probar fallos HTTP, timeout y resultado malformado con estado persistente.
  Reintento de operador limitado/auditado, no llamada automática extra.
- [ ] GREEN: métricas, purga y pruebas de helpers actuales inalteradas.

## T7 — Words y persistencia local en ambas plataformas

**Ficheros:** `shared/db/schema.sql`, schema checker/fixtures aplicables;
Android `core/DailyWordsImport.kt`, `data/LearningDbWriter.kt`,
`data/LearningDbDao.kt`, `AppViewModel.kt`; iOS `Core/DailyWordsImport.swift`,
`Core/Database/LearningDbWriter.swift`, `App/Storage.swift`; modelos/codec de
Archive solo para metadata opcional. Tests nuevos `DailyWordsImportTest.kt` y
`DailyWordsImportTests.swift`, más tests DB/backup ya existentes.

`planDailyWordsImport(result,owner,language,existing,receipts,suppressions,now)`
es puro y devuelve acciones `insert|keep|suppress|pendingCapacity`. El writer
aplica expresión+recibo en una transacción con admission de owner vigente.

- [ ] RED: `review_replay_does_not_reset_recall_progress`,
  `existing_meaning_is_preserved`, `single_word_is_saved_as_notebook_expression`,
  `capacity_2000_does_not_evict_or_claim_saved`.
- [ ] RED: `deleted_expression_does_not_return_after_review_replay`,
  `manual_mark_and_final_review_share_origin`, `manual_save_can_clear_suppression`,
  `language_switch_does_not_relabel_candidate`, `account_switch_rejects_late_import`.
- [ ] RED DB: caída entre upsert y recibo hace rollback de ambos; replay repara.
  Respetar epoch Apple, adopción legacy y source lookup.
- [ ] RED backup: archivos antiguos, metadata opcional, backup cruzado Android/iOS,
  restauración sin metadata exige confirmación para históricos, owner distinto no
  adopta recibos. Supresión al límite pausa autoimportación sin olvidar borrados.
- [ ] GREEN y `make check`, tests JVM y Swift Core; no añadir source daily al enum.

## T8 — Recorrido móvil y recuperación

**Ficheros nuevos:** Android `network/DailyPracticeClient.kt`,
`ui/DailyPracticeScreen.kt`, iOS `App/DailyPracticeClient.swift`,
`App/DailyPracticeView.swift`. Integraciones en Android `AppViewModel.kt`,
`ui/LearnScreen.kt`, `ui/AppRoot.kt`, `ui/LibraryScreens.kt`; iOS `App/Storage.swift`,
`App/CoursesViews.swift`, `App/RootView.swift`, `App/ConversationCoordinator.swift`,
`App/LibraryViews.swift`. Strings EN/ES Android y catálogo iOS, sin hardcoding.

- [ ] RED core: reducer de etapas, respuestas incrementales, pausa/reanudación
  tras reinicio, medianoche, respuestas tardías y fallo de transporte; ninguna
  ruta cierra por superar una duración estimada.
- [ ] RED UI: loading, paquete ausente, permiso denegado, red caída, HTTP error,
  respuesta tardía tras navegación, reintento, vacío y ayuda. Probar foco/teclado,
  accesibilidad y errores persistentes; mantener testTags existentes.
- [ ] Persistir outbox antes de petición; la UI no declara guardado al enviar.
  Explicar fecha UTC, resultados parciales y ausencia de sync general de Words.
- [ ] Probar proceso muerto en cada etapa y durante importación; mismo snapshot
  y sin doble evaluación, palabra o cargo. Voz libre debe seguir funcionando.
- [ ] GREEN Android unit/lint/UI y iOS Core/App/UI en sus entornos. Linux no
  verifica SwiftUI aunque un test de core pudiera ejecutarse.

## T9 — Panel de administración

**Ficheros:** `services/api/admin-ui/features/daily-practice.js`, `admin-ui/app.js`,
estilos solo cuando necesarios, `services/api/tests/admin-daily-practice.test.ts`,
`services/api/tests/browser/admin-e2e.mjs`; rutas `daily-admin.ts` y registro admin.

- [ ] RED Node sobre estado puro: dirty config, selección fecha/idioma, watch de
  runId, aborto al navegar, error HTTP persistente, replay tras timeout.
- [ ] RED API: auth/Origin/auditoría, hash no aprobado, retirada, budget inválido,
  reconciliación sin evidencia rechazada, tablas privadas excluidas de browser.
- [ ] Proyectar al panel el historial operativo por fase y checkpoints de tokens;
  nunca mostrar prompts/respuestas ni derivar un importe monetario. La consulta
  de ejecuciones debe conservar cada fase (`generate`, `judge`, `review`, etc.);
  el panel muestra únicamente estado y conteos `tokensIn`/`tokensOut`.
- [ ] RED E2E real: login→configurar→generar stub→watch propio→preview→publicar→
  comprobar paquete público→retirar. Añadir respuestas tardías, error y reintento
  visibles, foco y aviso persistente. No sustituir UI real con prueba unitaria.
- [ ] Evidencias bajo `E2E_EVIDENCE_DIR=openspec/changes/daily-practice/evidence/admin`
  (usar ruta absoluta al lanzar desde otra carpeta), ignorado por este change.
- [ ] GREEN sin sobrescribir screenshots versionadas ni descargar navegador
  automáticamente; documentar limitaciones si el entorno no está disponible.

## T10 — Validación conjunta y activación

- [ ] Fixture golden pública/privada/resultado usada por servidor y ambos cores;
  secuencia completa con alumno bajo usando ayudas y alumno sin ayudas sobre
  mismo paquete, con diferentes respuestas y resultado individual.
- [ ] Interrupciones reales con BD/API reiniciadas: después de reservar, después
  de cerrar y antes de persistir review; incertidumbre sin reenvío automático.
- [ ] Verificar account deletion, retención con clock inyectado, datos privados
  fuera de logs/table browser y compatibilidad de conversación/exámenes/progreso.
- [ ] Pasar los comandos aplicables de verify-report.md; un fallo no explicado
  impide cerrar la entrega. Revisiones independientes de cambios de alto riesgo
  deben inspeccionar auth, costes, concurrencia y formato local.
- [ ] Solo con autorización aparte: proveedores reales y prueba de calidad de
  síntesis local/revisión. Después, piloto de tiempos/accesibilidad de pedagogy.md.
- [ ] Documentar gasto medido, límites, flags y rollback; pedir autorización de
  despliegue/activación. Mantener idiomas distintos de en desactivados.

## Criterio común de cierre por tarea

Registrar requisito cubierto, prueba RED observada, GREEN, regresión histórica,
comandos, limitaciones y ficheros. Si faltan pruebas de plataforma/BD, la tarea
queda pendiente de verificación: no reemplazarla por «debería funcionar».
