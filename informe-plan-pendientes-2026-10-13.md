# Informe: plan de implementación de los pendientes — 2026-10-13

**Decisión:** modelo híbrido offline-first: contenido de examen inmutable y
persistencia local de cada intento, con sincronización y recuperación obligatorias
entre dispositivos o tras reinstalar al iniciar sesión en la misma cuenta.

**Objetivo:** responder, interrumpir, reanudar y revisar el examen exacto realizado,
sin que una descarga tardía, una nueva semana o una actualización cambien sus preguntas.
**Arquitectura:** SQLite conserva snapshots e intentos; el backend conserva snapshots
versionados e historial privado por cuenta. El catálogo semanal solo selecciona
contenido para nuevos intentos, nunca reconstruye un intento anterior.
**Stack:** Kotlin/Compose/OkHttp/kotlinx.serialization; Swift/SwiftUI/GRDB;
TypeScript/Fastify/PostgreSQL; exporters Python; panel HTML/CSS/JS; Compose/Caddy.
**Fuente de requisitos:** conversación y auditoría del código actual de este informe;
no existe un nuevo documento de especificación asociado.

Este documento planifica cambios; no declara implementadas las piezas propuestas.
Se excluyen autoría de contenido, ejecución del plan y operaciones de producción.

## 1. Garantías y límites de recuperación

- Sin red se puede iniciar un examen bundled y conservarlo en el dispositivo.
- Antes de mostrar la primera pregunta deben quedar persistidos snapshot e intento.
- Reanudar y revisar leen la BD local, no el catálogo de la semana actual.
- Recuperar en otro dispositivo exige la misma identidad autenticada y que el
  servidor haya confirmado la sincronización de ese contenido y progreso.
- Una respuesta guardada solo offline NO se recupera si se pierde o borra el único
  dispositivo que la contiene. Mostrar esta limitación, no prometer durabilidad cloud.
- Estados visibles: `pending`, `synced`, `error`, `conflict`; indicar la última
  revisión confirmada. «Guardado localmente» no significa «sincronizado».
- Los intentos anónimos permanecen locales. Vincularlos a una cuenta requiere una
  acción explícita del propietario; no transferirlos por cambiar de sesión.
- Al cerrar sesión o cambiar de cuenta, aislar lecturas, escrituras y outbox por
  propietario; descartar callbacks de la sesión anterior. No enviar datos de A con B.
- Integrar snapshots/intentos en la política existente de borrado local y cuenta,
  backups y exportación. Cerrar sesión no implica borrar historial del servidor.
- La caducidad semanal afecta a la selección para iniciar, NO al historial guardado.

## 2. Qué existe hoy y qué falta

