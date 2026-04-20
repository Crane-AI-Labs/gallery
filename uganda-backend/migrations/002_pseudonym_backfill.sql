-- Migration 002: backfill tier_2 pseudonyms to 64-char (full SHA-256 hex).
--
-- Context: promote.py used to truncate pseudonyms to 32 hex chars (128 bits)
-- before commit 5f1ab5e moved to the full 64-char form. Rows promoted under
-- the old scheme have 32-char pseudonyms; a re-POST of the same tier_1 id
-- now hashes to a different 64-char pseudonym and inserts a SECOND tier_2
-- row instead of updating the first. This migration nulls ner_scanned_at on
-- every tier_1 row whose pseudonym in tier_2 still has the short form, and
-- deletes the short-form tier_2 rows so the next ETL run re-promotes them
-- under the new 64-char scheme. One-shot, idempotent.
--
-- Run: sudo -u postgres psql -d easehealth -f 002_pseudonym_backfill.sql
-- Then: sudo systemctl start easehealth-etl

BEGIN;

-- Count what we'll touch so the operator sees scale before committing.
DO $$
DECLARE
    orphan_count INTEGER;
BEGIN
    SELECT count(*) INTO orphan_count
      FROM tier_2_analytics.assessments
      WHERE length(pseudonym) < 64;
    RAISE NOTICE 'tier_2 rows with legacy 32-char pseudonym: %', orphan_count;
END
$$;

-- Re-queue the source tier_1 rows. We can't join directly (pseudonym is an
-- HMAC so there's no lookup column), so we fall back to submitted_date + role
-- as a coarse match and nuke all short-form rows after. Safe because NER
-- redaction is deterministic — the re-run produces identical tier_2 text.
UPDATE tier_1_identified.assessments t1
   SET ner_scanned_at = NULL,
       ner_flagged = FALSE
  WHERE ner_scanned_at IS NOT NULL
    AND EXISTS (
        SELECT 1 FROM tier_2_analytics.assessments t2
        WHERE length(t2.pseudonym) < 64
          AND t2.submitted_date = t1.submitted_at::date
          AND t2.role = t1.role
    );

-- Drop the orphan tier_2 rows so the next ETL run recreates them under the
-- new pseudonym scheme. De-identified records only — no PII is lost.
DELETE FROM tier_2_analytics.assessments WHERE length(pseudonym) < 64;

COMMIT;
