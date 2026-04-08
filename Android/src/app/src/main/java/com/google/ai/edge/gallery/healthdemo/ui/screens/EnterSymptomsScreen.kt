package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.AgeRange
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.Sex
import com.google.ai.edge.gallery.healthdemo.data.VitalSigns
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

private val NavyBlue = Color(0xFF0D1B5E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterSymptomsScreen(
    viewModel: HealthDemoViewModel,
    onContinue: () -> Unit,
    onViewSavedResults: () -> Unit,
    onPause: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val canContinue = uiState.symptoms.isNotBlank()
    val hasImage = uiState.capturedImageBytes != null

    var ageDropdownExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var audioPermissionGranted by remember { mutableStateOf(false) }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> audioPermissionGranted = granted }

    // Check permission on first composition
    LaunchedEffect(Unit) {
        audioPermissionGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Image picker (file picker for images)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                if (bytes != null) {
                    viewModel.setCapturedImage(bytes)
                }
            } catch (e: Exception) {
                android.util.Log.e("EnterSymptoms", "Failed to read image", e)
            }
        }
    }

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
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Patient Assessment", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                OutlinedButton(
                    onClick = onPause,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFE6A817))
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null, tint = Color(0xFFE6A817), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pause", fontSize = 13.sp, color = Color(0xFFE6A817))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Role badge
            uiState.role?.let { role ->
                val displayRole = if (role == PatientRole.Other && uiState.customRole.isNotBlank())
                    uiState.customRole else role.label
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFF0F1FA)
                ) {
                    Text(
                        text = "Role: $displayRole",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        fontSize = 13.sp,
                        color = NavyBlue,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Disclaimer notice
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFF444746),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "For decision support only. Final clinical decisions remain yours.",
                    fontSize = 13.sp,
                    color = Color(0xFF444746)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Symptoms
            Text(
                text = "Symptoms",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = uiState.symptoms,
                onValueChange = { viewModel.setSymptoms(it) },
                placeholder = { Text("Describe patient's symptoms...", color = Color(0xFF9E9E9E)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NavyBlue,
                    unfocusedBorderColor = Color(0xFFE0E0E0),
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Add Clinical Evidence
            Text(
                text = "Add Clinical Evidence",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
            Text(
                text = "Attach supporting information if helpful.",
                fontSize = 13.sp,
                color = Color(0xFF444746)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row {
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
                    border = BorderStroke(1.dp, if (uiState.isRecording) Color(0xFFD32F2F) else if (uiState.isTranscribing) Color(0xFFE65100) else Color(0xFFE0E0E0)),
                    modifier = Modifier.weight(1f)
                ) {
                    if (uiState.isTranscribing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFE65100))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Transcribing...", color = Color(0xFFE65100), fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = if (uiState.isRecording) Color(0xFFD32F2F) else Color(0xFF444746), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (uiState.isRecording) "Recording..." else "Voice Note", color = if (uiState.isRecording) Color(0xFFD32F2F) else Color(0xFF1F1F1F), fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (hasImage) Color(0xFF2E7D32) else Color(0xFFE0E0E0)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = if (hasImage) Color(0xFF2E7D32) else Color(0xFF444746), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (hasImage) "Image Added" else "Add Image", color = if (hasImage) Color(0xFF2E7D32) else Color(0xFF1F1F1F), fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Patient Details
            Text(
                text = "Patient Details",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text("Age", fontSize = 14.sp, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(4.dp))

            ExposedDropdownMenuBox(
                expanded = ageDropdownExpanded,
                onExpandedChange = { ageDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = uiState.age?.label ?: "",
                    onValueChange = {},
                    readOnly = true,
                    placeholder = { Text("Select age", color = Color(0xFF9E9E9E)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ageDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NavyBlue,
                        unfocusedBorderColor = Color(0xFFE0E0E0),
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black
                    )
                )
                ExposedDropdownMenu(
                    expanded = ageDropdownExpanded,
                    onDismissRequest = { ageDropdownExpanded = false }
                ) {
                    AgeRange.entries.forEach { age ->
                        DropdownMenuItem(
                            text = { Text(age.label) },
                            onClick = {
                                viewModel.setAge(age)
                                ageDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Sex", fontSize = 14.sp, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(6.dp))

            Sex.entries.forEach { sex ->
                SexOption(
                    label = sex.label,
                    selected = uiState.sex == sex,
                    onClick = { viewModel.setSex(sex) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Vital Signs (Optional)
            Text(
                text = "Vital Signs",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
            Text(
                text = "(Optional)",
                fontSize = 13.sp,
                color = Color(0xFF9E9E9E)
            )

            Spacer(modifier = Modifier.height(10.dp))

            VitalField("Temperature", "e.g 37.5", uiState.vitalSigns.temperature) {
                viewModel.setVitalSigns(uiState.vitalSigns.copy(temperature = it))
            }
            Spacer(modifier = Modifier.height(10.dp))
            VitalField("Pulse Rate", "e.g 80 bpm", uiState.vitalSigns.pulseRate) {
                viewModel.setVitalSigns(uiState.vitalSigns.copy(pulseRate = it))
            }
            Spacer(modifier = Modifier.height(10.dp))
            VitalField("Blood Pressure", "e.g 120/80", uiState.vitalSigns.bloodPressure) {
                viewModel.setVitalSigns(uiState.vitalSigns.copy(bloodPressure = it))
            }
            Spacer(modifier = Modifier.height(10.dp))
            VitalField("Respiratory Rate", "e.g 16 breaths/min", uiState.vitalSigns.respiratoryRate) {
                viewModel.setVitalSigns(uiState.vitalSigns.copy(respiratoryRate = it))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Bottom buttons
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            // Navigate when guidance becomes available
            LaunchedEffect(uiState.guidance) {
                if (uiState.guidance != null && !uiState.isProcessing) {
                    onContinue()
                }
            }

            Button(
                onClick = {
                    viewModel.getGuidance()
                },
                enabled = canContinue && !uiState.isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NavyBlue,
                    disabledContainerColor = Color(0xFF9E9E9E)
                )
            ) {
                if (uiState.isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        uiState.processingStatus.ifEmpty { "Processing..." },
                        fontSize = 14.sp,
                        color = Color.White
                    )
                } else {
                    Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }

            // Show error if inference failed
            if (uiState.inferenceError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFDE8E8),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uiState.inferenceError ?: "",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = Color(0xFFD32F2F)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onViewSavedResults,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.5.dp, NavyBlue)
            ) {
                Text("View Saved Results", fontSize = 16.sp, color = NavyBlue, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SexOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = Color.White,
            border = BorderStroke(1.5.dp, if (selected) NavyBlue else Color(0xFFBDBDBD)),
            modifier = Modifier.size(20.dp)
        ) {
            if (selected) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = NavyBlue,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                ) {}
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, fontSize = 14.sp, color = Color(0xFF1F1F1F))
    }
}

@Composable
private fun VitalField(label: String, placeholder: String, value: String, onValueChange: (String) -> Unit) {
    Text(text = label, fontSize = 14.sp, color = Color(0xFF1F1F1F))
    Spacer(modifier = Modifier.height(4.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Color(0xFF9E9E9E)) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NavyBlue,
            unfocusedBorderColor = Color(0xFFE0E0E0),
            focusedTextColor = Color.Black,
            unfocusedTextColor = Color.Black
        ),
        singleLine = true
    )
}