| Área | Hecho verificado | Evidencia actual |
|---|---|---|
| Android | Compone el examen bundled en memoria; respuestas en `rememberSaveable`, sin intento durable. | `apps/android/app/src/main/java/com/jkdc/app/ui/LevelTestScreen.kt:54-60` |
| Resultado | `LevelTestHistoryStore` conserva en SharedPreferences el último resumen por idioma/nivel; sobrescribe y no guarda preguntas, respuestas ni porcentajes por sección. | `apps/android/app/src/main/java/com/jkdc/app/core/LevelTestHistoryStore.kt:13-45` |
| Revisión | Tras entregar solo se muestran resumen, secciones y unidades débiles; la tarjeta abre otro examen, no el anterior. | `LevelTestScreen.kt:170-212`; `apps/android/app/src/main/java/com/jkdc/app/ui/LearnScreen.kt:184-196` |
| Progreso | SQLite y `/v1/progress` guardan completions/rollups, no snapshots o respuestas de cada intento. | `apps/android/app/src/main/java/com/jkdc/app/AppViewModel.kt:233-237,690-699`; `services/api/src/progress.ts:24-47` |
| BD local | `sessions.assessments_json` y `scenario_review_json` pertenecen a conversaciones/situaciones, no a estos exámenes. | `shared/db/schema.sql:119-149,203-209` |
| Backend | Guarda UUID, pool y variantes JSONB; API sirve solo semana actual por idioma/nivel/variante, sin recuperación por UUID. | `services/api/migrations/041_level_tests_llm.sql:28-54`; `services/api/src/level-tests-llm.ts:554-569` |
| Regeneración | `--force` retira la fila anterior y crea UUID nuevo; conserva filas históricas, pero no las expone por API. | `services/api/src/level-tests-llm.ts:530-546` |
| iOS | Placement exporter solo genera Kotlin; leveltests genera Kotlin y TypeScript. Falta Swift, motores, pantallas y ciclo completo. | `scripts/export_placement.py:145-154`; `scripts/export_leveltests.py:193-206` |
| Verificación iOS | El paquete incluye `Core` y `Tests`, no `App`; CI usa macOS y Core importa CryptoKit. | `apps/ios/Package.swift:17-19`; `.github/workflows/checks.yml:15-22` |
| Infraestructura | Caddy ya proxifica todo el dominio API; Compose ya ejecuta migraciones antes de API. | `services/api/Caddyfile:1-3`; `services/api/compose.yaml:16-23,61-63` |

Los nombres de archivo abreviados en la tabla pertenecen a los directorios indicados.
Las nuevas piezas se marcan como **propuestas**; nombrar una tabla o ruta futura
no declara que exista hoy. Las referencias a archivos actuales conservan su contexto.

## 3. Contrato compartido: snapshot e intento separados

### Snapshot inmutable

Un `examId` identifica una revisión exacta de contenido; `attemptId` identifica una
realización concreta. `weekId` es año ISO × 100 + semana (por ejemplo `202641`),
no ID de contenido. ETag sirve para revalidar una representación, no para recuperar
historial. Separar `schemaVersion`, `contentRevision` y `gradingVersion`.

El snapshot completo debe conservar:

- Identidad/hash canónico, procedencia (`bundled`, `deterministic`, `llm`), idioma,
  nivel, semana, fechas y variante cuando corresponda; no exigir variante al bundled.
- Orden exacto de preguntas y opciones, ID y tipo (`kind`), sección, enunciado,
  respuestas correctas admitidas, explicación, `unitID` y referencias a pasajes.
- Texto/título de lectura y listening, estímulo de voz y umbrales de evaluación.
- Versiones de esquema, contenido y evaluador; referencias estables de medios y
  política de cache si se usan audios externos. No añadir grabaciones del alumno.

Persistir también el contenido bundled completo usado en el intento: un ID de
lección o una versión de app no permite restaurarlo tras cambiar el bundle.
El examen remoto ya compuesto se adapta al motor/UI; NO se vuelve a componer.

### Persistencia local propuesta

Extender el esquema compartido `shared/db/schema.sql` y sus exporters/DAOs con:

| Entidad propuesta | Responsabilidad |
|---|---|
| `exam_snapshots` | JSON validado e inmutable, hash, versiones, identidad y procedencia; deduplicado por contenido. |
| `exam_attempts` | `attemptId`, snapshot, propietario, estado, fechas, posición, revisión local/remota y estado de sync. |
| `exam_answers` | Respuesta por ID de pregunta y revisión; no por índice de una lista mutable. |
| Resultado del intento | Veredictos por pregunta, respuesta propia, resumen y reglas originales usadas al entregar. |
| `exam_sync_outbox` | Eventos durables de creación/progreso/entrega con `eventId` y revisión; propósito distinto a `usage_outbox`. |

Seguir migraciones y escritura transaccional existentes en Android `data/` e iOS
`Core/Database/`. SharedPreferences/UserDefaults pueden alojar preferencias, no
el historial completo. Incorporar tablas nuevas a backups, restore y borrado local.

### Ciclo de vida

