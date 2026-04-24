package com.google.ai.edge.gallery.healthdemo.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.CRITICAL_DANGER_SIGNS
import com.google.ai.edge.gallery.healthdemo.data.FinalAction
import com.google.ai.edge.gallery.healthdemo.data.HealthDemoRepository
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.data.SavedAssessment
import com.google.ai.edge.gallery.healthdemo.data.TraditionalMedicine
import com.google.ai.edge.gallery.llm.DeviceInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NavyBlue = Color(0xFF0D1B5E)
private val OrangeGold = Color(0xFFE6A817)
private val DangerRed = Color(0xFFD32F2F)

// ─── Sheet version (called from SavedRecordsScreen) ───────────────────────────
//
// Makerere v2 #2/#6 (2026-04-24): rebuilt on a LazyColumn so the nested
// vertical scroll cooperates with ModalBottomSheet's drag-to-dismiss (the
// Column+verticalScroll version fought the sheet on long content and
// oscillated visibly mid-scroll). Sections follow the clinical report
// template agreed with Makerere — header, role, patient, symptoms, vitals,
// confirmed signs, traditional medicine, AI assessment body, clinician
// confirmation, referral, location, session timeline, device footer.

@Composable
fun AssessmentDetailSheet(
    assessment: SavedAssessment,
    onDismiss: () -> Unit
) {
    val dateStr = SimpleDateFormat("MMM d, yyyy 'at' hh:mm a", Locale.getDefault())
        .format(Date(assessment.timestamp))
    val caseLabel = "#CASE${assessment.id.takeLast(6).uppercase()}"
    val displayRole = if (assessment.role == PatientRole.Other && assessment.customRole.isNotBlank())
        assessment.customRole else assessment.role.label
    val finalAction = assessment.clinicianConfirmation?.finalAction
    val g = assessment.guidance
    val triageColor = when {
        g.triageLevel.contains("Emergency", ignoreCase = true) -> DangerRed
        g.triageLevel.contains("Urgent", ignoreCase = true) -> Color(0xFFE65100)
        g.triageLevel.contains("Routine", ignoreCase = true) -> Color(0xFF2E7D32)
        g.triageLevel.contains("Home", ignoreCase = true) -> Color(0xFF1565C0)
        else -> Color(0xFF616161)
    }
    val context = LocalContext.current
    var deviceInfoExpanded by remember { mutableStateOf(false) }
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ── 1. Header ────────────────────────────────────────────────────────
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(caseLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                        if (g.triageLevel.isNotBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(6.dp), color = triageColor) {
                                Text(
                                    g.triageLevel.uppercase(),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text(dateStr, fontSize = 12.sp, color = Color(0xFF9E9E9E))
                }
                IconButton(onClick = onDismiss) {
                    Text("\u2715", fontSize = 18.sp, color = Color(0xFF9E9E9E))
                }
            }
        }

        // ── 2. Clinician role ────────────────────────────────────────────────
        item(key = "role") {
            SectionHeading("Clinician")
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF0F1FA)) {
                    Text(
                        displayRole,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 13.sp, color = NavyBlue, fontWeight = FontWeight.Medium
                    )
                }
                if (assessment.role == PatientRole.Other && assessment.customRole.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Selected Other · custom label",
                        fontSize = 11.sp, color = Color(0xFF9E9E9E)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                SectionDivider()
            }
        }

        // ── 3. Patient summary ───────────────────────────────────────────────
        item(key = "patient") {
            SectionHeading("Patient Summary")
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                val ageDisplay = buildString {
                    val y = assessment.ageYears.trim().toIntOrNull() ?: 0
                    val m = assessment.ageMonths.trim().toIntOrNull() ?: 0
                    if (y > 0) append("$y yr${if (y == 1) "" else "s"}")
                    if (m > 0) { if (isNotEmpty()) append(", "); append("$m mo") }
                }.ifBlank { null }

                if (ageDisplay != null) DetailInfoRow("Age") {
                    Text(ageDisplay, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                }
                if (assessment.age != null) DetailInfoRow("Age Group") {
                    Text(assessment.age.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                }
                if (assessment.sex != null) DetailInfoRow("Sex") {
                    Text(assessment.sex.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                }
                if (assessment.durationValue.isNotBlank()) DetailInfoRow("Duration") {
                    Text(
                        "${assessment.durationValue} ${assessment.durationUnit.label}",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                SectionDivider()
            }
        }

        // ── 4. Chief complaint / symptoms ────────────────────────────────────
        item(key = "symptoms") {
            SectionHeading("Chief Complaint")
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF5F5F5), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        assessment.symptoms.ifBlank { "—" },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        fontSize = 14.sp, color = Color(0xFF1F1F1F), lineHeight = 20.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                SectionDivider()
            }
        }

        // ── 5. Vital signs ───────────────────────────────────────────────────
        val vitalsEntries: List<Pair<String, String>> = buildList {
            if (assessment.vitalSigns.temperature.isNotBlank()) add("Temperature (°C)" to assessment.vitalSigns.temperature)
            if (assessment.vitalSigns.heartRate.isNotBlank()) add("Heart Rate (bpm)" to assessment.vitalSigns.heartRate)
            if (assessment.vitalSigns.respiratoryRate.isNotBlank()) add("Respiratory Rate" to assessment.vitalSigns.respiratoryRate)
            if (assessment.vitalSigns.bloodPressure.isNotBlank()) add("Blood Pressure (mmHg)" to assessment.vitalSigns.bloodPressure)
            if (assessment.vitalSigns.spO2.isNotBlank()) add("SpO2 (%)" to assessment.vitalSigns.spO2)
        }
        if (vitalsEntries.isNotEmpty()) {
            item(key = "vitals") {
                SectionHeading("Vital Signs")
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    vitalsEntries.forEach { (label, value) ->
                        DetailInfoRow(label) {
                            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionDivider()
                }
            }
        }

        // ── 6. Confirmed signs ───────────────────────────────────────────────
        if (assessment.confirmedSigns.isNotEmpty()) {
            item(key = "signs") {
                SectionHeading("Confirmed Signs")
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    assessment.confirmedSigns.forEach { sign ->
                        val isDanger = sign in CRITICAL_DANGER_SIGNS
                        val signColor = if (isDanger) DangerRed else OrangeGold
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
                            Box(modifier = Modifier.padding(top = 6.dp).size(6.dp).background(signColor, CircleShape))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(sign, fontSize = 13.sp, color = signColor, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionDivider()
                }
            }
        }

        // ── 7. Traditional medicine ──────────────────────────────────────────
        if (assessment.traditionalMedicine != null) {
            item(key = "trad-med") {
                SectionHeading("Traditional Medicine")
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    val label = when (assessment.traditionalMedicine) {
                        TraditionalMedicine.Yes -> {
                            if (assessment.traditionalMedicineDetails.isNotBlank())
                                "Yes — ${assessment.traditionalMedicineDetails}"
                            else "Yes"
                        }
                        TraditionalMedicine.No -> "No"
                    }
                    Text(label, fontSize = 14.sp, color = Color(0xFF1F1F1F), lineHeight = 19.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionDivider()
                }
            }
        }

        // ── 8. AI Assessment (the key block) ─────────────────────────────────
        item(key = "ai") {
            SectionHeading("AI Assessment")
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                if (g.triageLevel.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(8.dp), color = triageColor, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(g.triageLevel.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                if (g.possibleCondition.isNotBlank()) {
                                    Text("· ${g.possibleCondition}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f))
                                }
                            }
                            if (g.confidence.isNotBlank()) {
                                Surface(shape = RoundedCornerShape(4.dp), color = Color.White.copy(alpha = 0.2f)) {
                                    Text(
                                        g.confidence.replaceFirstChar { it.uppercase() },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        fontSize = 11.sp, color = Color.White,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (g.possibleCondition.isNotBlank()) {
                    DetailInfoRow("Possible Condition") {
                        Text(g.possibleCondition, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                    }
                }
                if (g.confidence.isNotBlank()) {
                    DetailInfoRow("Assessment Confidence") {
                        Text(
                            g.confidence.replaceFirstChar { it.uppercase() },
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F)
                        )
                    }
                }
                if (g.suggestedTreatment.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFFF8E1), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Suggested Treatment", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7B4F00))
                            Spacer(modifier = Modifier.height(6.dp))
                            g.suggestedTreatment.forEach { line ->
                                DetailBullet(text = line, color = Color(0xFF5D4037))
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
                if (g.recommendedNextSteps.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFE8EAF6), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Recommended Next Steps", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NavyBlue)
                            Spacer(modifier = Modifier.height(6.dp))
                            g.recommendedNextSteps.forEach { line ->
                                DetailBullet(text = line, color = NavyBlue)
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
                if (g.redFlags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFDE8E8), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Red Flags", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DangerRed)
                            Spacer(modifier = Modifier.height(6.dp))
                            g.redFlags.forEach { line ->
                                DetailBullet(text = line, color = DangerRed)
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
                if (g.whyItMatters.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Why It Matters", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(g.whyItMatters, fontSize = 13.sp, color = Color(0xFF444746), lineHeight = 18.sp)
                }
                if (g.disclaimer.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(g.disclaimer, fontSize = 11.sp, color = Color(0xFF9E9E9E), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                }
                Spacer(modifier = Modifier.height(16.dp))
                SectionDivider()
            }
        }

        // ── 9. Clinician confirmation ────────────────────────────────────────
        if (assessment.clinicianConfirmation != null) {
            item(key = "clinician") {
                SectionHeading("Clinician Confirmation")
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    val c = assessment.clinicianConfirmation
                    if (c.guidanceUsed != null) DetailInfoRow("Guidance Used") {
                        Text(c.guidanceUsed.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                    }
                    if (finalAction != null) DetailInfoRow("Final Action") {
                        val (bg, fg) = when (finalAction) {
                            FinalAction.Referred -> Pair(Color(0xFFFFF3E0), OrangeGold)
                            FinalAction.Escalated -> Pair(Color(0xFFFDE8E8), DangerRed)
                            FinalAction.ManagedLocally -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32))
                            else -> Pair(Color(0xFFF5F5F5), Color(0xFF444746))
                        }
                        Surface(shape = RoundedCornerShape(8.dp), color = bg) {
                            Text(
                                finalAction.label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 13.sp, color = fg, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    if (c.issueTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Issue Tags", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            c.issueTags.joinToString(" · ") { it.label },
                            fontSize = 13.sp, color = Color(0xFF1F1F1F)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionDivider()
                }
            }
        }

        // ── 10. Referral ─────────────────────────────────────────────────────
        if (assessment.referralInfo != null) {
            item(key = "referral") {
                val ref = assessment.referralInfo
                val urgencyColor = when (ref.urgency) {
                    com.google.ai.edge.gallery.healthdemo.data.ReferralUrgency.Emergency -> DangerRed
                    com.google.ai.edge.gallery.healthdemo.data.ReferralUrgency.Urgent -> OrangeGold
                    else -> Color(0xFF1565C0)
                }
                SectionHeading("Referral")
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = urgencyColor.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth().border(1.dp, urgencyColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(6.dp), color = urgencyColor) {
                                    Text(
                                        ref.urgency.label,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Referral Record", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
                            }
                            if (ref.destination != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Destination", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                                Text(ref.destination.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F1F1F))
                            }
                            if (ref.reasons.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Reasons", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                                Text(ref.reasons.joinToString(" · ") { it.label }, fontSize = 13.sp, color = Color(0xFF1F1F1F))
                            }
                            if (ref.notes.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Notes", fontSize = 12.sp, color = Color(0xFF9E9E9E))
                                Text(ref.notes, fontSize = 13.sp, color = Color(0xFF1F1F1F))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionDivider()
                }
            }
        }

        // ── 11. Location (district only) ─────────────────────────────────────
        if (!assessment.district.isNullOrBlank()) {
            item(key = "location") {
                SectionHeading("Location")
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    DetailInfoRow("District") {
                        Text(assessment.district, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionDivider()
                }
            }
        }

        // ── 12. Session Timeline ─────────────────────────────────────────────
        item(key = "timeline") {
            SectionHeading("Session Timeline")
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                val startMs = minOf(assessment.sessionStartTime, assessment.timestamp)
                val endMs = maxOf(assessment.sessionStartTime, assessment.timestamp)
                val startStr = SimpleDateFormat("MMM d, yyyy 'at' hh:mm a", Locale.getDefault())
                    .format(Date(startMs))
                val endStr = SimpleDateFormat("MMM d, yyyy 'at' hh:mm a", Locale.getDefault())
                    .format(Date(endMs))
                val durationMs = endMs - startMs
                val durationMin = (durationMs / 60000).toInt().coerceAtLeast(0)
                val durationLabel = when {
                    durationMs <= 0L -> null          // sessionStart == timestamp → unknown
                    durationMin == 0 -> "< 1 min"
                    else -> "$durationMin min active"
                }
                TimelineRowDetail(dotColor = Color(0xFF4CAF50), label = "Started", time = startStr, isGreen = true)
                Spacer(modifier = Modifier.height(10.dp))
                TimelineRowDetail(dotColor = Color(0xFF444746), label = "Completed", time = endStr, subtitle = durationLabel)
                Spacer(modifier = Modifier.height(16.dp))
                SectionDivider()
            }
        }

        // ── 13. Device info (collapsible footer) ─────────────────────────────
        item(key = "device") {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deviceInfoExpanded = !deviceInfoExpanded }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Device info",
                        fontSize = 11.sp,
                        color = Color(0xFF9E9E9E),
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (deviceInfoExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Color(0xFF9E9E9E),
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (deviceInfoExpanded) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val chipset = remember { DeviceInfo.chipset() }
                    Text(
                        buildString {
                            append("Chipset: $chipset\n")
                            append("Android API: ${Build.VERSION.SDK_INT}\n")
                            append("App version: $appVersion")
                        },
                        fontSize = 11.sp, color = Color(0xFF9E9E9E), lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeading(title: String) {
    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1F1F1F),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun SectionDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE0E0E0)))
}

@Composable
private fun DetailInfoRow(label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = Color(0xFF9E9E9E))
        value()
    }
}

@Composable
private fun DetailBullet(text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.padding(top = 6.dp).size(5.dp).background(color.copy(alpha = 0.7f), CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = Color(0xFF1F1F1F), lineHeight = 19.sp)
    }
}

@Composable
private fun TimelineRowDetail(
    dotColor: Color,
    label: String,
    time: String,
    subtitle: String? = null,
    isGreen: Boolean = false
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.padding(top = 4.dp).size(12.dp).background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F1F1F))
            Text(time, fontSize = 12.sp, color = Color(0xFF9E9E9E))
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Color(0xFF9E9E9E))
        }
    }
}

// ─── Full-screen fallback (kept for direct navigation if needed) ───────────────

@Composable
fun AssessmentDetailsScreen(
    assessmentId: String,
    repository: HealthDemoRepository,
    onNavigateBack: () -> Unit
) {
    val assessment = repository.getByIdCached(assessmentId)

    if (assessment == null) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Assessment not found.", color = Color(0xFF444746))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF1F1F1F))
            }
            Text("Case Details", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
        }
        Box(modifier = Modifier.weight(1f)) {
            AssessmentDetailSheet(assessment = assessment, onDismiss = onNavigateBack)
        }
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, NavyBlue)
            ) {
                Text("Back", color = NavyBlue, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
        }
    }
}
