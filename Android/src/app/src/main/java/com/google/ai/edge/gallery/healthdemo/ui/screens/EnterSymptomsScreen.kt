package com.google.ai.edge.gallery.healthdemo.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
    onViewSavedResults: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val hasImage = uiState.capturedImageBytes != null
    val canContinue = (uiState.symptoms.isNotBlank() || hasImage) && uiState.age != null
    val focusManager = LocalFocusManager.current

    var durationUnitExpanded by remember { mutableStateOf(false) }

    val dangerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    var audioPermissionGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> audioPermissionGranted = granted }

    // Location permission
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* result doesn't matter — location is best-effort */ }

    LaunchedEffect(Unit) {
        audioPermissionGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // Request location permission once (non-blocking, best-effort)
        if (!com.google.ai.edge.gallery.healthdemo.data.LocationCapture.hasPermission(context)) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            // Permission already granted — start capturing early
            viewModel.startLocationCapture()
        }
    }

    // Image capture — two separate contracts (camera + gallery)
    // because no Android API reliably combines both in a single native UI on Samsung
    var cameraImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showImageSheet by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraImageUri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(cameraImageUri!!)?.readBytes()
                if (bytes != null) viewModel.setCapturedImage(bytes)
            } catch (e: Exception) {
                android.util.Log.e("EnterSymptoms", "Failed to read camera image", e)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
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

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && cameraImageUri != null) {
            cameraLauncher.launch(cameraImageUri!!)
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
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
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

                Spacer(modifier = Modifier.height(10.dp))

                // Voice + Image (immediately after symptom entry)
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
                        onClick = { showImageSheet = true },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (hasImage) Color(0xFF2E7D32) else Color(0xFFE0E0E0)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = if (hasImage) Color(0xFF2E7D32) else Color(0xFF444746), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (hasImage) "Image Added" else "Add Image", fontSize = 13.sp, color = if (hasImage) Color(0xFF2E7D32) else Color(0xFF1F1F1F))
                    }
                }

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
                        modifier = Modifier.width(140.dp)
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

                // Age
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Patient Age", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("*", fontSize = 14.sp, color = DangerRed, fontWeight = FontWeight.Bold)
                }
                Text("Clinical group is assigned automatically", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = uiState.ageYears,
                        onValueChange = { v ->
                            viewModel.setAgeInput(v.filter { it.isDigit() }.take(3), uiState.ageMonths)
                        },
                        placeholder = { Text("Years", color = Color(0xFF9E9E9E)) },
                        label = { Text("Years", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NavyBlue,
                            unfocusedBorderColor = Color(0xFFE0E0E0),
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        )
                    )
                    OutlinedTextField(
                        value = uiState.ageMonths,
                        onValueChange = { v ->
                            val filtered = v.filter { it.isDigit() }.take(2)
                            val clamped = filtered.toIntOrNull()?.coerceIn(0, 11)?.toString() ?: filtered
                            viewModel.setAgeInput(uiState.ageYears, clamped)
                        },
                        placeholder = { Text("Months", color = Color(0xFF9E9E9E)) },
                        label = { Text("Months (0–11)", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NavyBlue,
                            unfocusedBorderColor = Color(0xFFE0E0E0),
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        )
                    )
                }
                // Auto-assigned group chip
                if (uiState.age != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Assigned group: ", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF0F1FA)
                        ) {
                            Text(
                                uiState.age!!.label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                color = NavyBlue,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Gender
                Text("Gender", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.google.ai.edge.gallery.healthdemo.data.Sex.entries.forEach { sex ->
                        val selected = uiState.sex == sex
                        OutlinedButton(
                            onClick = { viewModel.setSex(sex) },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.5.dp,
                                if (selected) NavyBlue else Color(0xFFE0E0E0)
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) Color(0xFFF0F1FA) else Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                sex.label,
                                fontSize = 13.sp,
                                color = if (selected) NavyBlue else Color(0xFF444746),
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
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

            if (uiState.isProcessing) {
                // Show processing state with cancel option
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF9E9E9E))
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(uiState.processingStatus.ifEmpty { "Processing..." }, fontSize = 14.sp, color = Color.White)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.cancelInference() },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, DangerRed)
                ) {
                    Text("Cancel", fontSize = 14.sp, color = DangerRed)
                }
            } else {
                Button(
                    onClick = { viewModel.getGuidance() },
                    enabled = canContinue,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NavyBlue, disabledContainerColor = Color(0xFF9E9E9E))
                ) {
                    Text("Generate Guidance", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }

            if (uiState.inferenceError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFDE8E8), modifier = Modifier.fillMaxWidth()) {
                    Text(uiState.inferenceError ?: "", modifier = Modifier.padding(12.dp), fontSize = 13.sp, color = DangerRed)
                }
            }
        }
    }

    // ── Danger/Warning Sign Sheet ──────────────────────────────────────────────
    // ── Image Source Sheet ─────────────────────────────────────────────────────
    if (showImageSheet) {
        val imageSheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showImageSheet = false },
            sheetState = imageSheetState,
            containerColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "Add Image",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F1F1F),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )

                // Camera option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showImageSheet = false
                            val photoFile = java.io.File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                            cameraImageUri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.provider", photoFile
                            )
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                cameraLauncher.launch(cameraImageUri!!)
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = NavyBlue, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Take Photo", fontSize = 16.sp, color = Color(0xFF1F1F1F))
                }

                // Gallery option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showImageSheet = false
                            galleryLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = NavyBlue, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Choose from Gallery", fontSize = 16.sp, color = Color(0xFF1F1F1F))
                }
            }
        }
    }

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
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { focusManager.clearFocus(); onCheck(!checked) }
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
