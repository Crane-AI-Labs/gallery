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
import re
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


class RedactionError(Exception):
    """A row's text could not be safely redacted. Fail closed: the caller must
    leave the row unpromoted rather than promote possibly-raw PII."""


# Narrow structured-date catch (closes the DOB false-negative without turning
# on Presidio DATE_TIME, which shreds clinical durations like "for 3 days"):
# full dates carrying a year, in either order, plus DOB-labelled fragments.
# None of these shapes can match a duration.
DATE_PII_PATTERNS = [
    re.compile(r"\b\d{1,2}[/\-.]\d{1,2}[/\-.](?:19|20)\d{2}\b"),
    re.compile(r"\b(?:19|20)\d{2}[/\-.]\d{1,2}[/\-.]\d{1,2}\b"),
    re.compile(
        r"\b\d{1,2}\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+(?:19|20)\d{2}\b",
        re.IGNORECASE,
    ),
    re.compile(r"(?i)\b(?:dob|date\s+of\s+birth|born(?:\s+on)?)\b[\s:]*[^,.;\n]{0,24}\d"),
]


def redact(text: str | None) -> tuple[str | None, bool]:
    """Return (redacted_text, was_flagged).

    Raises RedactionError instead of passing text through when NER fails on
    this row. Before 2026-07-13 a per-row Presidio exception returned the
    ORIGINAL text unflagged, and the row was promoted to tier_2 permanently —
    the per-row path must be as fail-closed as the load-time check in main().
    """
    if not text:
        return text, False
    flagged = False
    for pat in DATE_PII_PATTERNS:
        text, n = pat.subn("[DATE]", text)
        if n:
            flagged = True
    if not PRESIDIO_AVAILABLE:
        raise RedactionError("Presidio unavailable")
    try:
        results = analyzer.analyze(text=text, entities=PII_ENTITIES, language="en")
        if not results:
            return text, flagged
        operators = {
            ent: OperatorConfig("replace", {"new_value": REPLACEMENT_MAP.get(ent, f"[{ent}]")})
            for ent in REPLACEMENT_MAP
        }
        redacted = anonymizer.anonymize(text=text, analyzer_results=results, operators=operators)
        return redacted.text, True
    except Exception as e:
        raise RedactionError(f"NER failed: {e}") from e


def redact_list(items: list | None) -> tuple[list, bool]:
    """Element-wise redact for the clinical text arrays (treatment, next_steps)."""
    out: list = []
    flagged = False
    for item in items or []:
        red, f = redact(item)
        out.append(red)
        flagged = flagged or f
    return out, flagged


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
    # Fixed 2026-07-13: was mapped to "5-14y", mislabeling 13-17-year-olds in
    # analytics. A faithful bucket beats forcing them into 5-14y or 15-49y.
    "Adolescent (13-17)": "13-17y",
    "Adolescent (13–17)": "13-17y",
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


class _RowSkipped(Exception):
    """Internal: row intentionally left unpromoted (fail-closed redaction)."""


