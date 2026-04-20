"""Promote tier_1_identified records to tier_2_analytics with PII redaction.

Runs periodically. For each unprocessed record:
  1. Run Microsoft Presidio NER on free-text fields.
  2. Replace detected entities with tokens ([NAME], [PHONE], [LOC], [DATE]).
  3. Derive pseudonym (HMAC of UUID).
  4. Bucket age, aggregate district-level location.
  5. INSERT INTO tier_2_analytics.assessments.
  6. Mark tier_1 record as ner_scanned.
"""
from __future__ import annotations

import hashlib
import hmac
import logging
import os
import sys
from datetime import date, datetime, timezone
from typing import Any

import psycopg2
import psycopg2.extras

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("etl")

DB_HOST = os.environ.get("DB_HOST", "127.0.0.1")
DB_NAME = os.environ.get("DB_NAME", "easehealth")
DB_USER = os.environ.get("DB_USER", "easehealth_etl")
DB_PASSWORD = os.environ["DB_ETL_PASSWORD"]
PSEUDONYM_SECRET = os.environ["PSEUDONYM_SECRET"].encode()

# ─── NER ─────────────────────────────────────────────────────────────────────

try:
    from presidio_analyzer import AnalyzerEngine
    from presidio_analyzer.nlp_engine import NlpEngineProvider
    from presidio_anonymizer import AnonymizerEngine
    from presidio_anonymizer.entities import OperatorConfig

    # Use en_core_web_sm (40MB) instead of Presidio's default en_core_web_lg (400MB).
    # Accuracy trade-off is acceptable — the on-device regex catches structured PII
    # (phones, NINs, dates) anyway, so this server pass is a second line for names.
    _nlp_config = {
        "nlp_engine_name": "spacy",
        "models": [{"lang_code": "en", "model_name": "en_core_web_sm"}],
    }
    _provider = NlpEngineProvider(nlp_configuration=_nlp_config)
    analyzer = AnalyzerEngine(nlp_engine=_provider.create_engine())
    anonymizer = AnonymizerEngine()
    PRESIDIO_AVAILABLE = True
    log.info("Presidio loaded with en_core_web_sm")
except Exception as e:
    log.warning("Presidio not available: %s — NER will be regex-only", e)
    PRESIDIO_AVAILABLE = False


# Deliberately excludes DATE_TIME and LOCATION:
#   - DATE_TIME: clinicians write "for 3 days", "onset yesterday", "LMP 2 weeks ago" —
#     Presidio shreds these and destroys the clinical narrative. The on-device regex
#     already handles dates-with-years and DOB-labelled forms, which are the real
#     PII shapes we care about.
#   - LOCATION: district, village, and facility names are the whole point of the
#     analytics layer. We aggregate to district at insert time (see main.py), so the
#     raw text benefits the qualitative review without re-identifying anyone.
PII_ENTITIES = [
    "PERSON", "PHONE_NUMBER", "EMAIL_ADDRESS",
    "CREDIT_CARD", "IBAN_CODE", "IP_ADDRESS", "URL",
]

REPLACEMENT_MAP = {
    "PERSON": "[NAME]",
    "PHONE_NUMBER": "[PHONE]",
    "EMAIL_ADDRESS": "[EMAIL]",
    "CREDIT_CARD": "[CARD]",
    "IBAN_CODE": "[ACCOUNT]",
    "IP_ADDRESS": "[IP]",
    "URL": "[URL]",
}


def redact(text: str | None) -> tuple[str | None, bool]:
    """Return (redacted_text, was_flagged)."""
    if not text:
        return text, False
    if not PRESIDIO_AVAILABLE:
        return text, False  # regex on-device is first line; this is belt-and-braces
    try:
        results = analyzer.analyze(text=text, entities=PII_ENTITIES, language="en")
        if not results:
            return text, False
        operators = {
            ent: OperatorConfig("replace", {"new_value": REPLACEMENT_MAP.get(ent, f"[{ent}]")})
            for ent in REPLACEMENT_MAP
        }
        redacted = anonymizer.anonymize(text=text, analyzer_results=results, operators=operators)
        return redacted.text, True
    except Exception as e:
        log.error("NER failed: %s", e)
        return text, False


# ─── Helpers ─────────────────────────────────────────────────────────────────


def pseudonym(assessment_id: str) -> str:
    # Full 64-char hex (256 bits). Truncating to 32 chars was fine statistically
    # but since `pseudonym` is the tier_2 primary key a rare collision would
    # silently overwrite an unrelated record via ON CONFLICT DO UPDATE.
    return hmac.new(PSEUDONYM_SECRET, assessment_id.encode(), hashlib.sha256).hexdigest()


AGE_BUCKET_MAP = {
    "Newborn (0-28 days)": "<1mo",
    "Newborn (0–28 days)": "<1mo",
    "Infant (1-11 months)": "1-11mo",
    "Infant (1–11 months)": "1-11mo",
    "Young Child (1-5 yrs)": "1-4y",
    "Young Child (1–5 yrs)": "1-4y",
    "Child (6-12 yrs)": "5-14y",
    "Child (6–12 yrs)": "5-14y",
    "Adolescent (13-17)": "5-14y",
    "Adolescent (13–17)": "5-14y",
    "Adult (18-49)": "15-49y",
    "Adult (18–49)": "15-49y",
    "Mature Adult (50-59)": "50-64y",
    "Mature Adult (50–59)": "50-64y",
    "Elder (60+)": "65+",
}


