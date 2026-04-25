-- Migration 003: store wall-clock inference latency per assessment
--
-- The Android app already measures durationMs around the MedGemma call
-- (see HealthDemoViewModel.runMedGemmaInferenceWithRetry); previously
-- it was emitted to Firebase Analytics only. From 1.0.4 we send it on
-- the /assessments POST so the data team can analyse generation time
-- alongside chipset/RAM/native_variant.
--
-- Idempotent — safe to re-run.
--
-- Run: sudo -u postgres psql -d easehealth -f 003_inference_ms.sql

BEGIN;

ALTER TABLE tier_1_identified.assessments
    ADD COLUMN IF NOT EXISTS inference_ms BIGINT;

ALTER TABLE tier_2_analytics.assessments
    ADD COLUMN IF NOT EXISTS inference_ms BIGINT;

COMMIT;
