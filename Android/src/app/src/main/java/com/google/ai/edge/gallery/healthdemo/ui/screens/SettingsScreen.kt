package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.google.ai.edge.gallery.healthdemo.data.AppSettings
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.UgandaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val NavyBlue = Color(0xFF0D1B5E)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    repository: HealthDemoRepository? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var llmModelName by remember { mutableStateOf(AppSettings.getLlmModelName(context)) }
    var asrModelName by remember { mutableStateOf(AppSettings.getAsrModelName(context)) }
    var tokenizerName by remember { mutableStateOf(AppSettings.getTokenizerName(context)) }
    var selectedRole by remember { mutableStateOf(AppSettings.getRole(context)) }
    var isCopying by remember { mutableStateOf(false) }
    var copyingLabel by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteResult by remember { mutableStateOf<String?>(null) }
    var syncDisabled by remember { mutableStateOf(UgandaApi.isSyncDisabled(context)) }

    // LLM model picker
    val llmPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val fileName = AppSettings.getFileNameFromUri(context, uri)
            if (!fileName.endsWith(".gguf", ignoreCase = true)) return@rememberLauncherForActivityResult
            isCopying = true
            copyingLabel = "Copying LLM model..."
            Thread {
                val path = AppSettings.copyFileToAppStorage(context, uri, "models", fileName)
                if (path != null) {
                    AppSettings.saveLlmModel(context, path, fileName)
                    llmModelName = fileName
                }
                isCopying = false
            }.start()
        }
    }

    // ASR model picker
    val asrPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val fileName = AppSettings.getFileNameFromUri(context, uri)
            if (!fileName.endsWith(".onnx", ignoreCase = true)) return@rememberLauncherForActivityResult
            isCopying = true
            copyingLabel = "Copying ASR model..."
            Thread {
                val path = AppSettings.copyFileToAppStorage(context, uri, "asr_models", fileName)
                if (path != null) {
                    AppSettings.saveAsrModel(context, path, fileName)
                    asrModelName = fileName
                }
                isCopying = false
            }.start()
        }
    }

    // Tokenizer picker
    val tokenizerPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val fileName = AppSettings.getFileNameFromUri(context, uri)
            if (!fileName.endsWith(".json", ignoreCase = true)) return@rememberLauncherForActivityResult
            isCopying = true
            copyingLabel = "Copying tokenizer..."
            Thread {
                val path = AppSettings.copyFileToAppStorage(context, uri, "asr_models", fileName)
                if (path != null) {
                    AppSettings.saveTokenizer(context, path, fileName)
                    tokenizerName = fileName
                }
                isCopying = false
            }.start()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            // Copying indicator
            if (isCopying) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFF3E0),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFE65100))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(copyingLabel, fontSize = 13.sp, color = Color(0xFFE65100))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // --- LLM Model ---
            Text("AI Model (GGUF)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(4.dp))
            Text("The language model used for clinical assessments.", fontSize = 13.sp, color = Color(0xFF666666))
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { llmPickerLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    llmModelName ?: "Select .gguf model file",
                    color = if (llmModelName != null) Color(0xFF2E7D32) else Color(0xFF666666)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- ASR Model ---
            Text("Speech Recognition Model (ONNX)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(4.dp))
            Text("The medical ASR model used for voice note transcription.", fontSize = 13.sp, color = Color(0xFF666666))
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { asrPickerLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    asrModelName ?: "Select .onnx model file",
                    color = if (asrModelName != null) Color(0xFF2E7D32) else Color(0xFF666666)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- ASR Tokenizer ---
            Text("ASR Tokenizer (JSON)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(4.dp))
            Text("The tokenizer file for the speech recognition model.", fontSize = 13.sp, color = Color(0xFF666666))
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { tokenizerPickerLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    tokenizerName ?: "Select tokenizer.json file",
                    color = if (tokenizerName != null) Color(0xFF2E7D32) else Color(0xFF666666)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Role ---
            Text("Your Role", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(4.dp))
            Text("Select the role you work in. This is remembered across sessions.", fontSize = 13.sp, color = Color(0xFF666666))
            Spacer(modifier = Modifier.height(8.dp))

            PatientRole.entries.forEach { role ->
                val isSelected = selectedRole == role.label
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable {
                            selectedRole = role.label
                            AppSettings.saveRole(context, role.label)
                        }
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFF2E7D32) else Color(0xFFE0E0E0),
                            RoundedCornerShape(8.dp)
                        ),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) Color(0xFFE8F5E9) else Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(role.label, fontSize = 15.sp, color = Color(0xFF1F1F1F), modifier = Modifier.weight(1f))
                        if (isSelected) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Sync status (only visible when sync is blocked) ---
            if (syncDisabled) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFF3E0),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Sync paused",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE65100),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The server rejected this phone's sync token. Your assessments " +
                                "are safe on the phone. Tap Reset sync to re-enrol — your " +
                                "existing assessments will be uploaded under a new device ID.",
                            fontSize = 13.sp,
                            color = Color(0xFF424242),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                UgandaApi.resetIdentity(context)
                                syncDisabled = false
                                // Trigger a backfill so the user sees the
                                // "Synced" indicator flip immediately rather
                                // than waiting for the next save or cold start.
                                repository?.triggerBackfill()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Reset sync", color = Color(0xFFE65100))
                        }
                    }
                }
            }

            // --- Delete my data (DPPA §7 — right to erasure) ---
            if (repository != null) {
                Text(
                    "Data & Privacy",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F1F1F),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Erase every record this phone has sent to the Ease Health server. " +
                        "Your local history on this phone is also cleared. This cannot be undone.",
                    fontSize = 13.sp,
                    color = Color(0xFF666666),
                )
                Spacer(modifier = Modifier.height(8.dp))

                deleteResult?.let { msg ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (msg.startsWith("Error") || msg.startsWith("Offline")) Color(0xFFFFEBEE) else Color(0xFFE8F5E9),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Text(
                            msg,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp,
                            color = if (msg.startsWith("Error") || msg.startsWith("Offline")) Color(0xFFC62828) else Color(0xFF2E7D32),
                        )
                    }
                }

                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !deleting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        if (deleting) "Deleting…" else "Delete my data",
                        color = Color(0xFFC62828),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Done button
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .height(52.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NavyBlue)
        ) {
            Text("Done", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }

    if (showDeleteDialog && repository != null) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteDialog = false },
            title = { Text("Delete my data?") },
            text = {
                Text(
                    "This will erase every record this phone has sent to the server and " +
                        "wipe your local history. This cannot be undone. De-identified analytics " +
                        "from previous days may remain — those cannot be linked back to you."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        deleteResult = null
                        coroutineScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                repository.deleteAllMyData()
                            }
                            deleting = false
                            showDeleteDialog = false
                            deleteResult = if (ok) {
                                "Data erased from the server and this device."
                            } else {
                                "Offline — local data wiped, but the server copy was not erased. Try again when connected."
                            }
                        }
                    },
                ) {
                    Text("Delete", color = Color(0xFFC62828))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = { showDeleteDialog = false },
                ) { Text("Cancel") }
            },
        )
    }
}
