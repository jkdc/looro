# Verificación — daily-practice

## Verificación de extremo a extremo y correcciones (2026-11-09)

Revisión independiente de la rama completa (requisitos vigentes + lógica acordada
+ defectos de revisiones anteriores), con flujo real en emulador contra una API
aislada. Flags locales activados solo en la base efímera del laboratorio; sin
llamadas a proveedores reales, sin activación ni despliegue. Sin commits ni push.

### Defectos confirmados y corregidos (cada uno con prueba que falla antes)

1. **Candidato Words sin evidencia tumbaba toda la revisión (servicio).**
   `validateDailyReview` lanzaba `invalid_daily_review` para un candidato con
   `evidenceIds: []`; el requisito manda descartar el candidato sin perder el
   feedback independiente. RED: `a candidate without any evidence is dropped
   without failing the whole review` (fallaba con `invalid_daily_review`);
   GREEN 8/8 en `daily-review.test.ts`. Se añadieron además pruebas guarda de
   requisito: error inventado no autoriza candidato; respuesta correcta no
   habilita candidato ni error; forma corregida sobre pregunta fallada llega a
   Words. Guarda de prompt en `daily-prompts.test.ts` (grounding solo con
   error verificado o fragmento cierto).
2. **Validador iOS rechazaba la revisión completa por un candidato legal.**
   `DailyReviewResult.decodeAndValidate` solo admitía evidencia de fragmento;
   los candidatos anclados a pregunta fallada con forma corregida (que el
   servidor emite conforme al contrato) provocaban `invalidReview` y perdían
   todo el feedback. Ahora los candidatos de revisión con evidencia no
   admitida se descartan sin invalidar la revisión y se aceptan las dos formas
   legales (fragmento cierto del alumno con texto contenido o corrección de un
   error validado; pregunta fallada cuya corrección coincide). Prueba escrita
   (`testReviewKeepsFeedbackAndDropsOnlyUnsupportedWordCandidates`);
   **ejecución pendiente de macOS/Xcode**.
3. **Ayudas de Android se atribuían a etapas equivocadas.** La pantalla enviaba
   el conjunto plano acumulado (recortado alfabéticamente a 8) en cada
   `stage_submitted` y en `speaking_segment_saved`; la ayuda de preparación
   nunca se enviaba en `preparation_completed`. Corregido con filtrado por
   etapa (reducer puro `helpUsedForStage` + wiring): preparación manda sus
   ayudas en su evento; listening/práctica solo las suyas; speaking solo las
   de metas. iOS ya filtraba correctamente (`intersection`) y se mantuvo como
   referencia. RED/GREEN JVM en `DailyPracticeTest`.
4. **Las respuestas elegidas se enviaban nulas (defecto crítico preexistente).**
   En el clic de envío, `draft.answers[q.id]?.let { put("value", it) } ?:
   put("value", JsonNull)`: `JsonObjectBuilder.put` devuelve el valor previo de
   la clave (null en la primera inserción), por lo que el Elvis **siempre**
   sobrescribía la respuesta recién puesta con `JsonNull`. Observado en el
   emulador: listening y práctica aceptadas por el servidor con todo `null`
   (estado + outbox del dispositivo con las respuestas correctas descartaban
   pérdida de estado local). Corregido extrayendo la construcción del evento al
   reducer puro `stageSubmittedEvent`; RED JVM con la expresión buguenta
   verbatim (`expected:<[a]> but was:<[null]>`) y GREEN tras el arreglo.
   GREEN de sistema: la cuenta de laboratorio B envió `a/b/a` y `a/b/null/null`
   y la corrección cerrada del servidor calificó 2 correct / 1 incorrect.
5. **El botón de envío podía quedar inalcanzable.** El `LazyColumn` llegaba
   hasta el fondo sin `contentPadding`, y con contenido corto el botón
   «Save answers and continue» quedaba permanentemente bajo la barra de
   navegación (verificado por bounds de UI Automator). Corregido con
   `contentPadding` inferior; verificado en vivo (el botón pasa a ser
   alcanzable y el envío funciona).
6. **Presupuesto de revisión a cero producía 500 genérico.** Sin presupuesto,
   la creación de intentos fallaba con `500 service_unavailable` en vez del
   `503 daily_budget_unavailable` del contrato. RED con prueba PostgreSQL
   aislada (`creating an attempt without review budget fails closed…`, que
   exigía `ServiceError` 503 y cero efectos: sin intento, sin reserva y
   publicación sin consumir); GREEN tras mapear el fallo de reserva.

### Comportamiento verificado en vivo (emulador + API aislada en Docker)

