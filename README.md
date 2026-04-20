# Ease Health

An Android app that helps frontline health workers in rural Uganda triage
patients with an on-device AI model (MedGemma 4B via llama.cpp), plus a
Uganda-hosted backend for pseudonymised clinical analytics.

Built for offline-first clinical use. Forked from the
[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) as a
starting point for the on-device LLM plumbing; most of the product surface
has since been rewritten.

## What it does

1. A nurse, midwife, or medical officer opens the app on a Galaxy A-series
   or similar budget phone in a rural health centre.
2. They enter the patient's symptoms, age, sex, vitals, and any confirmed
   danger signs. Optionally they attach a photo or speak a voice note
   (transcribed locally by MedASR).
3. **MedGemma runs on-device** and returns a structured assessment —
   possible condition, suggested treatment, next steps, red flags, and a
   forced triage level: Emergency referral · Urgent clinic visit ·
   Routine care · Home care.
4. The clinician makes the final call, records what they did, and the
   assessment is saved locally (SQLCipher-encrypted Room DB).
5. When the phone has connectivity, the assessment is redacted of
   structured PII on-device and synced to the **Ease Health backend in
   Kampala** (Afriqloud VM) over a pinned TLS connection. A second NER
   pass on the server catches names before promoting the record into the
   pseudonymised analytics layer.

The app works fully offline. Sync is a background concern — the clinician
never waits on it.

## Architecture at a glance

```
Phone                                         Kampala VM
───────────────────────────                   ────────────────────────────
com.craneailabs.easehealth                    41.220.3.234
                                              (easehealth.afriqloud.cloud
                                               once DNS resolves)

 MedGemma 4B (Q4_0, llama.cpp)                nginx (rate limit + TLS)
 MedASR (ONNX)                                  │
      │                                         ├── /api/*  ──► FastAPI
 PiiRedactor (regex)                            │               ├── tier_1_identified
      │                                         │               │   (PII — short-lived)
 Room (SQLCipher)                               │               └── audit_log
      │                                         │
 UgandaApi (HTTPS + SPKI pin) ──────────────────┘
                                                │
                                                ▼
                                        ETL (Presidio NER, every 15 min)
                                                │
                                                ▼
                                        tier_2_analytics
                                        (pseudonymised, served via Metabase)
```

Two-tier data model:

- **tier_1_identified** — device token, precise timestamp, rough location,
  full free text. Short retention (90 days). Behind `psql` + audit logging.
- **tier_2_analytics** — HMAC pseudonym, week + district bucket, age band,
  NER-redacted text. Long retention (2 years). What Metabase sees.

Privacy layering:

1. Ugandan-specific regex redaction on-device before upload (phones,
   NINs, passports, TINs, NSSF, ISO/DMY dates, emails).
2. Microsoft Presidio NER on the server catches names that slip through.
3. District-level location aggregation at promotion time.
4. Retention sweep daily at 03:15 UTC (`etl/retention.py`).

Compliance:

- **DPPA 2019 §9** — informed consent (first-launch `ConsentScreen`, v2).
- **DPPA 2019 §18** — minimum-necessary retention (retention worker).
- **DPPA 2019 §19** — data sovereignty (Kampala VM, no cross-border copy).
- **DPPA 2019 §27** — automated-decision disclosure (consent copy names the
  model, forced triage taxonomy, and clinician-makes-final-call principle).
- **DPPA 2019 §30** — audit trail on every tier_1 write.

## Repo layout

```
Android/                   Kotlin + Jetpack Compose. Build with ./gradlew :app:assembleDebug.
  src/app/.../healthdemo/  The clinical product surface.
  src/app/.../llm/         llama.cpp JNI + loader.

uganda-backend/            FastAPI + Postgres, lives on the Kampala VM.
  app/main.py              Ingestion API.
  etl/                     Presidio promotion + retention workers.
  migrations/              Numbered, idempotent, apply in order.
  schema.sql               Full schema (two tiers, audit_log, device_registry).
  nginx-easehealth.conf    TLS, rate limits, HSTS, basic-auth for /metabase/.
  tls/                     Public cert + OpenSSL config (keys gitignored).
  scripts/revoke-device.sh Ops runbook — revoke a stolen device token.
  deploy-hardening.sh      Idempotent one-shot deploy.
  DEVELOPERS.md            How to get SSH, DB, and Metabase access.
  .secrets.env.example     Template — fill in and rename to .secrets.env.

app-wireframes/            UX source of truth.
model_allowlists/          Which GGUF / ONNX builds the app will load.
```

## Quick start

### Build + install the app

```bash
cd Android/src
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is ~3.4 GB — models are bundled for offline-first sideload
distribution (clinics typically side-load from a USB stick).

First launch shows a DPPA-compliant consent screen, then the role
selector. Once accepted, that phone mints a device token with the Kampala
API and starts syncing.

### Run the backend

The backend is already deployed. If you need to re-provision a VM:

```bash
cd uganda-backend
cp .secrets.env.example .secrets.env  # fill in real values
./deploy-hardening.sh
```

See [`uganda-backend/DEVELOPERS.md`](uganda-backend/DEVELOPERS.md) for
access (SSH, Metabase, DB), the revocation runbook, and how secrets flow.

### Do UI-only work

The app runs against a mock guidance engine if the backend isn't reachable —
so a UI-focused developer can iterate in the emulator without any
backend setup. Everything under `Android/` can be cloned and built
independently; touching `uganda-backend/` is only necessary when you're
editing the sync contract.

## Documentation

- [`uganda-backend/DEVELOPERS.md`](uganda-backend/DEVELOPERS.md) — backend
  access, Metabase setup, DB tunnels, scripts, and debug runbook.
- [`DEVELOPMENT.md`](DEVELOPMENT.md) — local Android build notes.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — fork etiquette + PR rules.
- [`Bug_Reporting_Guide.md`](Bug_Reporting_Guide.md) — how to file clinically
  actionable bug reports.

## Status

Deployed to a pilot clinic in Uganda. Sideload-only (no Play Store
distribution — the bundled model is larger than Play Asset Delivery's 1 GB
cap, and the app is currently audited for DPPA compliance rather than
open-market release).

Open tracks: Luganda / Runyankole / Luo consent translation; Play Asset
Delivery if Play Store distribution ever becomes a goal.

## License

Apache License 2.0 — inherited from the upstream Google AI Edge Gallery.
See [LICENSE](LICENSE).
