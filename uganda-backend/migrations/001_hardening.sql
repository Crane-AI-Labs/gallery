-- Migration 001: production hardening
-- Apply with: sudo -u postgres psql -d easehealth -f 001_hardening.sql

BEGIN;

-- Token revocation: checked in verify_token.
ALTER TABLE tier_1_identified.device_registry
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS revoke_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_device_registry_active
    ON tier_1_identified.device_registry (device_token)
    WHERE revoked_at IS NULL;

-- Audit log: make actor/action a bit tighter but keep it flexible.
ALTER TABLE tier_1_identified.audit_log
    ADD COLUMN IF NOT EXISTS ip TEXT;

CREATE INDEX IF NOT EXISTS idx_audit_log_at ON tier_1_identified.audit_log (at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON tier_1_identified.audit_log (actor);

-- Grant revocation writes to api (for self-service token reset + delete-my-data).
GRANT UPDATE ON tier_1_identified.device_registry TO easehealth_api;
GRANT DELETE ON tier_1_identified.assessments TO easehealth_api;
GRANT DELETE ON tier_1_identified.paused_consultations TO easehealth_api;

-- audit_log is BIGSERIAL — INSERT alone isn't enough, the inserter needs
-- USAGE on the sequence too. This was the "permission denied for sequence
-- audit_log_id_seq" log spam in the first rollout.
GRANT USAGE, SELECT ON SEQUENCE tier_1_identified.audit_log_id_seq TO easehealth_api;
GRANT USAGE, SELECT ON SEQUENCE tier_1_identified.audit_log_id_seq TO easehealth_etl;

COMMIT;
