# Informe: auditoría y cierre de huecos de tests (API + panel admin)

Fecha: 2026-10-22
Alcance: `services/api` (rutas HTTP, servicios, CLIs de operación) y el panel
de administración (`admin-ui`), verificado contra PostgreSQL 17 real.

## Resumen ejecutivo

| Métrica | Antes | Después |
|---|---|---|
| Tests de API | 569/569 | **603/603** (0 saltados) |
| Checks E2E del panel | 44 | **57** |
| Cobertura de líneas | alta pero con rutas sin test | **99,26 %** |
| Cobertura de ramas | — | **88,09 %** |
| Cobertura de funciones | — | **96,64 %** |

- **34 tests nuevos** de API y **13 checks E2E nuevos**.
- **1 bug real corregido** en el explorador de tablas del panel (500 → 400).
- **1 error de documentación corregido** en el comando de reproducción del E2E.
- La suite completa queda verde en una sola pasada, sin tests omitidos.

La cobertura de líneas ya era alta al empezar; el trabajo no se guió por el
porcentaje sino por un inventario ruta-por-ruta y rama-por-rama. El plan con
ese inventario está en
`docs/superpowers/plans/2026-10-22-api-test-gap-closure.md`.

## Método

1. Inventario de todas las rutas registradas en `src/app.ts` y de los servicios
   que invocan, cruzado con las menciones reales en `tests/*.test.ts`.
2. Para cada ruta sin test, identificar las ramas de negocio: autenticación,
   validación, límites, idempotencia, errores de proveedor y auditoría.
3. Escribir el test contra PostgreSQL 17 efímero
   (`TEST_DATABASE_URL` terminada en `_test`) usando los harness existentes.
4. Si el test descubre un comportamiento incorrecto, corregir la fuente y
   dejar el test como regresión. No se refactorizó código solo para subir
   cobertura.
5. Medir con
   `npx tsx --test --test-concurrency=1 --experimental-test-coverage`.

## Huecos cerrados

### Rutas HTTP que no tenían ningún test

| Lote | Archivo | Qué se cubrió |
|---|---|---|
| 1 | `tests/admin-routes.test.ts` | `GET /v1/admin/metrics`, `/timeseries` (validación de `set`, clamp y valor por defecto de `days`), `/usage`, `GET /v1/admin/accounts/:id` (200/404/400/401) |
| 2 | `tests/subscriptions.test.ts` | Planes públicos sin `stripePriceID`, `/mine`, `POST /subscriptions/checkout` (tier, clave, precio, transporte, `cache-control`), `SubscriptionBilling.checkout` con URL no segura, `syncPlay`, eventos `updated`/`deleted`, presupuesto vitalicio de goteo |
| 3 | `tests/trial.test.ts` | Elegibilidad: dispositivo nuevo, reintento idempotente, ya reclamado, dispositivo bloqueado, cambio de clave de atestación, ruta con `remaining/403` |
| 4 | `tests/payments-http.test.ts` | `catalogFromEnvironment` válido/inválido, checkout legado (`/v1/checkout`) con delegación/503/401/400, evento de suscripción reclamado antes del reconciliador por minuto |
| 5 | `tests/hosted-http.test.ts` | Estado y cierre de sesión de voz en vivo, 503/401/400 y delegación al propietario |
| 6 | `tests/level-test-attempts.test.ts` | Snapshots `llm` con linaje/variante inválidos, intento previo sin recibo (replay vs. conflicto), ids de examen mal codificados |
| 7 | `tests/level-tests-generation-jobs.test.ts` | Run `running` interrumpido → `unknown`, `finished` sin semana → `unknown`, aborto → `unknown`, fallo inesperado del pipeline → `failed` registrado |
| 8 | `tests/level-tests-scheduler.test.ts` | Timer disparado antes de tiempo (re-arma sin generar), `runWeek` que lanza (contenido y re-armado) |
| 9 | `tests/admin-browse.test.ts` | Filtros `integer`/`bigint` fuera del rango de PostgreSQL (**bug real**, ver abajo), builder y respuesta HTTP 400 |
| 10 | `tests/llm-config.test.ts` | `keyHint` (descifra/enmascara, `null` si no descifra), `maskKey`, `fingerprintSecret` estable y truncado |
| 11 | `tests/minute-providers.test.ts` | Factorías `configuredStripeMinuteProvider`/`configuredPlayMinuteProvider`: ausente, parcial (503) y completo |
| 12 | `tests/admin-cli.test.ts` | Los 5 CLIs de operación sin argumentos (exit 1 + uso), `admins-admin` add/list/passwd/disable, `access-requests-admin` prune/export/delete, `minutes-admin policy`/`set-policy`, `tokens-admin show`/`grant-unlock`/`revoke-unlock`, `account-closeout-admin inspect`, `migrate` (éxito y fallo) |
| 13 | `tests/admin-routes.test.ts` | Mapeo de `ConfigError` del configurador LLM: `invalid_llm_config` → 400 y `llm_master_key_missing` → 503 |

