package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.AppSettings

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)
private val MutedText = Color(0xFF424242)

/**
 * First-launch consent screen. Required for:
 *   - DPPA 2019 §9 — lawful basis for processing (informed consent)
 *   - DPPA 2019 §27 — explicit disclosure of automated decision-making
 *
 * Covers: AI-assisted guidance, on-device processing, what gets uploaded
 * to the Kampala backend, data-minimisation (on-device regex redaction
 * + server NER), retention (tier_1: 90 days identified, tier_2: 2 years
 * de-identified analytics), and that the clinician makes the final call.
 *
 * Content is versioned via [AppSettings.CURRENT_CONSENT_VERSION] so a
 * materially new version forces a re-prompt.
 */
@Composable
fun ConsentScreen(onAccepted: () -> Unit) {
    val context = LocalContext.current
    var clinicianChecked by remember { mutableStateOf(false) }
    var dataChecked by remember { mutableStateOf(false) }
    val enabled = clinicianChecked && dataChecked

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NavyBlue)
                .padding(24.dp)
        ) {
            Text(
                text = "Before you start",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Please read and accept to continue.",
                color = Color(0xFFBBD2FF),
                fontSize = 14.sp,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Section(
                title = "AI-assisted guidance",
                body = "Ease Health uses an on-device AI model — Google MedGemma 4B (4-bit) — to suggest a possible condition, suggested treatment, recommended next steps, and a triage level (Emergency referral / Urgent clinic visit / Routine care / Home care). The model is imperfect, especially on rare presentations, unusual phrasing, or poor-quality images. You are the health worker. You make the final clinical decision.",
            )
            Section(
                title = "What stays on the phone",
                body = "Symptoms, vitals, photos, and AI guidance are stored locally in an encrypted database. You can view your own saved cases offline.",
            )
            Section(
                title = "What is sent to the server",
                body = "When the phone is online, assessments are sent to Ease Health's server in Kampala, Uganda, over a pinned TLS connection. The phone strips out detected phone numbers, national IDs, passport numbers, emails, and dates before uploading. A second scan on the server removes names that slip through.",
            )
            Section(
                title = "Why we collect it",
                body = "• To help the clinical team review cases and spot quality issues.\n• To publish de-identified statistics (for example, triage counts by district) to Ministry of Health partners.\n• To improve the AI model's guidance over time.",
            )
            Section(
                title = "Where it is stored",
                body = "All data stays on servers inside Uganda, in line with the Data Protection and Privacy Act 2019.",
            )
            Section(
                title = "How long we keep it",
                body = "• Identified records (with your device, timestamp, rough location): 90 days, then deleted.\n• De-identified records (no names, no phone numbers, rounded to district and week): up to 2 years, for quality review.",
            )
            Section(
                title = "Your rights",
                body = "You can stop using the app at any time. You can erase every server-side record tied to your device from inside the app at Settings → Delete my data. You can turn off location sharing in device settings.",
            )
            Spacer(Modifier.height(16.dp))

            CheckRow(
                checked = clinicianChecked,
                onCheckedChange = { clinicianChecked = it },
                text = "I understand the guidance is AI-generated and that I, the health worker, make the final clinical decision."
            )
            Spacer(Modifier.height(12.dp))
            CheckRow(
                checked = dataChecked,
                onCheckedChange = { dataChecked = it },
                text = "I consent to my assessments being processed and stored as described above."
            )
        }

        Button(
            onClick = {
                AppSettings.acceptConsent(context)
                onAccepted()
            },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = OrangeGold,
                disabledContainerColor = Color(0xFFEEEEEE),
                contentColor = Color.White,
                disabledContentColor = Color(0xFF9E9E9E),
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .height(56.dp)
        ) {
            Text("I agree and want to continue", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = NavyBlue,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            fontSize = 14.sp,
            color = MutedText,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            color = MutedText,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}
