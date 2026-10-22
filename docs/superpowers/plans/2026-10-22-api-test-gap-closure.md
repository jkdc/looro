# Plan: cierre de huecos de tests en la API y el panel

Fecha: 2026-10-22
Estado: aprobado por el usuario («cuando lo tengas todo implementa todos los tests»)

## Evidencia de partida

- `npx tsx --test --test-concurrency=1 --experimental-test-coverage tests/*.test.ts`
  contra PostgreSQL 17 efímero: **569/569**, 0 saltados.
- Cobertura de líneas alta (app.ts 95,3 %, casi todos los módulos al 100 %),
  pero la cobertura de líneas **no** prueba comportamiento. El análisis
  comportamiento-a-ruta revela rutas y ramas sin ningún test.

### Rutas HTTP sin test (ninguna mención en `tests/*.test.ts`)

| Ruta | Qué hace | Riesgo |
|---|---|---|
| `GET /v1/admin/metrics` | KPIs del panel | panel roto sin aviso |
| `GET /v1/admin/metrics/timeseries` | series por conjunto | ídem |
| `GET /v1/admin/metrics/usage` | uso/cohortes | ídem |
| `GET /v1/admin/accounts/:id` | informe de cierre de cuenta | dinero/datos |
| `GET /v1/subscriptions/plans` | catálogo público de planes | fuga de price IDs |
| `GET /v1/subscriptions/mine` | suscripción del usuario | contrato app |
| `POST /v1/subscriptions/checkout` | alta de suscripción | dinero |
| `POST /v1/checkout` | checkout legado | dinero |
| `GET /v1/live/sessions/:id` | estado de sesión de voz | app |
| `POST /v1/live/sessions/:id/close` | cierre de voz | dinero |
| Webhook Stripe (rama suscripciones) | eventos de suscripción | dinero |

### Ramas/funciones sin test

| Módulo | Casos que faltan |
|---|---|
| `subscription-billing.ts` | `checkout` completo (tier, precio, clave, URL https), `syncPlay` (estado, tier, grant), `customer.subscription.updated/deleted` (referencia desconocida, cancelación, grant) |
| `trial.ts` | camino feliz de `trialEligibility`, dispositivo bloqueado, clave de atestación distinta, idempotencia |
| `subscriptions.ts` | presupuesto vitalicio de goteo agotado |
| `payments.ts` | `catalogFromEnvironment` (precios/fees válidos e inválidos) |
| `level-tests-generation-jobs.ts` | run `running` interrumpido → `unknown`; `finished` sin semana → `unknown`; aborto → `unknown` |
| `level-tests-scheduler.ts` | timer disparado antes de tiempo (re-arma); `runWeek` lanza y el scheduler sobrevive |
| `admin-browse.ts` | enteros fuera de rango, bigint de 20 dígitos, timestamptz inválido/largo |
| `level-test-attempts.ts` | snapshots `llm` con generationId/variant/examId inválidos; conflicto con intento previo sin recibo; id de examen mal codificado |
| `llm-config.ts` | `keyHint` (descifra/enmascara, null si no descifra), `fingerprintSecret` |
| `minute-providers.ts` | `configuredStripeMinuteProvider` / `configuredPlayMinuteProvider` (ausente, parcial, completo) |
| CLIs de operación | `admins-admin`, `access-requests-admin`, `minutes-admin`, `tokens-admin`, `account-closeout-admin`, `migrate` |

### Panel: vistas que el E2E nunca ejerce

El único E2E (`tests/browser/admin-e2e.mjs`, 44 checks) cubre login, nav,
configuración de IA, generación/revisión de exámenes y resultados. Nunca
verifica: Resumen, Crecimiento, Voz, Economía, Uso, Tablas, Auditoría,
Política de bienvenida, Planes ni Campañas.

## Batches (TDD: test primero, rojo esperado, implementación solo si aparece un bug)

1. `tests/admin-routes.test.ts` — métricas/timeseries/usage y detalle de cuenta.
2. `tests/subscriptions.test.ts` — plans/mine/checkout por HTTP, checkout y
   syncPlay del servicio, eventos updated/deleted, presupuesto de goteo.
3. `tests/trial.test.ts` — elegibilidad completa.
4. `tests/payments-http.test.ts` — catálogo desde entorno, checkout legado,
   rama de suscripciones del webhook.
5. `tests/hosted-http.test.ts` — estado y cierre de sesión de voz.
6. `tests/level-test-attempts.test.ts` — bordes de snapshots/creación/examen.
7. `tests/level-tests-generation-jobs.test.ts` — runs interrumpidas/abortadas.
8. `tests/level-tests-scheduler.test.ts` — early fire y error del run.
9. `tests/admin-browse.test.ts` — validación de magnitudes.
10. `tests/llm-config.test.ts` — helpers de clave.
11. `tests/minute-providers.test.ts` — factorías de proveedores.
12. `tests/admin-cli.test.ts` — entrada de los CLIs de operación.
13. `tests/browser/admin-e2e.mjs` — vistas administrativas restantes y una
    mutación auditada por vista comercial.

## Verificación

- `npx tsc --noEmit`.
- Suite API completa contra PostgreSQL 17 efímero (569 + nuevos).
- E2E desde pila limpia con rebuild (44 + nuevos).
- Informe final en `informe-api-tests-2026-10-22.md` con lo cubierto, los bugs
  encontrados y lo que no se pudo verificar.
