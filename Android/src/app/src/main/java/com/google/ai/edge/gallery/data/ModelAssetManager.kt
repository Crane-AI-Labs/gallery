package com.google.ai.edge.gallery.data

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Extracts bundled model assets from the APK to internal storage on first launch.
 *
 * Large models (>500MB) are split into chunks in assets/ to work around the
 * Android build system's Java array size limit. This class reassembles them
 * during extraction.
 *
 * Files are stored uncompressed in the APK (via noCompress in build.gradle.kts).
 */
object ModelAssetManager {

    private const val TAG = "ModelAssetManager"
    private const val MODELS_DIR = "models"
    private const val VERSION_KEY = "model_asset_version"
    private const val CURRENT_VERSION = 7  // v7 reverts Q3_K_M -> Q4_0. Q3_K_M was a side-quest for A06-class 3.7 GB devices, but A06 still mmap-thrashed even on Q3_K_M (Cortex-A55 page-cache can't fit a 2 GB model). A26+ is the practical floor; Q4_0 ships better triage quality on the devices that actually run inference.

    // Final model filenames (after reassembly)
    const val LLM_MODEL = "medgemma-v2.1-instruct-Q4_0.gguf"
    const val VISION_MODEL = "medgemma-mmproj-Q8_0.gguf"
    const val ASR_MODEL = "medasr-fp32.onnx"
    const val ASR_TOKENIZER = "medasr-tokenizer.json"

    // ganda flavor: MMS Luganda ASR (wav2vec2 CTC) + Ganda Gemma 1B LUG→EN
    // draft translator. Only the tiny vocab ships in the APK — the two big
    // models would push the archive past the Zip32 4 GiB limit, so
    // GandaModelDownloader fetches them into the same models/ dir on first run.
    const val MMS_ASR_MODEL = "mms-lug-asr.onnx"
    const val MMS_ASR_VOCAB = "mms-lug-vocab.json"
    const val GANDA_LLM = "ganda-gemma-1b-Q4_K_M.gguf"

    /** Bundled asset set differs per flavor — medasr on standard, MMS vocab on ganda. */
    private val expectedModels: List<String> =
        if (BuildConfig.FLAVOR == "ganda")
            listOf(LLM_MODEL, VISION_MODEL, MMS_ASR_VOCAB)
        else
            listOf(LLM_MODEL, VISION_MODEL, ASR_MODEL, ASR_TOKENIZER)

    /**
     * Files that live in models/ but arrive via GandaModelDownloader, not APK
     * extraction. The stale-file sweep must never delete these (or their .tmp
     * resume files) — they cost the clinic 1.75 GB of WiFi to re-fetch.
     */
    private val downloadedModels: Set<String> =
        if (BuildConfig.FLAVOR == "ganda") setOf(MMS_ASR_MODEL, GANDA_LLM) else emptySet()

    /**
     * Each entry: output filename -> list of asset chunk names.
     * Single-file assets have one chunk matching the filename.
     * Split assets have chunks named <filename>.partaa, .partab, etc.
     */
    private data class AssetEntry(val outputName: String, val chunks: List<String>)

    private fun discoverAssets(context: Context): List<AssetEntry> {
        val allAssets = context.assets.list("")?.toSet() ?: emptySet()

        return expectedModels.map { name ->
            // Check for split chunks: <name>.partaa, .partab, etc.
            val chunks = allAssets.filter { it.startsWith("$name.part") }.sorted()
            if (chunks.isNotEmpty()) {
                AssetEntry(name, chunks)
            } else if (name in allAssets) {
                AssetEntry(name, listOf(name))
            } else {
                Log.w(TAG, "Asset not found: $name (no chunks either)")
                AssetEntry(name, emptyList())
            }
        }
    }

    /** Returns the directory where extracted models live. */
    fun modelsDir(context: Context): File = File(context.filesDir, MODELS_DIR)

    /** Returns the full path for a given model file. */
    fun getModelPath(context: Context, assetName: String): String =
        File(modelsDir(context), assetName).absolutePath

    /** Check if all models are already extracted and up to date. */
    fun isReady(context: Context): Boolean {
        val prefs = context.getSharedPreferences("model_assets", Context.MODE_PRIVATE)
        if (prefs.getInt(VERSION_KEY, 0) != CURRENT_VERSION) return false
        val dir = modelsDir(context)
        return expectedModels.all { File(dir, it).exists() }
    }

    /**
     * Extract all model assets to internal storage, reassembling split chunks.
     * @param onProgress callback with (fileName, fileIndex, totalFiles, bytesWritten, totalBytes)
     */
    fun extractAll(
        context: Context,
        onProgress: ((fileName: String, fileIndex: Int, totalFiles: Int, bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val dir = modelsDir(context)
        dir.mkdirs()

        // Sweep stale model files left by older versions (e.g. the 2.3 GB
        // medgemma-v5b-Q4_0.gguf from v1.0.2). Anything in models/ that isn't
        // in the current expected set is removed before we start extracting,
        // so the device doesn't end up carrying two full LLM copies.
        val expected = expectedModels.toSet() + downloadedModels +
            downloadedModels.map { "$it.tmp" }
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name !in expected) {
                val size = f.length()
                if (f.delete()) {
                    Log.d(TAG, "Removed stale ${f.name} (${size / 1024 / 1024} MB)")
                }
            }
        }

        val entries = discoverAssets(context)

        // Calculate total size across all chunks
        val entrySizes = entries.map { entry ->
            entry.chunks.sumOf { chunk ->
                try { context.assets.openFd(chunk).use { it.length } }
                catch (e: Exception) { 0L }
            }
        }
        val totalBytes = entrySizes.sum()
        var cumulativeBytes = 0L

        for ((index, entry) in entries.withIndex()) {
            if (entry.chunks.isEmpty()) {
                Log.w(TAG, "Skipping ${entry.outputName} — no asset chunks found")
                continue
            }

            val destFile = File(dir, entry.outputName)
            val expectedSize = entrySizes[index]

            // Skip if already extracted and correct size
            if (destFile.exists() && destFile.length() == expectedSize) {
                Log.d(TAG, "Skipping ${entry.outputName} (already extracted, $expectedSize bytes)")
                cumulativeBytes += expectedSize
                onProgress?.invoke(entry.outputName, index, entries.size, cumulativeBytes, totalBytes)
                continue
            }

            Log.d(TAG, "Extracting ${entry.outputName} from ${entry.chunks.size} chunk(s) ($expectedSize bytes)...")
            val startMs = System.currentTimeMillis()

            FileOutputStream(destFile).use { output ->
                for (chunk in entry.chunks) {
                    context.assets.open(chunk).use { input ->
                        val buffer = ByteArray(8 * 1024 * 1024) // 8MB buffer
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            cumulativeBytes += bytesRead
                            onProgress?.invoke(entry.outputName, index, entries.size, cumulativeBytes, totalBytes)
                        }
                    }
                }
            }

            val durationMs = System.currentTimeMillis() - startMs
            val mbPerSec = if (durationMs > 0) expectedSize / 1024.0 / 1024.0 / (durationMs / 1000.0) else 0.0
            Log.d(TAG, "Extracted ${entry.outputName} in ${durationMs}ms (${String.format("%.1f", mbPerSec)} MB/s)")
        }

        // Mark extraction complete
        context.getSharedPreferences("model_assets", Context.MODE_PRIVATE)
            .edit()
            .putInt(VERSION_KEY, CURRENT_VERSION)
            .apply()

        Log.d(TAG, "All models extracted to ${dir.absolutePath}")
    }
}
