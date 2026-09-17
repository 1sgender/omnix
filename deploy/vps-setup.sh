#!/usr/bin/env bash
# OMNIX production deploy on a fresh VPS (Ubuntu 22.04/24.04, 2+ GB RAM).
# Idempotent: safe to re-run (existing .env.production is never overwritten).
#
# Usage:
#   sudo bash vps-setup.sh <public-domain> <acme-email> [git-ref]
#
# Example:
#   sudo bash vps-setup.sh omnix-xxxxx.duckdns.org admin@example.com main
#
# Prerequisites (done by the owner before running):
#   1. DNS A-record <public-domain> -> this server's public IP (ports 80/443 open).
#   2. Fresh VPS with internet access.
set -euo pipefail

DOMAIN="${1:?Usage: sudo bash vps-setup.sh <public-domain> <acme-email> [git-ref]}"
EMAIL="${2:?Usage: sudo bash vps-setup.sh <public-domain> <acme-email> [git-ref]}"
REF="${3:-main}"
INSTALL_DIR="/opt/omnix"
CREDS_FILE="/root/omnix-credentials.txt"

if [ "$(id -u)" != "0" ]; then
  echo "ERROR: run as root (sudo)." >&2
  exit 1
fi

echo "==> [1/7] Checking DNS: $DOMAIN must resolve to this server"
SERVER_IP=$(curl -s --max-time 15 https://api.ipify.org || true)
DNS_IP=$(getent hosts "$DOMAIN" | awk '{print $1}' | head -1 || true)
echo "    server public IP: ${SERVER_IP:-unknown}"
echo "    DNS resolves to:  ${DNS_IP:-FAILED TO RESOLVE}"
if [ -z "$DNS_IP" ]; then
  echo "ERROR: $DOMAIN does not resolve. Create the A-record first." >&2
  exit 1
fi
if [ -n "$SERVER_IP" ] && [ "$DNS_IP" != "$SERVER_IP" ]; then
  echo "WARNING: DNS ($DNS_IP) != server IP ($SERVER_IP). Continuing anyway in 5s..." >&2
  sleep 5
fi

echo "==> [2/7] Installing Docker + dependencies"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq docker.io docker-compose-plugin curl openssl git ca-certificates
systemctl enable --now docker

echo "==> [3/7] Fetching code to $INSTALL_DIR ($REF)"
if [ -d "$INSTALL_DIR/.git" ]; then
  git -C "$INSTALL_DIR" fetch origin --tags
  git -C "$INSTALL_DIR" checkout "$REF"
  git -C "$INSTALL_DIR" pull --ff-only origin "$REF"
else
  mkdir -p "$INSTALL_DIR"
  git clone --branch "$REF" https://github.com/1sgender/omnix.git "$INSTALL_DIR"
fi
cd "$INSTALL_DIR/deploy"
chmod +x verify-production-config.sh smoke-production-tls.sh 2>/dev/null || true

echo "==> [4/7] Generating .env.production (only if absent)"
if [ -f .env.production ]; then
  echo "    .env.production already exists — keeping it (delete manually to regenerate)."
else
  DB_PASS=$(openssl rand -hex 24)
  PEPPER=$(openssl rand -hex 48)
  CLIENT_TOKEN=$(openssl rand -hex 32)
  ADMIN_PASS=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 20)
  sed -e "s|^PUBLIC_DOMAIN=.*|PUBLIC_DOMAIN=$DOMAIN|" \
      -e "s|^ACME_EMAIL=.*|ACME_EMAIL=$EMAIL|" \
      -e "s|^DATABASE_PASSWORD=.*|DATABASE_PASSWORD=$DB_PASS|" \
      -e "s|^LICENSE_CODE_PEPPER=.*|LICENSE_CODE_PEPPER=$PEPPER|" \
      -e "s|^OMNIX_CLIENT_TOKENS=.*|OMNIX_CLIENT_TOKENS=$CLIENT_TOKEN:operations|" \
      -e "s|^OMNIX_ADMIN_BOOTSTRAP_PASSWORD=.*|OMNIX_ADMIN_BOOTSTRAP_PASSWORD=$ADMIN_PASS|" \
      .env.production.example > .env.production
  chmod 600 .env.production
  cat > "$CREDS_FILE" <<EOF
# OMNIX credentials (generated $(date -u +%FT%TZ), stored ONLY on this server).
# Admin UI: https://$DOMAIN/v1/admin/ui  (login: admin)
ADMIN_PASSWORD=$ADMIN_PASS
# Static operations token (also in $INSTALL_DIR/deploy/.env.production):
OPERATIONS_TOKEN=$CLIENT_TOKEN
EOF
  chmod 600 "$CREDS_FILE"
  echo "    secrets generated; admin password saved to $CREDS_FILE (root-only)."
fi

echo "==> [5/7] Opening firewall (80/443) if ufw is active"
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  echo "    ufw rules added."
else
  echo "    ufw not active — skipping (ensure 80/443 are open in provider firewall)."
fi

echo "==> [6/7] Building and starting containers (first build takes a while)"
docker compose -f docker-compose.production.yml --env-file .env.production up -d --build

echo "==> [7/7] Waiting for health: https://$DOMAIN/v1/health"
for i in $(seq 1 30); do
  if curl -sk --max-time 10 -H "Host: $DOMAIN" https://127.0.0.1/v1/health | grep -q '"ok"'; then
    echo "    HEALTHY after ${i}0s."
    break
  fi
  if [ "$i" = "30" ]; then
    echo "ERROR: server did not become healthy. Last logs:" >&2
    docker compose -f docker-compose.production.yml --env-file .env.production logs --tail=30 >&2
    exit 1
  fi
  sleep 10
done

echo ""
echo "==================== DEPLOY DONE ===================="
echo "API:      https://$DOMAIN"
echo "Health:   https://$DOMAIN/v1/health"
echo "Admin UI: https://$DOMAIN/v1/admin/ui  (user: admin, password in $CREDS_FILE)"
echo ""
echo "Issue an activation code (run ON this server):"
echo "  TOKEN=\$(grep OPERATIONS_TOKEN $CREDS_FILE | cut -d= -f2)"
echo "  curl -sk https://$DOMAIN/v1/admin/licenses/issue \\"
echo "    -H \"Authorization: Bearer \$TOKEN\" -H 'Content-Type: application/json' \\"
echo "    -d '{\"plan_id\":\"earclip-monthly\",\"one_time\":true}'"
echo "======================================================"
