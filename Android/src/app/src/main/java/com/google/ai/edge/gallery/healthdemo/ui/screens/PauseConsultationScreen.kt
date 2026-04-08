package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.PauseReason
import com.google.ai.edge.gallery.healthdemo.data.PausedConsultation
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PauseConsultationScreen(
    viewModel: HealthDemoViewModel,
    onSaveAndStartNew: (PausedConsultation) -> Unit,
    onContinue: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedReason by remember { mutableStateOf<PauseReason?>(null) }
    var note by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFF3E0)
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = null,
                        tint = OrangeGold,
                        modifier = Modifier.padding(8.dp).size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column {
                    Text("Pause Consultation", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                    Text("Save progress and return to this patient later", fontSize = 13.sp, color = Color(0xFF444746))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Current symptoms summary
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF5F5F5),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Current Symptoms", fontSize = 12.sp, color = Color(0xFF444746))
                    Text(
                        text = uiState.symptoms.ifBlank { "No symptoms entered" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1F1F1F)
                    )
                    if (uiState.role != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Clinician: ${uiState.role!!.label}", fontSize = 12.sp, color = Color(0xFF444746))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("Reason for Pause", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Text("(Optional)", fontSize = 13.sp, color = Color(0xFF9E9E9E))
            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            text = reason.label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            fontSize = 13.sp,
                            color = if (selected) Color.White else Color(0xFF1F1F1F)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("Add a Note", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Text("(Optional)", fontSize = 13.sp, color = Color(0xFF9E9E9E))
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("e.g. Bed 3, Paediatric ward...", color = Color(0xFF9E9E9E)) },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NavyBlue,
                    unfocusedBorderColor = Color(0xFFE0E0E0),
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF0F1FA),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No patient-identifying information. Stored locally only.",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    color = NavyBlue
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
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

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(
                onClick = onContinue,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Continue this consultation", color = NavyBlue, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
