# Despliegue en Coolify (VPS Hostinger)

Despliegue en Coolify con el patrón **repositorio conectado** (igual que
OpenTerminal): Coolify clona el repo privado `jkdc/palique` vía GitHub App,
hace build y redeploy con su webhook en cada push. El deploy automático por
push lo gestiona Coolify; el workflow `deploy.yml` de Actions quedó como
respaldo manual (`workflow_dispatch`).

## Arquitectura

| Pieza | Cómo se despliega |
|---|---|
| PostgreSQL 17 | Recurso **Database → PostgreSQL** de Coolify (no va en el repo) |
| API | App **Git Repository** → build pack Dockerfile → `services/api/Dockerfile` |
| Migraciones | La propia API las aplica al arranque con `RUN_MIGRATIONS_ON_BOOT=true` |
| TLS + dominio | Proxy de Coolify (Traefik), dominio configurado en la UI |

El `docker-compose.yml` de la raíz sigue existiendo como alternativa compose,
pero el flujo activo es la app git individual.

## 1. PostgreSQL en Coolify

New Resource → **Database → PostgreSQL 17**. Guarda la cadena de conexión
interna que Coolify genera (host, puerto, usuario, password, base).

## 2. App git (la API)

New Resource → **Git Repository** → repo `jkdc/palique`, branch `main`:

- **Build pack**: Dockerfile
- **Dockerfile location**: `services/api/Dockerfile`
- **Port**: `8080`
- **Domain**: el dominio público de la API (Coolify trae TLS de Let's Encrypt)

## 3. Variables de entorno de la app

Obligatorias:

- `DATABASE_URL` — la cadena del recurso PostgreSQL de Coolify.
- `PUBLIC_ORIGIN` — `https://tu-dominio` (la app rechaza orígenes no-https
  salvo localhost).
- `RUN_MIGRATIONS_ON_BOOT=true` — aplica el esquema al arrancar (opt-in;
  idempotente y con advisory lock, seguro en redeploys).

Para login de cuentas:

- `ACCOUNTS_ENABLED=true`
- `ACCOUNTS_HMAC_KEY` y `ACCOUNTS_PROXY_TOKEN` — dos hex de 64 chars
  distintos (`openssl rand -hex 32`).

**Nota trusted-proxy**: el login exige cabeceras que normalmente inyecta
Caddy (`X-App-Client-IP`). Con Traefik de Coolify no hay inyector, así que
en esta topología añade también:

- `ACCOUNTS_ALLOW_LOCAL_LOOPBACK=true`

Eso hace que la API confíe en la dirección del socket directo (Traefik,
una IP privada de la red Docker). Coste conocido: el limitado por IP
trata a todos los clientes como una única dirección (rate limits
compartidos). Es aceptable para el despliegue MVP actual; si se necesita
limitado por IP real, el `docker-compose.yml` raíz trae el sidecar `edge`
con Caddy que lo resuelve.

Opcionales: `GOOGLE_ANDROID_*`, `APPLE_CLIENT_ID`, `STRIPE_*`, `LEVELTEST_*`.

## 4. Por qué salía "Restarting"

El primer intento desplegó la API sin `DATABASE_URL`: `main.ts` hace
`process.exit(1)` sin ella. Con las variables de la sección 3 el contenedor
queda sano. En Coolify, un estado "Restarting" con deploy en verde siempre
significa crash del proceso: mirar **Logs** del recurso.

## 5. Deploy automático y operación

- Push a `main` → webhook Coolify → build + redeploy.
- Migraciones nuevas SQL: se aplican solas al arrancar con
  `RUN_MIGRATIONS_ON_BOOT=true`.
- Rollback: Deployments → redeploy de commit anterior.
- Verificación: `curl https://tu-dominio/healthz`.
- Panel admin: requiere `ADMIN_HMAC_KEY`/`ADMIN_PROXY_TOKEN` y su dominio;
  ver `services/api/docs/admin-panel.md` antes de habilitarlo.

## 6. Conectar las apps móviles al servidor desplegado

**Android** (property Gradle `-Papp.apiOrigin` → `BuildConfig.MANAGED_API_ORIGIN`):

```bash
make remote-install                    # APK debug + instalación por adb (SERIAL= opcional)
make remote-apk REMOTE_API_ORIGIN=https://otro-dominio   # sobrecarga puntual
```

**iOS** (xcconfig, gitignored): `apps/ios/Config/Local.xcconfig` ya apunta
`APP_MANAGED_API_URL` al servidor; compila con `make ios-build`.

**Estado de cuentas**: el servidor tiene `ACCOUNTS_ENABLED=false` — el
inicio de sesión móvil queda deshabilitado hasta configurar un proveedor de
identidad (Google/Apple) y activar `ACCOUNTS_ENABLED=true` en Coolify junto
con las claves `ACCOUNTS_HMAC_KEY`/`ACCOUNTS_PROXY_TOKEN` (ya generadas).
Los endpoints públicos (contenido, level-tests proxy-exempt) funcionan ya.
