package com.google.ai.edge.gallery.healthdemo.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.PausedConsultation
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthDemoLandingScreen(
    repository: HealthDemoRepository,
    viewModel: HealthDemoViewModel,
    onStartAssessment: () -> Unit,
    onViewHistory: () -> Unit,
    onSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val pausedList by repository.pausedConsultations.collectAsState()
    val savedCount by repository.savedAssessments.collectAsState()

    val roleSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val resumeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var showRoleSheet by remember { mutableStateOf(false) }
    var showResumeSheet by remember { mutableStateOf(false) }
    var showInProgressDialog by remember { mutableStateOf(false) }
    var selectedPausedId by remember { mutableStateOf<String?>(null) }
    var rememberRole by remember { mutableStateOf(AppSettings.getRole(context) != null) }

    val selectedPaused = selectedPausedId?.let { repository.getPausedById(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Text(
            "Ease Health",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1F1F1F),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Decision support only. Not a diagnostic tool.",
            fontSize = 14.sp,
            color = Color(0xFF9E9E9E),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Paused consultations section
        if (pausedList.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = OrangeGold,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Awaiting Resume",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1F1F1F)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1F1F1F)
                ) {
                    Text(
                        "${pausedList.size}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            pausedList.forEach { paused ->
                PausedCard(
                    paused = paused,
                    onResume = {
                        selectedPausedId = paused.id
                        showResumeSheet = true
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                val uiState = viewModel.uiState.value
                val hasInProgress = uiState.symptoms.isNotBlank() || uiState.age != null
                when {
                    hasInProgress -> showInProgressDialog = true
                    uiState.role != null -> onStartAssessment()
                    else -> showRoleSheet = true
                }
            },
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
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = Color(0xFF1F1F1F),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cases History", fontSize = 15.sp, color = Color(0xFF1F1F1F))
                if (savedCount.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF444746)) {
                        Text(
                            "${savedCount.size}",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (pausedList.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = OrangeGold,
                        modifier = Modifier.size(7.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${pausedList.size} paused", fontSize = 12.sp, color = OrangeGold)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }

    // ── BUG-06: In-progress assessment gate dialog ─────────────────────────────
    if (showInProgressDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showInProgressDialog = false },
            title = {
                Text("Assessment in progress", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF1F1F1F))
            },
            text = {
                Text("You have an unfinished assessment. What would you like to do?", color = Color(0xFF444746), fontSize = 14.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showInProgressDialog = false
                        onStartAssessment()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NavyBlue)
                ) { Text("Continue current assessment", color = Color.White) }
            },
            dismissButton = {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        viewModel.resetAssessment()
                        showInProgressDialog = false
                        val uiState = viewModel.uiState.value
                        if (uiState.role != null) onStartAssessment() else showRoleSheet = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Discard and start new", color = NavyBlue) }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp)
        )
    }

    // ── Select Role Sheet ──────────────────────────────────────────────────────
    if (showRoleSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRoleSheet = false },
            sheetState = roleSheetState,
            containerColor = Color.White
        ) {
            SelectRoleSheet(
                viewModel = viewModel,
                rememberRole = rememberRole,
                onRememberRoleChange = { rememberRole = it },
                onContinue = {
                    val role = viewModel.uiState.value.role
                    if (role != null) {
                        if (rememberRole) AppSettings.saveRole(context, role.label)
                        else AppSettings.saveRole(context, null)
                    }
                    scope.launch { roleSheetState.hide() }.invokeOnCompletion {
                        showRoleSheet = false
                        onStartAssessment()
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

    // ── Resume Sheet ───────────────────────────────────────────────────────────
    if (showResumeSheet && selectedPaused != null) {
        ModalBottomSheet(
            onDismissRequest = { showResumeSheet = false },
            sheetState = resumeSheetState,
            containerColor = Color.White
        ) {
            ResumeConsultationSheet(
                paused = selectedPaused,
                onResume = {
                    viewModel.loadPausedConsultation(selectedPaused)
                    repository.removePaused(selectedPaused.id)
                    scope.launch { resumeSheetState.hide() }.invokeOnCompletion {
                        showResumeSheet = false
                        selectedPausedId = null
                        onStartAssessment()
                    }
                },
                onDiscard = {
                    repository.removePaused(selectedPaused.id)
                    scope.launch { resumeSheetState.hide() }.invokeOnCompletion {
                        showResumeSheet = false
                        selectedPausedId = null
                    }
                },
                onDismiss = {
                    scope.launch { resumeSheetState.hide() }.invokeOnCompletion {
                        showResumeSheet = false
                        selectedPausedId = null
                    }
                }
            )
        }
    }
}

@Composable
private fun PausedCard(
    paused: PausedConsultation,
    onResume: () -> Unit
) {
    val timeStr = SimpleDateFormat("hh:mm a · MMM dd", Locale.getDefault()).format(Date(paused.timestamp))
    val displayRole = if (paused.role == PatientRole.Other && paused.customRole.isNotBlank())
        paused.customRole else paused.role.label

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFFFE0B2), RoundedCornerShape(10.dp))
            .background(Color(0xFFFFFBF2), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                paused.symptoms.ifBlank { "No symptoms entered" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(timeStr, fontSize = 12.sp, color = Color(0xFF9E9E9E))
            if (paused.pauseReason != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFFFF3E0)) {
                    Text(
                        paused.pauseReason.label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        fontSize = 11.sp,
                        color = OrangeGold,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = NavyBlue,
            modifier = Modifier.clickable(onClick = onResume)
        ) {
            Text(
                "Resume",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                fontSize = 13.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
