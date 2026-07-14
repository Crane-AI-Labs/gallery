package com.google.ai.edge.gallery.healthdemo.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.gallery.data.ModelAssetManager
import com.google.ai.edge.gallery.llm.DeviceInfo
import com.google.ai.edge.gallery.llm.LlamaCpp
import com.google.ai.edge.gallery.llm.TokenCallback
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Controlled on-device benchmark for the ngram-map-k4v self-speculative
 * decoder (1.0.16). NOT part of the normal app — triggered only via
 *   adb shell am start -n <pkg>/....MainActivity --es bench go [--ei iters N]
 *
 * Why a harness instead of driving the UI: the clinical form clears on every
 * "New", and normal inference runs at temperature 0.5 so output length (hence
 * wall-time) varies run-to-run. That makes a fair A/B impossible from the UI.
 *
 * Here we pin GREEDY decoding (topK=1) so the model emits the *identical*
 * token sequence every run — spec-off and spec-on produce byte-identical
 * output (the decoder is lossless), so the only variable is speed. We loop N
 * times per condition in one process on the real device with the real JNI
 * port, giving a clean per-run tok/s distribution → mean ± 95% CI.
 *
 * Definitions logged:
 *   - model_load_ms : one-time cold model load (the "cold" surcharge)
 *   - per run: prompt-eval + generation time with a fresh KV each run
 *     (clearContext before each), i.e. the per-assessment inference cost the
 *     app pays once the model is resident ("warm" app case).
 *   Cold app time  = model_load_ms + per-run time (first patient).
 *   Warm app time  = per-run time (subsequent patients).
 */
object SpecBenchmark {

    private const val TAG = "SpecBench"

    @Volatile private var running = false