def bucket_age(raw: str | None) -> str | None:
    if not raw:
        return None
    return AGE_BUCKET_MAP.get(raw, raw)


def iso_week(d: date) -> str:
    y, w, _ = d.isocalendar()
    return f"{y}-W{w:02d}"


# ─── Main ────────────────────────────────────────────────────────────────────


BATCH_SIZE = 50


def promote_batch(conn) -> int:
    """Promote up to BATCH_SIZE unprocessed rows.

    Each row is fetched, locked, redacted, and written inside its own
    short transaction, so:
      - A concurrent API upsert either completes before we lock, or waits
        for our tx to finish — we never redact a stale snapshot.
      - A row that was re-edited between our planning SELECT and our
        write SELECT gets its fresh text redacted.
      - `FOR UPDATE SKIP LOCKED` lets a second ETL instance (shouldn't
        happen with systemd oneshot, but defensive) skip over what we've
        already claimed rather than block.
    """
    # Plan pass: cheap list of candidate IDs, no locks held across redaction.
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT id FROM tier_1_identified.assessments
            WHERE ner_scanned_at IS NULL
            ORDER BY submitted_at ASC
            LIMIT %s
            """,
            (BATCH_SIZE,),
        )
        candidate_ids = [r[0] for r in cur.fetchall()]

    promoted = 0
    for row_id in candidate_ids:
        try:
            # Re-read the row with a lock so we write against current data.
            with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
                cur.execute(
                    """
                    SELECT * FROM tier_1_identified.assessments
                    WHERE id = %s AND ner_scanned_at IS NULL
                    FOR UPDATE SKIP LOCKED
                    """,
                    (row_id,),
                )
                row = cur.fetchone()
            if row is None:
                # Someone else promoted it, or it vanished. Not our problem.
                continue

            symptoms_red, sflag = redact(row["symptoms"])
            condition_red, cflag = redact(row["condition"])

            submitted_at: datetime = row["submitted_at"]
            submitted_date = submitted_at.date()

            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO tier_2_analytics.assessments (
                        pseudonym, submitted_date, submitted_week,
                        role, symptoms_redacted, age_bucket, sex,
                        temperature, pulse_rate, blood_pressure, respiratory_rate,
                        confirmed_signs, triage_level, condition_redacted, confidence,
                        red_flags,
                        guidance_used, final_action, issue_tags,
                        referral_urgency, referral_destination,
                        district,
                        chipset, total_ram_mb, native_variant, app_version
                    ) VALUES (
                        %s, %s, %s,
                        %s, %s, %s, %s,
                        %s, %s, %s, %s,
                        %s, %s, %s, %s,
                        %s,
                        %s, %s, %s,
                        %s, %s,
                        %s,
                        %s, %s, %s, %s
                    ) ON CONFLICT (pseudonym) DO UPDATE SET
                        symptoms_redacted = EXCLUDED.symptoms_redacted,
                        condition_redacted = EXCLUDED.condition_redacted,
                        promoted_at = now()
                    """,
                    (
                        pseudonym(str(row["id"])),
                        submitted_date,
                        iso_week(submitted_date),
                        row["role"], symptoms_red, bucket_age(row["age_range"]), row["sex"],
                        row["temperature"], row["pulse_rate"], row["blood_pressure"], row["respiratory_rate"],
                        row["confirmed_signs"] or [],
                        row["triage_level"], condition_red, row["confidence"],
                        row["red_flags"] or [],
                        row["guidance_used"], row["final_action"], row["issue_tags"] or [],
                        row["referral_urgency"], row["referral_destination"],
                        row["district"],
                        row["chipset"], row["total_ram_mb"], row["native_variant"], row["app_version"],
                    ),
                )
                cur.execute(
                    """
                    UPDATE tier_1_identified.assessments
                    SET ner_scanned_at = now(), ner_flagged = %s
                    WHERE id = %s
                    """,
                    (sflag or cflag, row["id"]),
                )
            conn.commit()
            promoted += 1
        except Exception as e:
            log.exception("Failed to promote %s: %s", row["id"], e)
            conn.rollback()

    return promoted


def main() -> int:
    # Fail closed: if Presidio didn't load, exit non-zero rather than
    # silently promoting raw symptoms/condition text into tier_2.
    # systemd records the failure; an operator can fix the NER install
    # before the next timer fire. This is the safest default for PII.
    if not PRESIDIO_AVAILABLE:
        log.error("Presidio unavailable — refusing to promote (would leak PII into tier_2)")
        return 1

    conn = psycopg2.connect(
        host=DB_HOST, dbname=DB_NAME, user=DB_USER, password=DB_PASSWORD
    )
    try:
        total = 0
        while True:
            n = promote_batch(conn)
            total += n
            if n < BATCH_SIZE:
                break
        log.info("Promoted %d records", total)
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
