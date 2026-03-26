package com.google.ai.edge.gallery.healthdemo.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "MedAsrEngine"
private const val MODEL_PATH = "/data/local/tmp/medasr-fp32.onnx"
private const val TOKENIZER_PATH = "/data/local/tmp/medasr-tokenizer.json"

// From processor_config.json
private const val SAMPLE_RATE = 16000
private const val N_FFT = 512
private const val HOP_LENGTH = 160
private const val WIN_LENGTH = 400
private const val N_MELS = 128

/**
 * On-device Medical ASR engine using ONNX Runtime.
 * Converts audio to mel spectrogram features, runs inference, decodes tokens to text.
 */
object MedAsrEngine {

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var vocabulary: List<String> = emptyList()
    private var melFilterbank: Array<FloatArray>? = null

    fun isAvailable(): Boolean {
        return File(MODEL_PATH).exists() && File(TOKENIZER_PATH).exists()
    }

    /**
     * Transcribe PCM16 audio bytes to text.
     * @param pcmBytes Raw PCM 16-bit mono audio at 16kHz
     * @return Transcribed text
     */
    fun transcribe(pcmBytes: ByteArray): String {
        ensureLoaded()

        // Convert PCM16 to float32
        val audio = pcm16ToFloat(pcmBytes)
        Log.d(TAG, "Audio: ${audio.size} samples (${audio.size / SAMPLE_RATE.toFloat()}s)")

        // Compute mel spectrogram
        val melSpec = computeMelSpectrogram(audio)
        val nFrames = melSpec[0].size
        Log.d(TAG, "Mel spectrogram: $N_MELS x $nFrames")

        // Prepare input tensors: [1, time, 128]
        val inputFeatures = Array(1) { Array(nFrames) { frame -> FloatArray(N_MELS) { mel -> melSpec[mel][frame] } } }
        val attentionMask = Array(1) { BooleanArray(nFrames) { true } }

        val env = ortEnv!!
        val inputTensor = OnnxTensor.createTensor(env, inputFeatures)
        val maskTensor = OnnxTensor.createTensor(env, attentionMask)

        val inputs = mapOf(
            "input_features" to inputTensor,
            "attention_mask" to maskTensor,
        )

        // Run inference
        val results = session!!.run(inputs)
        val logits = results.get(0).value as Array<Array<FloatArray>> // [1, time, 512]

        inputTensor.close()
        maskTensor.close()

        // Decode: CTC greedy decoding (argmax per frame, collapse repeats, remove blanks)
        val tokenIds = logits[0].map { frame ->
            frame.indices.maxByOrNull { frame[it] } ?: 0
        }

        val text = ctcDecode(tokenIds)
        results.close()

        Log.d(TAG, "Transcription: $text")
        return text
    }

    private fun ensureLoaded() {
        if (session != null) return

        Log.d(TAG, "Loading MedASR model...")
        ortEnv = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        session = ortEnv!!.createSession(MODEL_PATH, opts)
        Log.d(TAG, "MedASR model loaded")

        // Load vocabulary from tokenizer.json
        loadVocabulary()

        // Precompute mel filterbank
        melFilterbank = createMelFilterbank(SAMPLE_RATE, N_FFT, N_MELS)
        Log.d(TAG, "Mel filterbank ready")
    }

    private fun loadVocabulary() {
        val json = JSONObject(File(TOKENIZER_PATH).readText())
        val model = json.getJSONObject("model")
        val vocab = model.getJSONArray("vocab")
        val tokens = mutableListOf<String>()
        for (i in 0 until vocab.length()) {
            val entry = vocab.getJSONArray(i)
            tokens.add(entry.getString(0))
        }
        vocabulary = tokens
        Log.d(TAG, "Loaded vocabulary: ${vocabulary.size} tokens")
    }

    private fun pcm16ToFloat(pcmBytes: ByteArray): FloatArray {
        val shortBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val result = FloatArray(shortBuf.remaining())
        for (i in result.indices) {
            result[i] = shortBuf.get(i) / 32768.0f
        }
        return result
    }

    /**
     * CTC greedy decoding: take argmax per frame, collapse consecutive duplicates, remove blank token (id=0).
     */
    private fun ctcDecode(tokenIds: List<Int>): String {
        val collapsed = mutableListOf<Int>()
        var prev = -1
        for (id in tokenIds) {
            if (id != prev) {
                collapsed.add(id)
                prev = id
            }
        }

        // Token 0 is <epsilon> (blank), skip it. Also skip <s>=1, </s>=2, <unk>=3
        val textTokens = collapsed.filter { it > 3 && it < vocabulary.size }

        val sb = StringBuilder()
        for (id in textTokens) {
            val token = vocabulary[id]
            // SentencePiece: ▁ = word boundary (space)
            sb.append(token.replace("▁", " "))
        }

        return sb.toString().trim()
    }