### Casos de borde añadidos a suites existentes

- Suscripciones: estado de Play, concesión única, ciclo de vida y goteo.
- Voz en vivo: cierre por el propietario y errores de configuración.
- Intentos de examen: identidad de variante y conflicto de creación.
- Jobs de generación: reconciliación de estados durables.
- Scheduler: temporización y contención de errores.
- Explorador: valores que PostgreSQL rechaza con `22003`.
- Config LLM: enmascarado y huellas.
- Proveedores de minutos: pares completos/parciales.

## Bug real encontrado y corregido

**Síntoma.** Un filtro del explorador de tablas con un entero fuera del rango
de la columna devolvía **HTTP 500** en lugar de un 400 limpio.

Reproducción (PostgreSQL 17):

```
SELECT $1::integer;  -- '2147483648'  → SQLSTATE 22003
SELECT $1::bigint;   -- '9223372036854775808' → SQLSTATE 22003
```

`assertValueValid` en `src/admin-browse.ts` solo comprobaba la forma
(`/^-?\d{1,19}$/`) y, para `integer`, `Number.MAX_SAFE_INTEGER`. Eso deja pasar
valores que PostgreSQL no puede convertir, y el error de base de datos se
propagaba como 500.

**Corrección.** Los límites ahora son los de la propia columna, comparados con
`BigInt`:

- `integer`: `-2147483648 … 2147483647`
- `bigint`: `-9223372036854775808 … 9223372036854775807`

Un valor fuera de rango lanza `ServiceError('invalid_request')` y la ruta
responde 400. Los tests cubren el builder y la respuesta HTTP real.

## Panel de administración (E2E)

El E2E anterior solo probaba login, navegación, configuración de IA,
generación/revisión de exámenes y resultados. Se añadieron 13 checks que
recorren las vistas que nunca se renderizaban y verifican que sus mutaciones
quedan auditadas:

| Vista | Comprobación |
|---|---|
| Resumen | KPIs presentes y al menos 3 gráficos montados |
| Crecimiento / Voz / Economía | series temporales renderizadas, sin alertas de error |
| Uso de la app | KPIs (DAU/WAU/MAU), funciones más usadas y tabla de retención |
| Explorador de tablas | la cuenta sembrada aparece con su total |
| Política de bienvenida | guardar sin motivo → rechazo del API; con motivo → persiste y sube la versión |
| Planes y suscripciones | ídem para el guardado de planes |
| Campañas | preparar y aplicar 5 minutos a una cuenta: `concedidos 1 / omitidos 0` |
| Auditoría | aparecen `policy.update`, `plans.update`, `campaign.prepare`, `campaign.apply` con el motivo |
| Todas | ningún error de página nuevo durante el recorrido |

**Corrección de documentación.** El comando de reproducción de
`tests/browser/README.md` no arrancaba el servicio `seed-generate`, que es el
que crea las dos semanas en borrador y el job terminado que el E2E espera. Sin
él, la suite fallaba en los checks de monitor y regeneración. El comando del
README ya lo incluye.

## Cobertura final

| Módulo | Líneas | Ramas | Funciones |
|---|---:|---:|---:|
| **Todos los archivos** | **99,26 %** | **88,09 %** | **96,64 %** |
| `app.ts` | 99,59 % | 84,20 % | 96,45 % |
| `trial.ts` | 100,00 % | 100,00 % | 100,00 % |
| `subscriptions.ts` | 100,00 % | 73,77 % | 100,00 % |
| `payments.ts` | 100,00 % | 87,21 % | 92,86 % |
| `admin-browse.ts` | 99,74 % | 89,29 % | 90,00 % |
| `admin-metrics.ts` | 99,50 % | 94,12 % | 100,00 % |
| `level-test-attempts.ts` | 99,76 % | 81,19 % | 100,00 % |
| `level-tests-generation-jobs.ts` | 98,33 % | 86,67 % | 100,00 % |
| `level-tests-scheduler.ts` | 99,44 % | 82,61 % | 86,96 % |
| `llm-config.ts` | 99,38 % | 80,00 % | 100,00 % |
| `subscription-billing.ts` | 97,73 % | 83,72 % | 83,33 % |
| `admins-admin.ts` | 98,20 % | 76,00 % | 100,00 % |
| `access-requests-admin.ts` | 100,00 % | 42,86 % | 100,00 % |
| `account-closeout-admin.ts` | 100,00 % | 88,46 % | 100,00 % |
| `tokens-admin.ts` | 100,00 % | 75,61 % | 100,00 % |
| `migrate.ts` | 100,00 % | 85,71 % | 83,33 % |
| `minutes-admin.ts` | 93,57 % | 80,60 % | 100,00 % |

