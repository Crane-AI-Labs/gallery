"""Ease Health Uganda API — ingestion service.

Receives assessment POSTs from the Android app, writes to tier_1_identified.
Auth via device token (HMAC-signed UUID issued on first sync). Revocation
checked on every request; an audit row is written for each tier_1 write.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import uuid
from contextlib import asynccontextmanager
from typing import Any, Literal

import asyncpg
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("api")

DB_HOST = os.environ.get("DB_HOST", "127.0.0.1")
DB_NAME = os.environ.get("DB_NAME", "easehealth")
DB_USER = os.environ.get("DB_USER", "easehealth_api")
DB_PASSWORD = os.environ["DB_API_PASSWORD"]

# Shared secret used to sign device tokens. Rotating this forces re-enrollment
# of every device — prefer per-device revocation via device_registry.revoked_at.
DEVICE_TOKEN_SECRET = os.environ["DEVICE_TOKEN_SECRET"].encode()

pool: asyncpg.Pool | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    pool = await asyncpg.create_pool(
        host=DB_HOST, database=DB_NAME, user=DB_USER, password=DB_PASSWORD,
        min_size=1, max_size=10, timeout=10,
    )
    try:
        yield
    finally:
        if pool:
            await pool.close()


app = FastAPI(title="Ease Health Uganda API", version="1.1.0", lifespan=lifespan)

VALIDATION_LOG = "/var/log/easehealth/validation-failures.jsonl"


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    import pathlib, datetime
    record = {
        "ts": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "path": request.url.path,
        "errors": exc.errors(),
        "body": exc.body,
        "ip": request.client.host if request.client else None,
    }
    try:
        pathlib.Path(VALIDATION_LOG).parent.mkdir(parents=True, exist_ok=True)
        with open(VALIDATION_LOG, "a") as f:
            f.write(json.dumps(record, default=str) + "\n")
    except Exception as e:
        log.error("validation log write failed: %s", e)
    return JSONResponse(status_code=422, content={"detail": exc.errors()})


@app.exception_handler(Exception)
async def catch_all(request: Request, exc: Exception) -> JSONResponse:
    # Never leak SQL / stack traces to clients. Log the details server-side.
    log.exception("Unhandled error on %s %s: %s", request.method, request.url.path, exc)
    return JSONResponse(status_code=500, content={"detail": "internal error"})


# ─── Token management ────────────────────────────────────────────────────────


def sign_token(device_id: str) -> str:
    """Return `<device_id>.<hmac>` — proves the token was issued by this server."""
    sig = hmac.new(DEVICE_TOKEN_SECRET, device_id.encode(), hashlib.sha256).hexdigest()
    return f"{device_id}.{sig}"


def verify_signature(token: str) -> str | None:
    if "." not in token:
        return None
    device_id, sig = token.rsplit(".", 1)
    expected = hmac.new(DEVICE_TOKEN_SECRET, device_id.encode(), hashlib.sha256).hexdigest()
    return device_id if hmac.compare_digest(sig, expected) else None


async def require_device(
    request: Request,
    authorization: str | None = Header(None),
) -> str:
    """Auth gate: valid signature + not-revoked in device_registry.

    A device that was revoked (leaked phone, decommissioned health worker) is
    rejected even though its token signature is still cryptographically valid.
    """
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Missing Bearer token")
    token = authorization.removeprefix("Bearer ").strip()
    device_id = verify_signature(token)
    if not device_id:
        raise HTTPException(401, "Invalid token")

    if pool is None:
        raise HTTPException(503, "Service unavailable")
    # Revocation lookup. Uses partial index idx_device_registry_active.
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT revoked_at FROM tier_1_identified.device_registry WHERE device_token = $1",
            device_id,
        )
    if row is not None and row["revoked_at"] is not None:
        raise HTTPException(401, "Token revoked")

    # Stash the client IP on the request scope for audit logging downstream.
    request.state.client_ip = request.client.host if request.client else None
    return device_id


async def audit(
    actor: str,
    action: str,
    subject_id: str | None,
    ip: str | None,
    reason: str | None = None,
    meta: dict[str, Any] | None = None,
) -> None:
    """Best-effort write to tier_1_identified.audit_log.

    An audit failure must not fail the underlying request, but must still be
    logged server-side so an ops engineer can notice audit loss.
    """
    if pool is None:
        log.error("audit: pool missing; dropping %s %s", actor, action)
        return
    try:
        subject_uuid = uuid.UUID(subject_id) if subject_id else None
        async with pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO tier_1_identified.audit_log (actor, action, subject_id, ip, reason, meta)
                VALUES ($1, $2, $3, $4, $5, $6)
                """,
                actor, action, subject_uuid, ip, reason,
                json.dumps(meta) if meta else None,
            )
    except Exception as e:
        log.error("audit write failed: %s", e)


