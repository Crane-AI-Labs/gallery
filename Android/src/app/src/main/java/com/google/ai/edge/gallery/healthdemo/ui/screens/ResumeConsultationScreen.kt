package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.PausedConsultation
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)

@Composable
fun ResumeConsultationScreen(
    paused: PausedConsultation,
    onResume: () -> Unit,
    onDiscard: () -> Unit
) {
    val dateStr = SimpleDateFormat("hh:mm a · MMM dd", Locale.getDefault())
        .format(Date(paused.timestamp))
    val displayRole = if (paused.role == PatientRole.Other && paused.customRole.isNotBlank())
        paused.customRole else paused.role.label

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

            Text("Paused Consultation", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Text(dateStr, fontSize = 13.sp, color = Color(0xFF444746))

            Spacer(modifier = Modifier.height(16.dp))

            // Pause reason badge
            if (paused.pauseReason != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFFFF3E0)
                ) {
                    Text(
                        text = paused.pauseReason.label,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        fontSize = 13.sp,
                        color = OrangeGold,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Summary card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFF5F5F5),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SummaryRow("Symptoms", paused.symptoms.ifBlank { "Not recorded" })
                    Spacer(modifier = Modifier.height(8.dp))
                    SummaryRow("Age Group", paused.age?.label ?: "Not recorded")
                    Spacer(modifier = Modifier.height(8.dp))
                    SummaryRow("Clinician", displayRole)
                    if (paused.note.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        SummaryRow("Note", paused.note)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Session timeline
            Text("Session Timeline", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(12.dp))

            TimelineStep(
                icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp)) },
                label = "Started",
                time = dateStr,
                lineColor = Color(0xFF2E7D32)
            )
            TimelineStep(
                icon = {
                    Surface(shape = CircleShape, color = OrangeGold, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.padding(3.dp))
                    }
                },
                label = "Paused",
                time = "Currently paused",
                lineColor = null
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyBlue)
            ) {
                Text("Resume Consultation", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onDiscard,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Discard", fontSize = 16.sp, color = Color(0xFFD32F2F), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row {
        Text("$label: ", fontSize = 13.sp, color = Color(0xFF444746))
        Text(value, fontSize = 13.sp, color = Color(0xFF1F1F1F), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TimelineStep(
    icon: @Composable () -> Unit,
    label: String,
    time: String,
    lineColor: Color?
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            if (lineColor != null) {
                Box(modifier = Modifier.width(2.dp).height(32.dp).background(lineColor.copy(alpha = 0.3f)))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F1F1F))
            Text(time, fontSize = 12.sp, color = Color(0xFF444746))
        }
    }
}
