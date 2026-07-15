# Comprehensive Device Benchmark Sweep — Ease Health 1.0.18

**Driven by:** the DGX Spark agent — **Opus supervisor + Sonnet workers**.
**Supersedes:** `DEVICE_BENCHMARK_REPORT.md` (v1.0.15, text-only). This sweep re-measures the viable devices on **1.0.18** (which added the vision fix, eager-encode overlap, optional 448 px fast-image mode, prewarm, spec-decode, f16 KV) and **adds vision + MedASR (cold & hot), full memory profiling, sustained-battery thermals, and an i8mm reference device.**
**Goal:** a procurement- and engineering-grade report that (a) shows the 1.0.15→1.0.18 speedup per device, (b) characterises all three on-device engines (LLM / vision / ASR) cold and hot, (c) proves the memory budget, and (d) answers the old report's open follow-ups.

---

## 0. What's new vs the 1.0.15 sweep (so we measure the right things)

| Change in 1.0.18 | New thing to measure |
|---|---|
| **Prewarm** (bg model load + prefix prefill at symptom-screen entry) | cold-with-prewarm vs cold-without; how much it hides |
| **Spec-decode** (ngram-map-k4v) ON for text | decode tok/s + acceptance rate |
| **f16 KV** (≥6 GB) | already in 1.0.16; confirm |
| **Vision fixed + eager-encode overlap** | vision cold/hot, overlap on/off, encoder load, encode ms |
| **Optional 448 px fast-image mode** | 448 vs 896 encode + total (setting off by default) |
| **MedASR sparse mel** | ASR cold/hot, mel + inference, RTF |

Baseline for the regression table = the 1.0.15 numbers in `DEVICE_BENCHMARK_REPORT.md` §4.

---

## 1. Device matrix (viable-only + one i8mm reference)

The devices the old report proved **viable** (all Helio G99 / dotprod), plus one deliberate failed-device retest.

| # | Device | SoC | RAM | SIMD | Role |
|---|---|---|---|---|---|
| 1 | **Samsung Galaxy A17** (SM-A175F) | Helio G99 (2×A76+6×A55) | 7.6 GB | dotprod | production reference (73% of fleet). Run **all connected A17 units** (RRGL20BH6ZZ, RRGL208YREL) for inter-unit variance |
| 2 | **Infinix HOT 60 Pro** (X6885) | Helio G99 | 7.9 GB | dotprod | viable alt — 2nd vendor, same SoC |
| 3 | **TECNO CAMON 50** (CN5) | Helio G99 | 7.9 GB | dotprod | viable alt — 3rd vendor, same SoC |
| 4 | **Redmi 15C** (7.5 GB, HyperOS) | Helio G85-class | 7.5 GB | dotprod | **reboot-retest only** — the old report's untested follow-up: does a fresh boot free enough RAM to pass the gate? (Low priority; if it still blocks, one clean datapoint and move on.) |

- **No i8mm device this round** — the S24 Ultra isn't connected, so the whole fleet under test is dotprod-only (no i8mm). **The old report's #1 follow-up (quantify the ~3.5× i8mm prefill speedup) stays OPEN** — flag it in the report and revisit when a flagship is available.
- **Do NOT re-run the 4 GB class** — deterministic OOM, unchanged (1.0.18 has the same weight working set). The Redmi reboot-retest is the only failed-device probe.

---

## 2. Metric taxonomy — what we capture

### 2A. Text (MedGemma) — per device
- **Install time** (3.28 GB APK) and on-device install size / disk headroom.
- **Model load:** first-ever (cold page cache) **and** subsequent (warm page cache) — the old report saw 12 s → 7–8 s.
- **Prewarm:** background prefix-prefill duration (~1071 tokens); did it finish before Generate?
- **Cold assessment — two flavours:**
  - *cold, prewarm-hidden* (realistic: enter symptoms ~40 s, then Generate) — tail-prefill + decode.
  - *cold, no-prewarm* (worst case: Generate immediately) — full prefill + decode.
- **Hot assessment:** 2nd+ patient, prefix KV cached.
- **Breakdown:** prefill ms, decode ms, **decode tok/s**, **TTFT** (measure directly from logcat markers if present; else derive), spec-decode **acceptance %**.
- **Output:** tokens, chars, triage label, **byte-identical determinism** across runs & devices (seed-42 dist ⇒ identical input → identical output).

