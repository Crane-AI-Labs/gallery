package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.PausedConsultation
import com.google.ai.edge.gallery.healthdemo.data.ReferralInfo
import com.google.ai.edge.gallery.healthdemo.ui.components.DisclaimerBanner
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel
import kotlinx.coroutines.launch

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)
private val DangerRed = Color(0xFFD32F2F)

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

    var showReturnHomeDialog by remember { mutableStateOf(false) }
    var showReferralSheet by remember { mutableStateOf(false) }
    var showPauseSheet by remember { mutableStateOf(false) }
    var flagged by remember { mutableStateOf(false) }

    val referralSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pauseSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Auto-save when guidance is first displayed
    LaunchedEffect(guidance) {
        if (!isSaved) {
            val assessment = viewModel.buildSavedAssessment()
            repository.save(assessment)
            viewModel.markSaved(assessment)
        }
    }

    val displayRole = if (uiState.role == PatientRole.Other && uiState.customRole.isNotBlank())
        uiState.customRole else uiState.role?.label ?: ""
    val caseLabel = uiState.savedAssessment?.let {
        "#CASE-${it.id.takeLast(4).uppercase()}"
    } ?: "#CASE-NEW"

    val triageColor = when {
        guidance.triageLevel.contains("Emergency", ignoreCase = true) -> DangerRed
        guidance.triageLevel.contains("Urgent", ignoreCase = true) -> Color(0xFFE65100)
        guidance.triageLevel.contains("Routine", ignoreCase = true) -> Color(0xFF2E7D32)
        guidance.triageLevel.contains("Home", ignoreCase = true) -> Color(0xFF1565C0)
        else -> Color(0xFF616161)
    }

    val hasDangerSigns = uiState.confirmedSigns.isNotEmpty()

    // #03: Return to Home confirmation
    if (showReturnHomeDialog) {
        AlertDialog(
            onDismissRequest = { showReturnHomeDialog = false },
            title = { Text("Return to Home?", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Text("The assessment has been saved on this device.", color = Color(0xFF444746), fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = { showReturnHomeDialog = false; viewModel.resetAssessment(); onReturnHome() },
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
        DisclaimerBanner()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header row: Case ID + Generate New
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFF0F1FA)) {
                    Text(
                        caseLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 12.sp,
                        color = NavyBlue,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (displayRole.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFF5F5F5)) {
                        Text(
                            displayRole,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            fontSize = 12.sp,
                            color = Color(0xFF444746)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Clinical Guidance", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Text("Based on symptoms and assessment", fontSize = 13.sp, color = Color(0xFF9E9E9E))

            Spacer(modifier = Modifier.height(14.dp))

            // Triage badge
            if (guidance.triageLevel.isNotBlank()) {
                Surface(shape = RoundedCornerShape(8.dp), color = triageColor, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                guidance.triageLevel.uppercase(),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (guidance.possibleCondition.isNotBlank()) {
                                Text(
                                    "· ${guidance.possibleCondition}",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }
                        if (guidance.confidence.isNotBlank()) {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color.White.copy(alpha = 0.2f)) {
                                Text(
                                    guidance.confidence.replaceFirstChar { it.uppercase() },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 11.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Danger signs status
            if (hasDangerSigns) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFDE8E8),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFFFCDD2), RoundedCornerShape(8.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "${uiState.confirmedSigns.size} Danger Sign${if (uiState.confirmedSigns.size > 1) "s" else ""} Flagged — Escalate if necessary",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DangerRed
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFE8F5E9),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(10.dp).background(Color(0xFF2E7D32), CircleShape))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "No Danger Signs Flagged — Continue monitoring the patient",
                            fontSize = 13.sp,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Patient Summary
            Text("Patient Summary", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF9F9F9), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SummaryRow("Symptoms", uiState.symptoms.ifBlank { "—" })
                    if (uiState.age != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        SummaryRow("Age Group", uiState.age!!.label)
                    }
                    if (uiState.sex != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        SummaryRow("Sex", uiState.sex!!.label)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Suggested Next Checks (navy/blue box)
            if (guidance.recommendedNextSteps.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFE8EAF6),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Suggested Next Checks", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NavyBlue)
                        Spacer(modifier = Modifier.height(8.dp))
                        guidance.recommendedNextSteps.forEach { step ->
                            GuidanceBullet(text = step, color = NavyBlue)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Suggested Actions (orange box)
            if (guidance.suggestedTreatment.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFF8E1),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Suggested Actions", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7B4F00))
                        Spacer(modifier = Modifier.height(8.dp))
                        guidance.suggestedTreatment.forEach { action ->
                            GuidanceBullet(text = action, color = Color(0xFF5D4037))
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Why This Matters
            val whyText = guidance.whyItMatters.ifBlank {
                "Early and accurate triage helps prevent deterioration. Acting on clinical guidance supports better patient outcomes and reduces referral delays."
            }
            Column {
                Text("Why This Matters", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.height(6.dp))
                Text(whyText, fontSize = 13.sp, color = Color(0xFF444746), lineHeight = 19.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clickable { flagged = !flagged }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Flag,
                        contentDescription = null,
                        tint = if (flagged) OrangeGold else Color(0xFF9E9E9E),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (flagged) "Guidance flagged" else "Flag this guidance",
                        fontSize = 13.sp,
                        color = if (flagged) OrangeGold else Color(0xFF9E9E9E)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE0E0E0)))
            Spacer(modifier = Modifier.height(16.dp))

            // Clinician Confirmation (embedded)
            Text("Clinician Confirmation", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (uiState.clinicianAcknowledged) NavyBlue else Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                    .clickable { viewModel.setClinicalAcknowledged(!uiState.clinicianAcknowledged) }
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .border(1.5.dp, if (uiState.clinicianAcknowledged) NavyBlue else Color(0xFFBDBDBD), RoundedCornerShape(4.dp))
                        .background(if (uiState.clinicianAcknowledged) NavyBlue else Color.White, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.clinicianAcknowledged) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "I acknowledge that I am responsible for the patient's care. This guidance supports my judgment but does not replace it.",
                    fontSize = 13.sp,
                    color = Color(0xFF1F1F1F),
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.treatmentAdministered,
                onValueChange = { viewModel.setTreatmentAdministered(it) },
                placeholder = { Text("If you administered treatment to the patient, document it here.", color = Color(0xFF9E9E9E), fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().height(90.dp),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NavyBlue,
                    unfocusedBorderColor = Color(0xFFE0E0E0),
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                )
            )

            // Saved banner
            if (isSaved) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFE8F5E9), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Case saved. Will sync when online.",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = Color(0xFF2E7D32)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Bottom actions
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            // Uganda sync status — visible only once the case has been saved.
            if (isSaved) {
                val context = LocalContext.current
                val isOnline = com.google.ai.edge.gallery.healthdemo.data.UgandaApiSync.isOnline(context)
                val syncText = if (isOnline) "Case saved and synced." else "Case saved locally. Will sync when connected."
                val syncColor = if (isOnline) Color(0xFF2E7D32) else Color(0xFFE65100)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isOnline) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = syncText,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = syncColor,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Button(
                onClick = onConfirmOutcome,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyBlue)
            ) {
                Text("Save Assessment", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showReferralSheet = true },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NavyBlue)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = NavyBlue, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Refer Patient", fontSize = 13.sp, color = NavyBlue, fontWeight = FontWeight.Medium)
                }
                OutlinedButton(
                    onClick = { showPauseSheet = true },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OrangeGold)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null, tint = OrangeGold, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pause Assessment", fontSize = 13.sp, color = OrangeGold, fontWeight = FontWeight.Medium)
                }
            }


            Spacer(modifier = Modifier.height(4.dp))

            TextButton(
                onClick = { showReturnHomeDialog = true },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Return to Home", color = Color(0xFF9E9E9E), fontSize = 14.sp)
            }
        }
    }

    // ── Referral Sheet ─────────────────────────────────────────────────────────
    if (showReferralSheet) {
        ModalBottomSheet(
            onDismissRequest = { showReferralSheet = false },
            sheetState = referralSheetState,
            containerColor = Color.White
        ) {
            ReferralSheet(
                onSave = { referral: ReferralInfo ->
                    val savedId = uiState.savedAssessment?.id
                    if (savedId != null) repository.updateReferral(savedId, referral)
                    viewModel.setReferralInfo(referral)
                    scope.launch { referralSheetState.hide() }.invokeOnCompletion {
                        showReferralSheet = false
                        onReferralSaved(savedId ?: "")
                    }
                },
                onCancel = {
                    scope.launch { referralSheetState.hide() }.invokeOnCompletion { showReferralSheet = false }
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
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = Color(0xFF9E9E9E), modifier = Modifier.width(90.dp))
        Text(value, fontSize = 13.sp, color = Color(0xFF1F1F1F), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GuidanceBullet(text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.padding(top = 6.dp).size(5.dp).background(color.copy(alpha = 0.7f), CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = Color(0xFF1F1F1F), lineHeight = 19.sp)
    }
}
