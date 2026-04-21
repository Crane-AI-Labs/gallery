package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import com.google.ai.edge.gallery.healthdemo.data.PausedConsultation
import com.google.ai.edge.gallery.healthdemo.data.ReferralInfo
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel
import kotlinx.coroutines.launch

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidanceScreen(
    viewModel: HealthDemoViewModel,
    repository: HealthDemoRepository,
    onCreateNew: () -> Unit,
    onReturnHome: () -> Unit,
    onFeedback: () -> Unit,
    onConfirmOutcome: () -> Unit = {},
    onReferralSaved: (String) -> Unit = {},
    onSavePausedAndGoHome: (PausedConsultation) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val guidance = uiState.guidance ?: return
    val isSaved = uiState.savedAssessment != null
    var showNewAssessmentDialog by remember { mutableStateOf(false) }
    var showReturnHomeDialog by remember { mutableStateOf(false) }
    val view = LocalView.current

    // #01: hold screen-on during active guidance review
    androidx.compose.runtime.DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var showReferralSheet by remember { mutableStateOf(false) }
    var showPauseSheet by remember { mutableStateOf(false) }

    val referralSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pauseSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Auto-save when guidance is first displayed
    LaunchedEffect(guidance) {
        if (!isSaved) {
            val assessment = viewModel.buildSavedAssessment()
            repository.save(assessment)
            viewModel.markSaved(assessment)
        }
    }

    // Dialog: "You have an unfinished assessment"
    if (showNewAssessmentDialog) {
        AlertDialog(
            onDismissRequest = { showNewAssessmentDialog = false },
            title = {
                Text(
                    "You have an unfinished assessment.",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF1F1F1F)
                )
            },
            text = {
                Text("Would you like to continue?", color = Color(0xFF444746))
            },
            confirmButton = {
                Button(
                    onClick = { showNewAssessmentDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NavyBlue)
                ) {
                    Text("Continue", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showNewAssessmentDialog = false
                        viewModel.resetAssessment()
                        onCreateNew()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Discard and Start New", color = NavyBlue)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp)
        )
    }

    // #03: Return to Home confirmation — prevents silent discard
    if (showReturnHomeDialog) {
        AlertDialog(
            onDismissRequest = { showReturnHomeDialog = false },
            title = { Text("Return to Home?", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Text("The assessment has been saved on this device. You can resume it from Case History.", color = Color(0xFF444746), fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showReturnHomeDialog = false
                        viewModel.resetAssessment()
                        onReturnHome()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NavyBlue)
                ) { Text("Return to Home", color = Color.White) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showReturnHomeDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Stay here", color = NavyBlue) }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp)
        )
    }

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

            Text(
                text = "Suggested Guidance",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Single consolidated save banner (#11) or Disclaimer
            if (isSaved) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFE8F5E9),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Case saved. Will sync when online.",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = Color(0xFF2E7D32)
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFF444746),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = guidance.disclaimer,
                        fontSize = 13.sp,
                        color = Color(0xFF444746)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Triage Level Badge
            if (guidance.triageLevel.isNotBlank()) {
                val triageColor = when {
                    guidance.triageLevel.contains("Emergency", ignoreCase = true) -> Color(0xFFD32F2F)
                    guidance.triageLevel.contains("Urgent", ignoreCase = true) -> Color(0xFFE65100)
                    guidance.triageLevel.contains("Routine", ignoreCase = true) -> Color(0xFF1565C0)
                    guidance.triageLevel.contains("Home", ignoreCase = true) -> Color(0xFF2E7D32)
                    else -> Color(0xFF616161)
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = triageColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Triage Level",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Text(
                            text = guidance.triageLevel,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Confidence Indicator
            if (guidance.confidence.isNotBlank()) {
                val confColor = when (guidance.confidence.lowercase()) {
                    "high" -> Color(0xFF2E7D32)
                    "medium" -> Color(0xFFE65100)
                    else -> Color(0xFFD32F2F)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, confColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(confColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Confidence: ${guidance.confidence.replaceFirstChar { it.uppercase() }}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = confColor
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Possible Condition
            Text(
                text = "Possible Condition",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = guidance.possibleCondition,
                fontSize = 15.sp,
                color = Color(0xFF1F1F1F)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Suggested Treatment
            Text(
                text = "Suggested Treatment",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
            Spacer(modifier = Modifier.height(8.dp))
            guidance.suggestedTreatment.forEach { item ->
                BulletItem(text = item)
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Recommended Next Steps
            Text(
                text = "Follow-up & Monitoring",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
            Spacer(modifier = Modifier.height(8.dp))
            guidance.recommendedNextSteps.forEach { item ->
                BulletItem(text = item)
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Red Flags / Danger Signs
            if (guidance.redFlags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFDE8E8),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Danger Signs: Refer Immediately If",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD32F2F)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        guidance.redFlags.forEach { flag ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("  ", fontSize = 14.sp, color = Color(0xFFD32F2F))
                                Text(flag, fontSize = 13.sp, color = Color(0xFFB71C1C), lineHeight = 18.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Bottom actions
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            // Primary: confirm outcome
            Button(
                onClick = onConfirmOutcome,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyBlue)
            ) {
                Text("Save Assessment", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Refer + Pause — compact side-by-side row (BUG-08)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showReferralSheet = true },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NavyBlue)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = NavyBlue, modifier = androidx.compose.ui.Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Refer", fontSize = 13.sp, color = NavyBlue, fontWeight = FontWeight.Medium)
                }
                OutlinedButton(
                    onClick = { showPauseSheet = true },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OrangeGold)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null, tint = OrangeGold, modifier = androidx.compose.ui.Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pause", fontSize = 13.sp, color = OrangeGold, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Create New Assessment (outlined)
            OutlinedButton(
                onClick = { showNewAssessmentDialog = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, NavyBlue)
            ) {
                Text("Create New Assessment", fontSize = 15.sp, color = NavyBlue, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = { showReturnHomeDialog = true },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Return To Home", color = NavyBlue, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    // ── Referral Sheet — clinician-initiated, not triggered by danger signs ────
    if (showReferralSheet) {
        ModalBottomSheet(
            onDismissRequest = { showReferralSheet = false },
            sheetState = referralSheetState,
            containerColor = Color.White
        ) {
            ReferralSheet(
                onSave = { referral: ReferralInfo ->
                    val savedId = uiState.savedAssessment?.id
                    if (savedId != null) {
                        repository.updateReferral(savedId, referral)
                    }
                    viewModel.setReferralInfo(referral)
                    scope.launch { referralSheetState.hide() }.invokeOnCompletion {
                        showReferralSheet = false
                        onReferralSaved(savedId ?: "")
                    }
                },
                onCancel = {
                    scope.launch { referralSheetState.hide() }.invokeOnCompletion {
                        showReferralSheet = false
                    }
                }
            )
        }
    }

    // ── Pause Sheet ────────────────────────────────────────────────────────────
    if (showPauseSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPauseSheet = false },
            sheetState = pauseSheetState,
            containerColor = Color.White
        ) {
            PauseConsultationSheet(
                viewModel = viewModel,
                onSaveAndStartNew = { paused ->
                    scope.launch { pauseSheetState.hide() }.invokeOnCompletion {
                        showPauseSheet = false
                        onSavePausedAndGoHome(paused)
                    }
                },
                onContinue = {
                    scope.launch { pauseSheetState.hide() }.invokeOnCompletion { showPauseSheet = false }
                },
                onDismiss = {
                    scope.launch { pauseSheetState.hide() }.invokeOnCompletion { showPauseSheet = false }
                }
            )
        }
    }
}

@Composable
private fun BulletItem(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .background(Color(0xFF1F1F1F), CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            color = Color(0xFF1F1F1F),
            lineHeight = 20.sp
        )
    }
}
