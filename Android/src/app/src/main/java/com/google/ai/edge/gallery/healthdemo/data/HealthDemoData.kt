package com.google.ai.edge.gallery.healthdemo.data

import java.util.UUID

enum class PatientRole(val label: String) {
    Doctor("Doctor"),
    Nurse("Nurse"),
    MedicalOfficer("Medical Officer"),
    Midwife("Midwife"),
    Other("Other")
}

enum class DurationUnit(val label: String) {
    // Makerere v3 #8 (2026-04-24): testers reported long-standing conditions
    // (e.g. "cough for 3 months") needed finer granularity than Days/Hours.
    // Order here is what the dropdown renders via .entries — surfaced
    // longest-first so chronic cases are easy to find.
    Years("Years"),
    Months("Months"),
    Weeks("Weeks"),
    Days("Days"),
    Hours("Hours")
}

// ─── Signs & Symptoms ─────────────────────────────────────────────────────────

val CRITICAL_DANGER_SIGNS = listOf(
    "Convulsions",
    "Unable to drink/breastfeed",
    "Violent vomiting",
    "Lethargic/unresponsive",
    "Severe respiratory distress",
    "Bulging fontanelle (infant)",
    "Fever with rash",
    "Necrosis/unable to suck"
)

val WARNING_SIGNS = listOf(
    "Chest indrawing",
    "Poor skin turgor",
    "Sunken eyes",
    "Reduced feeding",
    "Prolonged capillary refill"
)

data class SignInfo(val description: String, val actionHint: String)

val SIGN_INFO: Map<String, SignInfo> = mapOf(
    "Unable to drink/breastfeed" to SignInfo(
        "Child cannot swallow or breastfeed — indicates severe illness",
        "Ensure IV access, prepare fluid resuscitation"
    ),
    "Convulsions" to SignInfo(
        "Seizure activity — high risk of airway compromise or brain injury",
        "Position patient safely, protect airway, prepare emergency medication"
    ),
    "Lethargic/unconscious" to SignInfo(
        "Abnormal conscious level — potential CNS involvement",
        "Assess AVPU scale, protect airway, urgent escalation required"
    ),
    "Severe respiratory distress" to SignInfo(
        "Laboured breathing — risk of hypoxia and respiratory failure",
        "Administer oxygen if available, position upright, escalate urgently"
    ),
    "Cardiac cyanosis" to SignInfo(
        "Central cyanosis — inadequate oxygenation of blood",
        "Administer high-flow oxygen, prepare for emergency transfer"
    ),
    "Chest indrawing" to SignInfo(
        "Subcostal or intercostal recession — indicates respiratory difficulty",
        "Monitor closely, consider pneumonia or asthma management"
    ),
    "Poor skin turgor" to SignInfo(
        "Reduced skin elasticity — consistent with dehydration",
        "Assess dehydration severity, initiate oral or IV rehydration"
    ),
    "Sunken eyes" to SignInfo(
        "Periorbital recession — consistent with moderate dehydration",
        "Initiate oral rehydration therapy, monitor fluid intake and output"
    ),
    "Reduced feeding / poor appetite" to SignInfo(
        "Reduced oral intake — may indicate systemic illness",
        "Assess feeding history, monitor weight, consider nutritional support"
    ),
    "Prolonged capillary refill" to SignInfo(
        "Capillary refill > 3 seconds — may indicate poor perfusion",
        "Assess circulation, consider IV fluid challenge if indicated"
    )
)

enum class AgeRange(val label: String) {
    Newborn("Newborn (0–28 days)"),
    Infant("Infant (1–11 months)"),
    YoungChild("Young Child (1–5 yrs)"),
    Child("Child (6–12 yrs)"),
    Adolescent("Adolescent (13–17)"),
    Adult("Adult (18–49)"),
    MatureAdult("Mature Adult (50–59)"),
    Elder("Elder (60+)")
}

fun ageGroupFromInput(years: Int, months: Int): AgeRange = when {
    years == 0 && months == 0 -> AgeRange.Newborn
    years == 0              -> AgeRange.Infant
    years <= 5              -> AgeRange.YoungChild
    years <= 12             -> AgeRange.Child
    years <= 17             -> AgeRange.Adolescent
    years <= 49             -> AgeRange.Adult
    years <= 59             -> AgeRange.MatureAdult
    else                    -> AgeRange.Elder
}

enum class Sex(val label: String) {
    Female("Female"),
    Male("Male")
}

data class VitalSigns(
    val temperature: String = "",
    val heartRate: String = "",
    val respiratoryRate: String = "",
    // Renamed from bloodLoss → bloodPressure (Makerere #2, 2026-04-24).
    // Stored as a free-text field so BP can be entered as "120/80" or just
    // systolic. Legacy rows with a `bloodLoss` JSON key are migrated into
    // this field at deserialisation time — see HealthTypeConverters.toVitalSigns.
    val bloodPressure: String = "",
    val spO2: String = ""
)

