# Informe: login + contabilización de tokens + corte por cuota — 2026-10-06

Pregunta que responde este informe: **¿qué queda para que el usuario haga login,
se empiece a contabilizar su uso de tokens y se le limite el uso cuando agote lo
asignado?** Análisis sobre la rama `feature/learning-platform` (HEAD `2c69b6a`),
con verificación directa del código del backend y de ambas apps móviles.

## 1. Resumen ejecutivo

**El esqueleto técnico del flujo completo ya existe, está probado (446 tests de
servidor) y cableado de punta a punta en ambas apps.** No hay que construir el
mecanismo: hay que (1) encenderlo en despliegue con la configuración de
operador, (2) crear un mecanismo de *asignación* de cuota — hoy la única vía de
dar saldo nanoUSD o el unlock es una compra en entorno **live**, no existe
grant de administrador —, (3) persistir el conteo de tokens por cuenta (hoy
solo queda el asiento `settle` en el ledger, no un registro de tokens) y (4)
validar el `usage` que reporta el proveedor antes de cobrar.

El flujo tal como está diseñado y codificado hoy:

```
login (Google federado, bearer 24 h)
  → POST /v1/proxy/responses con Authorization: Bearer
  → 403 unlock_required si la cuenta no compró el byok_unlock
  → admission: saldo disponible ≤ 0  → 402 token_balance_exhausted
  → estimación pre-llamada > saldo   → 402 token_balance_exhausted
  → llamada al proveedor con la clave del servidor
  → settle: tokens reportados × tarifa activa → débito nanoUSD en ledger
  → saldo a cero ⇒ la siguiente llamada recibe 402
  → apps: banner "saldo agotado" + botón "Get tokens"
```

Todo eso ya está implementado. Lo que falta, por bloques, se detalla abajo.

## 2. Lo que YA está (inventario)

### 2.1 Login — backend completo, clientes casi cerrados

| Pieza | Estado | Evidencia |
|---|---|---|
| Auth federada Google/Apple (challenge → OAuth con nonce → exchange, verificación JWKS, nonce timing-safe, `azp` Android) | ✅ Completa | `services/api/src/auth.ts:19-97`, rutas en `src/app.ts:411-423` |
| Sesiones bearer opacas (hash sha256 en BD, 24 h, máx 10, revocación total en sign-out) | ✅ | `migrations/001_billing.sql:1-25`, `auth.ts:56-119` |
| iOS: login Google real **verificado en iPhone** (12-sep-2026); sesión en Keychain | ✅ (parcial en verificación) | `docs/managed-accounts.md:5`, `ManagedAccountClient.swift:213-246` |
| Android: login Google nativo (Credential Manager), sesión cifrada Keystore, UI `AccountSheet` | ✅ Código completo, **sin verificar en instalación Play** | `docs/android-accounts.md:5`, `MainActivity.kt:157-190`, `AccountSessionStore.kt` |
| Identidad guest (instalación → cuenta `is_guest` con minutos de bienvenida) y transferencia guest→miembro al hacer login (inmediata o diferida con worker 60 s) | ✅ (solo Android; iOS excluido por diseño) | `guest-minutes.ts:14-178`, `MainActivity.kt:167-184` |
| Rate-limit durable por operación y red anonimizada (challenge 60/h, exchange 120/h…) | ✅ | `auth-admission.ts:16-21` |

### 2.2 Contabilización y cobro de tokens