1. Prefetch remoto actualiza solo candidatos para el próximo intento.
2. Al iniciar, seleccionar snapshot válido disponible o componer bundled; validar y
   guardar snapshot + intento + evento outbox en UNA transacción antes de enseñar UI.
3. La UI observa `attemptId` y lee su snapshot de SQLite. Guardar respuesta y posición
   junto con el evento de sincronización; confirmar el guardado durable al usuario.
4. Tras cerrar/reabrir, recuperar por `attemptId`. No recalcular con semana o bundle actuales.
5. Entregar congela respuestas y guarda veredictos, resultado, completion y evento
   en transacción idempotente. Reintentar no crea otra nota ni duplica completions.
6. Revisar muestra pregunta, respuesta propia, solución, explicación, pasaje y
   resultado original. Recalificar con otras reglas nunca sustituye silenciosamente el original.

Si falla SQLite, no fingir un intento guardado ni marcarlo sincronizado: impedir
iniciar sin snapshot durable y permitir reintentar un guardado fallido sin perder
la respuesta en pantalla. Los resúmenes legacy se etiquetan «solo resumen»;
no inventar sus preguntas/respuestas ni recuperarlas del banco actual.

## 4. Backend: contrato, calidad e historial recuperable

### Prerrequisitos antes de activar generación

- Unificar payload generated/deterministic: `kind`, `unitID`, pasajes, umbrales,
  identidad/revisión y variante opcional compatible. `flattenPool` hoy pierde
  `kind`/`unitID` (`services/api/src/level-tests-llm.ts:439-453`).
- Servir umbrales efectivos: el SELECT actual solo trae `pool, variants` y termina
  devolviendo `{}`; `version: 1` tampoco identifica una regeneración (`:554-569`).
- Ejecutar realmente `validateGeneratedPool`; actualmente solo está importado.
  Validar mínimos/forma fija, IDs únicos, opciones/respuestas y todas las referencias.
- `strict`: preguntas rechazadas nunca se publican; fallo/incompletitud del juez
  impide publicación. Retirar rechazados y revalidar mínimos antes de admitir
  el resultado. `flag`: avisos
  explícitos para revisión, no garantía estricta. `off`: omite juez, nunca estructura.
- El código actual registra verdicts pero no retira rechazos ni bloquea fallo de juez
  (`services/api/src/level-tests-llm.ts:328-350`). Reemplazar pruebas nominales por
  aserciones sobre preguntas rechazadas, suficiencia y estado publicado.
- Corregir GET settings para leer BD efectiva, igual que generación. PUT parcial
  debe preservar campos no enviados y aceptar `flag` explícito, no volver al default
  de env (`services/api/src/llm-config-admin.ts:22-41,77-87`).
- Crear flujo auditable `draft` → revisión → `approved`; solo approved entra en
  catálogo público. El default `review` hoy crea drafts sin operación de aprobación.

### API propuesta, no disponible todavía

| Ruta propuesta | Contrato |
|---|---|
| `GET /v1/level-tests/current?language=&level=&variant=` | Selección actual; devuelve identidad estable y snapshot normalizado. Mantener compatibilidad o versionar cambios de la ruta existente. |
| `GET /v1/level-tests/exams/:examId` | Contenido exacto por ID; acceso público solo para snapshots publicados, no respuestas privadas. |
| `POST /v1/level-test-attempts/sync` | Batch autenticado, idempotente y acotado de snapshots propios/eventos; acknowledgement de revisiones admitidas. |
| `GET /v1/level-test-attempts?cursor=&limit=` | Historial propio paginado y cursor opaco para restore incremental. |
| `GET /v1/level-test-attempts/:attemptId` | Intento propio, snapshot exacto, respuestas y resultado para reanudar/revisar. |
| `POST /v1/admin/level-tests/:examId/approve` | Aprobación explícita, autorizada y auditada tras validación. |

