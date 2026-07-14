package com.google.ai.edge.gallery.healthdemo.data

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.ModelAssetManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val TAG = "GandaModelDownloader"

/**
 * ganda flavor: fetches the two Luganda models on first run.
 *
 * They cannot ship inside the APK — adding their 1.75 GB to the existing
 * 3.3 GiB of MedGemma assets blows through the Zip32 4 GiB APK limit
 * (packageGandaRelease fails with "Zip32 cannot place CD entry"). So the APK
 * carries only the tiny vocab and these two are downloaded once into the same
 * models/ dir that ModelAssetManager extracts into, then verified by SHA-256
 * before being renamed into place. Partial downloads resume via HTTP Range.
 *
 *  - Ganda Gemma 1B Q4_K_M: public HuggingFace release (sha verified against
 *    CraneAILabs/ganda-gemma-1b-GGUF — byte-identical to the open-voice copy).
 *  - MMS Luganda ASR INT8 ONNX: the HF repo is private, so this is served
 *    from infrastructure we control (Uganda VM nginx — DPPA-friendly,
 *    in-country). Path must exist on the VM before first field use.
 */
object GandaModelDownloader {

    private data class Spec(
        val fileName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    private val SPECS = listOf(
        Spec(
            fileName = ModelAssetManager.GANDA_LLM,
            url = "https://huggingface.co/CraneAILabs/ganda-gemma-1b-GGUF/resolve/main/Q4_K_M/ganda-gemma-1b-q4_k_m.gguf",
            sha256 = "12e8fce498aa4aaa02f00412d0870f0e39b2d18d7f73cb35c523ead6f1bf2370",
            sizeBytes = 806_057_856L,
        ),
        Spec(
            fileName = ModelAssetManager.MMS_ASR_MODEL,
            url = "https://41.220.3.234/models/ganda/mms-lug-asr.onnx",
            sha256 = "58bd8fd4bb62c16c5a872ee9d05c862b5b7503f83c8a650c7db5bf6a01897352",
            sizeBytes = 1_024_685_718L,
        ),
    )

    @Volatile var isDownloading: Boolean = false
        private set

    private fun isPresent(context: Context, spec: Spec): Boolean {
        val f = File(ModelAssetManager.modelsDir(context), spec.fileName)
        return f.exists() && f.length() == spec.sizeBytes
    }

    /** True when both Luganda models are present and complete. */
    fun modelsReady(context: Context): Boolean = SPECS.all { isPresent(context, it) }

    /**
     * Human-readable names of the models still missing — so UI messages can
     * say exactly which file is absent instead of a blanket "models not
     * downloaded" (which reads as a lie when one of them IS downloaded).
     */
    fun missingModels(context: Context): List<String> =
        SPECS.filterNot { isPresent(context, it) }.map { it.fileName }

    /**
     * Download any missing model. Safe to call repeatedly — no-ops when both
     * files are in place, refuses to run concurrently with itself. Blocking;
     * call from a background coroutine.
     */
    @Synchronized
    fun ensureModels(context: Context) {
        if (modelsReady(context)) return
        isDownloading = true
        try {
            val dir = ModelAssetManager.modelsDir(context)
            dir.mkdirs()
            for (spec in SPECS) {
                val dest = File(dir, spec.fileName)
                if (dest.exists() && dest.length() == spec.sizeBytes) continue
                try {
                    download(spec, dest)
                } catch (e: Exception) {
                    // Keep the .tmp for HTTP-Range resume on the next attempt.
                    Log.w(TAG, "Download failed for ${spec.fileName}: ${e.message}")
                }
            }
        } finally {
            isDownloading = false
        }
    }

    private fun download(spec: Spec, dest: File) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        val existing = if (tmp.exists()) tmp.length() else 0L
        Log.d(TAG, "Downloading ${spec.fileName} (${spec.sizeBytes / 1024 / 1024} MB), resume from $existing")

        val conn = URL(spec.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

        val code = conn.responseCode
        val append = code == HttpURLConnection.HTTP_PARTIAL
        if (code != HttpURLConnection.HTTP_OK && !append) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code from ${spec.url}")
        }
        if (!append && existing > 0) tmp.delete()

        var written = if (append) existing else 0L
        var lastLoggedPct = -1
        conn.inputStream.use { input ->
            FileOutputStream(tmp, append).use { output ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    written += n
                    val pct = (written * 100 / spec.sizeBytes).toInt()
                    if (pct / 10 > lastLoggedPct / 10) {
                        lastLoggedPct = pct
                        Log.d(TAG, "${spec.fileName}: $pct% (${written / 1024 / 1024} MB)")
                    }
                }
            }
        }
        conn.disconnect()

        if (tmp.length() != spec.sizeBytes) {
            throw IllegalStateException("Size mismatch for ${spec.fileName}: ${tmp.length()} != ${spec.sizeBytes}")
        }
        val actual = sha256(tmp)
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            tmp.delete() // corrupt — do not resume from it
            throw IllegalStateException("SHA-256 mismatch for ${spec.fileName}")
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        Log.d(TAG, "Downloaded and verified ${spec.fileName}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
