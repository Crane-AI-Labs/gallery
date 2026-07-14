package com.google.ai.edge.gallery.llm

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

/**
 * JNI wrapper for llama.cpp inference.
 * Detects CPU features at runtime and loads the most optimized variant,
 * exactly matching llama.rn's RNLlama.java approach.
 */
object LlamaCpp {

    private const val TAG = "LlamaCpp"
    private var nativeLoaded = false
    private var loadedVariant = ""

    init {
        try {
            loadedVariant = loadBestLibrary()
            nativeLoaded = true
            Log.i(TAG, "Native llama.cpp loaded (variant: $loadedVariant)")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }

    /**
     * Load the best JNI wrapper variant based on CPU features.
     * Each llama_jni_* is linked against its matching rnllama_* at build time,
     * so loading one automatically pulls in the optimized rnllama variant.
     */
    private fun loadBestLibrary(): String {
        if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            val cpuFeatures = getCpuFeatures()
            val hasDotProd = cpuFeatures.contains("asimddp")
            val hasI8mm = cpuFeatures.contains("i8mm")
            val hasFp16 = cpuFeatures.contains("fphp")

            Log.d(TAG, "CPU: dotprod=$hasDotProd i8mm=$hasI8mm fp16=$hasFp16")

            if (hasDotProd && hasI8mm && tryLoad("llama_jni_v8_2_dotprod_i8mm"))
                return "v8_2_dotprod_i8mm"
            if (hasDotProd && tryLoad("llama_jni_v8_2_dotprod"))
                return "v8_2_dotprod"
            if (hasI8mm && tryLoad("llama_jni_v8_2_i8mm"))
                return "v8_2_i8mm"
            if (hasFp16 && tryLoad("llama_jni_v8_2"))
                return "v8_2"
            if (tryLoad("llama_jni_v8"))
                return "v8"
        } else if (Build.SUPPORTED_ABIS.any { it == "x86_64" }) {
            if (tryLoad("llama_jni_x86_64"))
                return "x86_64"
        }

        // Fallback to generic
        System.loadLibrary("llama_jni")
        return "generic"
    }