    /**
     * @param tag     label for this config, printed on every line (e.g. "thr2_ub2048_q4_fa1")
     * @param kvType  KV cache code (0=f16,1=q8_0,2=q4_0)
     * @param nBatch  logical batch (0 = device default)
     * @param nUbatch physical batch (0 = default)
     * @param nThreads pin to N fastest cores (0 = perf-core heuristic)
     * @param flashAttn 0/1
     * @param promptMult pad the constant prefix ~this many times to probe prompt-length scaling
     */
    fun run(
        context: Context, iters: Int = 12, warmup: Int = 2, tag: String = "default",
        kvType: Int = 2, nBatch: Int = 0, nUbatch: Int = 0, nThreads: Int = 0,
        flashAttn: Int = 1, promptMult: Int = 1, prefixReuse: Int = 0,
    ) {
        if (running) { Log.w(TAG, "already running"); return }
        running = true
        try {
            if (!LlamaCpp.isAvailable()) { Log.e(TAG, "native lib unavailable"); return }
            val modelPath = ModelAssetManager.getModelPath(context, ModelAssetManager.LLM_MODEL)
            if (!java.io.File(modelPath).exists()) { Log.e(TAG, "model missing: $modelPath"); return }

            // Definitive stacked A/B: baseline (q4_0 / clear / spec-off) vs
            // full stack (f16 / prefix-reuse / spec-on), SAME 6 patients, SAME
            // session. Baseline runs FIRST (cooler) so the stack is thermally
            // handicapped → the measured reduction is a conservative lower
            // bound.
            if (prefixReuse == 2) { runFinalStack(context, modelPath, iters, tag, promptMult); return }

            val batch = if (nBatch > 0) nBatch else DeviceInfo.recommendedNBatch(context)
            Log.i(TAG, "=== BENCH START [$tag] === device=${DeviceInfo.summary(context)} variant=${LlamaCpp.getLoadedVariant()}")
            Log.i(TAG, "cfg[$tag] iters=$iters warmup=$warmup kvType=$kvType nBatch=$batch nUbatch=$nUbatch nThreads=$nThreads flash=$flashAttn promptMult=$promptMult")

            val loadT0 = SystemClock.elapsedRealtime()
            val handle = LlamaCpp.initModelTuned(
                modelPath = modelPath, nCtx = if (promptMult > 1) 4096 else 2048, nGpuLayers = 99,
                kvCacheType = kvType, nBatch = batch, nUbatch = nUbatch,
                nThreadsOverride = nThreads, flashAttn = flashAttn,
            )
            val loadMs = SystemClock.elapsedRealtime() - loadT0
            if (handle == 0L) { Log.e(TAG, "model load failed [$tag]"); return }
            Log.i(TAG, "model_load_ms=$loadMs [$tag]")

            // Fixed prompt — the pneumonia case, identical every run. When
            // promptMult>1, prepend extra copies of the (constant) clinical
            // examples as filler to lengthen the prompt and probe how prefill
            // time — and thus the spec/thread speedups — scale with length.
            val filler = if (promptMult > 1)
                "Reference cases (ignore):\n".repeat((promptMult - 1) * 8) else ""
            val prompt = filler + ClinicalPrompt.buildTextPrompt(
                symptoms = "Child has high fever, fast breathing and chest indrawing for two days, not feeding well",
                age = "3 years", sex = "Male", vitals = "Temp: 38.9C, RR: 52/min",
            )
            val noop = object : TokenCallback { override fun onToken(token: String) {} }

            fun runPrompt(p: String, spec: Boolean, doClear: Boolean): Pair<Long, Int> {
                if (doClear) LlamaCpp.clearContext(handle)
                val t0 = SystemClock.elapsedRealtime()
                val json = LlamaCpp.completion(
                    handle = handle, prompt = p, nPredict = 384,
                    temperature = 1.0f, topK = 1, topP = 1.0f,
                    stopSequences = "</r>", callback = noop, nMinTokens = 5, specDecode = spec,
                )
                val ms = SystemClock.elapsedRealtime() - t0
                val tok = try { JSONObject(json).optInt("tokens_generated", -1) } catch (e: Exception) { -1 }
                return ms to tok
            }

            // ── Prefix-cache experiment ──────────────────────────────────────
            // The constant ~1000-token instruction+few-shot prefix is shared by
            // every assessment; only the ~100-token patient tail differs. CLEAR
            // reprocesses the whole prompt each time (status quo); REUSE keeps
            // the prefix KV across DIFFERENT patients so only the tail + decode
            // run. Interleaved CLEAR/REUSE to control thermal. spec is ON in
            // both (the target config).
            if (prefixReuse == 1) {
                val patients = listOf(
                    Triple("Child has high fever, fast breathing and chest indrawing for two days, not feeding well", "3 years", "Male"),
                    Triple("Adult with severe headache, neck stiffness and photophobia since yesterday", "34 years", "Female"),
                    Triple("Elderly patient with productive cough, fever and difficulty breathing for four days", "68 years", "Male"),
                    Triple("Young woman with lower abdominal pain, fever and foul vaginal discharge for three days", "22 years", "Female"),
                    Triple("Infant with watery diarrhoea, vomiting and sunken eyes since morning", "8 months", "Female"),
                    Triple("Man with painful swollen leg, redness and fever after a wound one week ago", "45 years", "Male"),
                )
                fun promptFor(p: Triple<String,String,String>) = filler + ClinicalPrompt.buildTextPrompt(
                    symptoms = p.first, age = p.second, sex = p.third, vitals = "",
                )
                // warm the prefix once
                runPrompt(promptFor(patients[0]), true, true)
                val clr = ArrayList<Long>(); val reu = ArrayList<Long>()
                for (i in 0 until iters) {
                    val pt = patients[i % patients.size]
                    val p = promptFor(pt)
                    val (msC, _) = runPrompt(p, true, true)   // CLEAR: full prefill
                    // change patient before REUSE so the reused portion is only the prefix
                    val pt2 = patients[(i + 1) % patients.size]
                    val (msR, _) = runPrompt(promptFor(pt2), true, false)  // REUSE: prefix cached
                    clr.add(msC); reu.add(msR)
                    Log.i(TAG, "[$tag] prefix run=$i clear_ms=$msC reuse_ms=$msR")
                }
                val mc = clr.average(); val mr = reu.average()
                Log.i(TAG, "SUMMARY [$tag] PREFIX clear_ms_mean=${"%.0f".format(mc)} reuse_ms_mean=${"%.0f".format(mr)} " +
                    "saved_ms=${"%.0f".format(mc - mr)} reduction=${"%.1f".format(100*(mc-mr)/mc)}%")
                LlamaCpp.releaseModel(handle)
                Log.i(TAG, "=== BENCH DONE ===")
                return
            }

            // Greedy: topK=1 forces argmax → deterministic identical output, so
            // spec on/off is a pure speed comparison on identical work.
            fun oneRun(spec: Boolean): Pair<Long, Int> = runPrompt(prompt, spec, true)

            // Warm the caches so the first measured run isn't an outlier.
            repeat(warmup) { oneRun(false); oneRun(true) }

            // INTERLEAVE off/on each iteration so both conditions share the
            // same thermal trajectory — the A17 throttles over a long run, and
            // an all-off-then-all-on order would run the second block hotter,
            // biasing the comparison. Also record ms (not just tok/s) and the
            // CPU-cluster temperature per run for transparency.
            val tOff = ArrayList<Double>(iters); val msOff = ArrayList<Long>(iters)
            val tOn  = ArrayList<Double>(iters); val msOn  = ArrayList<Long>(iters)
            var tokOff = -1; var tokOn = -1
            for (i in 1..iters) {
                for (spec in listOf(false, true)) {
                    val (ms, tok) = oneRun(spec)
                    val tokPerS = if (ms > 0) tok * 1000.0 / ms else 0.0
                    if (spec) { tOn.add(tokPerS); msOn.add(ms); if (tokOn < 0) tokOn = tok }
                    else      { tOff.add(tokPerS); msOff.add(ms); if (tokOff < 0) tokOff = tok }
                    Log.i(TAG, "[$tag] spec=$spec run=$i tokens=$tok ms=$ms tok_s=${"%.2f".format(tokPerS)}")
                }
            }
            summarize(tag, "false", tOff, msOff, tokOff, iters, loadMs)
            summarize(tag, "true", tOn, msOn, tokOn, iters, loadMs)

            LlamaCpp.releaseModel(handle)
            Log.i(TAG, "=== BENCH DONE ===")
        } catch (e: Exception) {
            Log.e(TAG, "benchmark failed", e)
        } finally {
            running = false
        }
    }

