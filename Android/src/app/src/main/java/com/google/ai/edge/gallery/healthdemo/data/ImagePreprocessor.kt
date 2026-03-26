package com.google.ai.edge.gallery.healthdemo.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Preprocesses images before sending to the vision encoder.
 * Resizes to a reasonable resolution to reduce memory usage and speed up inference.
 */
object ImagePreprocessor {

    private const val MAX_DIMENSION = 768

    /**
     * Decode, resize, and re-encode image bytes.
     * Returns JPEG bytes at the target resolution, or null if decoding fails.
     */
    fun preprocess(rawBytes: ByteArray): ByteArray? {
        // Decode to get dimensions without loading full bitmap
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)

        val origWidth = options.outWidth
        val origHeight = options.outHeight
        if (origWidth <= 0 || origHeight <= 0) return null

        // Calculate sample size for initial downscale (fast, power-of-2)
        var sampleSize = 1
        while (origWidth / sampleSize > MAX_DIMENSION * 2 || origHeight / sampleSize > MAX_DIMENSION * 2) {
            sampleSize *= 2
        }

        // Decode with sample size
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) ?: return null

        // Scale to max dimension if still too large
        val bitmap = if (sampled.width > MAX_DIMENSION || sampled.height > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / maxOf(sampled.width, sampled.height)
            val newWidth = (sampled.width * scale).toInt()
            val newHeight = (sampled.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(sampled, newWidth, newHeight, true)
            if (scaled !== sampled) sampled.recycle()
            scaled
        } else {
            sampled
        }

        // Re-encode as JPEG
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()

        return output.toByteArray()
    }
}
