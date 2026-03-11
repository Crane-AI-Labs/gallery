package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel

private val NavyBlue = Color(0xFF0D1B5E)

@Composable
fun GuidanceScreen(
    viewModel: HealthDemoViewModel,
    repository: HealthDemoRepository,
    onCreateNew: () -> Unit,
    onReturnHome: () -> Unit,
    onFeedback: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val guidance = uiState.guidance ?: return
    val isSaved = uiState.savedAssessment != null
    var showNewAssessmentDialog by remember { mutableStateOf(false) }

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

            // Saved banner (green) or Disclaimer (outlined)
            if (isSaved) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFE8F5E9),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Assessment saved on this device.",
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

            Spacer(modifier = Modifier.height(20.dp))

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
                text = "Recommended Next Steps",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
            Spacer(modifier = Modifier.height(8.dp))
            guidance.recommendedNextSteps.forEach { item ->
                BulletItem(text = item)
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Bottom actions
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = {
                    if (!isSaved) {
                        val assessment = viewModel.buildSavedAssessment()
                        repository.save(assessment)
                        viewModel.markSaved(assessment)
                    }
                },
                enabled = !isSaved,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NavyBlue,
                    disabledContainerColor = Color(0xFF9E9E9E)
                )
            ) {
                Text(
                    text = "Save Assessment",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    if (!isSaved) {
                        showNewAssessmentDialog = true
                    } else {
                        viewModel.resetAssessment()
                        onFeedback()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, NavyBlue)
            ) {
                Text(
                    text = "Create New Assessment",
                    fontSize = 16.sp,
                    color = NavyBlue,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onReturnHome,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "Return To Home",
                    color = NavyBlue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
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