Stack efímero propio (PostgreSQL 17 + API de la rama con migraciones 059–065),
puerta 10.0.2.2:8080 para el emulador, cuentas de laboratorio A/B, paquete
fixture publicado sembrado en BD (sin generación LLM), flags activados solo ahí.
Recorrido por UI: onboarding (inglés/español) → paquete «A greener city» →
crear intento (recuperación con la misma clave de idempotencia tras un fallo
inyectado de presupuesto) → preparación (marca de expresión `word_marked`
aceptada; ayudas de objetivos en `preparation_completed` con
`helpUsed=["hint_goal-idea","hint_goal-plan","hint_goal-reason"]` y
`activeMs` medido 629755) → listening (transcripción registrada como ayuda;
respuestas reales tras el fix, grading 2/1) → speaking omitido
(`stage_skipped` con `activeMs` medido 1209236, razón `learner_choice`) →
práctica (respuestas `a/b/null/null`, huecos vacíos omitidos) → finish
(`input_hash` fijado) → revisión:
   - sin evidencia → resultado sintético `partial`/`not_observed` sin
     proveedor, trabajo `cancelled`, reserva `released`; candidato
     `learner_mark` «shared space» importado a Words del dispositivo con
     recibo `mark:<markId>` acción `existing` (sin duplicar ni resetear
     barras/progress);
   - con evidencia → trabajo en cola y worker **falla cerrado** sin proveedor
     (`daily_llm_unavailable`, sin llamada externa); el cliente muestra error
     persistente `daily_review_unavailable` con reintento y el intento queda
     consultable.
Reanudación verificada dos veces (navegación fuera/vuelta y reinstalación de
APK): mismo intento, mismas respuestas en caché, sin eventos duplicados.
Reinicio de medición por etapa observado (listening 106087 ms tras el salto
anterior). El evento de listening solo llevó `helpUsed=["transcript"]` (sin
fuga de ayudas de preparación): atribución por etapa correcta en vivo.

### Nota sobre «tiempo activo»

Los requisitos no definen el tratamiento del tiempo en segundo plano. La
implementación cuenta tiempo de reloj de la etapa: Android
(`SystemClock.elapsedRealtime`, incluye sueño profundo) e iOS
(`ProcessInfo.systemUptime`) **sí cuentan el tiempo con la app en segundo
plano**. No se declara equivalencia con el «presupuesto activo» de pedagogía;
queda como decisión de producto pendiente (medir, o excluir segundo plano)
antes del piloto.

### Comprobaciones ejecutadas

| Comprobación | Resultado |
|---|---|
| `make test-api` final tras corregir reserva del worker (PostgreSQL aislado) | 986 pruebas, 0 fallos, 0 omitidos; stack efímero eliminado al terminar |
| `make check` | Pasó: 152 pruebas Python, esquema v9 sin drift, localización 8 idiomas |
| Android `:app:testDebugUnitTest :core:engine-local:testDebugUnitTest` | 935 pruebas, 0 fallos, 0 omitidos (incluye RED/GREEN del elvis y filtrado por etapa) |
| Android `:app:lintDebug :app:assembleDebug` | BUILD SUCCESSFUL; APK debug instalado en el emulador |
| Android `:app:compileUiTestAndroidTestKotlin` | Compila (task correcto de la variante uiTest; `compileDebugAndroidTestKotlin` no existe en este proyecto) |
| `git diff --check` | Pasó (sin whitespace errors) |
| iOS Core/App/UI | **No ejecutado**: host Linux sin Swift/Xcode; XCTest escrito y pendiente de macOS |
| Android `DailyPracticeScreenTest` en emulador | 2/2; fixture de intento cacheado corregido y prueba focalizada pasa |
| Daily admin browser E2E | Pasa con worker de generación real y proveedor LLM stub; run/package ID seguido hasta publicación, lectura pública y retirada |
| `make load-api-baseline` | Pasa con proveedor local; integridad de balances/ledger y usage/settled coincidente; stack/volúmenes eliminados |

### Clasificación de la verificación (2026-11-09)

**Verificado (ejecutado, evidencia en este documento):** suites de API
(`make test-api`, 986/986 con PostgreSQL aislado), Python (`make check`, 152),
Android JVM (935/935, incluye RED/GREEN de los defectos 3 y 4), lint/build APK
y compilación `compileUiTestAndroidTestKotlin`, y los flujos reales
cliente→API→BD del emulador descritos arriba (eventos con CAS, ayudas por
etapa, tiempo medido, reanudación tras salida y reinstalación, revisión
sintética sin evidencia, importación a Words con recibo, fallo cerrado del
worker con evidencia y su presentación en el cliente, grading 2/1 tras el fix
de respuestas).

**Pendiente (no bloquea el desarrollo ni invalida lo verificado):** ejecutar
en macOS/Xcode las pruebas Swift escritas para los defectos 2 (`DailyPracticeTests`)
y las suites App/UI iOS completas. El host Linux no tiene toolchain Swift; el
código iOS está revisado estáticamente y sus contratos con el servidor
verificados por las pruebas del servidor.

**Omitido deliberadamente (requiere autorización de proveedor/piloto):**
speaking hospedado real y revisión con LLM real en el flujo del emulador. El
binding de voz se revisó estáticamente (iOS `DailySpeakingController` con
`hostedDailyCreate(dailyAttemptID:)`, gates de capacidades/saldo y
generation-fencing; Android `dailyVoice*`; servidor `daily-voice.ts` con
`in_progress`+etapa+`daily_voice_active` bajo lock) y la puerta de voz nunca se
activó; cero llamadas a proveedores. La revisión LLM real y el piloto siguen
requiriendo su autorización separada.

