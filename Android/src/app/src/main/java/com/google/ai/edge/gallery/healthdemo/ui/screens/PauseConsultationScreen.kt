package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.PauseReason
import com.google.ai.edge.gallery.healthdemo.data.PausedConsultation
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel

private val OrangeGold = Color(0xFFE6A817)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PauseConsultationSheet(
    viewModel: HealthDemoViewModel,
    onSaveAndStartNew: (PausedConsultation) -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedReason by remember { mutableStateOf<PauseReason?>(null) }
    var note by remember { mutableStateOf("") }

    val displayRole = if (uiState.role == PatientRole.Other && uiState.customRole.isNotBlank())
        uiState.customRole else uiState.role?.label ?: "Clinician"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFFFF3E0), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Pause, contentDescription = null, tint = OrangeGold, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Pause Consultation", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Text("Save progress and return to this patient later", fontSize = 13.sp, color = Color(0xFF9E9E9E))
            }
            IconButton(onClick = onDismiss) {
                Text("✕", fontSize = 18.sp, color = Color(0xFF9E9E9E))
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            // Info card: symptoms + clinician
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFF9F9F9),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(10.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Pause, contentDescription = null, tint = Color(0xFF9E9E9E), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Symptoms recorded", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                            Text(
                                uiState.symptoms.ifBlank { "No symptoms entered" },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF1F1F1F)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF9E9E9E), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Clinician", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                            Text(displayRole, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F1F1F))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Reason chips
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reason for pause", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.width(6.dp))
                Text("(Optional)", fontSize = 13.sp, color = Color(0xFF9E9E9E))
            }
            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                PauseReason.entries.forEach { reason ->
                    val selected = selectedReason == reason
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) OrangeGold else Color.White,
                        modifier = Modifier
                            .border(1.dp, if (selected) OrangeGold else Color(0xFFE0E0E0), RoundedCornerShape(20.dp))
                            .clickable { selectedReason = if (selected) null else reason }
                    ) {
                        Text(
                            reason.label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            fontSize = 13.sp,
                            color = if (selected) Color.White else Color(0xFF1F1F1F),
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Note field
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add a note", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.width(6.dp))
                Text("(Optional)", fontSize = 13.sp, color = Color(0xFF9E9E9E))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= 200) note = it },
                placeholder = { Text("e.g. Bed 3, Paediatric ward...", color = Color(0xFF9E9E9E), fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().height(90.dp),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OrangeGold,
                    unfocusedBorderColor = Color(0xFFE0E0E0),
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                )
            )
            Text(
                "No patient-identifying information. Stored locally only.",
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val paused = viewModel.buildPausedConsultation(selectedReason, note)
                    onSaveAndStartNew(paused)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeGold)
            ) {
                Text("Save & Start New Patient", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Continue this consultation",
                    fontSize = 14.sp,
                    color = Color(0xFF444746),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
