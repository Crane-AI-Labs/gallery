package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.PauseReason
import com.google.ai.edge.gallery.healthdemo.data.PausedConsultation
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding()
            // Makerere v2 #5: pause sheet's note field lifts the keyboard;
            // imePadding keeps "Save & Start New" reachable without the
            // clinician having to dismiss the keyboard first.
            .imePadding()
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
                Text("Pause Assessment", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Text("Please select a reason for pausing this assessment", fontSize = 12.sp, color = Color(0xFF9E9E9E))
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {

            // Reason options — radio style
            PauseReason.entries.forEach { reason ->
                val selected = selectedReason == reason
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .border(
                            1.dp,
                            if (selected) NavyBlue else Color(0xFFE0E0E0),
                            RoundedCornerShape(8.dp)
                        )
                        .background(
                            if (selected) NavyBlue else Color.White,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedReason = if (selected) null else reason }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Radio circle
                    Surface(
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = androidx.compose.material3.CardDefaults.outlinedCardBorder().let {
                            androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (selected) Color.White else Color(0xFFBDBDBD)
                            )
                        },
                        modifier = Modifier.size(18.dp)
                    ) {
                        if (selected) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(3.dp)
                                    .background(Color.White, CircleShape)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        reason.label,
                        fontSize = 14.sp,
                        color = if (selected) Color.White else Color(0xFF1F1F1F),
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Note field
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= 100) note = it },
                placeholder = { Text("Please specify reason (max 100 chars)", color = Color(0xFF9E9E9E), fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().height(80.dp),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OrangeGold,
                    unfocusedBorderColor = Color(0xFFE0E0E0),
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                )
            )
            Text(
                "Reason to Pause: If not listed, tell us why you will not be able to specify the reason.",
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Confirm & Pause (only enabled when reason selected)
            Button(
                onClick = {
                    val paused = viewModel.buildPausedConsultation(selectedReason, note)
                    onSaveAndStartNew(paused)
                },
                enabled = selectedReason != null,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NavyBlue,
                    disabledContainerColor = Color(0xFFE0E0E0)
                )
            ) {
                Text(
                    "Confirm & Pause",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selectedReason != null) Color.White else Color(0xFF9E9E9E)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel", fontSize = 14.sp, color = Color(0xFF444746), textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