| Pieza | Estado | Evidencia |
|---|---|---|
| Proxy de tokens `POST /v1/proxy/responses` (relay con clave del servidor, no-streaming v1) | ✅ | `token-proxy.ts:42-106`, `app.ts:441-451` |
| Tarifas versionadas en BD (`rate_versions`, sembrada `2026-09-minimax-m`: $0.30/M in, $1.20/M out) | ✅ | `migrations/025_billing_tokens.sql:14-15`, `token-proxy.ts:35-40` |
| Settlement por llamada: tokens reportados × tarifa → asiento `settle` en ledger inmutable → débito de `wallets.balance_nano` (BigInt nanoUSD) | ✅ | `token-proxy.ts:94-105`, `ledger.ts:59-75` |
| Respuesta al cliente con `metering: { rateVersion, nanoUSDSettled }` | ✅ | `token-proxy.ts:105` |
| Contabilidad 100% ligada a la cuenta (login), no a la instalación: el proxy resuelve la cuenta del bearer; invitados no pueden comprar unlock | ✅ | `app.ts:444`, `pack-products.ts:133` |
| Telemetría de uso por producto (`usage_events`: DAU, lecciones…; outbox durable en cliente, drenaje on-flush, IDs de evento por instalación) | ✅ (pero es telemetría de producto, **no** facturación de tokens) | `usage.ts:101-116`, commits `5979741`, `ec1f0c5` |

### 2.3 Límite cuando se agota lo asignado

| Pieza | Estado | Evidencia |
|---|---|---|
| Corte pre-llamada: saldo disponible ≤ 0 → **402 `token_balance_exhausted`** | ✅ | `token-proxy.ts:78-79` |
| Corte pre-llamada: estimación (input estimado + 4.000 tokens de salida por defecto) > saldo → **402** | ✅ | `token-proxy.ts:81-83` |
| Gracia acotada: una llamada que cueste más que el saldo se cobra hasta el saldo (nunca negativo); saldo a cero rechaza la siguiente | ✅ | `token-proxy.ts:98-104` |
| Gate de unlock: sin `entitlements.unlocked_at` → **403 `unlock_required`** | ✅ | `app.ts:445-446` |
| Cap de concurrencia por cuenta (429 `account_busy`), body 32 KB, `stream:true` rechazado, proxy apagado sin config → 503 | ✅ | `token-proxy.ts:60-64`, `app.ts:442-443` |
| Android: mapeo de 402→banner "saldo agotado", 403→"unlock", 429→reintentable + botón **Get tokens** → hoja de compra; chip de saldo vía `GET /v1/wallet` | ✅ | `OpenAICompatibleEngine.kt:178-195`, `AppRoot.kt:323-331`, `TokensCommerce.kt:14-37` |
| iOS: espejo completo (`HostedProxyEngine`), 401→sign-in, 402→banner + "Get tokens", ciclo de re-attach de sesión al renovar login | ✅ (sin gate de compilación macOS aún) | `HostedProxyEngine.swift:22-45,125-137`, `ConversationCoordinator.swift:313-387` |
| Corte equivalente en la otra economía (minutos de voz): 402 `insufficient_minutes`, cierre de sesión a mitad por `funding_reversed`/`usage_limit` | ✅ | `hosted-voice.ts:127-133,355,427-428`, `meter.ts:14-35` |

## 3. Lo que FALTA, por bloques

### 3.1 Encender el login en un despliegue real (operacional, no código)

1. **`ACCOUNTS_ENABLED=true`** + `ACCOUNTS_HMAC_KEY` y `ACCOUNTS_PROXY_TOKEN`
   (64-hex, distintos) + client IDs de Google configurados; si falta algo, el
   proceso no arranca. Sin el flag, **toda** ruta de cuenta responde
   503 `accounts_unavailable` (`auth-admission.ts:9-15`, `main.ts:99-105`).
   Runbook: `services/api/docs/enable-accounts.md` (incluye la config de Caddy:
   las rutas de cuenta exigen cabeceras de trusted proxy o
   `ACCOUNTS_ALLOW_LOCAL_LOOPBACK=true` en local).
2. **Login Google en Android sin verificar en instalación real**: el certificado
   Play está registrado en el OAuth client, pero "A successful Play-signed
   login is still unverified" y la primera transferencia guest→miembro en vivo
   está pendiente (`verification/android-release-progress.md:62,70-71`). Hace
   falta configurar `GOOGLE_ANDROID_SERVER_CLIENT_ID`/`GOOGLE_ANDROID_CLIENT_IDS`
   en el servidor y `palique.apiOrigin`/`palique.googleServerClientID` en
   `local.properties` del app.
