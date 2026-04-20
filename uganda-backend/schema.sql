-- Ease Health two-tier schema
-- tier_1_identified: full records, restricted access, short retention
-- tier_2_analytics:  pseudonymized records, broader access, long retention

SET search_path TO tier_1_identified;

CREATE TABLE IF NOT EXISTS assessments (
    id                       UUID PRIMARY KEY,
    device_token             TEXT NOT NULL,
    submitted_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    client_timestamp_ms      BIGINT,

    -- Patient intake
    role                     TEXT NOT NULL,
    custom_role              TEXT,
    symptoms                 TEXT,
    duration_value           TEXT,
    duration_unit            TEXT,
    age_range                TEXT,
    sex                      TEXT,

    -- Vitals (all stored as text to match app)
    temperature              TEXT,
    pulse_rate               TEXT,
    blood_pressure           TEXT,
    respiratory_rate         TEXT,

    confirmed_signs          TEXT[],

    -- Guidance (AI output)
    triage_level             TEXT,
    condition                TEXT,
    confidence               TEXT,
    treatment                TEXT[],
    next_steps               TEXT[],
    red_flags                TEXT[],

    -- Clinician confirmation
    guidance_used            TEXT,
    final_action             TEXT,
    issue_tags               TEXT[],

    -- Referral
    referral_urgency         TEXT,
    referral_destination     TEXT,
    referral_reasons         TEXT[],
    referral_notes           TEXT,

    -- Location (already rounded on-device)
    latitude                 DOUBLE PRECISION,
    longitude                DOUBLE PRECISION,
    location_accuracy_m      REAL,
    district                 TEXT,

    -- Device diagnostics (for optimization)
    device_model             TEXT,
    device_manufacturer      TEXT,
    chipset                  TEXT,
    android_api              INTEGER,
    android_version          TEXT,
    total_ram_mb             BIGINT,
    max_cpu_freq_mhz         BIGINT,
    cpu_count                INTEGER,
    native_variant           TEXT,
    perf_cores               TEXT,
    recommended_n_batch      INTEGER,
    app_version              TEXT,

    -- PII scan state (set by ETL)
    ner_scanned_at           TIMESTAMPTZ,
    ner_flagged              BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_assessments_submitted_at
    ON tier_1_identified.assessments (submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_assessments_device_token
    ON tier_1_identified.assessments (device_token);
CREATE INDEX IF NOT EXISTS idx_assessments_pending_ner
    ON tier_1_identified.assessments (ner_scanned_at) WHERE ner_scanned_at IS NULL;

CREATE TABLE IF NOT EXISTS paused_consultations (
    id                UUID PRIMARY KEY,
    device_token      TEXT NOT NULL,
    submitted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    role              TEXT,
    symptoms          TEXT,
    age_range         TEXT,
    pause_reason      TEXT,
    note              TEXT
);

CREATE TABLE IF NOT EXISTS device_registry (
    device_token        TEXT PRIMARY KEY,
    first_seen          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen           TIMESTAMPTZ NOT NULL DEFAULT now(),
    device_model        TEXT,
    chipset             TEXT,
    android_api         INTEGER,
    app_version         TEXT,
    -- Revocation: set by /delete_my_data or scripts/revoke-device.sh.
    -- verify_token in the API rejects tokens whose row has revoked_at set.
    revoked_at          TIMESTAMPTZ,
    revoke_reason       TEXT
);

-- Partial index — verify_token's hot path is "is this token currently active".
CREATE INDEX IF NOT EXISTS idx_device_registry_active
    ON tier_1_identified.device_registry (device_token)
    WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS audit_log (
    id                BIGSERIAL PRIMARY KEY,
    at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor             TEXT NOT NULL,       -- user/role that acted
    action            TEXT NOT NULL,       -- e.g. "reidentify", "api_write"
    subject_id        UUID,                -- assessment or pseudonym
    ip                TEXT,                -- client IP at time of action
    reason            TEXT,
    meta              JSONB
);

CREATE INDEX IF NOT EXISTS idx_audit_log_at ON tier_1_identified.audit_log (at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON tier_1_identified.audit_log (actor);

-- ─── Tier 2: Analytics (pseudonymized) ────────────────────────────────────────
SET search_path TO tier_2_analytics;

CREATE TABLE IF NOT EXISTS assessments (
    pseudonym               TEXT PRIMARY KEY,   -- HMAC of tier_1.id
    submitted_date          DATE NOT NULL,
    submitted_week           TEXT,               -- ISO week "2026-W15"

    role                    TEXT,
    symptoms_redacted       TEXT,               -- after NER pass
    age_bucket              TEXT,               -- <1mo, 1-11mo, 1-4y, 5-14y, 15-49y, 50-64y, 65+
    sex                     TEXT,

    temperature             TEXT,
    pulse_rate              TEXT,
    blood_pressure          TEXT,
    respiratory_rate        TEXT,

    confirmed_signs         TEXT[],
    triage_level            TEXT,
    condition_redacted      TEXT,
    confidence              TEXT,
    red_flags               TEXT[],

    guidance_used           TEXT,
    final_action            TEXT,
    issue_tags              TEXT[],

    referral_urgency        TEXT,
    referral_destination    TEXT,

    -- Location aggregated to district only
    district                TEXT,

    -- Device bucket info for optimization analysis
    chipset                 TEXT,
    total_ram_mb            BIGINT,
    native_variant          TEXT,
    app_version             TEXT,

    promoted_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_analytics_submitted_date
    ON tier_2_analytics.assessments (submitted_date DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_district
    ON tier_2_analytics.assessments (district);
CREATE INDEX IF NOT EXISTS idx_analytics_triage
    ON tier_2_analytics.assessments (triage_level);

-- Grants
GRANT INSERT, SELECT, UPDATE, DELETE ON tier_1_identified.assessments TO easehealth_api;
GRANT INSERT, SELECT, UPDATE, DELETE ON tier_1_identified.paused_consultations TO easehealth_api;
GRANT INSERT, SELECT, UPDATE ON tier_1_identified.device_registry TO easehealth_api;
GRANT INSERT, SELECT ON tier_1_identified.audit_log TO easehealth_api;
GRANT USAGE, SELECT ON SEQUENCE tier_1_identified.audit_log_id_seq TO easehealth_api;

GRANT SELECT, UPDATE, DELETE ON tier_1_identified.assessments TO easehealth_etl;
GRANT SELECT, DELETE ON tier_1_identified.paused_consultations TO easehealth_etl;
GRANT SELECT, DELETE ON tier_1_identified.device_registry TO easehealth_etl;
GRANT INSERT, SELECT, UPDATE, DELETE ON tier_2_analytics.assessments TO easehealth_etl;
GRANT INSERT ON tier_1_identified.audit_log TO easehealth_etl;
GRANT USAGE, SELECT ON SEQUENCE tier_1_identified.audit_log_id_seq TO easehealth_etl;

GRANT SELECT ON tier_2_analytics.assessments TO easehealth_analytics;