**Por decidir/documentar (decisión de producto):** si «tiempo activo» debe
excluir el tiempo en segundo plano. Hoy ambos clientes cuentan reloj de etapa
(Android `elapsedRealtime`, incluye sueño profundo; iOS `systemUptime`), por
lo que el segundo plano SÍ se cuenta; los requisitos no lo definen y no se
declara equivalencia con el presupuesto activo de pedagogy.md. Decidir antes
del piloto (medir tal cual, o pausar el contador en background).

**Documentación añadida en esta sesión:** el mapeo
`preparation_completed.helpUsed → helpUsed.speaking` quedó justificado y
documentado en `contracts.md` (tabla de eventos, §4 y semántica de `helped`,
§5) y en `pedagogy.md` (ayudas por meta como andamiaje del speaking): las
ayudas por meta se ofrecen en la pantalla de preparación, pero quién las
consulta entra a hablar con la meta ya andamiada, de modo que la revisión
marca la sección speaking como `helped` cuando se consultaron. El resto de
ayudas se atribuye a la etapa que las ofreció.

**Cobertura de la regresión de outbox:** la prueba instrumentada que leía
`daily_outbox` se retiró **antes de ejecutarse** — nunca corrió — porque el
harness `uiTest` instala una app aparte sin `MANAGED_API_ORIGIN`, incapaz por
diseño de encolar eventos diarios (y el test hermano de reanudación ya fallaba
en este emulador antes de esta sesión). El comportamiento «las respuestas
elegidas viajan en el envío» queda cubierto por la prueba JVM determinista
`stageSubmissionCarriesTheAnswersChosenOnThisDevice` (RED con la expresión
buguente verbatim: `expected:<[a]> but was:<[null]>`; GREEN tras el fix) y por
la evidencia de sistema del emulador (antes: servidor aceptó todo nulo con las
respuestas presentes en el outbox/dispositivo; después: `a/b/a` y grading
2/1). El test conectado **no se presenta como ejecutado**.

### Correspondencia informe ↔ código (auditada el 2026-11-10)

Anclas verificadas sobre el árbol actual; `make test-api` se ejecutó de nuevo
tras corregir la reserva del worker (986/986):

- `services/api/src/daily-review.ts:88` — `grounded=evidence.length>0&&…`
  descarta el candidato sin evidencia (sin throw por lista vacía).
- `services/api/src/daily-attempts.ts:192` — fallo de reserva mapeado a
  `ServiceError('daily_budget_unavailable', 503)`.
- `services/api/src/daily-prompts.ts:42` — cláusulas de grounding del prompt de
  revisión; guarda en `tests/daily-prompts.test.ts:36`.
- Pruebas servidor: los 4 casos nuevos de candidatos/evidencia en
  `tests/daily-review.test.ts:44,52,63,73` y la prueba PostgreSQL de
  presupuesto en `tests/daily-attempts.test.ts:65`.
- `apps/android/app/src/main/java/com/jkdc/app/core/DailyPractice.kt:110,238`
  — `helpUsedForStage` y `stageSubmittedEvent` sin elvis (asignación explícita
  de `JsonNull` solo cuando falta respuesta).
- `apps/android/app/src/main/java/com/jkdc/app/ui/DailyPracticeScreen.kt:323,430`
  — `contentPadding` inferior y envío vía extractor; testTags
  `daily-submit-stage` y `daily-answer-*` presentes.
- `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt:3374` —
  `helpUsed` de los segmentos de speaking filtrado a metas.
- `apps/ios/Core/DailyPractice.swift:397-406` — filtro de candidatos no
  admitidos con las dos formas legales (fragmento cierto con texto contenido o
  corrección de error validado; pregunta fallada con corrección coincidente).
- Prueba Swift del defecto 2: `apps/ios/Tests/DailyPracticeTests.swift:201`.
- Pruebas Android JVM: `DailyPracticeTest.kt:45,60,69`.
- `apps/android/app/src/androidTest/java/com/jkdc/app/DailyPracticeScreenTest.kt`
  restaurado a sus 2 pruebas originales (sin test de outbox).

# Verificación previa

Fecha: 2026-11-07. **La verificación de implementación sigue parcial; esta evidencia no autoriza activar la funcionalidad.**

## Reanudación de implementación y correcciones de integridad (2026-11-08)

Se corrigió la creación inicial del intento para insertar primero la fila referenciada antes del job de revisión, y se permite un siguiente intento cuando la configuración eleva el máximo diario y el anterior ya terminó. La revisión marca speaking como `not_observed` si hay menos de dos fragmentos del alumno; una transcripción incierta limita evidencia. El historial al reconectar se ordena por secuencia autoritativa de segmento de voz y luego por secuencia local del fragmento.

La eliminación de cuenta elimina también las claves idempotentes diarias y sus respuestas, cancela/reembolsa solo trabajos cuyo envío se puede demostrar que no empezó y conserva la reserva ante operaciones `sending`/`unknown`/`succeeded`. La transición de operación a `sending` bloquea primero la fila del job; esto comparte la cerca transaccional con eliminación. Una sonda independiente en PostgreSQL 17 verificó ambos órdenes concurrentes: despacho primero mantiene reserva y envío incierto; eliminación primero libera la reserva y el despacho se rechaza. No hubo llamadas externas.