# ─── Schemas ─────────────────────────────────────────────────────────────────

# Literal types pin accepted enums; malformed clients get a clean 422 rather
# than polluting analytics with typos like "Nursee" or "URGNT".
PatientRole = Literal["Doctor", "Nurse", "MedicalOfficer", "Midwife", "Other"]
DurationUnit = Literal["Hours", "Days", "Weeks", "Months", "Years"]
TriageLevel = Literal[
    "Emergency referral", "Urgent clinic visit", "Routine care", "Home care",
    "URGENT", "ROUTINE", "EMERGENCY",  # tolerate a few legacy shapes
]
ConfidenceLevel = Literal["low", "medium", "high"]
GuidanceUsedEnum = Literal["Yes", "Partially", "No"]
FinalActionEnum = Literal[
    "ManagedLocally", "Referred", "Escalated", "SupervisorConsult", "Other",
]
SexEnum = Literal["Female", "Male"]
ReferralUrgencyEnum = Literal["Emergency", "Urgent", "Routine"]

MAX_FREE_TEXT = 4000
MAX_SHORT_TEXT = 200


class VitalSigns(BaseModel):
    temperature: str | None = Field(default=None, max_length=16)
    pulse_rate: str | None = Field(default=None, max_length=16)
    blood_pressure: str | None = Field(default=None, max_length=16)
    respiratory_rate: str | None = Field(default=None, max_length=16)


class Guidance(BaseModel):
    triage_level: TriageLevel | None = None
    condition: str | None = Field(default=None, max_length=MAX_SHORT_TEXT)
    confidence: ConfidenceLevel | None = None
    treatment: list[str] = Field(default_factory=list, max_length=20)
    next_steps: list[str] = Field(default_factory=list, max_length=20)
    red_flags: list[str] = Field(default_factory=list, max_length=20)


class Location(BaseModel):
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    accuracy_meters: float | None = Field(default=None, ge=0, le=100_000)
    district: str | None = Field(default=None, max_length=80)


class Device(BaseModel):
    device_model: str | None = Field(default=None, max_length=64)
    device_manufacturer: str | None = Field(default=None, max_length=64)
    chipset: str | None = Field(default=None, max_length=64)
    android_api: int | None = Field(default=None, ge=0, le=99)
    android_version: str | None = Field(default=None, max_length=32)
    total_ram_mb: int | None = Field(default=None, ge=0, le=1_048_576)
    max_cpu_freq_mhz: int | None = Field(default=None, ge=0, le=10_000)
    cpu_count: int | None = Field(default=None, ge=0, le=256)
    native_variant: str | None = Field(default=None, max_length=32)
    perf_cores: str | None = Field(default=None, max_length=64)
    recommended_n_batch: int | None = Field(default=None, ge=0, le=4096)
    app_version: str | None = Field(default=None, max_length=32)


class ClinicianConfirmation(BaseModel):
    guidance_used: GuidanceUsedEnum | None = None
    final_action: FinalActionEnum | None = None
    issue_tags: list[str] = Field(default_factory=list, max_length=10)


class Referral(BaseModel):
    urgency: ReferralUrgencyEnum | None = None
    destination: str | None = Field(default=None, max_length=80)
    reasons: list[str] = Field(default_factory=list, max_length=10)
    notes: str | None = Field(default=None, max_length=MAX_FREE_TEXT)


