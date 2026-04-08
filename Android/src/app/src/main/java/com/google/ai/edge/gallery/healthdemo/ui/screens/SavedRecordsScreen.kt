package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.FinalAction
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.PausedConsultation
import com.google.ai.edge.gallery.healthdemo.data.SavedAssessment
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)
private val DangerRed = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRecordsScreen(
    repository: HealthDemoRepository,
    onNavigateBack: () -> Unit,
    onResumePaused: (String) -> Unit = {}
) {
    val assessments by repository.savedAssessments.collectAsState()
    val pausedList by repository.pausedConsultations.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var selectedAssessmentId by remember { mutableStateOf<String?>(null) }

    val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val selectedAssessment = selectedAssessmentId?.let { id -> assessments.find { it.id == id } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF1F1F1F))
            }
            Column {
                Text("Cases History", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Text(
                    "${assessments.size} saved · ${pausedList.size} paused",
                    fontSize = 13.sp,
                    color = Color(0xFF9E9E9E)
                )
            }
        }

        // Tabs
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            TabBtn(
                label = "Saved",
                count = assessments.size,
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TabBtn(
                label = "Paused",
                count = pausedList.size,
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                modifier = Modifier.weight(1f),
                accentColor = OrangeGold,
                showDot = pausedList.isNotEmpty()
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (selectedTab == 0) {
            if (assessments.isEmpty()) {
                EmptyMsg("No saved cases yet.")
            } else {
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    items(assessments) { assessment ->
                        Spacer(modifier = Modifier.height(10.dp))
                        CaseCard(
                            assessment = assessment,
                            onView = {
                                selectedAssessmentId = assessment.id
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        } else {
            if (pausedList.isEmpty()) {
                EmptyMsg("No paused consultations.")
            } else {
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    items(pausedList) { paused ->
                        Spacer(modifier = Modifier.height(10.dp))
                        PausedCaseCard(paused = paused, onResume = { onResumePaused(paused.id) })
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFE0E0E0))
            ) {
                Text("Back", color = Color(0xFF1F1F1F), fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
        }
    }

    // ── Case Detail Sheet ──────────────────────────────────────────────────────
    if (selectedAssessment != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedAssessmentId = null },
            sheetState = detailSheetState,
            containerColor = Color.White
        ) {
            AssessmentDetailSheet(
                assessment = selectedAssessment,
                onDismiss = {
                    scope.launch { detailSheetState.hide() }.invokeOnCompletion {
                        selectedAssessmentId = null
                    }
                }
            )
        }
    }
}

@Composable
private fun TabBtn(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = NavyBlue,
    showDot: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) accentColor else Color.White,
        modifier = modifier
            .border(1.dp, if (selected) accentColor else Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (showDot && !selected) {
                Surface(shape = RoundedCornerShape(50), color = accentColor, modifier = Modifier.size(7.dp)) {}
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Color.White else Color(0xFF1F1F1F)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selected) Color.White.copy(alpha = 0.25f) else Color(0xFFF0F0F0)
            ) {
                Text(
                    "$count",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    fontSize = 12.sp,
                    color = if (selected) Color.White else Color(0xFF444746),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun EmptyMsg(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message, color = Color(0xFF9E9E9E), fontSize = 15.sp)
    }
}

@Composable
private fun CaseCard(assessment: SavedAssessment, onView: () -> Unit) {
    val dateStr = SimpleDateFormat("MMM d, yyyy 'at' hh:mm a", Locale.getDefault())
        .format(Date(assessment.timestamp))
    val displayRole = if (assessment.role == PatientRole.Other && assessment.customRole.isNotBlank())
        assessment.customRole else assessment.role.label
    val hasDangerSigns = assessment.confirmedSigns.isNotEmpty()
    val finalAction = assessment.clinicianConfirmation?.finalAction
    val caseLabel = "#CASE${assessment.id.takeLast(6).uppercase()}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (hasDangerSigns) Color(0xFFFFCDD2) else Color(0xFFE0E0E0), RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(caseLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                    if (hasDangerSigns) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(50), color = DangerRed, modifier = Modifier.size(7.dp)) {}
                    }
                }
                Text(dateStr, fontSize = 12.sp, color = Color(0xFF9E9E9E))
                Text(displayRole, fontSize = 12.sp, color = Color(0xFF9E9E9E))
            }
            if (finalAction != null) {
                val (bg, fg) = when (finalAction) {
                    FinalAction.Referred -> Pair(Color(0xFFFFF3E0), OrangeGold)
                    FinalAction.Escalated -> Pair(Color(0xFFFDE8E8), DangerRed)
                    FinalAction.ManagedLocally -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32))
                    else -> Pair(Color(0xFFF5F5F5), Color(0xFF444746))
                }
                Surface(shape = RoundedCornerShape(8.dp), color = bg) {
                    Text(finalAction.label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 12.sp, color = fg, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(assessment.symptoms, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F1F1F))

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onView),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text("View", color = NavyBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = NavyBlue, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PausedCaseCard(paused: PausedConsultation, onResume: () -> Unit) {
    val timeStr = SimpleDateFormat("MMM d, yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(paused.timestamp))
    val displayRole = if (paused.role == PatientRole.Other && paused.customRole.isNotBlank())
        paused.customRole else paused.role.label

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFFFE0B2), RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Text(timeStr, fontSize = 12.sp, color = Color(0xFF9E9E9E))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            paused.symptoms.ifBlank { "No symptoms entered" },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1F1F1F)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF0F1FA)) {
                    Text(displayRole, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 12.sp, color = NavyBlue)
                }
                if (paused.pauseReason != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFFF3E0)) {
                        Text(paused.pauseReason.label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 12.sp, color = OrangeGold)
                    }
                }
            }
            Button(
                onClick = onResume,
                modifier = Modifier.height(36.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeGold),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 0.dp)
            ) {
                Text("Resume", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}