Introducir contrato del batch: `attemptId`, `eventId`, `expectedRevision`, tipo,
contenido/patch validado y ACK con revisión resultante. Límites de bytes, páginas,
eventos y snapshots se fijan con fixtures y pruebas; prohibir payloads sin límite.

### Durabilidad, concurrencia y privacidad

- Publicar snapshot antes del intento o admitir ambos atómicamente; ninguna referencia
  colgante. Upload de bundled conserva contenido exacto, valida/hash/dedup y límites;
  contenido subido por usuario no se convierte automáticamente en catálogo público.
- Autorización por propietario derivado del token en cada operación; no confiar
  en un `accountId` del payload. Contenido privado recuperable solo por su cuenta.
- Guardar evento y cambios en la misma transacción; ACK tras commit. Cliente marca
  `synced` solo para la revisión confirmada, sin borrar eventos nuevos pendientes.
- Duplicado de `eventId` devuelve el ACK original; mismo ID con otro contenido se
  rechaza. Entregas repetidas conservan resultado original y no duplican progreso.
  Un intento completado no admite cambios posteriores de respuestas ni resultado.
- Actualización con revisión esperada/CAS; una revisión obsoleta produce conflicto,
  nunca last-write-wins ni fusión automática de respuestas de dos dispositivos.
- En conflicto, conservar las dos versiones. Continuar otro dispositivo exige
  recuperar la versión confirmada y takeover controlado por revisión; conservar
  cambios divergentes como nuevo intento requiere decisión explícita, no reset.
- Outbox propia se drena al arrancar, reconectar y en segundo plano cuando la
  plataforma lo permite; retries acotados/backoff. Separarla del sync de rollups
  y telemetría de consumo. Los rollups siguen siendo una proyección, no el historial.
- Restore autenticado descarga historial por páginas, trae snapshots faltantes y
  escribe localmente antes de ofrecer «Reanudar»/«Revisar». Mostrar progreso y errores.
- Retener snapshots referenciados, incluidos retirados/regenerados y bundled
  antiguos; sin TTL semanal para intentos completos. Documentar borrado de cuenta,
  exportación y retención explícita; cerrar sesión no es borrado remoto.
- Reforzar inmutabilidad de contenido en BD: los triggers actuales limitan estados,
  pero no bloquean cambios en pool/variants (`041_level_tests_llm.sql:56-68`).
- No enviar claves de proveedor ni otros secretos en snapshots/sync; no registrar
  respuestas o contenido privado en logs operativos. Las notas locales no son una
  certificación antifraude del servidor.

## 5. Android: remoto opcional, persistencia y restauración obligatorias

**Archivos existentes:** `apps/android/app/src/main/java/com/jkdc/app/`:
`AppViewModel.kt`, `core/LevelTests.kt`, `core/LevelTestHistoryStore.kt`,
`ui/LevelTestScreen.kt`, `ui/LearnScreen.kt` y `data/LearningDbWriter.kt`/DAOs.
**Nuevos propuestos:** `core/ExamSnapshot.kt`, `data/ExamAttemptStore.kt`,
`network/LevelTestRemoteClient.kt`, `network/ExamSyncClient.kt`;
pruebas bajo `app/src/test/java/com/jkdc/app/`.

- Repository/store concentra inicio, autosave, entrega, history y restore. ViewModel
  expone estados tipados; la pantalla no reemplaza preguntas al observar una descarga.
- `ignoreUnknownKeys` solo facilita evolución: validar schema soportado, identidad,
  idioma/nivel, semana, IDs únicos, referencias, tipos/secciones, orden, opciones,
  respuestas, conteos y tamaño. Rechazar todo snapshot incoherente antes de persistir.
- Fetch de catálogo con presupuesto TOTAL de 5 s (`callTimeout`), sin retries que
  bloqueen UI. Conexión/lectura por separado no garantizan ese límite total.