    private val CASES = listOf(
        Triple("Child has high fever, fast breathing and chest indrawing for two days, not feeding well", "3 years", "Male"),
        Triple("Adult with severe headache, neck stiffness and photophobia since yesterday", "34 years", "Female"),
        Triple("Elderly patient with productive cough, fever and difficulty breathing for four days", "68 years", "Male"),
        Triple("Young woman with lower abdominal pain, fever and foul vaginal discharge for three days", "22 years", "Female"),
        Triple("Infant with watery diarrhoea, vomiting and sunken eyes since morning", "8 months", "Female"),
        Triple("Man with painful swollen leg, redness and fever after a wound one week ago", "45 years", "Male"),
    )

    /** Definitive stacked A/B in one session; see call site for rationale. */
    private fun runFinalStack(context: Context, modelPath: String, iters: Int, tag: String, promptMult: Int) {
        val filler = if (promptMult > 1) "Reference cases (ignore):\n".repeat((promptMult - 1) * 8) else ""
        fun promptFor(p: Triple<String,String,String>) = filler + ClinicalPrompt.buildTextPrompt(
            symptoms = p.first, age = p.second, sex = p.third, vitals = "")
        val noop = object : TokenCallback { override fun onToken(token: String) {} }
        fun measure(handle: Long, spec: Boolean, clear: Boolean, p: String): Long {
            if (clear) LlamaCpp.clearContext(handle)
            val t0 = SystemClock.elapsedRealtime()
            LlamaCpp.completion(handle, p, 384, 1.0f, 1, 1.0f, "</r>", noop, 5, spec)
            return SystemClock.elapsedRealtime() - t0
        }
        val batch = DeviceInfo.recommendedNBatch(context)
        Log.i(TAG, "=== BENCH START [$tag] FINALSTACK === ${DeviceInfo.summary(context)}")

        // Phase 1 — BASELINE: q4_0, spec OFF, clear each (production behaviour).
        val hB = LlamaCpp.initModelTuned(modelPath, 2048, 99, 2, batch, 0, 0, 1)
        if (hB == 0L) { Log.e(TAG, "baseline load failed"); return }
        measure(hB, false, true, promptFor(CASES[0])) // warmup
        val base = ArrayList<Long>()
        for (i in 0 until iters) { val m = measure(hB, false, true, promptFor(CASES[i % CASES.size])); base.add(m); Log.i(TAG, "[$tag] base run=$i ms=$m") }
        LlamaCpp.releaseModel(hB)

        // Phase 2 — STACK: f16, spec ON, prefix reuse (no clear across patients).
        val hS = LlamaCpp.initModelTuned(modelPath, 2048, 99, 0, batch, 0, 0, 1)
        if (hS == 0L) { Log.e(TAG, "stack load failed"); return }
        measure(hS, true, true, promptFor(CASES[0])) // warmup: fill the constant prefix
        val stk = ArrayList<Long>()
        for (i in 0 until iters) { val m = measure(hS, true, false, promptFor(CASES[(i + 1) % CASES.size])); stk.add(m); Log.i(TAG, "[$tag] stack run=$i ms=$m") }
        LlamaCpp.releaseModel(hS)

        val mb = base.average(); val ms = stk.average()
        val (bm, bci, _) = stats(base.map { it.toDouble() })
        val (sm, sci, _) = stats(stk.map { it.toDouble() })
        Log.i(TAG, "SUMMARY [$tag] FINALSTACK baseline_ms=${"%.0f".format(mb)}±${"%.0f".format(bci)} " +
            "stack_ms=${"%.0f".format(ms)}±${"%.0f".format(sci)} reduction=${"%.1f".format(100*(mb-ms)/mb)}% " +
            "speedup=${"%.2f".format(mb/ms)}x")
        Log.i(TAG, "=== BENCH DONE ===")
    }

