"""Retention worker for DPPA 2019 §18 — minimum-necessary storage period.

Deletes from tier_1_identified after the identified-data retention window.
tier_2_analytics (pseudonymised) is kept longer per the consent copy, which
lets the clinical team audit trends without re-identifying individuals.

Policy (reviewed 2026-04-19):
  tier_1_identified.assessments          — 90 days after ner_scanned_at
  tier_1_identified.paused_consultations — 14 days (short-lived by design)
  tier_1_identified.device_registry      — 180 days after last_seen
  tier_2_analytics.assessments           — 2 years after submitted_date

Runs from a systemd timer once per day. Uses a fresh connection rather than
the API pool so deletions can't stall live traffic.
"""
from __future__ import annotations

import logging
import os
import sys

import psycopg2

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("retention")

DB_HOST = os.environ.get("DB_HOST", "127.0.0.1")
DB_NAME = os.environ.get("DB_NAME", "easehealth")
DB_USER = os.environ.get("DB_USER", "easehealth_etl")
DB_PASSWORD = os.environ["DB_ETL_PASSWORD"]

TIER_1_ASSESSMENTS_DAYS = int(os.environ.get("RETENTION_TIER1_DAYS", "90"))
PAUSED_DAYS = int(os.environ.get("RETENTION_PAUSED_DAYS", "14"))
DEVICE_REGISTRY_DAYS = int(os.environ.get("RETENTION_DEVICE_DAYS", "180"))
TIER_2_YEARS = int(os.environ.get("RETENTION_TIER2_YEARS", "2"))


def run() -> int:
    conn = psycopg2.connect(
        host=DB_HOST, dbname=DB_NAME, user=DB_USER, password=DB_PASSWORD
    )
    try:
        with conn.cursor() as cur:
            # Only delete records that have been successfully promoted to tier_2
            # (ner_scanned_at IS NOT NULL) — a scanning failure keeps the
            # identified record around so it can be retried rather than lost.
            cur.execute(
                """
                DELETE FROM tier_1_identified.assessments
                WHERE ner_scanned_at IS NOT NULL
                  AND ner_scanned_at < now() - (%s || ' days')::interval
                """,
                (TIER_1_ASSESSMENTS_DAYS,),
            )
            log.info("tier_1 assessments deleted: %d", cur.rowcount)

            cur.execute(
                """
                DELETE FROM tier_1_identified.paused_consultations
                WHERE submitted_at < now() - (%s || ' days')::interval
                """,
                (PAUSED_DAYS,),
            )
            log.info("paused_consultations deleted: %d", cur.rowcount)

            cur.execute(
                """
                DELETE FROM tier_1_identified.device_registry
                WHERE last_seen < now() - (%s || ' days')::interval
                """,
                (DEVICE_REGISTRY_DAYS,),
            )
            log.info("device_registry deleted: %d", cur.rowcount)

            cur.execute(
                """
                DELETE FROM tier_2_analytics.assessments
                WHERE submitted_date < (now() - (%s || ' years')::interval)::date
                """,
                (TIER_2_YEARS,),
            )
            log.info("tier_2 assessments deleted: %d", cur.rowcount)

        conn.commit()
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(run())