- Propagar `CancellationException` y cancelar llamada OkHttp; no envolver toda la
  operación en `runCatching` que convierta cancelación en fallo ordinario.
- Fallback ante 404/timeout/red/parseo inválido: usar bundled validado para nuevo
  intento; errores tipados/observables sin contenido sensible, no promesa absoluta
  de «nunca crashea». Usar dispatcher IO y límites de memoria/datos.
- Fijar variante para revalidación. 304 solo reutiliza cache válido de la misma
  identidad/variante/representación; sin cache válido, fallback o fetch incondicional
  dentro del presupuesto, nunca devolver contenido ausente/corrupto.
- Expiración y semana ISO en UTC para catálogo: lunes 00:00 UTC, no «7 días desde
  descarga». Android hoy usa fecha local; añadir fixtures de frontera y cambio de año.
- Revalidar candidatos deterministic para descubrir una generación aprobada después;
  respetar Cache-Control y `expiresAt`, sin bloquear el examen ya iniciado.
- Cache descargable y snapshots históricos son distintos: limpiar cache no elimina
  un snapshot referenciado por intento; caducidad no impide revisión ni restore.
- Historial enumera todos los intentos y estados de sync, no solo el último resumen.
  Revisión completa y reanudación funcionan incluso sin backend disponible.

## 6. iOS: paridad completa, no solo espejo offline

Añadir salida Swift a `scripts/export_placement.py` y `scripts/export_leveltests.py`
con checks de drift para todas las salidas. Usar el patrón de exporters existentes,
no editar datos generados manualmente. Placement incluye resultado persistente,
preferencia de instrucciones y entrada de navegación, no solo algoritmo.

**Propuestos en `apps/ios/Core/`:** `Placement.swift`, `PlacementData.swift`,
`LevelTests.swift`, `LevelTestsData.swift`, `ExamSnapshot.swift` y DAOs/store bajo
`Database/`. **Propuestos en `apps/ios/App/`:** `PlacementView.swift`,
`LevelTestScreen.swift`, cliente remoto/sync y vistas de history/revisión.
Modificar `App/Storage.swift` y `App/CoursesViews.swift` para integración.

- Igualdad SHA-256/composición ISO/golden B1 `202641`, evaluación y snapshot entre
  Kotlin/TypeScript/Swift; no recomponer contenido remoto.
- Mismo ciclo SQLite, autosave, outbox, ownership, ACK/CAS/conflicto, recuperación
  tras reinstalación y revisión histórica para generated y bundled.
- Regenerar Xcode project con `scripts/generate_project.py` al añadir archivos App.
- `swift test` verifica Core, NO SwiftUI ni navegación. Requiere el entorno macOS
  actual del proyecto; Linux no puede declararse verificación iOS completa.
- Añadir build de App y pruebas UI macOS/Xcode de inicio/reanudar/entregar/revisar,
  switching account y restore; mantener separados los resultados de cada gate.

## 7. Scheduler y despliegue: apagados hasta cerrar garantías

Scheduler in-process propuesto en `services/api/src/level-tests-scheduler.ts`,
cableado desde `main.ts`; reutiliza la función existente `generateWeeklyLevelTest`.
No copiar el ejemplo anterior `setInterval(nextRunMs)`: repetía el retraso inicial.

- Calcular próxima fecha UTC y programar `setTimeout` one-shot; recalcular tras
  terminar. Validar hora 0–23, día ISO 1–7, idiomas/niveles y listas sin duplicados.
- Exclusión local y entre réplicas/CLI por idioma+nivel+semana ANTES de llamadas
  facturables: lock de BD más estado durable del job, no solo índice único final.
- Una semana approved se salta; draft se conserva para revisión. Regenerar draft
  requiere acción explícita, no pagar de nuevo en cada tick/reinicio.
- Recuperar ejecución perdida al arrancar si ya pasó la hora de esa semana;
  consultar job/estado y no generar semanas históricas indiscriminadamente.
