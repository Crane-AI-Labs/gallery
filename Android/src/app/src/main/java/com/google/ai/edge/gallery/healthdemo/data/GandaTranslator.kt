package com.google.ai.edge.gallery.healthdemo.data

import android.util.Log
import com.google.ai.edge.gallery.llm.LlamaCpp
import com.google.ai.edge.gallery.llm.TokenCallback
import java.io.File

private const val TAG = "GandaTranslator"

/**
 * Luganda→English draft translation via Ganda Gemma 1B (ganda flavor only).
 *
 * Runs on the same llama.cpp JNI as MedGemma but with its own model handle.
 * The model is loaded on demand and released immediately after translating so
 * its ~0.9 GB never overlaps MedGemma's cold load on low-RAM devices.
 *
 * IMPORTANT — clinical safety: Ganda Gemma is trained EN→LUG; its LUG→EN
 * ability is usable but imperfect (bench testing found symptom-level
 * mistranslations, e.g. fever→cold). The output of this translator is a
 * DRAFT that must land in the editable symptoms field for the health worker
 * to confirm or correct before triage — never feed it to MedGemma unseen.
 *
 * The prompt scaffold below is the empirically-validated variant: the plain
 * "Translate to English:" form makes the model paraphrase in Luganda, while
 * the explicit "Reply in English only" + "English:" scaffold reliably
 * switches it to English output.
 */
object GandaTranslator {

    // 1B model, short prompt + one-sentence output: a small context is plenty
    // and keeps the KV cache footprint trivial next to MedGemma's.
    private const val N_CTX = 512
    private const val N_PREDICT = 96

    private var handle: Long = 0L

    /**
     * Translate Luganda text to an English draft. Loads the model, runs one
     * completion, releases the model. Returns null on any failure so the
     * caller can fall back to using the raw Luganda transcript.
     */
    fun translate(modelPath: String, lugandaText: String): String? {
        if (lugandaText.isBlank()) return null
        if (!LlamaCpp.isAvailable()) {
            Log.w(TAG, "llama.cpp native library not available")
            return null
        }
        if (!File(modelPath).exists()) {
            Log.w(TAG, "Ganda Gemma model not found at $modelPath")
            return null
        }

        return try {
            val startMs = System.currentTimeMillis()
            handle = LlamaCpp.initModel(
                modelPath = modelPath,
                nCtx = N_CTX,
                nGpuLayers = 0,
                nBatch = 512,
            )
            if (handle == 0L) {
                Log.e(TAG, "Failed to load Ganda Gemma model")
                return null
            }

            val prompt = "<start_of_turn>user\n" +
                "Translate this Luganda sentence into English. Reply in English only.\n\n" +
                "Luganda: ${lugandaText.trim()}\n" +
                "English:<end_of_turn>\n" +
                "<start_of_turn>model\n"

            val sb = StringBuilder()
            val result = LlamaCpp.completion(
                handle = handle,
                prompt = prompt,
                nPredict = N_PREDICT,
                temperature = 0.3f,
                topK = 40,
                topP = 0.95f,
                stopSequences = "",
                callback = object : TokenCallback {
                    override fun onToken(token: String) {
                        sb.append(token)
                        // Single-sentence output: stop decoding at the first
                        // line break instead of burning tokens to nPredict.
                        if (sb.contains('\n')) LlamaCpp.stopCompletion(handle)
                    }
                },
            )
            Log.v(TAG, "raw completion result: ${result.take(200)}")

            val english = sb.toString()
                .substringBefore('\n')
                .replace("<end_of_turn>", "")
                .trim()
            val elapsed = System.currentTimeMillis() - startMs
            Log.d(TAG, "LUG→EN translation in ${elapsed}ms: ${english.length} chars")
            english.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            null
        } finally {
            if (handle != 0L) {
                try { LlamaCpp.releaseModel(handle) } catch (_: Exception) { /* best effort */ }
                handle = 0L
            }
        }
    }
}
