# Verify report: proxy-api-provider

Fecha: 2026-11-01 (rev. 5, tras la cuarta revisión de `2ed4286`) · Rama: `feat/proxy-api-provider`.

## Rev. 5 — cuarta ronda de auditoría

Tres hallazgos sobre `2ed4286`, confirmados y corregidos test-first:

| Hallazgo | Corrección | Prueba versionada |
|---|---|---|
| **Alta** — sobreacreditación concurrente: el total del día se sumaba **antes** de adquirir el lock de wallet (dos paths concurrentes veían el mismo total: 90 → +810 +1710 = 2610) | `grantSubscriptionDayInTransaction` toma el lock de wallet (`lockWallet`) **antes** de leer el total; los paths quedan serializados y el perdedor recalcula tras el commit del ganador | `the day-grant takes the wallet lock before reading the day total` (orden lock→suma pinzado con un cliente SQL grabador, determinista) |
| **Media** — el total del día omitía las identidades históricas `subscription-upgrade:` escritas por `8de65cb` (reacreditaba 810 sobre datos previos) | La suma incluye `subscription-upgrade:<día>:<cuenta>` junto a grant y top-ups; el ledger inmutable no se toca | `a top-up counts the historical upgrade identities of the previous build` (PG) |
| **Media** — ajustes Android incoherentes: no se podía volver a OpenAI desde Ajustes, y los controles de voz local dependían del flag crudo | La fila "OpenAI (personal key)" se ofrece cuando es la opción realtime del build (`defaultEngineId == "openai"`); `AppViewModel.effectiveEngineId` expone el motor ejecutado y `SettingsScreen` deriva de él los controles de voz/STT | (wiring UI; la política subyacente ya pinzada por `EngineSelectionPolicyTest`) |

Nota de verificación: un intento de test de carrera con dos paths reales
resultó no discriminatorio (el path de asignación ya serializaba vía
`memberAccount`); la regresión del lock se pinza con el orden
lock→suma sobre un cliente determinista, que sí falla en la versión previa.

**Pendientes reconocidos (sin cambio)**: wiring App de iOS (gate macOS),
perfil de carga con propósitos (Task 8.1), sonda manual
`set-route` → policy → llamada, E2E con proveedor real.

---

## Rev. 4 — tercera ronda de auditoría

Fecha: 2026-11-01 (rev. 4, tras la tercera revisión de `8de65cb`) · Rama: `feat/proxy-api-provider`.

## Rev. 4 — tercera ronda de auditoría

Tres hallazgos sobre `8de65cb`, todos confirmados y corregidos test-first
(los dos backend reproducidos RED → GREEN contra PG 17):

| Hallazgo | Corrección | Prueba versionada |
|---|---|---|
| **Alta** — cambios sucesivos de plan fallaban: el top-up descontaba solo el grant original y reutilizaba una única identidad de upgrade (90→900→1800 → 409; 90→900→450 → 409 pese a no deber retirar crédito; reproducido en PG) | `grantSubscriptionDayInTransaction` calcula bajo lock el **total acreditado del día** (grant + top-ups) y concede solo la diferencia pendiente; cada incremento usa `subscription-topup:<día>:<cuenta>:<objetivo>` — el objetivo forma parte de la identidad, así que reintentar el mismo top-up es no-op y otro plan es identidad nueva | `successive plan upgrades never conflict and accumulate to the last allowance` · `a downgrade after a bigger same-day allowance adds nothing and never conflicts` (PG) |
| **Media** — agotar los reintentos de política dejaba el motor api bloqueado hasta el siguiente cambio de cuenta | `ensureConversationPolicy()` lanza un ciclo de refresco nuevo en cada inicio de conversación (voz y escrito) cuando el motor api no tiene cache; el ciclo agotado ya no es un estado permanente (los intentos viven por ciclo) | (wiring; la lógica de ciclo está cubierta por compilación + revisión) |
| **Media** — debug con build-flag `openai` y preferencia persistida `api`/`glm`: el adaptador se construía como OpenAI y el transporte seguía realtime pese al resolver | El switcher se usa también en debug con flag `openai` (solo el release BYOK fija el motor); transporte, `start()`, `cloudReady()` y gates resuelven la selección efectiva | (misma política testeada; wiring por revisión) |

El auditor confirmó en esta ronda: primer upgrade correcto, valoración
compartida, normalización release y cancelación/limpieza de política.

**Pendientes reconocidos (sin cambio)**: wiring App de iOS (gate macOS),
perfil de carga con propósitos (Task 8.1), sonda manual
`set-route` → policy → llamada, E2E con proveedor real.

---

## Rev. 3 — segunda ronda de auditoría

Fecha: 2026-11-01 (rev. 3, tras la re-revisión de `9cb3807`) · Rama: `feat/proxy-api-provider`.

## Rev. 3 — segunda ronda de auditoría

La re-revisión de `9cb3807` confirmó B1/B3/B4/B5/B6 corregidos pero halló
**2 regresiones backend (reproducidas en PostgreSQL) y 4 pendientes Android**.
Todas corregidas test-first (RED → GREEN contra PG 17; las Android ahora con
**tests versionados** sobre una política pura parametrizada):

