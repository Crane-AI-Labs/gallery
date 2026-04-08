package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.ReferralDestination
import com.google.ai.edge.gallery.healthdemo.data.ReferralInfo
import com.google.ai.edge.gallery.healthdemo.data.ReferralReason
import com.google.ai.edge.gallery.healthdemo.data.ReferralUrgency

private val NavyBlue = Color(0xFF0D1B5E)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReferralSheet(
    onSave: (ReferralInfo) -> Unit,
    onCancel: () -> Unit
) {
    var urgency by remember { mutableStateOf<ReferralUrgency?>(null) }
    var destination by remember { mutableStateOf<ReferralDestination?>(null) }
    var destinationExpanded by remember { mutableStateOf(false) }
    var selectedReasons by remember { mutableStateOf<Set<ReferralReason>>(emptySet()) }
    var notes by remember { mutableStateOf("") }

    val canSave = destination != null || selectedReasons.isNotEmpty()

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
            Column(modifier = Modifier.weight(1f)) {
                Text("Refer Patient Elsewhere", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Text("Document referral details for this case", fontSize = 13.sp, color = Color(0xFF9E9E9E))
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {

            // Urgency Level
            Text("Urgency Level", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReferralUrgency.entries.forEach { level ->
                    val selected = urgency == level
                    val (bg, fg) = when {
                        !selected -> Pair(Color.White, Color(0xFF1F1F1F))
                        level == ReferralUrgency.Emergency -> Pair(Color(0xFFD32F2F), Color.White)
                        level == ReferralUrgency.Urgent -> Pair(Color(0xFFE65100), Color.White)
                        else -> Pair(NavyBlue, Color.White)
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = bg,
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, if (selected) Color.Transparent else Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                            .clickable { urgency = level }
                    ) {
                        Text(
                            level.label,
                            modifier = Modifier.padding(vertical = 12.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = fg
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Referral Destination
            Text("Referral Destination", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = destinationExpanded,
                onExpandedChange = { destinationExpanded = it }
            ) {
                OutlinedTextField(
                    value = destination?.label ?: "",
                    onValueChange = {},
                    readOnly = true,
                    placeholder = { Text("Select destination...", color = Color(0xFF9E9E9E)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = destinationExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NavyBlue,
                        unfocusedBorderColor = Color(0xFFE0E0E0),
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black
                    )
                )
                ExposedDropdownMenu(expanded = destinationExpanded, onDismissRequest = { destinationExpanded = false }) {
                    ReferralDestination.entries.forEach { dest ->
                        DropdownMenuItem(
                            text = { Text(dest.label) },
                            onClick = { destination = dest; destinationExpanded = false }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Reason for Referral
            Text("Reason for Referral", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReferralReason.entries.forEach { reason ->
                    val selected = reason in selectedReasons
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) NavyBlue else Color.White,
                        modifier = Modifier
                            .border(1.dp, if (selected) NavyBlue else Color(0xFFE0E0E0), RoundedCornerShape(20.dp))
                            .clickable { selectedReasons = if (selected) selectedReasons - reason else selectedReasons + reason }
                    ) {
                        Text(
                            reason.label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontSize = 13.sp,
                            color = if (selected) Color.White else Color(0xFF1F1F1F),
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Additional Notes
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Additional Notes", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.width(6.dp))
                Text("(Optional)", fontSize = 13.sp, color = Color(0xFF9E9E9E))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { if (it.length <= 500) notes = it },
                placeholder = { Text("e.g. patient requires wheelchair access, accompanied by carer...", color = Color(0xFF9E9E9E), fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().height(110.dp),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NavyBlue,
                    unfocusedBorderColor = Color(0xFFE0E0E0),
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                )
            )
            Text(
                "${notes.length}/500",
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.align(Alignment.End)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    onSave(ReferralInfo(
                        urgency = urgency ?: ReferralUrgency.Routine,
                        destination = destination,
                        reasons = selectedReasons.toList(),
                        notes = notes
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
                Text("Save Referral", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", fontSize = 15.sp, color = Color(0xFF444746), textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
