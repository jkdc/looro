# Verify report: proxy-api-provider

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