class AssessmentPayload(BaseModel):
    id: uuid.UUID
    # Client timestamp in ms since epoch. Bounded roughly to the project's
    # actual lifetime (2023 → 2040) so a phone with a broken RTC doesn't
    # poison analytics with 1970 / 2099 timestamps. The SERVER's submitted_at
    # is authoritative — this field only preserves the client's view for
    # audit. `1_672_531_200_000` is 2023-01-01; `2_208_988_800_000` is 2040.
    timestamp: int = Field(ge=1_672_531_200_000, le=2_208_988_800_000)
    role: PatientRole
    custom_role: str | None = Field(default=None, max_length=64)
    symptoms: str | None = Field(default=None, max_length=MAX_FREE_TEXT)
    duration_value: str | None = Field(default=None, max_length=16)
    duration_unit: DurationUnit | None = None
    age: str | None = Field(default=None, max_length=64)
    sex: SexEnum | None = None
    vital_signs: VitalSigns = Field(default_factory=VitalSigns)
    confirmed_signs: list[str] = Field(default_factory=list, max_length=30)
    guidance: Guidance = Field(default_factory=Guidance)
    clinician_confirmation: ClinicianConfirmation | None = None
    referral: Referral | None = None
    location: Location = Field(default_factory=Location)
    device: Device = Field(default_factory=Device)
    # Wall-clock generation latency in ms. Bounded by the client-side
    # INFERENCE_TIMEOUT_MS (60s); allow up to 5 min in case we relax
    # that later. None for legacy clients (pre-1.0.4).
    inference_ms: int | None = Field(default=None, ge=0)


class PausedPayload(BaseModel):
    id: uuid.UUID
    timestamp: int = Field(ge=1_672_531_200_000, le=2_208_988_800_000)
    role: PatientRole | None = None
    symptoms: str | None = Field(default=None, max_length=MAX_FREE_TEXT)
    age: str | None = Field(default=None, max_length=64)
    pause_reason: str | None = Field(default=None, max_length=64)
    note: str | None = Field(default=None, max_length=MAX_FREE_TEXT)
    device: Device = Field(default_factory=Device)


class PausedDeletePayload(BaseModel):
    id: uuid.UUID


class DiagnosticsPayload(BaseModel):
    device_id: str | None = Field(default=None, max_length=64)
    device_model: str | None = Field(default=None, max_length=64)
    device_manufacturer: str | None = Field(default=None, max_length=64)
    chipset: str | None = Field(default=None, max_length=64)
    android_api: int | None = Field(default=None, ge=0, le=99)
    android_version: str | None = Field(default=None, max_length=32)
    total_ram_mb: int | None = Field(default=None, ge=0, le=1_048_576)
    max_cpu_freq_mhz: int | None = Field(default=None, ge=0, le=10_000)
    cpu_count: int | None = Field(default=None, ge=0, le=256)
    native_variant: str | None = Field(default=None, max_length=32)
    perf_cores: str | None = Field(default=None, max_length=64)
    recommended_n_batch: int | None = Field(default=None, ge=0, le=4096)
    app_version: str | None = Field(default=None, max_length=32)
    synced_at: int | None = Field(default=None, ge=1_672_531_200_000, le=2_208_988_800_000)


# ─── Endpoints ───────────────────────────────────────────────────────────────


@app.post("/enroll")
async def enroll(request: Request) -> dict[str, str]:
    """Issue a new device token. Called once per install on first sync.

    No auth at this stage — rate-limited at the nginx layer (limit_req_zone).
    An audit row records each enrollment with the source IP so bursts can be
    traced after the fact.
    """
    if pool is None:
        raise HTTPException(503, "Service unavailable")
    device_id = str(uuid.uuid4())
    token = sign_token(device_id)
    ip = request.client.host if request.client else None
    async with pool.acquire() as conn:
        await conn.execute(
            """
            INSERT INTO tier_1_identified.device_registry (device_token, first_seen, last_seen)
            VALUES ($1, now(), now())
            ON CONFLICT (device_token) DO NOTHING
            """,
            device_id,
        )
    await audit("system", "enroll", device_id, ip)
    return {"device_id": device_id, "token": token}