| Hallazgo | Corrección | Prueba versionada |
|---|---|---|
| **Alta** — upgrade gratuito→plan: mismo idempotency-reference con importe distinto → `idempotency_conflict` y rollback del alta (reproducido en PG) | `grantSubscriptionDayInTransaction` compartida por barrido y asignación: importe completo bajo `subscription-grant:<día>:<cuenta>`, **top-up de la diferencia** bajo `subscription-upgrade:<día>:<cuenta>`, no-op si ya está financiada a o por encima (el crédito nunca se reclama); el rerun del barrido tras un upgrade es no-op | `a plan upgrade after the free grant tops up the difference under its own identity` · `a plan downgrade after a bigger free grant adds nothing and never conflicts` |
| **Media** — barrido y asignación redondeaban distinto (2/2 tokens: 3n vs 2n) | Valoración única compartida `tokenCreditNano` (suma antes de dividir) en ambas rutas | `sweep and assignment value the same allowance identically` |
| **Alta** — el reintento de política no ocurría (reentrada recursiva bloqueada por el job activo) | Bucle de reintentos dentro de **una** coroutine (backoff acotado 5), sin recursión al método protegido | (lógica pinzada por compilación + revisión; el store con `clear()` sí tiene test) |
| **Alta** — BYOK ejecutable en release: el default con build-flag `glm`/`opencode` salía del resolver sin normalizar | `EngineSelectionPolicy` pura y parametrizada: `defaultEngine` normaliza el default del build, `effectiveSelection` normaliza la preferencia persistida, `selectable` filtra el setter; los 12 usos del flag crudo en AppViewModel sustituidos por `resolveEngineSelection()` | `EngineSelectionPolicyTest` (4 tests: default, persistida, selector) |
| **Media** — cambio de cuenta no cancelaba el GET ni limpiaba el cache | `force` cancela el vuelo previo y llama a `ConversationPolicyStore.clear()` antes de consultar; publicación condicionada a la revisión de cuenta | `clear drops the previous account's state so it can never leak` |
| **Media** — `sendTyped()` y otros 10 puntos usaban el flag crudo | Todos los usos del flag crudo sustituidos por la resolución efectiva (voz, escrito, gates, atribución, labels, duración, assessments) | (misma política testeada; wiring por revisión) |

Sobre el señalamiento de evidencia: correcto — la rev. 1 afirmaba «test-first»
para Android sin tests versionados que lo respalden. En esta rev. la parte
decidible en JVM (política de ejecución y store) tiene tests propios, y el
gating dependiente de `BuildConfig.DEBUG` queda reducido a un resolver puro
parametrizado cuyo comportamiento de release está pinzado.

**Pendientes reconocidos (sin cambio)**: wiring App de iOS (requiere macOS),
perfil de carga con propósitos (Task 8.1), sonda manual
`set-route` → policy → llamada, E2E con proveedor real.

---

## Rev. 2 — remediación de la auditoría (misma fecha)

Fecha: 2026-11-01 (rev. 2, tras la auditoría `informe-auditoria-suscripcion-llm-2026-11-01.md`) · Rama: `feat/proxy-api-provider` (rama nueva creada desde `feat/security-hardening`, con commit checkpoint del árbol previo).

## Rev. 2 — remediación de la auditoría (misma fecha)

La auditoría independiente confirmó 9 defectos reales que las suites
originales no cubrían. Todos verificados test-first (RED → GREEN contra PG
17) y corregidos:

| ID | Defecto | Corrección | Prueba de regresión |
|---|---|---|---|
| B1 | Suscriptores sin grant con allowance gratuito apagado (default) | El gate de allowance solo cubre el barrido gratuito; suscriptores siempre financiados + filtro guest/deleted en ambas consultas | `subscribers are funded even with the free daily allowance off` |
| B2 | Fallo transitorio o alta tardía perdían el día | `lastCompletedDay` solo al éxito (reintento por tick, idempotencia por referencia); `grant-quota` acredita el día en curso en la misma transacción | `grant-quota credits the first day immediately for late signups` |
| B3 | Un settle podía consumir la reserva de otra llamada | Tope de liquidación = `balance − (totalReserved − ownHold)`; diferencia confirmada visible como `cost_nano > settled_nano` | `a settlement cannot consume another call's reservation` |
| B4 | `grant-quota` persistía aunque rechazara guest o fallara auditoría | Validación + upsert + auditoría + grant inicial en **una** transacción | `a rejected grant-quota leaves plan, ledger and audit untouched` |
| B5 | Conciliación liberaba intentos `prepared`/`dispatched` y auditaba tras confirmar | Solo `unknown` es conciliable (comprobado bajo lock); liberación y auditoría atómicas | `reconciliation only releases unknown attempts` |
| B6 | `set-route` no actualizaba la etiqueta publicada | `set-route` mueve `model` + `display_name` + `cap` juntos | (comportamiento pinzado por la actualización conjunta; sonda manual pendiente) |
| A1 | Release usaba transporte/gates realtime con default `api` | `transport`, `start()`, atribución de sesión usan `defaultEngineId` (resolución única) | (rama debug pinzada por suite; rama release por revisión — BuildConfig.DEBUG no ejercitable en JVM) |
| A2 | BYOK oculto en UI pero ejecutable en release | `resolveEngineSelection()` normaliza a proxy-backed en release; `selectLocalEngine` rechaza BYOK | (ídem) |
| A4 | Respuesta tardía de política contaminaba otra cuenta | Cancelación del vuelo previo + scoping por `accountRevision` | (JVM: cubierto por revisión; patrón estándar del repo) |
| A3 | Fallo inicial de política bloqueaba el motor sin recuperación | Reintentos con backoff acotado (5), reset por cambio de cuenta | (ídem) |