enum class TraditionalMedicine(val label: String) {
    // Makerere v2 #4 (2026-04-24): dropped "Unknown" per clinical review —
    // testers said the option encouraged skipping the question. Yes/No
    // with a description field below forces a choice and captures the detail
    // that matters for drug-interaction risk.
    Yes("Yes"),
    No("No")
}

/**
 * Represents the full guidance result, matching the wireframe layout:
 * - Possible Condition (primary + optional differentials)
 * - Suggested Treatment (bullet list)
 * - Recommended Next Steps (bullet list)
 *
 * 2026-04-24: added `possibleConditions` to carry a ranked differential
 * list. The legacy `possibleCondition` scalar is kept for backward
 * compatibility (server ETL, old serialised rows, simple call sites).
 * Convention when `possibleConditions` is non-empty:
 *   possibleConditions[0] == possibleCondition   (primary, always)
 *   possibleConditions[1..2]                     (up to 2 alternates)
 * Use [differentials] when you want a list regardless of whether the
 * row was written before or after the migration — it falls back to
 * [possibleCondition] for legacy rows where `possibleConditions` is
 * empty but `possibleCondition` is set.
 */
data class HealthGuidance(
    val possibleCondition: String,
    val suggestedTreatment: List<String>,
    val recommendedNextSteps: List<String>,
    val disclaimer: String = "This guidance does not replace clinical judgment.",
    val triageLevel: String = "",
    val confidence: String = "",
    val redFlags: List<String> = emptyList(),
    val whyItMatters: String = "",
    val possibleConditions: List<String> = emptyList(),
) {
    /**
     * The condition(s) to display. For new rows this is `possibleConditions`.
     * For legacy rows (Gson-deserialised before the field existed) this
     * falls back to `[possibleCondition]` so UI code can treat every row
     * uniformly. Empty only if both inputs are blank.
     */
    val differentials: List<String>
        get() = when {
            possibleConditions.isNotEmpty() -> possibleConditions
            possibleCondition.isNotBlank() -> listOf(possibleCondition)
            else -> emptyList()
        }
}

// ─── Pause / Resume ──────────────────────────────────────────────────────────

enum class PauseReason(val label: String) {
    NeedsLabTests("Patient needs lab tests"),
    WaitingSeniorClinician("Waiting for senior clinician"),
    CollectMedication("Patient went to collect medication"),
    EquipmentUnavailable("Equipment not available"),
    CrisisStabilisation("Patient crisis need stabilisation"),
    Other("Other")
}

data class PausedConsultation(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val role: PatientRole,
    val customRole: String = "",
    val symptoms: String,
    val durationValue: String = "",
    val durationUnit: DurationUnit = DurationUnit.Days,
    val ageYears: String = "",
    val ageMonths: String = "",
    val age: AgeRange? = null,
    val sex: Sex? = null,
    val vitalSigns: VitalSigns = VitalSigns(),
    val checkedSigns: Set<String> = emptySet(),
    val confirmedSigns: Set<String> = emptySet(),
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
    val sessionStartTime: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val role: PatientRole,
    val customRole: String = "",
    val symptoms: String,
    val durationValue: String = "",
    val durationUnit: DurationUnit = DurationUnit.Days,
    val ageYears: String = "",
    val ageMonths: String = "",
    val age: AgeRange?,
    val sex: Sex?,
    val vitalSigns: VitalSigns = VitalSigns(),
    val confirmedSigns: Set<String> = emptySet(),
    val traditionalMedicine: TraditionalMedicine? = null,
    // Makerere v2 #4: free-text detail captured when traditionalMedicine=Yes.
    // Empty string for No and for legacy rows pre-migration 5→6.
    val traditionalMedicineDetails: String = "",
    val treatmentAdministered: String = "",
    val guidance: HealthGuidance,
    val clinicianConfirmation: ClinicianConfirmation? = null,
    val referralInfo: ReferralInfo? = null,
    // Location — rounded to 2 decimal places (~1.1km) for privacy
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyMeters: Float? = null,
    val district: String? = null,
    // Wall-clock ms from kicking off MedGemma inference to receiving the
    // structured guidance. Includes any retries inside
    // runMedGemmaInferenceWithRetry. Null on rows saved before
    // migration 6→7 — the field didn't exist then.
    val inferenceMs: Long? = null,
    // July 2026 pipeline note (migration 7→8):
    // 3.1 — worker flagged the AI guidance as concerning.
    val guidanceConcern: Boolean = false,
    // 3.5 — ms from inference start to the first generated token.
    val ttftMs: Long? = null,
    // 3.5 — retries before the assessment succeeded (0 = first attempt).
    val inferenceRetries: Int = 0,
)