También se exige publicación no retirada en la admisión de speaking, tanto en el preflight como bajo bloqueo, y se rehidrata el diálogo aceptado en una reconexión. No hay límite de duración o turnos añadido.

| Comprobación | Resultado |
|---|---|
| `make test-api` final en stack Docker/PostgreSQL aislado | Pasó: 976 tests, 0 fallidos, 0 omitidos; contenedores, red y volumen eliminados al terminar |
| `make check` | Pasó: 152 pruebas Python, esquema/export/paridad/localización; persisten ResourceWarnings de SQLite no relacionados |
| `services/api npm run check` | Pasó |
| Suite focal `daily-jobs`, `daily-provider-operations`, `daily-review`, `daily-voice`, `accounts` | Pasó; los tests de base se ejecutaron en la suite aislada |
| `git diff --check` | Pasó |
| Android build | Pasó en la continuación anterior. Connected UI tests no se consideran verdes: el emulador se reinició y la ejecución quedó bloqueada tras iniciar 84 pruebas y observar fallos preexistentes/no triados |
| E2E de administración diaria | Pasó el flujo publicación/retirada en entorno aislado; la suite general del panel sigue fallando en cuatro aserciones históricas de configuración LLM antes de llegar a la vista diaria |
| iOS Core/App/UI | No verificado: host Linux sin toolchain Swift/Xcode |

**La feature no está lista para cerrar.** Siguen pendientes la cobertura completa de los escenarios declarados por Tasks 1–10, la ejecución/triage de UI Android, verificación iOS en macOS, y pruebas de contrato/backup y flujos administrativos adicionales. Las flags permanecen desactivadas, los presupuestos en cero y no se invocó ningún proveedor real. No se hizo commit, push, activación ni despliegue.

## Continuación móvil (2026-11-08)

La implementación Android/iOS de Task 5 avanzó y se corrigieron los hallazgos
de revisión acotada: los fragmentos de speaking se conservan sin truncar y se
envían en lotes de hasta 20; el cliente Android reconoce el estado remoto
`in_progress`, restaura la etapa canónica y persiste conjuntamente la revisión
aceptada tras rebase 409; iOS conserva los intentos sin terminar en `.review`
para poder finalizar/reintentar la lectura de review; Words permite marcas
locales inmediatas y re-add explícito sin perder supresiones automáticamente.
También se corrigieron la clave `dailyVoiceSegmentId`, el cierre explícito y la
lineage de recibos Android. La revisión estática independiente limpió estos
hallazgos concretos, pero **no equivale a compilación ni suite móvil**.

| Comprobación | Estado |
|---|---|
| `git diff --check` tras las correcciones móviles | Pasó |
| `python3 scripts/check_ui_strings.py` | Pasó: 8 idiomas |
| `python3 scripts/check_db_schema.py` | Pasó: schema v9 |
| Android JVM/compilación | No ejecutado: lock del wrapper Gradle en caché de solo lectura |
| iOS Core/App/UI | No ejecutado: host Linux sin Swift/Xcode |
| Revisión móvil independiente | Revisión estructural acotada pasó; no sustituye pruebas nativas |

## Comprobaciones de esta fase

| Comprobación | Estado |
|---|---|
| Código actual inspeccionado con CodeGraph y spot check | Ejecutado: plan diario, escenarios, auth hospedada, Words y fronteras de persistencia |
| `openspec validate daily-practice --strict --no-interactive --json` | Ejecutado: valid true, 0 issues; comprobación final repetida tras correcciones |
| Enlaces locales, estructura y whitespace de documentos nuevos | Ejecutado: enlaces presentes, fences cerrados y sin trailing whitespace |
| Directorio de evidencias ignorado | Ejecutado con git check-ignore sobre evidence/admin/example.png |
| Builder check independiente del conjunto documental | Ejecutado; dos ambigüedades corregidas y recheck acotado sin bloqueos pendientes |
| Cambios locales previos conservados | Sin editar ficheros preexistentes durante esta fase |

### Hallazgos del builder check y corrección documental

1. Un evento inmutable rechazado por CAS necesitaba una salida distinta del
   replay de resultado desconocido. Se separó intención inmutable y precondición
   de transporte; un 409 confirmado admite un único rebase compatible con el
   mismo eventId, nunca sobrescribir una etapa ya resuelta ni duplicar tiempo.
2. Omitir práctica tras speaking dejaba sin definir el sellado oral. Tanto enviar
   como omitir práctica sellan la evidencia aceptada; el cierre por tiempo queda
   parcial sin fingir que no hubo conversación.

Ambas correcciones tienen escenarios normativos y tareas de regresión. El
revisor volvió a comprobar solo esos dos hallazgos y confirmó su resolución;
esto es revisión del diseño, no prueba funcional ni garantía de ausencia de bugs.

## Evidencia de implementación parcial (2026-11-07)