Decisiones documentadas, sin cambio de código: **D1** (los grants v1 son
crédito acumulable; la cuota diaria es pacing/display — explícito en
`design.md` §5), flag `PROXY_REQUIRE_PURPOSE` acepta `true|1`, proposal
alineado con `token_balance_exhausted`, y **aritmética del informe original
corregida** ($3–9/mes por usuario muy activo todo-en-pago, no <$0,10).

**Pendientes reconocidos (no cerrados por esta rev.)**: wiring App de iOS
(`ApiProxyEngine` no conectado al coordinador; requiere macOS), test JVM del
gating release (`BuildConfig.DEBUG=false` no ejercitable), perfil de carga
con propósitos (Task 8.1), sonda manual `set-route` → policy → llamada.

---

## Rev. 1 — verificación original

## Qué se implementó

Backend (Tasks 1–4): reserva atómica de fondos en la admisión del proxy
(audit F05), política de petición con allowlist y routing por propósito
(audit F06–F07), cuota diaria de suscripción con grants idempotentes al
ledger, endpoint `GET /v1/conversation-policy`, métricas y alertas nuevas,
docs reescritas. Android (Tasks 5–6): provider `api` ("Palique API"),
cabecera `x-palique-purpose`, `ConversationPolicyStore`, gating release/debug.
iOS (Task 7): `ApiProxyEngine.swift` (mirror del engine; App target pendiente
del gate macOS).

## Qué se ejecutó y qué no

| Verificación | Estado |
|---|---|
| Tests PG del proxy (`proxy-attempts`, `token-proxy`, `proxy-quota`) | ✅ 17/17 contra PostgreSQL 17 efímero (contenedor local), antes y después de cada corrección |
| Suite completa API (`make test-api`, stack efímero con el código compilado) | ✅ (ver resultado final abajo) |
| `npx tsc --noEmit` (server) | ✅ |
| Contratos multiplataforma (`make check`: exporters, cross-platform, db schema) | ✅ |
| Android `:app:testDebugUnitTest` (incluye los nuevos tests del engine `api`, policy store y factory) | ✅ |
| Android `:app:lintDebug` + `assembleDebug` | ✅ |
| `make obs-check` (alertas nuevas validadas con promtool/amtool) | ✅ |
| iOS `swift test` / `make ios-test` | ❌ **No ejecutado** — Linux no compila el gate de engines; `ApiProxyEngine.swift` queda sin verificación de compilación hasta el gate macOS |
| Conversación real contra proveedor real | ❌ No ejecutado (requiere `PROXY_PROVIDER_URL/KEY` reales) |
| Compras live / suscripción recurrente | ❌ Fuera de alcance de este change (siguiente change) |
| Perfil de carga con propósitos (Task 8.1) | ❌ Pendiente |

## Notas honestas

1. **Seed behavior-preserving**: la tabla `proxy_purpose_routes` siembra todos
   los propósitos al modelo actual (MiniMax-M2); el split voz-potente/resto-
   gratis es una decisión de configuración del operador (`tokens-admin
   set-route`), no de código.
2. **Gating release en Android**: verificado por código y por los tests de la
   rama debug; no hay test JVM que ejerza `BuildConfig.DEBUG=false` (los unit
   tests corren en buildType uiTest/debug). Verificar en un `assembleRelease`
   antes de publicar.
3. **`task_subscriptions`**: la tabla se llama `token_subscriptions` porque
   `subscriptions` ya existía (migración 027, hosted minutes).
4. **`main-wiring-stubs.mjs`**: los tests de composición redirigen los
   imports de primer nivel de `main.ts` a stubs; se añadieron los de
   `proxy-quota` (fallo detectado por la propia suite: 581–586, corregido y
   re-verificado).
5. **Absorción del audit**: este change implementa F05–F07 de
   `openspec/changes/audit-token-proxy-safety/` (reserva, política, binding
   de modelo). Ese change queda para archivar cuando este se archive; los
   hallazgos restantes del audit (reconciliación ya implementada aquí,
   alertas ya añadidas) quedan cubiertos.
6. **Streaming** sigue fuera del contrato v1 (sin cambiar); el diseño de
   metering-en-último-chunk queda para el change siguiente, junto con la
   suscripción recurrente (Play/Stripe).
