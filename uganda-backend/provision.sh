#!/bin/bash
# Ease Health Uganda VM provisioning script
# Run once as `user` with passwordless sudo.
set -euo pipefail

echo "=== 1/7 System update ==="
export DEBIAN_FRONTEND=noninteractive
APT_OPTS='-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold'
sudo apt-get update
# Skip the full upgrade — too many prompts on this base image. Just fix any broken state.
sudo apt-get $APT_OPTS -y --fix-broken install || true
sudo dpkg --configure -a || true

echo "=== 2/7 Install base packages ==="
sudo apt-get $APT_OPTS -y install \
  postgresql postgresql-contrib \
  nginx \
  certbot python3-certbot-nginx \
  python3-pip python3-venv \
  ufw fail2ban \
  git curl jq

echo "=== 3/7 PostgreSQL setup ==="
sudo systemctl enable --now postgresql
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='easehealth'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE DATABASE easehealth;"
for user_sql in \
  "CREATE USER easehealth_api WITH PASSWORD 'CHANGE_ME_API'" \
  "CREATE USER easehealth_etl WITH PASSWORD 'CHANGE_ME_ETL'" \
  "CREATE USER easehealth_analytics WITH PASSWORD 'CHANGE_ME_RO'"; do
  sudo -u postgres psql -c "$user_sql" 2>&1 | grep -v "already exists" || true
done
sudo -u postgres psql -d easehealth -c "CREATE SCHEMA IF NOT EXISTS tier_1_identified;"
sudo -u postgres psql -d easehealth -c "CREATE SCHEMA IF NOT EXISTS tier_2_analytics;"
sudo -u postgres psql -d easehealth -c "GRANT USAGE, CREATE ON SCHEMA tier_1_identified TO easehealth_api, easehealth_etl;"
sudo -u postgres psql -d easehealth -c "GRANT USAGE, CREATE ON SCHEMA tier_2_analytics TO easehealth_etl;"
sudo -u postgres psql -d easehealth -c "GRANT USAGE ON SCHEMA tier_2_analytics TO easehealth_analytics;"

echo "=== 4/7 Firewall ==="
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow ssh
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable

echo "=== 5/7 API service directory ==="
sudo mkdir -p /opt/easehealth-api
sudo chown "$USER:$USER" /opt/easehealth-api
python3 -m venv /opt/easehealth-api/.venv
/opt/easehealth-api/.venv/bin/pip install --upgrade pip
/opt/easehealth-api/.venv/bin/pip install fastapi uvicorn[standard] asyncpg pydantic python-jose[cryptography] python-multipart

echo "=== 6/7 nginx reverse proxy config ==="
sudo tee /etc/nginx/sites-available/easehealth > /dev/null << 'NGINX'
server {
    listen 443 ssl;
    server_name _;
    # TLS certs will be provisioned by certbot once we have a domain
    ssl_certificate /etc/ssl/certs/ssl-cert-snakeoil.pem;
    ssl_certificate_key /etc/ssl/private/ssl-cert-snakeoil.key;

    client_max_body_size 2m;
    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options nosniff always;

    location /api/ {
        proxy_pass http://127.0.0.1:8000/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /metabase/ {
        proxy_pass http://127.0.0.1:3000/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
NGINX
sudo ln -sf /etc/nginx/sites-available/easehealth /etc/nginx/sites-enabled/easehealth
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx

echo "=== 7/7 Done. Next: deploy API code, run schema migrations, set real passwords ==="
