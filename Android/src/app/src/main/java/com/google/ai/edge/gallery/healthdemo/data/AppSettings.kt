package com.google.ai.edge.gallery.healthdemo.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Persistent app settings stored in SharedPreferences.
 * Manages LLM model path, ASR model path, and user role.
 */
object AppSettings {
    private const val PREFS_NAME = "ease_health_settings"

    private const val KEY_LLM_MODEL_PATH = "llm_model_path"
    private const val KEY_LLM_MODEL_NAME = "llm_model_name"
    private const val KEY_ASR_MODEL_PATH = "asr_model_path"
    private const val KEY_ASR_MODEL_NAME = "asr_model_name"
    private const val KEY_TOKENIZER_PATH = "tokenizer_path"
    private const val KEY_TOKENIZER_NAME = "tokenizer_name"
    private const val KEY_ROLE = "user_role"
    private const val KEY_CONSENT_VERSION = "consent_version_accepted"
    private const val KEY_LAST_ACTIVE = "last_active_ms"
    private const val KEY_FAST_IMAGE_MODE = "fast_image_mode"

    /**
     * Reduced-resolution ("fast") image analysis. When on, the vision encoder
     * runs at [FAST_IMAGE_SIZE] px instead of the model's native 896 px, cutting
     * the on-device image-encode ~4x at the cost of fine visual detail. Off by
     * default — this is a clinical-quality tradeoff to be validated per site.
     */
    const val FAST_IMAGE_SIZE = 448

    fun isFastImageMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FAST_IMAGE_MODE, false)

    fun setFastImageMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FAST_IMAGE_MODE, enabled).apply()
    }

    /** ViT input size to use given the setting: reduced size, or 0 = model default. */
    fun visionImageSize(context: Context): Int =
        if (isFastImageMode(context)) FAST_IMAGE_SIZE else 0

    /**
     * July 2026 pipeline note 3.8: mark this device's records as test traffic.
     * Synced as `is_test` on every assessment so analytics can exclude in-house
     * QA rows cleanly instead of filtering by date. Off by default; enable on
     * office/demo devices only.
     */
    private const val KEY_TEST_DEVICE = "test_device"

    fun isTestDevice(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TEST_DEVICE, false)

    fun setTestDevice(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TEST_DEVICE, enabled).apply()
    }

    /**
     * Bump this when the consent copy changes materially (new data practices,
     * new processors, broader sharing). A user who has accepted version N
     * will be re-prompted when the stored value differs from [CURRENT_CONSENT_VERSION].
     *
     * v2: explicit model name + version, right-to-erasure entry point, TLS
     * pinning notice.
     */
    const val CURRENT_CONSENT_VERSION = 2

    /** Idle timeout before the landing screen re-confirms who's on shift. */
    const val IDLE_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- LLM Model ---

    fun getLlmModelPath(context: Context): String? =
        prefs(context).getString(KEY_LLM_MODEL_PATH, null)

    fun getLlmModelName(context: Context): String? =
        prefs(context).getString(KEY_LLM_MODEL_NAME, null)

    fun saveLlmModel(context: Context, path: String, name: String) {
        prefs(context).edit()
            .putString(KEY_LLM_MODEL_PATH, path)
            .putString(KEY_LLM_MODEL_NAME, name)
            .apply()
    }

    // --- ASR Model ---

    fun getAsrModelPath(context: Context): String? =
        prefs(context).getString(KEY_ASR_MODEL_PATH, null)

    fun getAsrModelName(context: Context): String? =
        prefs(context).getString(KEY_ASR_MODEL_NAME, null)

    fun saveAsrModel(context: Context, path: String, name: String) {
        prefs(context).edit()
            .putString(KEY_ASR_MODEL_PATH, path)
            .putString(KEY_ASR_MODEL_NAME, name)
            .apply()
    }

    // --- ASR Tokenizer ---

    fun getTokenizerPath(context: Context): String? =
        prefs(context).getString(KEY_TOKENIZER_PATH, null)

    fun getTokenizerName(context: Context): String? =
        prefs(context).getString(KEY_TOKENIZER_NAME, null)

    fun saveTokenizer(context: Context, path: String, name: String) {
        prefs(context).edit()
            .putString(KEY_TOKENIZER_PATH, path)
            .putString(KEY_TOKENIZER_NAME, name)
            .apply()
    }

    // --- Role ---

    fun getRole(context: Context): String? =
        prefs(context).getString(KEY_ROLE, null)

    fun saveRole(context: Context, role: String?) {
        prefs(context).edit()
            .putString(KEY_ROLE, role)
            .apply()
    }

    // --- Consent (DPPA §9 + §27) ---

    fun hasAcceptedConsent(context: Context): Boolean =
        prefs(context).getInt(KEY_CONSENT_VERSION, 0) >= CURRENT_CONSENT_VERSION

    fun acceptConsent(context: Context) {
        prefs(context).edit().putInt(KEY_CONSENT_VERSION, CURRENT_CONSENT_VERSION).apply()
    }

    // --- Idle / shift-change tracking ---

    fun touchLastActive(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_ACTIVE, System.currentTimeMillis()).apply()
    }

    fun isIdleTimeoutExceeded(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_ACTIVE, 0L)
        return last > 0L && System.currentTimeMillis() - last > IDLE_TIMEOUT_MS
    }

    // --- File helpers ---

    fun copyFileToAppStorage(context: Context, uri: Uri, subDir: String, fileName: String): String? {
        return try {
            val destDir = File(context.getExternalFilesDir(null), subDir)
            destDir.mkdirs()
            val destFile = File(destDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "model"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}