3. **Verificaciones de dispositivo iOS pendientes**: persistencia de sesión,
   sign-out, expiración y borrado (`docs/managed-accounts.md:5,62-71`).
4. **Sign-in con Apple bloqueado**: backend 503 sin revoker (`app.ts:420`) y el
   botón iOS oculto hasta que exista enrollment de pago de Apple Developer
   (`docs/managed-accounts.md:34`).
5. Menor: el `README.md:102` raíz aún dice que la integración Android "está en
   progreso" — está implementada; falta solo verificación (doc desactualizada).

### 3.2 Encender el proxy de tokens (operacional, casi nada de código)

1. **`PROXY_PROVIDER_URL` + `PROXY_PROVIDER_KEY` sin setear ⇒ la ruta responde
   503 `proxy_unavailable`** (`token-proxy.ts:18-20`, `app.ts:442-443`).
   `compose.yaml:34-36` los reenvía, pero **no aparecen en `.env.example`** —
   añadirlos documentados (gap fácil de cerrar).
2. El proveedor debe exponer un endpoint OpenAI-compatible con el modelo
   `MiniMax-M2` (hardcodeado en ambas apps; un desajuste sale como 502 sin
   impacto de cobro) — `docs/token-billing.md:100-107`.
3. `compose.yaml` no reenvía `PROXY_MAX_CONCURRENT` (gap documentado en
   `openspec/changes/token-billing/verify-report.md:59-63`).

### 3.3 Mecanismo de ASIGNACIÓN de cuota al usuario — **el hueco central**

Hoy "lo asignado al usuario" solo puede llegar por compra en entorno **live**:

- **Unlock**: únicamente la compra `byok_unlock` ($6.99) con recibo de entorno
  live lo activa; los recibos sandbox registran estado pero **nunca**
  desbloquean (`pack-products.ts:213-223`). Sin unlock, el usuario logueado no
  puede usar el proxy en absoluto (403).
- **Saldo**: únicamente el pack `token_pack` ($4.99 → $2.16 de saldo), que
  exige comercio activo (`APP_MINUTE_COMMERCE_ENABLED` + catálogo protegido +
  `APP_MINUTE_SALES_ENABLED`, hoy `false` por defecto).
- **No existe grant de administrador de nanoUSD ni de unlock.** El enum
  `unlock_source` ya reserva el valor `'campaign'`
  (`migrations/025_billing_tokens.sql:5`) y la documentación lo menciona
  ("a campaign grant can also set it", `docs/token-billing.md:39`), pero
  **ninguna ruta de código lo escribe** — las campañas de admin solo regalan
  minutos (`minutes-admin.ts:77-137`). Es el camino natural para "asignar
  tokens a un usuario" sin depender de tiendas: implementar el grant campaign
  de unlock y/o de saldo nanoUSD (vía CLI estilo `minutes-admin` o endpoint
  admin, siempre por el ledger).
- La oferta de bienvenida por signup (`POST /v1/minutes/welcome`) **no tiene
  llamador** en ningún cliente y la política por defecto está a presupuesto
  cero (`docs/conversation-minutes.md:9-15`): hoy hacer login no otorga nada.

**Conclusión del bloque**: para el flujo "login → uso contado → corte al
agotar lo asignado" sin depender de Google Play/Stripe live, falta decidir e
implementar la vía de asignación (grant admin de unlock + saldo). Es la única
pieza de funcionalidad nueva realmente requerida; el resto del mecanismo ya
está.

### 3.4 Contabilización durable del uso de tokens (registros, no solo dinero)

Hoy el único rastro de una llamada es el asiento `settle` del ledger (nanoUSD)
más el `metering` devuelto al cliente. Es auditable financieramente pero no es
un registro de uso:

