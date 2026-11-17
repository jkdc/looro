# Despliegue en Coolify (VPS Hostinger)

La instalación del usuario usa **Coolify** sobre el VPS, así que el deploy
automático lo gestiona el **webhook de GitHub de Coolify** (push → build →
deploy) y NO el workflow `deploy.yml` de Actions (que quedó reducido a
`workflow_dispatch` para no duplicar deploys).

## Arquitectura en Coolify

| Pieza | Cómo se despliega |
|---|---|
| PostgreSQL 17 + migraciones + API | Recurso **Docker Compose**: `services/api/compose.coolify.yaml` |
| TLS + dominio | Proxy de Coolify (Traefik) |
| Cabeceras trusted-proxy | Middleware Traefik custom (ver abajo) |
| Caddy / Prometheus / Grafana / Tempo | **No** van a Coolify (compose.yaml es para servidor dedicado) |

## 1. Recurso Docker Compose en Coolify

1. New Resource → **Docker Compose** → conecta el repo privado
   `jkdc/palique` (GitHub App de Coolify; da webhook de push automático).
2. **Compose file**: `services/api/compose.coolify.yaml`.
   Los `build.context` relativos funcionan porque Coolify clona el repo entero.
3. **Domain**: dominio o subdominio del servicio `api`, puerto `8080`
   (ej.: `api.tudominio.com` → `api:8080`). Coolify trae TLS con Let's Encrypt.
4. **Environment variables** en Coolify (o variables del compose):
   - `POSTGRES_PASSWORD` — obligatoria.
   - `PUBLIC_ORIGIN` — `https://api.tudominio.com` (obligatoria en esta variante).
   - `ACCOUNTS_ENABLED=true`, `ACCOUNTS_HMAC_KEY` y `ACCOUNTS_PROXY_TOKEN`
     — dos hex de 64 chars distintos (32 bytes cada uno): `openssl rand -hex 32`.
     Sin esto el login queda deshabilitado.
   - Opcionales: `GOOGLE_ANDROID_*`, `APPLE_CLIENT_ID`, `STRIPE_*`,
     `LEVELTEST_*` (ver `compose.yaml` para el catálogo completo).

## 2. Cabeceras trusted-proxy (obligatorio para el login)

La API solo acepta `X-App-Client-IP` de un proxy que además demuestre
`X-App-Proxy-Token` (`services/api/src/access-requests.ts:76`). El Traefik de
Coolify **no puede inyectar** esas cabeceras (su middleware
`customRequestHeaders` solo fija valores estáticos y no puede copiar la IP
del cliente desde `X-Forwarded-For`).

La solución ya viene resuelta en `compose.coolify.yaml`: un sidecar
**`edge`** (Caddy) recibe el tráfico del dominio, resuelve la IP real del
cliente desde `X-Forwarded-For` (confiando solo en rangos privados Docker,
con `trusted_proxies`) e inyecta ambas cabeceras hacia la API.

Configuración resultante en Coolify:

- **Domain** del recurso → servicio **`edge`**, puerto **`8081`** (no `api:8080`).
- La variable `ACCOUNTS_PROXY_TOKEN` debe estar definida (la usa el sidecar).

Si algo falla aquí, el login responde **503 `access_request_proxy_not_ready`**
— es la señal de este ajuste.

## 3. Por qué el contenedor salía "Restarting"

El primer intento desplegó la API sin variables: `main.ts` hace
`process.exit(1)` si falta `DATABASE_URL`, y el compose exige
`POSTGRES_PASSWORD` y dominio. Con el compose de Coolify + variables de la
sección 1 el contenedor queda sano (healthcheck `healthz` incluido, que
Coolify usa para el estado del recurso).

## 4. Deploy automático

- Push a `main` → webhook Coolify → build + `docker compose up`.
- Rollback: Coolify UI → Deployments → redeploy de un commit anterior.
- El workflow `.github/workflows/deploy.yml` queda como respaldo manual
  (`workflow_dispatch`) para el escenario de servidor dedicado con
  `deploy.sh`; en Coolify no se usa.

## 5. Verificación

```bash
curl https://api.tudominio.com/healthz          # {"ok":true,...}
# login correcto = cabeceras inyectadas bien
```
