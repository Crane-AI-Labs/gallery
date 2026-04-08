package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.AppSettings
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import com.google.ai.edge.gallery.healthdemo.data.PausedConsultation
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)

@Composable
fun HealthDemoLandingScreen(
    repository: HealthDemoRepository,
    onStartAssessment: () -> Unit,
    onViewHistory: () -> Unit,
    onResumePaused: (String) -> Unit,
    onDiscardPaused: (String) -> Unit,
    onSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val llmModelName = AppSettings.getLlmModelName(context)
    val roleName = AppSettings.getRole(context)
    val pausedList by repository.pausedConsultations.collectAsState()
    val savedCount by repository.savedAssessments.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Settings icon top-right
        IconButton(
            onClick = onSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFF444746), modifier = Modifier.size(28.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Text("Ease Health", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Decision support only. Not a diagnostic tool.", fontSize = 14.sp, color = Color(0xFF444746), textAlign = TextAlign.Center)

            if (llmModelName != null || roleName != null) {
                Spacer(modifier = Modifier.height(10.dp))
                if (llmModelName != null) Text("Model: $llmModelName", fontSize = 12.sp, color = Color(0xFF2E7D32), textAlign = TextAlign.Center)
                if (roleName != null) Text("Role: $roleName", fontSize = 12.sp, color = Color(0xFF1565C0), textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Paused consultations
            if (pausedList.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFFFF3E0)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, tint = OrangeGold, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Awaiting Resume", fontSize = 12.sp, color = OrangeGold, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(10.dp), color = OrangeGold) {
                                Text(
                                    text = "${pausedList.size}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 11.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    pausedList.forEach { paused ->
                        PausedCard(
                            paused = paused,
                            onResume = { onResumePaused(paused.id) },
                            onDiscard = { onDiscardPaused(paused.id) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = onStartAssessment,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyBlue)
            ) {
                Text("Start Assessment", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onViewHistory,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.5.dp, NavyBlue)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("View Cases History", fontSize = 16.sp, color = NavyBlue, fontWeight = FontWeight.Medium)
                    if (savedCount.isNotEmpty() || pausedList.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(10.dp), color = NavyBlue) {
                            Text(
                                text = "${savedCount.size}",
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                fontSize = 11.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (pausedList.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("· ${pausedList.size} paused", fontSize = 12.sp, color = OrangeGold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PausedCard(
    paused: PausedConsultation,
    onResume: () -> Unit,
    onDiscard: () -> Unit
) {
    val timeStr = SimpleDateFormat("hh:mm a · MMM dd", Locale.getDefault()).format(Date(paused.timestamp))
    val displayRole = if (paused.role == PatientRole.Other && paused.customRole.isNotBlank())
        paused.customRole else paused.role.label

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFFFF8F0),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = paused.symptoms.ifBlank { "No symptoms entered" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1F1F1F)
                    )
                    Text(timeStr, fontSize = 12.sp, color = Color(0xFF444746))
                    if (paused.pauseReason != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFFFF3E0)) {
                            Text(
                                text = paused.pauseReason.label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 11.sp,
                                color = OrangeGold
                            )
                        }
                    }
                }
                IconButton(onClick = onDiscard, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Discard", tint = Color(0xFF9E9E9E), modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeGold)
            ) {
                Text("Resume", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
    }
}