## Huecos residuales (y por qué no son comportamiento sin probar)

| Líneas | Motivo |
|---|---|
| `level-tests-content.generated.ts` | Archivo generado (datos de contenido); no se edita a mano ni se prueba como lógica. |
| `level-test-settings.ts`, `startup-diagnostics.ts`, `level-tests-validate.ts` | Líneas de tipos/imports y declaraciones sin ejecución propia. |
| `level-tests-generation-jobs.ts:1,110` | Import y declaración de una función exportada (la función sí se ejecuta). |
| `admin-browse.ts:307` | Llave de cierre del bloque `timestamptz`; artefacto de medición por línea. |
| `llm-config.ts:157` | Línea de retorno de una función con 100 % de funciones cubiertas. |
| `level-test-attempts.ts:358` | `decodeURIComponent` con `%` mal formado: Fastify rechaza la URL antes de entrar al handler; la rama es defensiva e inalcanzable por HTTP. |
| `minutes-admin.ts:156-165` | Dispatch de `set-funding`, `set-pricing`, `prepare-grant`, `apply-grant` y `reconcile-sandbox`. Las funciones subyacentes tienen tests directos; solo queda sin ejercitar el cableado CLI de esos cinco comandos (mismo patrón ya probado en `policy`, `set-policy` y `tokens-admin`). |
| `app.ts:672,711-712,823` | Re-lanzamientos y respuestas 502 para errores inesperados, no para contratos de entrada. |
| `subscription-billing.ts:1-2,132` | Imports de solo tipo y re-export. |
| Ramas bajas de `subscriptions.ts`, `access-requests-admin.ts`, `tokens-admin.ts` | Comprobaciones defensivas de forma de JSON y ramas de error que requieren fallos de infraestructura. |

## Qué no se pudo verificar

- **iOS**: el entorno es Linux; el target App no compila aquí (solo el paquete
  Core). No se ejecutaron tests de iOS.
- **Proveedores reales**: Stripe, Play y el proveedor LLM se ejercitan con
  transportes simulados/stub; no hay tráfico externo ni credenciales reales.
- **Regresión visual**: no hay snapshots de imagen; el E2E comprueba contenido,
  selectores y estado, no píxeles.
- **Carga/rendimiento**: no se midieron latencias ni concurrencia bajo carga.

## Reproducción

```bash
# PostgreSQL efímero de tests (una vez)
docker run -d --name looro-tdd-pg -p 127.0.0.1:55432:5432 \
  -e POSTGRES_USER=app_test -e POSTGRES_PASSWORD=tdd-only \
  -e POSTGRES_DB=app_container_test postgres:17-alpine

# Suite completa con cobertura
cd services/api
npx tsc --noEmit
TEST_DATABASE_URL=postgresql://app_test:tdd-only@127.0.0.1:55432/app_container_test \
  npx tsx --test --test-concurrency=1 --experimental-test-coverage tests/*.test.ts

# E2E del panel (stack aislado)
cd services/api/tests/browser
docker compose -f compose.yaml down -v
docker compose -f compose.yaml up -d --build database migrate api stub-llm caddy bootstrap seed-generate
docker compose -f compose.yaml logs -f bootstrap seed-generate   # esperar ambos
cd /home/joaquin/Proyectos/looro-test/looro
node services/api/tests/browser/admin-e2e.mjs
docker compose -f services/api/tests/browser/compose.yaml down -v
```

## Archivos tocados

- `services/api/src/admin-browse.ts` — límites `integer`/`bigint` con `BigInt`.
- `services/api/tests/admin-browse.test.ts` — builder y HTTP 400.
- `services/api/tests/admin-cli.test.ts` — CLIs de operación.
- `services/api/tests/admin-routes.test.ts` — métricas, detalle de cuenta y errores de config LLM.
- `services/api/tests/subscriptions.test.ts` — planes, checkout y ciclo de vida.
- `services/api/tests/trial.test.ts` — elegibilidad completa.
- `services/api/tests/payments-http.test.ts` — catálogo, checkout legado y webhook.
- `services/api/tests/hosted-http.test.ts` — sesiones de voz.
- `services/api/tests/level-test-attempts.test.ts` — snapshots y creación.
- `services/api/tests/level-tests-generation-jobs.test.ts` — reconciliación y fallo inesperado.
- `services/api/tests/level-tests-scheduler.test.ts` — temporización y contención.
- `services/api/tests/llm-config.test.ts` — enmascarado y huellas.
- `services/api/tests/minute-providers.test.ts` — factorías de proveedores.
- `services/api/tests/browser/admin-e2e.mjs` — 13 checks nuevos.
- `services/api/tests/browser/README.md` — comando de reproducción corregido.
- `docs/superpowers/plans/2026-10-22-api-test-gap-closure.md` — inventario y plan.
- `e2e-evidence/t11-browser-e2e/` — capturas del recorrido completo.
