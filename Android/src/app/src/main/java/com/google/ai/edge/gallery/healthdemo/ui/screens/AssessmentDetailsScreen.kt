package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NavyBlue = Color(0xFF0D1B5E)

@Composable
fun AssessmentDetailsScreen(
    assessmentId: String,
    repository: HealthDemoRepository,
    onNavigateBack: () -> Unit
) {
    val assessment = repository.getById(assessmentId) ?: run {
        // Assessment not found
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .statusBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Assessment not found.", color = Color(0xFF444746))
        }
        return
    }

    val dateStr = SimpleDateFormat("MMMM d, yyyy 'at' hh:mm a", Locale.getDefault())
        .format(Date(assessment.timestamp))

    val vs = assessment.vitalSigns

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF1F1F1F)
                )
            }
            Text(
                text = "Assessment Details",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = dateStr,
                fontSize = 13.sp,
                color = Color(0xFF444746)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Disclaimer
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
                    text = "This guidance does not replace clinical judgment.",
                    fontSize = 13.sp,
                    color = Color(0xFF444746)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Presenting Symptoms
            SectionTitle("Presenting Symptoms")
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = assessment.symptoms, fontSize = 15.sp, color = Color(0xFF1F1F1F))

            Spacer(modifier = Modifier.height(20.dp))

            // Patient Information table
            SectionTitle("Patient Information")
            Spacer(modifier = Modifier.height(8.dp))
            InfoTable(
                rows = listOf(
                    "Age:" to (assessment.age?.label ?: "N/A"),
                    "Sex:" to (assessment.sex?.label ?: "N/A")
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Vital Signs table
            SectionTitle("Vital Signs")
            Spacer(modifier = Modifier.height(8.dp))
            InfoTable(
                rows = listOf(
                    "Temperature:" to vs.temperature.ifBlank { "N/A" },
                    "Pulse:" to vs.pulseRate.ifBlank { "N/A" },
                    "Blood Pressure:" to vs.bloodPressure.ifBlank { "N/A" },
                    "Respiratory Rate:" to vs.respiratoryRate.ifBlank { "N/A" }
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Suggested Guidance
            Text(
                text = "Suggested Guidance",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )

            Spacer(modifier = Modifier.height(14.dp))

            SectionTitle("Possible Condition")
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = assessment.guidance.possibleCondition, fontSize = 15.sp, color = Color(0xFF1F1F1F))

            Spacer(modifier = Modifier.height(16.dp))

            SectionTitle("Suggested Treatment")
            Spacer(modifier = Modifier.height(8.dp))
            assessment.guidance.suggestedTreatment.forEach { item ->
                DetailBulletItem(text = item)
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            SectionTitle("Recommended Next Steps")
            Spacer(modifier = Modifier.height(8.dp))
            assessment.guidance.recommendedNextSteps.forEach { item ->
                DetailBulletItem(text = item)
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.5.dp, NavyBlue)
            ) {
                Text("Back To Saved Results", color = NavyBlue, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1F1F1F)
    )
}

@Composable
private fun InfoTable(rows: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
    ) {
        rows.forEachIndexed { index, (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = label, fontSize = 14.sp, color = Color(0xFF444746), modifier = Modifier.weight(1f))
                Text(text = value, fontSize = 14.sp, color = Color(0xFF1F1F1F), fontWeight = FontWeight.Medium)
            }
            if (index < rows.lastIndex) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFFE0E0E0))
                )
            }
        }
    }
}

@Composable
private fun DetailBulletItem(text: String) {
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
