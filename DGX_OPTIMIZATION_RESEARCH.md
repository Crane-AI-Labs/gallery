# On-Device Inference Optimization — Autonomous Research Backlog

**Owner:** DGX Spark agent (looped) · **Created:** 2026-07-14 · **Target device:** Galaxy A17 (Helio G99)
**Goal:** squeeze more text + image inference speed out of the shipped stack (Ease Health 1.0.18) **without degrading clinical output**, methodically, one hypothesis at a time.

> Work the cards **top to bottom** (low effort → hard). This is an *auto-research* loop in the Karpathy sense: reproduce a baseline, change **one** variable, gate on correctness, measure the real thing, **log everything (including failures)**, keep or kill, move on. Do not batch changes. Do not trust a speedup you haven't correctness-checked.

---

## 0. THE LOOP (do this every iteration)

1. **Pick** the top card whose `status: OPEN` (respect `depends-on`).
2. **Baseline** — reproduce the current number for that path on the A17 (or the harness), same inputs, greedy (topK=1) for determinism. Record it.
3. **Implement** the single change described in the card.
4. **Correctness gate (BLOCKING).** Greedy output must be **identical** to baseline for a lossless card, or within the card's stated tolerance. If it changes and the card claims lossless → the change is wrong; fix or kill. Never accept a perf win that fails this gate.
5. **Measure** on the A17 (authoritative) — prefill tok/s, decode tok/s, encode ms, end-to-end. Interleave off/on runs for thermal fairness.
6. **Log** — append a dated entry to §6 Running Log with: baseline, result, delta %, correctness verdict, keep/kill, and *why*. Be brutally honest; a killed hypothesis is a real result.
7. **Update** §7 Leaderboard if it's a keeper.
8. **Checkpoint notes** every ~2–3 experiments: a 3-line summary of what's been learned and what's next. Commit.
9. Next card.

**Anti-fooling rules:** one variable at a time · reproduce baseline before *and* after (thermal drift) · greedy for A/B, real temp for the final check · report the number you measured, never the number you hoped for · if you can't measure on the A17 this cycle, mark the card `BLOCKED-ON-DEVICE`, don't guess.

---

## 1. CONTEXT — read before touching anything

**Current numbers (A17, MedGemma-4B Q4_0):**
- Text assessment: ~2.3× faster than the original baseline (prefix-cache + spec-decode + f16 KV, all shipped). Decode ≈ 2.5 tok/s.
- Image assessment (896 px): encode ~184 s + prefill ~33 s + decode ~47 s ≈ **264 s raw**; **~53 s perceived** with the eager-encode overlap (1.0.18).
- Fast image mode (448 px, opt-in): encode+prefill ~50 s, raw ~97 s.

**Already SHIPPED — do not redo:** spec-decode (ngram-map-k4v), f16 KV cache, prefix caching, text prewarm, eager-encode vision overlap, optional 448 px vision. See `VISION_OPTIMIZATIONS.md`.

**Already RULED OUT — do NOT re-attempt (verified, several on-device):**
- Flash attention is **already ENABLED** on the CPU backend (tiled kernel; naive O(n²) is dead code).
- Matmuls **already run int8 dot-product** (SDOT); weights are not dequantized to fp32.
- Thread **count** is already optimal at 8 (all cores); 2 threads measured *worse* (395 s). *(Note: this is about count, NOT affinity — affinity is card E2, still open.)*
- `image_max_tokens`, `n_ubatch` for the image encode, mmproj Q8→Q4: no encode effect.
- GPU offload was assumed dead — but see E10; the assumption was about the *prebuilt* lib, and we build from source.