@app.post("/assessments")
async def post_assessment(
    payload: AssessmentPayload,
    request: Request,
    authorization: str | None = Header(None),
) -> dict[str, str]:
    device_id = await require_device(request, authorization)
    if pool is None:
        raise HTTPException(503, "Service unavailable")

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                """
                INSERT INTO tier_1_identified.assessments (
                    id, device_token, submitted_at, client_timestamp_ms,
                    role, custom_role, symptoms, duration_value, duration_unit,
                    age_range, sex,
                    temperature, pulse_rate, blood_pressure, respiratory_rate,
                    confirmed_signs,
                    triage_level, condition, confidence, treatment, next_steps, red_flags,
                    guidance_used, final_action, issue_tags,
                    referral_urgency, referral_destination, referral_reasons, referral_notes,
                    latitude, longitude, location_accuracy_m, district,
                    device_model, device_manufacturer, chipset, android_api, android_version,
                    total_ram_mb, max_cpu_freq_mhz, cpu_count, native_variant, perf_cores,
                    recommended_n_batch, app_version,
                    inference_ms
                ) VALUES (
                    $1, $2, now(), $3,
                    $4, $5, $6, $7, $8,
                    $9, $10,
                    $11, $12, $13, $14,
                    $15,
                    $16, $17, $18, $19, $20, $21,
                    $22, $23, $24,
                    $25, $26, $27, $28,
                    $29, $30, $31, $32,
                    $33, $34, $35, $36, $37,
                    $38, $39, $40, $41, $42,
                    $43, $44,
                    $45
                )
                ON CONFLICT (id) DO UPDATE SET
                    -- Client is offline-first, so re-POSTs always carry the
                    -- canonical state. Update every mutable field, not just
                    -- the confirmation columns — otherwise an edit to
                    -- symptoms or guidance on the device never propagates.
                    -- submitted_at / client_timestamp_ms are immutable.
                    role = EXCLUDED.role,
                    custom_role = EXCLUDED.custom_role,
                    symptoms = EXCLUDED.symptoms,
                    duration_value = EXCLUDED.duration_value,
                    duration_unit = EXCLUDED.duration_unit,
                    age_range = EXCLUDED.age_range,
                    sex = EXCLUDED.sex,
                    temperature = EXCLUDED.temperature,
                    pulse_rate = EXCLUDED.pulse_rate,
                    blood_pressure = EXCLUDED.blood_pressure,
                    respiratory_rate = EXCLUDED.respiratory_rate,
                    confirmed_signs = EXCLUDED.confirmed_signs,
                    triage_level = EXCLUDED.triage_level,
                    condition = EXCLUDED.condition,
                    confidence = EXCLUDED.confidence,
                    treatment = EXCLUDED.treatment,
                    next_steps = EXCLUDED.next_steps,
                    red_flags = EXCLUDED.red_flags,
                    guidance_used = EXCLUDED.guidance_used,
                    final_action = EXCLUDED.final_action,
                    issue_tags = EXCLUDED.issue_tags,
                    referral_urgency = EXCLUDED.referral_urgency,
                    referral_destination = EXCLUDED.referral_destination,
                    referral_reasons = EXCLUDED.referral_reasons,
                    referral_notes = EXCLUDED.referral_notes,
                    latitude = EXCLUDED.latitude,
                    longitude = EXCLUDED.longitude,
                    location_accuracy_m = EXCLUDED.location_accuracy_m,
                    district = EXCLUDED.district,
                    device_model = EXCLUDED.device_model,
                    device_manufacturer = EXCLUDED.device_manufacturer,
                    chipset = EXCLUDED.chipset,
                    android_api = EXCLUDED.android_api,
                    android_version = EXCLUDED.android_version,
                    total_ram_mb = EXCLUDED.total_ram_mb,
                    max_cpu_freq_mhz = EXCLUDED.max_cpu_freq_mhz,
                    cpu_count = EXCLUDED.cpu_count,
                    native_variant = EXCLUDED.native_variant,
                    perf_cores = EXCLUDED.perf_cores,
                    recommended_n_batch = EXCLUDED.recommended_n_batch,
                    app_version = EXCLUDED.app_version,
                    -- Keep the original measurement when the client re-POSTs
                    -- after a confirmation/referral edit — those round-trips
                    -- don't re-run inference. Only overwrite if we actually
                    -- received a new value.
                    inference_ms = COALESCE(EXCLUDED.inference_ms, tier_1_identified.assessments.inference_ms),
                    -- Only re-queue NER when the free-text fields actually
                    -- changed. An OS version bump or a new confirmation on an
                    -- untouched symptoms field shouldn't thrash Presidio.
                    ner_scanned_at = CASE
                        WHEN EXCLUDED.symptoms IS DISTINCT FROM tier_1_identified.assessments.symptoms
                          OR EXCLUDED.condition IS DISTINCT FROM tier_1_identified.assessments.condition
                          OR EXCLUDED.referral_notes IS DISTINCT FROM tier_1_identified.assessments.referral_notes
                        THEN NULL
                        ELSE tier_1_identified.assessments.ner_scanned_at
                    END,
                    ner_flagged = CASE
                        WHEN EXCLUDED.symptoms IS DISTINCT FROM tier_1_identified.assessments.symptoms
                          OR EXCLUDED.condition IS DISTINCT FROM tier_1_identified.assessments.condition
                          OR EXCLUDED.referral_notes IS DISTINCT FROM tier_1_identified.assessments.referral_notes
                        THEN FALSE
                        ELSE tier_1_identified.assessments.ner_flagged
                    END
                """,
                str(payload.id), device_id, payload.timestamp,
                payload.role, payload.custom_role, payload.symptoms,
                payload.duration_value, payload.duration_unit,
                payload.age, payload.sex,
                payload.vital_signs.temperature, payload.vital_signs.pulse_rate,
                payload.vital_signs.blood_pressure, payload.vital_signs.respiratory_rate,
                payload.confirmed_signs,
                payload.guidance.triage_level, payload.guidance.condition,
                payload.guidance.confidence,
                payload.guidance.treatment, payload.guidance.next_steps,
                payload.guidance.red_flags,
                payload.clinician_confirmation.guidance_used if payload.clinician_confirmation else None,
                payload.clinician_confirmation.final_action if payload.clinician_confirmation else None,
                payload.clinician_confirmation.issue_tags if payload.clinician_confirmation else [],
                payload.referral.urgency if payload.referral else None,
                payload.referral.destination if payload.referral else None,
                payload.referral.reasons if payload.referral else [],
                payload.referral.notes if payload.referral else None,
                payload.location.latitude, payload.location.longitude,
                payload.location.accuracy_meters, payload.location.district,
                payload.device.device_model, payload.device.device_manufacturer,
                payload.device.chipset, payload.device.android_api,
                payload.device.android_version,
                payload.device.total_ram_mb, payload.device.max_cpu_freq_mhz,
                payload.device.cpu_count, payload.device.native_variant,
                payload.device.perf_cores,
                payload.device.recommended_n_batch, payload.device.app_version,
                payload.inference_ms,
            )
            await conn.execute(
                """
                INSERT INTO tier_1_identified.device_registry (
                    device_token, first_seen, last_seen,
                    device_model, chipset, android_api, app_version
                ) VALUES ($1, now(), now(), $2, $3, $4, $5)
                ON CONFLICT (device_token) DO UPDATE SET
                    last_seen = now(),
                    device_model = EXCLUDED.device_model,
                    chipset = EXCLUDED.chipset,
                    android_api = EXCLUDED.android_api,
                    app_version = EXCLUDED.app_version
                """,
                device_id,
                payload.device.device_model, payload.device.chipset,
                payload.device.android_api, payload.device.app_version,
            )
    await audit(device_id, "assessment_upsert", str(payload.id), request.state.client_ip)
    return {"status": "ok", "id": str(payload.id)}


