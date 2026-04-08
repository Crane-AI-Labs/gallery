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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.google.ai.edge.gallery.healthdemo.data.IssueTag

private val NavyBlue = Color(0xFF0D1B5E)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClinicianConfirmationScreen(
    onSave: (ClinicianConfirmation) -> Unit
) {
    var understood by remember { mutableStateOf(false) }
    var guidanceUsed by remember { mutableStateOf<GuidanceUsed?>(null) }
    var finalAction by remember { mutableStateOf<FinalAction?>(null) }
    var selectedTags by remember { mutableStateOf<Set<IssueTag>>(emptySet()) }

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

            Text("Confirm Outcome", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Text("Record how this guidance was used", fontSize = 14.sp, color = Color(0xFF444746))

            Spacer(modifier = Modifier.height(20.dp))

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
                    text = "I understand this tool provides guidance only and I am responsible for all clinical decisions.",
                    fontSize = 14.sp,
                    color = Color(0xFF1F1F1F),
                    lineHeight = 20.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Did you use the guidance?
            SectionLabel("Did you use the guidance?")
            Spacer(modifier = Modifier.height(10.dp))
            GuidanceUsed.entries.forEach { option ->
                RadioOptionRow(
                    label = option.label,
                    selected = guidanceUsed == option,
                    onClick = { guidanceUsed = option }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Final Action Taken
            SectionLabel("Final Action Taken")
            Spacer(modifier = Modifier.height(10.dp))
            FinalAction.entries.forEach { action ->
                RadioOptionRow(
                    label = action.label,
                    selected = finalAction == action,
                    onClick = { finalAction = action }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Optional issue tagging
            SectionLabel("Optional Issue Tagging")
            Text("Flag any concerns with this guidance (optional)", fontSize = 13.sp, color = Color(0xFF444746))
            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IssueTag.entries.forEach { tag ->
                    val selected = tag in selectedTags
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) Color(0xFFFDE8E8) else Color.White,
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (selected) Color(0xFFD32F2F) else Color(0xFFE0E0E0),
                                RoundedCornerShape(20.dp)
                            )
                            .clickable {
                                selectedTags = if (selected) selectedTags - tag else selectedTags + tag
                            }
                    ) {
                        Text(
                            text = tag.label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            fontSize = 13.sp,
                            color = if (selected) Color(0xFFD32F2F) else Color(0xFF1F1F1F)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = {
                    onSave(ClinicianConfirmation(
                        understood = understood,
                        guidanceUsed = guidanceUsed,
                        finalAction = finalAction,
                        issueTags = selectedTags.toList()
                    ))
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NavyBlue,
                    disabledContainerColor = Color(0xFF9E9E9E)
                )
            ) {
                Text(
                    text = if (finalAction == FinalAction.Referred) "Save & Add Referral Details" else "Save Case",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
}

@Composable
private fun RadioOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) NavyBlue else Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = Color.White,
            border = BorderStroke(1.5.dp, if (selected) NavyBlue else Color(0xFFBDBDBD)),
            modifier = Modifier.size(20.dp)
        ) {
            if (selected) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = NavyBlue,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                ) {}
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, fontSize = 14.sp, color = Color(0xFF1F1F1F))
    }
}
