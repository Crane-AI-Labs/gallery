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

// ─── Pause / Resume ──────────────────────────────────────────────────────────

enum class PauseReason(val label: String) {
    SentToLab("Sent to lab"),
    ReferralPending("Referral pending"),
    AwaitingImaging("Awaiting imaging"),
    PatientUnavailable("Patient unavailable"),
    ShiftHandover("Shift handover"),
    AwaitingFamily("Awaiting family")
}

data class PausedConsultation(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val role: PatientRole,
    val customRole: String = "",
    val symptoms: String,
    val age: AgeRange? = null,
    val sex: Sex? = null,
    val vitalSigns: VitalSigns = VitalSigns(),
    val pauseReason: PauseReason? = null,
    val note: String = ""
)

// ─── Clinician Confirmation ───────────────────────────────────────────────────

enum class GuidanceUsed(val label: String) {
    Yes("Yes"),
    Partially("Partially"),
    No("No")
}

enum class FinalAction(val label: String) {
    ManagedLocally("Managed locally"),
    Referred("Referred"),
    Escalated("Escalated"),
    SupervisorConsult("Supervisor consult"),
    Other("Other")
}

enum class IssueTag(val label: String) {
    Unsafe("Unsafe"),
    Unclear("Unclear"),
    MissingDangerSign("Missing danger sign"),
    IncorrectSuggestion("Incorrect suggestion"),
    Other("Other")
}

data class ClinicianConfirmation(
    val understood: Boolean = false,
    val guidanceUsed: GuidanceUsed? = null,
    val finalAction: FinalAction? = null,
    val issueTags: List<IssueTag> = emptyList()
)

// ─── Referral ─────────────────────────────────────────────────────────────────

enum class ReferralUrgency(val label: String) {
    Emergency("Emergency"),
    Urgent("Urgent"),
    Routine("Routine")
}

enum class ReferralDestination(val label: String) {
    DistrictHospital("District Hospital"),
    RegionalHospital("Regional Hospital"),
    EmergencyServices("Emergency Services (999/112)"),
    SpecialistClinic("Specialist Clinic"),
    CommunityHealthCenter("Community Health Center"),
    OtherFacility("Other Facility")
}

enum class ReferralReason(val label: String) {
    DangerSignDetected("Danger sign detected"),
    NoImprovementExpected("No improvement expected"),
    BeyondLocalCapacity("Beyond local capacity"),
    SpecialistAssessmentNeeded("Specialist assessment needed"),
    DiagnosticImagingRequired("Diagnostic imaging required"),
    SurgicalReviewNeeded("Surgical review needed"),
    IntensiveMonitoringRequired("Intensive monitoring required")
}

data class ReferralInfo(
    val urgency: ReferralUrgency = ReferralUrgency.Routine,
    val destination: ReferralDestination? = null,
    val reasons: List<ReferralReason> = emptyList(),
    val notes: String = ""
)

// ─── Saved Assessment ─────────────────────────────────────────────────────────

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
    val guidance: HealthGuidance,
    val clinicianConfirmation: ClinicianConfirmation? = null,
    val referralInfo: ReferralInfo? = null
)
