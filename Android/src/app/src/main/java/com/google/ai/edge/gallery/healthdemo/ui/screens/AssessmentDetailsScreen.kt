package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
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
import com.google.ai.edge.gallery.healthdemo.data.FinalAction
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.SavedAssessment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)
private val DangerRed = Color(0xFFD32F2F)

// ─── Sheet version (called from SavedRecordsScreen) ───────────────────────────

@Composable
fun AssessmentDetailSheet(
    assessment: SavedAssessment,
    onDismiss: () -> Unit
) {
    val dateStr = SimpleDateFormat("MMM d, yyyy 'at' hh:mm a", Locale.getDefault())
        .format(Date(assessment.timestamp))
    val caseLabel = "#CASE${assessment.id.takeLast(6).uppercase()}"
    val displayRole = if (assessment.role == PatientRole.Other && assessment.customRole.isNotBlank())
        assessment.customRole else assessment.role.label
    val hasDangerSigns = assessment.confirmedSigns.isNotEmpty()
    val finalAction = assessment.clinicianConfirmation?.finalAction

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(caseLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Text(dateStr, fontSize = 12.sp, color = Color(0xFF9E9E9E))
            }
            IconButton(onClick = onDismiss) {
                Text("✕", fontSize = 18.sp, color = Color(0xFF9E9E9E))
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            // Danger Signs Detected
            if (hasDangerSigns) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFDE8E8),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFFFCDD2), RoundedCornerShape(10.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Danger Signs Detected", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DangerRed)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            assessment.confirmedSigns.take(3).forEach { sign ->
                                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFFFF5F5)) {
                                    Text(
                                        sign,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        fontSize = 12.sp,
                                        color = DangerRed
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Chief Complaints
            Text("Chief Complaints", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF5F5F5)) {
                Text(
                    assessment.symptoms,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    color = Color(0xFF1F1F1F)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Duration + Age
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (assessment.durationValue.isNotBlank()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Duration", fontSize = 13.sp, color = Color(0xFF9E9E9E))
                        Text("${assessment.durationValue} ${assessment.durationUnit.label}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                    }
                }
                if (assessment.age != null) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Age Group", fontSize = 13.sp, color = Color(0xFF9E9E9E))
                        Text(assessment.age.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Divider
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE0E0E0)))
            Spacer(modifier = Modifier.height(16.dp))

            // Clinician Response
            if (assessment.clinicianConfirmation != null) {
                Text("Clinician Response", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.height(12.dp))

                DetailInfoRow("Clinician Role") {
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF0F1FA)) {
                        Text(displayRole, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 13.sp, color = NavyBlue, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (finalAction != null) {
                    DetailInfoRow("Final Action") {
                        val (bg, fg) = when (finalAction) {
                            FinalAction.Referred -> Pair(Color(0xFFFFF3E0), OrangeGold)
                            FinalAction.Escalated -> Pair(Color(0xFFFDE8E8), DangerRed)
                            FinalAction.ManagedLocally -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32))
                            else -> Pair(Color(0xFFF5F5F5), Color(0xFF444746))
                        }
                        Surface(shape = RoundedCornerShape(8.dp), color = bg) {
                            Text(finalAction.label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 13.sp, color = fg, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (assessment.clinicianConfirmation.guidanceUsed != null) {
                    DetailInfoRow("Used Guidance") {
                        Text(assessment.clinicianConfirmation.guidanceUsed.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE0E0E0)))
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Session Timeline
            Text("Session Timeline", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(12.dp))

            val startStr = SimpleDateFormat("MMM d, yyyy 'at' hh:mm a", Locale.getDefault())
                .format(Date(assessment.timestamp - 18 * 60 * 1000))
            TimelineRowDetail(dotColor = Color(0xFF4CAF50), label = "Started", time = startStr, isGreen = true)
            Spacer(modifier = Modifier.height(10.dp))
            TimelineRowDetail(dotColor = Color(0xFF444746), label = "Completed", time = dateStr, subtitle = "18 min active")

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailInfoRow(label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = Color(0xFF9E9E9E))
        value()
    }
}

@Composable
private fun TimelineRowDetail(
    dotColor: Color,
    label: String,
    time: String,
    subtitle: String? = null,
    isGreen: Boolean = false
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.padding(top = 4.dp).size(12.dp).background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F1F1F))
            Text(time, fontSize = 12.sp, color = Color(0xFF9E9E9E))
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Color(0xFF9E9E9E))
        }
    }
}

// ─── Full-screen fallback (kept for direct navigation if needed) ───────────────

@Composable
fun AssessmentDetailsScreen(
    assessmentId: String,
    repository: HealthDemoRepository,
    onNavigateBack: () -> Unit
) {
    val assessment = repository.getByIdCached(assessmentId)

    if (assessment == null) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Assessment not found.", color = Color(0xFF444746))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF1F1F1F))
            }
            Text("Case Details", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            AssessmentDetailSheet(assessment = assessment, onDismiss = onNavigateBack)
        }
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, NavyBlue)
            ) {
                Text("Back", color = NavyBlue, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
        }
    }
}
