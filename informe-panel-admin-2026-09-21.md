# Informe: panel de administración en el backend

Fecha: 2026-09-21 (rev. v2 con requisitos de uso) · Rama: `feature/rebranding-looro` · Estado: **diseño propuesto, pendiente de OK del usuario** (no se ha implementado nada)

## Objetivo

Un panel de administración servido por el backend (`services/api`), accesible **solo para el administrador** (Joaquin), almacenado en una **tabla distinta** de los usuarios de las apps (`accounts`).

**Uso previsto (v2, tras feedback):** monitorizar cómo funcionan las apps móviles — explorar las tablas con **filtros, orden y paginación**, ejecutar **acciones por usuario**, y crecer en el futuro con **nuevas acciones** y **gráficas**. Un solo admin técnico; la prioridad es control y visibilidad, no UX pulida.

## Estado actual (verificado en código)

- **No existe ninguna tabla ni ruta admin por HTTP.** Las 23 migraciones no crean nada admin; las operaciones de operador viven en CLIs que conectan directo a Postgres con `DATABASE_URL` del operador:
  - `src/minutes-admin.ts` — política de minutos de bienvenida, conciliaciones sandbox, campañas. Escritura con **auditoría obligatoria** (`actor` + `reason`, tablas `minute_policy_audit`, `minute_campaigns` con `request_digest`/`target_digest`).
  - `src/access-requests-admin.ts` — export (archivo `0600`), delete, prune de la waitlist.
  - `src/account-closeout-admin.ts` — informe read-only de triage de cuentas (REPEATABLE READ READ ONLY).
- La autenticación de usuarios es OIDC federado (Google/Apple) verificado en `src/auth.ts` con nonce de un solo uso, JWKS remoto, audience/issuer pineados y validación de `azp` para Android. Sesiones bearer opacas hasheadas en `auth_sessions`, 24 h, revocables.
- Perímetro: Caddy termina TLS; la API no confía en `X-Forwarded-For` — la IP real llega solo con `X-App-Proxy-Token` (`src/access-requests.ts:66`). Admission limiter durable en Postgres para auth (`src/auth-admission.ts`), limiter en memoria 120 req/min para el resto.
- `app_runtime` es el único rol de Postgres de la API, con GRANTs mínimos por módulo (`operations/*.runtime-grants.sql`, `docs/*.md`).
- Backend: Fastify + `pg` + `jose`, sin frontend, sin dependencias de UI, sin ORM (queries parametrizadas crudas). Tests: `npm test` (tsx --test, 358/358 verificados en el último cambio).

## Decisiones de diseño

### D1. Alcance

- **Lectura completa con exploración genérica** (nuevo en v2): ver y filtrar cuentas, wallets, minutos, órdenes, sesiones de voz, helpers, waitlist, sesiones auth — con filtros, orden y paginación (D7).
- **Escrituras: solo las operaciones que ya existen y están auditadas** (política de bienvenida, campañas — incl. campaña a un único usuario —, delete de access requests) + **una escritura nueva** necesaria para acciones por usuario: cierre forzado de sesión de voz activa, con la misma auditoría actor+motivo.
- **La gestión de administradores queda en CLI para siempre** (`admins-admin.ts add|list|disable`): el panel no puede crear admins ⇒ no hay escalada de privilegios desde el panel.

### D2. Autenticación de administradores — OIDC federado + tablas propias

El admin entra con Google igual que en las apps, pero el `(provider, subject)` se busca en `admins`, no en `identities` (nunca por email). Reutiliza el código más duro del BE (nonce, JWKS, azp, admission limiter). Cero contraseñas que proteger. Aislamiento estructural: tablas distintas ⇒ un token de usuario jamás autentica como admin y viceversa.

Alternativas descartadas: password+TOTP propias (escribir gestión de credenciales seguras desde cero, justo la superficie donde un error cuesta caro); AdminJS (ver D8).

### D3. Exposición — subdominio `admin.$API_DOMAIN`

Caddy añade un site block. Aislamiento de cookies, CSP estricta propia, y opción futura de IP allowlist en Caddy sin tocar la API. Requiere un registro DNS. Alternativa: path `/admin/` en el dominio principal si no se quiere el DNS.

### D4. Modelo de datos (migración `024_admins.sql`)

