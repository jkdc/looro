# Plan de especificación: `admin-exam-operations` (panel de administración de exámenes)

**Fecha:** 2026-10-21
**Estado:** Propuesta validada contra el código actual; pendiente de aprobación e implementación.
**Alcance:** listado completo de exámenes generados con filtros, regeneración de la semana actual no publicada, y resultados de exámenes por alumno en el panel de administración. Se especificará como change SDD en `openspec/changes/admin-exam-operations/` y luego se implementará.

---

## 1. Decisiones de diseño

| # | Decisión | Detalle |
|---|---|---|
| 1 | **Regeneración restringida** | Solo la semana en curso (`weekIndex(now)`) y solo cuando NO está publicada: filas con estado `draft`/`rejected` de la semana actual (o claves de la semana actual sin fila). Si la clave tiene fila `approved` → 409 `level_test_week_not_regenerable`, porque los exámenes aprobados ya tendrán intentos de alumnos asociados. Validado server-side, no solo ocultando el botón. |
| 2 | **Cero cambios de esquema** | No hay migraciones nuevas: se reutilizan `level_test_weeks`, `level_test_generation_jobs`, `level_test_attempts` y sus índices/triggers actuales. |
| 3 | **Regeneración asíncrona** | El endpoint lanza `runLevelTestGenerationJob(..., force: true)` sin awaiting (mismo patrón que el scheduler), con advisory lock contra CLI/scheduler; responde `202 {started: true}`. El progreso se monitoriza con `jobs[]`. |
| 4 | **Listados ligeros** | Los listados devuelven solo metadatos; los JSONB pesados (pool, variants, review, questionReviews) solo en endpoints de detalle. |
| 5 | **Compatibilidad** | `/v1/admin/level-tests/drafts` se conserva; la UI deja de usarlo. `latestJob` en `/status` se conserva y se añade `jobs[]`. |

---

## 2. Contexto verificado (estado actual del código)

- **`level_test_weeks`** (migrations/041 + 046): estados `draft | approved | rejected | retired`; índice único parcial (una fila viva por idioma+nivel+semana); contenido inmutable por trigger (solo cambian `status`, `approved_at`, `approved_by`); borrado prohibido. El endpoint actual `GET /v1/admin/level-tests/drafts` solo lista `draft`+`rejected`, sin filtros.
- **Pipeline de generación**: `runLevelTestGenerationJob(db, env, {languageID, level, weekIndex, force})` (`services/api/src/level-tests-generation-jobs.ts`) ya acepta weekIndex arbitrario, usa advisory lock por clave y registra en `level_test_generation_jobs` (`running|finished|failed|unknown`, attempts, error_code). Con `force`, `persistWeek` retira la fila viva y crea una nueva (`draft` o `approved` según `publish_mode`). Solo existía regeneración por CLI (`leveltests-generate --force --langs --levels`, semana en curso).
- **Resultados**: `level_test_attempts` (account_id, attempt_id, exam_id, state, started/updated/completed_at, answers, result jsonb) guarda por intento el `result` completo que envía la app: `overall`, `sections` (recognition/reading/listening/writing), `passed`, `nextBand`, `weakSections`, `questionReviews` (respuesta/corrección/explicación por pregunta). Join con `accounts` (id, email) y `level_test_attempt_snapshot_ids` (idioma/nivel/semana del contenido). **No existe hoy ningún endpoint admin de resultados.** Los invitados no sincronizan intentos; los intentos se borran al borrar la cuenta (sin huérfanos).
- **Panel** (`services/api/admin-ui/`): SPA vanilla sin build, helpers `api()`, `detail()`, `ask()` (motivo auditado), Tabulator con header filters, auth por cookie + check de Origin (`assertAdminCsrf` para mutaciones, `assertAdminPageOrigin` para lecturas sensibles), auditoría con `recordAdminAction` (motivo 3-500 chars en `admin_actions`).

---

## 3. Feature A — Listado completo de exámenes con filtros

### Backend (en `services/api/src/app.ts`; `adminOnly` + `assertAdminPageOrigin` + `no-store`)

- `GET /v1/admin/level-tests/weeks`
  - Filtros: `status` (CSV de los 4 estados; default todos), `language` (CSV), `level` (CSV), `week_from`/`week_to` (entero YYYYWW), `created_from`/`created_to` (fechas sobre `created_at`), `sort` (whitelist: created_at, week_index, language_id, level, status, approved_at; default `created_at DESC`), `limit` (1-100, default 50), `offset` (≤100k).
  - Respuesta `{items, total, limit, offset}`; item = metadatos: `id, languageID, level, weekId, status, judgeMode, generator, model, variantCount, tokensIn, tokensOut, createdAt, approvedAt, approvedBy`. Sin pool/variants/review.
- `GET /v1/admin/level-tests/weeks/:id` — detalle completo (metadatos + pool + variants + review).
- `GET /v1/admin/level-tests/drafts` — se conserva sin cambios por compatibilidad.

### UI (sección "Generación de exámenes")

La tarjeta "Borradores privados" pasa a "Exámenes generados": barra de filtros (estado, idioma, nivel, rango de semanas, rango de fechas de creación), badges de estado con color, paginación con total, y acciones por fila:

- **Ver** → modal de detalle (helper `detail()` existente).
- **Aprobar / Rechazar** → solo `draft`, flujo actual con motivo auditado.
- **Regenerar** → solo semana actual no publicada (Feature B).

---

## 4. Feature B — Regeneración de la semana actual no publicada

- `POST /v1/admin/level-tests/regenerate` — `assertAdminCsrf` + `adminOnly`.
  - Body: `{languageID, level, weekIndex?, reason}` — reason 3-500 auditado como `level_test.regenerate`; `weekIndex` opcional (default `weekIndex(now)`).
  - Reglas server-side: `weekIndex` debe ser el actual; si existe fila `approved` para la clave → 409 `level_test_week_not_regenerable`; con `draft`/`rejected` o sin fila → procede con `force: true` (retira la fila viva, inserta nueva).
  - Ejecución asíncrona in-process, advisory lock contra CLI/scheduler/otra regen de la misma clave; `202 {started: true}`.
- **Monitor de jobs**: `GET /v1/admin/level-tests/status` ampliado con `jobs[]` (últimos ~10 de `level_test_generation_jobs`, conservando `latestJob`). La tarjeta "Scheduler y última generación" muestra la tabla de jobs con auto-poll (5 s) mientras haya alguno `running`.
- UI: botón "Regenerar" solo en filas de la semana actual con estado `draft`/`rejected`; modal `ask()` con motivo obligatorio.

---

## 5. Feature C — Resultados de exámenes por alumno

Nueva sección del panel "Resultados de exámenes" + endpoints (`adminOnly`, page-origin, `no-store`):

- `GET /v1/admin/level-test-attempts` — join con `level_test_attempt_snapshot_ids` (idioma/nivel/semana autoritativos del contenido) y `accounts` (email). Filtros: `account` (UUID), `email` (contains), `language`, `level`, `state` (in_progress|completed), `passed` (bool), `week_from`/`week_to`, `from`/`to` (sobre `started_at`). Sort whitelist (started_at, updated_at, state, overall). Paginación limit/offset + total. Items: attemptId, accountId, email, examId, source, languageID, level, weekId, state, fechas, overall, passed, sections — sin questionReviews.
- `GET /v1/admin/level-test-attempts/:accountId/:attemptId` — detalle completo (answers + result íntegro). Modal con scores por sección y tabla pregunta/respuesta/corrección; fallback a JSON crudo si el payload no encaja.
- `GET /v1/admin/level-test-attempts/summary` — KPIs (total, completados, nota media, % aprobados, media por sección) y desglose `group=week|level|language` para gráfica de evolución semanal (Chart.js, ya disponible en el panel).
- `GET /v1/admin/level-test-attempts/export` — CSV de la selección filtrada (tope 10.000 filas), descarga vía fetch+blob (compatible con CSP del panel).
- **Vista por alumno**: mismo listado filtrado por `account`/`email` con cabecera del alumno; acción "Ver exámenes" en el menú contextual de la tabla `accounts` del browse que navega a esta sección con el filtro precargado.
- **Privacidad**: solo admins autenticados con check de Origin, `no-store`, sin loguear bodies (regla del server); lecturas no auditadas (consistente con browse); mutaciones auditadas.

---

## 6. Documentación (openspec, en inglés como los changes existentes)

- `openspec/changes/admin-exam-operations/proposal.md` — Intent / Problem / Approach / Out of scope.
- `design.md` — decisiones con racional (regeneración restringida a semana actual no publicada y por qué, async + advisory lock, cero migraciones, exclusiones de JSONB en listados), tabla de endpoints con parámetros/respuestas, diagrama de secuencia de la regeneración.
- `tasks.md` — fases (endpoints listado/detalle → UI filtros → regeneración + monitor → endpoints resultados → UI resultados + acción en browse → agregados + CSV → verificación).
- `specs/admin-exam-management/spec.md` y `specs/admin-exam-results/spec.md` — Requirements SHALL + escenarios WHEN/THEN (listado de todos los estados, cada filtro, detalle, regeneración con sus 409, monitor de jobs, resultados, vista por alumno, export, privacidad).

---

## 7. Fuera de alcance

Editar contenido de exámenes; regeneración parcial por sección/ítem; regeneración de semanas aprobadas o pasadas; blue-green (draft en paralelo al aprobado); cambios en apps Android/iOS; migraciones nuevas; endpoints públicos nuevos; métricas de negocio avanzadas.

---

## 8. Verificación (fase de implementación)

1. `npx tsc --noEmit` (server).
2. Suite de integración con PG efímero (`make test-api`):
   - Filtros del listado de semanas con datos sembrados en los 4 estados; orden y paginación.
   - Regeneración: semana actual draft → ok (job creado, fila retirada + nueva), clave aprobada → 409, semana pasada → 409, concurrencia → job `busy`; auditoría `level_test.regenerate` con motivo.
   - Resultados: joins correctos, cada filtro, summary, export CSV, detalle con questionReviews.
   - Seguridad: `adminOnly`, CSRF/Origin en mutaciones, `no-store` en lecturas.
3. Exporters no aplican (no se tocan `shared/*.json`).

Lo que no pueda verificarse se reportará honestamente en el `verify-report.md` del change.