**HARDWARE CONSTRAINTS (critical — mis-targeting wastes cycles):**
- **A17 = ARMv8.2-A**, Cortex-A76 (2×, perf) + A55 (6×, eff). Native variant `v8_2_dotprod`.
- Has: **NEON, dotprod (SDOT/UDOT)**. Does **NOT** have: **i8mm (SMMLA)**, **SVE**, **SVE2**, **SME**. (i8mm arrived in v8.6; A76 is v8.2.)
- ⇒ Any kernel/microkernel work must target **NEON + dotprod**. i8mm / SVE / SME paths **will not execute on the A17** — do not invest there for the phone.
- Vision encode currently runs at **~22 % of the A17's CPU peak** (~29 of ~130 GFLOP/s) → there IS ~2–3× of lossless headroom, and it's an *efficiency* problem (scheduling/kernels), not a physics wall.
- Text **decode** is **memory-bandwidth-bound** (scales 2.7× with the S24's faster RAM) → near its floor; kernels won't move decode much. Attack text via **prefill** and **draft-model spec**, not decode kernels.

---

## 2. ENVIRONMENT SPLIT — what the DGX does vs the A17

**DGX Spark (Grace Neoverse-V2 ARM + Blackwell GPU) is good for:**
- Fast **cross-compilation** of the Android NDK libs (arm64-v8a, `v8_2_dotprod` variant).
- **Correctness verification at scale** — run the model (CPU or GPU) and diff outputs with/without a change over many prompts/images. Hardware-agnostic for *correctness* (lossless = same tokens).
- **Model-level work where the GPU genuinely helps:** training/fine-tuning a draft model (E7), re-aligning a 448-native vision checkpoint (E12), quant experiments (E13).
- Rough ARM **sanity** microbenchmarks on Grace.

**DGX is NOT a perf proxy for the A17:** Grace is a wide server core with SVE2/i8mm; Blackwell/CUDA doesn't exist on the phone. **A kernel that's faster on Grace may be neutral or slower on the A17.** Perf claims are only valid when measured on the A17 via adb.

**A17 (authoritative perf + on-device correctness):** every keeper must be validated here. Handoff = build on DGX → `adb install -r` → drive the flow (or the benchmark harness) → read logcat `VISION-TIMING` / tok/s. Tuning hooks without a rebuild: `adb shell setprop debug.eh.clipthreads N`, `adb shell setprop debug.eh.visionsize N`.

**Repo:** parent on branch `demo`; the llama.cpp fork is the submodule `Android/src/app/src/main/cpp/llama-rn-turbo-quant` (`BakungaBronson/llama-rn-turbo-quant`, branch `main`). Backend edits go in the submodule; commit there first, then bump the parent pointer.

---

## 3. METHODOLOGY NOTES (auto-research discipline)

- **Reproduce, then perturb.** Always re-measure the baseline in the same session (thermal drift is real; the A17 throttles over a 3-min burst).
- **Determinism for A/B:** greedy (topK=1) makes output byte-comparable and spec-decode 100 % acceptance, isolating the change. Do the *final* keeper check at production temp 0.5 too.
- **Correctness is a gate, not a nice-to-have.** This is a medical triage model. A 10 % speedup that changes one token in the differential is a **reject** unless the card explicitly permits it (only E10/E12/E13 do, and only behind a clinical concordance check).
- **Log negative results with the same rigor as wins** — half the value here is a durable "we tried X, it did/didn't work, because Y" record so nobody re-runs it.
- **Keep a leaderboard** (§7): the current best *stack* and each lever's isolated contribution.

---

## 4. BACKLOG — LOW EFFORT FIRST

Each card: `id · title` — target · expected · effort · risk · quality · `status`.
Statuses: `OPEN` → `IN-PROGRESS` → `KEPT` / `KILLED` / `BLOCKED-ON-DEVICE`.

---

### E1 · Enable KleidiAI microkernels (build flag)
- **Target:** text prefill + vision matmul · **Expected:** 5–20 % on compute-bound matmul · **Effort:** LOW · **Risk:** low · **Quality:** lossless · **status: OPEN**
- **Hypothesis:** ARM's KleidiAI provides hand-optimized dotprod microkernels for int8/int4 matmul that beat ggml's generic ones on A76. Our build does **not** define `GGML_CPU_KLEIDIAI` → we're leaving them on the table.
- **Implement:** enable KleidiAI in the Android CMake for the `v8_2_dotprod` variant (`android/build.gradle` / `cpp/CMakeLists.txt`; llama.cpp exposes `GGML_CPU_KLEIDIAI=ON`). Confirm at runtime it selects the **dotprod** microkernels (A17 has no i8mm — if it only wires i8mm paths, it's a no-op on the phone; verify).
- **Measure:** text prefill tok/s (biggest surface), vision encode ms. **Correctness:** greedy output identical.
- **Success:** ≥5 % on prefill or encode, bit-identical. **Kill if:** no dotprod path for A76, or <2 %.

### E2 · Pin the vision encoder to the big cores (dedicated threadpool)
- **Target:** vision encode · **Expected:** meaningful (encode at ~22 % peak) · **Effort:** LOW–MED · **Risk:** med · **Quality:** lossless · **status: OPEN**
- **Hypothesis (well-supported):** the LLM gets a pinned, HIGH-priority, big-core threadpool (`llama_jni.cpp:527–546`, `llama_attach_threadpool`), but **clip spins up its own unpinned default CPU backend** (`clip.cpp:167 lm_ggml_backend_init_by_type(CPU)`) — the pinned pool never reaches it. So the ViT barriers to the slowest A55 every op and contends across the heterogeneous cluster. This is the leading explanation for the 22 % efficiency and the device-independence.
- **Implement:** create/attach a pinned big-core (2×A76), HIGH-prio threadpool to clip's `backend_cpu` via `lm_ggml_backend_cpu_set_threadpool` (see the commented `lm_ggml_backend_cpu_set_n_threads` at `clip.cpp:3273`). Thread it from `nativeInitVision`. Try: 2 big cores pinned vs 2big+2little vs current.
- **Measure:** vision encode ms via `VISION-TIMING`. Sweep with `debug.eh.clipthreads` too. **Correctness:** identical (scheduling only).
- **Success:** encode ↓ ≥20 %. This stacks under the overlap AND shrinks fast-mode. **Note:** if it's flat, the bottleneck is memory-latency not scheduling → record that, it's a key finding.

### E3 · simd-gemm.h NEON macro fix
- **Target:** flash-attn tiled GEMM (both) · **Expected:** 0–6 % · **Effort:** TRIVIAL · **Risk:** low-med (global kernel) · **Quality:** lossless · **status: OPEN**
- **Hypothesis:** `ggml-cpu/simd-gemm.h` gates 4×4 register blocking on `defined(__ARM_NEON__)` (double-underscore-suffixed), but Android clang defines `__ARM_NEON` (no suffix) → falls to the 2×2 fallback. Fixing the macro enables 4×4 blocking (25/32 registers, fits A76).
- **Implement:** `__ARM_NEON__` → `__ARM_NEON` in `simd-gemm.h`. Verify the compiler define: `printf '' | <ndk-clang> -dM -E - | grep ARM_NEON`.
- **Measure:** prefill + encode (touches every matmul). **Correctness:** bit-identical (same FMAs, different tiling). **Kill if:** neutral/negative on A17 (register pressure) — it's a global hot path, so measure carefully.

### E4 · Speculative decode in the vision decode loop
- **Target:** vision decode · **Expected:** ~4–5 s (~10 % of decode) · **Effort:** LOW–MED · **Risk:** low · **Quality:** lossless (greedy-identical) · **status: OPEN**
- **Hypothesis:** the text path uses ngram-map-k4v spec-decode (+9.5 % tok/s); the vision decode loop (`nativeCompletionWithImage`) is a plain token-by-token loop. Port the spec + min-token machinery over.
- **Implement:** lift the spec-decode + KV-rollback loop from the text `nativeCompletion` into the vision generation loop.
- **Measure:** vision decode tok/s. **Correctness:** verified lossless by construction (verify-vs-sampler); confirm greedy-identical.

### E5 · n_ubatch / prefill batching sweep (text)
- **Target:** text prefill · **Expected:** 0–5 % · **Effort:** LOW · **Risk:** low · **Quality:** lossless · **status: OPEN**
- **Hypothesis:** text prefill throughput may be sensitive to n_ubatch on the A76+A55 mix. Sweep it (keep ≥256 so image blocks aren't split in the vision path).
- **Implement:** parametrize n_ubatch (already partly plumbed via `nativeInitModelTuned`); sweep 256/512/1024/2048.
- **Measure:** prefill tok/s across prompt lengths. **Correctness:** identical.

---

## 5. BACKLOG — HARDER

### E6 · Flash-attn tiled kernel: reduce K/V F16→F32 repack
- **Target:** vision encode (attention half) · **Expected:** 10–40 s IF confirmed dominant (unproven) · **Effort:** MED · **Risk:** med (hand kernel) · **Quality:** numerically identical · **status: OPEN** · depends-on: E2 (measure after pinning)
- **Hypothesis (low-confidence lead):** the CPU tiled flash-attn kernel re-unpacks K/V from F16→F32 per Q-tile (`ggml-cpu/ops.cpp` ~8500–8640), streaming KV repeatedly. Caching/hoisting the repack could cut memory traffic.
- **Implement:** profile the FA op first (is it actually the dominant sub-op? — after E2, re-profile). If yes, hoist the K/V conversion out of the Q-tile loop.
- **Measure:** encode ms. **Correctness:** bit-identical. **Kill if:** FA isn't dominant (FFN is ~40 % of FLOPs — may be the better target; see E9).

### E7 · Draft-model speculative decoding (text)
- **Target:** text decode · **Expected:** 1.3–1.8× decode IF acceptance is high · **Effort:** MED–HIGH · **Risk:** med · **Quality:** lossless · **status: OPEN**
- **Hypothesis:** a small draft model (e.g. a 0.5–1 B instruct) drafting for MedGemma-4B beats ngram self-spec on acceptance, especially on novel clinical text. **This is where the DGX GPU earns its keep** — pick/distill a draft model on-DGX.
- **Implement:** dual-model spec loop (draft proposes k, target verifies in one batch, KV rollback). Costs extra RAM/APK size (draft weights) — budget it against the A17's 7.6 GB.
- **Measure:** decode tok/s + acceptance rate at temp 0.5. **Correctness:** lossless (verify-vs-target). **Kill if:** acceptance <60 % or RAM blows the budget.

### E8 · Op-fusion in the SigLIP graph (cut barriers)
- **Target:** vision encode · **Expected:** unknown, potentially large · **Effort:** HIGH · **Risk:** high · **Quality:** lossless · **status: OPEN** · depends-on: E2
- **Hypothesis:** the ViT graph has a `lm_ggml_barrier` after ~every op (~400 barriers); on a heterogeneous pool each barrier waits for the slowest core. Fusing ops (norm+matmul, add+act) reduces sync points. Only worth it if E2 shows scheduling (not memory) is the bottleneck.
- **Implement:** identify fusable sequences in `models/siglip.cpp` / `build_vit`; add fused ggml ops or reorder to batch barriers.
- **Measure:** encode ms + barrier count. **Correctness:** bit-identical.

### E9 · Custom NEON+dotprod microkernels for the dominant ViT ops
- **Target:** vision encode (FFN ~40 %, attn ~38 %) · **Expected:** toward the ~2–3× ceiling · **Effort:** HIGH · **Risk:** high · **Quality:** lossless · **status: OPEN** · depends-on: E1, E2
- **Hypothesis:** hand-tuned A76 kernels (NEON + **dotprod only** — no i8mm/SVE on the A17) for the FFN up/down matmuls and QKV/O projections beat generic ggml. Do this only after KleidiAI (E1) — it may already cover most of it.
- **Implement:** micro-optimize the hottest matmul for A76 (cache blocking for A76's L2, dotprod accumulation). Validate correctness against the reference kernel.
- **Measure:** per-op ms + full encode. **Correctness:** bit-identical (or within fp tolerance, verified).

### E10 · Mali OpenCL backend for the vision encoder
- **Target:** vision encode · **Expected:** potentially >2× IF it runs · **Effort:** HIGH · **Risk:** high (may not work on Mali) · **Quality:** ~lossless (fp differs slightly) · **status: OPEN**
- **Hypothesis:** the A17 exposes `/vendor/lib64/libOpenCL.so` (Mali-G57). ggml's OpenCL backend is Adreno-tuned but may run (degraded) on Mali. We build from source, so it *can* be compiled in. Offloading the ViT to the GPU sidesteps the CPU entirely.
- **Implement:** build `ggml-opencl` into the `v8_2_dotprod` variant; set clip `use_gpu=true` for the vision encode only. Expect driver pain; timebox it.
- **Measure:** encode ms; watch for correctness drift (GPU fp) — gate with a concordance check since numerics differ. **Kill fast if:** Mali rejects the kernels or it's slower than pinned-CPU (E2).

### E11 · MediaTek APU (NNAPI / NeuroPilot) offload — RESEARCH ONLY
- **Target:** vision or LLM · **Expected:** unknown (large if it lands) · **Effort:** VERY HIGH · **Risk:** very high · **Quality:** TBD · **status: OPEN**
- **Hypothesis:** the Helio G99 has a MediaTek APU (NPU). Reaching it means NNAPI or NeuroPilot — a *different* runtime than llama.cpp (this is essentially what the LiteRT migration moved away from). Scope: research feasibility + a toy ViT-on-NNAPI PoC before any commitment. Likely a separate track, not a llama.cpp edit.
- **Deliverable this cycle:** a feasibility memo, not code.

### E12 · 448-native vision checkpoint (remove fast-mode quality tradeoff)
- **Target:** image quality@speed · **Expected:** makes fast-mode lossless-ish · **Effort:** HIGH (model) · **Risk:** med · **Quality:** the whole point · **status: OPEN**
- **Hypothesis:** the current 448 mode interpolates position embeddings off-distribution. A SigLIP re-aligned/fine-tuned at 448 (on-DGX, GPU) would give the ~4× encode win without the fine-detail loss. **DGX GPU work.**
- **Implement:** fine-tune / distill the vision tower at 448; re-export the mmproj; run a **labeled clinical concordance** check vs 896 before enabling.
- **Measure:** concordance vs 896 on a labeled set; encode ms. **Gate:** clinical sign-off.

### E13 · Model quant / size exploration
- **Target:** APK size + some speed · **Expected:** size big, speed small · **Effort:** MED · **Risk:** med (quality) · **status: OPEN**
- **Hypothesis:** MedASR fp32→int8 (~402 MB → ~100 MB) and MedGemma quant tradeoffs shrink the 3.28 GB APK and may speed load; primarily a **size** lever. **DGX** for the quant + eval.
- **Measure:** size, load time, ASR WER / triage concordance. **Gate:** quality thresholds.

---

## 6. RUNNING LOG (append-only — newest last)

> Template per entry:
> ```
> [YYYY-MM-DD HH:MM] Ex · <card id> · <one-line change>
> baseline: <metric>=<value>  →  result: <metric>=<value>  (Δ <±%>)
> correctness: <identical | drift: ...>
> verdict: KEPT / KILLED / BLOCKED  — because <reason>
> next: <what this implies for the next card>
> ```

_(empty — first experiment goes here)_

---

## 7. LEADERBOARD (current best, update on every KEEP)

| Path | Baseline (pre-work) | Shipped 1.0.18 | Best measured here | Lever(s) |
|------|--------------------:|---------------:|-------------------:|----------|
| Text assessment | 1.0× | ~2.3× | — | prefix+spec+f16 (shipped) |
| Image raw (896) | ~264 s | ~264 s | — | (overlap hides it) |
| Image perceived | ~264 s | ~53 s | — | eager overlap (shipped) |
| Image fast (448) | — | ~97 s / ~44 s | — | resolution + overlap (shipped) |

---

## 8. QUICK REFERENCE — where things live

- JNI: `Android/src/app/src/main/cpp/llama_jni.cpp` — `init_model_full` (threadpool 527–546), `nativeInitVision`, `nativeEncodeImagePrefix`, `nativeCompletionWithImage` (overlap fast path + vision decode loop).
- clip: `…/llama-rn-turbo-quant/cpp/tools/mtmd/clip.cpp` — CPU backend init (167), `clip_n_output_tokens` (2641), flash-attn warmup resolve (2234), graph_compute (3270).
- siglip graph: `…/cpp/tools/mtmd/models/siglip.cpp` — `build()`, `resize_position_embeddings`.
- kernels: `…/cpp/ggml-cpu/simd-gemm.h`, `…/cpp/ggml-cpu/ops.cpp` (flash-attn tiled path).
- build: `…/llama-rn-turbo-quant/android/build.gradle`, `…/cpp/CMakeLists.txt`; app CMake `Android/src/app/src/main/cpp/CMakeLists.txt`.
- On-device: `adb -s RRGL20BH6ZZ` (A17 #1), `RRGL208YREL` (A17 #2). Package `com.craneailabs.easehealth`. Logcat tags `LlamaJNI`, `LlamaCppLog`, `VISION-TIMING`.