| Comprobación | Resultado | Alcance / límite |
|---|---|---|
| `cd services/api && npm run check` | Pasó | TypeScript; no prueba comportamiento ni SQL |
| Tests diarios enfocados (`daily-admin`, `daily-generation`, `daily-routes`, `admin-ui-state`) | Pasaron: 4 archivos | Los tests que requieren `TEST_DATABASE_URL` se omiten en esta ejecución |
| `node --check` para bootstrap, daily browser E2E y admin browser E2E | Pasó | Sintaxis, no interacción de navegador |
| `docker compose config -q` del stack de navegador | Pasó | Configuración aislada; se usaron puertos alternativos para evitar conflictos locales |
| `daily-practice-e2e.mjs` contra PostgreSQL/API/Caddy/Chromium locales | Pasó | Guardado de flags solo en BD desechable → job en cola → preview → publicación → lectura pública → retirada y lectura posterior 404; sin proveedor real |
| RED/GREEN para detalle editorial | Pasó | RED detectó columnas SQL ambiguas (`state`/`updated_at` compartidas); GREEN al calificarlas con alias `o.`; E2E confirmó preview real |
| `git diff --check` | Pasó | Whitespace del árbol actual |
| `admin-e2e.mjs` completo preexistente | Falló antes del flujo diario | Cuatro aserciones del flujo anterior de configuración LLM (clave persistida/conexión) y timeout; no atribuido a la ruta diaria. El E2E diario aislado sí pasó |

El test browser aísla y completa el flujo de gestión editorial, pero **no existe
todavía un worker diario que procese el job encolado**: el test confirma el
estado `queued`, no generación real. El paquete preparado para publicar es una
fixture sintética con audio de prueba; no valida generación, juicio ni TTS.
La caché pública tiene un TTL contractual de 30 segundos; la aserción de retirada
usa `cache: no-store` para comprobar el estado actual del servidor.

## Pendiente / no ejecutado

- Worker de generación/revisión/TTS y reconciliación durable; ninguna llamada real a LLM/TTS.
- Integración de los flujos diarios en Android e iOS, revisión del alumno y entrada de expresiones en Words.
- Suite completa PostgreSQL tras los últimos cambios; `make test-api` y E2E admin preexistente completamente verdes.
- Android Gradle/UI tests; iOS Swift/Xcode tests y builds en macOS.
- Matriz completa de coste, retención, borrado de cuenta, restauración y compatibilidad multiplataforma.
- Revisión independiente final de riesgo alto.
- Piloto, despliegue y activación global; requieren autorización separada.

La suite diaria PostgreSQL y pruebas de administración que pasaron en sesiones
anteriores quedan registradas en `.superpowers/sdd/2026-11-07-daily-practice/progress.md`;
no sustituyen la repetición final sobre el candidato completo. No se hizo push,
merge ni activación global.

## Comandos previstos para implementación

No ejecutar esta tabla como parte de una mera aprobación de documentos. Los
ficheros nuevos de tests de tasks.md aún no existen; primero deben demostrar RED.

| Área | Comando | Qué acredita / límite |
|---|---|---|
| TypeScript temprano y final | `cd services/api && npm run check` | Tipos; no flujo real ni BD |
| API rápida | `cd services/api && npm run test:unit` | Node tests; suite parcial puede omitir tests sin BD |
| API con PG efímero | `make test-api` | Integración real aislada; inspeccionar que tests diarios SQL se ejecutan, no skips |
| Contratos/schema/exporters | `make check` | Paridad y drift de artefactos, no duración real |
| Android temprano | `cd apps/android && ./gradlew :app:compileDebugKotlin` | Compilación temprana |
| Android core | `cd apps/android && ./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest` | Lógica determinista y regresiones |
| Android lint/build | `cd apps/android && ./gradlew :app:lintDebug :app:assembleDebug` | Recursos/lint/APK |
| Android UI | `cd apps/android && ./gradlew :app:connectedUiTest` | Dispositivo/emulador y permisos/flujos reales |
| Swift Core | `swift test --package-path apps/ios` | Core donde haya toolchain compatible; no verifica App SwiftUI |
| iOS App/UI | `make ios-build` y `make ios-test` | macOS/Xcode y simulador configurado requeridos |
| Admin browser | `E2E_EVIDENCE_DIR="$PWD/openspec/changes/daily-practice/evidence/admin" node services/api/tests/browser/admin-e2e.mjs` | Requiere stack aislado/fixtures/stub/navegador según README de browser; no arranca ni descarga esos recursos por sí solo |
| Observabilidad | `make obs-check` si se tocan configs | Configuración; no implica métricas recibidas |

El runner E2E admite `E2E_PANEL_ORIGIN` y `E2E_API_ORIGIN`; apuntar solo al stack
aislado autorizado. No usar por defecto un entorno con datos reales. Para ejecutar
un test Node diario enfocado, tras crearlo: `cd services/api && node --import tsx
--test --test-concurrency=1 tests/daily-content.test.ts`; repetir el mismo comando
antes/después de implementar para demostrar RED/GREEN.

## Matriz mínima de evidencia final

