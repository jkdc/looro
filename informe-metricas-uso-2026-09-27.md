# Informe: métricas de uso en toda la app → panel de administración

Fecha: 2026-09-27 · Rama: `feature/rebranding-looro` · Estado: **diseño, pendiente de "lanza sdd"**

## Objetivo

Saber qué usa de verdad la gente dentro de la app (hablar, temas, Learn, Words, práctica, replay…) y verlo en el panel de administración, hoy limitado a métricas de negocio (voz alojada, dinero, suscripciones).

## Estado actual

### Lo que el backend ya ve (y el panel ya pinta)

`services/api/src/admin-metrics.ts` define 4 series temporales + snapshot, **todas derivadas de tablas transaccionales existentes** (cero ingesta nueva):

| Set | Métricas | Fuente |
|---|---|---|
| overview | signups, sesiones de voz, órdenes, volumen, waitlist | `accounts`, `hosted_sessions`, `minute_purchase_orders`, `access_requests` |
| growth | invitados, bienvenidas, informes IA, "activos" | `minute_entries` (proxy de actividad) |
| voice | sesiones creadas/cerradas, ms cobrados, helpers + coste | `hosted_sessions`, `hosted_helper_requests` |
| economy | órdenes, minutos concedidos/consumidos, suscripciones | `minute_entries`, `subscriptions` |

El panel (`services/api/admin-ui/`, vanilla JS + Chart.js + Tabulator) tiene 9 pestañas y rendered perfecto para añadir otra serie. `admin-browse.ts` deja explorar 20 tablas crudas.

### Lo que el backend NO ve (el hueco real)

- **Uso de features**: Learn (lecciones/práctica SRS/assessments), Words (lookups, práctica), temas, replay-aloud, velocidad TTS, ayuda… Todo vive en local (`SessionRecord`, `LearningRepository.kt`, iOS `Storage.swift`); de eso solo sale algo si hay sesión de voz alojada.
- **Modo BYOK/local**: sin `MANAGED_API_ORIGIN` no hay origen al que hablar; esas sesiones son invisibles.
- **Retención real**: "Activos 30 días" es un proxy del ledger de minutos, no DAU/cohorts.
- **Voz vs teclado**: no se distingue cómo entra el turno.

`meter.ts` rechaza explícitamente uso enviado por la app (solo acepta sideband del proveedor confiable), así que la ingesta necesita un endpoint propio autenticado. Las apps no tienen ningún SDK ni canal de telemetría hoy (verificado: sin Firebase/Amplitude, sin reporting genérico).

## Principios de diseño

1. **Sin contenido de usuario, jamás.** Nada de texto de conversación, ni la palabra buscada en el diccionario, ni texto libre. Solo un catálogo cerrado de nombres de evento + props con valores de catálogos de la app (ids de curso/lección/tema) y buckets numéricos.
2. **Un solo canal**: el que ya existe. Bearer de cuenta (incluye invitados, igual que `v1/account`), por el origen gestionado ya configurado. Sin SDK de terceros, sin origen nuevo.
3. **Eventos crudos, no agregados precalculados.** Con el volumen pre-lanzamiento, una tabla append-only da funnels, cohortes y explorable gratis; el rollup diario se añade después si el volumen lo pide.
4. **Fallo silencioso**: la telemetría no puede romper nunca una feature. Envío fire-and-forget con cola local.

## Diseño

### 1. Catálogo cerrado de eventos (~16)

Definido como dato compartido (enum + props permitidos) con **paridad Kotlin/Swift vía `shared/fixtures` + `check_cross_platform.py`**, y revalidado en el backend (el BE es la última instancia: rechaza lo que no conoce).

| Evento | Props (cerrados) | Enciende |
|---|---|---|
| `app_open` | platform, appVersion, providerReady | arranque/foreground |
| `session_started` | input: voice·typed · provider: hosted·byok · engine | `AppViewModel.start()` / `ConversationCoordinator.start()` |
| `session_ended` | durationBucket (s), turnsBucket, themeID | `end()` |
| `session_resumed` | — | `resumeSession()` |
| `theme_selected` | themeID | cambio de tema |
| `lesson_started` / `lesson_completed` | courseID, lessonID (temario versionado) | Learn / `CloudAction.StartLesson` |
| `assessment_completed` | courseID, passed | `FinalAssessmentQueue` |
| `practice_completed` | mode: recall·word · scoreBucket | `RecallEngine` / `WordPractice` |
| `word_lookup` | source: talk·words·lesson (**sin término**) | `lookup()` |
| `replay_used` | — | `replayReply()` |
| `tts_speed_changed` | value (0.6·0.8·1.0·1.2) | Settings |
| `help_requested` | — | `help()` |
| `report_requested` | — | informe IA |
| `paywall_viewed` | reason | compra de minutos |

