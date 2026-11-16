# Despliegue en VPS Hostinger (deploy automático con push)

El backend (`services/api`: Fastify + PostgreSQL 17 + panel admin + Caddy +
stack de observabilidad) se despliega en el VPS mediante Docker Compose.
Cada push a `main` que toque `services/api/**`, `shared/**` o el workflow
dispara el deploy automático (`.github/workflows/deploy.yml`).

Las apps móviles **no** se despliegan al VPS: se compilan en local/CI
(`apps/android`, `apps/ios`).

## 1. Repositorio

El origen de despliegue es el repo **privado** `github.com/jkdc/palique`
(el antiguo `jkdc/looro` era un fork público y no puede hacerse privado).
Remotes de este clone:

- `origin` → el repo de trabajo que uses (`git remote -v` para verlo)
- `palique` → `git@github.com:jkdc/palique.git` (destino del deploy)

```bash
git push palique main              # publica y dispara deploy
git push palique feat/daily-practice
```

Si quieres que `origin` apunte al privado, cambia la URL con
`git remote set-url origin git@github.com:jkdc/palique.git`.

## 2. Preparar el VPS (una sola vez)

Con el VPS recién creado (Ubuntu/Debian), por SSH como root:

```bash
bash services/api/operations/bootstrap-vps.sh jkdc
```

El script instala Docker, crea el usuario `deploy`, genera una clave SSH de
deploy (ed25519), clona el repo en `/srv/palique` y abre 80/443 en el
firewall (ufw). Al final imprime la clave pública: añádela en
`https://github.com/jkdc/palique/settings/keys` marcada **Read-only**.

> Nota de memoria VPS: el stack completo (Postgres + API + Caddy +
> observabilidad) necesita ~2 GB de RAM. En un KVM de 2 GB el stack entra;
> en uno de 1 GB valora levantar solo `database migrate api caddy`.

## 3. Configurar `services/api/.env` en el servidor

Obligatorio como mínimo (compose falla si faltan los primeros dos):

```ini
POSTGRES_PASSWORD=<contraseña larga única>
API_DOMAIN=api.tudominio.com        # DNS A → IP del VPS antes de arrancar
ADMIN_DOMAIN=admin.tudominio.com    # opcional: habilita el panel
ADMIN_PROXY_TOKEN=<token aleatorio largo>
ACCOUNTS_HMAC_KEY=<aleatorio largo>
ACCOUNTS_PROXY_TOKEN=<aleatorio largo>
ADMIN_HMAC_KEY=<aleatorio largo>
```

El resto de variables son opcionales (Google/Apple login, Stripe, LLM de
level-tests, OpenAI…) — ver `services/api/compose.yaml`. Nunca subir el
`.env` a git (el deploy falla a propósito si falta).

Primer arranque manual:

```bash
sudo -u deploy REPO_URL=git@github.com:jkdc/palique.git \
  bash /srv/palique/services/api/operations/deploy.sh
```

## 4. Secrets de GitHub (deploy automático)

En `https://github.com/jkdc/palique/settings/secrets/actions`:

| Secret | Valor |
|---|---|
| `VPS_HOST` | IP pública o hostname del VPS |
| `VPS_USER` | `deploy` |
| `VPS_SSH_KEY` | Clave privada que Actions usará para entrar. Recomendado: genera un par exclusivo para CI (`ssh-keygen -t ed25519 -f ci_ed25519`), añade la pública en `/home/deploy/.ssh/authorized_keys` del VPS y pega la privada aquí |
| `VPS_PORT` | Solo si no es 22 |
| `VPS_KNOWN_HOSTS` | Opcional, más estricto: salida de `ssh-keyscan -H <host>` |

Sin `VPS_KNOWN_HOSTS`, el workflow hace `ssh-keyscan` en cada ejecución
(TOFU en la primera conexión). Con el secret fijado, la clave del host va
pineada.

## 5. Qué hace el deploy

1. Push a `main` (paths filtrados) → workflow `deploy-vps` (serializado con
   `concurrency`, sin cancelar deploys en curso).
2. SSH al VPS → `services/api/operations/deploy.sh`:
   - `git fetch` + `git reset --hard origin/main` en `/srv/palique`.
   - `docker compose build --pull` + `up -d --remove-orphans`.
   - Health gate: sondea `/healthz` hasta 120 s; si no pasa, vuelca logs de
     la API y **falla el job** (el contenedor anterior sigue sirviendo
     tráfico porque la imagen vieja solo se sustituye si la nueva arranca;
     el prune de imágenes solo ocurre en deploy sano).
3. Si el job falla, revisa en el VPS: `docker compose logs -f api`.

## 6. Operación

- Logs: `docker compose logs -f api` (en `/srv/palique/services/api`)
- Estado: `docker compose ps`; health: `curl https://$API_DOMAIN/healthz`
- Observabilidad (Grafana/Prometheus): solo publicados en `127.0.0.1`;
  accede por túnel SSH: `ssh -L 3003:127.0.0.1:3003 deploy@<vps>`
- Rollback: `git -C /srv/palique reset --hard <sha-anterior> && docker compose up -d --build`

## 7. Qué NO se despliega

- `apps/android` / `apps/ios`: builds nativos (CI los compila; APK via
  `make android-build`).
- Bases de datos de laboratorio (`compose.lab.yaml`) y stacks de humo.
