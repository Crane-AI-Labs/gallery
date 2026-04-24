package com.google.ai.edge.gallery.healthdemo.ui.screens

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.analytics.HealthDemoAnalytics

private val NavyBlue = Color(0xFF0D1B5E)

@Composable
fun FeedbackScreen(
    onSubmit: () -> Unit,
    onSkip: () -> Unit
) {
    var starRating by remember { mutableIntStateOf(0) }
    var guidanceUsefulAnswer by remember { mutableStateOf<String?>(null) }
    var treatmentFollowedAnswer by remember { mutableStateOf<String?>(null) }
    var easeOfUseAnswer by remember { mutableStateOf<String?>(null) }
    var additionalComments by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            // Makerere v2 #5: feedback has the "Additional comments" text
            // field; imePadding keeps Submit/Skip reachable when the
            // keyboard is open.
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Quick Feedback",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Help us improve Ease Health for frontline health workers",
                fontSize = 14.sp,
                color = Color(0xFF444746)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Privacy notice
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF0F1FA),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Your feedback is stored locally and helps improve the tool. No patient data is included.",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp,
                    color = NavyBlue
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Star rating
            Text(
                text = "Overall Experience",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                repeat(5) { i ->
                    Icon(
                        imageVector = if (i < starRating) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "${i + 1} stars",
                        tint = if (i < starRating) Color(0xFFFBC02D) else Color(0xFFBDBDBD),
                        modifier = Modifier
                            .size(36.dp)
                            .clickable { starRating = i + 1 }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Was the guidance useful?
            FeedbackQuestion(
                question = "Was the guidance useful for your clinical decision?",
                options = listOf("Yes, very useful", "Somewhat useful", "Not very useful", "Not useful at all"),
                selectedOption = guidanceUsefulAnswer,
                onSelect = { guidanceUsefulAnswer = it }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Did you follow the suggested treatment steps?
            FeedbackQuestion(
                question = "Did you follow the suggested treatment steps?",
                options = listOf("Yes, followed all steps", "Followed most steps", "Followed some steps", "Did not follow"),
                selectedOption = treatmentFollowedAnswer,
                onSelect = { treatmentFollowedAnswer = it }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // How easy was the app to use?
            FeedbackQuestion(
                question = "How easy was the app to use?",
                options = listOf("Very easy", "Easy", "Neutral", "Difficult", "Very difficult"),
                selectedOption = easeOfUseAnswer,
                onSelect = { easeOfUseAnswer = it }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Additional comments
            Text(
                text = "Additional Comments",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )
            Text(
                text = "(Optional)",
                fontSize = 13.sp,
                color = Color(0xFF9E9E9E)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = additionalComments,
                onValueChange = { additionalComments = it },
                placeholder = { Text("Share any other thoughts here...", color = Color(0xFF9E9E9E)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NavyBlue,
                    unfocusedBorderColor = Color(0xFFE0E0E0),
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                )
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Bottom buttons
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = {
                    HealthDemoAnalytics.logFeedbackSubmitted(
                        rating = "$starRating stars"
                    )
                    onSubmit()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyBlue)
            ) {
                Text(
                    text = "Submit & Start New Assessment",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(
                onClick = onSkip,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "Skip Feedback",
                    color = NavyBlue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun FeedbackQuestion(
    question: String,
    options: List<String>,
    selectedOption: String?,
    onSelect: (String) -> Unit
) {
    Text(
        text = question,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1F1F1F)
    )
    Spacer(modifier = Modifier.height(8.dp))
    options.forEach { option ->
        RadioRow(
            label = option,
            selected = selectedOption == option,
            onClick = { onSelect(option) }
        )
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                if (selected) NavyBlue else Color(0xFFBDBDBD)
            ),
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