- Aislar error por idioma/nivel para continuar el resto; timeout total de job y
  transporte cancelable, retry seguro/acotado y diagnóstico sin secretos.
- Si una llamada LLM quedó con resultado externo desconocido, no reemitirla
  automáticamente: marcar job para conciliación. Un lock no garantiza exactamente
  una facturación tras pérdida de conexión/proceso sin idempotencia del proveedor.
- Shutdown cancela timer, evita nuevos jobs y drena/cancela el activo antes de
  cerrar BD. `unref()` por sí solo no resuelve este ciclo.
- Env controla ARRANQUE: cambiar configuración requiere reiniciar el proceso o
  recrear servicio Compose. No hay kill switch vivo por `/proc` ni file watch.

Añadir estas variables completas al servicio API de `services/api/compose.yaml`:

| Variable | Default / requisito |
|---|---|
| `LEVELTEST_GENERATE_ENABLED` | `false`; activación explícita después de los gates. |
| `LEVELTEST_GENERATE_HOUR` / `LEVELTEST_GENERATE_DAY` | `4` / `1`, UTC y día ISO. |
| `LEVELTEST_GENERATE_LANGS` / `LEVELTEST_GENERATE_LEVELS` | `en,es` / `A1,A2,B1`. |
| `LEVELTEST_VARIANTS` | `3`, rango 1–5 y settings efectivos coherentes. |
| `LEVELTEST_LLM_PROVIDER` / `LEVELTEST_LLM_URL` / `LEVELTEST_LLM_MODEL` | Preset/config validado según proveedor. |
| `LEVELTEST_LLM_API_KEY` / `LEVELTEST_LLM_MASTER_KEY` | Secretos protegidos; master key AES-GCM válida, no valores en repo/logs. |
| `LEVELTEST_PUBLISH_MODE` / `LEVELTEST_VERIFY` | `review` / `flag`; precedencia BD activa → env documentada. |

Verificar Caddy existente y servicio migrate; no presentarlos como funcionalidad
nueva. Las nuevas migraciones deben ejecutarse antes de API. Rebuild/recreate y
smoke de despliegue son futuros checks con entorno autorizado, no realizados hoy.

## 8. Panel admin: configuración efectiva y publicación operable

Modificar `services/api/admin-ui/app.js`/`index.html`/`app.css`, manteniendo panel
estático y catálogo `providers` de GET `/v1/admin/llm-config`.

- Mostrar provider/model/base URL, keyHint, publish/verify/variants efectivos.
  Guardar y recargar debe mostrar lo que utilizará generación, no defaults env.
- Usar cookies same-origin, SameSite Strict y comprobación de Origin existentes;
  no inventar un header CSRF que la API actual no implementa.
- «Probar conexión» prueba configuración GUARDADA. Si el formulario tiene cambios,
  avisar que hay que guardarlos; no afirmar que se probó una clave aún no persistida.
- Test con timeout/cancelación y respuesta acotada; no revelar claves. Auditar
  cambios y pruebas operativas relevantes, reutilizando autorización admin.
- Lista de drafts con contenido/veredictos del juez, rechazo y aprobación auditada;
  separar catálogo publicado del historial privado de alumnos.
- Estado de jobs/última generación y errores, sin presentar «configurada» como
  «scheduler activo». Publicación automática solo tras garantías estructurales.

## 9. Fases, tareas y criterios de aceptación

Los IDs son estables; ninguna casilla implica implementación o verificación realizada.
TDD efectivo no está resuelto por esta revisión: confirmarlo con configuración
vigente antes de ejecutar; si está activo, RED → GREEN → REFACTOR. No inferirlo por
existencia de tests ni ejecutar `sdd-init` para decidirlo.