    private fun summarize(tag: String, label: String, times: List<Double>, mss: List<Long>, tok: Int, iters: Int, loadMs: Long) {
        val (mean, ci95, sd) = stats(times)
        val msMean = if (mss.isNotEmpty()) mss.average() else 0.0
        Log.i(TAG, "SUMMARY [$tag] spec=$label tokens=$tok n=$iters " +
            "tok_s_mean=${"%.2f".format(mean)} sd=${"%.2f".format(sd)} ci95=±${"%.2f".format(ci95)} " +
            "ms_mean=${"%.0f".format(msMean)} load_ms=$loadMs cold_ms=${"%.0f".format(loadMs + msMean)}")
    }

    /** Hottest CPU-cluster temperature in °C, or -1 if unreadable. */
    private fun cpuTempC(): Int {
        var maxMilli = -1
        for (z in 0..40) {
            try {
                val t = java.io.File("/sys/class/thermal/thermal_zone$z/temp")
                if (!t.exists()) continue
                val v = t.readText().trim().toIntOrNull() ?: continue
                // values are usually milli-°C (e.g. 45000); some report °C directly
                val milli = if (v > 1000) v else v * 1000
                if (milli > maxMilli) maxMilli = milli
            } catch (e: Exception) { /* ignore */ }
        }
        return if (maxMilli < 0) -1 else maxMilli / 1000
    }

    /** mean, 95% CI half-width (t-approx 1.96 for n>=~10), sample SD. */
    private fun stats(xs: List<Double>): Triple<Double, Double, Double> {
        val n = xs.size
        if (n == 0) return Triple(0.0, 0.0, 0.0)
        val mean = xs.average()
        if (n == 1) return Triple(mean, 0.0, 0.0)
        val variance = xs.sumOf { (it - mean) * (it - mean) } / (n - 1)
        val sd = sqrt(variance)
        val ci = 1.96 * sd / sqrt(n.toDouble())
        return Triple(mean, ci, sd)
    }
}