Los ids de curso/lección/tema son datos config versionados (foundations-temario, temas), no contenido de usuario: seguras y valiosas para funnels. Los buckets (duración, turnos, score) evitan duraciones exactas y dejan histogramas.

### 2. Cliente (apps)

- Capa `UsageEvents` en core (Kotlin en `apps/android/.../core/`, Swift en `apps/ios/Core/`, paridad obligatoria): `record(name, props)` escribe en un **outbox persistido JSON** — mismo patrón que `FinalAssessmentQueue`/`StripePurchaseAttemptStore`, sin WorkManager nuevo.
- **Flush**: batch (≤100 eventos) al abrir la app, cada ~25 eventos encolados, y al cerrar sesión de voz. Retry con backoff; TTL local de 7 días; tope de cola (p. ej. 2.000) para no crecer sin fin.
- **Cuándo envía**: solo si hay origen gestionado configurado y bearer de cuenta (la app ya inicia sesión de invitado al entrar al ecosistema gestionado). BYOK puro sin managed API: la cola se descarta con tope — sin origen no hay canal honesto, y abrir un endpoint público anónimo sería una puerta de spam.
- Puntos de enganche (pocos y ya existentes):
  - Android: el dispatcher de `CloudAction` en `ui/AppRoot.kt` cubre Talk/Lookup/Tema/Li¡cción/Ayuda de una vez; ciclo en `AppViewModel.start/end/resumeSession/replayReply`; Learn/Words en sus VMs.
  - iOS: `ConversationCoordinator.start/end/chooseTheme/selectLesson` + `LearningStore.save/recordLessonAttempt`.

### 3. Backend

- **Migración `028_usage_events.sql`**:
  ```sql
  CREATE TABLE usage_events (
    id uuid PRIMARY KEY,
    client_event_id uuid NOT NULL,
    account_id uuid NOT NULL REFERENCES accounts(id),
    name text NOT NULL REFERENCES usage_event_catalog(name),
    platform text NOT NULL CHECK (platform IN ('android','ios')),
    app_version text NOT NULL,
    props jsonb NOT NULL DEFAULT '{}'::jsonb,
    client_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
  );
  CREATE UNIQUE INDEX usage_events_client_id_idx ON usage_events(client_event_id);
  CREATE INDEX usage_events_name_time_idx ON usage_events(name, created_at);
  CREATE INDEX usage_events_account_time_idx ON usage_events(account_id, created_at);
  ```
  Con tabla catálogo `usage_event_catalog` (name + versión de props) y trigger de inmutabilidad como `minute_entries` (los eventos no se editan). Retención 180 días con borrado programado (mismo runner que las limpiezas existentes).
- **`POST /v1/usage/events`** (en un módulo nuevo `usage.ts`): batch ≤100, `bodyLimit` 64 KB, `authenticate(db, authorization, true)` — **invitados incluidos** (son el onboarding mayoritario y es donde más importa medir). Valida catálogo y forma de props, INSERT idempotente por `client_event_id` (`ON CONFLICT DO NOTHING`), responde `{accepted}`. Rate limit por el admission existente. Nada de este endpoint toca `meter.ts` ni el ledger de dinero.
- **Panel**:
  - Set nuevo `'usage'` en `TIMESERIES_SETS`: DAU (`count(DISTINCT account_id)` con `app_open`), sesiones iniciadas por provider, lecciones completadas, práctica, lookups, replay.
  - Pestaña **"Uso"** nueva: KPIs (DAU/WAU/MAU, sesiones hoy, lecciones 7d) + **retención por cohorte D1/D7/D30** (signup cohort × `app_open`) + top features 7d. Las cohortes son la única query nueva con algo de chicha; el resto reutiliza el helper `daily()` de `admin-metrics.ts`.
  - `usage_events` y `usage_event_catalog` a `BROWSE_TABLES` (exploración cruda gratis) y últimos eventos en el informe de cuenta (`accountCloseoutReport`).

### 4. Privacidad

- Catálogo cerrado sin contenido: no hay campo donde meter texto de usuario (el BE lo rechazaría).
- La política de privacidad debe listar "métricas de uso agregadas (sin contenido de conversaciones)".
- Toggle en **Ajustes → métricas de uso** (default activado): barato en las apps (la capa ya es centralizada) y limpio de cara al usuario.

## Fases SDD (cada una con tests y veredicto honesto)