| Fase | Dependencias | Entregable |
|---|---|---|
| P0 Contrato/calidad | Ninguna | Snapshot estable, validación real y settings/publicación coherentes. |
| P1 Persistencia local | P0 | SQLite transaccional, intento/snapshot/outbox y política legacy/backups. |
| P2 Cloud recovery | P0, contrato P1 | API privada, ACK/CAS, retención y restore autenticado. |
| P3 Android completo | P1, P2 | Fetch seguro, intentos durables, history/review, sync y restore. |
| P4 iOS completo | P0–P2 | Exporters/motores y misma paridad de intento/sync/restore. |
| P5 Operación | P0, P2 | Scheduler seguro y panel; pueden prepararse antes, desactivados. |
| P6 Activación | P3–P5 y checks aplicables | Rollout explícito con recuperación demostrada; no despliegue automático. |

### P0 — cerrar contrato antes de propagarlo

- [ ] **T01** Normalizar snapshot generado/deterministic/bundled e identidad.
  Archivos: `services/api/src/level-tests.ts`, `level-tests-llm.ts`, `app.ts` y
  fixtures compartidos propuestos. Probar `kind`, `unitID`, pasajes, umbrales,
  variante opcional y diferencia de ID al regenerar; mismo ID mantiene mismos bytes.
- [ ] **T02** Activar validación/juez y corregir settings/publicación.
  Archivos: `services/api/src/level-tests-validate.ts`, `level-tests-llm.ts`,
  `llm-config-admin.ts`; tests existentes/ampliados en `services/api/tests/`.
  Probar strict rechazo/fallo/minimos, flag advisory, draft no público y aprobación;
  PUT parcial + GET fiel, incluidos cambios `strict` → `flag` → `off`.

### P1–P2 — historial durable y recuperación obligatoria

- [ ] **T03** Migraciones/DAOs/store de snapshots, intentos, respuestas y outbox.
  Archivos: `shared/db/schema.sql`, `scripts/export_db_schema.py`, DAOs/writers
  Android e iOS, backups/restore/borrado. Probar fallo de guardado inicial,
  rollback de respuesta/posición/outbox y migración legacy «solo resumen».
- [ ] **T04** Implementar API privada y snapshots recuperables por identidad.
  Archivos propuestos: `services/api/src/exam-attempts.ts`, nueva migración
  numerada según estado del repo y `services/api/tests/exam-attempts.test.ts`.
  Probar acceso cruzado denegado, límites/paginación, ausencia de refs colgantes,
  snapshot privado no público y retención tras `--force`.
- [ ] **T05** Sync durable, ACK idempotente y CAS/conflictos.
  Cliente propuesto `ExamSyncClient`; backend T04. Probar retry duplicado,
  ACK perdido, ACK viejo con edits nuevos, sesión cambiada y dos dispositivos
  concurrentes; ninguna pérdida silenciosa ni duplicación de entrega/completion.

### P3 — Android como ciclo completo

- [ ] **T06** Fetch/adaptador/cache defensivos y selección congelada.
  Archivos Android propuestos en sección 5 y `core/LevelTests.kt`.
  Probar timeout total, cancelación, JSON/schema inválido, IDs repetidos/pasaje
  faltante, 304 válido/inválido y respuesta tardía DESPUÉS de contestar.
- [ ] **T07** UI autosave/reanudación/history/revisión y restore.
  Archivos: `AppViewModel.kt`, `ui/LevelTestScreen.kt`, `ui/LearnScreen.kt`,
  store T03 y tests JVM/UI. Probar kill/reopen, fallo DB, entrega repetida,
  rollover semana/año/UTC, bundle actualizado y force con intento viejo revisable.

### P4–P5 — paridad iOS y operación segura

- [ ] **T08** Exporters Swift y motores/goldens de placement/leveltests.
  Archivos: exporters sección 6, Core Swift propuesto y `apps/ios/Tests/`.
  Probar drift de salidas, vector B1 `202641`, frontera año/UTC y grading equivalente.
