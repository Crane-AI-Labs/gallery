package com.google.ai.edge.gallery.healthdemo.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
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
import androidx.compose.material.icons.filled.Photo
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
import com.google.ai.edge.gallery.healthdemo.data.AppSettings
import com.google.ai.edge.gallery.healthdemo.data.CRITICAL_DANGER_SIGNS
import com.google.ai.edge.gallery.healthdemo.data.TraditionalMedicine
import com.google.ai.edge.gallery.healthdemo.ui.components.DisclaimerBanner
import com.google.ai.edge.gallery.healthdemo.data.DurationUnit
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.Sex
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
    val canContinue = (uiState.symptoms.isNotBlank() || hasImage) && uiState.age != null && uiState.sex != null
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    // #01: hold screen-on only while this consultation screen is active
    androidx.compose.runtime.DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // #07: record session start at screen open, not at first keystroke
    LaunchedEffect(Unit) { viewModel.markSessionStart() }

    var durationUnitExpanded by remember { mutableStateOf(false) }

    val dangerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val roleSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // #Wave4: role pill is tappable to re-open the role picker inline. Default
    // the remember toggle to the same logic Landing uses so the UX is
    // consistent between entry points.
    var showRoleSheet by remember { mutableStateOf(false) }
    var rememberRole by remember { mutableStateOf(AppSettings.getRole(context) != null) }

    var audioPermissionGranted by remember { mutableStateOf(false) }
    var cameraPermissionGranted by remember { mutableStateOf(false) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> audioPermissionGranted = granted }

    // Renamed from permissionLauncher for audio — kept for back-compat
    val permissionLauncher = audioPermissionLauncher

    // Location permission — best-effort; GPS starts capturing as soon as
    // it's granted so the coordinates are ready by the time inference runs.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startLocationCapture() }

    LaunchedEffect(Unit) {
        audioPermissionGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (com.google.ai.edge.gallery.healthdemo.data.LocationCapture.hasPermission(context)) {
            viewModel.startLocationCapture()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
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

    // Camera capture — must be declared before cameraPermissionLauncher
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val uri = cameraUri ?: return@rememberLauncherForActivityResult
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                if (bytes != null) viewModel.setCapturedImage(bytes)
            } catch (e: Exception) {
                android.util.Log.e("EnterSymptoms", "Failed to read camera image", e)
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) {
            val photoFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", photoFile)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val displayRole = if (uiState.role == PatientRole.Other && uiState.customRole.isNotBlank())
        uiState.customRole else uiState.role?.label ?: ""

    val confirmedDangerCount = uiState.confirmedSigns.count { it in CRITICAL_DANGER_SIGNS }
    val confirmedWarningCount = uiState.confirmedSigns.count { it in WARNING_SIGNS }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
    ) {
        DisclaimerBanner()
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
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFFF0F1FA),
                            modifier = Modifier.clickable {
                                // Sync the toggle to the latest persisted state
                                // in case AppSettings changed elsewhere (e.g.
                                // End Shift on Landing) before we re-opened.
                                rememberRole = AppSettings.getRole(context) != null
                                showRoleSheet = true
                            }
                        ) {
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

                // Presenting Symptom(s)
                Text("Presenting Symptom(s)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Text("Describe the main symptoms the patient is presenting with.", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.symptoms,
                    onValueChange = { viewModel.setSymptoms(it) },
                    placeholder = { Text("Describe the main symptoms the patient is presenting with", color = Color(0xFF9E9E9E)) },
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
                // #10: English-only disclaimer for voice input
                Text(
                    "English only — transcribes speech, does not translate.",
                    fontSize = 12.sp,
                    color = Color(0xFF9E9E9E)
                )
                Spacer(modifier = Modifier.height(6.dp))
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
                        onClick = {
                            if (!cameraPermissionGranted) {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            } else {
                                val photoFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", photoFile)
                                cameraUri = uri
                                cameraLauncher.launch(uri)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color(0xFF444746), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Take Photo", fontSize = 13.sp, color = Color(0xFF1F1F1F))
                    }
                }

                if (hasImage) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFE8F5E9), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Image attached", fontSize = 13.sp, color = Color(0xFF2E7D32))
                        }
                    }
                }

                // Gallery row
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (hasImage) Color(0xFF2E7D32) else Color(0xFFE0E0E0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Photo, contentDescription = null, tint = if (hasImage) Color(0xFF2E7D32) else Color(0xFF444746), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Upload from Gallery", fontSize = 13.sp, color = if (hasImage) Color(0xFF2E7D32) else Color(0xFF1F1F1F))
                    }
                }

                // #09: pre-capture guidance
                Text(
                    "Capture your photo with the camera app first, then attach it here.",
                    fontSize = 12.sp,
                    color = Color(0xFF9E9E9E)
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

                // Sex — required field (#04 regression fix)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Patient Sex", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("*", fontSize = 14.sp, color = DangerRed, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sex.entries.forEach { sexOption ->
                        val selected = uiState.sex == sexOption
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) NavyBlue else Color.White,
                            modifier = Modifier
                                .weight(1f)
                                .border(
                                    1.5.dp,
                                    if (selected) NavyBlue else Color(0xFFE0E0E0),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { viewModel.setSex(sexOption) }
                        ) {
                            Text(
                                sexOption.label,
                                modifier = Modifier.padding(vertical = 14.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selected) Color.White else Color(0xFF1F1F1F),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                VitalField("Temperature (C)", "e.g 37.5", uiState.vitalSigns.temperature) {
                    viewModel.setVitalSigns(uiState.vitalSigns.copy(temperature = it))
                }
                Spacer(modifier = Modifier.height(8.dp))
                VitalField("Respiratory Rate (breaths/min)", "e.g 16", uiState.vitalSigns.respiratoryRate) {
                    viewModel.setVitalSigns(uiState.vitalSigns.copy(respiratoryRate = it))
                }
                Spacer(modifier = Modifier.height(8.dp))
                VitalField("Heart Rate (beats/min)", "e.g 80", uiState.vitalSigns.heartRate) {
                    viewModel.setVitalSigns(uiState.vitalSigns.copy(heartRate = it))
                }
                Spacer(modifier = Modifier.height(8.dp))
                VitalField(
                    label = "Blood Pressure (mmHg)",
                    placeholder = "e.g 120/80",
                    value = uiState.vitalSigns.bloodPressure,
                ) {
                    viewModel.setVitalSigns(uiState.vitalSigns.copy(bloodPressure = it))
                }
                Spacer(modifier = Modifier.height(8.dp))
                VitalField("SpO2 (%)", "e.g 98", uiState.vitalSigns.spO2) {
                    viewModel.setVitalSigns(uiState.vitalSigns.copy(spO2 = it))
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Signs & Symptoms
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Danger Signs", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
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

                Spacer(modifier = Modifier.height(12.dp))

                // "I have reviewed all danger signs" checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (uiState.dangerSignsReviewed) NavyBlue else Color(0xFFE0E0E0),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { viewModel.setDangerSignsReviewed(!uiState.dangerSignsReviewed) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .border(1.5.dp, if (uiState.dangerSignsReviewed) NavyBlue else Color(0xFFBDBDBD), RoundedCornerShape(4.dp))
                            .background(if (uiState.dangerSignsReviewed) NavyBlue else Color.White, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.dangerSignsReviewed) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "I have reviewed all danger signs and warning signs and observed all that are present.",
                        fontSize = 13.sp,
                        color = Color(0xFF1F1F1F),
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Traditional Medicine
                Text("Traditional Medicine", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Many patients use traditional medicine. Some home remedies may interfere with treatment.",
                    fontSize = 12.sp,
                    color = Color(0xFF9E9E9E),
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TraditionalMedicine.entries.forEach { option ->
                        val selected = uiState.traditionalMedicine == option
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) NavyBlue else Color.White,
                            modifier = Modifier
                                .weight(1f)
                                .border(1.5.dp, if (selected) NavyBlue else Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                                .clickable { viewModel.setTraditionalMedicine(option) }
                        ) {
                            Text(
                                option.label,
                                modifier = Modifier.padding(vertical = 12.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selected) Color.White else Color(0xFF1F1F1F),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                // Makerere v2 #4: free-text detail capture when the patient
                // is using traditional medicine — drug-interaction risk
                // depends on what specifically is being used. Hidden for
                // No / unanswered to avoid dead space.
                if (uiState.traditionalMedicine == TraditionalMedicine.Yes) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = uiState.traditionalMedicineDetails,
                        onValueChange = { viewModel.setTraditionalMedicineDetails(it) },
                        placeholder = { Text("Please describe (e.g. herbal tea for fever)", color = Color(0xFF9E9E9E)) },
                        label = { Text("Please describe", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NavyBlue,
                            unfocusedBorderColor = Color(0xFFE0E0E0),
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        )
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Bottom buttons
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Button(
                onClick = {
                    viewModel.getGuidance()
                    onContinue()
                },
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyBlue, disabledContainerColor = Color(0xFF9E9E9E))
            ) {
                Text("Generate Assessment", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }

            if (uiState.inferenceError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFDE8E8), modifier = Modifier.fillMaxWidth()) {
                    Text(uiState.inferenceError ?: "", modifier = Modifier.padding(12.dp), fontSize = 13.sp, color = DangerRed)
                }
            }
        }
    }

    } // end Box wrapper

    // ── Role Sheet (tap-to-switch from pill) ───────────────────────────────────
    if (showRoleSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRoleSheet = false },
            sheetState = roleSheetState,
            containerColor = Color.White,
        ) {
            SelectRoleSheet(
                viewModel = viewModel,
                rememberRole = rememberRole,
                onRememberRoleChange = { rememberRole = it },
                onContinue = {
                    val state = viewModel.uiState.value
                    val role = state.role
                    if (role != null) {
                        val labelToSave = if (role == PatientRole.Other && state.customRole.isNotBlank())
                            state.customRole else role.label
                        if (rememberRole) AppSettings.saveRole(context, labelToSave)
                        else AppSettings.saveRole(context, null)
                    }
                    scope.launch { roleSheetState.hide() }.invokeOnCompletion {
                        showRoleSheet = false
                    }
                },
                onDismiss = {
                    scope.launch { roleSheetState.hide() }.invokeOnCompletion {
                        showRoleSheet = false
                    }
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
            containerColor = Color.White,
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
            .clickable {
                focusManager.clearFocus() // BUG-09: dismiss keyboard on checkbox tap
                onCheck(!checked)
            }
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