### 2B. Vision (MedGemma + SigLIP mmproj) — per device
- **Vision encoder (mmproj) load** time (cold).
- **Image encode** (SigLIP ViT): **cold** (first image, encoder cold) **and hot** (subsequent image — encoder warm but each new image re-encodes; no cross-image KV reuse).
- **Overlap A/B (the headline):** perceived **Generate→result WITH overlap** (attach photo → let bg encode finish → Generate) vs **WITHOUT** (attach → immediate Generate). This is the 1.0.18 win.
- **Tail-prefill** (overlap fast path), **decode** ms + tok/s.
- **Fast-image mode 448 px** variant: encode + total vs 896 px (Settings → Fast image analysis ON). Capture output for later clinical concordance (do **not** judge quality here).
- **Totals:** vision cold, vision hot, both with/without overlap.

### 2C. MedASR (ONNX) — per device
- **ASR model load** (402 MB ONNX): cold + subsequent.
- **Mel preprocessing** (sparse mel) ms; **inference** ms — **cold and hot**.
- **RTF (real-time factor)** = processing_time ÷ audio_duration (< 1 = faster than real-time). Use a **fixed-duration** clip so RTF is comparable.
- **Transcription** text + consistency across runs (accuracy/WER = nice-to-have, needs clean input — see §7).

### 2D. Memory — the comprehensive profile (the procurement headline)
- **Per-phase PSS/RSS/USS + system MemAvailable + zram/swap** at each step:
  `idle → app launched → LLM loaded → prewarmed → vision encoder loaded → ASR loaded → peak during each inference`.
- **Memory budget table:** how many MB does *each* engine add (LLM working set ~2.5 GB, +vision mmproj/embeddings, +ASR runtime/model)?
- **Concurrent worst case:** one session doing **symptoms + image + voice** (all three engines touched) → **peak PSS + MemAvailable floor**. Does it stay clear of OOM on 8 GB? This is the real field flow and the true RAM ceiling.
- **LMK / crash / `onTrimMemory`** events from logcat.

### 2E. Thermals, power, resilience (cross-cutting)
- **Thermals:** battery temp start/end, thermal status, per-core CPU freq — sampled 2 s.
- **Sustained-battery run (fills the old gap):** 8–10 back-to-back assessments **unplugged** → throttle onset, tok/s degradation over the run, **battery % drain**, **assessments-per-charge** estimate, **energy per assessment** (mAh or %/inference) split by engine.
- **Screen-off test:** does 1.0.18 hold a wakelock, or does screen-off still ~2× the time (old report §6.3)?
- **Failure resilience:** on any OOM/crash, does 1.0.18 fail *gracefully* (retry/message) or silent-vanish like 1.0.15?

---

## 3. Standardized inputs (identical on every device, every run)

- **Symptoms text (continuity with old report):** `"Child aged 3 with fever for 2 days, vomiting…"` (same 1,113-token full prompt). Add a **2nd fixed vignette** for a different severity if we want output-consistency coverage.
- **Test image:** one fixed clinical-style JPEG (reuse `vision_test.jpg`, 1024×1024; or a curated skin/wound image). Same bytes on every device so the encode is comparable and the overlap hash matches.
- **Test audio:** `benchmark-assets/test_asr_12s.wav` — a fixed **12.5 s** English clinical-description clip, **16 kHz mono 16-bit PCM** (synthetic TTS; reference transcript in `benchmark-assets/test_asr_reference.txt`). Same file everywhere so RTF is comparable. Delivery via loopback (see §7). *(Synthetic voice → use for timing/RTF; real-speech WER is a fast-follow.)*
- **Age/Sex:** fixed (e.g. 34 / Male) to satisfy the required fields.

---

## 4. Methodology