- [ ] **T09** iOS storage/navegación/UI/sync/restore equivalentes a T03–T07.
  Archivos sección 6, `scripts/generate_project.py`, `apps/ios/UITests/AppUITests.swift`.
  Comprobar Core y App por separado; repetir kill/reopen, review, cuenta/conflicto y restore.
- [ ] **T10** Scheduler apagado por defecto y Compose completo.
  Archivos: `services/api/src/level-tests-scheduler.ts` propuesto, `main.ts`,
  `compose.yaml`; tests scheduler propuestos. Probar mock clock, siguiente lunes,
  arranque perdido, drafts preservados, error por combinación, shutdown y dos
  instancias/CLI compitiendo sin llamadas LLM duplicadas concurrentes.
- [ ] **T11** Panel configuración/prueba guardada y drafts/aprobación.
  Archivos admin-ui y API admin sección 8. Probar save/reload, secreto enmascarado,
  formulario pendiente no probado, autorización/Origin y approved solo tras gate.

### P6 — aceptación de producto antes de activar

- [ ] **T12** Demostrar restore en instalación limpia y segundo dispositivo:
  MISMA CUENTA recupera snapshot, orden, respuestas, posición y resultado exactos,
  tanto bundled antiguo como generated retirado. Sin claves LLM reales en pruebas.
- [ ] **T13** Demostrar límites y aislamiento: cambios nunca subidos no se recuperan
  tras perder el dispositivo; estado pending es visible; logout/cambio cuenta no
  filtra ni sincroniza respuestas con otro propietario; conflictos requieren resolución.
- [ ] **T14** Registrar checks ejecutados/fallidos/omitidos, validar restore extremo
  a extremo y activar solo mediante autorización operativa explícita. Sin macOS,
  P4/P6 quedan parcialmente verificados; no declarar paridad iOS completada.

## 10. Verificación prevista y siguiente paso

Comandos comprobados en package/config/documentación; NO ejecutados en esta revisión:

```sh
# Backend: integración requiere TEST_DATABASE_URL y PostgreSQL de pruebas.
(cd services/api && npm run check && npm run build && npm test)
# Android: JVM, lint y build existentes.
(cd apps/android && ./gradlew :app:testDebugUnitTest :core:engine-local:testDebugUnitTest :app:lintDebug :app:assembleDebug)
# Exporters/contratos; --check no genera contenido.
python3 -m unittest discover -s scripts/tests -t .
python3 scripts/export_placement.py --check
python3 scripts/export_leveltests.py --check
python3 scripts/export_android_content.py --check
python3 scripts/check_cross_platform.py
# iOS Core: macOS del proyecto; no verifica App/SwiftUI.
swift test --package-path apps/ios
# App: macOS/Xcode, proyecto regenerado antes de verificar.
xcodebuild -project apps/ios/App.xcodeproj -scheme App \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath .build/DerivedData \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES build
# UI: exige simulador iPhone 17 disponible.
xcodebuild -project apps/ios/App.xcodeproj -scheme App \
  -destination 'platform=iOS Simulator,name=iPhone 17,arch=arm64' \
  -derivedDataPath .build/DerivedData CODE_SIGNING_ALLOWED=NO ARCHS=arm64 \
  ONLY_ACTIVE_ARCH=YES -parallel-testing-enabled NO test
```

Fuentes: `services/api/package.json`, `docs/build-and-test.md` y exporters actuales.
Antes de ejecutar, concretar runner Android UI/e2e admin y límites del contrato
con la configuración disponible; no sustituir esas pruebas por una revisión manual.
Los jobs de integración sin BD, macOS o simulador son pendientes, no PASS.

**Siguiente paso:** revisar este plan actualizado. La modificación del documento no
implementa aplicaciones, no activa generación, no crea commits/PRs ni autoriza
transferencias remotas. La primera implementación debe cerrar P0, no encender el cron.