1. **No se persisten los tokens de ninguna llamada.** La tabla legacy
   `usage_records` (con columnas `input_tokens`/`output_tokens`,
   `migrations/001_billing.sql:89-103`) no tiene escritores en `src/`.
2. **No existe agregado por cuenta/día** de tokens o nanoUSD gastados: los
   timeseries del panel (`admin-metrics.ts:6,75-97`) cubren minutos y eventos
   de producto, pero nada suma los `settle` del ledger.
3. Si se quiere "ver cuánto usó cada usuario", hay que escribir el registro
   por llamada (tokens in/out, tarifa, nanoUSD) o el agregado diario, y
   exponerlo en métricas admin.

### 3.5 Robustez del cobro (bugs conocidos, prioridad alta si se activa en real)

1. **El proxy confía a ciegas en `payload.usage`** (`token-proxy.ts:94-97`):
   `usage` ausente ⇒ coste 0 (llamada gratis); no numérico ⇒ excepción no
   manejada (500 y respuesta perdida, sin débito); negativo ⇒ sin débito. No
   se valida contra la estimación pre-llamada ni contra cotas de sanidad.
   Ya recogido en `informe-analisis-2026-10-06.md:166-168`.
2. **El rate pinning es más débil de lo documentado**: el settle siempre usa
   la tarifa *activa* (`token-proxy.ts:80`), no la pineada por el pack; el pin
   queda solo como registro de auditoría. Una tarifa nueva reinterpreta saldos
   ya pagados (contradice `docs/token-billing.md:69-72`).
3. **Sin reserva (hold) en el camino del proxy**: el diseño contemplaba
   pre-reserva al estilo de minutos; hoy solo hay estimación + gracia
   acotada — elhueco máximo de sobregiro es una llamada.
4. **Cap de concurrencia in-process** (Map en memoria,
   `token-proxy.ts:43-46`): N instancias ⇒ N×cap. Y el 429 no lleva
   `Retry-After` (`verify-report.md:59-63`).
5. **`GET /v1/wallet` no expone el unlock** (`app.ts:593-600` devuelve solo
   balance/reservado): el cliente no puede mostrar "cuenta desbloqueada" sin
   intentar una llamada. Gap aceptado de v1 (`verify-report.md:52-54`).
6. **Android**: un 401 del proxy (sesión revocada en vuelo) cae en el mensaje
   crudo del motor sin botón de sign-in — `needsAccountRecovery`
   (`AppViewModel.kt:102-103`) solo reacciona a fallos de cuenta/hosted, no a
   `EngineException` (iOS sí lo mapea). Menor pero real.
7. **iOS no consulta `/v1/wallet`** en esta versión (`docs/managed-accounts.md:28`)
   — sin chip de saldo.

### 3.6 Gates de verificación pendientes (documentados, no código)

- Token-billing (`openspec/changes/token-billing/verify-report.md:39-48`):
  compilación/test en macOS de `HostedProxyEngineTests`/`TokensCommerceTests`
  (nunca corridos), e2e de compra e intercambio de motor en ambas plataformas,
  check contra servidor desplegado (hoy solo stack local + stub), creación de
  productos en Play Console, aprobación de precio final, suites instrumentadas
  Android + lint.
- Checklist "antes de activación comercial" del server (7 ítems,
  `services/api/README.md:136-144`): hangup WebRTC real con el proveedor,
  attestation de trials, presupuestos globales, StoreKit verificado,
  merchant/tax/pagos live, separación de privilegios DB… — aplica sobre todo a
  voz hosted y compras, no al proxy de texto, pero bloquea el despliegue
  comercial general.
- Streaming SSE passthrough: diferido por contrato v1 (no-streaming).

## 4. Lista mínima para encender el flujo end-to-end (orden propuesto)

1. **Operador (sin código)**: despliegue con `ACCOUNTS_ENABLED=true` + secretos
   HMAC + client IDs Google + Caddy (runbook `enable-accounts.md`), y
   `PROXY_PROVIDER_URL`/`KEY` apuntando a un proveedor con `MiniMax-M2`.
   Añadir ambas a `.env.example` (cambio de una línea).