```sql
CREATE TABLE admins (
  id uuid PRIMARY KEY,
  email text,                                  -- informativo, NUNCA clave de autenticación
  provider text NOT NULL CHECK (provider IN ('google','apple')),
  subject text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  disabled_at timestamptz,
  UNIQUE (provider, subject)
);
CREATE TABLE admin_sessions (
  id uuid PRIMARY KEY,
  admin_id uuid NOT NULL REFERENCES admins(id),
  token_hash text NOT NULL UNIQUE,             -- SHA-256 del token opaco; el token nunca se guarda
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz
);
```

- Sesiones de **8 h**, tope de 3 concurrentes, revocación total en sign-out (mismos patrones que `auth.ts`).
- Una identidad por admin (sin tabla `admin_identities`); si algún día hace falta vincular proveedores, es una migración más.
- GRANTs a `app_runtime` documentados en `docs/admin-panel.md` (patrón `enable-accounts.md`).
- Bootstrap CLI: `npm run admins-admin -- add google <subject> [email] | list | disable <id>` (patrón `access-requests-admin.ts`).

### D5. UI — tema y componentes prehechos vendidos como estáticos, sin build step

Ni todo hecho a mano ni framework con toolchain: **piezas preconstruidas, vendidas como archivos estáticos comprometidos en el repo** (versiones pineadas, servidas same-origin por Caddy, sin CDN en runtime, sin npm ni bundler). La API sigue siendo 100 % JSON.

