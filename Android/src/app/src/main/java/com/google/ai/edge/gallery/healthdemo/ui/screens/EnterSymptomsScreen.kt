package com.google.ai.edge.gallery.healthdemo.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.healthdemo.data.AgeRange
import com.google.ai.edge.gallery.healthdemo.data.CRITICAL_DANGER_SIGNS
import com.google.ai.edge.gallery.healthdemo.data.DurationUnit
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.VitalSigns
import com.google.ai.edge.gallery.healthdemo.data.WARNING_SIGNS
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel
import kotlinx.coroutines.launch

private val NavyBlue = Color(0xFF0D1B5E)
private val DangerRed = Color(0xFFD32F2F)
private val OrangeGold = Color(0xFFE6A817)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterSymptomsScreen(
    viewModel: HealthDemoViewModel,
    onContinue: () -> Unit,
    onViewSavedResults: () -> Unit,
    onSavePausedAndGoHome: (com.google.ai.edge.gallery.healthdemo.data.PausedConsultation) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val canContinue = uiState.symptoms.isNotBlank()
    val hasImage = uiState.capturedImageBytes != null

    var ageDropdownExpanded by remember { mutableStateOf(false) }
    var durationUnitExpanded by remember { mutableStateOf(false) }
    var showPauseSheet by remember { mutableStateOf(false) }

    val pauseSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dangerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    var audioPermissionGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> audioPermissionGranted = granted }

    LaunchedEffect(Unit) {
        audioPermissionGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                if (bytes != null) viewModel.setCapturedImage(bytes)
            } catch (e: Exception) {
                android.util.Log.e("EnterSymptoms", "Failed to read image", e)
            }
        }
    }

    val displayRole = if (uiState.role == PatientRole.Other && uiState.customRole.isNotBlank())
        uiState.customRole else uiState.role?.label ?: ""

    val confirmedDangerCount = uiState.confirmedSigns.count { it in CRITICAL_DANGER_SIGNS }
    val confirmedWarningCount = uiState.confirmedSigns.count { it in WARNING_SIGNS }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // Confirmed signs banner
            if (uiState.confirmedSigns.isNotEmpty()) {
                Surface(
                    color = if (confirmedDangerCount > 0) Color(0xFFFDE8E8) else Color(0xFFFFF3E0),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (confirmedDangerCount > 0) DangerRed else OrangeGold,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                buildString {
                                    if (confirmedDangerCount > 0) append("$confirmedDangerCount Critical Danger Sign${if (confirmedDangerCount > 1) "s" else ""} Confirmed")
                                    if (confirmedDangerCount > 0 && confirmedWarningCount > 0) append(" · ")
                                    if (confirmedWarningCount > 0) append("$confirmedWarningCount Warning Sign${if (confirmedWarningCount > 1) "s" else ""}")
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (confirmedDangerCount > 0) DangerRed else OrangeGold
                            )
                        }
                        if (confirmedDangerCount > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Requires urgent review — escalate if necessary", fontSize = 12.sp, color = DangerRed)
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Spacer(modifier = Modifier.height(16.dp))

                // Header: title + role pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text("Symptom Entry", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                        Text("Complete all required fields", fontSize = 13.sp, color = Color(0xFF9E9E9E))
                    }
                    if (displayRole.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFF0F1FA)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(6.dp).background(NavyBlue, CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(displayRole, fontSize = 12.sp, color = NavyBlue, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Enter Symptom
                Text("Enter Symptom", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Text("Write or describe symptom in the text box below.", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.symptoms,
                    onValueChange = { viewModel.setSymptoms(it) },
                    placeholder = { Text("Type a symptom...", color = Color(0xFF9E9E9E)) },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NavyBlue,
                        unfocusedBorderColor = Color(0xFFE0E0E0),
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Duration
                Text("Duration", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.durationValue,
                        onValueChange = { viewModel.setDuration(it.filter { c -> c.isDigit() }) },
                        placeholder = { Text("Enter number", color = Color(0xFF9E9E9E)) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NavyBlue,
                            unfocusedBorderColor = Color(0xFFE0E0E0),
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        )
                    )
                    ExposedDropdownMenuBox(
                        expanded = durationUnitExpanded,
                        onExpandedChange = { durationUnitExpanded = it },
                        modifier = Modifier.width(110.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.durationUnit.label,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = durationUnitExpanded) },
                            modifier = Modifier.menuAnchor(),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NavyBlue,
                                unfocusedBorderColor = Color(0xFFE0E0E0),
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black
                            )
                        )
                        ExposedDropdownMenu(expanded = durationUnitExpanded, onDismissRequest = { durationUnitExpanded = false }) {
                            DurationUnit.entries.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit.label) },
                                    onClick = { viewModel.setDurationUnit(unit); durationUnitExpanded = false }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Age Group
                Text("Age Group", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.height(8.dp))
                ExposedDropdownMenuBox(expanded = ageDropdownExpanded, onExpandedChange = { ageDropdownExpanded = it }) {
                    OutlinedTextField(
                        value = uiState.age?.label ?: "",
                        onValueChange = {},
                        readOnly = true,
                        placeholder = { Text("Select age group", color = Color(0xFF9E9E9E)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ageDropdownExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NavyBlue,
                            unfocusedBorderColor = Color(0xFFE0E0E0),
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        )
                    )
                    ExposedDropdownMenu(expanded = ageDropdownExpanded, onDismissRequest = { ageDropdownExpanded = false }) {
                        AgeRange.entries.forEach { age ->
                            DropdownMenuItem(
                                text = { Text(age.label) },
                                onClick = { viewModel.setAge(age); ageDropdownExpanded = false }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Vitals (Optional)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vitals", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("(Optional)", fontSize = 13.sp, color = Color(0xFF9E9E9E))
                }
                Spacer(modifier = Modifier.height(8.dp))
                VitalField("Temperature (°C)", "e.g 37.5", uiState.vitalSigns.temperature) {
                    viewModel.setVitalSigns(uiState.vitalSigns.copy(temperature = it))
                }
                Spacer(modifier = Modifier.height(8.dp))
                VitalField("Pulse Rate (bpm)", "e.g 80", uiState.vitalSigns.pulseRate) {
                    viewModel.setVitalSigns(uiState.vitalSigns.copy(pulseRate = it))
                }
                Spacer(modifier = Modifier.height(8.dp))
                VitalField("Respiratory Rate", "e.g 16", uiState.vitalSigns.respiratoryRate) {
                    viewModel.setVitalSigns(uiState.vitalSigns.copy(respiratoryRate = it))
                }
                Spacer(modifier = Modifier.height(8.dp))
                VitalField("Blood Pressure", "e.g 120/80", uiState.vitalSigns.bloodPressure) {
                    viewModel.setVitalSigns(uiState.vitalSigns.copy(bloodPressure = it))
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Signs & Symptoms
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Signs & Symptoms", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                    Text("(tick all that apply)", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                }
                Spacer(modifier = Modifier.height(10.dp))

                // Critical Danger Signs
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFDE8E8),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Critical Danger Signs", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DangerRed)
                            Spacer(modifier = Modifier.width(6.dp))
                            if (confirmedDangerCount > 0) {
                                Surface(shape = RoundedCornerShape(10.dp), color = DangerRed) {
                                    Text("$confirmedDangerCount confirmed", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        CRITICAL_DANGER_SIGNS.forEach { sign ->
                            SignCheckRow(
                                sign = sign,
                                checked = sign in uiState.checkedSigns,
                                confirmed = sign in uiState.confirmedSigns,
                                checkColor = DangerRed,
                                onCheck = { checked ->
                                    if (checked) viewModel.checkSign(sign, isDanger = true)
                                    else viewModel.uncheckSign(sign)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Warning Signs
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFF8E1),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = OrangeGold, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Warning Signs", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OrangeGold)
                            Spacer(modifier = Modifier.width(6.dp))
                            if (confirmedWarningCount > 0) {
                                Surface(shape = RoundedCornerShape(10.dp), color = OrangeGold) {
                                    Text("$confirmedWarningCount confirmed", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        WARNING_SIGNS.forEach { sign ->
                            SignCheckRow(
                                sign = sign,
                                checked = sign in uiState.checkedSigns,
                                confirmed = sign in uiState.confirmedSigns,
                                checkColor = OrangeGold,
                                onCheck = { checked ->
                                    if (checked) viewModel.checkSign(sign, isDanger = false)
                                    else viewModel.uncheckSign(sign)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Optional Image + Voice
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Optional Image", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("(Optional)", fontSize = 13.sp, color = Color(0xFF9E9E9E))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!audioPermissionGranted) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else if (uiState.isRecording) {
                                viewModel.stopVoiceRecording()
                            } else {
                                viewModel.startVoiceRecording()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (uiState.isRecording) DangerRed else Color(0xFFE0E0E0)),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (uiState.isTranscribing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = OrangeGold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Transcribing...", color = OrangeGold, fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = if (uiState.isRecording) DangerRed else Color(0xFF444746), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (uiState.isRecording) "Recording..." else "Voice Note", fontSize = 13.sp, color = if (uiState.isRecording) DangerRed else Color(0xFF1F1F1F))
                        }
                    }
                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (hasImage) Color(0xFF2E7D32) else Color(0xFFE0E0E0)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = if (hasImage) Color(0xFF2E7D32) else Color(0xFF444746), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (hasImage) "Image Added" else "Add Image (Optional)", fontSize = 13.sp, color = if (hasImage) Color(0xFF2E7D32) else Color(0xFF1F1F1F))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Bottom buttons
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            LaunchedEffect(uiState.guidance) {
                if (uiState.guidance != null && !uiState.isProcessing) {
                    onContinue()
                }
            }

            Button(
                onClick = { viewModel.getGuidance() },
                enabled = canContinue && !uiState.isProcessing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyBlue, disabledContainerColor = Color(0xFF9E9E9E))
            ) {
                if (uiState.isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(uiState.processingStatus.ifEmpty { "Processing..." }, fontSize = 14.sp, color = Color.White)
                } else {
                    Text("Generate Guidance", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }

            if (uiState.inferenceError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFDE8E8), modifier = Modifier.fillMaxWidth()) {
                    Text(uiState.inferenceError ?: "", modifier = Modifier.padding(12.dp), fontSize = 13.sp, color = DangerRed)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { showPauseSheet = true },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, OrangeGold)
            ) {
                Icon(Icons.Default.Pause, contentDescription = null, tint = OrangeGold, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pause Patient", fontSize = 14.sp, color = OrangeGold)
            }
        }
    }

    // ── Pause Sheet ────────────────────────────────────────────────────────────
    if (showPauseSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPauseSheet = false },
            sheetState = pauseSheetState,
            containerColor = Color.White
        ) {
            PauseConsultationSheet(
                viewModel = viewModel,
                onSaveAndStartNew = { paused ->
                    scope.launch { pauseSheetState.hide() }.invokeOnCompletion {
                        showPauseSheet = false
                        onSavePausedAndGoHome(paused)
                    }
                },
                onContinue = {
                    scope.launch { pauseSheetState.hide() }.invokeOnCompletion { showPauseSheet = false }
                },
                onDismiss = {
                    scope.launch { pauseSheetState.hide() }.invokeOnCompletion { showPauseSheet = false }
                }
            )
        }
    }

    // ── Danger/Warning Sign Sheet ──────────────────────────────────────────────
    val pendingSign = uiState.pendingSignAlert
    if (pendingSign != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissSignForNow() },
            sheetState = dangerSheetState,
            containerColor = Color.White
        ) {
            if (uiState.pendingSignIsDanger) {
                DangerSignAlertSheet(
                    signName = pendingSign,
                    onConfirm = { viewModel.confirmSign() },
                    onNotPresent = { viewModel.uncheckSign(pendingSign) },
                    onDismiss = { viewModel.dismissSignForNow() }
                )
            } else {
                WarningSignAlertSheet(
                    signName = pendingSign,
                    onConfirm = { viewModel.confirmSign() },
                    onNotPresent = { viewModel.uncheckSign(pendingSign) },
                    onDismiss = { viewModel.dismissSignForNow() }
                )
            }
        }
    }
}

@Composable
private fun SignCheckRow(
    sign: String,
    checked: Boolean,
    confirmed: Boolean,
    checkColor: Color,
    onCheck: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheck(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    1.5.dp,
                    if (checked) checkColor else Color(0xFFBDBDBD),
                    RoundedCornerShape(4.dp)
                )
                .background(
                    if (checked) checkColor else Color.White,
                    RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            sign,
            fontSize = 14.sp,
            color = if (checked) Color(0xFF1F1F1F) else Color(0xFF444746),
            fontWeight = if (confirmed) FontWeight.Medium else FontWeight.Normal
        )
        if (confirmed) {
            Spacer(modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(10.dp), color = checkColor.copy(alpha = 0.15f)) {
                Text("confirmed", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = checkColor)
            }
        }
    }
}

@Composable
private fun VitalField(label: String, placeholder: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("$label: $placeholder", color = Color(0xFF9E9E9E)) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NavyBlue,
            unfocusedBorderColor = Color(0xFFE0E0E0),
            focusedTextColor = Color.Black,
            unfocusedTextColor = Color.Black
        ),
        singleLine = true,
        label = { Text(label, fontSize = 12.sp) }
    )
}
