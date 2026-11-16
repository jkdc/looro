#!/usr/bin/env bash
# One-time bootstrap for a fresh Hostinger (or any Debian/Ubuntu) VPS.
# Run FROM THE VPS as root, e.g.:
#   bash bootstrap-vps.sh <github-username>
# Installs Docker, creates a deploy user with SSH access, sets up a deploy key
# for the private repo, clones the code and opens 80/443. You still need to
# create services/api/.env afterwards (see docs/deployment-hostinger.md).
set -euo pipefail

GH_USER="${1:?Usage: bash bootstrap-vps.sh <github-username>}"
DEPLOY_DIR="${DEPLOY_DIR:-/srv/palique}"
DEPLOY_USER="${DEPLOY_USER:-deploy}"

# -- Base packages + Docker ---------------------------------------------------
apt-get update
apt-get install -y ca-certificates curl git ufw

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# -- Deploy user + repo deploy key --------------------------------------------
id -u "$DEPLOY_USER" >/dev/null 2>&1 || useradd -m -s /bin/bash -G docker "$DEPLOY_USER"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" /home/"$DEPLOY_USER"/.ssh

if [ ! -f /home/"$DEPLOY_USER"/.ssh/id_ed25519 ]; then
  ssh-keygen -t ed25519 -N "" -f /home/"$DEPLOY_USER"/.ssh/id_ed25519 -C "palique-deploy@$HOSTNAME"
  chown "$DEPLOY_USER":"$DEPLOY_USER" /home/"$DEPLOY_USER"/.ssh/id_ed25519*
fi
echo "=== ADD THIS DEPLOY KEY AS A READ-ONLY KEY ON GITHUB ==="
cat /home/"$DEPLOY_USER"/.ssh/id_ed25519.pub
echo "https://github.com/jkdc/palique/settings/keys (check 'Read-only')"

sudo -u "$DEPLOY_USER" git config --global --add safe.directory "$DEPLOY_DIR" || true

if [ ! -d "$DEPLOY_DIR/.git" ]; then
  sudo -u "$DEPLOY_USER" git clone --branch main \
    "git@github.com:jkdc/palique.git" "$DEPLOY_DIR"
else
  echo "Repo already cloned at $DEPLOY_DIR; skipping clone."
fi
chown -R "$DEPLOY_USER":"$DEPLOY_USER" "$DEPLOY_DIR"

# -- Firewall: SSH + HTTP(S) only ----------------------------------------------
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw --force enable

echo
echo "Bootstrap done. Next steps:"
echo "  1. Add the deploy key printed above to the GitHub repo (read-only)."
echo "  2. sudo -u $DEPLOY_USER nano $DEPLOY_DIR/services/api/.env"
echo "  3. Run the first deploy:"
echo "     sudo -u $DEPLOY_USER REPO_URL=git@github.com:jkdc/palique.git bash $DEPLOY_DIR/services/api/operations/deploy.sh"