- **Cold** = force-stop → fresh launch → first assessment of the session (KV empty, model cold-loaded). **Hot/warm** = subsequent assessment, same session (model resident, prefix cached).
- **Reps:** text ≥3 sessions/device (cold) + ≥2 hot; vision ≥3 cold + ≥2 hot; ASR ≥3 cold + ≥3 hot. Sustained run: 1× per device (10 assessments).
- **Determinism:** the app samples at temp 0.5 with a **fixed seed (42)** → identical input yields **byte-identical output**; use that for clean timing A/B and cross-device identity checks. (No need to force greedy.)
- **Thermal fairness:** record battery temp at the start of every run; **cool-down** (or note temp) between sessions; the primary matrix is **USB-powered, screen-on**; the sustained + screen-off variants are separate, labelled runs.
- **Timing source:** the app's own logcat markers (`Loading model from` → `MedGemma model loaded` → prefill/`Running … inference` → `VISION-TIMING …` → `Inference complete` → `Saved assessment`). Native `VISION-TIMING` already splits tokenize/eval/decode. Device wall-clocks drift — use **within-device log deltas** only.
- **Resource sampling:** host-side every 2 s — MemAvailable, app PSS (smaps_rollup, dumpsys fallback), per-core freq, battery temp/level, thermal status; plus LMK/crash from logcat.
- **Contamination guard:** reject + re-run any session hit by screen-lock mid-gen, USB re-enumeration truncation, or a thermal outlier (the old report's Infinix 224 s screen-off run is the cautionary example).

---

## 5. The run matrix (cells the agent fills)

Per device, per scenario, capture the full metric set of §2 + resource CSV:

| Modality | Scenario | Reps |
|---|---|---|
| Text | cold (prewarm-hidden) | 3 |
| Text | cold (no-prewarm) | 2 |
| Text | hot | 2 |
| Vision 896 | cold, overlap ON | 3 |
| Vision 896 | cold, overlap OFF (immediate Generate) | 2 |
| Vision 896 | hot | 2 |
| Vision 448 (fast mode) | cold, overlap ON | 2 |
| MedASR | cold | 3 |
| MedASR | hot | 3 |
| Memory | full concurrent flow (symptoms+image+voice) | 2 |
| Sustained | 10 back-to-back, **unplugged** | 1 |
| Screen-off | text cold, screen off | 1 |

≈ 24 runs/device × 4 devices ≈ ~**100 runs** → this is why it needs the Sonnet-worker fan-out.

---

## 6. Agent architecture — Opus supervisor + Sonnet workers

**Opus supervisor** (the resident DGX agent):
- Owns this spec; enumerates connected viable devices (`adb devices`); builds the per-device run matrix.
- **Dispatches one Sonnet worker per device** (they run in parallel across devices; runs within a device are serial).
- **Validates** each returned run (sane deltas, no contamination, output identity where expected); orders **re-runs** on anomalies.
- Does the **statistics** (mean ± 95% CI), the **1.0.15→1.0.18 regression table**, the **memory-budget** synthesis, and **writes the final report**.
- Handles judgment: outlier exclusion, thermal interpretation, viability calls, and the adb-over-TCP reconnection resilience.

**Sonnet workers** (cheap, high-volume, one per device):
- Execute the mechanical loop per run: `adb install -r` (once) → drive the UI (launch, enter symptoms, attach image, trigger voice, set Fast-image toggle, Generate) → run the 2 s host sampler → capture logcat → parse markers → **return one structured JSON per run** (schema §8).
- Never make analysis decisions; just drive + capture + report raw. Flag (don't fix) anomalies for the supervisor.

Implementation on the Spark: the Opus agent uses the Agent tool with `model: sonnet` to spawn the per-device workers, collects their JSON, and synthesizes. Keep Opus tokens for planning + analysis + report; keep the ~100 mechanical runs on Sonnet.

---

## 7. Mechanism + the two tricky bits

- **APK:** the shipped **1.0.18** APK is already on the Spark (`~/easehealth-1.0.18.apk`) and on Drive. Install it as-is (no rebuild needed — good, because full Gradle/APK builds are still blocked on the DGX per the env notes). UI-drive it exactly like the 1.0.15 report did.
- **Transport:** phones plug into the DGX; use **adb over TCP** (`adb tcpip 5555; adb connect <ip>:5555`) — USB re-enumerates mid-transfer and the DGX's default route *is* a tethered phone (do **not** disable USB tethering). Long runs must be **detached** (nohup → logfile → poll), never streamed over a live adb pipe.
- **Tricky bit 1 — ASR input.** The app records from the mic; there's no file-import path. Two options: **(a)** audio **loopback** — play the fixed WAV through a speaker at the phone (gives valid *timing/RTF* since ASR compute depends on duration, not content; transcription text will vary → timing-only). **(b)** a tiny **debug hook** feeding a bundled WAV straight to `MedAsrEngine` (gives clean timing **and** WER) — needs the APK-build unblock (`libc6-amd64-cross` + `QEMU_LD_PREFIX`, per the env notes). **Default: (a) for timing now; (b) later for accuracy.**
- **Tricky bit 2 — vision "cold" reproducibility.** Each cold vision run needs a genuinely cold encoder → force-stop between. For the overlap-OFF measurement, tap Generate within ~1 s of attaching (before the bg encode finishes) so the encode isn't hidden.

---

## 8. Results schema + final report

**Per-run JSON** (worker → supervisor):
```json
{ "device":"SM-A175F/RRGL20BH6ZZ", "app_version":"1.0.18", "model_md5":"...",
  "modality":"vision|text|asr", "scenario":"cold_overlap_on", "rep":1,
  "timings_ms":{"model_load":..,"prewarm":..,"encoder_load":..,"encode":..,
                "tail_prefill":..,"prefill":..,"decode":..,"total":..,"ttft":..},
  "throughput":{"decode_tok_s":..,"prefill_tok_s":..,"spec_accept_pct":..,"asr_rtf":..},
  "memory_mb":{"idle_avail":..,"peak_pss":..,"peak_rss":..,"avail_floor":..,"swap":..},
  "thermal":{"batt_temp_start":..,"batt_temp_end":..,"thermal_status_max":..,"throttled":false},
  "power":{"batt_pct_delta":..},
  "output":{"tokens":..,"chars":..,"triage":"..","hash":"..","deterministic_vs_ref":true},
  "flags":["screen_locked?","usb_drop?","lmk?"], "verdict":"clean|rerun" }
```

**Final report** (`DEVICE_BENCHMARK_REPORT_1.0.18.md`), mirroring the old structure plus:
1. Exec summary + **1.0.15 → 1.0.18 regression table** per device per metric (the speedup, front and centre).
2. Per-engine tables: **text / vision / ASR × cold / hot** (mean ± 95% CI).
3. **Memory budget** table (per-engine footprint + concurrent peak + headroom on 8 GB).
4. **i8mm finding** (S24 vs G99 — prefill/encode speedup).
5. **Sustained-battery + thermal + energy-per-assessment + assessments-per-charge**.
6. **Screen-off** behaviour under 1.0.18.
7. Cross-cutting findings + updated procurement recommendation.
8. Appendix: raw per-run table + artifacts under `devices/<serial>/`.

---

## 9. Nice-to-haves (explicitly, so nothing's lost)

1. **i8mm device — DEFERRED this round** (no S24/flagship connected; whole fleet is dotprod-only). Keep the ~3.5× i8mm prefill question open; run it when a flagship is available.
2. **Sustained-battery thermals + energy/assessment + assessments-per-charge** — the old report's biggest gap (it only did single, plugged runs).
3. **Concurrent-engine peak memory** — LLM+vision+ASR in one flow: the true RAM ceiling; decides whether all three can coexist on 8 GB.
4. **Overlap A/B** — the perceived-latency win, measured in the real "attach-then-type" workflow.
5. **Regression/lossless check** — 1.0.18 output byte-identical to 1.0.15 for the same input (prove the speedups didn't change clinical text) across devices.
6. **Cross-device output identity** — same input → same bytes on every device (extend the old text finding to vision + ASR).
7. **Screen-off / wakelock** — confirm the 1.0.18 behaviour (old report: screen-off ~2× slower).
8. **Prewarm effectiveness** — how much of the cold start the bg prefill actually hides given realistic data-entry time.
9. **Cold-cache vs page-cache-warm model load** — first-ever vs subsequent (old report: 12 s → 7–8 s).
10. **Fast-image (448) capture-for-concordance** — record the 448 outputs alongside 896 so the later clinical gate has paired data (reusable for the E14/E16 quality gates).
11. **Failure-mode UX under 1.0.18** — does it still silent-vanish on OOM, or recover?
12. **Reboot-then-retest the Redmi 15C** — the old untested follow-up (does a clean boot free enough RAM?).
13. **Install + disk footprint** per device (3.28 GB APK) — real deployment friction.

---

## 10. Operational landmines (from the DGX agent's own env notes — heed these)
- **adb over TCP**, not USB (USB re-enumerates → truncated pulls); **don't kill USB tethering** (it's the DGX's uplink).
- **Detached benchmarks** (nohup → logfile → poll); never stream over a live adb pipe.
- Wall-clocks drift up to ~50 days → **within-device log deltas only**.
- **Wakelock/stay-on** per device: `svc power stayon true` (+ TECNO needs `settings put global stay_on_while_plugged_in 7`); the sustained/screen-off runs deliberately drop this.

---

## 11. Decisions — RESOLVED (2026-07-15)
1. **Device set:** all connected **A17 unit(s) + Infinix HOT 60 Pro + TECNO Camon 50 + Redmi 15C (reboot-retest)**. No S24 → **no i8mm device this round** (i8mm follow-up stays open).
2. **ASR:** **timing/RTF now** via loopback; WER is a fast-follow once the APK build is unblocked.
3. **Test audio:** generated → `benchmark-assets/test_asr_12s.wav` (12.5 s, 16 kHz mono) + reference transcript.
4. *(Optional, not blocking)* a 2nd severity vignette for output-consistency coverage — add if the supervisor wants it.

**Ready to hand off.** Sweep runs on the shipped 1.0.18 APK (`~/easehealth-1.0.18.apk` on the Spark) over adb-TCP; Opus supervisor dispatches Sonnet workers per connected device.
