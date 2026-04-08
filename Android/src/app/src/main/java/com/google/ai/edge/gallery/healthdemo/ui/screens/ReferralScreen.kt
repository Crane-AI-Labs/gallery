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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.google.ai.edge.gallery.healthdemo.data.ReferralDestination
import com.google.ai.edge.gallery.healthdemo.data.ReferralInfo
import com.google.ai.edge.gallery.healthdemo.data.ReferralReason
import com.google.ai.edge.gallery.healthdemo.data.ReferralUrgency

private val NavyBlue = Color(0xFF0D1B5E)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReferralScreen(
    onSave: (ReferralInfo) -> Unit,
    onCancel: () -> Unit
) {
    var urgency by remember { mutableStateOf(ReferralUrgency.Routine) }
    var destination by remember { mutableStateOf<ReferralDestination?>(null) }
    var destinationExpanded by remember { mutableStateOf(false) }
    var selectedReasons by remember { mutableStateOf<Set<ReferralReason>>(emptySet()) }
    var notes by remember { mutableStateOf("") }

    val canSave = destination != null || selectedReasons.isNotEmpty()

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

            Text("Refer Patient Elsewhere", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Text("Document referral details for this case", fontSize = 14.sp, color = Color(0xFF444746))

            Spacer(modifier = Modifier.height(24.dp))

            // Urgency level
            Text("Urgency Level", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
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
                            .border(
                                1.dp,
                                if (selected) Color.Transparent else Color(0xFFE0E0E0),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { urgency = level }
                    ) {
                        Text(
                            text = level.label,
                            modifier = Modifier.padding(vertical = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = fg
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Destination
            Text("Referral Destination", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = destinationExpanded,
                onExpandedChange = { destinationExpanded = it }
            ) {
                OutlinedTextField(
                    value = destination?.label ?: "",
                    onValueChange = {},
                    readOnly = true,
                    placeholder = { Text("Select destination", color = Color(0xFF9E9E9E)) },
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
                ExposedDropdownMenu(
                    expanded = destinationExpanded,
                    onDismissRequest = { destinationExpanded = false }
                ) {
                    ReferralDestination.entries.forEach { dest ->
                        DropdownMenuItem(
                            text = { Text(dest.label) },
                            onClick = {
                                destination = dest
                                destinationExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Reasons
            Text("Reason for Referral", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReferralReason.entries.forEach { reason ->
                    val selected = reason in selectedReasons
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) Color(0xFFF0F1FA) else Color.White,
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (selected) NavyBlue else Color(0xFFE0E0E0),
                                RoundedCornerShape(20.dp)
                            )
                            .clickable {
                                selectedReasons = if (selected) selectedReasons - reason else selectedReasons + reason
                            }
                    ) {
                        Text(
                            text = reason.label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            fontSize = 13.sp,
                            color = if (selected) NavyBlue else Color(0xFF1F1F1F),
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Additional notes
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Additional Notes", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                Spacer(modifier = Modifier.width(8.dp))
                Text("(Optional)", fontSize = 13.sp, color = Color(0xFF9E9E9E))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { if (it.length <= 500) notes = it },
                placeholder = { Text("Additional context for the receiving facility...", color = Color(0xFF9E9E9E)) },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NavyBlue,
                    unfocusedBorderColor = Color(0xFFE0E0E0),
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                )
            )
            Text(
                text = "${notes.length}/500",
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.align(Alignment.End)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = {
                    onSave(ReferralInfo(
                        urgency = urgency,
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

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Cancel", fontSize = 15.sp, color = NavyBlue, fontWeight = FontWeight.Medium)
            }
        }
    }
}