2. **Asignación de cuota (única pieza nueva de producto)**: implementar el
   grant campaign/admin de (a) unlock (`unlock_source='campaign'`, enum ya
   reservado) y (b) saldo nanoUSD por ledger, con su test. Alternativa a
   corto plazo sin código: comprar `byok_unlock` + `token_pack` en entorno
   live real para usuarios piloto.
3. **Cierre de bugs de cobro**: validar `payload.usage` (ausente/no-numérico/
   negativo/versus estimación) antes de settear; decidir política de rate
   (activa vs pineada) y alinearla con la doc o el código.
4. **Registro de uso**: persistir tokens in/out + nanoUSD por llamada (revivir
   `usage_records` o tabla nueva) y añadir el agregado por cuenta/día a
   `admin-metrics`.
5. **Verificación real**: login Google Play-signed Android + transferencia
   guest; checks de dispositivo iOS; smoke test del 402→banner→"Get tokens"
   en ambas apps contra el servidor desplegado.

Con 1 y 2 el flujo pedido funciona; 3 y 4 lo hacen auditable y robusto; 5 lo
certifica.

## 6. Cierre — implementación de los puntos 1–4 (2026-10-06, misma sesión)

Implementados los puntos 1–4 de la lista mínima (el punto 5 es verificación en
dispositivo/despliegue, no código). Suite completa re-ejecutada en verde.

### Punto 1 — Configuración de despliegue

- `services/api/.env.example`: nueva sección del proxy con `PROXY_PROVIDER_URL`,
  `PROXY_PROVIDER_KEY`, `PROXY_MAX_TOKENS` y `PROXY_MAX_CONCURRENT` (antes no
  aparecían y el proxy parecía roto al 503 sin motivo).
- `services/api/compose.yaml`: ahora reenvía `PROXY_MAX_CONCURRENT` (gap
  documentado en `verify-report.md:59-63`).

### Punto 2 — Asignación de cuota sin tienda: `tokens-admin`

Nuevo CLI de operador `src/tokens-admin.ts` (`npm run tokens-admin`), mismo
rango que `minutes-admin`, sin ruta HTTP que cree estado (respeta la nota de
`ledger.ts`: "no HTTP endpoint that creates journal entries"):

- `grant-unlock`: activa `entitlements` con `unlock_source='campaign'` (el
  valor que el schema reservaba y nadie escribía). Set-once como la compra: no
  pisa un unlock activo (pagado o de campaña) y re-otorga sobre una fila
  limpiada por refund. `unlock_order_id` queda NULL.
- `revoke-unlock`: limpia solo el unlock de campaña; el pagado se revoca
  únicamente por su maquinaria de refund.
- `grant-balance`: credita la cartera por el ledger inmutable (kind
  `'purchase'`, sandbox 0, rate version activa como proveniencia), referencia
  `campaign-grant:<grantID>` — replay del mismo `grantID` es no-op y el
  conflicto de reuso es 409. Gastable en el proxy; no satisface por sí solo
  las puertas de `cash_provenance` de voz/helpers pagados.
- `show`: cartera + unlock + uso 30 días de una cuenta.
- Todas las mutaciones exigen `actor` y `reason` (validación igual que
  `minutes-admin`), rechazan invitados y cuentas borradas, y escriben la nueva
  tabla inmutable `token_admin_audit` (navegable en el panel).

### Punto 3 — Blindaje del cobro

- `token-proxy.ts`: el `payload.usage` del proveedor se valida estrictamente
  (presente, entero en rango segura, no negativo; `providerUsage`). Una
  respuesta de éxito que no se puede medir se rechaza con
  `502 provider_error` **antes** de liquidar: nada se debita, la respuesta no
  se devuelve y un proveedor que corrompe su `usage` no puede convertirse en
  canal de uso gratuito. Matriz de errores y doc actualizados.