- **Tema visual: [Tabler](https://tabler.io)** (tema admin sobre Bootstrap 5, MIT, distribuye `dist` compilado para uso sin build): layout con sidebar, stat cards para métricas, estilos de tabla/formularios/botones. Alternativa más austera si se prefiere: Pico CSS.
- **Tabla: [Tabulator](https://jspreadsheets.com)** (vanilla JS, MIT): cabeceras ordenables, filtros por columna y **paginación remota** — su modo `pagination: "remote"` envía página/orden/filtros como parámetros y nuestro endpoint browse los valida contra la whitelist (D7). El filtrado **siempre lo aplica el servidor**; la librería solo pinta. Su formateador de celdas por defecto escapa HTML.
- **Gráficas: Chart.js vendido** (renderiza a canvas, sin HTML injection): sesiones/día, minutos consumidos vs concedidos, altas/día, órdenes/ingresos/día.
- Combinación remota remota conocida: Tabulator tiene un issue histórico de datos obsoletos al combinar `paginationMode` y `filterMode` remotos ([#3663](https://github.com/olifolkerd/tabulator/issues/3663)) — se prueba explícitamente ese combo en T5 y, si persiste, se compone filtro+página en un solo refresh controlado.
- Cookie de sesión **HttpOnly + Secure + SameSite=Strict**, validación de `Origin` en toda mutación, **CORS: ninguno** (same-origin). CSP: `default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:`.
- Trade-off asumido: JS/CSS de terceros corriendo en el origen admin sin auditar línea a línea. Mitigación: versiones pineadas committeadas (sin supply chain en runtime), CSP que solo permite `self`, y la frontera de seguridad real (auth admin, whitelist SQL, auditoría) vive 100 % en el servidor.

### D6. Superficie HTTP (todas las rutas admin detrás del admission limiter + trusted-proxy)

```
POST   /v1/admin/auth/challenge               # nonce, igual que usuarios
POST   /v1/admin/auth/exchange                # OIDC → busca en admins → cookie de sesión
POST   /v1/admin/auth/sign-out
GET    /v1/admin/me
GET    /v1/admin/browse/:table                # exploración genérica (D7): filtros, orden, paginación
GET    /v1/admin/accounts/:id                 # detalle: reutiliza accountCloseoutReport (read-only)
GET    /v1/admin/metrics                      # instantánea: conteos y sumas fijas
GET    /v1/admin/metrics/timeseries           # series diarias para gráficas (queries fijas)
GET    /v1/admin/access-requests              # waitlist (equivalente a browse, respuesta curada)
DELETE /v1/admin/access-requests              # reutiliza deleteAccessRequest
GET    /v1/admin/policy/welcome               # reutiliza welcomePolicy
PUT    /v1/admin/policy/welcome               # reutiliza updateWelcomePolicy (actor=admin, reason obligatoria)
POST   /v1/admin/campaigns                    # reutiliza CampaignRequest; audience [un usuario] = acción por usuario
POST   /v1/admin/hosted-sessions/:id/close    # NUEVO: cierre forzado auditado de sesión de voz activa
```

Acciones por usuario en v1: **ver detalle** (closeout), **campaña de minutos a ese usuario** (auditoría existente), **borrar su access request**, **cerrar su sesión de voz activa**. Añadir acciones futuras = un endpoint que reutilice lógica auditada + un botón en la config de la fila.

- Las escrituras reutilizan **exactamente** las funciones de `minutes-admin.ts` (misma auditoría, mismo advisory lock, mismo 409 de versión) — el panel no duplica lógica de negocio.
- Nuevos códigos de error: `admin_sign_in_required` (401), `not_admin` (403, genérico), `admin_disabled` (403), `table_not_browsable` (404).
- Extensión de `auth-admission.ts` con operaciones `admin-exchange` / `admin-api` (límites por red más estrictos que los de usuarios).

### D7. Motor de exploración genérico (la pieza nueva de v2)

Un único endpoint parametrizado en vez de N vistas fijas — la parte que AdminJS regalaría y aquí construimos con whitelist:

- **Config por tabla, declarada en código** (`admin-browse.ts`): tabla, columnas visibles, columnas filtrables con operadores permitidos (`eq, neq, contains, gte, lte, in, is-null`), columnas ordenables, orden por defecto, y JOINs prohibidos (v1: solo tablas planas, sin joins).
- **SQL 100 % parametrizado**: los valores van por `$n`; los identificadores (columna, dirección de orden, tabla) salen **exclusivamente de la whitelist** — jamás se interpolan strings del cliente. Esto es el punto de riesgo clásico (ORDER BY/WHERE dinámicos) y se cubre con tests de inyección dedicados.
- **Paginación**: `LIMIT` con tope 100 + `OFFSET`, más total count opcional (para el paginador). Si alguna tabla crece a millones, keyset por `created_at,id` como mejora posterior — sin cambiar la API.
- **Privacidad por defecto en la config**: `auth_sessions` expone metadatos (creada, expira, revocada) pero **jamás** `token_hash`; `access_requests` muestra email (es para eso); `usage_records` agrega, no detalla transcripciones si las hubiera.
- Tablas navegables v1: `accounts`, `wallets`, `minute_wallets`, `minute_entries`, `hosted_sessions`, `hosted_helper_requests`, `minute_purchase_orders`, `access_requests`, `auth_sessions` (metadatos), `usage_records`.

### D8. Por qué no AdminJS, actualizado a v2

Los requisitos nuevos (browsing intensivo) eran el mejor caso a favor de AdminJS, y [`@adminjs/sql`](https://docs.adminjs.co) existe y es oficial (Postgres sin ORM). Aun así sigue sin convencer aquí, por datos verificados:

1. **Es el adaptador más joven de la familia** (los maduros son los de ORM: Sequelize/TypeORM/Prisma — y este repo no usa ORM ni vamos a añadir uno para el panel).
2. **Su login asume email+password** (`authenticate(email, password)`); con OIDC habría que montarlo tras middleware propio y desactivar el suyo — pelear contra la librería en la parte crítica.
3. **Gráficas**: AdminJS es débil en dashboards sin su bundler de componentes React (build step que el repo no tiene); Chart.js vendido en vanilla es una línea frente a eso.
4. **Coste permanente**: árbol de dependencias grande con frontend precompilado en el sistema de dinero, cuya filosofía actual son 5 dependencias y supply chain mínima — a cambio de ahorrarnos ~una fase (el motor browse, D7).

Si el criterio de Joaquin fuese "menos código propio aunque sea caja negra", AdminJS read-only tras middleware OIDC propio es defendible — por eso queda documentado como plan B explícito.

## Despliegue: Docker Compose (ya existente, el panel encaja sin infraestructura nueva)

La API **ya corre hoy en Docker Compose** (`services/api/compose.yaml`): `database` (postgres:17-alpine), `migrate` (one-off), `api` (multi-stage `node:22-alpine`, `USER node`, filesystem read-only, `cap_drop: ALL`, `no-new-privileges`, **sin puertos publicados**) y `caddy` (único que publica 80/443). El panel no añade ningún servicio ni imagen:

- **Rutas `/v1/admin/*`**: viven en el contenedor `api` existente — es la misma app Node; cero cambios de despliegue.
- **UI estática (Tabler/Tabulator/Chart.js)**: la sirve Caddy con `file_server` desde un volumen montado read-only (`./admin-ui:/srv/admin-ui:ro`), igual que hoy monta el Caddyfile. El nuevo site block `{$ADMIN_DOMAIN}` proxifica `/v1/admin/*` a `api:8080` y sirve el resto como estático, con las cabeceras de seguridad + CSP.
- **CLI de admins** (`admins-admin add|list|disable`): one-off con el patrón de `migrate` — `docker compose run --rm api node dist/src/admins-admin.js add google <subject>` (misma red, mismo `DATABASE_URL`).
- **Cambios totales en compose**: un volumen montado en `caddy` y las envs nuevas (`ADMIN_DOMAIN`, `GOOGLE_ADMIN_CLIENT_ID`, claves HMAC/admin). Sin servicios nuevos, sin imágenes nuevas.

## Invariantes de seguridad

1. **Aislamiento estructural de tokens**: `authenticate()` solo consulta `auth_sessions`; la auth admin solo consulta `admin_sessions`. Ningún token vale en el otro mundo, por construcción.
2. **El panel no puede tocar la tabla `admins`** (alta/baja es CLI) ⇒ sin escalada de privilegios.
3. **Identidad nunca por email**; solo `(provider, subject)`.
4. **Toda mutación auditada** con actor admin + motivo, en las tablas de auditoría existentes.
5. **El motor browse no interpola nunca input del cliente en SQL** — solo valores parametrizados e identificadores desde whitelist, con tests de inyección.
6. **Sin logging nuevo de datos sensibles**; cookie admin aislada por dominio, SameSite=Strict, Origin validado, sin CORS.
7. Export de waitlist con PII **sigue siendo CLI con archivo 0600** (no por HTTP).

## Pruebas (node:test, injection como hoy)

- `admin-auth.test.ts`: OIDC mockeado vía `identityVerifier` (patrón existente), nonce de un solo uso, cookie flags, `not_admin`, admin disabled, sign-out, tope de sesiones, expiración 8 h.
- `admin-isolation.test.ts`: token de usuario → 401 en admin; token admin → 401 en usuario; ninguna consulta cruza tablas.
- `admin-browse.test.ts`: filtros/orden/paginación por config, **tests de inyección** (intentos de subvertir columna/operador/orden/dirección), tope de LIMIT, tablas no configuradas → 404, privacidad (token_hash jamás en respuesta).
- `admin-api.test.ts`: detalle closeout, métricas y timeseries, política con audit row + 409 de versión, campaña a un usuario, delete de access request, cierre forzado de sesión auditado.
- `admin-admission.test.ts`: 429 + `Retry-After`.
- Gate: `npm run check` + `npm test` verdes (base 358/358).

## Fases preliminares (para openspec tras tu OK)

- **T1** — Migración 024 + GRANTs + `admins-admin.ts` (add/list/disable) + tests.
- **T2** — Auth admin (challenge/exchange/sign-out/me) con cookie + admission + tests de aislamiento.
- **T3** — Motor browse (`admin-browse.ts` + endpoint + config de tablas) + métricas y timeseries + tests de inyección. *Con esto ya hay panel útil read-only.*
- **T4** — Escrituras auditadas (política, campañas, delete access request, cierre forzado de sesión) + tests.
- **T5** — UI estática sobre Tabler + Tabulator + Chart.js vendidos (tablas con filtros/orden/paginación remotos, detalle, botones de acción, gráficas; prueba explícita del combo remoto de Tabulator #3663) + site block Caddy + CSP + compose (`ADMIN_DOMAIN`).
- **T6** — `docs/admin-panel.md` + verificación completa y veredicto honesto.

## Fuera de alcance v1

Gestión de admins desde el panel · roles granulares · export de waitlist por HTTP · keyset pagination (OFFSET basta al volumen actual) · joins en browse · app móvil de admin.

## Decisiones que necesito de ti

1. **OK al paquete v2**: panel vanilla con motor de exploración genérico, OIDC con tablas propias, subdominio, UI estática con gráficas.
2. Confirmar que puedes crear el registro DNS `admin.$API_DOMAIN` (si no, lo montamos bajo `/admin/` del dominio principal).
3. Con el OK: propuesta openspec y ejecución por fases con tests, empezando por T1–T2 (auth) y T3 (exploración read-only, útil desde el primer momento).
