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

    fun saveRole(context: Context, role: String) {
        prefs(context).edit()
            .putString(KEY_ROLE, role)
            .apply()
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
