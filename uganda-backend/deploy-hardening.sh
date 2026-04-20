#!/bin/bash
# Apply production hardening to the Uganda VM.
#
# Run from the repo root with SSH access to the "easehealth" host. Idempotent:
# re-running is safe, nothing is destroyed, existing state is preserved.
#
# What this script does:
#   1. Installs the purpose-generated TLS cert + keys.
#   2. Deploys the hardened nginx config (rate limiting, HSTS, port-80 redirect).
#   3. Applies migration 001_hardening.sql (token revocation, audit_log indexes).
#   4. Deploys the new API (main.py) — token revocation check, audit writes,
#      exception handler, /delete_my_data, tightened pydantic.
#   5. Deploys the updated ETL (promote.py) + retention worker, with a
#      dedicated easehealth-etl system user.
#   6. Reloads nginx + restarts easehealth-api.
#
# If anything fails it exits non-zero; the VM state at that point may be
# partial. Re-run once the root cause is fixed.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REMOTE=easehealth

say() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }

say "1/6 Install TLS cert"
ssh "$REMOTE" "sudo mkdir -p /etc/ssl/easehealth && sudo chmod 700 /etc/ssl/easehealth"
scp "$HERE/tls/easehealth.crt" "$REMOTE:/tmp/easehealth.crt"
scp "$HERE/tls/easehealth.key" "$REMOTE:/tmp/easehealth.key"
ssh "$REMOTE" "sudo mv /tmp/easehealth.crt /etc/ssl/easehealth/ && \
    sudo mv /tmp/easehealth.key /etc/ssl/easehealth/ && \
    sudo chmod 644 /etc/ssl/easehealth/easehealth.crt && \
    sudo chmod 600 /etc/ssl/easehealth/easehealth.key && \
    sudo chown root:root /etc/ssl/easehealth/easehealth.crt /etc/ssl/easehealth/easehealth.key"

say "2/6 Deploy nginx config + Metabase basic-auth"
ssh "$REMOTE" "sudo mkdir -p /etc/nginx/auth && sudo chmod 755 /etc/nginx/auth"
scp "$HERE/nginx-auth/metabase.htpasswd" "$REMOTE:/tmp/metabase.htpasswd"
ssh "$REMOTE" "sudo mv /tmp/metabase.htpasswd /etc/nginx/auth/metabase.htpasswd && \
    sudo chown root:www-data /etc/nginx/auth/metabase.htpasswd && \
    sudo chmod 640 /etc/nginx/auth/metabase.htpasswd"
scp "$HERE/nginx-easehealth.conf" "$REMOTE:/tmp/easehealth.conf"
ssh "$REMOTE" "sudo mv /tmp/easehealth.conf /etc/nginx/sites-available/easehealth && \
    sudo ln -sf /etc/nginx/sites-available/easehealth /etc/nginx/sites-enabled/easehealth && \
    sudo rm -f /etc/nginx/sites-enabled/default && \
    sudo mkdir -p /var/www/html/.well-known/acme-challenge && \
    sudo nginx -t && sudo systemctl reload nginx && \
    sudo ufw allow 80/tcp >/dev/null"

say "3/6 Apply DB migration 001"
scp "$HERE/migrations/001_hardening.sql" "$REMOTE:/tmp/001.sql"
ssh "$REMOTE" "sudo -u postgres psql -d easehealth -f /tmp/001.sql && rm /tmp/001.sql"

say "4/6 Deploy API"
scp "$HERE/app/main.py" "$REMOTE:/tmp/main.py"
ssh "$REMOTE" "sudo cp /tmp/main.py /opt/easehealth-api/app/main.py && \
    rm /tmp/main.py && \
    sudo systemctl restart easehealth-api && sleep 3 && \
    sudo systemctl is-active easehealth-api"

say "5/6 Deploy ETL worker + retention + dedicated user"
ssh "$REMOTE" "sudo id easehealth-etl >/dev/null 2>&1 || \
    sudo useradd -r -s /usr/sbin/nologin -d /opt/easehealth-etl easehealth-etl"
ssh "$REMOTE" "sudo chown -R easehealth-etl:easehealth-etl /opt/easehealth-etl && \
    sudo chmod 750 /opt/easehealth-etl && sudo chmod 640 /opt/easehealth-etl/.env"
scp "$HERE/etl/promote.py" "$HERE/etl/retention.py" "$REMOTE:/tmp/"
scp "$HERE/easehealth-etl.service" "$HERE/easehealth-retention.service" "$HERE/easehealth-retention.timer" "$REMOTE:/tmp/"
ssh "$REMOTE" "sudo cp /tmp/promote.py /opt/easehealth-etl/promote.py && \
    sudo cp /tmp/retention.py /opt/easehealth-etl/retention.py && \
    sudo chown easehealth-etl:easehealth-etl /opt/easehealth-etl/*.py && \
    sudo mv /tmp/easehealth-etl.service /etc/systemd/system/ && \
    sudo mv /tmp/easehealth-retention.service /etc/systemd/system/ && \
    sudo mv /tmp/easehealth-retention.timer /etc/systemd/system/ && \
    sudo systemctl daemon-reload && \
    sudo systemctl enable --now easehealth-etl.timer easehealth-retention.timer"

say "6/6 Smoke test"
ssh "$REMOTE" "curl -s -k -o /dev/null -w 'api/health=%{http_code}\n' https://localhost/api/health -H 'Host: easehealth.afriqloud.cloud'"

say "Deploy complete."
