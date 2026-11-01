# Verify report: proxy-api-provider

Fecha: 2026-11-01 · Rama: `feat/proxy-api-provider` (rama nueva creada desde
`feat/security-hardening`, con commit checkpoint del árbol previo).

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
