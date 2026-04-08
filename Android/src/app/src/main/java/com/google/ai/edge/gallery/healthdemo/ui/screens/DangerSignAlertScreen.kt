package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.google.ai.edge.gallery.healthdemo.data.SIGN_INFO

private val DangerRed = Color(0xFFD32F2F)

@Composable
fun DangerSignAlertSheet(
    signName: String,
    onConfirm: () -> Unit,
    onNotPresent: () -> Unit,
    onDismiss: () -> Unit
) {
    val info = SIGN_INFO[signName]
    var selected by remember { mutableStateOf<Boolean?>(null) } // true = yes, false = no

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding()
    ) {
        // Red header band
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DangerRed)
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔔", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "⚠ Danger Sign Detected",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                signName,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (info != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    info.description,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    lineHeight = 20.sp
                )
            }
        }

        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Please confirm before proceeding",
                fontSize = 14.sp,
                color = Color(0xFF444746)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Yes / No buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                // Yes, present
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected == true) DangerRed else Color.White,
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, if (selected == true) DangerRed else Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                        .clickable { selected = true }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Yes, present",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected == true) Color.White else Color(0xFF1F1F1F),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Escalate flow",
                            fontSize = 12.sp,
                            color = if (selected == true) Color.White.copy(0.8f) else Color(0xFF9E9E9E),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // No, not present
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected == false) Color(0xFFF5F5F5) else Color.White,
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                        .clickable { selected = false }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "No, not present",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1F1F1F),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Remove flag",
                            fontSize = 12.sp,
                            color = Color(0xFF9E9E9E),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Confirm button
            Button(
                onClick = {
                    when (selected) {
                        true -> onConfirm()
                        false -> onNotPresent()
                        null -> {}
                    }
                },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DangerRed,
                    disabledContainerColor = Color(0xFFE0E0E0)
                )
            ) {
                Text(
                    if (selected == null) "Select an option"
                    else if (selected == true) "Confirm & Escalate"
                    else "Confirm",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected == null) Color(0xFF9E9E9E) else Color.White
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Dismiss for now", fontSize = 13.sp, color = Color(0xFF9E9E9E), textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