- Política de tarifa decidida y documentada: el settle usa siempre la rate
  version **activa en el momento de la llamada**; el pin del pack es
  proveniencia de compra, no tarifa congelada (`docs/token-billing.md`,
  sección Metering).

### Punto 4 — Registro durable de uso

- Migración `040_proxy_usage.sql`: tabla `proxy_usage` (una fila por relay
  exitoso: tokens in/out, estimación de admisión, coste, liquidado,
  `ledger_reference` FK al asiento `settle` cuando lo hubo; NULL en coste
  cero) + índices por cuenta/día y por día; y `token_admin_audit` inmutable.
- `token-proxy.ts` escribe la fila en la misma transacción que el settle.
- `admin-metrics.ts`: nuevo set de timeseries `tokens` (llamadas, tokens de
  entrada/salida, nanoUSD liquidados por día UTC, cero-relleno) servido por
  `GET /v1/admin/metrics/timeseries?set=tokens`.
- `admin-browse.ts`: `proxy_usage` y `token_admin_audit` navegables desde el
  panel.

### Extra

- `GET /v1/wallet` devuelve ahora `unlocked` y `unlockSource` (join a
  `entitlements`): los clientes pueden mostrar el estado de desbloqueo sin
  intentar una llamada. Cambio aditivo, documentado en la tabla de endpoints
  del README del server y en `docs/token-billing.md`.
- README raíz: la frase "Android account integration is in progress" pasa a
  reflejar la realidad (implementado, pendiente de verificación en
  instalación real).
- `package.json`: script `tokens-admin`.

### Verificación de la ronda

| Batería | Resultado |
|---|---|
| Servidor `tsc --noEmit` | Limpio |
| Servidor suite completa (PG 17 efímero en Docker, Node 20.19.4) | **455 tests, 455 pass, 0 fail, 0 skipped** (446 base + 10 nuevos: 6 de `tokens-admin`, 3 de `token-proxy`) |
| Nuevo test HTTP end-to-end | login (sesión bearer) → 403 `unlock_required` → `grant-unlock` → 402 `token_balance_exhausted` → `grant-balance` → 200 con `metering` y débito → `/v1/wallet` con `unlocked:true, unlockSource:'campaign'` |

Nota ambiental: con el `node` del PATH del sistema (v18.19.1, sin `crypto`
global) fallan 4 ficheros por `ReferenceError: crypto` en jose
(accounts/auth/apple-revocation) y el test de OAuth service-account depende de
red; con Node 20.19.4 (nvm), el de la batería de la mañana, todo pasa. El
`engines` del package (>=22.19) es lo que usa CI.

### Residual honesto (quedan, del informe original)

- Punto 5: verificación real (login Play-signed Android, checks de dispositivo
  iOS, smoke 402→banner→"Get tokens" contra servidor desplegado) — requiere
  dispositivo/despliegue.
- Sin hold de reserva en el camino del proxy (gracia acotada a una llamada) —
  decisión de diseño v1 mantenida, documentada.
- Cap de concurrencia in-process (N instancias = N×cap) y 429 sin
  `Retry-After` — gaps v1 aceptados, sin cambiar.
- Android: un 401 del proxy en vuelo cae en el mensaje crudo del motor sin
  botón de sign-in (`AppViewModel.kt:102-103` solo reacciona a
  `HostedFailure`/`AccountFailure`) — pendiente en cliente.

## 5. Conclusión

No falta un sistema: falta **configuración de despliegue, una vía de asignación
de cuota sin tienda, el registro durable de tokens y el blindado del cobro**.
El corte por cuota (402 + UI) ya existe y está probado en las dos plataformas;
la contabilidad es por cuenta (login) desde el diseño, y los commits recientes
de "usage outbox" son telemetría de producto, no facturación — no hay nada que
re-keyar para ligar el uso a la cuenta. La pieza de mayor valor pendiente es el
grant campaign de unlock/saldo: convierte el sistema de "compra o nada" en
"asignable por el operador", que es exactamente lo que la pregunta presupone
("lo asignado al usuario").