@app.post("/paused_consultations")
async def post_paused(
    payload: PausedPayload,
    request: Request,
    authorization: str | None = Header(None),
) -> dict[str, str]:
    device_id = await require_device(request, authorization)
    if pool is None:
        raise HTTPException(503, "Service unavailable")
    async with pool.acquire() as conn:
        await conn.execute(
            """
            INSERT INTO tier_1_identified.paused_consultations (
                id, device_token, role, symptoms, age_range, pause_reason, note
            ) VALUES ($1, $2, $3, $4, $5, $6, $7)
            ON CONFLICT (id) DO UPDATE SET
                role = EXCLUDED.role,
                symptoms = EXCLUDED.symptoms,
                age_range = EXCLUDED.age_range,
                pause_reason = EXCLUDED.pause_reason,
                note = EXCLUDED.note
            """,
            str(payload.id), device_id, payload.role, payload.symptoms,
            payload.age, payload.pause_reason, payload.note,
        )
    await audit(device_id, "paused_upsert", str(payload.id), request.state.client_ip)
    return {"status": "ok", "id": str(payload.id)}


@app.post("/paused_consultations/delete")
async def delete_paused(
    payload: PausedDeletePayload,
    request: Request,
    authorization: str | None = Header(None),
) -> dict[str, str]:
    device_id = await require_device(request, authorization)
    if pool is None:
        raise HTTPException(503, "Service unavailable")
    async with pool.acquire() as conn:
        # Only the owning device can delete its own paused records.
        result = await conn.execute(
            """
            DELETE FROM tier_1_identified.paused_consultations
            WHERE id = $1 AND device_token = $2
            """,
            str(payload.id), device_id,
        )
    if result.endswith(" 0"):
        log.info("delete_paused: no-op for device=%s id=%s", device_id, payload.id)
    await audit(device_id, "paused_delete", str(payload.id), request.state.client_ip)
    return {"status": "ok", "id": str(payload.id)}


