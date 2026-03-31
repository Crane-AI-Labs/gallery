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
     */
    fun initModel(modelPath: String, nCtx: Int, nGpuLayers: Int, kvCacheType: Int = 2): Long {
        if (!nativeLoaded) return 0L
        return nativeInitModel(modelPath, nCtx, nGpuLayers, kvCacheType)
    }

    fun completion(
        handle: Long,
        prompt: String,
        nPredict: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        stopSequences: String,
        callback: TokenCallback
    ): String {
        if (!nativeLoaded) return """{"error":"Native library not loaded"}"""
        return nativeCompletion(handle, prompt, nPredict, temperature, topK, topP, stopSequences, callback)
    }

    fun stopCompletion(handle: Long) {
        if (nativeLoaded) nativeStopCompletion(handle)
    }

    fun releaseModel(handle: Long) {
        if (nativeLoaded) nativeReleaseModel(handle)
    }

    fun initVision(handle: Long, mmprojPath: String): Boolean {
        if (!nativeLoaded) return false
        return nativeInitVision(handle, mmprojPath)
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

    private external fun nativeInitModel(modelPath: String, nCtx: Int, nGpuLayers: Int, kvCacheType: Int): Long
    private external fun nativeCompletion(
        handle: Long, prompt: String, nPredict: Int,
        temperature: Float, topK: Int, topP: Float,
        stopSequences: String, callback: TokenCallback
    ): String
    private external fun nativeStopCompletion(handle: Long)
    private external fun nativeReleaseModel(handle: Long)
    private external fun nativeInitVision(handle: Long, mmprojPath: String): Boolean
    private external fun nativeCompletionWithImage(
        handle: Long, prompt: String, imageData: ByteArray, nPredict: Int,
        temperature: Float, topK: Int, topP: Float, callback: TokenCallback
    ): String
}

interface TokenCallback {
    fun onToken(token: String)
}
