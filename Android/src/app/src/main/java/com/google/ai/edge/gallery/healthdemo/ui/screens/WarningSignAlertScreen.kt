package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val WarnOrange = Color(0xFFE65100)
private val WarnOrangeLight = Color(0xFFFFF3E0)

@Composable
fun WarningSignAlertScreen(
    sign: String,
    currentIndex: Int,
    total: Int,
    onPresent: () -> Unit,
    onNotPresent: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Orange header band
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = WarnOrange
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Warning Sign Detected",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (total > 1) {
                            Text(
                                text = "${currentIndex + 1} of $total",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = WarnOrangeLight,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = sign,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarnOrange
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This finding warrants close monitoring. Please confirm whether this sign is present in the patient.",
                        fontSize = 14.sp,
                        color = Color(0xFFBF360C),
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF3E5F5),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "If present: add to the monitoring list and review if the patient's condition changes.",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp,
                    color = Color(0xFF6A1B9A)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Is this sign present in the patient?",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = onPresent,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WarnOrange)
            ) {
                Text("Yes, Present — Add to Monitor List", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onNotPresent,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("No, Not Present", fontSize = 15.sp, color = Color(0xFF1F1F1F), fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Dismiss for Now", color = Color(0xFF444746), fontSize = 14.sp)
            }
        }
    }
}