@app.post("/device_diagnostics")
async def post_diagnostics(
    payload: DiagnosticsPayload,
    request: Request,
    authorization: str | None = Header(None),
) -> dict[str, str]:
    device_id = await require_device(request, authorization)
    if pool is None:
        raise HTTPException(503, "Service unavailable")
    async with pool.acquire() as conn:
        await conn.execute(
            """
            INSERT INTO tier_1_identified.device_registry (
                device_token, first_seen, last_seen,
                device_model, chipset, android_api, app_version
            ) VALUES ($1, now(), now(), $2, $3, $4, $5)
            ON CONFLICT (device_token) DO UPDATE SET
                last_seen = now(),
                device_model = EXCLUDED.device_model,
                chipset = EXCLUDED.chipset,
                android_api = EXCLUDED.android_api,
                app_version = EXCLUDED.app_version
            """,
            device_id, payload.device_model, payload.chipset,
            payload.android_api, payload.app_version,
        )
    # Diagnostics are high-volume — skip per-call audit rows to keep the log readable.
    return {"status": "ok"}


@app.post("/delete_my_data")
async def delete_my_data(
    request: Request,
    authorization: str | None = Header(None),
) -> dict[str, Any]:
    """DPPA §7 — right to erasure.

    Deletes every tier_1 record belonging to the calling device and revokes
    the device token so no further writes accept this identity. tier_2
    pseudonymised records are retained: they are no longer linkable to the
    person or device, and the dashboards depend on them.
    """
    device_id = await require_device(request, authorization)
    if pool is None:
        raise HTTPException(503, "Service unavailable")
    async with pool.acquire() as conn:
        async with conn.transaction():
            assess = await conn.execute(
                "DELETE FROM tier_1_identified.assessments WHERE device_token = $1",
                device_id,
            )
            paused = await conn.execute(
                "DELETE FROM tier_1_identified.paused_consultations WHERE device_token = $1",
                device_id,
            )
            await conn.execute(
                """
                UPDATE tier_1_identified.device_registry
                SET revoked_at = now(), revoke_reason = 'user_requested_erasure'
                WHERE device_token = $1 AND revoked_at IS NULL
                """,
                device_id,
            )
    # `assess` / `paused` look like "DELETE 3" — split out the count.
    def _rowcount(tag: str) -> int:
        return int(tag.rsplit(" ", 1)[-1]) if tag and tag[-1].isdigit() else 0
    counts = {"assessments": _rowcount(assess), "paused": _rowcount(paused)}
    await audit(device_id, "delete_my_data", None, request.state.client_ip, meta=counts)
    log.info("delete_my_data: device=%s counts=%s", device_id, counts)
    return {"status": "ok", **counts}


@app.get("/health")
async def health() -> dict[str, Any]:
    if pool is None:
        raise HTTPException(503, "Service unavailable")
    async with pool.acquire() as conn:
        await conn.fetchval("SELECT 1")
    return {"status": "ok"}