    private fun tryLoad(name: String): Boolean {
        return try {
            System.loadLibrary(name)
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    private fun getCpuFeatures(): String {
        return try {
            BufferedReader(FileReader("/proc/cpuinfo")).use { reader ->
                reader.lineSequence()
                    .firstOrNull { it.startsWith("Features") }
                    ?.substringAfter(":")?.trim() ?: ""
            }
        } catch (e: Exception) { "" }
    }

    fun isAvailable(): Boolean = nativeLoaded

    /**
     * KV cache type codes:
     * 0 = f16 (default, no compression)
     * 1 = q8_0 (50% memory savings)
     * 2 = q4_0 (75% memory savings)
     * 3 = turbo3 (81% savings, TurboQuant arXiv 2504.19874)
     * 4 = turbo4 (75% savings, TurboQuant)
     *
     * @param nBatch Prompt processing batch size. Lower this on low-RAM devices to avoid OOM.
     *               Recommended: 256 for <3GB RAM, 512 for <5GB, 2048 for flagships.
     */
    fun initModel(
        modelPath: String,
        nCtx: Int,
        nGpuLayers: Int,
        // TurboQuant KV (code=3/4) crashes inside lm_ggml_compute_forward_flash_attn_ext
        // — the TURBO3_0/TURBO4_0 types are wired through llama-graph.cpp and
        // llama-kv-cache.cpp but ggml-cpu/ops.cpp flash-attn has no dispatch
        // for them, so MedGemma's FA path reads them as garbage. Stay on Q4_0
        // KV until the FA kernel learns the TurboQuant layout.
        kvCacheType: Int = 2,
        nBatch: Int = 2048
    ): Long {
        if (!nativeLoaded) return 0L
        return nativeInitModel(modelPath, nCtx, nGpuLayers, kvCacheType, nBatch)
    }

    /**
     * Benchmark-only init with tuning knobs. 0 = use default.
     *   nUbatch          physical batch size
     *   nThreadsOverride pin to the N fastest cores (big-cores-first)
     *   flashAttn        0=off, 1=on
     */
    fun initModelTuned(
        modelPath: String, nCtx: Int, nGpuLayers: Int, kvCacheType: Int, nBatch: Int,
        nUbatch: Int = 0, nThreadsOverride: Int = 0, flashAttn: Int = 1,
    ): Long {
        if (!nativeLoaded) return 0L
        return nativeInitModelTuned(modelPath, nCtx, nGpuLayers, kvCacheType, nBatch, nUbatch, nThreadsOverride, flashAttn)
    }

    /** Returns the number of inference threads currently bound to perf cores. */
    fun getThreadCount(handle: Long): Int {
        if (!nativeLoaded) return 0
        return nativeGetThreadCount(handle)
    }

    /** Returns CPU IDs of detected performance cores as a comma-separated string. */
    fun getPerfCoreInfo(): String {
        if (!nativeLoaded) return ""
        return nativeGetPerfCoreInfo()
    }

    /** Returns the variant name of the loaded native library (e.g. "v8_2_dotprod_i8mm"). */
    fun getLoadedVariant(): String = loadedVariant

    fun completion(
        handle: Long,
        prompt: String,
        nPredict: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        stopSequences: String,
        callback: TokenCallback,
        // 1.0.12: minimum tokens before EOG/EOS is honored. Defends against
        // the warm-cache-replay-stop pathology where a re-run of an
        // identical prompt makes the sampler pick EOG immediately.
        nMinTokens: Int = 5,
        // 1.0.16: single-model speculative decoding (ngram-map-k4v, ported
        // from llama.cpp PR-18471). Lossless; measured +9.5% tok/s on the
        // A17 with MedGemma-4B Q4. Text completions only — never enable for
        // batched serving (it inverts the gain).
        specDecode: Boolean = false,
    ): String {
        if (!nativeLoaded) return """{"error":"Native library not loaded"}"""
        return nativeCompletion(handle, prompt, nPredict, temperature, topK, topP, stopSequences, nMinTokens, specDecode, callback)
    }

    /**
     * Prefill a prompt (e.g. the constant instruction+few-shot prefix) into the
     * KV cache without generating — used for background prewarming so the first
     * assessment skips the cold prefill. Returns tokens resident, or -1 on error.
     */
    fun prefill(handle: Long, prompt: String): Int {
        if (!nativeLoaded) return -1
        return nativePrefill(handle, prompt)
    }

    fun stopCompletion(handle: Long) {
        if (nativeLoaded) nativeStopCompletion(handle)
    }

    fun releaseModel(handle: Long) {
        if (nativeLoaded) nativeReleaseModel(handle)
    }

    /**
     * Explicitly clear the KV cache. Call this when starting a NEW conversation,
     * not between turns of the same conversation. Between turns the cache is
     * reused so the system prompt and prior history don't need re-processing.
     */
    fun clearContext(handle: Long) {
        if (nativeLoaded) nativeClearContext(handle)
    }

    /**
     * Returns the number of tokens currently in the KV cache.
     * Useful for monitoring cache utilization relative to n_ctx.
     */
    fun getCacheTokenCount(handle: Long): Int {
        if (!nativeLoaded) return 0
        return nativeGetCacheTokenCount(handle)
    }

    /**
     * @param imageSize override the square ViT input size (0 = model default,
     *   896 for MedGemma). A smaller value (e.g. 448) enables reduced-resolution
     *   "fast image mode" — quicker encode, less fine detail.
     */
    fun initVision(handle: Long, mmprojPath: String, imageSize: Int = 0): Boolean {
        if (!nativeLoaded) return false
        return nativeInitVision(handle, mmprojPath, imageSize)
    }

    /**
     * Eagerly encode [constant prefix + image] into the KV cache (the ~184s
     * SigLIP forward) so a following [completionWithImage] with the same image +
     * prefix only prefills the patient tail. Run in the background at image
     * attach time. Returns the resident token count, or -1 on error.
     */
    fun encodeImagePrefix(handle: Long, prefixPrompt: String, imageData: ByteArray): Int {
        if (!nativeLoaded) return -1
        return nativeEncodeImagePrefix(handle, prefixPrompt, imageData)
    }

    fun completionWithImage(
        handle: Long,
        prompt: String,
        imageData: ByteArray,
        nPredict: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        callback: TokenCallback
    ): String {
        if (!nativeLoaded) return """{"error":"Native library not loaded"}"""
        return nativeCompletionWithImage(handle, prompt, imageData, nPredict, temperature, topK, topP, callback)
    }

    private external fun nativeInitModel(modelPath: String, nCtx: Int, nGpuLayers: Int, kvCacheType: Int, nBatch: Int): Long
    private external fun nativeInitModelTuned(modelPath: String, nCtx: Int, nGpuLayers: Int, kvCacheType: Int, nBatch: Int, nUbatch: Int, nThreadsOverride: Int, flashAttn: Int): Long
    private external fun nativeCompletion(
        handle: Long, prompt: String, nPredict: Int,
        temperature: Float, topK: Int, topP: Float,
        stopSequences: String, nMinTokens: Int, specDecode: Boolean, callback: TokenCallback
    ): String
    private external fun nativePrefill(handle: Long, prompt: String): Int
    private external fun nativeStopCompletion(handle: Long)
    private external fun nativeClearContext(handle: Long)
    private external fun nativeGetCacheTokenCount(handle: Long): Int
    private external fun nativeGetThreadCount(handle: Long): Int
    private external fun nativeGetPerfCoreInfo(): String
    private external fun nativeReleaseModel(handle: Long)
    private external fun nativeInitVision(handle: Long, mmprojPath: String, imageSize: Int): Boolean
    private external fun nativeEncodeImagePrefix(handle: Long, prefixPrompt: String, imageData: ByteArray): Int
    private external fun nativeCompletionWithImage(
        handle: Long, prompt: String, imageData: ByteArray, nPredict: Int,
        temperature: Float, topK: Int, topP: Float, callback: TokenCallback
    ): String
}

interface TokenCallback {
    fun onToken(token: String)
}
