package com.google.ai.edge.gallery.healthdemo.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val NavyBlue = Color(0xFF0D1B5E)

object ModelPreferences {
    private const val PREFS_NAME = "easy_health_model_prefs"
    private const val KEY_MODEL_PATH = "gguf_model_path"
    private const val KEY_MODEL_NAME = "gguf_model_name"

    fun getModelPath(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_PATH, null)
    }

    fun getModelName(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_NAME, null)
    }

    fun saveModel(context: Context, path: String, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL_PATH, path)
            .putString(KEY_MODEL_NAME, name)
            .apply()
    }

    /**
     * Copy a content URI to the app's internal files directory and return the path.
     * This is needed because ONNX/llama.cpp need a real file path, not a content URI.
     */
    fun copyModelToAppStorage(context: Context, uri: Uri, fileName: String): String? {
        return try {
            val destDir = File(context.getExternalFilesDir(null), "models")
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
        var name = "model.gguf"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}

@Composable
fun HealthDemoLandingScreen(onStartAssessment: () -> Unit) {
    val context = LocalContext.current
    var selectedModelName by remember { mutableStateOf(ModelPreferences.getModelName(context)) }
    var isCopying by remember { mutableStateOf(false) }

    var showWrongFileError by remember { mutableStateOf(false) }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val fileName = ModelPreferences.getFileNameFromUri(context, uri)
            if (!fileName.endsWith(".gguf", ignoreCase = true)) {
                showWrongFileError = true
                return@rememberLauncherForActivityResult
            }
            showWrongFileError = false
            isCopying = true
            Thread {
                val path = ModelPreferences.copyModelToAppStorage(context, uri, fileName)
                if (path != null) {
                    ModelPreferences.saveModel(context, path, fileName)
                    selectedModelName = fileName
                }
                isCopying = false
            }.start()
        }
    }

    Scaffold { innerPadding ->
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Settings button top-right
            IconButton(
                onClick = { modelPickerLauncher.launch("*/*") },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Select Model",
                    tint = Color(0xFF444746),
                    modifier = Modifier.size(28.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Ease Health",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F1F1F),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Supporting better clinical support.",
                    fontSize = 16.sp,
                    color = Color(0xFF444746),
                    textAlign = TextAlign.Center
                )

                // Show selected model or status
                Spacer(modifier = Modifier.height(8.dp))
                if (isCopying) {
                    Text(
                        text = "Copying model to app storage...",
                        fontSize = 13.sp,
                        color = Color(0xFFE65100),
                        textAlign = TextAlign.Center
                    )
                } else if (showWrongFileError) {
                    Text(
                        text = "Please select a .gguf model file.",
                        fontSize = 13.sp,
                        color = Color(0xFFD32F2F),
                        textAlign = TextAlign.Center
                    )
                } else if (selectedModelName != null) {
                    Text(
                        text = "Model: $selectedModelName",
                        fontSize = 13.sp,
                        color = Color(0xFF2E7D32),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = onStartAssessment,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NavyBlue)
                ) {
                    Text(
                        text = "Start Assessment",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }
    }
}