| Flujo | Unit/core | SQL/API | UI/E2E | Piloto |
|---|---|---|---|---|
| Paquete igual entre alumnos, solo en | Requerido | Requerido | Requerido | — |
| Ayudas y dificultad accesible | Requerido | Flags/registro | Requerido | Requerido |
| Estimación de 15–20 min sin límite | Medición/analytics | Sin cierre por reloj | Requerido | Requerido |
| Topic presente en conversación | Política | Binding real | Ambos móviles | Requerido |
| Retomar el mismo intento en cualquier momento tras cierre | Reducer/outbox | Reinicios reales | Ambos móviles | — |
| Citas verificadas e incertidumbre | Requerido | Snapshot/review | Representación | Calidad autorizada |
| Words sin duplicar/borrar progreso | Importador/backup | SQLite real | Ambos móviles | — |
| Owner/idioma/restore/respuestas tardías | Requerido | Requerido | Ambos móviles y admin | — |
| Publicar/retirar/unknown/costes | Requerido | Requerido | Admin real | — |

## Autorizaciones pendientes

Revisión del diseño escrito y de los defaults explícitos del README; después
plan de ejecución y autorización de implementación. Uso de proveedores reales,
piloto con alumnos y despliegue siguen siendo decisiones independientes.

## Evidencia de continuación (2026-11-07)

- Se corrigió la divergencia de versión SQLite Android: `LearningDb.VERSION` pasa
  a 8, alineado con `shared/db/schema.sql`, `SchemaData` y el opener iOS. El test
  Android ahora exige explícitamente esa versión y el `user_version` de apertura.
- Pasaron `python3 scripts/check_db_schema.py`,
  `python3 -m unittest scripts.tests.test_check_db_schema scripts.tests.test_export_db_schema`
  (39 tests), `cd services/api && npm run check` y `git diff --check`.
- No se pudo ejecutar el test Android: Gradle no puede crear el lock en el
  wrapper cache de solo lectura (`~/.gradle/...gradle-8.11.1-bin.zip.lck`).
- La integración UI Android/iOS continúa pendiente: el perfil obligatorio para
  el writer paralelo (`gpt-6.1-luna`) no está disponible en esta sesión y no se
  sustituyó por otro perfil ni se hizo la unidad inline.
- Revisión independiente detectó que el inventario de tablas del test Android
  aún omitía las cinco tablas nuevas; se corrigió y un probe SQLite aislado
  confirmó coincidencia exacta de las 30 tablas. La ejecución JVM sigue sin
  verificación por el bloqueo del wrapper Gradle.

## Contrato TTS local aprobado (2026-11-08)

El usuario prefiere mantener el enfoque de voz existente. Se propone que Daily
Practice entregue transcript y `speechLocale`, y que cada app lo reproduzca con
su TTS local actual. Esto elimina audio binario, ruta/blob y operaciones/costes de
TTS del servidor; la voz y duración audible pueden variar por dispositivo. Si no
hay voz del locale, no se permite fallback silencioso: se ofrece reintentar o
saltar listening como parcial. Esta propuesta actualiza los documentos de diseño,
contrato, pedagogía, tareas y OpenSpec, y fue aprobada por el usuario antes de
implementar este slice. La migración que retira el almacenamiento de audio está
escrita, pero no se ha ejecutado. Validación observada: tests API focalizados de
contenido (7), generación (6 + 1 omitido sin `TEST_DATABASE_URL`), admin (11),
intentos (7) y rutas (9) pasaron; `npm run check`, `make check` (incluidos 152 tests Python y gate de localización de 8 idiomas), `git diff --check` y `python3 scripts/check_ui_strings.py` pasaron.
Android no pudo ejecutar Gradle por lock de caché de solo lectura; iOS no pudo
ejecutar Swift/Xcode en Linux. La revisión independiente terminó con veredicto parcial y sin bloqueadores confirmados; no pudo ejecutar la migración PostgreSQL por falta de acceso al socket Docker ni las suites nativas Android/iOS (lock de Gradle y ausencia de Swift/Xcode).

## Proveedor LLM diario compartido (2026-11-08)

Se añadió `services/api/src/daily-llm.ts`: cada job puede resolver una vez la
misma configuración LLM activa que usan los exámenes y reutilizar su transporte;
si no hay proveedor configurado, falla cerrado antes de llamar al transporte.
RED/GREEN observado en `services/api/tests/daily-llm.test.ts` (2/2) y
`cd services/api && npm run check`; no hubo llamadas a proveedores reales. Este
adaptador por sí solo no ejecuta generación/revisión: los consumidores durables
siguen pendientes y Daily Practice conserva flags apagados y presupuesto cero.


## Vinculación de claves privadas antes de publicar (2026-11-08)

La publicación ahora valida que `private_payload.listening` y `.practice` tengan
exactamente una clave por pregunta pública; verifica tipo de clave, opción
aceptada existente, respuestas de hueco, explicaciones EN/ES y referencias a
expresiones del paquete. Esto evita que un borrador incompleto llegue a un estado
publicable y que el grading falle al empezar. RED observado en
`invalid_daily_answer_key_binding_cannot_publish`; GREEN en `daily-generation.test.ts`
(7 pass, 1 PostgreSQL omitido sin `TEST_DATABASE_URL`), `daily-answer-keys.test.ts`
(3/3), `daily-admin.test.ts` (11/11), `npm run check` y `git diff --check`.
La prueba PostgreSQL de publicación no se ejecutó en esta verificación.


