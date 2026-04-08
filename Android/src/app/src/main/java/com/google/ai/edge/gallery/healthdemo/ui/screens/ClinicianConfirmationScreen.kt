package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.google.ai.edge.gallery.healthdemo.data.ClinicianConfirmation
import com.google.ai.edge.gallery.healthdemo.data.FinalAction
import com.google.ai.edge.gallery.healthdemo.data.GuidanceUsed
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import com.google.ai.edge.gallery.healthdemo.data.IssueTag
import com.google.ai.edge.gallery.healthdemo.data.ReferralInfo
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel
import kotlinx.coroutines.launch

private val NavyBlue = Color(0xFF0D1B5E)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ClinicianConfirmationScreen(
    viewModel: HealthDemoViewModel,
    repository: HealthDemoRepository,
    onCaseSaved: (String) -> Unit
) {
    var understood by remember { mutableStateOf(false) }
    var guidanceUsed by remember { mutableStateOf<GuidanceUsed?>(null) }
    var finalAction by remember { mutableStateOf<FinalAction?>(null) }
    var selectedTags by remember { mutableStateOf<Set<IssueTag>>(emptySet()) }
    var showReferralSheet by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<ClinicianConfirmation?>(null) }

    val referralSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val canSave = understood && finalAction != null

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

            Text("Clinician Confirmation", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Text(
                "Complete all required fields, optional ones will have the word \"optional\" next to them.",
                fontSize = 13.sp,
                color = Color(0xFF444746),
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Disclaimer checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (understood) NavyBlue else Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                    .clickable { understood = !understood }
                    .padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = if (understood) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (understood) NavyBlue else Color(0xFF9E9E9E),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "I understand this tool provides guidance only and I am responsible for clinical decisions.",
                    fontSize = 14.sp,
                    color = Color(0xFF1F1F1F),
                    lineHeight = 20.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Did you use the guidance?
            Text("Did you use the guidance?", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(10.dp))
            GuidanceUsed.entries.forEach { option ->
                RadioRow(
                    label = option.label,
                    selected = guidanceUsed == option,
                    onClick = { guidanceUsed = option }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Final Action Taken
            Text("Final Action Taken", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(10.dp))
            FinalAction.entries.forEach { action ->
                RadioRow(
                    label = action.label,
                    selected = finalAction == action,
                    onClick = { finalAction = action }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Optional Issue Tagging
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Optional Issue Tagging", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.width(6.dp))
                Text("(Optional)", fontSize = 13.sp, color = Color(0xFF9E9E9E))
            }
            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IssueTag.entries.forEach { tag ->
                    val selected = tag in selectedTags
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) Color(0xFFF5F5F5) else Color.White,
                        modifier = Modifier
                            .border(1.dp, if (selected) Color(0xFF444746) else Color(0xFFE0E0E0), RoundedCornerShape(20.dp))
                            .clickable { selectedTags = if (selected) selectedTags - tag else selectedTags + tag }
                    ) {
                        Text(
                            tag.label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            fontSize = 13.sp,
                            color = Color(0xFF1F1F1F)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = {
                    val confirmation = ClinicianConfirmation(
                        understood = understood,
                        guidanceUsed = guidanceUsed,
                        finalAction = finalAction,
                        issueTags = selectedTags.toList()
                    )
                    viewModel.setClinicianConfirmation(confirmation)
                    if (finalAction == FinalAction.Referred) {
                        pendingConfirmation = confirmation
                        showReferralSheet = true
                    } else {
                        val savedId = viewModel.uiState.value.savedAssessment?.id
                        if (savedId != null) {
                            repository.updateConfirmation(savedId, confirmation, null)
                        }
                        onCaseSaved(savedId ?: "")
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NavyBlue,
                    disabledContainerColor = Color(0xFF9E9E9E)
                )
            ) {
                Text("Save Case", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
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
                onSave = { referral ->
                    val confirmation = pendingConfirmation
                    val savedId = viewModel.uiState.value.savedAssessment?.id
                    if (savedId != null && confirmation != null) {
                        repository.updateConfirmation(savedId, confirmation, referral)
                    }
                    viewModel.setReferralInfo(referral)
                    scope.launch { referralSheetState.hide() }.invokeOnCompletion {
                        showReferralSheet = false
                        onCaseSaved(savedId ?: "")
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
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = Color.White,
            border = BorderStroke(1.5.dp, if (selected) NavyBlue else Color(0xFFBDBDBD)),
            modifier = Modifier.size(20.dp)
        ) {
            if (selected) {
                Surface(
                    shape = CircleShape,
                    color = NavyBlue,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                ) {}
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, color = Color(0xFF1F1F1F))
    }
}
