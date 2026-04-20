# Ease Health Uganda — Developer Access

Short guide for a new developer joining the project. If you're trying to query analytics, run the API locally, or investigate an incident, start here.

## What you'll access

| Thing | Address | Who hands you the credential |
|---|---|---|
| Metabase dashboards |  `https://41.220.3.234/metabase/` | Admin invites you in Metabase |
| SSH to the VM | `user@41.220.3.234` (hostname once DNS resolves) | Paste your SSH pubkey in Slack / 1Password |
| PostgreSQL (via SSH tunnel) | `127.0.0.1:5432` on the VM | `.secrets.env` shared via 1Password |
| Repo + deploy scripts | this repo | — |

The VM lives in Kampala for DPPA 2019 §19 compliance. Do not move data out of it.

## 1. Getting SSH access

Send your public key to the admin. They run on the VM:

```bash
echo 'ssh-ed25519 AAAA... you@laptop' | sudo tee -a /home/user/.ssh/authorized_keys
```

Your side:

```
# ~/.ssh/config
Host easehealth
  HostName      41.220.3.234                  # swap to easehealth.afriqloud.cloud when DNS resolves
  User          user
  IdentityFile  ~/.ssh/id_ed25519
  ServerAliveInterval 30
```

Smoke test:

```bash
ssh easehealth "uptime && sudo systemctl is-active easehealth-api metabase postgresql"
```

You should see three lines of `active`.

## 2. Metabase — BI dashboards

Two gates:

1. **HTTP basic-auth** at the nginx layer. Credentials in `.secrets.env` under `METABASE_PROXY_USER` / `METABASE_PROXY_PASSWORD`. Rotate the htpasswd file at `/etc/nginx/auth/metabase.htpasswd` when a contributor leaves.
2. **Metabase login** — created for you by the current admin via **Admin → People → Invite**.

Available data:
- `tier_2_analytics.assessments` — pseudonymised (HMAC of assessment id), district-level location, age-bucketed, symptoms redacted by Presidio + on-device regex. Safe to share in reports.
- You cannot query `tier_1_identified.*` from Metabase — the `easehealth_analytics` role is not granted on it.

If you need tier_1 access for a clinical audit, use `psql` over SSH and record your action in `tier_1_identified.audit_log` manually (no automation here yet — DPPA §30 compliance is on you).

## 3. Database access

Two ways.

### Read-only analytics (equivalent to Metabase but local)

```bash
ssh -L 5432:127.0.0.1:5432 easehealth
# In another terminal:
PGPASSWORD="$DB_RO_PASSWORD" psql -h 127.0.0.1 -U easehealth_analytics -d easehealth
# You can SELECT from tier_2_analytics.*; DROP / UPDATE will error.
```

### tier_1 identified (PII — don't export) for incident response

```bash
ssh easehealth
sudo -u postgres psql -d easehealth
# Full access — log what you did:
INSERT INTO tier_1_identified.audit_log (actor, action, subject_id, reason)
  VALUES ('<your-name>', 'manual_inspection', '<assessment-uuid>', '<ticket/reason>');
```

## 4. Scripts you'll actually use

All checked in under `uganda-backend/scripts/`:

| Script | When |
|---|---|
| `scripts/revoke-device.sh <device_id> [reason]` | Nurse reports a phone stolen. Run on the VM. Idempotent. |
| `uganda-backend/deploy-hardening.sh` | Re-apply the full production config after a VM rebuild or a new dev environment. Idempotent. |

### Checking what devices are active

```sql
SELECT device_token, last_seen, device_model, chipset, app_version
FROM tier_1_identified.device_registry
WHERE revoked_at IS NULL
ORDER BY last_seen DESC LIMIT 20;
```

### Finding a specific assessment for debugging

```sql
SELECT id, submitted_at, role, triage_level, confidence
FROM tier_1_identified.assessments
WHERE device_token = '<uuid>'
ORDER BY submitted_at DESC LIMIT 10;
```

## 5. Building + shipping the Android app

```bash
cd Android/src
./gradlew :app:assembleDebug            # debug APK, 3.4 GB (bundled models)
./gradlew :app:assembleRelease          # release APK — requires signing keys
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you bump the server's TLS cert, update the SPKI pin in
`Android/src/app/src/main/res/xml/network_security_config.xml` — otherwise
the app will fail to sync with an obscure `CertPathValidatorException`.

Regenerate the pin:

```bash
openssl x509 -in uganda-backend/tls/easehealth.crt -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary | openssl enc -base64
```

## 6. Secrets

`uganda-backend/.secrets.env` is mode 600 on the project owner's laptop. Share via 1Password, not commits. Contents:

- `DB_API_PASSWORD`, `DB_ETL_PASSWORD`, `DB_RO_PASSWORD`, `DB_METABASE_PASSWORD` — Postgres roles
- `DEVICE_TOKEN_SECRET` — HMAC key for device tokens (rotate ≡ invalidating every phone)
- `PSEUDONYM_SECRET` — HMAC key for tier_2 pseudonyms (do not rotate or analytics break)
- `METABASE_ENCRYPTION_KEY` — Metabase's own encryption-at-rest key
- `METABASE_PROXY_USER` / `METABASE_PROXY_PASSWORD` — outer basic-auth

If a laptop with this file is lost, rotate `DEVICE_TOKEN_SECRET` (re-enrol everyone), `METABASE_PROXY_PASSWORD`, and any DB user the laptop connected as.

## 7. Where the code lives

- `uganda-backend/app/main.py` — FastAPI ingestion (/enroll, /assessments, /delete_my_data, /health)
- `uganda-backend/etl/promote.py` — every 15 min, promotes tier_1 → tier_2 with Presidio NER
- `uganda-backend/etl/retention.py` — daily 03:15 UTC, enforces DPPA §18 retention
- `uganda-backend/schema.sql` — the full schema (two tiers, audit_log, device_registry)
- `uganda-backend/migrations/` — numbered, idempotent, apply in order
- `uganda-backend/nginx-easehealth.conf` — nginx config (rate limits, HSTS, basic-auth for Metabase)
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/healthdemo/` — the feature code
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/healthdemo/data/UgandaApi.kt` — the client

## 8. Common first-hour tasks

- **View today's triage counts**: Metabase → New question → tier_2_analytics → assessments → count by `triage_level` filtered by `submitted_date = today`.
- **Check why a specific device stopped syncing**: `SELECT revoked_at, revoke_reason, last_seen FROM device_registry WHERE device_token = '<uuid>'`. If `revoked_at IS NOT NULL`, the device will be told "Token revoked" — the user recovers via Settings → Reset sync.
- **Check ETL backlog**: `SELECT count(*) FROM tier_1_identified.assessments WHERE ner_scanned_at IS NULL` — should be near 0 during the day.
- **Audit trail for a specific assessment**: `SELECT * FROM tier_1_identified.audit_log WHERE subject_id = '<assessment-uuid>' ORDER BY at`.

## 9. If something breaks

- API down: `sudo systemctl status easehealth-api` · `sudo journalctl -u easehealth-api -n 50`
- ETL stuck: `sudo journalctl -u easehealth-etl -n 50` (usually Presidio issue; check `/opt/easehealth-etl/.venv/bin/python -c 'import spacy; spacy.load("en_core_web_sm")'`)
- Metabase down: `sudo journalctl -u metabase -n 50` (often OOM — Metabase's JVM eats ~500 MB)
- nginx 502: API or Metabase process is down, see above.

DPPA §18 retention and §30 audit are wired; check `audit_log` to trace any write to tier_1.
