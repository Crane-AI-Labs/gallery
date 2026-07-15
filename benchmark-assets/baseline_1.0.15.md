# 1.0.15 baseline (for the 1.0.18 regression table)

Distilled from the v1.0.15 procurement report (DEVICE_BENCHMARK_REPORT.md, 2026-07-09).
**TEXT ONLY** — 1.0.15 had no vision/ASR benchmarking, so **vision and MedASR have NO 1.0.15
baseline**; they are net-new in the 1.0.18 sweep (report them as new, not as a delta).
Common: MedGemma-4B Q4_0, n_ctx 2048, prompt = 1,113 tokens; cold output 155 tok / 674 chars;
warm output ~103 tok / 449 chars. All three viable devices are Helio G99 / dotprod / ~8 GB.

| Device (G99, ~8GB) | Model load | Cold assessment | Warm assessment | TTFT (derived) | Decode |
|---|---|---|---|---|---|
| Galaxy A17 (SM-A175F) | 7.1 s | **149.6 s** [125,174] | **50.8 s** | ~74 s | ~2.0 tok/s |
| Infinix HOT 60 Pro (X6885) | 7.8 s | **128.8 s** [106,152] | **46.4 s** | ~60 s | ~2.2 tok/s |
| TECNO Camon 50 (CN5) | 9.1 s | **137.6 s** [130,145] | **51.4 s** | ~61 s | ~2.0 tok/s |

Memory (peak app PSS / system MemAvailable floor): A17 ~4.96 GB / ~2.9 GB · Infinix ~4.5 GB /
~1.6 GB · Camon ~4.3 GB. Thermals: 30–36 °C, status 0, no sustained throttle at single-assessment
duty cycle (sustained-battery NOT tested in 1.0.15 — new in this sweep).

Failed (unchanged, do not re-run except Redmi reboot-retest): every 4 GB device OOM-killed
(measured working set ~2.5–2.7 GB); Redmi 15C gate-blocked (HyperOS ~5.8 GB resident).

**Regression note:** 1.0.15 cold = full prefill (no prewarm/spec/f16). Compare against 1.0.18
*cold no-prewarm* for the pure kernel/KV delta, and *cold prewarm-hidden* for the shipped UX delta.
