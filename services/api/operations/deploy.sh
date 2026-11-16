#!/usr/bin/env bash
# Server-side deploy: pull the pinned branch, rebuild and roll the Compose
# stack, then verify health. Runs ON THE VPS from $DEPLOY_DIR. Designed for
# non-interactive SSH invocation from GitHub Actions (deploy.yml).
#
# Required env: none beyond defaults. Optional: REPO_URL, BRANCH, DEPLOY_DIR.
set -euo pipefail

REPO_URL="${REPO_URL:-git@github.com:jkdc/palique.git}"
BRANCH="${BRANCH:-main}"
DEPLOY_DIR="${DEPLOY_DIR:-/srv/palique}"
COMPOSE_DIR="$DEPLOY_DIR/services/api"

# -- Fetch the new commit ----------------------------------------------------
if [ ! -d "$DEPLOY_DIR/.git" ]; then
  mkdir -p "$(dirname "$DEPLOY_DIR")"
  git clone --branch "$BRANCH" "$REPO_URL" "$DEPLOY_DIR"
else
  cd "$DEPLOY_DIR"
  git fetch --prune origin "$BRANCH"
  git reset --hard "origin/$BRANCH"
fi

cd "$COMPOSE_DIR"

# -- Refuse to deploy without runtime secrets configured ---------------------
if [ ! -f .env ]; then
  echo "ERROR: $COMPOSE_DIR/.env is missing (POSTGRES_PASSWORD, API_DOMAIN, ...)" >&2
  echo "See docs/deployment-hostinger.md for the required contents." >&2
  exit 1
fi

# -- Build and roll ----------------------------------------------------------
docker compose pull --ignore-buildable-images || true
docker compose build --pull
docker compose up -d --remove-orphans

# -- Health gate -------------------------------------------------------------
echo "Waiting for /healthz..."
for i in $(seq 1 60); do
  if docker compose exec -T api wget -qO- http://127.0.0.1:8080/healthz >/dev/null 2>&1; then
    echo "Deploy OK at $(git -C "$DEPLOY_DIR" rev-parse --short HEAD)"
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 2
done

echo "ERROR: health check did not pass after rollout; showing recent logs:" >&2
docker compose logs --tail=80 api >&2
exit 1
