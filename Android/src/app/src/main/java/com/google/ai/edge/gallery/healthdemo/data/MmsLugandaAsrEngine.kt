package com.google.ai.edge.gallery.healthdemo.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

private const val TAG = "MmsLugandaAsrEngine"

/**
 * Luganda MMS/Wav2Vec2 CTC ASR (ganda flavor only).
 *
 * Ported from the open-voice project's MmsLugandaASR. Fine-tuned checkpoint:
 * CraneAILabs/mms-1b-lug-asr-waxalnlp (held-out CER 7.0%). The ONNX export is
 * MatMul-only dynamic INT8 — Conv ops stay FP32 because ONNX Runtime Android
 * has no ConvInteger kernel.
 *
 * Unlike MedAsrEngine (mel-spectrogram front end), wav2vec2 consumes the raw
 * 16 kHz waveform with per-utterance zero-mean/unit-variance normalization.
 * Model + vocab are extracted by ModelAssetManager; paths are injected via
 * [setModelPath]/[setVocabPath] the same way MedAsrEngine does.
 */
object MmsLugandaAsrEngine {

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var idToToken: Map<Int, String> = emptyMap()
    private var blankId: Int = 0
    private var inputNames: Set<String> = emptySet()

    private var modelPath: String = ""
    private var vocabPath: String = ""

    fun setModelPath(path: String) {
        if (session != null && modelPath != path) release()
        modelPath = path
    }

    fun setVocabPath(path: String) {
        if (idToToken.isNotEmpty() && vocabPath != path) idToToken = emptyMap()
        vocabPath = path
    }

    fun isAvailable(): Boolean = File(modelPath).exists() && File(vocabPath).exists()

    /**
     * Transcribe PCM16 mono 16 kHz audio bytes to Luganda text.
     */
    fun transcribe(pcmBytes: ByteArray): String {
        ensureLoaded()
        val audio = pcm16ToFloat(pcmBytes)
        if (audio.isEmpty()) return ""
        Log.d(TAG, "Audio: ${audio.size} samples (${audio.size / 16000f}s)")

        val normalized = normalize(audio)
        val shape = longArrayOf(1L, normalized.size.toLong())
        val env = ortEnv!!
        val inputValues = OnnxTensor.createTensor(env, FloatBuffer.wrap(normalized), shape)
        val inputs = linkedMapOf<String, OnnxTensor>()
        val primaryInput = when {
            "input_values" in inputNames -> "input_values"
            inputNames.isNotEmpty() -> inputNames.first()
            else -> "input_values"
        }
        inputs[primaryInput] = inputValues

        val attentionMask = if ("attention_mask" in inputNames) {
            val mask = LongArray(normalized.size) { 1L }
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).also { inputs["attention_mask"] = it }
        } else null

        return try {
            session!!.run(inputs).use { results ->
                @Suppress("UNCHECKED_CAST")
                val logits = results[0].value as Array<Array<FloatArray>>
                val text = decodeGreedy(logits[0])
                Log.d(TAG, "Luganda transcription: ${text.length} chars")
                text
            }
        } finally {
            inputValues.close()
            attentionMask?.close()
        }
    }

    private fun ensureLoaded() {
        if (session != null) return
        Log.d(TAG, "Loading MMS Luganda ASR from $modelPath")
        ortEnv = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        }
        session = ortEnv!!.createSession(modelPath, opts)
        inputNames = session!!.inputNames

        val root = JSONObject(File(vocabPath).readText())
        // vocab.json is either flat token→id or the MMS multilingual map keyed by "lug"
        val vocab = root.optJSONObject("lug") ?: root
        val map = mutableMapOf<Int, String>()
        for (token in vocab.keys()) map[vocab.getInt(token)] = token
        idToToken = map
        blankId = map.entries.firstOrNull { it.value == "<pad>" }?.key ?: 0
        Log.d(TAG, "MMS ASR loaded; inputs=$inputNames vocab=${idToToken.size} blank=$blankId")
    }

    private fun pcm16ToFloat(pcmBytes: ByteArray): FloatArray {
        val shortBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val result = FloatArray(shortBuf.remaining())
        for (i in result.indices) result[i] = shortBuf.get(i) / 32768.0f
        return result
    }

    /** Wav2Vec2 feature extractor: zero-mean, unit-variance over the utterance. */
    private fun normalize(input: FloatArray): FloatArray {
        var sum = 0.0
        for (v in input) sum += v
        val mean = (sum / input.size).toFloat()
        var sq = 0.0
        for (v in input) {
            val d = v - mean
            sq += (d * d).toDouble()
        }
        val std = sqrt(sq / input.size).toFloat().coerceAtLeast(1e-7f)
        return FloatArray(input.size) { i -> (input[i] - mean) / std }
    }

    /** CTC greedy decode: argmax per frame, collapse repeats, drop blank/specials. */
    private fun decodeGreedy(frames: Array<FloatArray>): String {
        val sb = StringBuilder()
        var prev = -1
        for (frame in frames) {
            var bestId = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in frame.indices) {
                if (frame[i] > bestScore) {
                    bestScore = frame[i]
                    bestId = i
                }
            }
            if (bestId != prev && bestId != blankId) {
                val token = idToToken[bestId]
                if (token != null && !token.startsWith("<")) {
                    sb.append(if (token == "|") ' ' else token)
                }
            }
            prev = bestId
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun release() {
        session?.close()
        session = null
        ortEnv = null
        idToToken = emptyMap()
        Log.d(TAG, "MMS Luganda ASR released")
    }
}
