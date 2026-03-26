package com.google.ai.edge.gallery.healthdemo.data

import java.util.UUID

enum class PatientRole(val label: String) {
    MedicalOfficer("Medical Officer"),
    Nurse("Nurse"),
    Midwife("Midwife"),
    ClinicalAssistant("Clinical Assistant"),
    CommunityHealthWorker("Community Health Worker"),
    Other("Other")
}

enum class AgeRange(val label: String) {
    Under1("Under 1 Year"),
    Yr1to5("1–5 Years"),
    Yr6to12("6–12 Years"),
    Yr13to17("13–17 Years"),
    Yr18to59("18–59 Years"),
    Yr60Plus("60+ Years")
}

enum class Sex(val label: String) {
    Female("Female"),
    Male("Male"),
    Other("Other")
}

data class VitalSigns(
    val temperature: String = "",
    val pulseRate: String = "",
    val bloodPressure: String = "",
    val respiratoryRate: String = ""
)

/**
 * Represents the full guidance result, matching the wireframe layout:
 * - Possible Condition
 * - Suggested Treatment (bullet list)
 * - Recommended Next Steps (bullet list)
 */
data class HealthGuidance(
    val possibleCondition: String,
    val suggestedTreatment: List<String>,
    val recommendedNextSteps: List<String>,
    val disclaimer: String = "This guidance does not replace clinical judgment.",
    val triageLevel: String = "",
    val confidence: String = "",
    val redFlags: List<String> = emptyList(),
)

/**
 * Represents a full patient assessment (input + output) that can be saved.
 */
data class SavedAssessment(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val role: PatientRole,
    val customRole: String = "",
    val symptoms: String,
    val age: AgeRange?,
    val sex: Sex?,
    val vitalSigns: VitalSigns = VitalSigns(),
    val guidance: HealthGuidance
)