## Puerta de generación y juez independiente (2026-11-08)

Añadido `runDailyContentQualityGate`: el servidor fija locale/perfil/fecha/ID y
hashes, valida claves privadas y entrega el artefacto a una invocación de juez
separada. Solo todas las evidencias `pass` completas y no contradictorias por ID
permiten estado `ready`; `fail` rechaza y `uncertain`/evidencia incompleta queda
`unknown`. RED/GREEN en `daily-content-pipeline.test.ts` (5/5), claves (3/3),
generación/publicación (7 pass, 1 PostgreSQL omitido), administración (11/11),
`npm run check` y `git diff --check`. El pipeline aún es una función inyectable,
no está conectado al consumidor durable ni invoca proveedores en esta verificación.

## Continuación: evidencia operativa de tokens en el panel (2026-11-08)

- Prueba RED: `daily-admin.test.ts` falló porque la respuesta de ejecuciones no incluía `provider_operations`; `admin-ui-state.test.ts` falló al importar el helper que aún no existía.
- Pruebas GREEN: `node --import tsx tests/daily-admin.test.ts` (12/12) y `node --import tsx tests/admin-ui-state.test.ts` (14/14).
- `npm run check`, `node --check admin-ui/app.js`, `node --check admin-ui/features/daily-practice.js` y `git diff --check` completados sin errores.
- La API de administración ahora devuelve registros por fase para jobs de contenido y revisión con campos allowlist; el panel solo presenta estado y conteos. No presenta prompts, respuestas ni coste monetario basado en tokens.
- No se pudo ejecutar integración SQL/PostgreSQL ni E2E de navegador en esta comprobación. Los workers durables aún no persisten checkpoints reales: el pipeline no está conectado y no hubo llamada a proveedor.

Verification correction (2026-11-08): An additional run-poll sanitization test was added after the initial report; final focused counts are daily-admin 13/13 and admin-ui-state 14/14 (not the earlier 12/12 intermediate count).

## Continuación de verificación (2026-11-08)

- Worker de contenido/revisión: `npm run check` correcto; pruebas `daily-workers` 5/5 y scheduler de revisión 1/1; suite diaria ejecutada archivo por archivo, sin fallos. Los casos PostgreSQL aparecen omitidos cuando falta `TEST_DATABASE_URL`.
- Resultado agregado final de los ficheros `daily-*.test.ts`: 85 pasaron, 6 omitidos por falta de `TEST_DATABASE_URL`, 0 fallaron. `daily-jobs.test.ts`: 7 pasaron, 4 omitidos.
- Recuperación de jobs: se observó RED/GREEN para renovar leases vigentes sin resucitar expirados y para reflejar `unknown`/`failed` en el estado de revisión consultable por el alumno.
- `make check`: correcto, 152 tests Python y validaciones de esquema/export/paridad/localización; avisos ResourceWarning preexistentes durante Python.
- `make test-api`: no ejecutable en este entorno: acceso al daemon Docker denegado antes de iniciar PostgreSQL.
- `main-wiring.test.ts`: no pudo ejecutarse porque el sandbox deniega `spawnSync` del proceso Node (`EPERM`); el test no alcanzó las aserciones de orden de inicio/cierre.
- `make android-build`: bloqueado antes de compilar porque el lock del wrapper Gradle está en un filesystem de solo lectura. iOS no verificable en este host Linux por ausencia del toolchain Xcode/Swift.
- Sin llamadas reales a LLM, activación, cambios de presupuesto, datos productivos, despliegue ni publicación.
- Pendiente: integración PostgreSQL real, tests nativos Android/iOS, browser E2E completo y revisión independiente del cambio de concurrencia/feature entero.

## Corrección de integración PostgreSQL (2026-11-08)

- Se agregó un test de cuenta real contra la BD efímera para comprobar que el borrado lógico elimina intentos, eventos, claves de idempotencia y revisiones privadas; cancela el job de revisión no enviado y libera su reserva.
- La primera ejecución aislada falló porque la aserción consultaba `daily_attempts.attempt_id`; el identificador de esa tabla es `id`. Corregido y verificado: el test aislado pasa 1/1 en PostgreSQL 17.
- La suite completa reveló dos fallos reales en los fakes SQL de `daily-workers.test.ts`: no modelaban la lectura del `job_id` de la operación ni el bloqueo de la fila del job al iniciar envío. Ajustados los fakes; `node --import tsx tests/daily-workers.test.ts`: 5/5.
- Nueva ejecución final `make test-api`: 976 pasaron, 0 fallaron, 0 omitidos, PostgreSQL aislado; duración observada 482.8 s. Los contenedores y volumen efímero se limpiaron.
- `cd services/api && npm run check`: correcto tras los cambios. Esto no cierra el change: faltan recorridos Android conectados estables, iOS en macOS, casos restantes de panel/importación/backup y revisión de todos los requisitos T1–T10. No hubo llamadas LLM, activación ni despliegue.

## Verificación Android (2026-11-08)