    // --- Mel Spectrogram computation ---

    private fun computeMelSpectrogram(audio: FloatArray): Array<FloatArray> {
        val window = hannWindow(WIN_LENGTH)
        val nFrames = (audio.size - WIN_LENGTH) / HOP_LENGTH + 1
        if (nFrames <= 0) return Array(N_MELS) { floatArrayOf() }

        val filterbank = melFilterbank!!
        val melSpec = Array(N_MELS) { FloatArray(nFrames) }

        val fftReal = FloatArray(N_FFT)
        val fftImag = FloatArray(N_FFT)
        val powerSpec = FloatArray(N_FFT / 2 + 1)

        for (frame in 0 until nFrames) {
            val offset = frame * HOP_LENGTH

            // Window the frame
            for (i in 0 until N_FFT) {
                fftReal[i] = if (i < WIN_LENGTH && offset + i < audio.size) {
                    audio[offset + i] * window[i]
                } else 0f
                fftImag[i] = 0f
            }

            // FFT
            fft(fftReal, fftImag, N_FFT)

            // Power spectrum
            for (i in powerSpec.indices) {
                powerSpec[i] = fftReal[i] * fftReal[i] + fftImag[i] * fftImag[i]
            }

            // Apply mel filterbank
            for (mel in 0 until N_MELS) {
                var sum = 0f
                for (k in powerSpec.indices) {
                    sum += filterbank[mel][k] * powerSpec[k]
                }
                melSpec[mel][frame] = ln(max(sum, 1e-10f))
            }
        }

        return melSpec
    }

    private fun hannWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))).toFloat()
        }
    }

    /**
     * In-place Cooley-Tukey FFT. Assumes n is a power of 2.
     */
    private fun fft(real: FloatArray, imag: FloatArray, n: Int) {
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        // FFT
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wReal = cos(ang).toFloat()
            val wImag = kotlin.math.sin(ang).toFloat()

            var i = 0
            while (i < n) {
                var curReal = 1.0f
                var curImag = 0.0f
                for (k in 0 until len / 2) {
                    val uReal = real[i + k]
                    val uImag = imag[i + k]
                    val vReal = real[i + k + len / 2] * curReal - imag[i + k + len / 2] * curImag
                    val vImag = real[i + k + len / 2] * curImag + imag[i + k + len / 2] * curReal
                    real[i + k] = uReal + vReal
                    imag[i + k] = uImag + vImag
                    real[i + k + len / 2] = uReal - vReal
                    imag[i + k + len / 2] = uImag - vImag
                    val newCurReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = newCurReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Create mel filterbank matrix [n_mels, n_fft/2+1].
     */
    private fun createMelFilterbank(sampleRate: Int, nFft: Int, nMels: Int): Array<FloatArray> {
        val fMin = 0.0
        val fMax = sampleRate / 2.0
        val nFreqs = nFft / 2 + 1

        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melPoints = DoubleArray(nMels + 2) { i ->
            melToHz(melMin + (melMax - melMin) * i / (nMels + 1))
        }

        // Convert Hz points to FFT bin indices
        val binPoints = melPoints.map { hz -> ((nFft + 1) * hz / sampleRate).toInt() }

        val filterbank = Array(nMels) { FloatArray(nFreqs) }
        for (m in 0 until nMels) {
            for (k in binPoints[m]..min(binPoints[m + 1], nFreqs - 1)) {
                if (binPoints[m + 1] > binPoints[m]) {
                    filterbank[m][k] = (k - binPoints[m]).toFloat() / (binPoints[m + 1] - binPoints[m])
                }
            }
            for (k in binPoints[m + 1]..min(binPoints[m + 2], nFreqs - 1)) {
                if (binPoints[m + 2] > binPoints[m + 1]) {
                    filterbank[m][k] = (binPoints[m + 2] - k).toFloat() / (binPoints[m + 2] - binPoints[m + 1])
                }
            }
        }
        return filterbank
    }

    fun release() {
        session?.close()
        session = null
        ortEnv?.close()
        ortEnv = null
        Log.d(TAG, "MedASR engine released")
    }
}