def promote_batch(conn) -> tuple[int, int]:
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
    skipped = 0
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

            # Fail closed per row: if any text can't be redacted, skip the row
            # (leave ner_scanned_at NULL so it retries next run) and surface it.
            try:
                symptoms_red, f_sym = redact(row["symptoms"])
                condition_red, f_cond = redact(row["condition"])
                referral_notes_red, f_ref = redact(row["referral_notes"])
                custom_role_red, f_role = redact(row["custom_role"])
                treatment_admin_red, f_tadm = redact(row.get("treatment_administered"))
                treatment_red, f_treat = redact_list(row["treatment"])
                next_steps_red, f_next = redact_list(row["next_steps"])
            except RedactionError as e:
                log.error("Redaction failed for %s — left unpromoted (fail closed): %s", row_id, e)
                conn.rollback()
                raise _RowSkipped from e
            any_flag = f_sym or f_cond or f_ref or f_role or f_tadm or f_treat or f_next

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
                        chipset, total_ram_mb, native_variant, app_version,
                        inference_ms,
                        device_model, device_manufacturer, android_api, android_version,
                        max_cpu_freq_mhz, cpu_count, perf_cores, recommended_n_batch,
                        treatment_redacted, next_steps_redacted, referral_reasons,
                        referral_notes_redacted, custom_role_redacted,
                        client_timestamp_ms, duration_value, duration_unit,
                        age_years, age_months, spo2,
                        treatment_administered_redacted, is_test,
                        guidance_concern, ttft_ms, inference_retries,
                        latitude, longitude
                    ) VALUES (
                        %s, %s, %s,
                        %s, %s, %s, %s,
                        %s, %s, %s, %s,
                        %s, %s, %s, %s,
                        %s,
                        %s, %s, %s,
                        %s, %s,
                        %s,
                        %s, %s, %s, %s,
                        %s,
                        %s, %s, %s, %s,
                        %s, %s, %s, %s,
                        %s, %s, %s,
                        %s, %s,
                        %s, %s, %s,
                        %s, %s, %s,
                        %s, %s,
                        %s, %s, %s,
                        %s, %s
                    ) ON CONFLICT (pseudonym) DO UPDATE SET
                        -- Full refresh (2026-07-13). The old 4-field SET list
                        -- stranded late clinician confirmations and edits in
                        -- tier 1 forever. Everything except the immutable keys
                        -- now follows the tier-1 row.
                        role = EXCLUDED.role,
                        symptoms_redacted = EXCLUDED.symptoms_redacted,
                        age_bucket = EXCLUDED.age_bucket,
                        sex = EXCLUDED.sex,
                        temperature = EXCLUDED.temperature,
                        pulse_rate = EXCLUDED.pulse_rate,
                        blood_pressure = EXCLUDED.blood_pressure,
                        respiratory_rate = EXCLUDED.respiratory_rate,
                        confirmed_signs = EXCLUDED.confirmed_signs,
                        triage_level = EXCLUDED.triage_level,
                        condition_redacted = EXCLUDED.condition_redacted,
                        confidence = EXCLUDED.confidence,
                        red_flags = EXCLUDED.red_flags,
                        guidance_used = EXCLUDED.guidance_used,
                        final_action = EXCLUDED.final_action,
                        issue_tags = EXCLUDED.issue_tags,
                        referral_urgency = EXCLUDED.referral_urgency,
                        referral_destination = EXCLUDED.referral_destination,
                        district = EXCLUDED.district,
                        chipset = EXCLUDED.chipset,
                        total_ram_mb = EXCLUDED.total_ram_mb,
                        native_variant = EXCLUDED.native_variant,
                        app_version = EXCLUDED.app_version,
                        inference_ms = COALESCE(EXCLUDED.inference_ms, tier_2_analytics.assessments.inference_ms),
                        device_model = EXCLUDED.device_model,
                        device_manufacturer = EXCLUDED.device_manufacturer,
                        android_api = EXCLUDED.android_api,
                        android_version = EXCLUDED.android_version,
                        max_cpu_freq_mhz = EXCLUDED.max_cpu_freq_mhz,
                        cpu_count = EXCLUDED.cpu_count,
                        perf_cores = EXCLUDED.perf_cores,
                        recommended_n_batch = EXCLUDED.recommended_n_batch,
                        treatment_redacted = EXCLUDED.treatment_redacted,
                        next_steps_redacted = EXCLUDED.next_steps_redacted,
                        referral_reasons = EXCLUDED.referral_reasons,
                        referral_notes_redacted = EXCLUDED.referral_notes_redacted,
                        custom_role_redacted = EXCLUDED.custom_role_redacted,
                        client_timestamp_ms = EXCLUDED.client_timestamp_ms,
                        duration_value = EXCLUDED.duration_value,
                        duration_unit = EXCLUDED.duration_unit,
                        age_years = EXCLUDED.age_years,
                        age_months = EXCLUDED.age_months,
                        spo2 = EXCLUDED.spo2,
                        treatment_administered_redacted = EXCLUDED.treatment_administered_redacted,
                        is_test = EXCLUDED.is_test,
                        guidance_concern = COALESCE(EXCLUDED.guidance_concern, tier_2_analytics.assessments.guidance_concern),
                        ttft_ms = COALESCE(EXCLUDED.ttft_ms, tier_2_analytics.assessments.ttft_ms),
                        inference_retries = COALESCE(EXCLUDED.inference_retries, tier_2_analytics.assessments.inference_retries),
                        latitude = EXCLUDED.latitude,
                        longitude = EXCLUDED.longitude,
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
                        row.get("inference_ms"),
                        row["device_model"], row["device_manufacturer"], row["android_api"], row["android_version"],
                        row["max_cpu_freq_mhz"], row["cpu_count"], row["perf_cores"], row["recommended_n_batch"],
                        treatment_red, next_steps_red, row["referral_reasons"] or [],
                        referral_notes_red, custom_role_red,
                        row["client_timestamp_ms"], row["duration_value"], row["duration_unit"],
                        row.get("age_years"), row.get("age_months"), row.get("spo2"),
                        treatment_admin_red, row.get("is_test", False),
                        row.get("guidance_concern"), row.get("ttft_ms"), row.get("inference_retries"),
                        # lat/lon promotion approved 2026-07-20 (already 2dp ~1.1km on-device)
                        row.get("latitude"), row.get("longitude"),
                    ),
                )
                cur.execute(
                    """
                    UPDATE tier_1_identified.assessments
                    SET ner_scanned_at = now(), ner_flagged = %s
                    WHERE id = %s
                    """,
                    (any_flag, row["id"]),
                )
            conn.commit()
            promoted += 1
        except _RowSkipped:
            skipped += 1
        except Exception as e:
            log.exception("Failed to promote %s: %s", row_id, e)
            conn.rollback()
            skipped += 1

    return promoted, skipped


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
        total_skipped = 0
        while True:
            n, sk = promote_batch(conn)
            total += n
            total_skipped += sk
            if n + sk < BATCH_SIZE:
                break
        log.info("Promoted %d records (%d skipped)", total, total_skipped)
        if total_skipped:
            # Non-zero exit so systemd marks the run failed and it is visible
            # to monitoring; the skipped rows retry on the next timer fire.
            log.error("%d rows left unpromoted (fail-closed redaction)", total_skipped)
            return 1
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