1. **F1 — Backend**: migración 028 + `usage.ts` (endpoint + validación de catálogo) + tests (receta docker habitual). Verificable en local al 100%.
2. **F2 — Apps**: capa `UsageEvents` con paridad cross-platform + outbox + cliente de envío + hooks en los puntos de arriba. Tests Android (las 413+ deben seguir verdes + nuevas); iOS verificado por reasoning/compilación con gates Mac/dispositivo pendientes, como siempre.
3. **F3 — Panel**: set `usage`, pestaña Uso con cohortes, browse, detalle de cuenta. Verificación: curl con cookie admin + revisión visual del panel.

Orden F1→F2→F3: cada fase es útil sin las otras (endpoint probado con curl; apps encolan aunque el panel no pinte aún).

## Decisiones abiertas (con mi recomendación)

1. **¿Invitados incluidos?** → **Sí**: si no, perdemos justo el funnel de adquisición. El bearer de invitado ya existe.
2. **¿Opt-out en Ajustes?** → **Sí** (recomendado); si prefieres ahorrar la F2, se puede posponer sin tocar el esquema.
3. **Retención** → 180 días, prorrogable.
4. **¿Contar términos buscados en Words?** → **No** (contenido de usuario). Si algún día hace falta, hash con salt rotada como los nonces, pero hoy no vale lo que cuesta.

## Veredicto

El 80% del valor (ver uso de features y retención en el panel) sale con un pipeline pequeño y honesto: una tabla append-only, un endpoint batch reutilizando la autenticación existente, un outbox sin dependencias nuevas en las apps, y una pestaña más en un panel que ya sabe pintar series. Los riesgos son bajos y conocidos: volumen (tope de cola + retención) y privacidad (catálogo cerrado, sin contenido). Listo para lanzar SDD por fases cuando digas.

---

*Nota de árbol: hay cambios sin commit en `services/api/src/app.ts` y `services/api/src/subscriptions.ts` (trabajo de suscripciones previo). El SDD debe partir de ellos commiteados o arrancar en rama limpia para no mezclar.*

---

## Ejecución SDD (2026-09-27) — veredictos por fase

**F1 backend — HECHA (3dd5593).** Migración `028_usage_events.sql` (tabla append-only, catálogo cerrado en CHECK, gatillo anti-UPDATE, índices), módulo `src/usage.ts` (parser de lotes con throws inline — tsgo no estrecha tipos tras helpers `never`, INSERT multi-fila idempotente por `client_event_id`, prune 180 días), `POST /v1/usage/events` con `authenticate(..., allowGuest=true)`. Suite completa: **405/405**.

**F2 apps — HECHA (d388c86).** Capa core espejada Kotlin/Swift (`UsageMetrics.kt` / `UsageMetrics.swift`: catálogo, buckets gruesos, outbox con tope 2.000/TTL 7 días, reporter con flush que restaura en fallo), clientes `UsageClient` (OkHttp / URLSession contra el origen gestionado, bearer de miembro o de invitado), y `usageMetricsEnabled` en Preferences (opcional, paridad + fixture compartido actualizado, toggle en Ajustes de ambas plataformas). Hooks Android: app_open, session_started (voz/teclado × hosted/byok × motor), session_ended (cubos de duración/turnos/tema), lesson_started/completed, session_resumed, theme_selected, word_lookup (sin término), replay_used, tts_speed_changed, help_requested, report_requested, assessment_completed, practice_completed (fin de ronda de Recall), paywall_viewed. Hooks iOS: app_open, session_started, session_ended, lesson_started/completed, theme_selected, help_requested, word_lookup. Android **428/428** + `check_cross_platform.py` verde + Xcode project regenerado. **iOS razonamiento-solamente** (gate Mac pendiente, como siempre); quedan sin enganchar en iOS: assessment/practice/paywall/report (superficies distintas o trabajo futuro).

**F3 panel — HECHA (ae0f061).** Set `usage` en TIMESERIES_SETS (DAU, sesiones, lecciones, repaso, búsquedas, repeticiones), `GET /v1/admin/metrics/usage` (KPIs DAU/WAU/MAU, top features 7d, retención por cohorte de registro D1/D7/D30), pestaña "Uso" en el panel, `usage_events` en el explorador de tablas. **Hallazgo durante F3**: el FK de la 028 habría bloqueado el borrado duro de cuentas signup-only (auth.ts hace DELETE) → migración `029` con ON DELETE CASCADE + test (los eventos se van con la cuenta: correcto también en privacidad). Suite completa: **408/408**. Verificado EN VIVO en https://admin.localhost/#usage con eventos sintéticos de desarrollo (1.932, marcados `app_version='1.0.0 (dev-seed)'`, borrables con un DELETE por app_version).

**Pendiente (fuera de este SDD):** gates macOS/compilación iOS y deploy a dispositivo; mención de "métricas de uso anónimas" en la política de privacidad real; los datos de producción llegarán cuando las apps actualizadas envíen su primer lote.
