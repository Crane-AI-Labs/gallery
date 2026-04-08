package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.PausedConsultation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)

@Composable
fun ResumeConsultationSheet(
    paused: PausedConsultation,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    val timestampStr = SimpleDateFormat("MMM d, yyyy 'at' hh:mm a", Locale.getDefault())
        .format(Date(paused.timestamp))
    val startStr = SimpleDateFormat("MMM d, yyyy 'at' hh:mm a", Locale.getDefault())
        .format(Date(paused.timestamp - 8 * 60 * 1000))
    val displayRole = if (paused.role == PatientRole.Other && paused.customRole.isNotBlank())
        paused.customRole else paused.role.label

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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFFFF3E0), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Pause, contentDescription = null, tint = OrangeGold, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Paused Consultation", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Text(timestampStr, fontSize = 12.sp, color = Color(0xFF9E9E9E))
            }
            IconButton(onClick = onDismiss) {
                Text("✕", fontSize = 18.sp, color = Color(0xFF9E9E9E))
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            // Pause reason card
            if (paused.pauseReason != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFFFBE6),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFFFE0B2), RoundedCornerShape(10.dp))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(paused.pauseReason.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OrangeGold)
                            Text("Paused at: Symptom entry", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                        }
                        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFFFF3E0)) {
                            Text(
                                paused.pauseReason.label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 12.sp, color = OrangeGold, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Symptoms
            Text("Symptoms", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(6.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF5F5F5)) {
                Text(
                    paused.symptoms.ifBlank { "No symptoms entered" },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp, color = Color(0xFF1F1F1F)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Duration + Age
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (paused.durationValue.isNotBlank()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Duration", fontSize = 13.sp, color = Color(0xFF9E9E9E))
                        Text("${paused.durationValue} ${paused.durationUnit.label}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                    }
                }
                if (paused.age != null) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Age Group", fontSize = 13.sp, color = Color(0xFF9E9E9E))
                        Text(paused.age.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Clinician row
            Row(
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Clinician", fontSize = 14.sp, color = Color(0xFF9E9E9E))
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF0F1FA)) {
                    Text(displayRole, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 13.sp, color = NavyBlue, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Session Timeline
            Text("Session Timeline", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(12.dp))

            TimelineRow(dotColor = Color(0xFF4CAF50), label = "Started", time = startStr)
            Spacer(modifier = Modifier.height(10.dp))
            TimelineRow(dotColor = OrangeGold, label = "Paused", time = timestampStr, subtitle = "8 min into session", useIcon = true)
            Spacer(modifier = Modifier.height(10.dp))
            TimelineRow(
                dotColor = OrangeGold,
                label = "Currently paused",
                time = if (paused.pauseReason != null) "${paused.pauseReason.label} — $timestampStr" else timestampStr,
                isOrange = true,
                useIcon = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyBlue)
            ) {
                Text("Resume Consultation", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onDiscard,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0))
            ) {
                Text("Discard", fontSize = 15.sp, color = Color(0xFF1F1F1F))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TimelineRow(
    dotColor: Color,
    label: String,
    time: String,
    subtitle: String? = null,
    isOrange: Boolean = false,
    useIcon: Boolean = false
) {
    Row(verticalAlignment = Alignment.Top) {
        if (useIcon) {
            Box(
                modifier = Modifier.padding(top = 2.dp).size(16.dp).background(dotColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
            }
        } else {
            Box(modifier = Modifier.padding(top = 5.dp).size(12.dp).background(dotColor, CircleShape))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (isOrange) OrangeGold else Color(0xFF1F1F1F))
            Text(time, fontSize = 12.sp, color = Color(0xFF9E9E9E))
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Color(0xFF9E9E9E))
        }
    }
}