- El wrapper de Gradle no puede crear su lock bajo `~/.gradle` (solo lectura), y el JDK host 25 falla dentro de Kotlin. Bypass local sin cambios de repo: Gradle 8.11.1 ya descargado + JDK 21 local + `GRADLE_USER_HOME=/tmp/looro-gradle-home` + caché de dependencias read-only y `--offline`.
- Build equivalente a `make android-build`: `:app:testDebugUnitTest :core:engine-local:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon` — **BUILD SUCCESSFUL**. Re-ejecución forzada de tests JVM: Android app 914/914; engine-local 11/11. El apk debug se instaló y la app abrió en `emulator-5554`.
- CI usa la variante `UiTest`: `:app:connectedUiTestAndroidTest`. Gradle no pudo localizar instrumentation en el emulador. Instalados manualmente ambos APK de test; `am instrument` sí ejecutó 84 tests pero reportó 12 fallos: ocho de `CaptionParityTest`, uno de `DesignReviewTest`, uno de `ManualMuteUiTest`, uno de `OnboardingTest` y uno de `ReportHistoryTest`. El recorrido se completa, pero la suite no está verde. No hay aún prueba de UI instrumentada específica de Daily Practice.
- El checkout es Linux; iOS Core/App/UI sigue pendiente de ejecución en macOS. No cambiar código ajeno para silenciar fallos de UI preexistentes sin primero establecer su línea base.
- Unidad de pruebas y verificación registrada en el commit `bce9a1c` (`test(daily-practice): verify worker and deletion cleanup`).
- Se añadió `DailyPracticeScreenTest`: el RED falló en el tag de ruta ausente; tras añadir `daily-practice-screen`, el test aislado pasó 1/1 en `emulator-5554`. Las pruebas instrumentadas verifican que el alumno inglés abre la pantalla desde `tab-learn`; no cubren todavía respuestas/errores/reanudación.
- APKs `UiTest` y `UiTestAndroidTest` recompilados y prueba aislada pasada. Después del cambio, el conjunto equivalente a `make android-build` volvió a terminar `BUILD SUCCESSFUL`; los avisos Kotlin existentes de deprecación/performance continúan.

## Revisión de persistencia local Android (2026-11-08)

Una revisión independiente detectó defectos que impedían guardar eventos diarios en Android y podían reiniciar el progreso de Words al importar resultados desfasados. Se corrigieron antes de esta verificación:

- Las escrituras diarias ya no interpretan `SqlDatabase.update() == -1` como fallo: el adaptador Android usa `execSQL` y no ofrece recuento de filas. Los métodos verifican el estado persistido dentro de la misma transacción; los conflictos CAS siguen fallando si la revisión no avanzó.
- La importación vuelve a comprobar en la transacción la expresión actual, el recibo y la supresión. Un plan `INSERT` obsoleto no reemplaza el progreso concurrente; `KEEP` rellena significado vacío y adopta fila legacy sin resetear barras, intentos ni fechas. Si coexisten una fila legacy y otra del idioma, prioriza la coincidencia exacta por idioma antes de considerar adopción.
- Las fechas de repaso utilizan `nowSeconds()` (época de Apple), no Unix seconds. El esquema canónico y los dos generados declaran versión 9.

| Comprobación | Resultado |
|---|---|
| Regresiones Android focalizadas | Pasaron: escrituras/remapeo sin row count, plan obsoleto, `KEEP`, época, prioridad exacta sobre legacy y cierre/reapertura de SQLite. Las pruebas de precedencia y los cuatro casos iniciales fallaron primero por el comportamiento esperado. |
| Android JVM completo | Pasó: 932 tests, 0 fallidos, 0 omitidos (105 suites incluyendo engine-local). |
| `:app:lintDebug :app:assembleDebug` | Pasó con JDK 21, Gradle 8.11.1 y caché local de dependencias. |
| `make check` | Pasó: 152 tests Python y validaciones de esquema/export/paridad/localización; siguen avisos `ResourceWarning` de SQLite. |
| `make test-api` aislado con PostgreSQL | Pasó: 976 tests, 0 fallidos, 0 omitidos. |
| Revisión independiente acotada a la persistencia y su corrección | Pasó estáticamente y con sonda SQLite: cinco hallazgos originales corregidos y se corrigió además la precedencia entre fila exacta y legacy. No certifica el feature completo ni reemplaza XCTest. |
| Android connected UI | El test de entrada Daily Practice pasó 1/1 en ejecución previa. La suite instrumentada completa observó 12 fallos de 84, en CaptionParity, DesignReview, ManualMuteUi, Onboarding y ReportHistory; no se afirma verde. |
| iOS Core/App/UI | No verificado en este host Linux sin Swift/Xcode. |

La feature sigue **parcial**, con pruebas de recuperación/backup, escenarios UI y verificación iOS pendientes. No se hicieron llamadas reales a proveedores, activación, piloto ni despliegue.

La re-revisión de iOS encontró la misma precedencia entre fila legacy y exacta por idioma; ambas plataformas ahora buscan primero la coincidencia exacta. Android observó RED→GREEN y conserva ambas filas/progreso. Se añadieron pruebas Swift para esa coexistencia y para schema v9, pero no se pudieron ejecutar en este host. Un error tipográfico del test (accesor `double` en vez de `real`) fue corregido tras el informe independiente; sigue pendiente ejecutar Swift en macOS.
